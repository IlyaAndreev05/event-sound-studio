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

abstract class AppShellUiPart01 extends AppShellState {
    protected AppShellUiPart01(ConfigStore store, AudioEngine engine, SoundLibrary library, EventRouter router, EvdevDeviceScanner scanner, EvdevInputService input, InputEventDispatcher dispatcher, LocalControlApi api, ThemeManager theme) { super(store, engine, library, router, scanner, input, dispatcher, api, theme); }

    public void start(){engine.start(value->Platform.runLater(()->{audioState=value;engineStatus.setText(value);renderIf(Page.HOME);renderIf(Page.SETTINGS);}));refreshAudioOutputs();Map<String,Object> config=store.snapshot();loadSounds(config);applyRuntime(config);}

    protected void refreshAudioOutputs(){if(runtimeClosed||outputDiscoveryRunning)return;outputDiscoveryRunning=true;Thread.ofVirtual().name("ess-output-discovery").start(()->{List<AudioEngine.OutputOption> found;try{found=AudioEngine.availablePreviewOutputOptions();}catch(RuntimeException error){Platform.runLater(()->{outputDiscoveryRunning=false;if(!runtimeClosed)setNotice(t("Не удалось обновить список выходов: ","Could not refresh audio outputs: ")+message(error),true);});return;}Platform.runLater(()->{outputDiscoveryRunning=false;if(runtimeClosed)return;audioOutputOptions=List.copyOf(found);audioOutputs=found.stream().map(AudioEngine.OutputOption::selector).toList();setNotice(t("Список выходов для предпрослушивания обновлён.","Preview output list refreshed."),false);render(page);});});}

    protected void buildChrome(){
        sidebar.getStyleClass().add("sidebar");HBox brand=new HBox(9,new Label("ES"),new Label("Event Sound Studio"));brand.getChildren().getFirst().getStyleClass().add("brand-mark");brand.getChildren().getLast().getStyleClass().add("brand");brand.setAlignment(Pos.CENTER_LEFT);sidebar.getChildren().add(brand);sidebar.getChildren().add(new javafx.scene.control.Separator());
        ToggleGroup group=new ToggleGroup();for(Page p:Page.values()){ToggleButton b=new ToggleButton(label(p));b.getStyleClass().add("nav-button");b.setMaxWidth(Double.MAX_VALUE);b.setAlignment(Pos.CENTER_LEFT);b.setAccessibleText(label(p));b.setToggleGroup(group);b.setOnAction(e->render(p));nav.put(p,b);sidebar.getChildren().add(b);}
        Region spacer=new Region();VBox.setVgrow(spacer,Priority.ALWAYS);sidebar.getChildren().addAll(spacer,new javafx.scene.control.Separator(),engineStatus,sourceStatus,new Label("JSON · PCM WAV"));engineStatus.getStyleClass().add("status-pill");sourceStatus.getStyleClass().add("muted");sidebar.setPrefWidth(245);
        HBox top=new HBox(14);pageTitle.getStyleClass().add("page-title");Region push=new Region();HBox.setHgrow(push,Priority.ALWAYS);search.setPromptText(t("Поиск звуков и привязок…","Search sounds and bindings…"));search.setAccessibleText(t("Поиск Event Sound Studio","Search Event Sound Studio"));search.setPrefWidth(270);search.setMinWidth(120);search.setMaxWidth(340);top.getStyleClass().add("topbar");top.getChildren().addAll(pageTitle,push,search);body.setTop(top);setLeft(sidebar);setCenter(body);
    }

    protected void render(Page selected){page=selected;ToggleButton chosen=nav.get(selected);if(chosen!=null)chosen.setSelected(true);pageTitle.setText(label(selected));Node content=switch(selected){case HOME->homePage();case SOUNDS->soundsPage();case GENERATOR->generatorPage();case BINDINGS->bindingsPage();case INPUTS->inputsPage();case PROFILES->profilesPage();case SETTINGS->settingsPage();};ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);body.setCenter(scroll);if(notice.isVisible())body.setBottom(notice);}

    protected void renderIf(Page expected){if(page==expected)render(page);}

    protected VBox page(String subtitle){VBox box=new VBox(18);box.getStyleClass().add("page-content");box.getChildren().add(labelNode(subtitle,"page-subtitle"));return box;}

    protected VBox card(String title){VBox box=new VBox(12);box.getStyleClass().add("card");box.getChildren().add(labelNode(title,"card-title"));return box;}

    protected Label labelNode(String text,String style){Label l=new Label(text);l.setWrapText(true);if(style!=null&&!style.isBlank())l.getStyleClass().add(style);return l;}

    protected Button button(String text,Runnable action){Button b=new Button(text);b.setMinHeight(36);b.setAccessibleText(text);b.setOnAction(e->action.run());return b;}

    protected VBox homePage(){
        VBox root=page(t("Звуки событий идут через Audio Matrix; предпрослушивание можно направить отдельно.","Event sounds go through Audio Matrix; previews can use a separate output."));
        HBox metrics=new HBox(14);metrics.getChildren().addAll(metric(t("Звуки","Sounds"),Integer.toString(library.entries().size())),metric(t("Привязки","Bindings"),Integer.toString(router.bindings().size())),metric(t("Обрезанные сэмплы","Clipped samples"),Long.toString(engine.clippedSamples())));metrics.getChildren().forEach(n->HBox.setHgrow(n,Priority.ALWAYS));root.getChildren().add(metrics);
        VBox audio=card(t("Аудиовыход и эффекты","Audio output and effects"));Map<String,Object> config=store.snapshot();Map<String,Object> settings=map(config.get("settings"));
        Map<String,Object> active=activeProfile(config);ComboBox<String> output=new ComboBox<>();output.getItems().setAll(audioOutputs);String savedOutput=string(settings.get("preview_output"),"system_default");if(!output.getItems().contains(savedOutput))output.getItems().add(savedOutput);output.setValue(savedOutput);output.setAccessibleText(t("Выход предпрослушивания и генератора","Preview and generator output"));output.setButtonCell(outputCell());output.setCellFactory(list->outputCell());
        output.setMaxWidth(Double.MAX_VALUE);output.setOnAction(e->mutate(c->map(c.get("settings")).put("preview_output",output.getValue())));
        Slider volume=new Slider(0,1,num(active.get("master_volume"),num(settings.get("master_volume"),.65)));volume.setAccessibleText(t("Громкость активного профиля","Active profile volume"));Label volumeText=labelNode(Math.round(volume.getValue()*100)+"%","stat-value");PauseTransition volumeSave=new PauseTransition(Duration.millis(180));volume.valueProperty().addListener((o,a,b)->{volumeText.setText(Math.round(b.doubleValue()*100)+"%");volumeSave.setOnFinished(e->mutate(c->activeProfile(c).put("master_volume",volume.getValue())));volumeSave.playFromStart();});
        CheckBox mute=new CheckBox(t("Заглушить только эффекты","Mute effects only"));mute.setSelected(Boolean.TRUE.equals(settings.get("master_mute")));mute.setOnAction(e->mutate(c->map(c.get("settings")).put("master_mute",mute.isSelected())));
        Label status=labelNode(audioState,"accent");Label actual=labelNode(t("Физические выходы событий настраиваются в графе Audio Matrix. Выбранный выход действует только для предпрослушивания и генератора.","Physical event outputs are configured in the Audio Matrix graph. This selection is only for previews and the generator."),"muted");
        HBox level=new HBox(12,labelNode(t("Громкость эффектов","Effects volume"),"muted"),volume,volumeText);level.setAlignment(Pos.CENTER_LEFT);HBox.setHgrow(volume,Priority.ALWAYS);
        audio.getChildren().addAll(labelNode(t("Предпрослушивание","Preview output"),"muted"),output,level,mute,status,actual);root.getChildren().add(audio);
        VBox event=card(t("Быстрая проверка без глобального ввода","Quick test without global input"));ComboBox<String> sounds=new ComboBox<>();library.entries().stream().filter(SoundLibrary.Entry::ready).forEach(s->sounds.getItems().add(s.id()));sounds.setPromptText(t("Выберите звук","Choose a sound"));
        Button play=button(t("Прослушать","Preview"),()->{if(sounds.getValue()!=null)router.playSound(sounds.getValue());});Button apiTest=button(t("Событие API: test","API event: test"),()->router.trigger("api_test"));sounds.setAccessibleText(t("Звук для проверки","Sound to preview"));sounds.setMaxWidth(Double.MAX_VALUE);HBox testRow=new HBox(10,sounds,play,apiTest);HBox.setHgrow(sounds,Priority.ALWAYS);event.getChildren().addAll(labelNode(t("Тест работает локально и не требует разрешения evdev.","The test is local and does not require evdev access."),"muted"),testRow);root.getChildren().add(event);
        VBox sources=card(t("Источники событий","Event sources"));sources.getChildren().addAll(labelNode(sourceState,"muted"),labelNode(t("Клавиатура/мышь выключены по умолчанию. Они запускаются только после явного включения и выбора конкретных устройств.","Keyboard/mouse are off by default. They start only after explicit opt-in and choosing specific devices."),"warning"));root.getChildren().add(sources);return root;
    }

    protected VBox metric(String name,String value){VBox c=card(name);c.getChildren().add(labelNode(value,"stat-value"));return c;}

    protected VBox soundsPage(){
        VBox root=page(t("Отдельные записи WAV можно переиспользовать в нескольких событиях.","WAV assets can be reused by multiple events."));
        VBox libraryCard=card(t("Библиотека звуков","Sound library"));FlowPane actions=new FlowPane(10,8);actions.getChildren().add(button(t("Импортировать WAV","Import WAV"),this::importWav));exportSoundSetButton=button(t("Экспорт набора ZIP…","Export sound set ZIP…"),this::exportSoundSet);exportSoundSetButton.setDisable(selectedSoundExports.isEmpty());soundExportCount=labelNode(t("Отмечено для экспорта: ","Selected for export: ")+selectedSoundExports.size(),"muted");Button clearSoundSelection=button(t("Снять выбор","Clear selection"),()->{selectedSoundExports.clear();refreshSoundExportSelection();render(Page.SOUNDS);});actions.getChildren().addAll(exportSoundSetButton,clearSoundSelection,soundExportCount);libraryCard.getChildren().addAll(actions,labelNode(t("Поддерживаются mono/stereo WAV; импортированный оригинал хранится отдельно от нормализованной копии.","Mono/stereo WAV is supported; an imported original is kept separately from its normalized copy."),"muted"));
        List<SoundLibrary.Entry> entries=library.entries().stream().filter(e->matches(soundSearchText(e),search.getText())).toList();
        if(entries.isEmpty()){Label noSounds=labelNode(t("Звуки ещё загружаются или совпадений нет.","Sounds are loading or no matches were found."),"muted");noSounds.getStyleClass().add("empty-state");libraryCard.getChildren().add(noSounds);}
        for(SoundLibrary.Entry entry:entries)libraryCard.getChildren().add(soundRow(entry));root.getChildren().add(libraryCard);return root;
    }

    protected Node soundRow(SoundLibrary.Entry entry){
        HBox row=new HBox(12);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("list-row");
        String details=String.join(" · ",List.of(string(entry.metadata().get("group"),""),String.join(", ",list(entry.metadata().get("tags")).stream().map(String::valueOf).toList()),string(entry.metadata().get("description"),"")).stream().filter(v->!v.isBlank()).toList());
        VBox info=new VBox(4,labelNode(entry.name(),"card-title"),labelNode(details.isBlank()?entry.relativePath():details,"muted"),labelNode(entry.relativePath()+(entry.error()==null?"":" · "+entry.error()),entry.ready()?"muted":"danger"));Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        CheckBox include=new CheckBox("ZIP");include.setAccessibleText(t("Включить в ZIP-набор: ","Include in ZIP set: ")+entry.name());include.setTooltip(new javafx.scene.control.Tooltip(t("Включить в ZIP-набор","Include in ZIP set")));include.setSelected(selectedSoundExports.contains(entry.id()));include.setDisable(!entry.ready());include.setOnAction(e->{if(include.isSelected())selectedSoundExports.add(entry.id());else selectedSoundExports.remove(entry.id());refreshSoundExportSelection();});
        Button preview=button(t("Прослушать","Preview"),()->router.playSound(entry.id()));preview.setDisable(!entry.ready());
        MenuItem fragment=new MenuItem(t("Сохранить фрагмент…","Save fragment…"));fragment.setOnAction(e->editFragment(entry));fragment.setDisable(!entry.ready());
        MenuItem export=new MenuItem(t("Экспорт WAV…","Export WAV…"));export.setOnAction(e->exportWav(entry));export.setDisable(!entry.ready());
        MenuItem favorite=new MenuItem(Boolean.TRUE.equals(entry.metadata().get("favorite"))?t("Убрать из избранного","Remove from favorites"):t("В избранное","Add to favorites"));favorite.setOnAction(e->toggleFavorite(entry));
        MenuItem edit=new MenuItem(t("Свойства…","Properties…"));edit.setOnAction(e->editSound(entry));MenuItem copy=new MenuItem(t("Копировать звук…","Copy sound…"));copy.setOnAction(e->copySound(entry));
        MenuButton actions=new MenuButton(t("Действия","Actions"));actions.setAccessibleText(t("Действия для звука ","Actions for sound ")+entry.name());actions.getItems().addAll(fragment,export,favorite,edit,copy);if(!Boolean.TRUE.equals(entry.metadata().get("builtin"))){MenuItem remove=new MenuItem(t("Удалить…","Delete…"));remove.setOnAction(e->deleteSound(entry));actions.getItems().add(remove);}
        row.getChildren().addAll(include,info,spacer,preview,actions);return row;
    }

    protected void refreshSoundExportSelection(){if(soundExportCount!=null)soundExportCount.setText(t("Отмечено для экспорта: ","Selected for export: ")+selectedSoundExports.size());if(exportSoundSetButton!=null)exportSoundSetButton.setDisable(selectedSoundExports.isEmpty());}

    protected void importWav(){FileChooser chooser=new FileChooser();chooser.setTitle(t("Импорт WAV","Import WAV"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio","*.wav","*.WAV"));java.io.File file=chooser.showOpenDialog(getScene().getWindow());if(file==null)return;
        library.importWav(file.toPath()).whenComplete((entry,error)->Platform.runLater(()->{if(error!=null){setNotice(t("Импорт не выполнен: ","Import failed: ")+message(error),true);return;}mutate(c->{List<Map<String,Object>> sounds=maps(c.get("sounds"));sounds.add(entry.metadata());});}));}

    protected void exportWav(SoundLibrary.Entry entry){
        if(entry==null||!entry.ready()){setNotice(t("Исходный WAV недоступен.","The source WAV is unavailable."),true);return;}
        FileChooser chooser=new FileChooser();chooser.setTitle(t("Экспорт WAV","Export WAV"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio","*.wav","*.WAV"));chooser.setInitialFileName(entry.id()+".wav");java.io.File file=chooser.showSaveDialog(getScene().getWindow());if(file==null)return;
        Path destination=ensureWavSuffix(file.toPath());boolean replace=Files.exists(destination);if(replace&&!confirmOverwrite(destination))return;
        Path target=destination;setNotice(t("Экспортирую WAV…","Exporting WAV…"),false);library.exportWav(entry,target,replace).whenComplete((saved,error)->Platform.runLater(()->setNotice(error==null?t("WAV сохранён: ","WAV exported: ")+saved:t("Экспорт не выполнен: ","Export failed: ")+message(error),error!=null)));
    }

    protected boolean confirmOverwrite(Path destination){Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,t("Перезаписать выбранный файл?","Replace the selected file?"));confirm.initOwner(getScene().getWindow());return confirm.showAndWait().filter(button->button==javafx.scene.control.ButtonType.OK).isPresent();}

    protected void editSound(SoundLibrary.Entry entry){
        Dialog<Map<String,Object>> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Свойства звука","Sound properties"));dialog.setHeaderText(entry.id());
        TextField name=text(entry.name(),t("Имя звука","Sound name"));
        TextField group=text(string(entry.metadata().get("group"),""),t("Группа","Group"));
        TextField tags=text(String.join(", ",list(entry.metadata().get("tags")).stream().map(String::valueOf).toList()),t("Теги через запятую","Tags, comma separated"));
        TextArea description=new TextArea(string(entry.metadata().get("description"),""));description.setAccessibleText(t("Описание звука","Sound description"));description.setPrefRowCount(4);description.setWrapText(true);
        Label nameLabel=labelNode(t("Имя","Name"),"");nameLabel.setLabelFor(name);Label groupLabel=labelNode(t("Группа","Group"),"");groupLabel.setLabelFor(group);Label tagsLabel=labelNode(t("Теги","Tags"),"");tagsLabel.setLabelFor(tags);Label descriptionLabel=labelNode(t("Описание","Description"),"");descriptionLabel.setLabelFor(description);
        GridPane form=new GridPane();form.setHgap(12);form.setVgap(10);form.setPadding(new Insets(8));javafx.scene.layout.ColumnConstraints labelColumn=new javafx.scene.layout.ColumnConstraints(96);javafx.scene.layout.ColumnConstraints fieldColumn=new javafx.scene.layout.ColumnConstraints();fieldColumn.setHgrow(Priority.ALWAYS);fieldColumn.setFillWidth(true);form.getColumnConstraints().addAll(labelColumn,fieldColumn);form.addRow(0,nameLabel,name);form.addRow(1,groupLabel,group);form.addRow(2,tagsLabel,tags);form.addRow(3,descriptionLabel,description);GridPane.setHgrow(name,Priority.ALWAYS);GridPane.setHgrow(group,Priority.ALWAYS);GridPane.setHgrow(tags,Priority.ALWAYS);GridPane.setHgrow(description,Priority.ALWAYS);form.setPrefWidth(520);
        dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);dialog.getDialogPane().setPrefWidth(560);dialog.setResizable(true);
        styleDialog(dialog,560);
        javafx.scene.control.Button save=(javafx.scene.control.Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);save.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(()->name.getText().isBlank(),name.textProperty()));
        dialog.setOnShown(event->name.requestFocus());
        dialog.setResultConverter(button->button==javafx.scene.control.ButtonType.OK?DefaultConfig.map("name",name.getText().trim(),"group",group.getText().trim(),"tags",parseTags(tags.getText()),"description",description.getText().trim()):null);
        dialog.showAndWait().filter(values->!String.valueOf(values.get("name")).isBlank()).ifPresent(values->mutate(c->{for(Map<String,Object>s:maps(c.get("sounds")))if(entry.id().equals(s.get("id"))){s.putAll(values);break;}}));
    }

    protected void styleDialog(Dialog<?> dialog,double width){dialog.getDialogPane().setPrefWidth(width);dialog.setResizable(true);if(getScene()!=null){dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());dialog.getDialogPane().getStyleClass().add("app-shell");String themeClass=getScene().getRoot().getStyleClass().contains("dark-theme")?"dark-theme":"light-theme";dialog.getDialogPane().getStyleClass().add(themeClass);}}

    protected void copySound(SoundLibrary.Entry entry){String copyName=entry.name()+t(" — копия"," — copy");setNotice(t("Создаю копию WAV…","Creating WAV copy…"),false);library.copySound(entry,copyName).whenComplete((copy,error)->Platform.runLater(()->{if(error!=null){setNotice(t("Копия не создана: ","Copy failed: ")+message(error),true);return;}mutate(c->maps(c.get("sounds")).add(copy.metadata()));}));}

    protected void toggleFavorite(SoundLibrary.Entry entry){mutate(c->{for(Map<String,Object>s:maps(c.get("sounds")))if(entry.id().equals(s.get("id")))s.put("favorite",!Boolean.TRUE.equals(s.get("favorite")));});}

    protected void deleteSound(SoundLibrary.Entry entry){
        Map<String,Object> current=store.snapshot();List<String> dependencies=new ArrayList<>();
        maps(current.get("bindings")).stream().filter(b->entry.id().equals(b.get("sound"))).forEach(b->dependencies.add(t("Привязка: ","Binding: ")+b.get("id")));
        maps(current.get("groups")).stream().filter(g->entry.id().equals(g.get("default_sound"))).forEach(g->dependencies.add(t("Группа: ","Group: ")+g.get("name")));
        if(!dependencies.isEmpty()){Alert used=new Alert(Alert.AlertType.INFORMATION);used.initOwner(getScene().getWindow());used.setTitle(t("Звук используется","Sound is in use"));used.setHeaderText(t("Сначала переназначьте зависимости","Reassign these references first"));used.setContentText(String.join("\n",dependencies));used.showAndWait();return;}
        Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,t("Удалить пользовательский звук и его WAV?","Delete this user sound and its WAV file?"));
        confirm.initOwner(getScene().getWindow());
        confirm.showAndWait().filter(b->b==javafx.scene.control.ButtonType.OK).ifPresent(b->{
            ConfigStore.VersionedSnapshot snapshot=store.versionedSnapshot();Map<String,Object> config=snapshot.config();maps(config.get("sounds")).removeIf(s->entry.id().equals(s.get("id")));
            store.saveAsync(config,snapshot.revision()).whenComplete((v,error)->Platform.runLater(()->{
                if(error!=null){setNotice(t("Конфигурация не сохранена: ","Configuration was not saved: ")+message(error),true);return;}
                selectedSoundExports.remove(entry.id());refreshSoundExportSelection();
                try{deleteUnusedSoundFiles(entry,config);}
                catch(Exception ex){setNotice(message(ex),true);}
                render(Page.SOUNDS);
            }));
        });
    }

    protected void deleteUnusedSoundFiles(SoundLibrary.Entry entry,Map<String,Object> nextConfig)throws Exception{
        String assetPath=entry.relativePath();String originalPath=string(entry.metadata().get("original_path"),"");
        for(Map<String,Object> sound:maps(nextConfig.get("sounds"))){
            if(assetPath.equals(sound.get("path")))assetPath="";
            if(!originalPath.isBlank()&&originalPath.equals(sound.get("original_path")))originalPath="";
        }
        Path base=store.dataDirectory().toAbsolutePath().normalize();
        for(String raw:List.of(assetPath,originalPath))if(!raw.isBlank()){
            Path file=Path.of(raw);if(!file.isAbsolute())file=base.resolve(file);file=file.toAbsolutePath().normalize();
            if(file.startsWith(base))Files.deleteIfExists(file);
        }
    }

    protected VBox generatorPage(){
        VBox root=page(t("Генератор создаёт WAV в GUI; в JSON остаются только путь и параметры воспроизведения.","The GUI generator creates a WAV; JSON stores only the file path and playback settings."));VBox card=card(t("Генератор звука","Sound generator"));
        ComboBox<SoundSynthesizer.Waveform> waveform=new ComboBox<>();waveform.getItems().setAll(SoundSynthesizer.Waveform.values());waveform.setValue(SoundSynthesizer.Waveform.SINE);waveform.setAccessibleText(t("Форма сигнала","Waveform"));
        TextField name=text("Новый звук","Sound name"),frequency=text("620","Frequency in Hz"),amplitude=text("0.12","Amplitude 0–0.9"),duration=text("80","Duration in ms"),repeats=text("1","Repeat count"),dc=text("0","DC offset -0.1–0.1");
        Label nameLabel=labelNode(t("Имя","Name"),"");nameLabel.setLabelFor(name);Label waveformLabel=labelNode(t("Форма","Waveform"),"");waveformLabel.setLabelFor(waveform);
        Label frequencyLabel=labelNode(t("Частота, Гц","Frequency, Hz"),"");frequencyLabel.setLabelFor(frequency);Label amplitudeLabel=labelNode(t("Амплитуда","Amplitude"),"");amplitudeLabel.setLabelFor(amplitude);Label durationLabel=labelNode(t("Длительность, мс","Duration, ms"),"");durationLabel.setLabelFor(duration);
        Label repeatsLabel=labelNode(t("Повторы","Repeats"),"");repeatsLabel.setLabelFor(repeats);Label dcLabel=labelNode("DC offset","");dcLabel.setLabelFor(dc);
        GridPane form=new GridPane();form.setHgap(12);form.setVgap(10);javafx.scene.layout.ColumnConstraints formLabel=new javafx.scene.layout.ColumnConstraints();formLabel.setMinWidth(110);javafx.scene.layout.ColumnConstraints formField=new javafx.scene.layout.ColumnConstraints();formField.setHgrow(Priority.ALWAYS);formField.setFillWidth(true);form.getColumnConstraints().addAll(formLabel,formField,formLabel,formField);
        form.addRow(0,nameLabel,name,waveformLabel,waveform);form.addRow(1,frequencyLabel,frequency,amplitudeLabel,amplitude);form.addRow(2,durationLabel,duration,repeatsLabel,repeats);form.addRow(3,dcLabel,dc);waveform.setMaxWidth(Double.MAX_VALUE);GridPane.setHgrow(name,Priority.ALWAYS);GridPane.setHgrow(waveform,Priority.ALWAYS);GridPane.setHgrow(frequency,Priority.ALWAYS);GridPane.setHgrow(amplitude,Priority.ALWAYS);GridPane.setHgrow(duration,Priority.ALWAYS);GridPane.setHgrow(repeats,Priority.ALWAYS);GridPane.setHgrow(dc,Priority.ALWAYS);
        final PcmSound[] previewSound={null};Canvas wave=new Canvas(680,150);wave.getStyleClass().add("waveform");wave.setAccessibleText(t("Предпросмотр формы сигнала. Параметры задаются полями выше.","Waveform preview. Parameters are set in the fields above."));wave.widthProperty().bind(card.widthProperty().subtract(36));wave.widthProperty().addListener((o,a,b)->drawWave(wave,previewSound[0]));drawWave(wave,null);
        Button preview=button(t("Сгенерировать и прослушать","Generate and preview"),()->{try{float[] samples=SoundSynthesizer.generate(waveform.getValue(),parse(frequency),parse(amplitude),Integer.parseInt(duration.getText()),Integer.parseInt(repeats.getText()),parse(dc));previewSound[0]=new PcmSound("preview",name.getText(),samples,1,SoundSynthesizer.SAMPLE_RATE);drawWave(wave,previewSound[0]);engine.play(previewSound[0],1,0,0,1,"restart");}catch(Exception e){setNotice(t("Параметры не приняты: ","Invalid generator settings: ")+message(e),true);}});
        Button save=button(t("Сохранить готовый WAV в библиотеку","Save generated WAV to library"),()->{try{if(name.getText().isBlank())throw new IllegalArgumentException(t("Введите имя звука.","Enter a sound name."));library.saveGenerated(name.getText(),waveform.getValue(),parse(frequency),parse(amplitude),Integer.parseInt(duration.getText()),Integer.parseInt(repeats.getText()),parse(dc)).whenComplete((entry,error)->Platform.runLater(()->{if(error!=null){setNotice(message(error),true);return;}mutate(c->maps(c.get("sounds")).add(entry.metadata()));}));}catch(Exception e){setNotice(message(e),true);}});
        Button export=button(t("Экспортировать готовый WAV…","Export generated WAV…"),()->exportGeneratedWav(name,waveform,frequency,amplitude,duration,repeats,dc));
        FlowPane generatorActions=new FlowPane(10,8);generatorActions.getChildren().addAll(preview,save,export);
        card.getChildren().addAll(form,wave,generatorActions,labelNode(t("Все параметры ограничены; preview проходит через master volume и limiter. Экспорт WAV сохраняет результат отдельно от библиотеки.","Inputs are bounded; preview passes through master volume and the limiter. WAV export saves a standalone file outside the library."),"muted"));root.getChildren().add(card);return root;
    }
}
