package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SoundLibrary implements AutoCloseable {
    private final ConfigStore store;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ess-sound-library");t.setDaemon(true);return t;});
    private final Map<String,Entry> entries = new ConcurrentHashMap<>();
    public SoundLibrary(ConfigStore store){this.store=store;}
    public CompletableFuture<List<Entry>> loadAll(List<Map<String,Object>> sounds){
        return CompletableFuture.supplyAsync(()->{
            entries.clear(); List<Entry> result=new ArrayList<>();
            for(Map<String,Object> sound:sounds){
                String id=string(sound.get("id"),"");String name=string(sound.get("name"),id);String rel=string(sound.get("path"),"");
                try{
                    Path path=resolve(rel);PcmSound pcm=SoundSynthesizer.loadWav(path,id,name);
                    Entry entry=new Entry(id,name,rel,path,pcm,sound,null);entries.put(id,entry);result.add(entry);
                }catch(Exception e){Entry entry=new Entry(id,name,rel,null,null,sound,e.getMessage());entries.put(id,entry);result.add(entry);}
            }
            return List.copyOf(result);
        },loader);
    }
    public Entry get(String id){return entries.get(id);}
    public List<Entry> entries(){return entries.values().stream().sorted(java.util.Comparator.comparing(Entry::name,String.CASE_INSENSITIVE_ORDER)).toList();}
    public CompletableFuture<Path> exportWav(Entry entry,Path destination,boolean replaceExisting){
        return CompletableFuture.supplyAsync(()->{
            Path temporary=null;
            try{
                if(entry==null||!entry.ready()||entry.file()==null||!Files.isRegularFile(entry.file()))throw new IOException("Sound is not available for export");
                Path target=destination.toAbsolutePath().normalize();if(target.equals(entry.file().toAbsolutePath().normalize()))throw new IOException("Export destination is the source WAV file");
                Path parent=target.getParent();if(parent==null)throw new IOException("Export destination has no parent directory");Files.createDirectories(parent);
                if(!replaceExisting&&Files.exists(target))throw new java.nio.file.FileAlreadyExistsException(target.toString());
                temporary=Files.createTempFile(parent,".ess-export-",".wav.tmp");Files.copy(entry.file(),temporary,StandardCopyOption.REPLACE_EXISTING);
                return installExport(temporary,target,replaceExisting);
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
            finally{deleteQuietly(temporary);}
        },loader);
    }
    public CompletableFuture<Path> exportGenerated(SoundSynthesizer.Waveform waveform,double frequency,double amplitude,
                                                    int durationMs,int repeats,double dcOffset,Path destination,boolean replaceExisting){
        return CompletableFuture.supplyAsync(()->{
            Path temporary=null;
            try{
                Path target=destination.toAbsolutePath().normalize();Path parent=target.getParent();if(parent==null)throw new IOException("Export destination has no parent directory");Files.createDirectories(parent);
                if(!replaceExisting&&Files.exists(target))throw new java.nio.file.FileAlreadyExistsException(target.toString());
                temporary=Files.createTempFile(parent,".ess-export-",".wav.tmp");float[] samples=SoundSynthesizer.generate(waveform,frequency,amplitude,durationMs,repeats,dcOffset);
                SoundSynthesizer.writeWav(temporary,samples,SoundSynthesizer.SAMPLE_RATE);
                return installExport(temporary,target,replaceExisting);
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
            finally{deleteQuietly(temporary);}
        },loader);
    }
    private static Path installExport(Path temporary,Path target,boolean replaceExisting)throws IOException{
        if(replaceExisting){try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}}
        else Files.move(temporary,target);
        return target;
    }
    public CompletableFuture<Entry> importWav(Path source){
        return CompletableFuture.supplyAsync(()->{
            Path original=null,normalizedFile=null;
            try{
                PcmSound normalized=SoundSynthesizer.loadWav(source,"preview",source.getFileName().toString());
                String id="user-"+UUID.randomUUID().toString().substring(0,8);
                Path folder=store.dataDirectory().resolve("library/imports");Files.createDirectories(folder);
                String safe=safeName(source.getFileName().toString().replaceFirst("(?i)\\.wav$",""));
                original=folder.resolve(id+"-original-"+safe+".wav");Files.copy(source,original);
                normalizedFile=folder.resolve(id+"-"+safe+".wav");
                float[] samples=normalized.samples().clone(); double peak=0;
                for(int i=0;i<samples.length;i++){
                    if(!Float.isFinite(samples[i]))samples[i]=0;
                    peak=Math.max(peak,Math.abs(samples[i]));
                }
                if(peak>0)for(int i=0;i<samples.length;i++)samples[i]=(float)Math.max(-.98,Math.min(.98,samples[i]/peak*.85));
                // Apply one gain to all channels so stereo balance and phase are retained.
                SoundSynthesizer.writeWav(normalizedFile,samples,normalized.sampleRate(),normalized.channels());
                PcmSound pcm=SoundSynthesizer.loadWav(normalizedFile,id,source.getFileName().toString());
                String relative=store.dataDirectory().relativize(normalizedFile).toString();
                Map<String,Object> meta=new LinkedHashMap<>();meta.put("id",id);meta.put("name",source.getFileName().toString().replaceFirst("(?i)\\.wav$",""));meta.put("path",relative);meta.put("original_path",store.dataDirectory().relativize(original).toString());meta.put("format","pcm_wav");meta.put("volume",0.7);meta.put("tags",new ArrayList<>());meta.put("description","");meta.put("group","");meta.put("favorite",false);meta.put("builtin",false);
                Entry entry=new Entry(id,string(meta.get("name"),id),relative,normalizedFile,pcm,meta,null);entries.put(id,entry);return entry;
            }catch(Exception e){deleteQuietly(normalizedFile);deleteQuietly(original);throw new java.util.concurrent.CompletionException(e);}
        },loader);
    }
    public CompletableFuture<Entry> copySound(Entry source,String name){
        return CompletableFuture.supplyAsync(()->{
            Path copiedFile=null,copiedOriginal=null;
            try{
                if(source==null||!source.ready())throw new IOException("Sound is not available for copying");
                if(name==null||name.isBlank())throw new IOException("Sound name must not be empty");
                String id="copy-"+UUID.randomUUID().toString().substring(0,8);
                Path folder=store.dataDirectory().resolve("library/copies");Files.createDirectories(folder);
                copiedFile=folder.resolve(id+".wav");Files.copy(source.file(),copiedFile);
                PcmSound pcm=SoundSynthesizer.loadWav(copiedFile,id,name.trim());
                Map<String,Object> meta=new LinkedHashMap<>(source.metadata());
                meta.put("id",id);meta.put("name",name.trim());meta.put("path",store.dataDirectory().relativize(copiedFile).toString());meta.put("builtin",false);meta.put("favorite",false);meta.put("copy_of",source.id());meta.remove("original_path");
                Object originalPath=source.metadata().get("original_path");
                if(originalPath instanceof String path&&!path.isBlank()){
                    Path candidate=Path.of(path);if(!candidate.isAbsolute())candidate=store.dataDirectory().resolve(candidate);Path existingOriginal=candidate.toAbsolutePath().normalize();
                    if(existingOriginal.startsWith(store.dataDirectory().toAbsolutePath().normalize())&&Files.isRegularFile(existingOriginal)){
                        copiedOriginal=folder.resolve(id+"-original.wav");Files.copy(existingOriginal,copiedOriginal);
                        meta.put("original_path",store.dataDirectory().relativize(copiedOriginal).toString());
                    }
                }
                Entry entry=new Entry(id,name.trim(),String.valueOf(meta.get("path")),copiedFile,pcm,meta,null);entries.put(id,entry);return entry;
            }catch(Exception e){deleteQuietly(copiedFile);deleteQuietly(copiedOriginal);throw new java.util.concurrent.CompletionException(e);}
        },loader);
    }
    public CompletableFuture<Entry> saveFragment(Entry source,String name,int startFrame,int endFrame){
        return CompletableFuture.supplyAsync(()->{
            Path file=null;
            try{
                if(source==null||!source.ready())throw new IOException("Sound is not available for editing");
                if(name==null||name.isBlank())throw new IOException("Sound name must not be empty");
                PcmSound input=source.pcm();if(startFrame<0||endFrame>input.frames()||endFrame<=startFrame)throw new IOException("Selected fragment is empty or outside the sound");
                int channels=input.channels();float[] samples=Arrays.copyOfRange(input.samples(),startFrame*channels,endFrame*channels);
                String id="fragment-"+UUID.randomUUID().toString().substring(0,8);Path folder=store.dataDirectory().resolve("library/fragments");Files.createDirectories(folder);file=folder.resolve(id+".wav");
                SoundSynthesizer.writeWav(file,samples,input.sampleRate(),channels);PcmSound pcm=SoundSynthesizer.loadWav(file,id,name.trim());
                Map<String,Object> meta=new LinkedHashMap<>(source.metadata());meta.put("id",id);meta.put("name",name.trim());meta.put("path",store.dataDirectory().relativize(file).toString());meta.put("builtin",false);meta.put("favorite",false);meta.put("fragment_of",source.id());meta.put("fragment_start_frame",startFrame);meta.put("fragment_end_frame",endFrame);meta.remove("original_path");meta.remove("copy_of");
                Entry entry=new Entry(id,name.trim(),String.valueOf(meta.get("path")),file,pcm,meta,null);entries.put(id,entry);return entry;
            }catch(Exception e){deleteQuietly(file);throw new java.util.concurrent.CompletionException(e);}
        },loader);
    }
    public CompletableFuture<Entry> saveGenerated(String name, SoundSynthesizer.Waveform waveform, double frequency, double amplitude, int durationMs, int repeats, double dcOffset){
        return CompletableFuture.supplyAsync(()->{
            try{
                String id="generated-"+UUID.randomUUID().toString().substring(0,8);Path folder=store.dataDirectory().resolve("library/generated");Files.createDirectories(folder);
                Path path=folder.resolve(id+".wav");float[] samples=SoundSynthesizer.generate(waveform,frequency,amplitude,durationMs,repeats,dcOffset);
                SoundSynthesizer.writeWav(path,samples,SoundSynthesizer.SAMPLE_RATE);PcmSound pcm=SoundSynthesizer.loadWav(path,id,name);
                String relative=store.dataDirectory().relativize(path).toString();Map<String,Object> meta=new LinkedHashMap<>();meta.put("id",id);meta.put("name",name);meta.put("path",relative);meta.put("format","pcm_wav");meta.put("volume",0.7);meta.put("tags",List.of("generated"));meta.put("builtin",false);
                Entry entry=new Entry(id,name,relative,path,pcm,meta,null);entries.put(id,entry);return entry;
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        },loader);
    }
    private Path resolve(String rel)throws IOException{
        Path path=Path.of(rel);if(!path.isAbsolute())path=store.dataDirectory().resolve(path);path=path.normalize();
        if(!Files.isRegularFile(path))throw new IOException("Sound file is missing: "+path);return path;
    }
    private static void deleteQuietly(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    private static String safeName(String value){String ascii=Normalizer.normalize(value,Normalizer.Form.NFKD).replaceAll("[^A-Za-z0-9._-]+","-").replaceAll("^-|-$","");return ascii.isBlank()?"sound":ascii;}
    private static String string(Object value,String fallback){return value==null?fallback:String.valueOf(value);}
    @Override public void close(){loader.shutdownNow();entries.clear();}
    public record Entry(String id,String name,String relativePath,Path file,PcmSound pcm,Map<String,Object> metadata,String error){public boolean ready(){return pcm!=null&&error==null;}}
}
