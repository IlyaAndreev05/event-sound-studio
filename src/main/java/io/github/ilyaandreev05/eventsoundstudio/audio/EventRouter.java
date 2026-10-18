package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Maps normalized event IDs to predecoded sounds. No file access occurs on trigger(). */
public final class EventRouter {
    private final AudioEngine engine;
    private final SoundLibrary library;
    private final AtomicReference<RoutingState> state=new AtomicReference<>(new RoutingState(List.of(),Map.of(),Set.of(),Map.of(),Map.of(),Map.of(),"desk",.65,false,"system_default"));
    private final Map<String,java.util.concurrent.atomic.AtomicLong> lastTriggerNanos=new ConcurrentHashMap<>();
    private final Map<String,ProcessedCache> processedCache=new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong droppedCooldowns=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bindingMatches=new java.util.concurrent.atomic.AtomicLong(), acceptedRequests=new java.util.concurrent.atomic.AtomicLong(), missingSounds=new java.util.concurrent.atomic.AtomicLong();
    public EventRouter(AudioEngine engine,SoundLibrary library){this.engine=engine;this.library=library;}
    public void update(Map<String,Object> config){
        Map<String,Object> settings=map(config.get("settings"));
        String previewOutput=String.valueOf(settings.getOrDefault("preview_output","system_default"));
        String activeProfileId=String.valueOf(config.getOrDefault("active_profile","desk"));
        Map<String,ProfileSelection> profiles=new HashMap<>();
        for(Map<String,Object> profile:listMaps(config.get("profiles"))){
            Set<String> enabled=new HashSet<>();Object active=profile.get("enabled_bindings");if(active instanceof List<?> ids)ids.forEach(id->enabled.add(String.valueOf(id)));
            profiles.put(String.valueOf(profile.get("id")),new ProfileSelection(Set.copyOf(enabled),num(profile.get("master_volume"),num(settings.get("master_volume"),.65)),String.valueOf(profile.getOrDefault("output",settings.getOrDefault("output","system_default")))));
        }
        ProfileSelection activeProfile=profiles.getOrDefault(activeProfileId,new ProfileSelection(Set.of(),num(settings.get("master_volume"),.65),String.valueOf(settings.getOrDefault("output","system_default"))));
        Map<String,String> sourceProfiles=stringMap(config.get("source_profiles"));
        Map<String,String> eventSources=new HashMap<>();
        for(Map<String,Object> event:listMaps(config.get("event_types")))eventSources.put(String.valueOf(event.get("id")),eventCategory(String.valueOf(event.getOrDefault("source","custom")),map(event.get("match")),String.valueOf(event.get("id"))));
        boolean mute=bool(settings.get("master_mute"),false);
        Map<String,Double> soundVolumes=new HashMap<>();for(Map<String,Object> sound:listMaps(config.get("sounds")))soundVolumes.put(String.valueOf(sound.get("id")),num(sound.get("volume"),1));
        List<Binding> bindings=new ArrayList<>();for(Map<String,Object> b:listMaps(config.get("bindings"))){
            String id=String.valueOf(b.get("id"));String soundId=String.valueOf(b.get("sound"));SoundLibrary.Entry entry=library.get(soundId);
            PcmSound source=entry!=null&&entry.ready()?entry.pcm():null;Map<String,Object> eq=map(b.get("eq"));Map<String,Object> effects=new HashMap<>();effects.put("eq",eq);effects.put("duration_ms",intNum(b.get("duration_ms"),0));effects.put("attack_ms",intNum(b.get("attack_ms"),0));effects.put("release_ms",intNum(b.get("release_ms"),0));effects.put("remove_dc",bool(b.get("remove_dc"),false));
            effects.put("hold_ms",intNum(b.get("hold_ms"),0));effects.put("decay_ms",intNum(b.get("decay_ms"),0));effects.put("sustain_level",num(b.get("sustain_level"),1));effects.put("anti_click_ms",num(b.get("anti_click_ms"),1));
            PcmSound rendered=source==null?null:processedSound(id,source,effects);
            int filterCount=eq.get("filters") instanceof List<?> filters?filters.size():0;
            bindings.add(new Binding(id,String.valueOf(b.get("event")),soundId,bool(b.get("enabled"),false),activeProfile.enabledBindingIds().contains(id),intNum(b.get("priority"),0),num(b.get("volume"),1),intNum(b.get("cooldown_ms"),0),String.valueOf(b.getOrDefault("overlap","mix")),num(b.get("pan"),0),intNum(b.get("delay_ms"),0),intNum(b.get("repeat"),1),String.valueOf(b.getOrDefault("output","profile_default")),intNum(b.get("duration_ms"),0),intNum(b.get("attack_ms"),0),intNum(b.get("release_ms"),0),bool(b.get("remove_dc"),false),rendered,bool(eq.get("enabled"),false),filterCount));
        }
        Set<String> liveBindingIds=bindings.stream().map(Binding::id).collect(java.util.stream.Collectors.toSet());processedCache.keySet().retainAll(liveBindingIds);
        bindings.forEach(binding->lastTriggerNanos.computeIfAbsent(binding.id(),ignored->new java.util.concurrent.atomic.AtomicLong()));
        bindings.sort(Comparator.comparingInt(Binding::priority).reversed());
        state.set(new RoutingState(List.copyOf(bindings),Map.copyOf(soundVolumes),activeProfile.enabledBindingIds(),Map.copyOf(profiles),Map.copyOf(sourceProfiles),Map.copyOf(eventSources),activeProfileId,activeProfile.volume(),mute,previewOutput));
        // Event voices are explicitly targeted below; the engine's default is reserved for previews and the generator.
        engine.updateSettings(1,mute,true,previewOutput);
    }
    public boolean trigger(String eventId){
        return routeEvent(eventId,false);
    }
    /** Plays a configured event only because the user requested a GUI preview. */
    public boolean previewEvent(String eventId){
        return routeEvent(eventId,true);
    }
    private boolean routeEvent(String eventId,boolean preview){
        RoutingState current=state.get();ProfileSelection profile=profileForEvent(current,eventId);if(profile==null)return false;boolean played=false;
        for(Binding binding:current.bindings()){
            if(!binding.enabled()||!profile.enabledBindingIds().contains(binding.id())||!binding.event().equals(eventId))continue;
            bindingMatches.incrementAndGet();
            if(binding.renderedSound()==null){missingSounds.incrementAndGet();continue;}
            if(!preview&&!claimCooldown(binding)){droppedCooldowns.incrementAndGet();continue;}
            String output=preview?current.output():MixerOutputSelector.AUDIO_MATRIX_EVENTS;double gain=binding.volume()*current.soundVolumes().getOrDefault(binding.sound(),1.0)*profile.volume();
            if(engine.tryPlay(binding.renderedSound(),gain,binding.pan(),binding.delayMs(),binding.repeat(),binding.overlap(),preview?"preview:"+binding.id():binding.id(),output)){acceptedRequests.incrementAndGet();played=true;}
        }
        return played;
    }
    public boolean hasEnabledBinding(String eventId){
        RoutingState current=state.get();ProfileSelection profile=profileForEvent(current,eventId);if(profile==null)return false;
        return current.bindings().stream().anyMatch(binding->binding.enabled()&&profile.enabledBindingIds().contains(binding.id())&&binding.event().equals(eventId));
    }
    public boolean playBinding(String bindingId){
        RoutingState current=state.get();Binding binding=current.bindings().stream().filter(item->item.id().equals(bindingId)).findFirst().orElse(null);if(binding==null||binding.renderedSound()==null)return false;
        ProfileSelection profile=profileForEvent(current,binding.event());if(profile==null)return false;String output=current.output();
        return engine.tryPlay(binding.renderedSound(),binding.volume()*current.soundVolumes().getOrDefault(binding.sound(),1.0)*profile.volume(),binding.pan(),binding.delayMs(),binding.repeat(),"mix","preview:"+binding.id(),output);
    }
    public boolean playSound(String soundId){
        RoutingState current=state.get();SoundLibrary.Entry entry=library.get(soundId);if(entry==null||!entry.ready())return false;
        return engine.tryPlay(entry.pcm(),current.soundVolumes().getOrDefault(soundId,1.0)*current.masterVolume(),0,0,1,"mix","preview:sound:"+soundId,current.output());
    }
    public long droppedCooldowns(){return droppedCooldowns.get();}
    public Diagnostics diagnostics(){return new Diagnostics(bindingMatches.get(),acceptedRequests.get(),missingSounds.get(),droppedCooldowns.get());}
    public record Diagnostics(long bindingMatches,long acceptedRequests,long missingSounds,long droppedCooldowns){}
    private boolean claimCooldown(Binding binding){
        java.util.concurrent.atomic.AtomicLong clock=lastTriggerNanos.get(binding.id());
        long previous,now;do{previous=clock.get();now=System.nanoTime();if(previous!=0&&now-previous<binding.cooldownMs()*1_000_000L)return false;}while(!clock.compareAndSet(previous,now));return true;
    }
    public double masterVolume(){return state.get().masterVolume();}
    public String activeOutput(){return state.get().output();}
    public List<BindingView> bindings(){return state.get().bindings().stream().map(b->new BindingView(b.id(),b.event(),b.sound(),b.enabled(),b.profileEnabled(),b.priority(),b.volume(),b.pan(),b.delayMs(),b.repeat(),b.overlap(),b.output(),b.durationMs(),b.attackMs(),b.releaseMs(),b.removeDc(),b.eqEnabled(),b.eqFilterCount())).toList();}
    private static Map<String,Object> map(Object o){if(o instanceof Map<?,?> m){Map<String,Object> r=new HashMap<>();m.forEach((k,v)->r.put(String.valueOf(k),v));return r;}return Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> listMaps(Object o){return o instanceof List<?> l?(List<Map<String,Object>>)l:List.of();}
    private static double num(Object o,double d){return o instanceof Number n?n.doubleValue():d;}
    private static int intNum(Object o,int d){return o instanceof Number n?n.intValue():d;}
    private static boolean bool(Object o,boolean d){return o instanceof Boolean b?b:d;}
    private ProfileSelection profileForEvent(RoutingState current,String eventId){
        String source=sourceCategory(eventId,current.eventSources());String profileId=current.sourceProfiles().getOrDefault(source,current.activeProfileId());
        return current.profiles().getOrDefault(profileId,current.profiles().get(current.activeProfileId()));
    }
    private static String sourceCategory(String eventId,Map<String,String> eventSources){
        if(eventId.startsWith("key:"))return "keyboard";if(eventId.startsWith("button:")||eventId.startsWith("wheel:"))return "mouse";
        if(eventId.startsWith("group:"))return eventId.endsWith("builtin.mouse")?"mouse":"keyboard";
        return eventSources.getOrDefault(eventId,eventId.startsWith("mouse_")||eventId.startsWith("wheel_")?"mouse":"system");
    }
    private static String eventCategory(String source,Map<String,Object> match,String id){
        if(match.containsKey("button")||match.containsKey("axis"))return "mouse";
        if(match.containsKey("key")||match.containsKey("keys")||"key".equals(match.get("kind")))return "keyboard";
        if("evdev".equals(source))return "keyboard";
        return "system";
    }
    private static Map<String,String> stringMap(Object value){Map<String,String> result=new HashMap<>();if(value instanceof Map<?,?> map)map.forEach((key,item)->result.put(String.valueOf(key),String.valueOf(item)));return result;}
    private PcmSound processedSound(String bindingId,PcmSound source,Map<String,Object> eq){
        Object snapshot=deepCopy(eq);ProcessedCache cache=processedCache.compute(bindingId,(key,previous)->previous!=null&&previous.source()==source&&previous.eq().equals(snapshot)?previous:new ProcessedCache(source,snapshot,AudioEffects.applyBindingEffects(source,eq)));
        return cache.rendered();
    }
    private static Object deepCopy(Object value){if(value instanceof Map<?,?> map){Map<String,Object> copy=new java.util.LinkedHashMap<>();map.forEach((key,item)->copy.put(String.valueOf(key),deepCopy(item)));return copy;}if(value instanceof List<?> list)return list.stream().map(EventRouter::deepCopy).toList();return value;}
    private record Binding(String id,String event,String sound,boolean enabled,boolean profileEnabled,int priority,double volume,int cooldownMs,String overlap,double pan,int delayMs,int repeat,String output,int durationMs,int attackMs,int releaseMs,boolean removeDc,PcmSound renderedSound,boolean eqEnabled,int eqFilterCount){}
    private record ProcessedCache(PcmSound source,Object eq,PcmSound rendered){}
    private record ProfileSelection(Set<String> enabledBindingIds,double volume,String output){}
    private record RoutingState(List<Binding> bindings,Map<String,Double> soundVolumes,Set<String> enabledIds,Map<String,ProfileSelection> profiles,Map<String,String> sourceProfiles,Map<String,String> eventSources,String activeProfileId,double masterVolume,boolean muted,String output){}
    public record BindingView(String id,String event,String sound,boolean enabled,boolean profileEnabled,int priority,double volume,double pan,int delayMs,int repeats,String overlap,String output,int durationMs,int attackMs,int releaseMs,boolean removeDc,boolean eqEnabled,int eqFilterCount){}
}
