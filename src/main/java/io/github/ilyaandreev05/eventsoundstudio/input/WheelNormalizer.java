package io.github.ilyaandreev05.eventsoundstudio.input;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** Each evdev SYN_REPORT commits a packet, so legacy-first initial packets cannot double fire. */
final class WheelNormalizer {
    private final Map<String,State> devices=new HashMap<>();
    private final InputDiagnostics diagnostics;
    WheelNormalizer(InputDiagnostics diagnostics){this.diagnostics=diagnostics;}
    synchronized void delta(String device,int code,int value){
        if(value==0||!(code==6||code==8||code==11||code==12))return;
        boolean high=code==11||code==12;String axis=code==6||code==12?"horizontal":"vertical";
        State state=devices.computeIfAbsent(device,ignored->new State());
        diagnostics.raw(device,axis,high,value);
        if(high)state.high.add(axis);
        String key=axis+":"+(value>0?"positive":"negative");
        (high?state.packetHigh:state.packetLegacy).merge(key,Math.abs((long)value),Long::sum);
    }
    synchronized void report(String device,boolean preferHigh,int units,BiPredicate<Boolean,Integer> impulse){
        State state=devices.get(device);if(state==null)return;
        for(String axis:new String[]{"vertical","horizontal"})for(int sign:new int[]{1,-1}){
            String key=axis+":"+(sign>0?"positive":"negative");
            boolean high=preferHigh&&state.high.contains(axis);
            long count;
            if(high){long total=state.remainders.getOrDefault(key,0L)+state.packetHigh.getOrDefault(key,0L);count=total/units;state.remainders.put(key,total%units);}
            else count=state.packetLegacy.getOrDefault(key,0L);
            for(long i=0;i<count;i++)diagnostics.detent(device,axis,impulse.test(axis.equals("horizontal"),sign));
        }
        state.packetHigh.clear();state.packetLegacy.clear();
    }
    synchronized void reset(String device){devices.remove(device);}
    private static final class State {
        final Set<String> high=new HashSet<>();
        final Map<String,Long> packetHigh=new HashMap<>(),packetLegacy=new HashMap<>(),remainders=new HashMap<>();
    }
}
