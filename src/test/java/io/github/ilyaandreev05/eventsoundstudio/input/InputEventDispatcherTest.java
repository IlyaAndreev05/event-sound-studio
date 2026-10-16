package io.github.ilyaandreev05.eventsoundstudio.input;

import io.github.ilyaandreev05.eventsoundstudio.audio.AudioEngine;
import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;
import io.github.ilyaandreev05.eventsoundstudio.audio.SoundLibrary;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputEventDispatcherTest {
    @TempDir Path temp;

    @Test void ordinaryKeysEmitBoundEventsOnPressAndRelease(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,0,2));
            assertEquals(List.of("key:KEY_A:press","key:KEY_A:release"),sink.events);
        }
    }

    @Test void aSingleWinKeyUsesTheModifierGroupOnRelease(){
        RecordingSink sink=new RecordingSink();sink.disabled.add("key:KEY_LEFTMETA:release");try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,125,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,125,0,2));
            assertEquals(List.of("group:builtin.modifiers"),sink.events);
        }
    }

    @Test void winAOnlyComboDoesNotAlsoTriggerTheWinOrLetterGroups(){
        RecordingSink sink=new RecordingSink();Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> combos=new ArrayList<>((List<Map<String,Object>>)config.get("combos"));
        combos.add(DefaultConfig.map("id","win-a","keys",List.of("KEY_LEFTMETA","KEY_A"),"policy","ONLY_COMBO","event","win_a"));config.put("combos",combos);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,125,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,1,2));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,0,3));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,125,0,4));
            assertEquals(List.of("win_a"),sink.events);
        }
    }

    @Test void keyRepeatsAreIgnoredByDefault(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,2,2));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,0,3));
            assertEquals(List.of("key:KEY_A:press","key:KEY_A:release"),sink.events);
        }
    }

    @Test void throttledKeyRepeatsUseRepeatBindingAndRespectMinimumInterval() throws Exception {
        RecordingSink sink=new RecordingSink();Map<String,Object> config=DefaultConfig.create();@SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");settings.put("keyboard_repeat",DefaultConfig.map("mode","throttle","minimum_interval_ms",100));
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,2,2));Thread.sleep(130);dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,2,3));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,2,4));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,0,5));
            assertEquals(List.of("key:KEY_A:press","key:KEY_A:repeat","key:KEY_A:release"),sink.events);
        }
    }

    @Test void heldCombinationDoesNotFireAgainUntilReleased(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,29,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,1,2));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,30,1,3));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,2,4));
            assertEquals(1,sink.events.stream().filter("combo_save"::equals).count());
            dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,0,5));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,31,1,6));
            assertEquals(2,sink.events.stream().filter("combo_save"::equals).count());
        }
    }

    @Test void altTabRecencyRequiresBothKeysOnTheSameKeyboard(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());
            assertFalse(dispatcher.altTabPressedWithin(750));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1",1,56,1,1)); // KEY_LEFTALT down
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-2",1,15,1,2)); // KEY_TAB on another device
            assertFalse(dispatcher.altTabPressedWithin(750));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1",1,15,1,3)); // KEY_TAB on the same device
            assertTrue(dispatcher.altTabPressedWithin(750));
        }
    }

    @Test void commonMediaKeysAreNormalizedForIndividualBindings(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,115,1,1));dispatcher.accept(new EvdevInputService.RawEvent("kbd",1,115,0,2));
            assertEquals(List.of("key:KEY_VOLUMEUP:press","key:KEY_VOLUMEUP:release"),sink.events);
        }
    }

    @Test void highResolutionWheelAccumulatesDetentsAndSuppressesLegacyDuplicates(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());for(int units:List.of(35,35,50))dispatcher.accept(new EvdevInputService.RawEvent("mouse",2,11,units,1));
            dispatcher.accept(new EvdevInputService.RawEvent("mouse",2,8,1,2));dispatcher.accept(new EvdevInputService.RawEvent("mouse",2,12,-120,3));
            assertEquals(List.of("wheel_up","wheel_left"),sink.events);
        }
    }

    @Test void legacyWheelCanBePreferredWhenHighResolutionModeIsDisabled(){
        RecordingSink sink=new RecordingSink();Map<String,Object> config=DefaultConfig.create();@SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");@SuppressWarnings("unchecked") Map<String,Object> wheel=(Map<String,Object>)settings.get("wheel");wheel.put("high_resolution_preferred",false);
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);dispatcher.accept(new EvdevInputService.RawEvent("mouse",2,11,120,1));dispatcher.accept(new EvdevInputService.RawEvent("mouse",2,8,1,2));
            assertEquals(List.of("wheel_up"),sink.events);
        }
    }

    @Test void immediateDoubleClickPolicyAddsDoubleEventAfterTwoClicks(){
        RecordingSink sink=new RecordingSink();try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(DefaultConfig.create());for(int i=0;i<2;i++){dispatcher.accept(new EvdevInputService.RawEvent("mouse",1,272,1,i*2));dispatcher.accept(new EvdevInputService.RawEvent("mouse",1,272,0,i*2+1));}
            assertEquals(List.of("button:BTN_LEFT:press","mouse_left","button:BTN_LEFT:release","button:BTN_LEFT:press","mouse_left","button:BTN_LEFT:release","mouse_double_left"),sink.events);
        }
    }

    @Test void waitAndSuppressDelaysSingleClickUntilWindowExpires() throws Exception {
        RecordingSink sink=new RecordingSink();Map<String,Object> config=DefaultConfig.create();@SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");settings.put("mouse_click_policy",DefaultConfig.map("mode","wait_and_suppress","double_click_window_ms",40));
        try(InputEventDispatcher dispatcher=new InputEventDispatcher(sink)){
            dispatcher.updateConfig(config);dispatcher.accept(new EvdevInputService.RawEvent("mouse",1,272,1,1));dispatcher.accept(new EvdevInputService.RawEvent("mouse",1,272,0,2));assertFalse(sink.events.contains("mouse_left"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(!sink.events.contains("mouse_left")&&System.nanoTime()<deadline)Thread.sleep(5);assertEquals(1,sink.events.stream().filter("mouse_left"::equals).count());
        }
    }

    @Test void synReportDoesNotClearHeldKeysAndOnlyComboConsumesItsKeys() throws Exception {
        try (ConfigStore store = new ConfigStore(temp.resolve("config.yaml"));
             SoundLibrary library = new SoundLibrary(store);
             AudioEngine engine = new AudioEngine()) {
            EventRouter router = new EventRouter(engine, library);
            var config = DefaultConfig.create();
            router.update(config);
            InputEventDispatcher dispatcher = new InputEventDispatcher(router);
            dispatcher.updateConfig(config);

            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1", 1, 29, 1, 1)); // KEY_LEFTCTRL down
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1", 0, 0, 0, 2)); // SYN_REPORT
            assertEquals(1, dispatcher.pressedKeyCount("kbd-1"));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1", 1, 31, 1, 3)); // KEY_S down
            assertEquals(2, dispatcher.pressedKeyCount("kbd-1"));
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1", 1, 31, 0, 4)); // KEY_S up
            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1", 1, 29, 0, 5)); // KEY_LEFTCTRL up
            assertEquals(0, dispatcher.pressedKeyCount("kbd-1"));

            dispatcher.accept(new EvdevInputService.RawEvent("kbd-1", -1, 0, 0, 6)); // lost source
            assertEquals(0, dispatcher.pressedKeyCount("kbd-1"));
            dispatcher.close();
        }
    }
    private static final class RecordingSink implements InputEventDispatcher.EventSink{
        final List<String> events=new CopyOnWriteArrayList<>();final Set<String> disabled=new HashSet<>();public boolean hasEnabledBinding(String event){return !disabled.contains(event);}public boolean trigger(String event){events.add(event);return true;}
    }
}
