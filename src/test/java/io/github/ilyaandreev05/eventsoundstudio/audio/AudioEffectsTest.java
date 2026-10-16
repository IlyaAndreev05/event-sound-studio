package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioEffectsTest {
    @Test void peakingEqChangesOnlyTheRenderedCopyAndBoostsItsCenterFrequency() {
        int sampleRate=48_000,frames=12_000;float[] samples=new float[frames*2];
        for(int frame=0;frame<frames;frame++){float sample=(float)(.2*Math.sin(2*Math.PI*1000*frame/sampleRate));samples[frame*2]=sample;samples[frame*2+1]=sample*.5f;}
        float[] original=samples.clone();PcmSound source=new PcmSound("tone","Tone",samples,2,sampleRate);
        PcmSound rendered=AudioEffects.applyEq(source,java.util.Map.of("enabled",true,"filters",List.of(java.util.Map.of("type","peaking","frequency_hz",1000,"gain_db",12,"q",1))));
        double before=rms(source.samples(),source.channels(),2000),after=rms(rendered.samples(),rendered.channels(),2000);
        assertTrue(after>before*3.5,"center frequency should receive the configured boost");
        assertArrayEquals(original,source.samples());assertNotSame(source.samples(),rendered.samples());assertEquals(source.channels(),rendered.channels());assertEquals(source.sampleRate(),rendered.sampleRate());
    }

    @Test void invalidNyquistAndFilterRangesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->AudioEffects.coefficients("low_pass",24_000,0,.7,48_000));
        assertThrows(IllegalArgumentException.class,()->AudioEffects.coefficients("peaking",1000,13,1,48_000));
    }

    @Test void bindingEffectsResampleRemoveDcAndApplyAttackReleaseWithoutMutatingSource(){
        int frames=100;float[] samples=new float[frames*2];for(int frame=0;frame<frames;frame++){float signal=(float)(.1*Math.sin(2*Math.PI*5*frame/1000));samples[frame*2]=.35f+signal;samples[frame*2+1]=.2f+signal*.5f;}float[] original=samples.clone();PcmSound source=new PcmSound("stereo","Stereo",samples,2,1000);
        PcmSound rendered=AudioEffects.applyBindingEffects(source,java.util.Map.of("duration_ms",50,"attack_ms",10,"release_ms",10,"remove_dc",true));
        assertEquals(50,rendered.frames());assertEquals(0f,rendered.samples()[0],1e-7);assertEquals(0f,rendered.samples()[rendered.samples().length-1],1e-7);assertTrue(rms(rendered.samples(),rendered.channels(),15)>0.01);assertArrayEquals(original,source.samples());
    }

    private static double rms(float[] samples,int channels,int startFrame){double sum=0;int count=0;for(int i=startFrame*channels;i<samples.length;i+=channels){sum+=samples[i]*samples[i];count++;}return Math.sqrt(sum/count);}
}
