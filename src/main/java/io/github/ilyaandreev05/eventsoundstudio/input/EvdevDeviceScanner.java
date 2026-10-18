package io.github.ilyaandreev05.eventsoundstudio.input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Lists event-device identities and permissions without opening or reading event data. */
public final class EvdevDeviceScanner {
    private static final Path INPUT=Path.of("/dev/input");
    public List<Device> scan(){
        Map<Path,Device> byTarget=new LinkedHashMap<>();
        for(Path directory:List.of(INPUT.resolve("by-id"),INPUT.resolve("by-path"))){
            if(!Files.isDirectory(directory))continue;
            try(Stream<Path> paths=Files.list(directory)){
                paths.sorted().forEach(link->{
                    try{
                        if(!Files.isSymbolicLink(link))return;
                        Path target=link.toRealPath();if(!target.getFileName().toString().startsWith("event"))return;
                        Device device=toDevice(link.getFileName().toString(),target,false);
                        byTarget.putIfAbsent(target,device);
                    }catch(IOException ignored){}
                });
            }catch(IOException ignored){}
        }
        try(Stream<Path> paths=Files.list(INPUT)){
            paths.filter(p->p.getFileName().toString().matches("event[0-9]+" )).sorted().forEach(path->{
                try{Path real=path.toRealPath();byTarget.putIfAbsent(real,toDevice(path.getFileName().toString(),real,true));}catch(IOException ignored){}
            });
        }catch(IOException ignored){}
        return byTarget.values().stream().sorted(Comparator.comparing(Device::id)).toList();
    }
    private static Device toDevice(String id,Path target,boolean temporary){
        String lower=id.toLowerCase(java.util.Locale.ROOT);Kind kind=lower.contains("kbd")||lower.contains("keyboard")?Kind.KEYBOARD:lower.contains("mouse")?Kind.MOUSE:Kind.UNKNOWN;
        return new Device(id,target,kind,Files.isReadable(target),temporary);
    }
    public enum Kind { KEYBOARD, MOUSE, UNKNOWN }
    public record Device(String id,Path devicePath,Kind kind,boolean readable,boolean temporaryIdentity) {
        public String label(){return id+(temporaryIdentity?" (temporary ID)":"")+(readable?"":" · permission required");}
        public String fnSupport(){
            try{
                String bitmap=Files.readString(Path.of("/sys/class/input",devicePath.getFileName().toString(),"device/capabilities/key")).strip();
                String[] words=bitmap.split("\\s+");int bits=Long.getLong("sun.arch.data.model",64L).intValue();
                int word=words.length-1-464/bits;
                return word>=0&&new java.math.BigInteger(words[word],16).testBit(464%bits)?"supported":"unsupported by device";
            }catch(IOException|NumberFormatException ignored){return "capability unavailable";}
        }
    }
}
