package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EventRouterTest {
    @TempDir Path temp;
    @Test void customApiEventRunsWithoutEvdevOrAnotherApp()throws Exception{
        try(ConfigStore store=new ConfigStore(temp.resolve("config.json"));SoundLibrary library=new SoundLibrary(store);AudioEngine engine=new AudioEngine()){
            Map<String,Object> config=DefaultConfig.create();
            List<Map<String,Object>> sounds=(List<Map<String,Object>>)config.get("sounds");
            library.loadAll(sounds).join();EventRouter router=new EventRouter(engine,library);router.update(config);
            assertTrue(router.trigger("api_test"));assertFalse(router.trigger("event-that-does-not-exist"));
            List<Map<String,Object>> bindings=(List<Map<String,Object>>)config.get("bindings");Map<String,Object> wheel=bindings.stream().filter(b->"wheel-up".equals(b.get("id"))).findFirst().orElseThrow();wheel.put("pan",-.4);wheel.put("delay_ms",35);wheel.put("repeat",3);wheel.put("overlap","queue");wheel.put("output","profile_default");wheel.put("duration_ms",50);wheel.put("attack_ms",5);wheel.put("release_ms",8);wheel.put("remove_dc",true);wheel.put("eq",DefaultConfig.map("enabled",true,"filters",List.of(DefaultConfig.map("type","peaking","frequency_hz",1200,"gain_db",6,"q",1))));router.update(config);
            EventRouter.BindingView effect=router.bindings().stream().filter(b->"wheel-up".equals(b.id())).findFirst().orElseThrow();assertEquals(-.4,effect.pan(),1e-9);assertEquals(35,effect.delayMs());assertEquals(3,effect.repeats());assertEquals("queue",effect.overlap());assertEquals("profile_default",effect.output());assertEquals(50,effect.durationMs());assertEquals(5,effect.attackMs());assertEquals(8,effect.releaseMs());assertTrue(effect.removeDc());assertTrue(effect.eqEnabled());assertEquals(1,effect.eqFilterCount());assertTrue(router.playBinding("wheel-up"));
            List<Map<String,Object>> profiles=(List<Map<String,Object>>)config.get("profiles");
            Map<String,Object> quiet=profiles.stream().filter(p->"quiet".equals(p.get("id"))).findFirst().orElseThrow();quiet.put("output",MixerOutputSelector.AUDIO_MATRIX_EVENTS);quiet.put("master_volume",.27);config.put("active_profile","quiet");
            router.update(config);assertEquals("system_default",router.activeOutput());assertEquals(.27,router.masterVolume(),1e-9);
        }
    }
    @Test void keyboardMouseAndSystemEventsCanSelectDifferentProfiles()throws Exception{
        try(ConfigStore store=new ConfigStore(temp.resolve("source-profiles.json"));SoundLibrary library=new SoundLibrary(store);AudioEngine engine=new AudioEngine()){
            Map<String,Object> config=DefaultConfig.create();
            config.put("source_profiles",DefaultConfig.map("system","quiet","mouse","quiet"));
            library.loadAll((List<Map<String,Object>>)config.get("sounds")).join();EventRouter router=new EventRouter(engine,library);router.update(config);
            assertTrue(router.hasEnabledBinding("group:builtin.letters"));
            assertFalse(router.hasEnabledBinding("mouse_left"));
            assertTrue(router.hasEnabledBinding("api_test"));
            assertFalse(router.trigger("mouse_left"));
            assertTrue(router.trigger("api_test"));
            config.put("source_profiles",DefaultConfig.map("system","quiet","mouse","desk"));router.update(config);
            assertTrue(router.hasEnabledBinding("mouse_left"));
        }
    }
}
