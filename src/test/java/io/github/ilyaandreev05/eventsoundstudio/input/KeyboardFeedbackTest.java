package io.github.ilyaandreev05.eventsoundstudio.input;

import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardFeedbackTest {
    @Test void defaultsEnableAllKeyPressAndFunctionModifierBindings(){
        Map<String,Object> config=DefaultConfig.create();
        Map<String,Map<String,Object>> bindings=bindings(config);
        assertEquals("key_press",bindings.get("all-key-press").get("event"));
        assertEquals("octine-click",bindings.get("all-key-press").get("sound"));
        assertEquals(0.07,bindings.get("all-key-press").get("volume"));
        assertEquals(60,bindings.get("all-key-press").get("duration_ms"));
        assertTrue(Boolean.TRUE.equals(bindings.get("all-key-press").get("enabled")));
        assertEquals("group:builtin.modifiers",bindings.get("modifiers-default").get("event"));
        assertEquals("octine-click",bindings.get("modifiers-default").get("sound"));
        assertEquals(0.15,bindings.get("modifiers-default").get("volume"));
        assertEquals("octine-click",bindings.get("function-keys").get("sound"));
        assertEquals(0.15,bindings.get("function-keys").get("volume"));
        assertEquals("octine-click",bindings.get("super-key-press").get("sound"));
        assertEquals("octine-click",bindings.get("super-right-key-press").get("sound"));
        assertEquals(0.15,bindings.get("super-key-press").get("volume"));
        Map<String,Object> desk=profile(config,"desk");
        assertTrue(((List<?>)desk.get("enabled_bindings")).containsAll(List.of("all-key-press","modifiers-default")));
    }

    @Test void normalKeysUseTheNormalBindingWithoutSilentGaps(){
        Map<String,Object> config=DefaultConfig.create();RecordingSink sink=new RecordingSink(config);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);
            for(int code:List.of(30,2,103,79,28,14,15,1,57,111,51)){press(dispatcher,code);}
            assertEquals(List.of("group:builtin.letters","group:builtin.digits","group:builtin.navigation","group:builtin.numpad","enter_press","backspace_press","group:builtin.navigation","group:builtin.navigation","group:custom.punctuation","delete_press","group:custom.punctuation"),sink.events);
        }
    }

    @Test void functionAndModifierKeysUseFunctionBinding(){
        Map<String,Object> config=DefaultConfig.create();RecordingSink sink=new RecordingSink(config);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);
            for(int code:List.of(59,194,42,125)){press(dispatcher,code);}
            assertEquals(List.of("group:custom.function","group:custom.function","group:builtin.modifiers","key:KEY_LEFTMETA:press"),sink.events);
        }
    }

    @Test void modifierPressIsImmediateAndDoesNotWaitForRelease(){
        Map<String,Object> config=DefaultConfig.create();RecordingSink sink=new RecordingSink(config);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,29,1,1));
            assertEquals(List.of("group:builtin.modifiers"),sink.events);
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,29,0,2));
            assertEquals(List.of("group:builtin.modifiers"),sink.events);
        }
    }

    @Test void ctrlSComboFiresWithoutLetterFeedback(){
        Map<String,Object> config=DefaultConfig.create();RecordingSink sink=new RecordingSink(config);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,29,1,1));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,1,2));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,0,3));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,29,0,4));
            assertEquals(List.of("group:builtin.modifiers","combo_save"),sink.events);
        }
    }

    @Test void letterPressIsImmediateEvenWhenAComboCouldFollow(){
        Map<String,Object> config=DefaultConfig.create();RecordingSink sink=new RecordingSink(config);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,1,1));
            assertEquals(List.of("group:builtin.letters"),sink.events);
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,0,2));
            assertEquals(List.of("group:builtin.letters"),sink.events);
        }
    }

    @Test void unmatchedKeyPressFallsBackToKeyPress(){
        Map<String,Object> config=DefaultConfig.create();RecordingSink sink=new RecordingSink(config);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);
            press(dispatcher,119);
            assertEquals(List.of("key_press"),sink.events);
        }
    }

    private static void press(InputEventDispatcher dispatcher,int code){
        dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,code,1,code));
        dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,code,0,code+1));
    }

    private static final class RecordingSink implements InputEventDispatcher.EventSink{
        final List<String> events=new CopyOnWriteArrayList<>();final Set<String> enabled;
        RecordingSink(Map<String,Object> config){enabled=enabledEvents(config);}
        public boolean hasEnabledBinding(String event){return enabled.contains(event);}
        public boolean trigger(String event){events.add(event);return true;}
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enabledEvents(Map<String,Object> config){
        Set<String> profileIds=new HashSet<>(),events=new HashSet<>();
        for(Map<String,Object> profile:(List<Map<String,Object>>)config.get("profiles"))
            if(String.valueOf(config.get("active_profile")).equals(profile.get("id")))
                for(Object id:(List<?>)profile.get("enabled_bindings"))profileIds.add(String.valueOf(id));
        for(Map<String,Object> binding:(List<Map<String,Object>>)config.get("bindings"))
            if(Boolean.TRUE.equals(binding.get("enabled"))&&profileIds.contains(String.valueOf(binding.get("id"))))events.add(String.valueOf(binding.get("event")));
        return events;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Map<String,Object>> bindings(Map<String,Object> config){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(Map<String,Object> binding:(List<Map<String,Object>>)config.get("bindings"))result.put(String.valueOf(binding.get("id")),binding);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> profile(Map<String,Object> config,String id){
        for(Map<String,Object> profile:(List<Map<String,Object>>)config.get("profiles"))if(id.equals(profile.get("id")))return profile;
        throw new IllegalArgumentException("Missing profile "+id);
    }
}
