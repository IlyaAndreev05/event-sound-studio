package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: emits quiet pulses ONLY to audiomatrix_events; never a hardware/default preview. */
class PersistentEventOutputLiveSmokeTest {
    @Test void continuousSilenceAndQuietPulsesKeepOneEventStreamAlive() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("EVENT_SOUND_STUDIO_PERSISTENT_SMOKE")));
        int seconds=Integer.parseInt(System.getenv().getOrDefault("EVENT_SOUND_STUDIO_SMOKE_SECONDS","30"));
        assertTrue(seconds>=10&&seconds<=7200,"smoke duration must be 10..7200 seconds");
        float[] samples=new float[240];
        for(int i=0;i<samples.length;i++)samples[i]=(float)(.025*Math.sin(2*Math.PI*90*i/48_000)*Math.sin(Math.PI*i/(samples.length-1)));
        PcmSound pulse=new PcmSound("stability","Quiet stability pulse",samples,1,48_000);
        try(AudioEngine engine=new AudioEngine()){
            engine.updateSettings(.2,false,true,MixerOutputSelector.AUDIO_MATRIX_EVENTS);engine.start(null);
            long readyDeadline=System.nanoTime()+10_000_000_000L;
            while(System.nanoTime()<readyDeadline&&engine.diagnostics().outputs().stream().noneMatch(AudioEngine.OutputDiagnostics::ready))Thread.sleep(25);
            assertTrue(engine.diagnostics().outputs().stream().anyMatch(AudioEngine.OutputDiagnostics::ready),engine.diagnostics().toString());
            long initialFrames=engine.diagnostics().outputs().getFirst().writtenFrames();
            long deadline=System.nanoTime()+seconds*1_000_000_000L;long pulses=0;
            while(System.nanoTime()<deadline){
                assertTrue(engine.tryPlay(pulse,1,0,0,1,"mix","stability",MixerOutputSelector.AUDIO_MATRIX_EVENTS));pulses++;
                Thread.sleep(250);
                AudioEngine.OutputDiagnostics output=engine.diagnostics().outputs().getFirst();
                assertTrue(output.ready(),output.toString());assertEquals(0,output.restarts(),output.toString());
            }
            Thread.sleep(100);
            AudioEngine.Diagnostics diagnostics=engine.diagnostics();
            assertEquals(pulses,diagnostics.playedRequests(),diagnostics.toString());assertEquals(0,diagnostics.droppedRequests(),diagnostics.toString());
            assertTrue(diagnostics.outputs().getFirst().writtenFrames()>initialFrames+seconds*40_000L,diagnostics.toString());
            System.out.println("Persistent EVENT PCM software delivery only; hardware audibility and xruns require independent observation: "+diagnostics);
        }
    }
}
