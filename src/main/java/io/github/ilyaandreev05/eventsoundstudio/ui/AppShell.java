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

public final class AppShell extends AppShellUiPart04 {
    public AppShell(ConfigStore store, AudioEngine engine, SoundLibrary library, EventRouter router,
                    EvdevDeviceScanner scanner, EvdevInputService input, InputEventDispatcher dispatcher,
                    LocalControlApi api, ThemeManager theme) {
        super(store, engine, library, router, scanner, input, dispatcher, api, theme);
    }
}
