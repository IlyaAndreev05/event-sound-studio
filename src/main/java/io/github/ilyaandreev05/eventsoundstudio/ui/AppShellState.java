package io.github.ilyaandreev05.eventsoundstudio.ui;
import io.github.ilyaandreev05.eventsoundstudio.ThemeManager;
import io.github.ilyaandreev05.eventsoundstudio.api.LocalControlApi;
import io.github.ilyaandreev05.eventsoundstudio.audio.AudioEngine;
import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;
import io.github.ilyaandreev05.eventsoundstudio.audio.PcmSound;
import io.github.ilyaandreev05.eventsoundstudio.audio.SoundLibrary;
import io.github.ilyaandreev05.eventsoundstudio.audio.SoundSynthesizer;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigArchive;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import io.github.ilyaandreev05.eventsoundstudio.gnome.GnomeEventBridge;
import io.github.ilyaandreev05.eventsoundstudio.gnome.GnomeFocusEventPolicy;
import io.github.ilyaandreev05.eventsoundstudio.input.EvdevDeviceScanner;
import io.github.ilyaandreev05.eventsoundstudio.input.EvdevInputService;
import io.github.ilyaandreev05.eventsoundstudio.input.InputEventDispatcher;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

abstract class AppShellState extends AppShellContract {

protected final ConfigStore store;protected final AudioEngine engine;protected final SoundLibrary library;protected final EventRouter router;
    protected final EvdevDeviceScanner scanner;protected final EvdevInputService input;protected final InputEventDispatcher dispatcher;protected final LocalControlApi api;protected final ThemeManager theme;
    protected final ExecutorService runtime=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ess-runtime-config");t.setDaemon(true);return t;});
    protected final VBox sidebar=new VBox(8);protected final BorderPane body=new BorderPane();protected final Label pageTitle=new Label();protected final Label engineStatus=new Label();protected final Label sourceStatus=new Label();protected final Label notice=new Label();protected final TextField search=new TextField();protected final Map<Page,ToggleButton> nav=new LinkedHashMap<>();protected final Set<String> selectedBindings=new java.util.HashSet<>();protected final Set<String> selectedSoundExports=new java.util.HashSet<>();protected Label soundExportCount;protected Button exportSoundSetButton;protected Runnable undoAction;protected String undoLabel="";
    protected volatile Page page=Page.HOME;protected volatile String audioState="Audio engine starting";protected volatile String sourceState="Global input is off";protected volatile LocalControlApi.Status apiState=new LocalControlApi.Status(false,-1,null);protected volatile String soundSetHash="";protected volatile List<String> audioOutputs=List.of("system_default");protected volatile List<AudioEngine.OutputOption> audioOutputOptions=List.of(new AudioEngine.OutputOption("system_default","system_default"));protected volatile boolean outputDiscoveryRunning;protected volatile boolean gnomeEnabled;protected volatile boolean runtimeClosed;protected volatile GnomeEventBridge gnome;


    protected AppShellState(ConfigStore store,AudioEngine engine,SoundLibrary library,EventRouter router,EvdevDeviceScanner scanner,EvdevInputService input,InputEventDispatcher dispatcher,LocalControlApi api,ThemeManager theme){
        this.store=store;this.engine=engine;this.library=library;this.router=router;this.scanner=scanner;this.input=input;this.dispatcher=dispatcher;this.api=api;this.theme=theme;
        getStyleClass().add("app-shell");buildChrome();render(Page.HOME);search.textProperty().addListener((o,a,b)->render(page));
        store.addListener(event->{if(runtimeClosed)return;if(event.message().startsWith("Configuration rejected:")){Platform.runLater(()->setNotice(event.message(),true));return;}applyRuntime(event.config());if("Configuration saved".equals(event.message()))return;Platform.runLater(()->{setNotice(event.message(),false);render(page);});});
    }
    protected static Path ensureWavSuffix(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")?path:path.resolveSibling(path.getFileName()+".wav");}
    protected static int selectedStart(double fraction,int frames){return Math.max(0,Math.min(Math.max(0,frames-1),(int)Math.floor(fraction*frames)));}
    protected static int selectedEnd(double fraction,int frames){return Math.max(0,Math.min(frames,(int)Math.ceil(fraction*frames)));}
    protected static String keyLabel(String key){return switch(key){case "KEY_ESC"->"Esc";case "KEY_TAB"->"Tab";case "KEY_BACKSPACE"->"⌫";case "KEY_ENTER"->"Enter";case "KEY_LEFTCTRL","KEY_RIGHTCTRL"->"Ctrl";case "KEY_LEFTSHIFT","KEY_RIGHTSHIFT"->"Shift";case "KEY_LEFTALT","KEY_RIGHTALT"->"Alt";case "KEY_LEFTMETA","KEY_RIGHTMETA"->"Super";case "KEY_SPACE"->"Space";case "KEY_MUTE"->"Mute";case "KEY_VOLUMEDOWN"->"Vol−";case "KEY_VOLUMEUP"->"Vol+";case "KEY_PLAYPAUSE"->"Play/Pause";case "KEY_STOPCD"->"Stop";case "KEY_PREVIOUSSONG"->"Prev";case "KEY_NEXTSONG"->"Next";case "KEY_MICMUTE"->"Mic mute";case "KEY_BRIGHTNESSDOWN"->"Bright−";case "KEY_BRIGHTNESSUP"->"Bright+";default->key.startsWith("KEY_")?key.substring(4):key;};}
    protected static Integer boundedInt(TextField field,int min,int max){try{int value=Integer.parseInt(field.getText().trim());return value>=min&&value<=max?value:null;}catch(Exception ignored){return null;}}
    protected static Double boundedDouble(TextField field,double minExclusive,double max){try{double value=Double.parseDouble(field.getText().trim());return Double.isFinite(value)&&value>minExclusive&&value<=max?value:null;}catch(Exception ignored){return null;}}
    public static String setting(Map<String,Object> c,String section,String key,String fallback){return string(map(c.get(section)).get(key),fallback);}
    protected static boolean matches(String text,String q){return q==null||q.isBlank()||String.valueOf(text).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));}
    protected static String soundSearchText(SoundLibrary.Entry entry){return entry.name()+" "+entry.id()+" "+entry.relativePath()+" "+entry.metadata().getOrDefault("description","")+" "+entry.metadata().getOrDefault("group","")+" "+entry.metadata().getOrDefault("tags","");}
    protected static List<String> parseTags(String text){List<String> tags=new ArrayList<>();Set<String> seen=new java.util.HashSet<>();for(String raw:text.split(",")){String tag=raw.trim();if(!tag.isBlank()&&seen.add(tag.toLowerCase(Locale.ROOT)))tags.add(tag);}return tags;}
    @SuppressWarnings("unchecked") protected static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String,Object>>) list : new ArrayList<>(); }
    @SuppressWarnings("unchecked") protected static List<Object> list(Object value) { return value instanceof List<?> list ? (List<Object>) list : new ArrayList<>(); }
    protected static Map<String,Object> map(Object o){if(o instanceof Map<?,?>m){Map<String,Object>r=new LinkedHashMap<>();m.forEach((k,v)->r.put(String.valueOf(k),v));return r;}return new LinkedHashMap<>();}
    protected static Map<String,Object> deepMap(Map<String,Object> m){Map<String,Object>r=new LinkedHashMap<>();m.forEach((k,v)->r.put(k,deepValue(v)));return r;}
    protected static Object deepValue(Object value){if(value instanceof Map<?,?> map){Map<String,Object> copy=new LinkedHashMap<>();map.forEach((k,v)->copy.put(String.valueOf(k),deepValue(v)));return copy;}if(value instanceof List<?> list){List<Object> copy=new ArrayList<>();list.forEach(item->copy.add(deepValue(item)));return copy;}return value;}
    protected static String string(Object o,String d){return o==null?d:String.valueOf(o);}
    protected static double num(Object o,double d){return o instanceof Number n?n.doubleValue():d;}
    protected static double parse(TextField f){return Double.parseDouble(f.getText());}
    protected static TextField text(String value,String accessible){TextField f=new TextField(value);f.setAccessibleText(accessible);return f;}
    protected static String soundHash(Map<String,Object> c){return maps(c.get("sounds")).stream().map(s->s.get("id")+"="+s.get("path")+"|"+s.get("name")+"|"+s.get("volume")+"|"+s.get("favorite")+"|"+s.get("group")+"|"+s.get("tags")+"|"+s.get("description")+"|"+s.get("original_path")).sorted().reduce("",(a,b)->a+";"+b);}
    protected static String message(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
