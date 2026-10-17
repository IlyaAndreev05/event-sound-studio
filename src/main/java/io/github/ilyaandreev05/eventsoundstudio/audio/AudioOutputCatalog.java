package io.github.ilyaandreev05.eventsoundstudio.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers physical playback devices and prepares stable, readable output choices. */
final class AudioOutputCatalog {
    private AudioOutputCatalog() {}

    static List<String> availableOutputs() {
        return availableOutputOptions().stream().map(AudioEngine.OutputOption::selector).toList();
    }

    static List<AudioEngine.OutputOption> availableOutputOptions() {
        return selectableOutputOptions(probeOutputs(), PipeWireEventOutput.available());
    }

    static List<AudioEngine.OutputOption> availablePreviewOutputOptions() {
        return selectableOutputOptions(probeOutputs(), false);
    }

    static List<String> selectableOutputNames(List<AudioEngine.OutputProbe> probes) {
        List<String> result = new ArrayList<>();
        result.add("system_default");
        for (AudioEngine.OutputProbe output : probes)
            if (output.supportsPcm48Stereo()) result.add(output.name());
        return List.copyOf(result);
    }

    static List<AudioEngine.OutputOption> selectableOutputOptions(List<AudioEngine.OutputProbe> probes) {
        return selectableOutputOptions(probes, false);
    }

    static List<AudioEngine.OutputOption> selectableOutputOptions(List<AudioEngine.OutputProbe> probes,
                                                                   boolean includeEvents) {
        List<AudioEngine.OutputProbe> supported = probes.stream()
                .filter(AudioEngine.OutputProbe::supportsPcm48Stereo).toList();
        Map<String, Integer> identityCounts = new HashMap<>();
        for (AudioEngine.OutputProbe output : supported) identityCounts.merge(identity(output), 1, Integer::sum);
        List<AudioEngine.OutputProbe> unique = uniqueOutputs(supported, identityCounts);
        Map<String, Integer> nameCounts = counts(unique, AudioEngine.OutputProbe::name);
        Map<String, Integer> labelCounts = counts(unique, output -> optionLabel(output, nameCounts));
        List<AudioEngine.OutputOption> result = new ArrayList<>();
        result.add(new AudioEngine.OutputOption("system_default", "system_default"));
        addNamedOutputs(result, unique, nameCounts, labelCounts);
        if (includeEvents) result.add(new AudioEngine.OutputOption(
                MixerOutputSelector.AUDIO_MATRIX_EVENTS, "Audio Matrix event input"));
        return List.copyOf(result);
    }

    private static List<AudioEngine.OutputProbe> uniqueOutputs(List<AudioEngine.OutputProbe> supported,
                                                                 Map<String, Integer> identityCounts) {
        List<AudioEngine.OutputProbe> unique = new ArrayList<>();
        Set<String> selectors = new LinkedHashSet<>();
        for (AudioEngine.OutputProbe output : supported) {
            if (identityCounts.getOrDefault(identity(output), 0) == 1 && selectors.add(output.selector()))
                unique.add(output);
        }
        return unique;
    }

    private static Map<String, Integer> counts(List<AudioEngine.OutputProbe> probes,
                                                java.util.function.Function<AudioEngine.OutputProbe, String> key) {
        Map<String, Integer> counts = new HashMap<>();
        for (AudioEngine.OutputProbe output : probes) counts.merge(key.apply(output), 1, Integer::sum);
        return counts;
    }

    private static void addNamedOutputs(List<AudioEngine.OutputOption> result,
                                        List<AudioEngine.OutputProbe> outputs,
                                        Map<String, Integer> nameCounts, Map<String, Integer> labelCounts) {
        for (AudioEngine.OutputProbe output : outputs) {
            String label = optionLabel(output, nameCounts);
            if (labelCounts.getOrDefault(label, 0) > 1) {
                String hint = MixerOutputSelector.identityHint(output.selector());
                if (!hint.isBlank()) label += " · " + hint;
            }
            result.add(new AudioEngine.OutputOption(output.selector(), label));
        }
    }

    private static String identity(AudioEngine.OutputProbe output) {
        String key = MixerOutputSelector.canonicalKey(output.selector());
        return key == null ? output.selector() : key;
    }

    private static String optionLabel(AudioEngine.OutputProbe output, Map<String, Integer> nameCounts) {
        String name = output.name().isBlank()
                ? MixerOutputSelector.displayName(output.selector()) : output.name();
        if (nameCounts.getOrDefault(output.name(), 0) <= 1) return name;
        String detail = java.util.stream.Stream.of(output.vendor(), output.description())
                .filter(value -> value != null && !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" · "));
        return detail.isBlank() ? name : name + " — " + detail;
    }

    static boolean outputAvailable(String selector, List<String> availableSelectors) {
        return MixerOutputSelector.available(selector, availableSelectors);
    }

    static String outputDisplayName(String selector, List<AudioEngine.OutputOption> options) {
        if (selector == null) return "";
        if (MixerOutputSelector.AUDIO_MATRIX_EVENTS.equals(selector)) return "Audio Matrix event input";
        List<AudioEngine.OutputOption> matches = options.stream()
                .filter(option -> MixerOutputSelector.equivalent(selector, option.selector())).toList();
        return matches.size() == 1 ? matches.getFirst().displayName() : MixerOutputSelector.displayName(selector);
    }

    static List<AudioEngine.OutputProbe> probeOutputs() {
        List<AudioEngine.OutputProbe> result = new ArrayList<>();
        DataLine.Info requested = new DataLine.Info(SourceDataLine.class,
                new AudioFormat(SoundSynthesizer.SAMPLE_RATE, 16, 2, true, false));
        for (Mixer.Info info : AudioSystem.getMixerInfo()) addOutputProbe(result, info, requested);
        return List.copyOf(result);
    }

    private static void addOutputProbe(List<AudioEngine.OutputProbe> result, Mixer.Info info,
                                       DataLine.Info requested) {
        try {
            Mixer mixer = AudioSystem.getMixer(info);
            int sourceLineTypes = mixer.getSourceLineInfo().length;
            if (sourceLineTypes > 0) result.add(new AudioEngine.OutputProbe(
                    MixerOutputSelector.forMixer(info), info.getName(), info.getVendor(),
                    info.getDescription(), info.getVersion(), sourceLineTypes, mixer.isLineSupported(requested)));
        } catch (Exception ignored) { }
    }

    static String defaultOutputName() {
        try {
            Mixer mixer = AudioSystem.getMixer(null);
            return mixer == null ? "unavailable" : mixer.getMixerInfo().getName();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
