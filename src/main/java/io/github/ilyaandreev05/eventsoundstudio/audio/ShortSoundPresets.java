package io.github.ilyaandreev05.eventsoundstudio.audio;

import java.util.List;

/** Suggested starting points, not hardware calibration or locked presets. */
public final class ShortSoundPresets {
    private ShortSoundPresets() {}
    public record Preset(String id, String name, SynthesisParameters parameters) {
        @Override public String toString() { return name; }
    }
    private static Preset preset(String id,String name,SoundSynthesizer.Waveform waveform,
                                 double hz,double amplitude,int ms,int repeats) {
        return new Preset(id,name,new SynthesisParameters(waveform,hz,amplitude,ms,repeats,0,
                SoundEnvelope.forDuration(ms)));
    }
    private static final List<Preset> PRESETS = List.of(
            preset("octine-click","Left Click",SoundSynthesizer.Waveform.IMPULSE,180,.35,32,1),
            preset("octine-right-click","Right Click",SoundSynthesizer.Waveform.SINE,125,.38,45,1),
            preset("octine-middle-click","Middle Click",SoundSynthesizer.Waveform.TRIANGLE,230,.3,28,1),
            preset("octine-scroll","Scroll Detent",SoundSynthesizer.Waveform.IMPULSE,240,.18,16,1),
            preset("octine-keypress","Key Tap",SoundSynthesizer.Waveform.SINE,210,.16,20,1),
            preset("octine-function","Function Tick",SoundSynthesizer.Waveform.TRIANGLE,145,.24,30,1),
            preset("octine-technical","Technical Tick",SoundSynthesizer.Waveform.SINE,280,.17,18,1),
            preset("octine-super","Super Pulse",SoundSynthesizer.Waveform.SINE,150,.28,24,2),
            preset("octine-enter","Enter Confirm",SoundSynthesizer.Waveform.SINE,170,.32,38,1),
            preset("octine-backspace","Backspace Soft",SoundSynthesizer.Waveform.SINE,190,.17,23,1),
            preset("octine-delete","Delete Deep",SoundSynthesizer.Waveform.TRIANGLE,105,.32,42,1),
            preset("octine-workspace","Workspace Whoosh",SoundSynthesizer.Waveform.CHIRP,95,.23,120,1),
            preset("octine-notification","Notification Tap",SoundSynthesizer.Waveform.TRIANGLE,260,.22,28,2));
    public static List<Preset> all() { return PRESETS; }
}
