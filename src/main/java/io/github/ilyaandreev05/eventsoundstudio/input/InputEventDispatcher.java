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
    private final EventSink router;
    private final Map<String,DeviceState> devices=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ess-input-timers");t.setDaemon(true);return t;});
    private volatile Map<String,Object> config=Map.of();
    private volatile List<Map<String,Object>> combos=List.of();
    private final AtomicLong lastAltTabNanos=new AtomicLong(Long.MIN_VALUE);
    public InputEventDispatcher(EventRouter router){this(new EventSink(){public boolean hasEnabledBinding(String event){return router.hasEnabledBinding(event);}public boolean trigger(String event){return router.trigger(event);}});}
    InputEventDispatcher(EventSink router){this.router=router;}
    public synchronized void updateConfig(Map<String,Object> config){this.config=config;List<Map<String,Object>> next=new ArrayList<>();Object raw=config.get("combos");if(raw instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map){Map<String,Object> c=new HashMap<>();map.forEach((k,v)->c.put(String.valueOf(k),v));next.add(Map.copyOf(c));}combos=List.copyOf(next);}
    public void accept(EvdevInputService.RawEvent raw){
        if(raw.type()<0){reset(raw.deviceId());return;}
        if(raw.type()==0)return; // EV_SYN boundaries are consumed without clearing held keys.
        DeviceState state=devices.computeIfAbsent(raw.deviceId(),ignored->new DeviceState());
        synchronized(state){
            if(raw.type()==1){key(state,raw.code(),raw.value());}
            else if(raw.type()==2){wheel(state,raw.code(),raw.value());}
        }
    }
    /** Plays the configured key/group event without reading a physical input device. */
    public void previewKey(String key){triggerKey(key,"press");}
    /** Simulates one mouse click through the same normalization path used by evdev. */
    public void previewMouseButton(String button){Integer code=BUTTONS.entrySet().stream().filter(entry->entry.getValue().equals(button)).map(Map.Entry::getKey).findFirst().orElse(null);if(code==null)return;String device="ui-preview-mouse";accept(new EvdevInputService.RawEvent(device,1,code,1,System.nanoTime()));accept(new EvdevInputService.RawEvent(device,1,code,0,System.nanoTime()));}
    public void reset(String deviceId){DeviceState state=devices.remove(deviceId);if(state!=null)synchronized(state){state.pressed.clear();state.wheel.clear();state.highRes.clear();state.pendingClicks.values().forEach(f->f.cancel(false));state.pendingClicks.clear();}}
    int pressedKeyCount(String deviceId){DeviceState state=devices.get(deviceId);if(state==null)return 0;synchronized(state){return state.pressed.size();}}
    public boolean altTabPressedWithin(long windowMs){long last=lastAltTabNanos.get();if(last==Long.MIN_VALUE)return false;long elapsed=System.nanoTime()-last;return elapsed>=0&&elapsed<=TimeUnit.MILLISECONDS.toNanos(windowMs);}
    private void key(DeviceState state,int code,int value){
        String key=KEYS.get(code);String button=BUTTONS.get(code);
        if(key==null&&button==null)return;
        if(value==2){if(button==null)keyRepeat(state,key);return;}
        boolean down=value==1;
        if(button!=null){mouseButton(state,button,down);return;}
        if(down){
            if(!state.pressed.add(key))return;
            long now=System.nanoTime();
            if(("KEY_TAB".equals(key)&&hasAlt(state.pressed))||(isAlt(key)&&state.pressed.contains("KEY_TAB")))lastAltTabNanos.set(now);
            state.lastRepeatNanos.put(key,now);
            Map<String,Object> combo=matchingCombo(state.pressed,state);
            if(combo!=null){state.firedCombos.add(comboIdentity(combo));String event=String.valueOf(combo.getOrDefault("event",combo.get("id")));router.trigger(event);String policy=String.valueOf(combo.getOrDefault("policy","ONLY_COMBO"));
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
            if(!isModifier(key))triggerKey(key,"press");
        }else{
            state.pressed.remove(key);
            state.lastRepeatNanos.remove(key);
            state.firedCombos.removeIf(identity->!comboStillHeld(identity,state.pressed));
            if(state.consumed.remove(key))return;
            triggerKey(key,"release");
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
        triggerKey(key,"repeat");
    }
    private void triggerKey(String key,String state){
        String exact="key:"+key+":"+state;
        if(router.hasEnabledBinding(exact)){router.trigger(exact);return;}
        if("repeat".equals(state)){
            String press="key:"+key+":press";
            if(router.hasEnabledBinding(press)){router.trigger(press);return;}
            if(key.equals("KEY_ENTER")&&router.hasEnabledBinding("enter_press")){router.trigger("enter_press");return;}
            String group=groupFor(key);if(group!=null)router.trigger("group:"+group);
            return;
        }
        if(key.equals("KEY_ENTER")&&"press".equals(state)&&router.hasEnabledBinding("enter_press")){router.trigger("enter_press");return;}
        String group=groupFor(key);
        if(group!=null&&("press".equals(state)||("release".equals(state)&&"builtin.modifiers".equals(group))))router.trigger("group:"+group);
    }
    private void mouseButton(DeviceState state,String button,boolean down){
        String edge="button:"+button+":"+(down?"press":"release");
        if(down){
            router.trigger(edge);
            if("BTN_LEFT".equals(button)&&"immediate_plus_double".equals(mouseClickPolicy())&&router.hasEnabledBinding("mouse_left"))router.trigger("mouse_left");
            return;
        }
        router.trigger(edge);
        if(!"BTN_LEFT".equals(button))return;
        long now=System.nanoTime();long window=mouseDoubleWindowNanos();long previous=state.lastLeftRelease;
        String policy=mouseClickPolicy();
        if(previous>0&&now-previous<=window){
            state.lastLeftRelease=0;ScheduledFuture<?> pending=state.pendingClicks.remove(button);if(pending!=null)pending.cancel(false);
            router.trigger("mouse_double_left");
        }else{
            state.lastLeftRelease=now;
            if("wait_and_suppress".equals(policy)){
                state.pendingClicks.put(button,timers.schedule(()->{if(System.nanoTime()-state.lastLeftRelease>=window){router.trigger("mouse_left");state.pendingClicks.remove(button);}},window,TimeUnit.NANOSECONDS));
            }
        }
    }
    private void wheel(DeviceState state,int code,int value){
        if(value==0)return;
        boolean high=code==11||code==12;boolean horizontal=code==6||code==12;Map<String,Object> wheelConfig=map(map(config.get("settings")).get("wheel"));
        if(!(code==6||code==8||code==11||code==12))return;
        String axis=horizontal?"h":"v";
        if(high){if(!bool(wheelConfig.get("high_resolution_preferred"),true))return;state.highRes.add(axis);long total=state.wheel.getOrDefault(axis,0L)+value;int detent=wheelUnits();
            while(Math.abs(total)>=detent){int sign=Long.signum(total);triggerWheel(horizontal,sign);total-=sign*detent;}state.wheel.put(axis,total);
        }else{
            if(bool(map(map(config.get("settings")).get("wheel")).get("legacy_deduplication"),true)&&state.highRes.contains(axis))return;
            for(int i=0;i<Math.min(20,Math.abs((long)value));i++)triggerWheel(horizontal,Integer.signum(value));
        }
    }
    private void triggerWheel(boolean horizontal,int sign){
        String event=horizontal?(sign>0?"wheel_right":"wheel_left"):(sign>0?"wheel_up":"wheel_down");
        if(router.hasEnabledBinding(event))router.trigger(event);else router.trigger("group:builtin.mouse");
    }
    private Map<String,Object> matchingCombo(Set<String> pressed,DeviceState state){
        return combos.stream().filter(c->{List<String> keys=stringList(c.get("keys"));return keys.size()>=2&&pressed.containsAll(keys)&&!state.firedCombos.contains(comboIdentity(c));}).max(java.util.Comparator.comparingInt(c->stringList(c.get("keys")).size())).orElse(null);
    }
    private static String comboIdentity(Map<String,Object> combo){return String.valueOf(combo.getOrDefault("id",stringList(combo.get("keys"))));}
    private boolean comboStillHeld(String identity,Set<String> pressed){return combos.stream().anyMatch(combo->comboIdentity(combo).equals(identity)&&pressed.containsAll(stringList(combo.get("keys"))));}
    private String groupFor(String key){
        for(Map.Entry<String,List<String>> entry:GROUPS.entrySet())if(entry.getValue().contains(key))return entry.getKey();
        return null;
    }
    private boolean isModifier(String key){return key.contains("CTRL")||key.contains("SHIFT")||key.contains("ALT")||key.contains("META");}
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
    private static final Map<String,List<String>> GROUPS=Map.ofEntries(
            Map.entry("builtin.letters",letters()),Map.entry("builtin.digits",List.of("KEY_0","KEY_1","KEY_2","KEY_3","KEY_4","KEY_5","KEY_6","KEY_7","KEY_8","KEY_9")),
            Map.entry("builtin.function",List.of("KEY_F1","KEY_F2","KEY_F3","KEY_F4","KEY_F5","KEY_F6","KEY_F7","KEY_F8","KEY_F9","KEY_F10","KEY_F11","KEY_F12")),
            Map.entry("builtin.modifiers",List.of("KEY_LEFTCTRL","KEY_RIGHTCTRL","KEY_LEFTALT","KEY_RIGHTALT","KEY_LEFTSHIFT","KEY_RIGHTSHIFT","KEY_LEFTMETA","KEY_RIGHTMETA")),
            Map.entry("builtin.navigation",List.of("KEY_ENTER","KEY_SPACE","KEY_TAB","KEY_ESC","KEY_BACKSPACE","KEY_DELETE","KEY_INSERT","KEY_HOME","KEY_END","KEY_PAGEUP","KEY_PAGEDOWN","KEY_UP","KEY_DOWN","KEY_LEFT","KEY_RIGHT")),
            Map.entry("builtin.numpad",List.of("KEY_KP0","KEY_KP1","KEY_KP2","KEY_KP3","KEY_KP4","KEY_KP5","KEY_KP6","KEY_KP7","KEY_KP8","KEY_KP9","KEY_KPDOT","KEY_KPENTER","KEY_KPPLUS","KEY_KPMINUS","KEY_KPASTERISK","KEY_KPSLASH")));
    private static List<String> letters(){List<String>r=new ArrayList<>();for(char c='A';c<='Z';c++)r.add("KEY_"+c);return List.copyOf(r);}
    private static final Map<Integer,String> KEYS=Map.ofEntries(
            Map.entry(30,"KEY_A"),Map.entry(48,"KEY_B"),Map.entry(46,"KEY_C"),Map.entry(32,"KEY_D"),Map.entry(18,"KEY_E"),Map.entry(33,"KEY_F"),Map.entry(34,"KEY_G"),Map.entry(35,"KEY_H"),Map.entry(23,"KEY_I"),Map.entry(36,"KEY_J"),Map.entry(37,"KEY_K"),Map.entry(38,"KEY_L"),Map.entry(50,"KEY_M"),Map.entry(49,"KEY_N"),Map.entry(24,"KEY_O"),Map.entry(25,"KEY_P"),Map.entry(16,"KEY_Q"),Map.entry(19,"KEY_R"),Map.entry(31,"KEY_S"),Map.entry(20,"KEY_T"),Map.entry(22,"KEY_U"),Map.entry(47,"KEY_V"),Map.entry(17,"KEY_W"),Map.entry(45,"KEY_X"),Map.entry(21,"KEY_Y"),Map.entry(44,"KEY_Z"),
            Map.entry(11,"KEY_0"),Map.entry(2,"KEY_1"),Map.entry(3,"KEY_2"),Map.entry(4,"KEY_3"),Map.entry(5,"KEY_4"),Map.entry(6,"KEY_5"),Map.entry(7,"KEY_6"),Map.entry(8,"KEY_7"),Map.entry(9,"KEY_8"),Map.entry(10,"KEY_9"),
            Map.entry(59,"KEY_F1"),Map.entry(60,"KEY_F2"),Map.entry(61,"KEY_F3"),Map.entry(62,"KEY_F4"),Map.entry(63,"KEY_F5"),Map.entry(64,"KEY_F6"),Map.entry(65,"KEY_F7"),Map.entry(66,"KEY_F8"),Map.entry(67,"KEY_F9"),Map.entry(68,"KEY_F10"),Map.entry(87,"KEY_F11"),Map.entry(88,"KEY_F12"),
            Map.entry(28,"KEY_ENTER"),Map.entry(57,"KEY_SPACE"),Map.entry(15,"KEY_TAB"),Map.entry(1,"KEY_ESC"),Map.entry(14,"KEY_BACKSPACE"),Map.entry(111,"KEY_DELETE"),Map.entry(110,"KEY_INSERT"),Map.entry(102,"KEY_HOME"),Map.entry(107,"KEY_END"),Map.entry(104,"KEY_PAGEUP"),Map.entry(109,"KEY_PAGEDOWN"),Map.entry(103,"KEY_UP"),Map.entry(108,"KEY_DOWN"),Map.entry(105,"KEY_LEFT"),Map.entry(106,"KEY_RIGHT"),
            Map.entry(29,"KEY_LEFTCTRL"),Map.entry(97,"KEY_RIGHTCTRL"),Map.entry(56,"KEY_LEFTALT"),Map.entry(100,"KEY_RIGHTALT"),Map.entry(42,"KEY_LEFTSHIFT"),Map.entry(54,"KEY_RIGHTSHIFT"),Map.entry(125,"KEY_LEFTMETA"),Map.entry(126,"KEY_RIGHTMETA"),
            Map.entry(113,"KEY_MUTE"),Map.entry(114,"KEY_VOLUMEDOWN"),Map.entry(115,"KEY_VOLUMEUP"),Map.entry(116,"KEY_POWER"),Map.entry(119,"KEY_PAUSE"),Map.entry(142,"KEY_SLEEP"),Map.entry(143,"KEY_WAKEUP"),Map.entry(158,"KEY_BACK"),Map.entry(159,"KEY_FORWARD"),Map.entry(163,"KEY_NEXTSONG"),Map.entry(164,"KEY_PLAYPAUSE"),Map.entry(165,"KEY_PREVIOUSSONG"),Map.entry(166,"KEY_STOPCD"),Map.entry(172,"KEY_HOMEPAGE"),Map.entry(217,"KEY_SEARCH"),Map.entry(224,"KEY_BRIGHTNESSDOWN"),Map.entry(225,"KEY_BRIGHTNESSUP"),Map.entry(248,"KEY_MICMUTE"),
            Map.entry(82,"KEY_KP0"),Map.entry(79,"KEY_KP1"),Map.entry(80,"KEY_KP2"),Map.entry(81,"KEY_KP3"),Map.entry(75,"KEY_KP4"),Map.entry(76,"KEY_KP5"),Map.entry(77,"KEY_KP6"),Map.entry(71,"KEY_KP7"),Map.entry(72,"KEY_KP8"),Map.entry(73,"KEY_KP9"),Map.entry(83,"KEY_KPDOT"),Map.entry(96,"KEY_KPENTER"),Map.entry(78,"KEY_KPPLUS"),Map.entry(74,"KEY_KPMINUS"),Map.entry(55,"KEY_KPASTERISK"),Map.entry(98,"KEY_KPSLASH"));
    private static final Map<Integer,String> BUTTONS=Map.of(272,"BTN_LEFT",273,"BTN_RIGHT",274,"BTN_MIDDLE",275,"BTN_SIDE",276,"BTN_EXTRA");
    interface EventSink{boolean hasEnabledBinding(String event);boolean trigger(String event);}
    private static final class DeviceState{final Set<String>pressed=new HashSet<>(),consumed=new HashSet<>(),highRes=new HashSet<>(),firedCombos=new HashSet<>();final Map<String,Long>wheel=new HashMap<>(),lastRepeatNanos=new HashMap<>();final Map<String,ScheduledFuture<?>>pendingClicks=new HashMap<>();long lastLeftRelease;}
}
