package io.github.ilyaandreev05.eventsoundstudio.audio;

public record PcmSound(String id, String name, float[] samples, int channels, int sampleRate) {
    public int frames() { return samples.length / Math.max(1, channels); }
}
