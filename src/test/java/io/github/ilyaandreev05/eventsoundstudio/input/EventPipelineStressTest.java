package io.github.ilyaandreev05.eventsoundstudio.input;

import io.github.ilyaandreev05.eventsoundstudio.audio.AudioEngine;
import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;
import io.github.ilyaandreev05.eventsoundstudio.audio.SoundLibrary;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventPipelineStressTest {
    @TempDir Path temp;

    @Test void syntheticTypingWheelAndProfileSwitchBurstStaysWithinTheBoundedQueue() throws Exception {
        try(ConfigStore store=new ConfigStore(temp.resolve("config.json"));
            SoundLibrary library=new SoundLibrary(store);
            AudioEngine engine=new AudioEngine()){
            Map<String,Object> config=DefaultConfig.create();
            @SuppressWarnings("unchecked") List<Map<String,Object>> sounds=(List<Map<String,Object>>)config.get("sounds");
            library.loadAll(sounds).get(5,TimeUnit.SECONDS);
            for(Map<String,Object> binding:maps(config.get("bindings")))
                if("letters-default".equals(binding.get("id"))||"wheel-up".equals(binding.get("id")))binding.put("cooldown_ms",0);
            for(Map<String,Object> profile:maps(config.get("profiles")))
                if("quiet".equals(profile.get("id")))profile.put("enabled_bindings",List.of("letters-default","wheel-up"));

            EventRouter router=new EventRouter(engine,library);InputEventDispatcher dispatcher=new InputEventDispatcher(router);
            try{
                router.update(config);dispatcher.updateConfig(config);
                for(int i=0;i<160;i++){
                    if(i%20==0){config.put("active_profile",(i/20)%2==0?"quiet":"desk");router.update(config);dispatcher.updateConfig(config);}
                    dispatcher.accept(new EvdevInputService.RawEvent("stress-kbd",1,30,1,i*3L));
                    dispatcher.accept(new EvdevInputService.RawEvent("stress-kbd",1,30,0,i*3L+1));
                    dispatcher.accept(new EvdevInputService.RawEvent("stress-mouse",2,11,120,i*3L+2));
                    dispatcher.accept(new EvdevInputService.RawEvent("stress-mouse",0,0,0,i*3L+2));
                }
                config.put("active_profile","quiet");router.update(config);dispatcher.updateConfig(config);
                assertEquals(0,dispatcher.pressedKeyCount("stress-kbd"),"all synthetic key presses must be released");
                assertEquals(0,router.droppedCooldowns(),"the stress profile sets tested binding cooldowns to zero");
                assertEquals(0,engine.droppedRequests(),"the bounded audio queue should accept this 320-request burst");
                assertEquals("system_default",router.activeOutput());
                assertEquals(.25,router.masterVolume(),1e-9);
            }finally{dispatcher.close();}
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}

}
