package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SoundLibraryTest {
    @TempDir Path temp;

    @Test void wavImportKeepsStereoAndOriginalAndCopyGetsIndependentFiles() throws Exception {
        Path source=temp.resolve("stereo-source.wav");float[] stereo=new float[480*2];
        for(int frame=0;frame<480;frame++){stereo[frame*2]=.2f;stereo[frame*2+1]=-.4f;}
        SoundSynthesizer.writeWav(source,stereo,SoundSynthesizer.SAMPLE_RATE,2);byte[] original=Files.readAllBytes(source);

        try(ConfigStore store=new ConfigStore(temp.resolve("config/config.yaml"));SoundLibrary library=new SoundLibrary(store)){
            SoundLibrary.Entry imported=library.importWav(source).get(5,TimeUnit.SECONDS);
            assertEquals(2,imported.pcm().channels());assertEquals(480,imported.pcm().frames());
            assertEquals(.425,imported.pcm().samples()[20],.002);assertEquals(-.85,imported.pcm().samples()[21],.002);
            Path originalPath=store.dataDirectory().resolve(String.valueOf(imported.metadata().get("original_path")));
            assertArrayEquals(original,Files.readAllBytes(originalPath));

            SoundLibrary.Entry copy=library.copySound(imported,"Stereo copy").get(5,TimeUnit.SECONDS);
            assertNotEquals(imported.id(),copy.id());assertEquals(imported.id(),copy.metadata().get("copy_of"));
            assertEquals(2,copy.pcm().channels());assertNotEquals(imported.file(),copy.file());
            Path copiedOriginal=store.dataDirectory().resolve(String.valueOf(copy.metadata().get("original_path")));
            assertNotEquals(originalPath,copiedOriginal);assertArrayEquals(original,Files.readAllBytes(copiedOriginal));

            SoundLibrary.Entry fragment=library.saveFragment(imported,"Stereo crop",100,300).get(5,TimeUnit.SECONDS);
            assertEquals(200,fragment.pcm().frames());assertEquals(2,fragment.pcm().channels());
            assertEquals(imported.id(),fragment.metadata().get("fragment_of"));assertFalse(fragment.metadata().containsKey("original_path"));
        }
    }

    @Test void exportsTheSelectedNormalizedWavWithoutOverwritingSilently() throws Exception {
        Path source=temp.resolve("export-source.wav");SoundSynthesizer.writeWav(source,new float[]{.1f,-.2f,.3f,-.4f},SoundSynthesizer.SAMPLE_RATE);
        try(ConfigStore store=new ConfigStore(temp.resolve("export-config/config.yaml"));SoundLibrary library=new SoundLibrary(store)){
            SoundLibrary.Entry sound=library.importWav(source).get(5,TimeUnit.SECONDS);Path destination=temp.resolve("portable/exported.wav");
            library.exportWav(sound,destination,false).get(5,TimeUnit.SECONDS);
            assertArrayEquals(Files.readAllBytes(sound.file()),Files.readAllBytes(destination));
            assertThrows(java.util.concurrent.ExecutionException.class,()->library.exportWav(sound,destination,false).get(5,TimeUnit.SECONDS));
            Files.write(destination,new byte[]{9,8,7});
            library.exportWav(sound,destination,true).get(5,TimeUnit.SECONDS);
            assertArrayEquals(Files.readAllBytes(sound.file()),Files.readAllBytes(destination));
        }
    }

    @Test void exportsGeneratorResultAsStandalonePcmWav() throws Exception {
        try(ConfigStore store=new ConfigStore(temp.resolve("generator-export-config/config.yaml"));SoundLibrary library=new SoundLibrary(store)){
            Path destination=temp.resolve("outside-library/generated.wav");
            library.exportGenerated(SoundSynthesizer.Waveform.SINE,440,.08,40,1,0,destination,false).get(5,TimeUnit.SECONDS);
            PcmSound exported=SoundSynthesizer.loadWav(destination,"exported","Generated export");
            float peak=0;for(float sample:exported.samples())peak=Math.max(peak,Math.abs(sample));
            assertEquals(2,exported.channels());assertEquals(1920,exported.frames());assertTrue(peak>.02);
        }
    }
}
