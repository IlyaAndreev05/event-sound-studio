package io.github.ilyaandreev05.eventsoundstudio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.eventsoundstudio.api.LocalControlApi;
import io.github.ilyaandreev05.eventsoundstudio.audio.AudioEngine;
import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;
import io.github.ilyaandreev05.eventsoundstudio.audio.SoundLibrary;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigMigrator;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigValidator;
import io.github.ilyaandreev05.eventsoundstudio.gnome.GnomeEventBridge;
import io.github.ilyaandreev05.eventsoundstudio.input.EvdevDeviceScanner;
import io.github.ilyaandreev05.eventsoundstudio.input.EvdevInputService;
import io.github.ilyaandreev05.eventsoundstudio.input.InputEventDispatcher;
import io.github.ilyaandreev05.eventsoundstudio.ui.AppShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class EventSoundStudioApp extends Application {
    private ConfigStore store;private AudioEngine engine;private SoundLibrary library;private EventRouter router;private EvdevInputService input;
    private InputEventDispatcher dispatcher;private LocalControlApi api;private GnomeEventBridge gnome;private ThemeManager theme;private AppShell shell;

    public static void main(String[] args){
        if(args.length>0&&"--validate-config".equals(args[0])){validate(args.length>1?Path.of(args[1]):ConfigStore.defaultPath());System.exit(0);return;}
        if(args.length>0&&"--export-default-config".equals(args[0])){exportDefault(args.length>1?Path.of(args[1]):Path.of("config.example.json"));System.exit(0);return;}
        if(args.length>0&&"--diagnose-audio".equals(args[0])){diagnoseAudio();System.exit(0);return;}
        if("root".equals(System.getProperty("user.name"))){System.err.println("Event Sound Studio refuses to run as root. Start it in the logged-in desktop session.");System.exit(2);}
        launch(args);
    }
    private static void validate(Path path){try{ObjectMapper mapper=path.getFileName().toString().endsWith(".yaml")||path.getFileName().toString().endsWith(".yml")?new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()):new ObjectMapper();Map<String,Object> config=mapper.readValue(path.toFile(),new TypeReference<>(){});ConfigMigrator.migrateToCurrentSchema(config,true);List<String> errors=ConfigValidator.validate(config);if(errors.isEmpty())System.out.println("Configuration is valid after supported migrations: "+path.toAbsolutePath());else{errors.forEach(e->System.err.println("- "+e));System.exit(1);}}catch(Exception e){System.err.println("Configuration could not be validated: "+e.getMessage());System.exit(1);}}
    private static void diagnoseAudio(){System.out.println("Java Sound outputs (enumeration only; no output line opened and no audio played):");System.out.println("Default mixer: "+AudioEngine.defaultOutputName());System.out.println("Explicit mixers:");for(AudioEngine.OutputProbe output:AudioEngine.probeOutputs())System.out.println("- "+output.selector()+" | "+output.name()+" | "+output.vendor()+" | "+output.description()+" | "+output.version()+" | source line types="+output.sourceLineTypes()+" | 48 kHz/16-bit/stereo="+(output.supportsPcm48Stereo()?"supported":"not reported"));}
    private static void exportDefault(Path path){try{if(path.getFileName().toString().endsWith(".yaml")||path.getFileName().toString().endsWith(".yml"))throw new IllegalArgumentException("YAML is supported only as an import format; choose a .json path");new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig.create());System.out.println("Wrote default configuration: "+path.toAbsolutePath());}catch(Exception e){System.err.println("Could not write default configuration: "+e.getMessage());System.exit(1);}}
    @Override public void start(Stage stage)throws Exception{
        store=new ConfigStore(ConfigStore.defaultPath());engine=new AudioEngine();library=new SoundLibrary(store);router=new EventRouter(engine,library);
        EvdevDeviceScanner scanner=new EvdevDeviceScanner();input=new EvdevInputService(scanner);dispatcher=new InputEventDispatcher(router);api=new LocalControlApi(router,ignored->{});theme=new ThemeManager();
        shell=new AppShell(store,engine,library,router,scanner,input,dispatcher,api,theme);
        Scene scene=new Scene(shell,1320,850);scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        theme.apply(scene,AppShell.setting(store.snapshot(),"settings","theme","system"));stage.setTitle("Event Sound Studio");stage.setMinWidth(1024);stage.setMinHeight(680);stage.setScene(scene);stage.show();shell.start();stage.setOnCloseRequest(e->shell.close());
    }
    @Override public void stop(){if(shell!=null)shell.close();}
}
