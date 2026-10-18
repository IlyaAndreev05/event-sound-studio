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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Reads only explicitly selected stable interfaces; discovery never grants permission. */
public final class EvdevInputService implements AutoCloseable {
    private final EvdevDeviceScanner scanner;
    private final Map<String,Running> running=new ConcurrentHashMap<>();
    private final Map<String,String> states=new ConcurrentHashMap<>();
    private final ScheduledExecutorService discovery=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ess-input-discovery");t.setDaemon(true);return t;});
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile Consumer<RawEvent> listener=ignored->{};
    private volatile Consumer<String> status=ignored->{};
    private volatile Set<String> allowlist=Set.of();
    private volatile boolean keyboard,mouse;
    public EvdevInputService(EvdevDeviceScanner scanner){this.scanner=scanner;discovery.scheduleWithFixedDelay(this::refresh,1,1,TimeUnit.SECONDS);}
    public void configure(boolean keyboard,boolean mouse,Set<String> allowlist,Consumer<RawEvent> listener,Consumer<String> status){
        this.listener=listener==null?ignored->{}:listener;this.status=status==null?ignored->{}:status;
        this.keyboard=keyboard;this.mouse=mouse;this.allowlist=Set.copyOf(allowlist);refresh();
    }
    public Map<String,String> statusSnapshot(){return Map.copyOf(states);}
    private void refresh(){
        if(closed.get())return;
        var found=scanner.scan();
        for(String id:new ArrayList<>(running.keySet())){
            Running task=running.get(id);
            var device=found.stream().filter(d->d.id().equals(id)).findFirst().orElse(null);
            if(device==null||!permitted(device)||!device.readable()||!device.devicePath().equals(task.device.devicePath()))stop(id);
        }
        states.clear();
        for(String id:allowlist){
            var matches=found.stream().filter(d->d.id().equals(id)).toList();
            if(matches.size()!=1){states.put(id,matches.isEmpty()?"offline":"ambiguous");continue;}
            var device=matches.getFirst();
            if(!permitted(device)){states.put(id,"disabled-or-unsupported-interface");continue;}
            if(!device.readable()){states.put(id,"permission-required");continue;}
            if(!running.containsKey(id)){
                Running task=new Running(device);running.put(id,task);
                task.thread=Thread.ofPlatform().daemon().name("ess-evdev-"+id).start(()->readLoop(task));
            }
            states.put(id,running.get(id).opened.get()?"monitoring":"opening");
        }
        status.accept(states.isEmpty()?"Global input is off; choose explicit stable devices.":"Input sources: "+states);
    }
    private boolean permitted(EvdevDeviceScanner.Device device){return allowlist.contains(device.id())&&!device.temporaryIdentity()&&((device.kind()==EvdevDeviceScanner.Kind.KEYBOARD&&keyboard)||(device.kind()==EvdevDeviceScanner.Kind.MOUSE&&mouse));}
    private void readLoop(Running task){
        int timevalBytes=Long.getLong("sun.arch.data.model",64L)==64?16:8;
        ByteBuffer record=ByteBuffer.allocateDirect(timevalBytes+8).order(ByteOrder.nativeOrder());
        try(FileChannel channel=FileChannel.open(task.device.devicePath(),StandardOpenOption.READ)){
            task.channel.set(channel);task.opened.set(true);
            if(task.stop.get())return;
            while(!closed.get()&&!task.stop.get()){
                int count=channel.read(record);if(count<0)throw new IOException("Device disconnected");
                if(record.hasRemaining())continue;
                record.flip();record.position(timevalBytes);
                int type=Short.toUnsignedInt(record.getShort()),code=Short.toUnsignedInt(record.getShort()),value=record.getInt();record.clear();
                synchronized(this){if(running.get(task.device.id())==task&&!task.stop.get())listener.accept(new RawEvent(task.device.id(),type,code,value,System.nanoTime()));}
            }
        }catch(IOException ignored){states.put(task.device.id(),"source-unavailable");}
        finally{task.opened.set(false);task.channel.set(null);synchronized(this){if(running.remove(task.device.id(),task))listener.accept(new RawEvent(task.device.id(),-1,0,0,System.nanoTime()));}}
    }
    private void stop(String id){Running task=running.remove(id);if(task==null)return;task.stop.set(true);if(task.thread!=null)task.thread.interrupt();listener.accept(new RawEvent(id,-1,0,0,System.nanoTime()));}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;discovery.shutdownNow();for(String id:new ArrayList<>(running.keySet()))stop(id);}
    public record RawEvent(String deviceId,int type,int code,int value,long timestampNanos){}
    private static final class Running {
        final EvdevDeviceScanner.Device device;
        final AtomicBoolean stop=new AtomicBoolean(),opened=new AtomicBoolean();
        final AtomicReference<FileChannel> channel=new AtomicReference<>();
        volatile Thread thread;
        Running(EvdevDeviceScanner.Device device){this.device=device;}
    }
}
