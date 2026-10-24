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

abstract class AppShellUiPart04 extends AppShellUiPart03 {
    protected AppShellUiPart04(ConfigStore store, AudioEngine engine, SoundLibrary library, EventRouter router, EvdevDeviceScanner scanner, EvdevInputService input, InputEventDispatcher dispatcher, LocalControlApi api, ThemeManager theme) { super(store, engine, library, router, scanner, input, dispatcher, api, theme); }

    protected void confirmArchiveImport(ConfigArchive.PreparedImport prepared){
        boolean profileImport=prepared.profileArchive(),soundSetImport=prepared.soundSetArchive();
        List<String> available=List.copyOf(audioOutputs);
        List<String> missingOutputs=!profileImport&&!soundSetImport?prepared.unavailableOutputSelectors(available):List.of();
        String action=profileImport
                ?t("Продолжить? Профиль будет добавлен к текущей конфигурации с новыми ID при совпадениях. Текущий профиль и настройки сохранятся; импортированный профиль не станет активным.","Continue? The profile will be added to the current configuration with new IDs for conflicts. Your active profile and settings will be preserved; the imported profile will stay inactive.")
                :soundSetImport
                        ?t("Продолжить? Звуки будут объединены с текущей библиотекой; совпадающие ID и их ссылки будут переназначены. Профили, настройки и выбранный сейчас профиль сохранятся.","Continue? Sounds will be merged into the current library; conflicting IDs and their references will be remapped. Profiles, settings, and the active profile will be preserved.")
                        :t("Продолжить? Полный backup заменит JSON-конфигурацию. Текущие звуковые файлы останутся; совпадающие ресурсы будут переиспользованы. Глобальная клавиатура, мышь, GNOME и API останутся выключены.","Continue? The full backup will replace the JSON configuration. Existing sound files remain; matching resources will be reused. Global keyboard, mouse, GNOME, and API sources will stay disabled.");
        String header=profileImport?t("Профильный архив проверен","Profile archive validated"):soundSetImport?t("Набор звуков проверен","Sound set validated"):t("Архив проверен","Archive validated");
        String missingNote=missingOutputs.isEmpty()?"":"\n\n"+t("Выходы, которых нет на этом компьютере: ","Outputs not found on this computer: ")+String.join(", ",missingOutputs)+". "+t("После подтверждения можно выбрать замену.","You can choose replacements after confirming.");
        Alert confirm=new Alert(Alert.AlertType.CONFIRMATION);confirm.initOwner(getScene().getWindow());confirm.setTitle(t("Предпросмотр импорта","Import preview"));confirm.setHeaderText(header);confirm.setContentText(t("Звуков в ZIP: ","Sounds in ZIP: ")+prepared.soundCount()+"\n"+t("Профилей: ","Profiles: ")+prepared.profileCount()+"\n"+t("Привязок: ","Bindings: ")+prepared.bindingCount()+"\n"+t("WAV-файлов в ZIP: ","WAV files in ZIP: ")+prepared.assetCount()+"\n\n"+action+missingNote);
        confirm.showAndWait().filter(button->button==ButtonType.OK).ifPresentOrElse(button->{
            if(missingOutputs.isEmpty())installPreparedImport(prepared,Map.of(),available);
            else reviewOutputRemapping(prepared,missingOutputs,available);
        },prepared::close);
    }

    protected void reviewOutputRemapping(ConfigArchive.PreparedImport prepared,List<String> missing,List<String> available){
        Dialog<Map<String,String>> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Сопоставление аудиовыходов","Audio output remapping"));dialog.setHeaderText(t("Выходы из архива не найдены","Some archived outputs are unavailable"));
        ButtonType importButton=new ButtonType(t("Импортировать","Import"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(importButton,ButtonType.CANCEL);
        VBox content=new VBox(10);content.setPadding(new Insets(8,4,4,4));content.getChildren().add(labelNode(t("Выберите доступный выход для каждого старого назначения. Оставьте поле пустым, чтобы сохранить недоступный выбор и назначить устройство позже.","Choose an available output for each old selector. Leave a field blank to preserve it as unavailable and assign a device later."),"muted"));
        Map<String,ComboBox<String>> choices=new LinkedHashMap<>();
        for(String selector:missing){
            Label old=new Label(outputName(selector));old.setWrapText(true);old.setMinWidth(170);old.setMaxWidth(260);old.setAccessibleText(t("Выход из архива, которого нет на этом компьютере","Archived output missing on this computer")+": "+outputName(selector));
            ComboBox<String> replacement=new ComboBox<>();replacement.getItems().setAll(available);replacement.setPromptText(t("Оставить без замены","Keep unavailable"));replacement.setAccessibleText(t("Новый выход вместо ","Replacement for ")+selector);replacement.setButtonCell(outputCell());replacement.setCellFactory(list->outputCell());HBox row=new HBox(12,old,replacement);row.setAlignment(Pos.CENTER_LEFT);choices.put(selector,replacement);content.getChildren().add(row);
        }
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefWidth(600);
        dialog.setResultConverter(button->{if(button!=importButton)return null;Map<String,String> remapping=new LinkedHashMap<>();choices.forEach((old,choice)->{if(choice.getValue()!=null)remapping.put(old,choice.getValue());});return remapping;});
        dialog.showAndWait().ifPresentOrElse(remapping->installPreparedImport(prepared,remapping,available),()->{prepared.close();setNotice(t("Импорт отменён.","Import cancelled."),false);});
    }

    protected void installPreparedImport(ConfigArchive.PreparedImport prepared,Map<String,String> remapping,List<String> available){
        setNotice(t("Импортирую данные и WAV…","Importing data and WAV assets…"),false);
        runtime.execute(()->{try{if(!remapping.isEmpty())prepared.remapOutputSelectors(remapping,available);ConfigArchive.install(prepared,store.dataDirectory(),store);Platform.runLater(()->{setNotice(t("Импорт завершён.","Import complete."),false);render(prepared.soundSetArchive()?Page.SOUNDS:Page.PROFILES);});}catch(Exception e){Platform.runLater(()->setNotice(t("Импорт не выполнен: ","Import failed: ")+message(e),true));}finally{prepared.close();}});
    }

    protected VBox settingsPage(){
        VBox root=page(t("Настройки, локальный API и диагностика аудио/разрешений.","Preferences, local API, and audio/permission diagnostics."));Map<String,Object> settings=map(store.snapshot().get("settings"));
        VBox prefs=card(t("Интерфейс","Interface"));ComboBox<String> language=new ComboBox<>();language.getItems().addAll("ru","en");language.setValue(string(settings.get("language"),"ru"));language.setAccessibleText(t("Язык","Language"));language.setOnAction(e->mutate(c->{Map<String,Object> updated=map(c.get("settings"));updated.put("language",language.getValue());c.put("settings",updated);}));
        ComboBox<String> themeBox=new ComboBox<>();themeBox.getItems().addAll("system","light","dark");themeBox.setValue(string(settings.get("theme"),"system"));themeBox.setAccessibleText(t("Тема","Theme"));themeBox.setOnAction(e->{mutate(c->{Map<String,Object> updated=map(c.get("settings"));updated.put("theme",themeBox.getValue());c.put("settings",updated);});theme.apply(getScene(),themeBox.getValue());});
        Label languageLabel=labelNode(t("Язык","Language"),"");languageLabel.setLabelFor(language);Label themeLabel=labelNode(t("Тема: системная, светлая или тёмная","Theme: system, light, or dark"),"");themeLabel.setLabelFor(themeBox);
        ComboBox<String> backgroundMode=new ComboBox<>();backgroundMode.getItems().setAll("continue_audio","exit");backgroundMode.setValue(string(settings.get("background_mode"),"continue_audio"));backgroundMode.setAccessibleText(t("Закрытие окна","Window close behavior"));
        backgroundMode.setOnAction(e->mutate(c->{Map<String,Object> updated=map(c.get("settings"));updated.put("background_mode",backgroundMode.getValue());c.put("settings",updated);}));
        prefs.getChildren().addAll(languageLabel,language,themeLabel,themeBox,labelNode(t("При закрытии: continue_audio — скрыть окно; exit — завершить","On close: continue_audio hides the window; exit stops the app"),"muted"),backgroundMode,button(t("Завершить приложение и остановить события","Quit application and stop events"),()->quitAction.run()));root.getChildren().add(prefs);
        VBox eventPrefs=card(t("Политика мыши и колеса","Mouse and wheel behavior"));Map<String,Object> clickPolicy=map(settings.get("mouse_click_policy")),wheelConfig=map(settings.get("wheel"));ComboBox<String> clickMode=new ComboBox<>();clickMode.getItems().setAll("immediate_plus_double","wait_and_suppress");clickMode.setValue(string(clickPolicy.get("mode"),"immediate_plus_double"));clickMode.setAccessibleText(t("Политика одиночного/двойного клика","Single/double-click policy"));clickMode.setButtonCell(clickModeCell());clickMode.setCellFactory(list->clickModeCell());
        TextField doubleWindow=text(String.valueOf(clickPolicy.getOrDefault("double_click_window_ms",250)),t("Окно двойного клика, 50–1500 мс","Double-click window, 50–1500 ms")),wheelUnits=text(String.valueOf(wheelConfig.getOrDefault("units_per_detent",120)),t("Units на одно деление, (0, 10000]","Units per detent, (0, 10000]"));CheckBox highResolution=new CheckBox(t("Предпочитать колесо высокого разрешения","Prefer high-resolution wheel")),legacyDedup=new CheckBox(t("Не дублировать legacy после high-res","Deduplicate legacy events after high-res"));highResolution.setSelected(Boolean.TRUE.equals(wheelConfig.get("high_resolution_preferred")));legacyDedup.setSelected(Boolean.TRUE.equals(wheelConfig.get("legacy_deduplication")));
        Label windowError=labelNode("","danger"),unitsError=labelNode("","danger");Button saveEventPrefs=button(t("Сохранить поведение ввода","Save input behavior"),()->{});Runnable validateEventPrefs=()->{Integer window=boundedInt(doubleWindow,50,1500);Double units=boundedDouble(wheelUnits,0,10_000);windowError.setText(window==null?t("Введите целое число 50–1500.","Enter an integer from 50 to 1500."):"");unitsError.setText(units==null?t("Введите конечное число больше 0 и не больше 10000.","Enter a finite number greater than 0 and at most 10000."):"");windowError.setManaged(window==null);windowError.setVisible(window==null);unitsError.setManaged(units==null);unitsError.setVisible(units==null);saveEventPrefs.setDisable(window==null||units==null||clickMode.getValue()==null);};
        doubleWindow.textProperty().addListener((o,a,v)->validateEventPrefs.run());wheelUnits.textProperty().addListener((o,a,v)->validateEventPrefs.run());clickMode.valueProperty().addListener((o,a,v)->validateEventPrefs.run());validateEventPrefs.run();saveEventPrefs.setOnAction(e->{Integer window=boundedInt(doubleWindow,50,1500);Double units=boundedDouble(wheelUnits,0,10_000);if(window==null||units==null||clickMode.getValue()==null)return;mutate(c->{Map<String,Object> cfg=map(c.get("settings"));cfg.put("mouse_click_policy",DefaultConfig.map("mode",clickMode.getValue(),"double_click_window_ms",window));Map<String,Object> wheel=map(cfg.get("wheel"));wheel.put("units_per_detent",units);wheel.put("high_resolution_preferred",highResolution.isSelected());wheel.put("legacy_deduplication",legacyDedup.isSelected());cfg.put("wheel",wheel);c.put("settings",cfg);});});
        Label clickModeLabel=labelNode(t("Политика клика","Click policy"),"");clickModeLabel.setLabelFor(clickMode);Label doubleWindowLabel=labelNode(t("Окно двойного клика, мс","Double-click window, ms"),"");doubleWindowLabel.setLabelFor(doubleWindow);Label wheelUnitsLabel=labelNode(t("Единиц на одно деление колеса","Units per wheel detent"),"");wheelUnitsLabel.setLabelFor(wheelUnits);
        eventPrefs.getChildren().addAll(labelNode(t("Одиночный клик срабатывает сразу или после окна ожидания; двойной клик дополнительно имеет собственное событие.","A single click can fire immediately or after a wait; double-click has a separate event."),"muted"),clickModeLabel,clickMode,doubleWindowLabel,doubleWindow,windowError,wheelUnitsLabel,wheelUnits,unitsError,highResolution,legacyDedup,saveEventPrefs);root.getChildren().add(eventPrefs);
        VBox repeatPrefs=card(t("Повторы клавиш","Keyboard repeats"));Map<String,Object> repeatConfig=map(settings.get("keyboard_repeat"));ComboBox<String> repeatMode=new ComboBox<>();repeatMode.getItems().setAll("ignore","throttle");repeatMode.setValue(string(repeatConfig.get("mode"),"ignore"));repeatMode.setAccessibleText(t("Политика повторов клавиш","Keyboard repeat policy"));repeatMode.setButtonCell(keyboardRepeatModeCell());repeatMode.setCellFactory(list->keyboardRepeatModeCell());
        TextField repeatInterval=text(String.valueOf(repeatConfig.getOrDefault("minimum_interval_ms",180)),t("Минимальный интервал повтора, 50–5000 мс","Minimum repeat interval, 50–5000 ms"));Label repeatIntervalError=labelNode("","danger");Button saveRepeat=button(t("Сохранить повторы клавиш","Save keyboard repeat policy"),()->{});
        Runnable validateRepeat=()->{Integer interval=boundedInt(repeatInterval,50,5000);repeatIntervalError.setText(interval==null?t("Введите целое число 50–5000.","Enter an integer from 50 to 5000."):"");repeatIntervalError.setManaged(interval==null);repeatIntervalError.setVisible(interval==null);saveRepeat.setDisable(interval==null||repeatMode.getValue()==null);};
        repeatInterval.textProperty().addListener((o,a,v)->validateRepeat.run());repeatMode.valueProperty().addListener((o,a,v)->validateRepeat.run());validateRepeat.run();saveRepeat.setOnAction(e->{Integer interval=boundedInt(repeatInterval,50,5000);if(interval==null||repeatMode.getValue()==null)return;mutate(c->{Map<String,Object> cfg=map(c.get("settings"));cfg.put("keyboard_repeat",DefaultConfig.map("mode",repeatMode.getValue(),"minimum_interval_ms",interval));c.put("settings",cfg);});});
        Label repeatModeLabel=labelNode(t("Режим повторов","Repeat mode"),"");repeatModeLabel.setLabelFor(repeatMode);Label repeatIntervalLabel=labelNode(t("Минимальный интервал, мс","Minimum interval, ms"),"");repeatIntervalLabel.setLabelFor(repeatInterval);
        repeatPrefs.getChildren().addAll(labelNode(t("Повторы evdev можно игнорировать или пропускать не чаще заданного интервала. Начальное нажатие всегда обрабатывается отдельно.","Evdev repeats can be ignored or allowed no more often than the chosen interval. The initial key press is always handled separately."),"muted"),repeatModeLabel,repeatMode,repeatIntervalLabel,repeatInterval,repeatIntervalError,saveRepeat);root.getChildren().add(repeatPrefs);
        VBox apiCard=card(t("Локальный API","Local API"));CheckBox apiEnabled=new CheckBox(t("Разрешить локальный API play/trigger","Enable local play/trigger API"));apiEnabled.setSelected(api.enabled());apiEnabled.setOnAction(e->{try{if(apiEnabled.isSelected()){apiState=api.enable();}else{api.disable();apiState=api.status();}mutate(c->{Map<String,Object> updated=map(c.get("api"));updated.put("enabled",apiEnabled.isSelected());c.put("api",updated);});render(Page.SETTINGS);}catch(Exception ex){apiEnabled.setSelected(false);setNotice(t("API не запущен: ","API could not start: ")+message(ex),true);}});
        apiCard.getChildren().addAll(labelNode(t("Выключен по умолчанию. Слушает только 127.0.0.1; случайный bearer-токен существует только в памяти и отображается здесь.","Disabled by default. Binds only to 127.0.0.1; a random bearer token exists only in memory and is displayed here."),"muted"),apiEnabled);
        if(api.enabled()){apiCard.getChildren().addAll(labelNode("http://127.0.0.1:"+apiState.port()+"/v1/play","accent"),labelNode("Bearer "+apiState.token(),"muted"),labelNode("POST /v1/play {\"bindingId\":\"custom-test-api\"} · POST /v1/trigger {\"eventId\":\"api_test\"}",""));}
        root.getChildren().add(apiCard);
        root.getChildren().add(new RuntimeDiagnosticsPane(engine,router,library,input,dispatcher,()->gnome,store,this::mutate,t("Диагностика и калибровка колеса","Diagnostics and wheel calibration")));
        return root;
    }

    protected void loadSounds(Map<String,Object> config){String hash=soundHash(config);soundSetHash=hash;library.loadAll(maps(config.get("sounds"))).whenComplete((entries,error)->Platform.runLater(()->{if(runtimeClosed)return;if(error!=null)setNotice(message(error),true);else{Set<String> available=new java.util.HashSet<>();entries.stream().filter(SoundLibrary.Entry::ready).forEach(entry->available.add(entry.id()));selectedSoundExports.retainAll(available);refreshSoundExportSelection();router.update(store.snapshot());setNotice(t("Звуковая библиотека загружена.","Sound library loaded."),false);requestShellRefresh();}}));}

    protected void applyRuntime(Map<String,Object> config){
        if(runtimeClosed)return;
        try{runtime.execute(()->{
            if(runtimeClosed)return;
            String nextHash=soundHash(config);if(!nextHash.equals(soundSetHash)){soundSetHash=nextHash;library.loadAll(maps(config.get("sounds"))).thenRun(()->router.update(store.snapshot()));}
            router.update(config);dispatcher.updateConfig(config);
            Map<String,Object> settings=map(config.get("settings"));Map<String,Object> inputSettings=map(settings.get("input"));Set<String> allow=new java.util.HashSet<>(list(inputSettings.get("device_allowlist")).stream().map(String::valueOf).toList());
            input.configure(Boolean.TRUE.equals(inputSettings.get("enable_keyboard")),Boolean.TRUE.equals(inputSettings.get("enable_mouse")),allow,dispatcher::accept,value->{if(value==null||value.equals(sourceState))return;sourceState=value;Platform.runLater(()->{if(runtimeClosed||!value.equals(sourceState))return;if(!uiVisible()){refreshDirty.set(true);return;}sourceStatus.setText(value);requestStatusRefresh();});});
            boolean apiWanted=Boolean.TRUE.equals(map(config.get("api")).get("enabled"));try{if(apiWanted&&!api.enabled())apiState=api.enable();else if(!apiWanted&&api.enabled()){api.disable();apiState=api.status();}}catch(Exception e){sourceState="Local API unavailable: "+message(e);}
            boolean gnomeWanted=Boolean.TRUE.equals(inputSettings.get("enable_gnome"));if(gnomeWanted&&!gnomeEnabled){gnome=new GnomeEventBridge(this::routeGnomeEvent);gnome.start();gnomeEnabled=true;}else if(!gnomeWanted&&gnomeEnabled){gnome.close();gnome=null;gnomeEnabled=false;}
            Platform.runLater(()->requestRenderIf(Page.SETTINGS));
        });}catch(java.util.concurrent.RejectedExecutionException stopped){if(!runtimeClosed)throw stopped;}
    }

    protected void routeGnomeEvent(String event) {
        Map<String,Object> inputSettings = map(map(store.snapshot().get("settings")).get("input"));
        boolean deduplicate = Boolean.TRUE.equals(inputSettings.get("deduplicate_alt_tab_focus"));
        boolean recentAltTab = dispatcher.altTabPressedWithin(GnomeFocusEventPolicy.ALT_TAB_WINDOW_MS);
        if (!GnomeFocusEventPolicy.shouldSuppress(event, deduplicate, recentAltTab)) router.trigger(event);
    }

    protected void mutate(Consumer<Map<String,Object>> change){ConfigStore.VersionedSnapshot snapshot=store.versionedSnapshot();Map<String,Object> config=snapshot.config();change.accept(config);store.saveAsync(config,snapshot.revision()).whenComplete((v,error)->Platform.runLater(()->{if(error!=null)setNotice(t("Изменения не сохранены: ","Changes were not saved: ")+message(error),true);else{setNotice(t("Изменения сохранены в config.json.","Changes saved to config.json."),false);}}));}

    protected void editProfileVolume(String id){Map<String,Object> profile=findProfile(store.snapshot(),id);if(profile==null)return;TextInputDialog dialog=new TextInputDialog(String.valueOf(num(profile.get("master_volume"),.65)));dialog.setTitle(t("Громкость профиля","Profile volume"));dialog.setHeaderText(String.valueOf(profile.get("name")));dialog.setContentText(t("Коэффициент от 0 до 1","Volume from 0 to 1"));dialog.showAndWait().ifPresent(value->{try{double volume=Double.parseDouble(value.trim());if(!Double.isFinite(volume)||volume<0||volume>1)throw new NumberFormatException();mutate(c->findProfile(c,id).put("master_volume",volume));}catch(NumberFormatException e){setNotice(t("Введите число от 0 до 1.","Enter a number from 0 to 1."),true);}});}

    protected void renameProfile(String id){Map<String,Object> profile=findProfile(store.snapshot(),id);if(profile==null)return;TextInputDialog dialog=new TextInputDialog(String.valueOf(profile.get("name")));dialog.setTitle(t("Переименовать профиль","Rename profile"));dialog.setHeaderText(null);dialog.setContentText(t("Имя","Name"));dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->mutate(c->findProfile(c,id).put("name",name)));}

    protected void deleteProfile(String id){Map<String,Object> current=store.snapshot();if(maps(current.get("profiles")).size()<=1){setNotice(t("Нельзя удалить последний профиль.","The last profile cannot be deleted."),true);return;}Map<String,Object> profile=findProfile(current,id);if(profile==null)return;Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,t("Удалить профиль «","Delete profile ")+profile.get("name")+"?",javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);confirm.initOwner(getScene().getWindow());confirm.setHeaderText(null);confirm.showAndWait().filter(button->button==javafx.scene.control.ButtonType.OK).ifPresent(button->mutate(c->{List<Map<String,Object>> profiles=maps(c.get("profiles"));profiles.removeIf(item->id.equals(item.get("id")));if(id.equals(c.get("active_profile")))c.put("active_profile",String.valueOf(profiles.getFirst().get("id")));Map<String,Object> overrides=new LinkedHashMap<>(map(c.get("source_profiles")));overrides.values().removeIf(value->id.equals(String.valueOf(value)));c.put("source_profiles",overrides);}));}

    protected void setNotice(String text,boolean error){notice.setText(text);notice.setWrapText(true);notice.getStyleClass().removeAll("error-banner","warning","accent");notice.getStyleClass().add(error?"error-banner":"status-pill");notice.setPadding(new Insets(9,22,9,22));notice.setVisible(true);notice.setManaged(true);body.setBottom(notice);}

    protected boolean profileEnabled(String bindingId,String event){Map<String,Object> config=store.snapshot();String source=eventSourceCategory(event,config);Map<String,Object> overrides=map(config.get("source_profiles"));Map<String,Object> profile=findProfile(config,String.valueOf(overrides.getOrDefault(source,config.get("active_profile"))));return profile!=null&&list(profile.get("enabled_bindings")).contains(bindingId);}

    protected String eventSourceCategory(String eventId,Map<String,Object> config){if(eventId.startsWith("key:"))return "keyboard";if(eventId.startsWith("button:")||eventId.startsWith("wheel:"))return "mouse";if(eventId.startsWith("group:"))return eventId.endsWith("builtin.mouse")?"mouse":"keyboard";for(Map<String,Object> event:maps(config.get("event_types")))if(eventId.equals(event.get("id"))){Map<String,Object> match=map(event.get("match"));if(match.containsKey("button")||match.containsKey("axis"))return "mouse";if(match.containsKey("key")||match.containsKey("keys")||"key".equals(match.get("kind"))||"evdev".equals(event.get("source")))return "keyboard";return "system";}return eventId.startsWith("mouse_")||eventId.startsWith("wheel_")?"mouse":"system";}

    protected Map<String,Object> activeProfile(Map<String,Object> c){String id=String.valueOf(c.get("active_profile"));return maps(c.get("profiles")).stream().filter(p->id.equals(p.get("id"))).findFirst().orElseGet(()->maps(c.get("profiles")).getFirst());}

    protected Map<String,Object> findProfile(Map<String,Object> config,String id){return maps(config.get("profiles")).stream().filter(p->id.equals(p.get("id"))).findFirst().orElse(null);}

    protected String label(Page p){return switch(p){case HOME->t("Обзор","Overview");case SOUNDS->t("Библиотека","Sound library");case GENERATOR->t("Генератор","Generator");case BINDINGS->t("События и привязки","Events & bindings");case INPUTS->t("Ввод и разрешения","Input & permissions");case PROFILES->t("Профили","Profiles");case SETTINGS->t("Настройки и диагностика","Settings & diagnostics");};}

    protected String t(String ru,String en){return "en".equals(setting(store.snapshot(),"settings","language","ru"))?en:ru;}

    public synchronized void close(){if(runtimeClosed)return;runtimeClosed=true;runtime.shutdownNow();if(gnome!=null)gnome.close();if(api!=null)api.close();if(input!=null)input.close();if(dispatcher!=null)dispatcher.close();if(library!=null)library.close();if(engine!=null)engine.close();if(theme!=null)theme.close();if(store!=null)store.close();}
}
