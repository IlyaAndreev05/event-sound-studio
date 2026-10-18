package io.github.ilyaandreev05.eventsoundstudio.input;

import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregate wheel-only telemetry: never records key codes, text, timestamps or event history. */
public final class InputDiagnostics {
    public record Totals(long rawHighResolution,long rawLegacy,long detents,long acceptedImpulses) {
        Totals subtract(Totals other){return new Totals(rawHighResolution-other.rawHighResolution,rawLegacy-other.rawLegacy,detents-other.detents,acceptedImpulses-other.acceptedImpulses);}
    }
    public record Snapshot(Map<String,Map<String,Totals>> devices,long receivedEvents) {}
    public record Calibration(String deviceId,int physicalDetents,Map<String,Totals> axes,boolean active) {}
    private final Map<String,Map<String,long[]>> totals=new LinkedHashMap<>();
    private String calibrationDevice;
    private int physicalDetents;
    private Map<String,Totals> baseline=Map.of();
    private long receivedEvents;
    synchronized void received(){receivedEvents++;}
    private Calibration completed;
    synchronized void raw(String device,String axis,boolean high,int value){counter(device,axis)[high?0:1]+=value;}
    synchronized void detent(String device,String axis,boolean accepted){long[] count=counter(device,axis);count[2]++;if(accepted)count[3]++;}
    public synchronized Snapshot snapshot(){Map<String,Map<String,Totals>> copy=new LinkedHashMap<>();totals.forEach((device,axes)->copy.put(device,copy(axes)));return new Snapshot(Map.copyOf(copy),receivedEvents);}
    public synchronized void beginCalibration(String device,int detents){
        if(device==null||device.isBlank()||!(detents==1||detents==5||detents==10))throw new IllegalArgumentException("Choose a device and 1, 5 or 10 physical detents");
        calibrationDevice=device;physicalDetents=detents;baseline=copy(totals.getOrDefault(device,Map.of()));completed=null;
    }
    public synchronized Calibration calibration(){
        if(calibrationDevice==null)return completed;
        Map<String,Totals> delta=new LinkedHashMap<>();copy(totals.getOrDefault(calibrationDevice,Map.of())).forEach((axis,value)->delta.put(axis,value.subtract(baseline.getOrDefault(axis,new Totals(0,0,0,0)))));
        return new Calibration(calibrationDevice,physicalDetents,Map.copyOf(delta),true);
    }
    public synchronized Calibration endCalibration(){Calibration current=calibration();if(current!=null)completed=new Calibration(current.deviceId(),current.physicalDetents(),current.axes(),false);calibrationDevice=null;baseline=Map.of();return completed;}
    private long[] counter(String device,String axis){return totals.computeIfAbsent(device,ignored->new LinkedHashMap<>()).computeIfAbsent(axis,ignored->new long[4]);}
    private static Map<String,Totals> copy(Map<String,long[]> axes){Map<String,Totals> copy=new LinkedHashMap<>();axes.forEach((axis,v)->copy.put(axis,new Totals(v[0],v[1],v[2],v[3])));return Map.copyOf(copy);}
}
