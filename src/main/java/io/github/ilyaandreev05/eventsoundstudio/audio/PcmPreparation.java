package io.github.ilyaandreev05.eventsoundstudio.audio;

/** Offline in-place finalization. A shared peak gain preserves stereo image and waveform shape. */
final class PcmPreparation {
    private PcmPreparation() {}
    static void finish(float[] samples, int channels, int sampleRate, boolean removeDc,
                       double fadeMs, double ceiling) {
        int frames = samples.length / channels;
        if (frames == 0) return;
        int fade = Math.min((frames + 1) / 2, Math.max(2, (int)Math.round(fadeMs * sampleRate / 1000)));
        double[] sums = removeDc ? new double[channels] : null;
        double weightSum = 0;
        for (int frame = 0; frame < frames; frame++) {
            double weight = fadeMs > 0 ? fade(frame, frames, fade) : 1;
            weightSum += weight;
            for (int channel = 0; channel < channels; channel++) {
                int index = frame * channels + channel;
                samples[index] = Float.isFinite(samples[index]) ? (float)(samples[index] * weight) : 0;
                if (removeDc) sums[channel] += samples[index];
            }
        }
        double peak = 0;
        for (int frame = 0; frame < frames; frame++) {
            double weight = fadeMs > 0 ? fade(frame, frames, fade) : 1;
            for (int channel = 0; channel < channels; channel++) {
                int index = frame * channels + channel;
                if (removeDc && weightSum > 0) samples[index] -= (float)(sums[channel] / weightSum * weight);
                peak = Math.max(peak, Math.abs(samples[index]));
            }
        }
        if (peak > ceiling) {
            double gain = ceiling / peak;
            for (int index = 0; index < samples.length; index++) samples[index] *= (float)gain;
        }
    }
    private static double fade(int frame, int frames, int fade) {
        return Math.max(0, Math.min(1, Math.min(frame, frames - 1 - frame) / (double)(fade - 1)));
    }
}
