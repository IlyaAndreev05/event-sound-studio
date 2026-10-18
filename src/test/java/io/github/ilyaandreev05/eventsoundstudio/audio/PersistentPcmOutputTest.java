package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PersistentPcmOutputTest {
    @FunctionalInterface interface Writer { int write(byte[] bytes,int offset,int length) throws Exception; }
    private static SourceDataLine line(Writer writer) {
        return (SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
            if(method.getName().equals("write"))return writer.write((byte[])args[0],(int)args[1],(int)args[2]);
            Class<?> type=method.getReturnType();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;return null;
        });
    }
    @Test void partialWritesPreserveEveryFrameAndRejectZeroProgress() {
        AtomicInteger offset=new AtomicInteger();
        SourceDataLine shortWriter=line((bytes,start,length)->{assertEquals(offset.get(),start);offset.addAndGet(4);return 4;});
        PcmOutputWorker.writeFully(shortWriter,new byte[32]);assertEquals(32,offset.get());
        assertThrows(IllegalStateException.class,()->PcmOutputWorker.writeFully(line((bytes,start,length)->0),new byte[4]));
        assertThrows(IllegalStateException.class,()->PcmOutputWorker.writeFully(line((bytes,start,length)->1),new byte[4]));
    }
    @Test void eventStreamWritesIdleSilenceAndRepeatedStartDoesNotDuplicateIt() throws Exception {
        AtomicInteger opens=new AtomicInteger();CountDownLatch silence=new CountDownLatch(20);
        try(AudioEngine engine=new AudioEngine(route->{assertEquals(MixerOutputSelector.AUDIO_MATRIX_EVENTS,route);opens.incrementAndGet();return line((bytes,offset,length)->{for(byte value:bytes)assertEquals(0,value);silence.countDown();return length;});})){
            engine.start(null);engine.start(null);
            assertTrue(silence.await(2,TimeUnit.SECONDS));assertEquals(1,opens.get());
            assertTrue(engine.diagnostics().outputs().getFirst().writtenFrames()>0);
        }
    }
    @Test void monoPlaybackAdvancesThroughSamplesRatherThanRepeatingItsFirstSample() throws Exception {
        CountDownLatch heard=new CountDownLatch(1);
        try(AudioEngine engine=new AudioEngine(route->line((bytes,offset,length)->{
            for(int i=offset;i<offset+length;i++)if(bytes[i]!=0){heard.countDown();break;}return length;
        }))){
            engine.updateSettings(1,false,false,MixerOutputSelector.AUDIO_MATRIX_EVENTS);engine.start(null);
            float[] samples=new float[512];samples[1]=.8f;
            engine.play(new PcmSound("mono","Mono",samples,1,48_000),1,0,0,1,"mix","mono",MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            assertTrue(heard.await(2,TimeUnit.SECONDS),"nonzero mono sample after the first frame must reach PCM");
        }
    }
    @Test void blockedPreviewDoesNotStopEventPcmOrBlockProducers() throws Exception {
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),eventBlocks=new CountDownLatch(20);
        AudioEngine engine=new AudioEngine(route->line((bytes,offset,length)->{
            if(route.equals("blocked-preview")){blocked.countDown();release.await(3,TimeUnit.SECONDS);}
            else eventBlocks.countDown();return length;
        }));
        try {
            engine.start(null);float[] samples=new float[48_000];java.util.Arrays.fill(samples,.2f);
            engine.play(new PcmSound("hold","Hold",samples,1,48_000),1,0,0,1,"mix","preview","blocked-preview");
            assertTrue(blocked.await(2,TimeUnit.SECONDS));assertTrue(eventBlocks.await(2,TimeUnit.SECONDS));
            for(int i=0;i<1500;i++)engine.play(new PcmSound("tiny","Tiny",new float[]{0},1,48_000),1,0,0,1,"mix");
            assertTrue(engine.diagnostics().enqueuedRequests()>0);
        } finally { release.countDown();engine.close(); }
    }
}
