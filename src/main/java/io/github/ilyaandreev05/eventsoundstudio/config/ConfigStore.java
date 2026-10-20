package io.github.ilyaandreev05.eventsoundstudio.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ConfigStore implements AutoCloseable {
    private static final TypeReference<LinkedHashMap<String,Object>> MAP_TYPE = new TypeReference<>() {};
    private final Path file;
    private final Path directory;
    private final Path legacyFile;
    private final ObjectMapper mapper;
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> daemon(r,"ess-config-watch"));
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor(r -> daemon(r,"ess-config-storage"));
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String,Object> current;
    private volatile String fingerprint;
    private volatile String diagnostic = "";
    private volatile boolean closed;
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}

    public ConfigStore(Path file) throws IOException, ConfigValidationException {
        this(activeJsonPath(file),legacyYamlPath(file));
    }
    ConfigStore(Path file,Path legacyFile) throws IOException, ConfigValidationException {
        this.file=activeJsonPath(file); this.directory=this.file.getParent();
        this.legacyFile=legacyFile==null?null:legacyFile.toAbsolutePath().normalize();
        this.mapper=new ObjectMapper();
        Files.createDirectories(directory); Files.createDirectories(directory.resolve("assets/sounds")); Files.createDirectories(directory.resolve("library"));
        installBuiltIns();
        migrateLegacyYaml();
        if(!Files.exists(this.file)) atomicWrite(mapper.writeValueAsBytes(DefaultConfig.create()));
        load(false); startWatcher();
    }
    public static Path defaultPath(){String xdg=System.getenv("XDG_CONFIG_HOME");Path base=xdg==null||xdg.isBlank()?Path.of(System.getProperty("user.home"),".config"):Path.of(xdg);return base.resolve("event-sound-studio/config.json");}
    public static Path legacyPath(){return defaultPath().resolveSibling("config.yaml");}
    private static Path activeJsonPath(Path requested){Path normalized=requested.toAbsolutePath().normalize();String name=normalized.getFileName().toString();if(name.endsWith(".json"))return normalized;if(name.endsWith(".yaml")||name.endsWith(".yml"))return normalized.resolveSibling("config.json");throw new IllegalArgumentException("Active configuration must use config.json");}
    private static Path legacyYamlPath(Path requested){Path normalized=requested.toAbsolutePath().normalize();String name=normalized.getFileName().toString();if(name.endsWith(".yaml")||name.endsWith(".yml"))return normalized;if(normalized.equals(defaultPath()))return legacyPath();return null;}
    public Path path(){return file;} public Path dataDirectory(){return directory;} public String diagnostic(){return diagnostic;}
    public Map<String,Object> snapshot(){return deep(current);}
    public synchronized VersionedSnapshot versionedSnapshot(){return new VersionedSnapshot(deep(current),fingerprint);}
    public void addListener(Consumer<Event> listener){listeners.add(listener);}
    public void removeListener(Consumer<Event> listener){listeners.remove(listener);}

    private void installBuiltIns() throws IOException {
        for(DefaultConfig.SoundAsset sound:DefaultConfig.builtInSounds()){
            Path target=directory.resolve("assets/sounds").resolve(sound.id()+".wav");
            if(Files.exists(target))continue;
            try(InputStream in=ConfigStore.class.getResourceAsStream("/assets/sounds/"+sound.id()+".wav")){
                if(in==null)throw new IOException("Missing packaged sound asset: "+sound.id());
                Path tmp=Files.createTempFile(target.getParent(),".builtin-",".wav.tmp");
                try{Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING);Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}
            }
        }
    }

    public synchronized void save(Map<String,Object> next,String expectedRevision) throws IOException, ConfigConflictException, ConfigValidationException {
        List<String> errors=ConfigValidator.validate(next);if(!errors.isEmpty())throw new ConfigValidationException(errors);
        if(expectedRevision==null||!expectedRevision.equals(fingerprint))throw new ConfigConflictException("Configuration changed since this edit began; reload before saving.");
        if(!hash(Files.readAllBytes(file)).equals(fingerprint))throw new ConfigConflictException("Configuration changed on disk; reload and resolve edits before saving.");
        byte[] data=mapper.writeValueAsBytes(next);atomicWrite(data);current=deep(next);fingerprint=hash(data);diagnostic="";publish(new Event(current,"Configuration saved"));
    }
    public CompletableFuture<Void> saveAsync(Map<String,Object> next,String expectedRevision){Map<String,Object> immutableCopy=deep(next);return CompletableFuture.runAsync(()->{try{save(immutableCopy,expectedRevision);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}},storageExecutor);}
    public synchronized void reload() throws IOException,ConfigValidationException{load(true);}
    private synchronized void load(boolean notify)throws IOException,ConfigValidationException{
        byte[] bytes=Files.readAllBytes(file);Map<String,Object> parsed;
        try{parsed=mapper.readValue(bytes,MAP_TYPE);if(parsed==null)throw new IOException("Config root must be an object");}
        catch(Exception e){diagnostic="Config could not be parsed: "+e.getMessage();throw new ConfigValidationException(List.of(diagnostic));}
        int sourceVersion=parsed.get("schema_version") instanceof Number n?n.intValue():-1;
        boolean migrated=sourceVersion>=1&&sourceVersion<ConfigMigrator.CURRENT_SCHEMA_VERSION
                &&ConfigMigrator.migrateToCurrentSchema(parsed,true,directory);
        List<String> errors=ConfigValidator.validate(parsed);if(!errors.isEmpty()){diagnostic=String.join("; ",errors);throw new ConfigValidationException(errors);}
        if(migrated){createMigrationBackup(bytes);bytes=mapper.writeValueAsBytes(parsed);atomicWrite(bytes);}
        current=deep(parsed);fingerprint=hash(bytes);diagnostic="";if(notify)publish(new Event(current,"External configuration reloaded"));
    }
    private void startWatcher(){watcherExecutor.submit(()->{
        try(var watch=FileSystems.getDefault().newWatchService()){
            directory.register(watch,java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
            while(!closed){var key=watch.take();boolean changed=false;for(var e:key.pollEvents())if(e.context() instanceof Path p&&p.equals(file.getFileName()))changed=true;key.reset();if(!changed)continue;Thread.sleep(180);
                if(diskMatchesCurrent())continue;
                try{reload();}catch(Exception e){diagnostic=e.getMessage();publish(new Event(snapshot(),"Configuration rejected: "+diagnostic));}}
        }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){diagnostic="Configuration watcher stopped: "+e.getMessage();}
    });}
    private synchronized boolean diskMatchesCurrent() throws IOException {
        return fingerprint!=null&&hash(Files.readAllBytes(file)).equals(fingerprint);
    }
    private void atomicWrite(byte[] data)throws IOException{
            Path temp=Files.createTempFile(directory,".config-",".json.tmp");try{
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){channel.write(java.nio.ByteBuffer.wrap(data));channel.force(true);}
            try{Files.setPosixFilePermissions(temp,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));}catch(UnsupportedOperationException ignored){}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){throw new IOException("Config filesystem does not support atomic replacement",e);}
        }finally{Files.deleteIfExists(temp);}
    }
    private void createMigrationBackup(byte[] original)throws IOException{
        if(!hash(Files.readAllBytes(file)).equals(hash(original)))throw new IOException("Configuration changed during migration; reload before applying it.");
        String base=file.getFileName()+".before-schema-"+ConfigMigrator.CURRENT_SCHEMA_VERSION;
        Path backup=file.resolveSibling(base);int suffix=1;
        while(Files.exists(backup))backup=file.resolveSibling(base+"-"+suffix++);
        Path temporary=Files.createTempFile(directory,".config-backup-", ".tmp");
        try{
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(original);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            try{Files.move(temporary,backup,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){throw new IOException("Config filesystem does not support atomic migration backups",e);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private void migrateLegacyYaml()throws IOException,ConfigValidationException{
        Path legacy=legacyFile;if(legacy==null||Files.exists(file)||!Files.exists(legacy))return;
        Map<String,Object> parsed;
        try{parsed=new ObjectMapper(new YAMLFactory()).readValue(Files.readAllBytes(legacy),MAP_TYPE);}
        catch(Exception error){throw new ConfigValidationException(List.of("Legacy YAML could not be read; it was left untouched: "+error.getMessage()));}
        int version=parsed.get("schema_version") instanceof Number n?n.intValue():-1;if(version<1||version>ConfigMigrator.CURRENT_SCHEMA_VERSION)throw new ConfigValidationException(List.of("Legacy YAML has an unsupported schema version"));
        ConfigMigrator.migrateToCurrentSchema(parsed,true,directory);List<String> errors=ConfigValidator.validate(parsed);if(!errors.isEmpty())throw new ConfigValidationException(errors);
        Path backup=legacy.resolveSibling("config.yaml.before-json");if(Files.exists(backup))throw new IOException("Legacy backup already exists; refusing to overwrite "+backup);
        Files.copy(legacy,backup);try{atomicWrite(mapper.writeValueAsBytes(parsed));}catch(IOException|RuntimeException failure){Files.deleteIfExists(file);throw failure;}
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> deep(Map<String,Object> map){return (Map<String,Object>)copy(map);}
    private static Object copy(Object value){if(value instanceof Map<?,?> m){Map<String,Object> c=new LinkedHashMap<>();m.forEach((k,v)->c.put(String.valueOf(k),copy(v)));return c;}if(value instanceof List<?> l){List<Object> c=new ArrayList<>();l.forEach(v->c.add(copy(v)));return c;}return value;}
    private void publish(Event event){listeners.forEach(l->{try{l.accept(event);}catch(RuntimeException ignored){}});}
    @Override public void close(){closed=true;watcherExecutor.shutdownNow();storageExecutor.shutdownNow();}

    public record Event(Map<String,Object> config,String message){}
    public record VersionedSnapshot(Map<String,Object> config,String revision){}
    public static class ConfigValidationException extends Exception{private final List<String> errors;public ConfigValidationException(List<String> errors){super(String.join("; ",errors));this.errors=List.copyOf(errors);}public List<String> errors(){return errors;}}
    public static class ConfigConflictException extends Exception{public ConfigConflictException(String message){super(message);}}
}
