package io.github.ilyaandreev05.eventsoundstudio.audio;

/** Attack/hold/decay to sustain, followed by release. Stage times are milliseconds.
 * If stages exceed the sound duration they are proportionally shortened; otherwise
 * sustain occupies the remaining time. A separate anti-click fade protects boundaries. */
public record SoundEnvelope(double attackMs, double holdMs, double decayMs,
                            double sustainLevel, double releaseMs) {
    public SoundEnvelope {
        requireTime(attackMs); requireTime(holdMs); requireTime(decayMs); requireTime(releaseMs);
        if (!Double.isFinite(sustainLevel) || sustainLevel < 0 || sustainLevel > 1)
            throw new IllegalArgumentException("Envelope sustain must be between 0 and 1");
    }
    private static void requireTime(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 30_000)
            throw new IllegalArgumentException("Envelope times must be between 0 and 30000 ms");
    }
    public static SoundEnvelope forDuration(int durationMs) {
        return new SoundEnvelope(Math.min(2, durationMs * .08), 0, durationMs * .65, .18,
                durationMs * .25);
    }
    public double gain(int frame, int frames, int sampleRate) {
        if (frames <= 1) return 0;
        double duration = (frames - 1) * 1000.0 / sampleRate;
        double total = attackMs + holdMs + decayMs + releaseMs;
        double scale = total > duration ? duration / total : 1;
        double attack = attackMs * scale, hold = holdMs * scale;
        double decay = decayMs * scale, release = releaseMs * scale;
        double time = frame * 1000.0 / sampleRate;
        if (attack > 0 && time < attack) return time / attack;
        if (time < attack + hold) return 1;
        double level = sustainLevel;
        if (decay > 0 && time < attack + hold + decay)
            level = 1 - (1 - sustainLevel) * (time - attack - hold) / decay;
        if (release > 0 && time >= duration - release)
            level *= Math.max(0, (duration - time) / release);
        return level;
    }
}
