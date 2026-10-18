package io.github.ilyaandreev05.eventsoundstudio.config;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioSystem;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConfigHapticTest {
    @Test void defaultEventBindingsUseMouseHapticLevels() {
        Map<String,Object> config=DefaultConfig.create();
        Map<String,Map<String,Object>> bindings=maps(config.get("bindings")).stream()
                .collect(java.util.stream.Collectors.toMap(b->String.valueOf(b.get("id")),b->b));
        for(String id:List.of("letters-default","digits-default")){
            assertEquals("octine-click",bindings.get(id).get("sound"),id);
            assertEquals(0.0595,bindings.get(id).get("volume"),id);
            assertEquals(60,bindings.get(id).get("duration_ms"),id);
        }
        for(String id:List.of("all-key-press","navigation-default","numpad-default","enter-key","punctuation-default","backspace-key","delete-key")){
            assertEquals("octine-click",bindings.get(id).get("sound"),id);
            assertEquals(0.07,bindings.get(id).get("volume"),id);
            assertEquals(60,bindings.get(id).get("duration_ms"),id);
        }
        for(String id:List.of("modifiers-default","function-keys","super-key-press","super-right-key-press")){
            assertEquals("octine-click",bindings.get(id).get("sound"),id);
            assertEquals(0.105,bindings.get(id).get("volume"),id);
            assertEquals(60,bindings.get(id).get("duration_ms"),id);
        }
        assertEquals(true,bindings.get("all-key-press").get("enabled"));
        assertEquals("octine-click",bindings.get("mouse-click").get("sound"));
        assertEquals(0.7,bindings.get("mouse-click").get("volume"));
        assertEquals(60,bindings.get("mouse-click").get("duration_ms"));
        assertEquals("octine-right-click",bindings.get("mouse-right-click").get("sound"));
        assertEquals("octine-scroll",bindings.get("wheel-up").get("sound"));
        assertFalse(Boolean.TRUE.equals(bindings.get("mouse-double-click").get("enabled")));
        Map<String,Object> desk=maps(config.get("profiles")).stream()
                .filter(p->"desk".equals(p.get("id"))).findFirst().orElseThrow();
        assertEquals(1.0,desk.get("master_volume"));
        assertTrue(((List<?>)desk.get("enabled_bindings")).containsAll(List.of("all-key-press","modifiers-default","function-keys")));
    }

    @Test void bundledOctineEffectsAreNormalizedLowFrequencyPulses() throws Exception {
        for(String sound:List.of("octine-click","octine-keypress","octine-function","octine-double-click","octine-scroll","octine-right-click","octine-enter","octine-system","octine-reminder")){
            try(InputStream resource=getClass().getResourceAsStream("/assets/sounds/"+sound+".wav")){
                assertNotNull(resource,sound);
                try(var audio=AudioSystem.getAudioInputStream(resource)){
                    assertEquals(48_000,(int)audio.getFormat().getSampleRate(),sound);
                    byte[] bytes=audio.readAllBytes();double peak=0;int crossings=0;int previous=0;
                    for(int i=0;i+1<bytes.length;i+=2){
                        int value=(short)((bytes[i]&255)|((bytes[i+1]&255)<<8));peak=Math.max(peak,Math.abs(value)/32768.0);
                        int sign=Math.abs(value)<peak*32768*.05?previous:Integer.signum(value);
                        if(previous!=0&&sign!=0&&sign!=previous)crossings++;if(sign!=0)previous=sign;
                    }
                    double seconds=bytes.length/(audio.getFormat().getChannels()*2.0*audio.getFormat().getSampleRate());
                    assertTrue(peak>.8,sound+" peak="+peak);
                    assertTrue(crossings/2.0/seconds<500,sound+" estimated frequency="+crossings/2.0/seconds);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> maps(Object value){return (List<Map<String,Object>>)value;}
}
