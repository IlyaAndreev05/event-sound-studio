package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.Mixer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixerOutputSelectorTest {
    @TempDir Path tempDir;

    @Test
    void metadataSelectorSurvivesEnumerationOrderAndLegacyNamesMustBeUnique() {
        Mixer.Info first = info("USB output", "Acme", "USB playback", "1.0");
        Mixer.Info second = info("HDMI output", "Display", "HDMI playback", "2.0");
        String selector = MixerOutputSelector.forMixer(first);

        assertEquals("USB output", MixerOutputSelector.displayName(selector));
        assertSame(first, MixerOutputSelector.resolve(selector, new Mixer.Info[]{second, first}));
        assertThrows(IllegalArgumentException.class,
                () -> MixerOutputSelector.resolve("USB output", new Mixer.Info[]{first, info("USB output", "Other", "Other USB", "1.0")}));
    }

    @Test
    void alsaPcmSelectorUsesThePhysicalSysfsPathInsteadOfCardNumber() throws Exception {
        Path cardsFile = tempDir.resolve("cards");
        Files.writeString(cardsFile, " 2 [HDA             ]: HDA-Intel - Generic\n14 [HDA             ]: HDA-Intel - Generic\n");
        Path soundClass = tempDir.resolve("class/sound");
        Path physicalDevice = tempDir.resolve("devices/pci0000:00/0000:0b:00.3");
        Files.createDirectories(physicalDevice);
        linkCardDevice(soundClass, 2, physicalDevice);
        linkCardDevice(soundClass, 14, physicalDevice);

        String before = MixerOutputSelector.forMixer("Generic [plughw:2,1]", "ALSA", "Analog output", cardsFile, soundClass);
        String after = MixerOutputSelector.forMixer("Generic [hw:14,1]", "ALSA", "Renamed analog output", cardsFile, soundClass);

        assertTrue(MixerOutputSelector.equivalent(before, after));
        assertTrue(MixerOutputSelector.available(before, java.util.List.of(after)));
        assertFalse(MixerOutputSelector.available(before, java.util.List.of(after, after)));
        assertTrue(MixerOutputSelector.matches(before, "Generic [hw:14,1]", "ALSA", "Renamed analog output", cardsFile, soundClass));
        assertFalse(MixerOutputSelector.matches(before, "Generic [hw:14,0]", "ALSA", "Analog output", cardsFile, soundClass));
        assertEquals("Generic [plughw:2,1]", MixerOutputSelector.displayName(before));
    }

    @Test
    void oldMixerNameRemainsAvailableOnlyWhenItResolvesUnambiguously() {
        String current = "java-sound-v1|USB+output|Acme|USB+playback";

        assertTrue(MixerOutputSelector.available("USB output", java.util.List.of(current)));
        assertFalse(MixerOutputSelector.available("USB output", java.util.List.of(current,
                "java-sound-v1|USB+output|Other|USB+playback")));
    }

    private static Mixer.Info info(String name, String vendor, String description, String version) {
        return new Mixer.Info(name, vendor, description, version) {};
    }

    private static void linkCardDevice(Path soundClass, int cardIndex, Path physicalDevice) throws Exception {
        Path card = soundClass.resolve("card" + cardIndex);
        Files.createDirectories(card);
        Files.createSymbolicLink(card.resolve("device"), physicalDevice);
    }
}
