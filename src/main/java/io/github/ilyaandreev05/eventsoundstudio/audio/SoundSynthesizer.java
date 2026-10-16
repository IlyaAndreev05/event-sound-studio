package io.github.ilyaandreev05.eventsoundstudio.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public final class SoundSynthesizer {
    public static final int SAMPLE_RATE = 48_000;
    private SoundSynthesizer() {}
    public enum Waveform { SINE, SQUARE, TRIANGLE, NOISE, CHIRP, SWEEP, IMPULSE }

    public static float[] generate(Waveform waveform, double frequency, double amplitude, int durationMs, int repeats, double dcOffset) {
        frequency = clamp(frequency, 20, 18_000); amplitude = clamp(amplitude, 0, 0.9); dcOffset = clamp(dcOffset, -0.1, 0.1);
        durationMs = (int) clamp(durationMs, 10, 3000); repeats = (int) clamp(repeats, 1, 20);
        int frames = SAMPLE_RATE * durationMs / 1000, gap = SAMPLE_RATE / 30;
        float[] out = new float[(frames * repeats) + gap * Math.max(0, repeats - 1)];
        Random random = new Random(0x455353L + waveform.ordinal() * 31L + durationMs);
        for (int rep = 0; rep < repeats; rep++) {
            int offset = rep * (frames + gap);
            for (int frame = 0; frame < frames; frame++) {
                double progress = frame / (double) Math.max(1, frames - 1);
                double phaseFrequency = switch (waveform) {
                    case CHIRP, SWEEP -> frequency + frequency * 2.0 * progress;
                    default -> frequency;
                };
                double t = frame / (double) SAMPLE_RATE;
                double phase = 2 * Math.PI * (waveform == Waveform.CHIRP || waveform == Waveform.SWEEP
                        ? frequency * t + frequency * t * t / Math.max(durationMs / 1000.0, .001)
                        : phaseFrequency * t);
                double raw = switch (waveform) {
                    case SINE, CHIRP, SWEEP -> Math.sin(phase);
                    case SQUARE -> Math.sin(phase) >= 0 ? 1 : -1;
                    case TRIANGLE -> 2 * Math.abs(2 * ((phase / (2 * Math.PI)) - Math.floor(phase / (2 * Math.PI) + .5))) - 1;
                    case NOISE -> random.nextDouble(-1, 1);
                    case IMPULSE -> frame < Math.max(1, frames / 12) ? 1 - frame / (double) Math.max(1, frames / 12) : Math.sin(phase) * Math.exp(-progress * 18);
                };
                double attack = Math.min(1, frame / Math.max(1, SAMPLE_RATE * .003));
                double release = Math.min(1, (frames - frame) / (double) Math.max(1, SAMPLE_RATE * .008));
                double envelope = attack * release * Math.exp(-progress * (waveform == Waveform.IMPULSE ? 4 : 2.5));
                out[offset + frame] = (float) clamp((raw * envelope * amplitude) + dcOffset * envelope, -0.99, 0.99);
            }
        }
        return out;
    }

    public static void writeWav(Path path, float[] monoSamples, int sampleRate) throws IOException {
        writeWav(path, monoSamples, sampleRate, 1);
    }

    public static void writeWav(Path path, float[] samples, int sampleRate, int channels) throws IOException {
        if (channels != 1 && channels != 2) throw new IllegalArgumentException("WAV must be mono or stereo");
        if (sampleRate < 8_000 || sampleRate > 192_000) throw new IllegalArgumentException("Unsupported sample rate");
        if (samples.length % channels != 0) throw new IllegalArgumentException("Interleaved PCM sample count does not match channel count");
        long dataBytesLong = (long) samples.length * 2;
        if (dataBytesLong > Integer.MAX_VALUE - 44) throw new IllegalArgumentException("WAV is too large");
        int dataBytes = (int) dataBytesLong;
        int blockAlign = channels * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + dataBytes).put(new byte[]{'W','A','V','E'});
        wav.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)channels).putInt(sampleRate);
        wav.putInt(sampleRate * blockAlign).putShort((short)blockAlign).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        for (float sample : samples) wav.putShort((short) Math.round(clamp(sample, -1, 1) * Short.MAX_VALUE));
        Files.write(path, wav.array());
    }

    public static PcmSound loadWav(Path path, String id, String name) throws IOException, UnsupportedAudioFileException {
        AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(path.toFile());
        try (AudioInputStream in = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat source = in.getFormat();
            requireSupportedWav(fileFormat.getType(), source);
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 2, 4, SAMPLE_RATE, false);
            if (!AudioSystem.isConversionSupported(target, source)) throw new UnsupportedAudioFileException("Cannot convert " + source + " to PCM 48 kHz stereo");
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                pcm.transferTo(bytes);
                byte[] data = bytes.toByteArray();
                float[] samples = new float[data.length / 2];
                ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                for (int i=0;i<samples.length;i++) samples[i] = buffer.getShort() / 32768f;
                return new PcmSound(id, name, samples, 2, SAMPLE_RATE);
            }
        }
    }

    public static PcmSound loadWav(byte[] bytes, String id, String name) throws IOException, UnsupportedAudioFileException {
        AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(new ByteArrayInputStream(bytes));
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            AudioFormat source = in.getFormat();
            requireSupportedWav(fileFormat.getType(), source);
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 2, 4, SAMPLE_RATE, false);
            if (!AudioSystem.isConversionSupported(target, source)) throw new UnsupportedAudioFileException("Cannot convert " + source + " to PCM 48 kHz stereo");
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                pcm.transferTo(data); byte[] raw = data.toByteArray(); float[] samples = new float[raw.length/2];
                ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN); for(int i=0;i<samples.length;i++)samples[i]=buffer.getShort()/32768f;
                return new PcmSound(id,name,samples,2,SAMPLE_RATE);
            }
        }
    }
    /** Relative one-sided Hann-window FFT magnitude for a selected frame interval. */
    public static double[] magnitudeSpectrum(PcmSound sound,int startFrame,int endFrame){
        if(sound==null||sound.channels()<1||startFrame<0||endFrame<=startFrame||endFrame>sound.frames())return new double[0];
        int used=Math.min(2048,endFrame-startFrame);int size=64;while(size<used&&size<2048)size<<=1;
        double[] real=new double[size],imaginary=new double[size];float[] samples=sound.samples();int channels=sound.channels();
        for(int frame=0;frame<used;frame++){
            double mono=0;for(int channel=0;channel<channels;channel++)mono+=samples[(startFrame+frame)*channels+channel];mono/=channels;
            double window=used<=1?1:.5-.5*Math.cos(2*Math.PI*frame/(used-1));real[frame]=mono*window;
        }
        for(int i=1,j=0;i<size;i++){
            int bit=size>>1;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;
            if(i<j){double temp=real[i];real[i]=real[j];real[j]=temp;}
        }
        for(int length=2;length<=size;length<<=1){
            double angle=-2*Math.PI/length,stepReal=Math.cos(angle),stepImaginary=Math.sin(angle);
            for(int offset=0;offset<size;offset+=length){double wr=1,wi=0;
                for(int j=0;j<length/2;j++){
                    int even=offset+j,odd=even+length/2;double tr=wr*real[odd]-wi*imaginary[odd],ti=wr*imaginary[odd]+wi*real[odd];
                    real[odd]=real[even]-tr;imaginary[odd]=imaginary[even]-ti;real[even]+=tr;imaginary[even]+=ti;
                    double nextWr=wr*stepReal-wi*stepImaginary;wi=wr*stepImaginary+wi*stepReal;wr=nextWr;
                }
            }
        }
        double[] magnitude=new double[size/2];double peak=0;
        for(int bin=0;bin<magnitude.length;bin++){magnitude[bin]=Math.hypot(real[bin],imaginary[bin]);peak=Math.max(peak,magnitude[bin]);}
        if(peak>0)for(int bin=0;bin<magnitude.length;bin++)magnitude[bin]/=peak;
        return magnitude;
    }
    private static void requireSupportedWav(AudioFileFormat.Type type, AudioFormat source) throws UnsupportedAudioFileException {
        if (!AudioFileFormat.Type.WAVE.equals(type)) throw new UnsupportedAudioFileException("Only WAV files are supported");
        if (source.getChannels() < 1 || source.getChannels() > 2) throw new UnsupportedAudioFileException("Only mono and stereo WAV files are supported");
        AudioFormat.Encoding encoding = source.getEncoding();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(encoding)
                && !AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)
                && !AudioFormat.Encoding.PCM_FLOAT.equals(encoding))
            throw new UnsupportedAudioFileException("Only uncompressed PCM WAV files are supported");
    }
    private static double clamp(double x,double low,double high){return Math.max(low,Math.min(high,x));}
}
