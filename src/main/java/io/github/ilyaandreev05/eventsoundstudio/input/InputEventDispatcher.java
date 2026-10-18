package io.github.ilyaandreev05.eventsoundstudio.input;

import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Normalizes evdev keyboard/mouse events; raw codes and key states remain only in memory. */
public final class InputEventDispatcher implements AutoCloseable {
    private final InputDiagnostics diagnostics=new InputDiagnostics();
    private final WheelNormalizer wheels=new WheelNormalizer(diagnostics);
    private final Set<String> dropped=ConcurrentHashMap.newKeySet();
    private final EventSink router;
    private final Map<String,DeviceState> devices=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ess-input-timers");t.setDaemon(true);return t;});
    private volatile Map<String,Object> config=Map.of();
    private volatile List<Map<String,Object>> combos=List.of();
    private final AtomicLong lastAltTabNanos=new AtomicLong(Long.MIN_VALUE);
    public InputEventDispatcher(EventRouter router){this(new EventSink(){public boolean hasEnabledBinding(String event){return router.hasEnabledBinding(event);}public boolean trigger(String event){return router.trigger(event);}public boolean preview(String event){return router.previewEvent(event);}});}
    InputEventDispatcher(EventSink router){this.router=router;}
    public synchronized void updateConfig(Map<String,Object> config){this.config=config;List<Map<String,Object>> next=new ArrayList<>();Object raw=config.get("combos");if(raw instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map){Map<String,Object> c=new HashMap<>();map.forEach((k,v)->c.put(String.valueOf(k),v));next.add(Map.copyOf(c));}combos=List.copyOf(next);}
    public void accept(EvdevInputService.RawEvent raw){
        if((raw.type()==1||raw.type()==2)&&!raw.deviceId().startsWith("ui-preview-"))diagnostics.received();
        if(raw.type()<0){reset(raw.deviceId());return;}
        if(raw.type()==0&&raw.code()==3){reset(raw.deviceId());dropped.add(raw.deviceId());return;}
        if(dropped.contains(raw.deviceId())){if(raw.type()==0&&raw.code()==0)dropped.remove(raw.deviceId());return;}
        DeviceState state=devices.computeIfAbsent(raw.deviceId(),id->new DeviceState(id.startsWith("ui-preview-")));
        synchronized(state){
            if(raw.type()==0&&raw.code()==0)wheels.report(raw.deviceId(),bool(map(map(config.get("settings")).get("wheel")).get("high_resolution_preferred"),true),wheelUnits(),(horizontal,sign)->triggerWheel(horizontal,sign,state));
            else if(raw.type()==1)key(state,raw.code(),raw.value());
            else if(raw.type()==2)wheels.delta(raw.deviceId(),raw.code(),raw.value());
        }
    }
    /** Plays the configured key/group event without reading a physical input device. */
    public void previewKey(String key){triggerKey(key,"press",true);}
    /** Simulates one mouse click through the same normalization path used by evdev. */
    public void previewMouseButton(String button){Integer code=BUTTONS.entrySet().stream().filter(entry->entry.getValue().equals(button)).map(Map.Entry::getKey).findFirst().orElse(null);if(code==null)return;String device="ui-preview-mouse";accept(new EvdevInputService.RawEvent(device,1,code,1,System.nanoTime()));accept(new EvdevInputService.RawEvent(device,1,code,0,System.nanoTime()));}
    public InputDiagnostics diagnostics(){return diagnostics;}
    public InputDiagnostics.Snapshot snapshot(){return diagnostics.snapshot();}
    public void beginCalibration(String deviceId,int physicalDetents){reset(deviceId);diagnostics.beginCalibration(deviceId,physicalDetents);}
    public InputDiagnostics.Calibration endCalibration(){return diagnostics.endCalibration();}
    public void reset(String deviceId){wheels.reset(deviceId);dropped.remove(deviceId);DeviceState state=devices.remove(deviceId);if(state!=null)synchronized(state){state.pressed.clear();state.pendingClicks.values().forEach(f->f.cancel(false));state.pendingClicks.clear();state.pendingModifiers.clear();}}
    int pressedKeyCount(String deviceId){DeviceState state=devices.get(deviceId);if(state==null)return 0;synchronized(state){return state.pressed.size();}}
    public boolean altTabPressedWithin(long windowMs){long last=lastAltTabNanos.get();if(last==Long.MIN_VALUE)return false;long elapsed=System.nanoTime()-last;return elapsed>=0&&elapsed<=TimeUnit.MILLISECONDS.toNanos(windowMs);}
    private void key(DeviceState state,int code,int value){
        String key=KEYS.get(code);String button=BUTTONS.get(code);
        if(key==null&&button==null)return;
        if(value<0||value>2)return;
        if(value==2){if(button==null)keyRepeat(state,key);return;}
        boolean down=value==1;
        if(button!=null){mouseButton(state,button,down);return;}
        if(down){
            if(!state.pressed.add(key))return;
            long now=System.nanoTime();
            if(("KEY_TAB".equals(key)&&hasAlt(state.pressed))||(isAlt(key)&&state.pressed.contains("KEY_TAB")))lastAltTabNanos.set(now);
            state.lastRepeatNanos.put(key,now);
            Map<String,Object> combo=matchingCombo(state.pressed,state);
            if(combo!=null){String policy=String.valueOf(combo.getOrDefault("policy","ONLY_COMBO"));
                if("COMBO_AND_KEYS".equals(policy)){
                    for(String comboKey:stringList(combo.get("keys"))){
                        if(state.pendingModifiers.remove(comboKey))triggerKey(comboKey,"press",state.preview);
                    }
                }
                state.firedCombos.add(comboIdentity(combo));String event=String.valueOf(combo.getOrDefault("event",combo.get("id")));fire(state,event);
                if("ONLY_COMBO".equals(policy)||"SUPPRESS_MODIFIERS".equals(policy)){
                    state.pendingModifiers.removeAll(stringList(combo.get("keys")));
                }
                if("ONLY_COMBO".equals(policy)){
                    for(String comboKey:stringList(combo.get("keys")))state.consumed.add(comboKey);
                    return;
                }
                if("SUPPRESS_MODIFIERS".equals(policy)){
                    for(String comboKey:stringList(combo.get("keys")))if(isModifier(comboKey))state.consumed.add(comboKey);
                    if(isModifier(key))return;
                }
            }
            if(state.consumed.contains(key))return;
            if(awaitsModifierSuppression(key)){
                state.pendingModifiers.add(key);
            }else triggerKey(key,"press",state.preview);
        }else{
            state.pressed.remove(key);
            state.lastRepeatNanos.remove(key);
            state.firedCombos.removeIf(identity->!comboStillHeld(identity,state.pressed));
            if(state.pendingModifiers.remove(key)&&!state.consumed.contains(key))triggerKey(key,"press",state.preview);
            if(state.consumed.remove(key))return;
            triggerKey(key,"release",state.preview);
        }
    }
    private void keyRepeat(DeviceState state,String key){
        if(!state.pressed.contains(key)||state.consumed.contains(key))return;
        Map<String,Object> policy=map(map(config.get("settings")).get("keyboard_repeat"));
        if(!"throttle".equals(policy.get("mode")))return;
        long interval=(long)(num(policy.get("minimum_interval_ms"),180)*1_000_000L),now=System.nanoTime();
        long previous=state.lastRepeatNanos.getOrDefault(key,0L);
        if(previous!=0&&now-previous<interval)return;
        state.lastRepeatNanos.put(key,now);
        triggerKey(key,"repeat",state.preview);
    }
    private void triggerKey(String key,String state,boolean preview){
        String exact="key:"+key+":"+state;
        if("release".equals(state)){
            if(router.hasEnabledBinding(exact))fire(preview,exact);
            return;
        }
        if(router.hasEnabledBinding(exact)){fire(preview,exact);return;}
        if("repeat".equals(state)){
            String press="key:"+key+":press";
            if(router.hasEnabledBinding(press)){fire(preview,press);return;}
            if(key.equals("KEY_ENTER")&&router.hasEnabledBinding("enter_press")){fire(preview,"enter_press");return;}
            String group=groupFor(key);if(group!=null)fire(preview,"group:"+group);
            return;
        }
        String special=switch(key){case "KEY_ENTER"->"enter_press";case "KEY_BACKSPACE"->"backspace_press";case "KEY_DELETE"->"delete_press";default->"";};
        if(!special.isEmpty()&&router.hasEnabledBinding(special)){fire(preview,special);return;}
        String group=groupFor(key);
        if("press".equals(state)){
            if(group!=null&&router.hasEnabledBinding("group:"+group)){fire(preview,"group:"+group);return;}
            if(key.matches("KEY_[A-Z0-9]")||Set.of("KEY_MINUS","KEY_EQUAL","KEY_LEFTBRACE","KEY_RIGHTBRACE","KEY_BACKSLASH","KEY_SEMICOLON","KEY_APOSTROPHE","KEY_GRAVE","KEY_COMMA","KEY_DOT","KEY_SLASH","KEY_102ND").contains(key))fire(preview,"key_press");
        }
    }
    private void mouseButton(DeviceState state,String button,boolean down){
        if(down?!state.pressed.add(button):!state.pressed.remove(button))return;
        String edge="button:"+button+":"+(down?"press":"release");
        if(down){
            fire(state,edge);
            if("BTN_LEFT".equals(button)&&!router.hasEnabledBinding(edge)&&"immediate_plus_double".equals(mouseClickPolicy())&&router.hasEnabledBinding("mouse_left"))fire(state,"mouse_left");
            return;
        }
        fire(state,edge);
        if(!"BTN_LEFT".equals(button))return;
        long now=System.nanoTime();long window=mouseDoubleWindowNanos();long previous=state.lastLeftRelease;
        String policy=mouseClickPolicy();
        if(previous>0&&now-previous<=window){
            state.lastLeftRelease=0;ScheduledFuture<?> pending=state.pendingClicks.remove(button);if(pending!=null)pending.cancel(false);
            boolean immediateSingleFeedback="immediate_plus_double".equals(policy)&&hasSingleLeftClickBinding();
            if(!immediateSingleFeedback)fire(state,"mouse_double_left");
        }else{
            state.lastLeftRelease=now;
            if("wait_and_suppress".equals(policy)){
                state.pendingClicks.put(button,timers.schedule(()->{if(System.nanoTime()-state.lastLeftRelease>=window){fire(state,"mouse_left");state.pendingClicks.remove(button);}},window,TimeUnit.NANOSECONDS));
            }
        }
    }
    private boolean hasSingleLeftClickBinding(){
        return router.hasEnabledBinding("mouse_left")||router.hasEnabledBinding("button:BTN_LEFT:press")
                ||router.hasEnabledBinding("button:BTN_LEFT:release");
    }
    private boolean triggerWheel(boolean horizontal,int sign,DeviceState state){
        String event=horizontal?(sign>0?"wheel_right":"wheel_left"):(sign>0?"wheel_up":"wheel_down");
        String selected=router.hasEnabledBinding(event)?event:"group:builtin.mouse";
        return state.preview?router.preview(selected):router.trigger(selected);
    }
    private void fire(DeviceState state,String event){fire(state.preview,event);}
    private void fire(boolean preview,String event){if(preview)router.preview(event);else router.trigger(event);}
    private Map<String,Object> matchingCombo(Set<String> pressed,DeviceState state){
        return combos.stream().filter(c->{List<String> keys=stringList(c.get("keys"));return keys.size()>=2&&keys.size()<=4&&pressed.containsAll(keys)&&!state.firedCombos.contains(comboIdentity(c));}).max(java.util.Comparator.comparingInt(c->stringList(c.get("keys")).size())).orElse(null);
    }
    private static String comboIdentity(Map<String,Object> combo){return String.valueOf(combo.getOrDefault("id",stringList(combo.get("keys"))));}
    private boolean comboStillHeld(String identity,Set<String> pressed){return combos.stream().anyMatch(combo->comboIdentity(combo).equals(identity)&&pressed.containsAll(stringList(combo.get("keys"))));}
    private String groupFor(String key){
        if(config.get("groups") instanceof List<?> groups){
            for(String preferred:List.of("custom.function","custom.technical","custom.punctuation"))
                for(Object item:groups){Map<String,Object> group=map(item);if(preferred.equals(String.valueOf(group.get("id")))&&stringList(group.get("members")).contains(key))return preferred;}
            for(boolean custom:new boolean[]{false,true})for(Object item:groups){Map<String,Object> group=map(item);String id=String.valueOf(group.get("id"));if(id.startsWith("builtin.")!=custom&&stringList(group.get("members")).contains(key))return id;}
        }
        return null;
    }
    private boolean isModifier(String key){return key.contains("CTRL")||key.contains("SHIFT")||key.contains("ALT")||key.contains("META");}
    private boolean awaitsModifierSuppression(String key){return combos.stream().anyMatch(combo->stringList(combo.get("keys")).contains(key)&&("ONLY_COMBO".equals(String.valueOf(combo.getOrDefault("policy","ONLY_COMBO")))||(isModifier(key)&&"SUPPRESS_MODIFIERS".equals(combo.get("policy")))));}
    private static boolean isAlt(String key){return "KEY_LEFTALT".equals(key)||"KEY_RIGHTALT".equals(key);}
    private static boolean hasAlt(Set<String> keys){return keys.contains("KEY_LEFTALT")||keys.contains("KEY_RIGHTALT");}
    private long mouseDoubleWindowNanos(){return (long)(num(map(map(config.get("settings")).get("mouse_click_policy")).get("double_click_window_ms"),250)*1_000_000);}
    private String mouseClickPolicy(){return String.valueOf(map(map(config.get("settings")).get("mouse_click_policy")).getOrDefault("mode","immediate_plus_double"));}
    private int wheelUnits(){return Math.max(1,(int)Math.round(num(map(map(config.get("settings")).get("wheel")).get("units_per_detent"),120)));}
    private static boolean bool(Object o,boolean d){return o instanceof Boolean b?b:d;}
    private static double num(Object o,double d){return o instanceof Number n?n.doubleValue():d;}
    private static Map<String,Object> map(Object o){if(o instanceof Map<?,?>m){Map<String,Object>r=new HashMap<>();m.forEach((k,v)->r.put(String.valueOf(k),v));return r;}return Map.of();}
    private static List<String> stringList(Object o){return o instanceof List<?>l?l.stream().map(String::valueOf).toList():List.of();}
    @Override public void close(){timers.shutdownNow();devices.clear();}
    private static final Map<Integer,String> KEYS=Map.ofEntries(
            Map.entry(30,"KEY_A"),Map.entry(48,"KEY_B"),Map.entry(46,"KEY_C"),Map.entry(32,"KEY_D"),Map.entry(18,"KEY_E"),Map.entry(33,"KEY_F"),Map.entry(34,"KEY_G"),Map.entry(35,"KEY_H"),Map.entry(23,"KEY_I"),Map.entry(36,"KEY_J"),Map.entry(37,"KEY_K"),Map.entry(38,"KEY_L"),Map.entry(50,"KEY_M"),Map.entry(49,"KEY_N"),Map.entry(24,"KEY_O"),Map.entry(25,"KEY_P"),Map.entry(16,"KEY_Q"),Map.entry(19,"KEY_R"),Map.entry(31,"KEY_S"),Map.entry(20,"KEY_T"),Map.entry(22,"KEY_U"),Map.entry(47,"KEY_V"),Map.entry(17,"KEY_W"),Map.entry(45,"KEY_X"),Map.entry(21,"KEY_Y"),Map.entry(44,"KEY_Z"),
            Map.entry(12,"KEY_MINUS"),Map.entry(13,"KEY_EQUAL"),Map.entry(26,"KEY_LEFTBRACE"),Map.entry(27,"KEY_RIGHTBRACE"),Map.entry(43,"KEY_BACKSLASH"),Map.entry(39,"KEY_SEMICOLON"),Map.entry(40,"KEY_APOSTROPHE"),Map.entry(41,"KEY_GRAVE"),Map.entry(51,"KEY_COMMA"),Map.entry(52,"KEY_DOT"),Map.entry(53,"KEY_SLASH"),Map.entry(86,"KEY_102ND"),
            Map.entry(11,"KEY_0"),Map.entry(2,"KEY_1"),Map.entry(3,"KEY_2"),Map.entry(4,"KEY_3"),Map.entry(5,"KEY_4"),Map.entry(6,"KEY_5"),Map.entry(7,"KEY_6"),Map.entry(8,"KEY_7"),Map.entry(9,"KEY_8"),Map.entry(10,"KEY_9"),
            Map.entry(59,"KEY_F1"),Map.entry(60,"KEY_F2"),Map.entry(61,"KEY_F3"),Map.entry(62,"KEY_F4"),Map.entry(63,"KEY_F5"),Map.entry(64,"KEY_F6"),Map.entry(65,"KEY_F7"),Map.entry(66,"KEY_F8"),Map.entry(67,"KEY_F9"),Map.entry(68,"KEY_F10"),Map.entry(87,"KEY_F11"),Map.entry(88,"KEY_F12"),
            Map.entry(183,"KEY_F13"),Map.entry(184,"KEY_F14"),Map.entry(185,"KEY_F15"),Map.entry(186,"KEY_F16"),Map.entry(187,"KEY_F17"),Map.entry(188,"KEY_F18"),Map.entry(189,"KEY_F19"),Map.entry(190,"KEY_F20"),Map.entry(191,"KEY_F21"),Map.entry(192,"KEY_F22"),Map.entry(193,"KEY_F23"),Map.entry(194,"KEY_F24"),Map.entry(58,"KEY_CAPSLOCK"),Map.entry(69,"KEY_NUMLOCK"),Map.entry(70,"KEY_SCROLLLOCK"),Map.entry(464,"KEY_FN"),
            Map.entry(28,"KEY_ENTER"),Map.entry(57,"KEY_SPACE"),Map.entry(15,"KEY_TAB"),Map.entry(1,"KEY_ESC"),Map.entry(14,"KEY_BACKSPACE"),Map.entry(111,"KEY_DELETE"),Map.entry(110,"KEY_INSERT"),Map.entry(102,"KEY_HOME"),Map.entry(107,"KEY_END"),Map.entry(104,"KEY_PAGEUP"),Map.entry(109,"KEY_PAGEDOWN"),Map.entry(103,"KEY_UP"),Map.entry(108,"KEY_DOWN"),Map.entry(105,"KEY_LEFT"),Map.entry(106,"KEY_RIGHT"),
            Map.entry(29,"KEY_LEFTCTRL"),Map.entry(97,"KEY_RIGHTCTRL"),Map.entry(56,"KEY_LEFTALT"),Map.entry(100,"KEY_RIGHTALT"),Map.entry(42,"KEY_LEFTSHIFT"),Map.entry(54,"KEY_RIGHTSHIFT"),Map.entry(125,"KEY_LEFTMETA"),Map.entry(126,"KEY_RIGHTMETA"),
            Map.entry(113,"KEY_MUTE"),Map.entry(114,"KEY_VOLUMEDOWN"),Map.entry(115,"KEY_VOLUMEUP"),Map.entry(116,"KEY_POWER"),Map.entry(119,"KEY_PAUSE"),Map.entry(142,"KEY_SLEEP"),Map.entry(143,"KEY_WAKEUP"),Map.entry(158,"KEY_BACK"),Map.entry(159,"KEY_FORWARD"),Map.entry(163,"KEY_NEXTSONG"),Map.entry(164,"KEY_PLAYPAUSE"),Map.entry(165,"KEY_PREVIOUSSONG"),Map.entry(166,"KEY_STOPCD"),Map.entry(172,"KEY_HOMEPAGE"),Map.entry(217,"KEY_SEARCH"),Map.entry(224,"KEY_BRIGHTNESSDOWN"),Map.entry(225,"KEY_BRIGHTNESSUP"),Map.entry(248,"KEY_MICMUTE"),
            Map.entry(82,"KEY_KP0"),Map.entry(79,"KEY_KP1"),Map.entry(80,"KEY_KP2"),Map.entry(81,"KEY_KP3"),Map.entry(75,"KEY_KP4"),Map.entry(76,"KEY_KP5"),Map.entry(77,"KEY_KP6"),Map.entry(71,"KEY_KP7"),Map.entry(72,"KEY_KP8"),Map.entry(73,"KEY_KP9"),Map.entry(83,"KEY_KPDOT"),Map.entry(96,"KEY_KPENTER"),Map.entry(78,"KEY_KPPLUS"),Map.entry(74,"KEY_KPMINUS"),Map.entry(55,"KEY_KPASTERISK"),Map.entry(98,"KEY_KPSLASH"));
    private static final Map<Integer,String> BUTTONS=Map.of(272,"BTN_LEFT",273,"BTN_RIGHT",274,"BTN_MIDDLE",275,"BTN_SIDE",276,"BTN_EXTRA");
    interface EventSink{boolean hasEnabledBinding(String event);boolean trigger(String event);default boolean preview(String event){return trigger(event);}}
    private static final class DeviceState{final boolean preview;DeviceState(boolean preview){this.preview=preview;}final Set<String>pressed=new HashSet<>(),consumed=new HashSet<>(),firedCombos=new HashSet<>(),pendingModifiers=new HashSet<>();final Map<String,Long>lastRepeatNanos=new HashMap<>();final Map<String,ScheduledFuture<?>>pendingClicks=new HashMap<>();long lastLeftRelease;}
}
