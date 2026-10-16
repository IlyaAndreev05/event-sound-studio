package io.github.ilyaandreev05.eventsoundstudio.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigValidator {
    private ConfigValidator() {}
    public static List<String> validate(Map<String, Object> root) {
        List<String> errors = new ArrayList<>();
        if (num(root.get("schema_version"), -1) != 3) errors.add("schema_version must be 3");
        if (!"event-sound-studio".equals(root.get("app"))) errors.add("app must be event-sound-studio");
        Map<String,Object> settings = map(root.get("settings"));
        if (settings.isEmpty()) errors.add("settings is required");
        if (!Set.of("ru","en").contains(settings.get("language"))) errors.add("settings.language must be ru or en");
        if (!Set.of("system","light","dark").contains(settings.get("theme"))) errors.add("settings.theme must be system, light, or dark");
        volume(settings.get("master_volume"), "settings.master_volume", errors);
        if (!(settings.get("master_mute") instanceof Boolean)) errors.add("settings.master_mute must be boolean");
        Map<String,Object> input = map(settings.get("input"));
        for (String key : List.of("enable_keyboard","enable_mouse","enable_gnome")) if (!(input.get(key) instanceof Boolean)) errors.add("settings.input."+key+" must be boolean");
        if (input.containsKey("deduplicate_alt_tab_focus") && !(input.get("deduplicate_alt_tab_focus") instanceof Boolean)) errors.add("settings.input.deduplicate_alt_tab_focus must be boolean");
        if (!Boolean.FALSE.equals(input.get("log_key_sequences"))) errors.add("settings.input.log_key_sequences must remain false");
        if (!(input.get("device_allowlist") instanceof List<?>)) errors.add("settings.input.device_allowlist must be an array");
        Map<String,Object> wheel = map(settings.get("wheel"));
        double unitsPerDetent=num(wheel.get("units_per_detent"),Double.NaN);if(!Double.isFinite(unitsPerDetent)||unitsPerDetent<=0||unitsPerDetent>10_000) errors.add("settings.wheel.units_per_detent must be finite and between 0 and 10000");
        if (!(wheel.get("high_resolution_preferred") instanceof Boolean)) errors.add("settings.wheel.high_resolution_preferred must be boolean");
        Map<String,Object> clickPolicy=settings.containsKey("mouse_click_policy")?map(settings.get("mouse_click_policy")):DefaultConfig.map("mode","immediate_plus_double","double_click_window_ms",250);
        if(!Set.of("immediate_plus_double","wait_and_suppress").contains(clickPolicy.get("mode")))errors.add("settings.mouse_click_policy.mode is unsupported");
        if(!integerInRange(clickPolicy.get("double_click_window_ms"),50,1500))errors.add("settings.mouse_click_policy.double_click_window_ms must be an integer from 50 to 1500");
        Map<String,Object> repeatPolicy=settings.containsKey("keyboard_repeat")?map(settings.get("keyboard_repeat")):DefaultConfig.map("mode","ignore","minimum_interval_ms",180);
        if(!Set.of("ignore","throttle").contains(repeatPolicy.get("mode")))errors.add("settings.keyboard_repeat.mode must be ignore or throttle");
        if(!integerInRange(repeatPolicy.get("minimum_interval_ms"),50,5000))errors.add("settings.keyboard_repeat.minimum_interval_ms must be an integer from 50 to 5000");
        List<Map<String,Object>> sounds = list(root.get("sounds"), "sounds", errors);
        List<Map<String,Object>> events = list(root.get("event_types"), "event_types", errors);
        List<Map<String,Object>> groups = list(root.get("groups"), "groups", errors);
        List<Map<String,Object>> bindings = list(root.get("bindings"), "bindings", errors);
        List<Map<String,Object>> combos = list(root.get("combos"), "combos", errors);
        List<Map<String,Object>> profiles = list(root.get("profiles"), "profiles", errors);
        unique(sounds,"sounds",errors); unique(events,"event_types",errors); unique(groups,"groups",errors); unique(bindings,"bindings",errors); unique(combos,"combos",errors); unique(profiles,"profiles",errors);
        Set<String> soundIds = ids(sounds), eventIds = ids(events), bindingIds = ids(bindings), profileIds = ids(profiles);
        if (root.containsKey("source_profiles")) {
            Object overridesValue=root.get("source_profiles");
            if (!(overridesValue instanceof Map<?,?> overrides)) errors.add("source_profiles must be an object");
            else for (var entry:overrides.entrySet()) {
                String source=String.valueOf(entry.getKey());
                if (!Set.of("keyboard","mouse","system").contains(source)) errors.add("source_profiles has unsupported source " + source);
                if (!(entry.getValue() instanceof String profileId) || !profileIds.contains(profileId)) errors.add("source_profiles."+source+" must reference a profile");
            }
        }
        for (int i=0;i<events.size();i++) if (events.get(i).containsKey("name") && (!(events.get(i).get("name") instanceof String name) || name.isBlank())) errors.add("event_types["+i+"].name must be a non-empty string");
        for (var builtin : DefaultConfig.BUILTIN_GROUPS.entrySet()) {
            Map<String,Object> group = groups.stream().filter(g -> builtin.getKey().equals(g.get("id"))).findFirst().orElse(null);
            if (group == null) errors.add("required protected group missing: " + builtin.getKey());
            else {
                if (!Boolean.TRUE.equals(group.get("protected"))) errors.add(builtin.getKey()+" must be protected");
                if (!DefaultConfig.BUILTIN_NAMES.get(builtin.getKey()).equals(group.get("name"))) errors.add(builtin.getKey()+" name is protected");
                if (!builtin.getValue().equals(group.get("members"))) errors.add(builtin.getKey()+" member list is protected");
            }
        }
        for (int i=0;i<sounds.size();i++) {
            Map<String,Object> sound=sounds.get(i); volume(sound.get("volume"),"sounds["+i+"].volume",errors);
            if (!(sound.get("name") instanceof String name) || name.isBlank()) errors.add("sounds["+i+"].name is required");
            if (!(sound.get("path") instanceof String path) || path.isBlank()) errors.add("sounds["+i+"].path is required");
            if (!"pcm_wav".equals(sound.get("format"))) errors.add("sounds["+i+"].format must be pcm_wav");
            if (!(sound.get("tags") instanceof List<?> tags) || tags.stream().anyMatch(tag -> !(tag instanceof String))) errors.add("sounds["+i+"].tags must be an array of strings");
            for(String key:List.of("description","group","original_path","copy_of","fragment_of")) if(sound.containsKey(key)&&!(sound.get(key) instanceof String)) errors.add("sounds["+i+"]."+key+" must be a string");
            for(String key:List.of("fragment_start_frame","fragment_end_frame")) if(sound.containsKey(key)&&!(sound.get(key) instanceof Number)) errors.add("sounds["+i+"]."+key+" must be numeric");
        }
        for (int i=0;i<groups.size();i++) if (!soundIds.contains(String.valueOf(groups.get(i).get("default_sound")))) errors.add("groups["+i+"] references missing default sound");
        for (int i=0;i<bindings.size();i++) {
            Map<String,Object> b=bindings.get(i); volume(b.get("volume"),"bindings["+i+"].volume",errors);
            if (b.containsKey("name") && (!(b.get("name") instanceof String name) || name.isBlank())) errors.add("bindings["+i+"].name must be a non-empty string");
            if (!soundIds.contains(String.valueOf(b.get("sound")))) errors.add("bindings["+i+"] references missing sound");
            if (!(b.get("enabled") instanceof Boolean)) errors.add("bindings["+i+"].enabled must be boolean");
            if (!integerInRange(b.get("priority"),0,1000)) errors.add("bindings["+i+"].priority must be an integer from 0 to 1000");
            if (!integerInRange(b.get("cooldown_ms"),0,60_000)) errors.add("bindings["+i+"].cooldown_ms must be an integer from 0 to 60000");
            if (b.containsKey("pan")){double pan=num(b.get("pan"),Double.NaN);if(!Double.isFinite(pan)||pan< -1||pan>1)errors.add("bindings["+i+"].pan must be between -1 and 1");}
            if (b.containsKey("delay_ms")&&!integerInRange(b.get("delay_ms"),0,5000)) errors.add("bindings["+i+"].delay_ms must be an integer from 0 to 5000");
            if (b.containsKey("repeat")&&!integerInRange(b.get("repeat"),1,20)) errors.add("bindings["+i+"].repeat must be an integer from 1 to 20");
            if (b.containsKey("duration_ms")&&!integerInRange(b.get("duration_ms"),0,30_000)) errors.add("bindings["+i+"].duration_ms must be an integer from 0 to 30000");
            for(String key:List.of("attack_ms","release_ms"))if(b.containsKey(key)&&!integerInRange(b.get(key),0,5000))errors.add("bindings["+i+"]."+key+" must be an integer from 0 to 5000");
            if (b.containsKey("remove_dc")&&!(b.get("remove_dc") instanceof Boolean))errors.add("bindings["+i+"].remove_dc must be boolean");
            if (!Set.of("mix","restart","ignore","queue").contains(b.get("overlap"))) errors.add("bindings["+i+"].overlap is unsupported");
            if (b.containsKey("output")&&(!(b.get("output") instanceof String output)||output.isBlank())) errors.add("bindings["+i+"].output must be a non-empty string");
            if (b.containsKey("eq")) validateEq(b.get("eq"),"bindings["+i+"].eq",errors);
            String event=String.valueOf(b.get("event"));
            if (!eventIds.contains(event) && !event.startsWith("group:") && !event.startsWith("key:") && !event.startsWith("button:") && !event.startsWith("wheel:")) errors.add("bindings["+i+"] references unknown event " + event);
        }
        for (int i=0;i<combos.size();i++) {
            Map<String,Object> c=combos.get(i); Object keys=c.get("keys");
            if (!(keys instanceof List<?> list) || list.size()<2 || list.size()>4) errors.add("combos["+i+"].keys must contain 2–4 keys");
            if (!Set.of("ONLY_COMBO","COMBO_AND_KEYS","SUPPRESS_MODIFIERS").contains(c.get("policy"))) errors.add("combos["+i+"].policy is unsupported");
            if (c.get("event") != null && !eventIds.contains(String.valueOf(c.get("event")))) errors.add("combos["+i+"] references unknown event");
        }
        if (!profileIds.contains(String.valueOf(root.get("active_profile")))) errors.add("active_profile does not reference a profile");
        for (int i=0;i<profiles.size();i++) {
            Map<String,Object> profile=profiles.get(i); volume(profile.get("master_volume"),"profiles["+i+"].master_volume",errors);
            Object enabled=profile.get("enabled_bindings"); if (!(enabled instanceof List<?> list)) errors.add("profiles["+i+"].enabled_bindings must be an array");
            else for (Object id : list) if (!bindingIds.contains(String.valueOf(id))) errors.add("profiles["+i+"] references unknown binding " + id);
        }
        return errors;
    }
    private static void volume(Object value,String path,List<String> errors){double d=num(value,Double.NaN);if(!Double.isFinite(d)||d<0||d>1)errors.add(path+" must be between 0.0 and 1.0");}
    private static void validateEq(Object value,String path,List<String> errors){
        if(!(value instanceof Map<?,?> eq)){errors.add(path+" must be an object");return;}
        if(!(eq.get("enabled") instanceof Boolean))errors.add(path+".enabled must be boolean");
        Object filtersValue=eq.get("filters");if(!(filtersValue instanceof List<?> filters)){errors.add(path+".filters must be an array");return;}
        if(filters.size()>12)errors.add(path+".filters must contain at most 12 filters");
        for(int i=0;i<filters.size();i++){
            String filterPath=path+".filters["+i+"]";Object item=filters.get(i);
            if(!(item instanceof Map<?,?> filter)){errors.add(filterPath+" must be an object");continue;}
            if(!(filter.get("type") instanceof String type)||!io.github.ilyaandreev05.eventsoundstudio.audio.AudioEffects.FILTER_TYPES.contains(type))errors.add(filterPath+".type is unsupported");
            double frequency=num(filter.get("frequency_hz"),Double.NaN),gain=num(filter.get("gain_db"),Double.NaN),q=num(filter.get("q"),Double.NaN);
            if(!Double.isFinite(frequency)||frequency<20||frequency>20_000)errors.add(filterPath+".frequency_hz must be between 20 and 20000");
            if(!Double.isFinite(gain)||gain< -24||gain>12)errors.add(filterPath+".gain_db must be between -24 and 12");
            if(!Double.isFinite(q)||q<.1||q>12)errors.add(filterPath+".q must be between 0.1 and 12");
        }
    }
    private static boolean integerInRange(Object value,int minimum,int maximum){if(!(value instanceof Number number))return false;double n=number.doubleValue();return Double.isFinite(n)&&n==Math.rint(n)&&n>=minimum&&n<=maximum;}
    private static double num(Object value,double fallback){return value instanceof Number n?n.doubleValue():fallback;}
    private static Set<String> ids(List<Map<String,Object>> items){Set<String> s=new HashSet<>();items.forEach(i->{if(i.get("id") instanceof String id)s.add(id);});return s;}
    private static void unique(List<Map<String,Object>> items,String path,List<String> errors){Set<String>s=new HashSet<>();for(int i=0;i<items.size();i++){Object id=items.get(i).get("id");if(!(id instanceof String v)||v.isBlank())errors.add(path+"["+i+"].id must be a non-empty string");else if(!s.add(v))errors.add(path+" contains duplicate id "+v);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){return o instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object o,String path,List<String> errors){if(!(o instanceof List<?> l)){errors.add(path+" must be an array");return List.of();}List<Map<String,Object>> out=new ArrayList<>();for(int i=0;i<l.size();i++){if(l.get(i) instanceof Map<?,?> m)out.add((Map<String,Object>)m);else errors.add(path+"["+i+"] must be an object");}return out;}
}
