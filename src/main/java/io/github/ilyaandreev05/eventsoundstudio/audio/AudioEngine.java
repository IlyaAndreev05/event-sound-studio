package io.github.ilyaandreev05.eventsoundstudio.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** A dedicated PCM mixer thread. Configuration and decoded buffers are handed over as immutable snapshots. */
public final class AudioEngine implements AutoCloseable {
    private static final int BLOCK_FRAMES = 256;
    private final AudioFormat format = new AudioFormat(SoundSynthesizer.SAMPLE_RATE,16,2,true,false);
    private static final int QUEUE_CAPACITY = 1024;
    private static final int MAX_ACTIVE_VOICES = 128;
    private static final long REPEAT_GAP_FRAMES = Math.round(SoundSynthesizer.SAMPLE_RATE * .045);
    private final BlockingQueue<PlayRequest> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AudioLineProvider lineProvider;
    private final AtomicReference<RenderSettings> settings = new AtomicReference<>(new RenderSettings(.65,false,true,"system_default"));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong clippedSamples = new AtomicLong();
    private final AtomicLong droppedRequests = new AtomicLong();
    private volatile String status = "Audio engine starting";
    private volatile Consumer<String> statusListener = ignored -> {};
    private volatile Thread thread;

    public AudioEngine(){this.lineProvider=this::openLine;}
    AudioEngine(AudioLineProvider lineProvider){this.lineProvider=lineProvider;}
    public void start(Consumer<String> listener) { statusListener = listener == null ? ignored -> {} : listener; thread = Thread.ofPlatform().daemon().name("event-sound-studio-audio").start(this::run); }
    public String status() { return status; }
    public long clippedSamples() { return clippedSamples.get(); }
    public long droppedRequests() { return droppedRequests.get(); }
    public static List<String> availableOutputs() {
        return availableOutputOptions().stream().map(OutputOption::selector).toList();
    }
    public static List<OutputOption> availableOutputOptions() { return selectableOutputOptions(probeOutputs()); }
    static List<String> selectableOutputNames(List<OutputProbe> probes) {
        List<String> result = new ArrayList<>(); result.add("system_default");
        for (OutputProbe output : probes) if (output.supportsPcm48Stereo()) result.add(output.name());
        return List.copyOf(result);
    }
    static List<OutputOption> selectableOutputOptions(List<OutputProbe> probes) {
        List<OutputProbe> supported = probes.stream().filter(OutputProbe::supportsPcm48Stereo).toList();
        Map<String, Integer> identityCounts = new HashMap<>();
        for (OutputProbe output : supported) identityCounts.merge(identity(output), 1, Integer::sum);
        List<OutputProbe> unique = new ArrayList<>();
        Set<String> selectors = new LinkedHashSet<>();
        for (OutputProbe output : supported) {
            if (identityCounts.getOrDefault(identity(output), 0) == 1 && selectors.add(output.selector())) unique.add(output);
        }
        Map<String, Integer> nameCounts = new HashMap<>();
        Map<String, Integer> labelCounts = new HashMap<>();
        for (OutputProbe output : unique) nameCounts.merge(output.name(), 1, Integer::sum);
        for (OutputProbe output : unique) labelCounts.merge(optionLabel(output, nameCounts), 1, Integer::sum);
        List<OutputOption> result = new ArrayList<>();
        result.add(new OutputOption("system_default", "system_default"));
        for (OutputProbe output : unique) {
            String label = optionLabel(output, nameCounts);
            if (labelCounts.getOrDefault(label, 0) > 1) {
                String hint = MixerOutputSelector.identityHint(output.selector());
                if (!hint.isBlank()) label += " · " + hint;
            }
            result.add(new OutputOption(output.selector(), label));
        }
        return List.copyOf(result);
    }
    private static String identity(OutputProbe output) {
        String key = MixerOutputSelector.canonicalKey(output.selector());
        return key == null ? output.selector() : key;
    }
    private static String optionLabel(OutputProbe output, Map<String, Integer> nameCounts) {
        String name = output.name().isBlank() ? MixerOutputSelector.displayName(output.selector()) : output.name();
        if (nameCounts.getOrDefault(output.name(), 0) <= 1) return name;
        String detail = java.util.stream.Stream.of(output.vendor(), output.description()).filter(value -> value != null && !value.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining(" · "));
        return detail.isBlank() ? name : name + " — " + detail;
    }
    public static boolean outputAvailable(String selector, List<String> availableSelectors) {
        return MixerOutputSelector.available(selector, availableSelectors);
    }
    public static String outputDisplayName(String selector, List<OutputOption> options) {
        if (selector == null) return "";
        List<OutputOption> matches = options.stream()
                .filter(option -> MixerOutputSelector.equivalent(selector, option.selector())).toList();
        if (matches.size() == 1) return matches.getFirst().displayName();
        return MixerOutputSelector.displayName(selector);
    }
    public static List<OutputProbe> probeOutputs() {
        List<OutputProbe> result = new ArrayList<>();
        DataLine.Info requested = new DataLine.Info(SourceDataLine.class, new AudioFormat(SoundSynthesizer.SAMPLE_RATE,16,2,true,false));
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer=AudioSystem.getMixer(info);
                int sourceLineTypes=mixer.getSourceLineInfo().length;
                if (sourceLineTypes > 0) result.add(new OutputProbe(MixerOutputSelector.forMixer(info), info.getName(),
                        info.getVendor(), info.getDescription(), info.getVersion(), sourceLineTypes, mixer.isLineSupported(requested)));
            } catch (Exception ignored) { }
        }
        return List.copyOf(result);
    }
    public static String defaultOutputName() {
        try { Mixer mixer=AudioSystem.getMixer(null); return mixer==null?"unavailable":mixer.getMixerInfo().getName(); }
        catch (Exception ignored) { return "unavailable"; }
    }
    public void updateSettings(double volume, boolean mute, boolean limiter, String output) {
        settings.set(new RenderSettings(clamp(volume,0,1),mute,limiter,output == null ? "system_default" : output));
    }
    public void play(PcmSound sound, double volume, double pan, int delayMs, int repeats, String overlap) {
        play(sound, volume, pan, delayMs, repeats, overlap, sound == null ? "" : sound.id());
    }
    public void play(PcmSound sound, double volume, double pan, int delayMs, int repeats, String overlap, String voiceKey) {
        play(sound,volume,pan,delayMs,repeats,overlap,voiceKey,settings.get().output());
    }
    public void play(PcmSound sound,double volume,double pan,int delayMs,int repeats,String overlap,String voiceKey,String output){
        if (sound == null || closed.get()) return;
        String route=output==null||output.isBlank()?settings.get().output():output;
        PlayRequest request = new PlayRequest(sound, clamp(volume,0,1), clamp(pan,-1,1), Math.max(0,delayMs), Math.max(1,repeats), overlap == null ? "mix" : overlap, voiceKey == null ? sound.id() : voiceKey,route);
        if (!queue.offer(request)) droppedRequests.incrementAndGet();
    }
    public void clearVoices() { queue.clear(); queue.offer(PlayRequest.haltAll()); }

    private void run() {
        List<Voice> voices = new ArrayList<>();Map<String,OutputState> outputs=new HashMap<>();long frameClock=0;
        while (!closed.get()) {
            RenderSettings render=settings.get();
            PlayRequest request;
            while((request=queue.poll())!=null){
                PlayRequest next=request;
                if(next.stopAll()){voices.clear();continue;}
                boolean exists=voices.stream().anyMatch(v->v.voiceKey.equals(next.voiceKey()));
                if("ignore".equals(next.overlap())&&exists)continue;
                if("restart".equals(next.overlap()))voices.removeIf(v->v.voiceKey.equals(next.voiceKey()));
                if(voices.size()>=MAX_ACTIVE_VOICES){droppedRequests.incrementAndGet();continue;}
                long start=frameClock;
                if("queue".equals(next.overlap()))start=voices.stream().filter(v->v.voiceKey.equals(next.voiceKey())).mapToLong(Voice::endFrame).max().orElse(frameClock);
                voices.add(new Voice(next,start));
            }
            Set<String> neededOutputs=new HashSet<>();neededOutputs.add(render.output());voices.forEach(v->neededOutputs.add(v.output));
            outputs.keySet().removeIf(output->{if(neededOutputs.contains(output))return false;outputs.get(output).close();return true;});
            long now=System.currentTimeMillis();
            for(String route:neededOutputs){OutputState output=outputs.computeIfAbsent(route,ignored->new OutputState());if(output.line==null&&now-output.lastOpenAttempt>1500){output.lastOpenAttempt=now;try{output.open(lineProvider.open(route));announce("Audio output ready: "+(route.equals("system_default")?"system default":MixerOutputSelector.displayName(route)));}catch(Exception e){announce("Audio output unavailable ("+MixerOutputSelector.displayName(route)+"): "+safe(e));}}}
            for(OutputState output:outputs.values())Arrays.fill(output.mix,0);
            for(int frame=0;frame<BLOCK_FRAMES;frame++){
                long absoluteFrame=frameClock+frame;
                for(int v=voices.size()-1;v>=0;v--){
                    Voice voice=voices.get(v);
                    OutputState route=outputs.get(voice.output);if(route==null||route.line==null)continue;
                    if(absoluteFrame<voice.startFrame)continue;
                    if(voice.position>=voice.sound.frames()){
                        if(voice.repeatsLeft>1){voice.repeatsLeft--;voice.position=0;voice.startFrame=absoluteFrame+REPEAT_GAP_FRAMES;continue;}
                        voices.remove(v);continue;
                    }
                    int channel=voice.sound.channels()==1?0:voice.position*voice.sound.channels();
                    float left=voice.sound.samples()[channel]; float right=voice.sound.channels()==1?left:voice.sound.samples()[channel+1];
                    double leftPan=voice.pan>0?1-voice.pan:1; double rightPan=voice.pan<0?1+voice.pan:1;
                    float gain=(float)(voice.gain*(render.mute()?0:render.volume()));
                    route.mix[frame*2]+=left*gain*(float)leftPan; route.mix[frame*2+1]+=right*gain*(float)rightPan; voice.position++;
                }
            }
            for(OutputState route:outputs.values())if(route.line!=null){
                for(int i=0;i<route.mix.length;i++){
                    float sample=route.mix[i]; if(Math.abs(sample)>1f)clippedSamples.incrementAndGet();
                    if(render.limiter()) sample=(float)(Math.tanh(sample*1.15)/Math.tanh(1.15)); else sample=Math.max(-1,Math.min(1,sample));
                    int value=Math.round(sample*32767); route.output[i*2]=(byte)value;route.output[i*2+1]=(byte)(value>>8);
                }
                try{route.line.write(route.output,0,route.output.length);}catch(Exception e){route.close();announce("Audio output lost: "+safe(e));}
            }
            frameClock+=BLOCK_FRAMES;
            if(outputs.values().stream().noneMatch(o->o.line!=null)){try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        }
        outputs.values().forEach(OutputState::close);
        announce("Audio engine stopped");
    }
    private SourceDataLine openLine(String output)throws Exception{
        DataLine.Info info=new DataLine.Info(SourceDataLine.class,format); SourceDataLine line;
        if(output!=null&&!output.isBlank()&&!output.equals("system_default")){
            Mixer.Info match=MixerOutputSelector.resolve(output, AudioSystem.getMixerInfo());
            line=(SourceDataLine)AudioSystem.getMixer(match).getLine(info);
        }else line=(SourceDataLine)AudioSystem.getLine(info);
        line.open(format,BLOCK_FRAMES*4*8);line.start();return line;
    }
    private void announce(String value){status=value;try{statusListener.accept(value);}catch(RuntimeException ignored){}}
    private static String safe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static double clamp(double x,double min,double max){return Math.max(min,Math.min(max,x));}
    @Override public void close(){if(closed.compareAndSet(false,true)){queue.clear();if(thread!=null)thread.interrupt();}}

    private record RenderSettings(double volume,boolean mute,boolean limiter,String output){}
    public record OutputProbe(String selector,String name,String vendor,String description,String version,int sourceLineTypes,boolean supportsPcm48Stereo){
        public OutputProbe(String name,String vendor,String version,int sourceLineTypes,boolean supportsPcm48Stereo){
            this(MixerOutputSelector.forMixer(name,vendor,"",java.nio.file.Path.of("/proc/asound/cards"),java.nio.file.Path.of("/sys/class/sound")),
                    name,vendor,"",version,sourceLineTypes,supportsPcm48Stereo);
        }
    }
    public record OutputOption(String selector,String displayName){}
    private record PlayRequest(PcmSound sound,double gain,double pan,int delayMs,int repeats,String overlap,String voiceKey,String output,boolean stopAll){
        PlayRequest(PcmSound sound,double gain,double pan,int delayMs,int repeats,String overlap,String voiceKey,String output){this(sound,gain,pan,delayMs,repeats,overlap,voiceKey,output,false);}
        static PlayRequest haltAll(){return new PlayRequest(null,0,0,0,1,"mix","","system_default",true);}
    }
    private static final class Voice{
        final PcmSound sound;final double gain,pan;final String voiceKey,output;int position,repeatsLeft;long startFrame;
        Voice(PlayRequest r,long clock){sound=r.sound();gain=r.gain();pan=r.pan();voiceKey=r.voiceKey();output=r.output();repeatsLeft=r.repeats();startFrame=clock+Math.round(SoundSynthesizer.SAMPLE_RATE*r.delayMs()/1000.0);}
        long endFrame(){return startFrame+Math.max(0,sound.frames()-position)+(long)Math.max(0,repeatsLeft-1)*(sound.frames()+REPEAT_GAP_FRAMES);}
    }
    private static final class OutputState{
        final float[] mix=new float[BLOCK_FRAMES*2];final byte[] output=new byte[BLOCK_FRAMES*4];SourceDataLine line;long lastOpenAttempt;
        void open(SourceDataLine value){line=value;}
        void close(){if(line!=null){try{line.stop();line.close();}catch(Exception ignored){}line=null;}}
    }
    @FunctionalInterface interface AudioLineProvider{SourceDataLine open(String output)throws Exception;}
}
