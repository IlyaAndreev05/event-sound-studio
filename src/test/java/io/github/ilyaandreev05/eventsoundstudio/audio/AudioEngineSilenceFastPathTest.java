package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the idle-silence fast path keeps exact PCM block, voice-frame, and limiter semantics. */
class AudioEngineSilenceFastPathTest {
    private static final int BLOCK_FRAMES=256;
    private static final int RATE=SoundSynthesizer.SAMPLE_RATE;

    @Test void finishedToneLeavesOnlyZeroBlocksAndNeverRepeatsTheLastBuffer() throws Exception {
        Recorder recorder=new Recorder();
        try(AudioEngine engine=new AudioEngine(recorder::line)){
            engine.updateSettings(1,false,false,MixerOutputSelector.AUDIO_MATRIX_EVENTS);engine.start(null);
            await("event output ready",()->engine.diagnostics().outputs().stream().anyMatch(AudioEngine.OutputDiagnostics::ready));
            engine.play(tone(300,.5f),1,0,0,1,"mix","tone",MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            await("the tone to reach PCM",()->firstNonZeroFrame(recorder.blocks(MixerOutputSelector.AUDIO_MATRIX_EVENTS))>=0);
            await("zero blocks after the tone",()->trailingZeroBlocks(recorder.blocks(MixerOutputSelector.AUDIO_MATRIX_EVENTS))>=8);
            List<byte[]> blocks=recorder.blocks(MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            int lastNonZero=-1;
            for(int i=0;i<blocks.size();i++)if(!allZero(blocks.get(i)))lastNonZero=i;
            assertTrue(lastNonZero>=0&&lastNonZero<blocks.size()-1,"the tone must end while silence keeps flowing");
            for(int i=lastNonZero+1;i<blocks.size();i++)assertTrue(allZero(blocks.get(i)),"block "+i+" after the tone must be exact silence");
            assertFalse(Arrays.equals(blocks.get(lastNonZero),blocks.get(blocks.size()-1)),"a finished buffer must not be replayed");
            assertEquals(0,engine.clippedSamples());assertEquals(0,engine.droppedRequests());
        }
    }

    @Test void delayedRepeatedVoiceKeepsItsExactStartEndAndRepeatGapFrames() throws Exception {
        Recorder recorder=new Recorder();
        try(AudioEngine engine=new AudioEngine(recorder::line)){
            engine.updateSettings(1,false,false,MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            int frames=100;
            engine.play(tone(frames,.5f),1,0,300,2,"mix","repeated","delayed-route");
            engine.play(tone(frames,.5f),1,0,400,1,"mix","later","delayed-route");
            engine.start(null);
            await("all delayed runs in PCM",()->runs(recorder.blocks("delayed-route")).size()==3&&trailingZeroBlocks(recorder.blocks("delayed-route"))>=3);
            List<Run> runs=runs(recorder.blocks("delayed-route"));
            assertEquals(3,runs.size());
            long first=runs.get(0).start();
            assertTrue(first>0,"a delayed voice must not start at stream frame zero");
            assertEquals(frames,runs.get(0).end()-first,"the first delayed run keeps its sound length");
            assertEquals(frames,runs.get(1).end()-runs.get(1).start(),"the repeat keeps its sound length");
            assertEquals(frames,runs.get(2).end()-runs.get(2).start(),"the later delayed voice keeps its sound length");
            assertEquals(frames+2160,runs.get(1).start()-first,"the repeat gap must stay 2160 frames");
            assertEquals(4800,runs.get(2).start()-first,"a 100 ms later delay must shift the start by 4800 frames");
            assertEquals(0,engine.droppedRequests(),"idle silence must not consume the drop budget");
        }
    }

    @Test void outputSelectorsNeverShareBuffersOrReplayStaleAudio() throws Exception {
        Recorder recorder=new Recorder();
        try(AudioEngine engine=new AudioEngine(recorder::line)){
            engine.updateSettings(1,false,false,MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            engine.play(tone(1,0f),1,0,0,1,"mix","warm-a","selector-a");
            engine.play(tone(1,0f),1,0,0,1,"mix","warm-b","selector-b");
            engine.start(null);
            await("both selectors ready",()->engine.diagnostics().outputs().stream().filter(output->output.selector().startsWith("selector-")).count()==2&&engine.diagnostics().outputs().stream().allMatch(AudioEngine.OutputDiagnostics::ready));
            engine.play(tone(200,.5f),1,0,0,1,"mix","a-tone","selector-a");
            await("selector A tone",()->firstNonZeroFrame(recorder.blocks("selector-a"))>=0);
            await("selector A drained",()->trailingZeroBlocks(recorder.blocks("selector-a"))>=4);
            engine.play(tone(200,.25f),1,0,0,1,"mix","b-tone","selector-b");
            await("selector B tone",()->firstNonZeroFrame(recorder.blocks("selector-b"))>=0);
            await("selector B drained",()->trailingZeroBlocks(recorder.blocks("selector-b"))>=4);
            assertEquals(16384,firstNonZeroSample(recorder.blocks("selector-a")),"selector A must keep its own 0.5 tone");
            assertEquals(8192,firstNonZeroSample(recorder.blocks("selector-b")),"selector B must get 0.25, not A's stale buffer");
            assertTrue(trailingZeroBlocks(recorder.blocks("selector-a"))>=4,"selector A must fall back to silence while B plays");
            assertEquals(200,countNonZeroFrames(recorder.blocks("selector-a")));
            assertEquals(200,countNonZeroFrames(recorder.blocks("selector-b")));
        }
    }

    @Test void producedBlockKeepsTheExistingLimiterEncoding() throws Exception {
        Recorder recorder=new Recorder();
        try(AudioEngine engine=new AudioEngine(recorder::line)){
            engine.updateSettings(1,false,true,MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            engine.play(tone(120,.8f),1,0,0,1,"mix","limited",MixerOutputSelector.AUDIO_MATRIX_EVENTS);engine.start(null);
            await("limited tone in PCM",()->firstNonZeroSample(recorder.blocks(MixerOutputSelector.AUDIO_MATRIX_EVENTS))!=0);
            float limited=(float)(Math.tanh(.8f*1.15)/Math.tanh(1.15));
            assertEquals(Math.round(limited*32767),firstNonZeroSample(recorder.blocks(MixerOutputSelector.AUDIO_MATRIX_EVENTS)),"the produced path must keep tanh limiter/encode semantics");
            assertEquals(0,engine.clippedSamples());
        }
    }

    private static PcmSound tone(int frames,float amplitude){
        float[] samples=new float[frames];Arrays.fill(samples,amplitude);
        return new PcmSound("tone","Tone",samples,1,RATE);
    }
    private static void await(String what,BooleanSupplier condition)throws InterruptedException{
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)fail("timed out waiting for "+what);Thread.sleep(2);}
    }
    private static List<byte[]> snapshot(Recorder recorder,String route){
        List<byte[]> blocks=recorder.blocks.get(route);if(blocks==null)return List.of();
        synchronized(blocks){return List.copyOf(blocks);}
    }
    private static boolean allZero(byte[] block){for(byte value:block)if(value!=0)return false;return true;}
    private static boolean frameNonZero(byte[] block,int frame){int index=frame*4;return block[index]!=0||block[index+1]!=0||block[index+2]!=0||block[index+3]!=0;}
    private static int firstNonZeroFrame(byte[] block){for(int frame=0;frame<block.length/4;frame++)if(frameNonZero(block,frame))return frame;return -1;}
    private static int firstNonZeroFrame(List<byte[]> blocks){for(byte[] block:blocks)if(firstNonZeroFrame(block)>=0)return firstNonZeroFrame(block);return -1;}
    private static int firstNonZeroSample(List<byte[]> blocks){
        for(byte[] block:blocks)for(int frame=0;frame<block.length/4;frame++)if(frameNonZero(block,frame))return (short)((block[frame*4]&255)|(block[frame*4+1]<<8));
        return 0;
    }
    private static int countNonZeroFrames(List<byte[]> blocks){int count=0;for(byte[] block:blocks)for(int frame=0;frame<block.length/4;frame++)if(frameNonZero(block,frame))count++;return count;}
    private static int trailingZeroBlocks(List<byte[]> blocks){int count=0;for(int index=blocks.size()-1;index>=0&&allZero(blocks.get(index));index--)count++;return count;}
    private record Run(long start,long end){}
    private static List<Run> runs(List<byte[]> blocks){
        List<Run> result=new ArrayList<>();long base=0,start=0;boolean inRun=false;
        for(byte[] block:blocks){
            for(int frame=0;frame<block.length/4;frame++){
                long absolute=base+frame;boolean nonzero=frameNonZero(block,frame);
                if(nonzero&&!inRun){inRun=true;start=absolute;}
                else if(!nonzero&&inRun){inRun=false;result.add(new Run(start,absolute));}
            }
            base+=block.length/4;
        }
        if(inRun)result.add(new Run(start,base));
        return result;
    }

    private static final class Recorder {
        private final Map<String,List<byte[]>> blocks=new ConcurrentHashMap<>();
        SourceDataLine line(String route){
            List<byte[]> target=blocks.computeIfAbsent(route,ignored->Collections.synchronizedList(new ArrayList<>()));
            return (SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
                if(method.getName().equals("write")&&args!=null&&args.length==3){
                    byte[] bytes=(byte[])args[0];int offset=(int)args[1],length=(int)args[2];
                    target.add(Arrays.copyOfRange(bytes,offset,offset+length));return length;
                }
                if(method.getName().equals("toString"))return "RecorderLine("+route+")";
                Class<?> type=method.getReturnType();
                if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;
                if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==byte.class)return (byte)0;
                if(type==short.class)return (short)0;if(type==char.class)return (char)0;return null;
            });
        }
        List<byte[]> blocks(String route){return snapshot(this,route);}
    }
}
