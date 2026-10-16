package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AudioEngineOutputTest {
    @Test void pendingRequestsStayWithinTheBoundedQueueAndDropsAreCounted(){
        try(AudioEngine engine=new AudioEngine()){
            PcmSound sound=new PcmSound("test","Test",new float[]{.25f,.25f},1,SoundSynthesizer.SAMPLE_RATE);
            for(int i=0;i<1500;i++)engine.play(sound,1,0,0,1,"mix","stress-"+i,"system_default");
            assertEquals(476,engine.droppedRequests(),"the 1024-entry queue must count requests beyond its capacity");
        }
    }

    @Test void outputSelectorKeepsSystemDefaultAndOnlyOffersMixersReportingThePcmFormat(){
        List<String> outputs=AudioEngine.selectableOutputNames(List.of(
                new AudioEngine.OutputProbe("stereo output","vendor","version",1,true),
                new AudioEngine.OutputProbe("unsupported output","vendor","version",1,false),
                new AudioEngine.OutputProbe("capture only","vendor","version",0,false)));

        assertEquals(List.of("system_default","stereo output"),outputs);
    }

    @Test void voiceIsWrittenToItsSelectedMixerOnly() throws Exception {
        Map<String,AtomicInteger> audibleWrites=new ConcurrentHashMap<>();CountDownLatch routedAudio=new CountDownLatch(1);
        AudioEngine engine=new AudioEngine(output->fakeLine(output,audibleWrites,routedAudio));
        try{
            engine.updateSettings(1,false,false,"profile-sink");engine.start(ignored->{});
            int frames=8_000;float[] samples=new float[frames*2];for(int frame=0;frame<frames;frame++){float value=(float)(.5*Math.sin(2*Math.PI*440*frame/SoundSynthesizer.SAMPLE_RATE));samples[frame*2]=value;samples[frame*2+1]=value;}
            engine.play(new PcmSound("test","Test",samples,2,SoundSynthesizer.SAMPLE_RATE),1,0,0,1,"mix","test-binding","binding-sink");
            assertTrue(routedAudio.await(2,TimeUnit.SECONDS),"the selected binding output should receive non-silent PCM");
            assertTrue(audibleWrites.get("binding-sink").get()>0);assertEquals(0,audibleWrites.get("profile-sink").get());
        }finally{engine.close();}
    }

    @Test void lostSelectedMixerIsReportedWithoutFallingBackToProfileOutput() throws Exception {
        Map<String,AtomicInteger> audibleWrites=new ConcurrentHashMap<>();CountDownLatch outputLost=new CountDownLatch(1);
        AudioEngine engine=new AudioEngine(output->output.equals("binding-sink")?failingLine():fakeLine(output,audibleWrites,new CountDownLatch(1)));
        try{
            engine.updateSettings(1,false,false,"profile-sink");engine.start(value->{if(value.startsWith("Audio output lost:"))outputLost.countDown();});
            float[] samples=new float[512*2];java.util.Arrays.fill(samples,.5f);
            engine.play(new PcmSound("test","Test",samples,2,SoundSynthesizer.SAMPLE_RATE),1,0,0,1,"mix","test-binding","binding-sink");
            assertTrue(outputLost.await(2,TimeUnit.SECONDS),"a failed selected line should be reported");
            assertEquals(0,audibleWrites.getOrDefault("profile-sink",new AtomicInteger()).get(),"the voice must not be rerouted to the profile output");
        }finally{engine.close();}
    }

    @Test void masterMuteWritesSilenceOnlyToTheInjectedEffectsLine() throws Exception {
        CountDownLatch wroteBlock=new CountDownLatch(1);AtomicInteger nonZeroBlocks=new AtomicInteger();
        AudioEngine engine=new AudioEngine(ignored->recordingLine(wroteBlock,nonZeroBlocks));
        try{
            engine.updateSettings(1,true,false,"system_default");
            float[] samples=new float[2_000*2];java.util.Arrays.fill(samples,.5f);
            engine.play(new PcmSound("test","Test",samples,2,SoundSynthesizer.SAMPLE_RATE),1,0,0,1,"mix","test-binding","system_default");
            engine.start(ignored->{});
            assertTrue(wroteBlock.await(2,TimeUnit.SECONDS),"the injected effects output should receive a PCM block");
            assertEquals(0,nonZeroBlocks.get(),"master mute should silence only this engine's effect output");
        }finally{engine.close();}
    }

    private static SourceDataLine fakeLine(String route,Map<String,AtomicInteger> audibleWrites,CountDownLatch routedAudio){
        audibleWrites.computeIfAbsent(route,ignored->new AtomicInteger());
        return (SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
            if(method.getName().equals("write")&&args!=null&&args.length==3){byte[] bytes=(byte[])args[0];int offset=(int)args[1],length=(int)args[2];for(int i=offset;i<offset+length;i++)if(bytes[i]!=0){audibleWrites.get(route).incrementAndGet();if(route.equals("binding-sink"))routedAudio.countDown();break;}return length;}
            if(method.getName().equals("toString"))return "FakeSourceDataLine("+route+")";
            Class<?> type=method.getReturnType();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==byte.class)return (byte)0;if(type==short.class)return (short)0;if(type==char.class)return (char)0;return null;
        });
    }

    private static SourceDataLine failingLine(){
        return (SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
            if(method.getName().equals("write"))throw new IllegalStateException("simulated unplug");
            if(method.getName().equals("toString"))return "FakeSourceDataLine(unplugged)";
            Class<?> type=method.getReturnType();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==byte.class)return (byte)0;if(type==short.class)return (short)0;if(type==char.class)return (char)0;return null;
        });
    }

    private static SourceDataLine recordingLine(CountDownLatch wroteBlock,AtomicInteger nonZeroBlocks){
        return (SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
            if(method.getName().equals("write")&&args!=null&&args.length==3){byte[] bytes=(byte[])args[0];int offset=(int)args[1],length=(int)args[2];for(int i=offset;i<offset+length;i++)if(bytes[i]!=0){nonZeroBlocks.incrementAndGet();break;}wroteBlock.countDown();return length;}
            if(method.getName().equals("toString"))return "FakeSourceDataLine(recording)";
            Class<?> type=method.getReturnType();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==byte.class)return (byte)0;if(type==short.class)return (short)0;if(type==char.class)return (char)0;return null;
        });
    }
}
