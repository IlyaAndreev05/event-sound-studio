package io.github.ilyaandreev05.eventsoundstudio.input;

import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WheelPacketTest {
    private static final class Sink implements InputEventDispatcher.EventSink {
        final List<String> events=new ArrayList<>();
        boolean accept=true;
        public boolean hasEnabledBinding(String event){return true;}
        public boolean trigger(String event){events.add(event);return accept;}
    }
    private static void event(InputEventDispatcher d,String device,int type,int code,int value){d.accept(new EvdevInputService.RawEvent(device,type,code,value,0));}
    private static void rel(InputEventDispatcher d,String device,int code,int value){event(d,device,2,code,value);}
    private static void report(InputEventDispatcher d,String device){event(d,device,0,0,0);}
    @Test void firstPacketPrefersHighResolutionRegardlessOfLegacyOrdering(){
        for(boolean legacyFirst:List.of(true,false)){
            Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
                rel(d,"mouse",legacyFirst?8:11,legacyFirst?1:120);
                rel(d,"mouse",legacyFirst?11:8,legacyFirst?120:1);
                assertTrue(sink.events.isEmpty());report(d,"mouse");
                assertEquals(List.of("wheel_up"),sink.events);
            }
        }
    }
    @Test void directionAxisAndDeviceHaveIndependentRemainders(){
        Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
            rel(d,"a",11,60);rel(d,"a",11,-60);rel(d,"a",12,60);rel(d,"b",11,60);
            report(d,"a");report(d,"b");assertTrue(sink.events.isEmpty());
            rel(d,"a",11,60);report(d,"a");assertEquals(List.of("wheel_up"),sink.events);
            rel(d,"a",11,-60);report(d,"a");rel(d,"a",12,60);report(d,"a");rel(d,"b",11,60);report(d,"b");
            assertEquals(List.of("wheel_up","wheel_down","wheel_right","wheel_up"),sink.events);
        }
    }
    @Test void rapidLegacyAndHighResolutionDetentsAreNeverCappedAtTwenty(){
        Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
            rel(d,"legacy",8,57);report(d,"legacy");rel(d,"high",11,120*73);report(d,"high");
            assertEquals(130,sink.events.size());
        }
    }
    @Test void disconnectResetsOnlyItsOwnIncompleteState(){
        Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
            rel(d,"a",11,60);report(d,"a");rel(d,"b",11,60);report(d,"b");
            event(d,"a",-1,0,0);rel(d,"a",11,60);report(d,"a");assertTrue(sink.events.isEmpty());
            rel(d,"b",11,60);report(d,"b");assertEquals(List.of("wheel_up"),sink.events);
            event(d,"a",-1,0,0);rel(d,"a",8,1);report(d,"a");assertEquals(2,sink.events.size());
        }
    }
    @Test void synDroppedDiscardsPacketAndIgnoresRecordsUntilNextReport(){
        Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
            rel(d,"a",11,60);event(d,"a",0,3,0);rel(d,"a",11,120);report(d,"a");
            rel(d,"a",11,60);report(d,"a");assertTrue(sink.events.isEmpty());
            rel(d,"a",11,60);report(d,"a");assertEquals(List.of("wheel_up"),sink.events);
        }
    }
    @Test void calibrationReportsRawDetentsAndAcceptedEventsWithoutKeyHistory(){
        Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
            d.beginCalibration("a",5);rel(d,"a",8,5);rel(d,"a",11,600);report(d,"a");
            sink.accept=false;rel(d,"a",11,120);report(d,"a");
            var result=d.endCalibration();var axis=result.axes().get("vertical");
            assertFalse(result.active());assertEquals(5,result.physicalDetents());
            assertEquals(720,axis.rawHighResolution());assertEquals(5,axis.rawLegacy());assertEquals(6,axis.detents());assertEquals(5,axis.acceptedImpulses());
            assertThrows(IllegalArgumentException.class,()->d.beginCalibration("a",3));
            assertThrows(UnsupportedOperationException.class,()->d.diagnostics().snapshot().devices().clear());
        }
    }
    @Test void slowModifierDoesNotLeakBeforeOnlyComboAndReconnectClearsChord(){
        Sink sink=new Sink();try(var d=new InputEventDispatcher(sink)){
            Map<String,Object> config=DefaultConfig.create();config.put("combos",List.of(Map.of("id","test","keys",List.of("KEY_LEFTMETA","KEY_A"),"policy","ONLY_COMBO","event","combo")));d.updateConfig(config);
            event(d,"kbd",1,125,1);assertEquals(List.of("key:KEY_LEFTMETA:press"),sink.events);
            event(d,"kbd",1,30,1);event(d,"kbd",1,30,0);event(d,"kbd",1,125,0);
            assertEquals(List.of("key:KEY_LEFTMETA:press","combo"),sink.events);
            event(d,"kbd",1,125,1);event(d,"kbd",-1,0,0);event(d,"kbd",1,30,1);event(d,"kbd",1,30,0);
            assertEquals(1,sink.events.stream().filter("combo"::equals).count());
        }
    }
}
