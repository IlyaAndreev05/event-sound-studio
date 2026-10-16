package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SoundSynthesizerTest {
    @TempDir Path temp;
    @Test void allWaveformsStayFiniteAndBounded(){
        for(var shape:SoundSynthesizer.Waveform.values()){
            float[] samples=SoundSynthesizer.generate(shape,440,.2,80,2,0);
            assertTrue(samples.length>0);for(float value:samples){assertTrue(Float.isFinite(value));assertTrue(Math.abs(value)<=.91f);}
        }
    }
    @Test void generatorWritesLoadablePcmWav()throws Exception{
        Path file=temp.resolve("generated.wav");float[] samples=SoundSynthesizer.generate(SoundSynthesizer.Waveform.CHIRP,330,.15,90,1,0);
        SoundSynthesizer.writeWav(file,samples,SoundSynthesizer.SAMPLE_RATE);PcmSound loaded=SoundSynthesizer.loadWav(file,"test","Test");
        assertEquals(2,loaded.channels());assertEquals(SoundSynthesizer.SAMPLE_RATE,loaded.sampleRate());assertTrue(loaded.frames()>0);
    }
    @Test void magnitudeSpectrumFindsSineFrequencyBin(){
        int frames=2048;float[] samples=new float[frames*2];for(int i=0;i<frames;i++){float value=(float)Math.sin(2*Math.PI*1000*i/SoundSynthesizer.SAMPLE_RATE);samples[i*2]=value;samples[i*2+1]=value;}
        double[] spectrum=SoundSynthesizer.magnitudeSpectrum(new PcmSound("tone","Tone",samples,2,SoundSynthesizer.SAMPLE_RATE),0,frames);
        int peak=0;for(int i=1;i<spectrum.length;i++)if(spectrum[i]>spectrum[peak])peak=i;
        assertTrue(peak>=42&&peak<=44,"Expected the 1 kHz peak near bins 42–44, got "+peak);assertEquals(1.0,spectrum[peak],1e-9);
    }
}
