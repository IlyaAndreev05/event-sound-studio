package io.github.ilyaandreev05.eventsoundstudio.input;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Opt-in evdev reader. It starts only for explicitly allowlisted device IDs and never logs values. */
public final class EvdevInputService implements AutoCloseable {
    private final EvdevDeviceScanner scanner;
    private final Map<String,Running> running=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile Consumer<RawEvent> listener=ignored->{};
    private volatile Consumer<String> status=ignored->{};
    public EvdevInputService(EvdevDeviceScanner scanner){this.scanner=scanner;}
    public synchronized void configure(boolean keyboard,boolean mouse,Set<String> allowlist,Consumer<RawEvent> listener,Consumer<String> status){
        this.listener=listener==null?ignored->{}:listener;this.status=status==null?ignored->{}:status;
        Set<String> wanted=keyboard||mouse?Set.copyOf(allowlist):Set.of();
        for(String current:new ArrayList<>(running.keySet()))if(!wanted.contains(current))stop(current);
        for(EvdevDeviceScanner.Device device:scanner.scan()){
            boolean kindAllowed=(device.kind()==EvdevDeviceScanner.Kind.KEYBOARD&&keyboard)||(device.kind()==EvdevDeviceScanner.Kind.MOUSE&&mouse)||(device.kind()==EvdevDeviceScanner.Kind.UNKNOWN&&(keyboard||mouse));
            if(!kindAllowed||!wanted.contains(device.id())||running.containsKey(device.id()))continue;
            if(!device.readable()){this.status.accept("permissionRequired:"+device.id());continue;}
            Running task=new Running(device);running.put(device.id(),task);task.thread=Thread.ofVirtual().name("ess-evdev-"+sanitize(device.id())).start(()->readLoop(task));
        }
        if(wanted.isEmpty())this.status.accept("Global input is off. Enable it and choose specific devices to start.");
        else if(!running.isEmpty())this.status.accept("Monitoring "+running.size()+" explicitly selected input device(s).");
        else this.status.accept("No selected input device is readable. Check per-device permissions.");
    }
    private void readLoop(Running task){
        int wordBytes=Long.getLong("sun.arch.data.model",64L)==64?8:4;int timevalBytes=wordBytes*2;int eventSize=timevalBytes+8;
        ByteBuffer record=ByteBuffer.allocateDirect(eventSize).order(ByteOrder.nativeOrder());
        while(!closed.get()&&!task.stop.get()){
            try(FileChannel channel=FileChannel.open(task.device.devicePath(),StandardOpenOption.READ)){
                task.channel.set(channel);record.clear();
                while(!closed.get()&&!task.stop.get()){
                    int count=channel.read(record);
                    if(count<0)throw new IOException("Device closed");
                    if(record.hasRemaining())continue;
                    record.flip();record.position(timevalBytes);
                    int type=Short.toUnsignedInt(record.getShort());int code=Short.toUnsignedInt(record.getShort());int value=record.getInt();
                    record.clear();
                    if(type!=0)this.listener.accept(new RawEvent(task.device.id(),type,code,value,System.nanoTime()));
                }
            }catch(Exception e){
                task.channel.set(null);if(!closed.get()&&!task.stop.get()){
                    this.listener.accept(new RawEvent(task.device.id(),-1,0,0,System.nanoTime()));
                    this.status.accept("sourceUnavailable:"+task.device.id());
                    try{Thread.sleep(1000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();return;}
                    var latest=scanner.scan().stream().filter(d->d.id().equals(task.device.id())).findFirst().orElse(null);
                    if(latest!=null&&latest.readable())task.device=latest;
                }
            }
        }
    }
    private synchronized void stop(String id){Running task=running.remove(id);if(task==null)return;task.stop.set(true);FileChannel c=task.channel.get();if(c!=null)try{c.close();}catch(IOException ignored){}if(task.thread!=null)task.thread.interrupt();}
    private static String sanitize(String value){return value.replaceAll("[^A-Za-z0-9_-]","_");}
    @Override public synchronized void close(){closed.set(true);for(String id:new ArrayList<>(running.keySet()))stop(id);}
    public record RawEvent(String deviceId,int type,int code,int value,long timestampNanos){}
    private static final class Running{volatile EvdevDeviceScanner.Device device;final AtomicBoolean stop=new AtomicBoolean();final AtomicReference<FileChannel> channel=new AtomicReference<>();volatile Thread thread;Running(EvdevDeviceScanner.Device d){device=d;}}
}
