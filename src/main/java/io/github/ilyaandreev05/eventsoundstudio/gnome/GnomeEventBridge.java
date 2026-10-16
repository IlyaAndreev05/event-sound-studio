package io.github.ilyaandreev05.eventsoundstudio.gnome;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receives only event names over the session bus. Window titles and application contents are ignored. */
public final class GnomeEventBridge implements AutoCloseable {
    public static final String OBJECT_PATH="/org/ilya/EventSoundStudio";
    private static final Pattern EVENT=Pattern.compile("(?:member=Event|org\\.ilya\\.EventSoundStudio\\.Event).*?\\('([^']+)'(?:,\\s*'[^']*')?\\)");
    private final AtomicBoolean closed=new AtomicBoolean();private final Consumer<String> event;private volatile Process process;
    public GnomeEventBridge(Consumer<String> event){this.event=event;}
    public void start(){Thread.ofVirtual().name("ess-gnome-dbus-monitor").start(this::run);}
    private void run(){while(!closed.get()){
        try{
            process=new ProcessBuilder("gdbus","monitor","--session","--dest","org.gnome.Shell","--object-path",OBJECT_PATH).redirectErrorStream(true).start();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){String line;boolean awaitingPayload=false;while(!closed.get()&&(line=reader.readLine())!=null){
                if(line.contains("member=Event")){awaitingPayload=true;continue;}
                Matcher matcher=EVENT.matcher((awaitingPayload?"member=Event ":"")+line);if(!matcher.find())continue;awaitingPayload=false;String name=matcher.group(1);
                switch(name){case "workspace_changed"->event.accept("desktop_changed");case "window_focus_changed"->event.accept("window_focus_changed");case "window_opened"->event.accept("window_opened");case "window_closed"->event.accept("window_closed");default->{}}
            }}
            if(!closed.get())Thread.sleep(1500);
        }catch(Exception e){if(!closed.get())try{Thread.sleep(3000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();return;}}
    }}
    @Override public void close(){closed.set(true);if(process!=null)process.destroyForcibly();}
}
