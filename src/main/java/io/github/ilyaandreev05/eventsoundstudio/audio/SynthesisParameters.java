package io.github.ilyaandreev05.eventsoundstudio.audio;

import java.util.Map;
import java.util.Objects;

/** Immutable, editable synthesis recipe; duration is per hit and repeats have a 33 ms gap. */
public record SynthesisParameters(SoundSynthesizer.Waveform waveform, double frequency,
                                 double amplitude, int durationMs, int repeats,
                                 double dcOffset, SoundEnvelope envelope) {
    public SynthesisParameters {
        Objects.requireNonNull(waveform, "Waveform is required");
        Objects.requireNonNull(envelope, "Envelope is required");
        if (!Double.isFinite(frequency) || frequency < 20 || frequency > 18_000)
            throw new IllegalArgumentException("Frequency must be between 20 and 18000 Hz");
        if (!Double.isFinite(amplitude) || amplitude < 0 || amplitude > .9)
            throw new IllegalArgumentException("Amplitude must be between 0 and 0.9");
        if (durationMs < 10 || durationMs > 3000 || repeats < 1 || repeats > 20)
            throw new IllegalArgumentException("Duration must be 10–3000 ms and repeats 1–20");
        if (!Double.isFinite(dcOffset) || dcOffset < -.1 || dcOffset > .1)
            throw new IllegalArgumentException("DC offset must be between -0.1 and 0.1");
    }
    /** Stored alongside the WAV, allowing the generator to be repopulated without changing legacy paths. */
    public Map<String,Object> metadata() {
        return Map.of("waveform", waveform.name(), "frequency_hz", frequency, "amplitude", amplitude,
                "duration_ms", durationMs, "repeats", repeats, "dc_offset", dcOffset,
                "envelope", Map.of("attack_ms", envelope.attackMs(), "hold_ms", envelope.holdMs(),
                        "decay_ms", envelope.decayMs(), "sustain_level", envelope.sustainLevel(),
                        "release_ms", envelope.releaseMs()));
    }
    public static SynthesisParameters fromMetadata(Map<String,Object> recipe) {
        Object rawEnvelope=recipe.get("envelope");
        if(!(rawEnvelope instanceof Map<?,?> values))throw new IllegalArgumentException("Missing synthesis envelope");
        SoundEnvelope envelope=new SoundEnvelope(number(values,"attack_ms"),number(values,"hold_ms"),
                number(values,"decay_ms"),number(values,"sustain_level"),number(values,"release_ms"));
        return new SynthesisParameters(SoundSynthesizer.Waveform.valueOf(String.valueOf(recipe.get("waveform"))),
                number(recipe,"frequency_hz"),number(recipe,"amplitude"),(int)number(recipe,"duration_ms"),
                (int)number(recipe,"repeats"),number(recipe,"dc_offset"),envelope);
    }
    private static double number(Map<?,?> values,String key){
        if(!(values.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException("Invalid synthesis "+key);
        return n.doubleValue();
    }
}
