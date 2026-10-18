package io.github.ilyaandreev05.eventsoundstudio.audio;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline, per-binding PCM effects. Processing runs while a config snapshot is built, never in the audio thread. */
public final class AudioEffects {
    public static final Set<String> FILTER_TYPES=Set.of("peaking","notch","low_shelf","high_shelf","high_pass","low_pass","band_pass");
    private AudioEffects(){}

    public static PcmSound applyEq(PcmSound source,Map<String,Object> eq){
        if(source==null||eq==null||!Boolean.TRUE.equals(eq.get("enabled"))||!(eq.get("filters") instanceof List<?> filters)||filters.isEmpty())return source;
        float[] samples=source.samples().clone();
        for(Object item:filters){
            if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("EQ filter must be a mapping");
            String type=string(raw.get("type"));double frequency=number(raw.get("frequency_hz")),gain=number(raw.get("gain_db")),q=number(raw.get("q"));
            Coefficients c=coefficients(type,frequency,gain,q,source.sampleRate());int channels=source.channels();
            for(int channel=0;channel<channels;channel++){
                double z1=0,z2=0;
                for(int frame=0;frame<source.frames();frame++){
                    int index=frame*channels+channel;double input=samples[index];double output=c.b0()*input+z1;
                    z1=c.b1()*input-c.a1()*output+z2;z2=c.b2()*input-c.a2()*output;samples[index]=(float)output;
                }
            }
        }
        return new PcmSound(source.id()+"-eq",source.name(),samples,source.channels(),source.sampleRate());
    }

    /** Prepares binding-local duration, DC correction, EQ, and fades without modifying the source WAV buffer. */
    public static PcmSound applyBindingEffects(PcmSound source,Map<String,Object> settings){
        if(source==null)return null;
        if(settings==null)settings=Map.of();
        int durationMs=integer(settings.get("duration_ms"),0);
        SoundEnvelope envelope=new SoundEnvelope(value(settings,"attack_ms",0),value(settings,"hold_ms",0),
                value(settings,"decay_ms",0),value(settings,"sustain_level",1),value(settings,"release_ms",0));
        double fadeMs=value(settings,"anti_click_ms",1);
        if(fadeMs<0||fadeMs>100)throw new IllegalArgumentException("Anti-click fade must be between 0 and 100 ms");
        boolean removeDc=Boolean.TRUE.equals(settings.get("remove_dc"));
        PcmSound rendered=durationMs>0?resampleDuration(source,durationMs):source;
        rendered=applyEq(rendered,map(settings.get("eq")));
        float[] samples=rendered.samples().clone();int channels=rendered.channels(),frames=rendered.frames();
        for(int frame=0;frame<frames;frame++){
            double gain=envelope.gain(frame,frames,rendered.sampleRate());
            for(int channel=0;channel<channels;channel++)samples[frame*channels+channel]*=(float)gain;
        }
        PcmPreparation.finish(samples,channels,rendered.sampleRate(),removeDc,fadeMs,.99);
        return new PcmSound(source.id()+"-binding",source.name(),samples,channels,rendered.sampleRate());
    }

    /** Linear duration resampling keeps channel alignment and returns a separate buffer. */
    public static PcmSound resampleDuration(PcmSound source,int durationMs){
        if(source==null)throw new IllegalArgumentException("Source PCM is required");
        if(durationMs<1||durationMs>30_000)throw new IllegalArgumentException("Target duration must be from 1 to 30000 ms");
        if(source.frames()==0)return new PcmSound(source.id()+"-resampled",source.name(),new float[0],source.channels(),source.sampleRate());int targetFrames=Math.max(1,(int)Math.round(durationMs*source.sampleRate()/1000.0));if(targetFrames==source.frames())return source;
        int channels=source.channels();float[] input=source.samples(),output=new float[targetFrames*channels];
        for(int frame=0;frame<targetFrames;frame++){double position=targetFrames==1||source.frames()<=1?0:frame*(source.frames()-1.0)/(targetFrames-1.0);int left=(int)position,right=Math.min(source.frames()-1,left+1);double fraction=position-left;for(int channel=0;channel<channels;channel++){float a=input[left*channels+channel],b=input[right*channels+channel];output[frame*channels+channel]=(float)(a+(b-a)*fraction);}}
        return new PcmSound(source.id()+"-resampled",source.name(),output,channels,source.sampleRate());
    }

    public static Coefficients coefficients(String type,double frequency,double gainDb,double q,int sampleRate){
        if(!FILTER_TYPES.contains(type))throw new IllegalArgumentException("Unsupported EQ filter: "+type);
        if(!Double.isFinite(frequency)||frequency<20||frequency>20_000||frequency>=sampleRate/2.0)throw new IllegalArgumentException("EQ frequency must be between 20 Hz and Nyquist");
        if(!Double.isFinite(gainDb)||gainDb< -24||gainDb>12)throw new IllegalArgumentException("EQ gain must be between -24 and 12 dB");
        if(!Double.isFinite(q)||q<.1||q>12)throw new IllegalArgumentException("EQ Q must be between 0.1 and 12");
        double omega=2*Math.PI*frequency/sampleRate,cos=Math.cos(omega),sin=Math.sin(omega),alpha=sin/(2*q);
        double b0,b1,b2,a0,a1,a2;
        switch(type){
            case "peaking"->{double a=Math.pow(10,gainDb/40);b0=1+alpha*a;b1=-2*cos;b2=1-alpha*a;a0=1+alpha/a;a1=-2*cos;a2=1-alpha/a;}
            case "notch"->{b0=1;b1=-2*cos;b2=1;a0=1+alpha;a1=-2*cos;a2=1-alpha;}
            case "band_pass"->{b0=alpha;b1=0;b2=-alpha;a0=1+alpha;a1=-2*cos;a2=1-alpha;}
            case "low_pass"->{b0=(1-cos)/2;b1=1-cos;b2=(1-cos)/2;a0=1+alpha;a1=-2*cos;a2=1-alpha;}
            case "high_pass"->{b0=(1+cos)/2;b1=-(1+cos);b2=(1+cos)/2;a0=1+alpha;a1=-2*cos;a2=1-alpha;}
            case "low_shelf"->{double a=Math.pow(10,gainDb/40),beta=2*Math.sqrt(a)*alpha;b0=a*((a+1)-(a-1)*cos+beta);b1=2*a*((a-1)-(a+1)*cos);b2=a*((a+1)-(a-1)*cos-beta);a0=(a+1)+(a-1)*cos+beta;a1=-2*((a-1)+(a+1)*cos);a2=(a+1)+(a-1)*cos-beta;}
            case "high_shelf"->{double a=Math.pow(10,gainDb/40),beta=2*Math.sqrt(a)*alpha;b0=a*((a+1)+(a-1)*cos+beta);b1=-2*a*((a-1)+(a+1)*cos);b2=a*((a+1)+(a-1)*cos-beta);a0=(a+1)-(a-1)*cos+beta;a1=2*((a-1)-(a+1)*cos);a2=(a+1)-(a-1)*cos-beta;}
            default->throw new IllegalArgumentException("Unsupported EQ filter: "+type);
        }
        return new Coefficients(b0/a0,b1/a0,b2/a0,a1/a0,a2/a0);
    }

    private static String string(Object value){return value instanceof String s?s:"";}
    private static double number(Object value){return value instanceof Number n?n.doubleValue():Double.NaN;}
    private static int integer(Object value,int fallback){return value instanceof Number n?n.intValue():fallback;}
    private static double value(Map<String,Object> settings,String key,double fallback){
        Object raw=settings.get(key);if(raw==null)return fallback;
        if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid "+key);
        return n.doubleValue();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    public record Coefficients(double b0,double b1,double b2,double a1,double a2){}
}
