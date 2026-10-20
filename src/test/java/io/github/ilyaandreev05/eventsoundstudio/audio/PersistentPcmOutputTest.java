package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
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
    @Test void linearMixGainsReachPcmWithoutCubicConversion() throws Exception {
        assertEquals(.4,EventRouter.linearGain(.5,.8,1),1e-12);
        assertEquals(.2,EventRouter.linearGain(.5,.8,.5),1e-12);
        assertEquals(0,EventRouter.linearGain(0,.8,1),0);
        CountDownLatch captured=new CountDownLatch(1);AtomicReference<Integer> sample=new AtomicReference<>();
        double gain=EventRouter.linearGain(.5,.8,1);
        try(AudioEngine engine=new AudioEngine(route->line((bytes,offset,length)->{
            for(int i=offset;i+1<offset+length;i+=2){int value=(short)((bytes[i]&255)|(bytes[i+1]<<8));if(value!=0){sample.compareAndSet(null,value);captured.countDown();break;}}
            return length;
        }))){
            engine.updateSettings(1,false,false,MixerOutputSelector.AUDIO_MATRIX_EVENTS);engine.start(null);
            float[] unity=new float[512];java.util.Arrays.fill(unity,1f);
            assertTrue(engine.tryPlay(new PcmSound("linear","Linear",unity,1,48_000),gain,0,0,1,"mix","linear-test",MixerOutputSelector.AUDIO_MATRIX_EVENTS));
            assertTrue(captured.await(2,TimeUnit.SECONDS));
            assertEquals((int)Math.round(.4*32767),sample.get().intValue(),"0.5 × 0.8 should be written as 0.4, not cubed again");
        }
    }

    @Test void outputWorkerReopensAfterAStreamWriteFails() throws Exception {
        AtomicInteger opens=new AtomicInteger();CountDownLatch recoveredWrite=new CountDownLatch(1);AtomicLong played=new AtomicLong();
        try(PcmOutputWorker worker=new PcmOutputWorker("mock-output",route->{
            if(opens.incrementAndGet()==1)return line((bytes,offset,length)->{throw new IllegalStateException("simulated disconnect");});
            return line((bytes,offset,length)->{recoveredWrite.countDown();return length;});
        },ignored->{},4,played)){
            long firstDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!worker.ready()&&System.nanoTime()<firstDeadline)Thread.sleep(5);
            assertTrue(worker.ready());assertTrue(worker.offer(new byte[]{1,0,1,0},1));
            long reopenDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(opens.get()<2&&System.nanoTime()<reopenDeadline)Thread.sleep(10);
            assertTrue(opens.get()>=2,"failed stream should be reopened");
            while(!worker.ready()&&System.nanoTime()<reopenDeadline)Thread.sleep(5);
            assertTrue(worker.ready());
            assertTrue(worker.offer(new byte[]{1,0,1,0},1));
            assertTrue(recoveredWrite.await(1,TimeUnit.SECONDS));
        }
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
