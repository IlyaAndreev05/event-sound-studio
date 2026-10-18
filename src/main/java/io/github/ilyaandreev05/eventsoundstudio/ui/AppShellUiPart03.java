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

abstract class AppShellUiPart03 extends AppShellUiPart02 {
    protected AppShellUiPart03(ConfigStore store, AudioEngine engine, SoundLibrary library, EventRouter router, EvdevDeviceScanner scanner, EvdevInputService input, InputEventDispatcher dispatcher, LocalControlApi api, ThemeManager theme) { super(store, engine, library, router, scanner, input, dispatcher, api, theme); }

    protected void copyEvent(String id){
        Map<String,Object> source=maps(store.snapshot().get("event_types")).stream().filter(event->id.equals(event.get("id"))).findFirst().orElse(null);if(source==null)return;String copyId="user."+UUID.randomUUID().toString().substring(0,8),copyName=String.valueOf(source.getOrDefault("name",id))+t(" — копия"," copy");mutate(c->maps(c.get("event_types")).add(DefaultConfig.map("id",copyId,"name",copyName,"source","custom","match",DefaultConfig.map("event_id",copyId))));
    }

    protected void deleteEvent(String id){
        Map<String,Object> config=store.snapshot();List<Map<String,Object>> events=maps(config.get("event_types"));int index=-1;Map<String,Object> target=null;for(int i=0;i<events.size();i++)if(id.equals(events.get(i).get("id"))){index=i;target=events.get(i);break;}if(target==null||!"custom".equals(target.get("source")))return;
        boolean referenced=maps(config.get("bindings")).stream().anyMatch(binding->id.equals(binding.get("event")))||maps(config.get("combos")).stream().anyMatch(combo->id.equals(combo.get("event")));if(referenced)return;
        Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,t("Удалить событие «","Delete event ")+target.getOrDefault("name",id)+"?",javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);confirm.initOwner(getScene().getWindow());confirm.setHeaderText(null);int originalIndex=index;Map<String,Object> saved=deepMap(target);
        confirm.showAndWait().filter(button->button==javafx.scene.control.ButtonType.OK).ifPresent(button->{offerUndo(t("Отменить удаление события","Undo event deletion"),()->mutate(c->{List<Map<String,Object>> current=maps(c.get("event_types"));if(current.stream().noneMatch(event->id.equals(event.get("id"))))current.add(Math.min(originalIndex,current.size()),deepMap(saved));}));mutate(c->maps(c.get("event_types")).removeIf(event->id.equals(event.get("id"))));});
    }

    protected void editBinding(Map<String,Object> binding){
        String id=String.valueOf(binding.get("id"));Dialog<Map<String,Object>> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Настройки привязки","Binding settings"));dialog.setHeaderText(id);styleDialog(dialog,640);
        Slider volume=new Slider(0,1,num(binding.get("volume"),.5)),pan=new Slider(-1,1,num(binding.get("pan"),0));volume.setAccessibleText(t("Громкость привязки","Binding volume"));pan.setAccessibleText(t("Панорама привязки","Binding pan"));
        Label volumeLabel=labelNode(t("Громкость","Volume"),"");volumeLabel.setLabelFor(volume);Label panLabel=labelNode(t("Панорама","Pan"),"");panLabel.setLabelFor(pan);
        Label volumeValue=labelNode(Math.round(volume.getValue()*100)+"%","muted"),panValue=labelNode(Math.round(pan.getValue()*100)+"%","muted");volume.valueProperty().addListener((o,a,v)->volumeValue.setText(Math.round(v.doubleValue()*100)+"%"));pan.valueProperty().addListener((o,a,v)->panValue.setText(Math.round(v.doubleValue()*100)+"%"));
        TextField delay=text(String.valueOf(binding.getOrDefault("delay_ms",0)),t("Задержка, мс","Delay in ms")),repeat=text(String.valueOf(binding.getOrDefault("repeat",1)),t("Число повторов","Repeat count"));
        Label delayLabel=labelNode(t("Задержка, мс","Delay, ms"),"");delayLabel.setLabelFor(delay);Label repeatLabel=labelNode(t("Повторы","Repeats"),"");repeatLabel.setLabelFor(repeat);
        TextField duration=text(String.valueOf(binding.getOrDefault("duration_ms",0)),t("Длительность, мс; 0 — исходная","Duration in ms; 0 keeps source")),attack=text(String.valueOf(binding.getOrDefault("attack_ms",0)),t("Атака, мс","Attack in ms")),release=text(String.valueOf(binding.getOrDefault("release_ms",0)),t("Спад, мс","Release in ms"));
        Label durationLabel=labelNode(t("Длительность","Duration"),"");durationLabel.setLabelFor(duration);Label attackLabel=labelNode(t("Атака, мс","Attack, ms"),"");attackLabel.setLabelFor(attack);Label releaseLabel=labelNode(t("Спад, мс","Release, ms"),"");releaseLabel.setLabelFor(release);CheckBox removeDc=new CheckBox(t("Удалить DC-смещение перед воспроизведением","Remove DC offset before playback"));removeDc.setSelected(Boolean.TRUE.equals(binding.get("remove_dc")));
        Label delayError=labelNode("","danger"),repeatError=labelNode("","danger");ComboBox<String> overlap=new ComboBox<>();overlap.getItems().setAll("mix","restart","ignore","queue");overlap.setValue(String.valueOf(binding.getOrDefault("overlap","mix")));overlap.setAccessibleText(t("Правило перекрытия","Overlap rule"));Label overlapLabel=labelNode(t("Перекрытие","Overlap"),"");overlapLabel.setLabelFor(overlap);
        GridPane form=new GridPane();form.setHgap(12);form.setVgap(10);form.setPadding(new Insets(8));javafx.scene.layout.ColumnConstraints labels=new javafx.scene.layout.ColumnConstraints(120);javafx.scene.layout.ColumnConstraints fields=new javafx.scene.layout.ColumnConstraints();fields.setHgrow(Priority.ALWAYS);form.getColumnConstraints().addAll(labels,fields);
        Label outputInfo=labelNode(t("События этой привязки идут в Audio Matrix; конечный выход задаётся в графе.","Events from this binding go to Audio Matrix; the final output is set in its graph."),"muted");
        Label durationError=labelNode("","danger"),attackError=labelNode("","danger"),releaseError=labelNode("","danger");
        form.addRow(0,volumeLabel,new HBox(10,volume,volumeValue));form.addRow(1,panLabel,new HBox(10,pan,panValue));form.addRow(2,delayLabel,delay);form.addRow(3,new Label(),delayError);form.addRow(4,repeatLabel,repeat);form.addRow(5,new Label(),repeatError);form.addRow(6,overlapLabel,overlap);form.addRow(7,new Label(),outputInfo);form.addRow(8,durationLabel,duration);form.addRow(9,new Label(),durationError);form.addRow(10,attackLabel,attack);form.addRow(11,new Label(),attackError);form.addRow(12,releaseLabel,release);form.addRow(13,new Label(),releaseError);form.addRow(14,new Label(),removeDc);HBox.setHgrow(volume,Priority.ALWAYS);HBox.setHgrow(pan,Priority.ALWAYS);GridPane.setHgrow(delay,Priority.ALWAYS);GridPane.setHgrow(repeat,Priority.ALWAYS);GridPane.setHgrow(duration,Priority.ALWAYS);GridPane.setHgrow(attack,Priority.ALWAYS);GridPane.setHgrow(release,Priority.ALWAYS);
        Map<String,Object> existingEq=map(binding.get("eq"));CheckBox eqEnabled=new CheckBox(t("Включить EQ для этой привязки","Enable EQ for this binding"));eqEnabled.setSelected(Boolean.TRUE.equals(existingEq.get("enabled")));
        VBox eqRows=new VBox(6);List<EqFilterEditor> eqEditors=new ArrayList<>();Label eqError=labelNode("","danger");Button addFilter=button(t("Добавить фильтр","Add filter"),()->{});final Runnable[] validateRef={()->{}};
        for(Map<String,Object> filter:maps(existingEq.get("filters")))if(eqEditors.size()<12)addEqFilter(eqRows,eqEditors,filter,addFilter,()->validateRef[0].run());
        addFilter.setOnAction(e->{if(eqEditors.size()<12)addEqFilter(eqRows,eqEditors,Map.of(),addFilter,()->validateRef[0].run());validateRef[0].run();});
        VBox eqForm=new VBox(8,labelNode(t("Эквалайзер привязки","Binding equalizer"),"card-title"),eqEnabled,eqRows,addFilter,eqError);eqForm.getStyleClass().add("node-card");
        Label note=labelNode(t("EQ, длительность, DC-коррекция и огибающая готовятся только для этой привязки. Изменение длительности ресэмплирует звук и меняет высоту тона.","EQ, duration, DC correction, and envelope are prepared for this binding only. Changing duration resamples the sound and changes pitch."),"muted");
        VBox content=new VBox(12,form,eqForm,note);ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setPrefViewportHeight(420);dialog.getDialogPane().setContent(scroll);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.control.Button save=(javafx.scene.control.Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        Runnable validate=()->{Integer delayMs=boundedInt(delay,0,5000),repeats=boundedInt(repeat,1,20),durationMs=boundedInt(duration,0,30_000),attackMs=boundedInt(attack,0,5000),releaseMs=boundedInt(release,0,5000);delayError.setText(delayMs==null?t("Введите целое число 0–5000.","Enter an integer from 0 to 5000."):"");repeatError.setText(repeats==null?t("Введите целое число 1–20.","Enter an integer from 1 to 20."):"");durationError.setText(durationMs==null?t("Введите целое число 0–30000.","Enter an integer from 0 to 30000."):"");attackError.setText(attackMs==null?t("Введите целое число 0–5000.","Enter an integer from 0 to 5000."):"");releaseError.setText(releaseMs==null?t("Введите целое число 0–5000.","Enter an integer from 0 to 5000."):"");for(Label error:List.of(delayError,repeatError,durationError,attackError,releaseError)){boolean invalid=!error.getText().isEmpty();error.setManaged(invalid);error.setVisible(invalid);}String eqProblem=eqProblem(eqEditors);eqError.setText(eqProblem==null?"":eqProblem);eqError.setManaged(eqProblem!=null);eqError.setVisible(eqProblem!=null);save.setDisable(delayMs==null||repeats==null||durationMs==null||attackMs==null||releaseMs==null||overlap.getValue()==null||eqProblem!=null);};
        validateRef[0]=validate;for(TextField field:List.of(delay,repeat,duration,attack,release))field.textProperty().addListener((o,a,v)->validate.run());overlap.valueProperty().addListener((o,a,v)->validate.run());eqEnabled.selectedProperty().addListener((o,a,v)->validate.run());validate.run();
        dialog.setResultConverter(button->{if(button!=javafx.scene.control.ButtonType.OK)return null;List<Map<String,Object>> filters=new ArrayList<>();for(EqFilterEditor editor:eqEditors)filters.add(DefaultConfig.map("type",editor.type().getValue(),"frequency_hz",Double.parseDouble(editor.frequency().getText().trim()),"gain_db",Double.parseDouble(editor.gain().getText().trim()),"q",Double.parseDouble(editor.q().getText().trim())));return DefaultConfig.map("volume",volume.getValue(),"pan",pan.getValue(),"delay_ms",boundedInt(delay,0,5000),"repeat",boundedInt(repeat,1,20),"overlap",overlap.getValue(),"duration_ms",boundedInt(duration,0,30_000),"attack_ms",boundedInt(attack,0,5000),"release_ms",boundedInt(release,0,5000),"remove_dc",removeDc.isSelected(),"eq",DefaultConfig.map("enabled",eqEnabled.isSelected(),"filters",filters));});
        dialog.showAndWait().ifPresent(values->mutate(c->{for(Map<String,Object> current:maps(c.get("bindings")))if(id.equals(current.get("id"))){current.putAll(values);break;}}));
    }

    protected void addEqFilter(VBox rows,List<EqFilterEditor> editors,Map<String,Object> source,Button addButton,Runnable changed){
        ComboBox<String> type=new ComboBox<>();type.getItems().setAll(io.github.ilyaandreev05.eventsoundstudio.audio.AudioEffects.FILTER_TYPES.stream().sorted().toList());type.setValue(String.valueOf(source.getOrDefault("type","peaking")));type.setAccessibleText(t("Тип фильтра","Filter type"));
        TextField frequency=text(String.valueOf(source.getOrDefault("frequency_hz",1000)),t("Частота, Гц","Frequency in Hz"));TextField gain=text(String.valueOf(source.getOrDefault("gain_db",0)),t("Усиление, дБ","Gain in dB"));TextField q=text(String.valueOf(source.getOrDefault("q",1)),"Q");frequency.setPrefColumnCount(7);gain.setPrefColumnCount(6);q.setPrefColumnCount(4);
        VBox frequencyField=new VBox(2,labelNode(t("Частота, Гц","Frequency, Hz"),"muted"),frequency),gainField=new VBox(2,labelNode(t("Усиление, дБ","Gain, dB"),"muted"),gain),qField=new VBox(2,labelNode("Q","muted"),q);
        HBox row=new HBox(8,type,frequencyField,gainField,qField);row.setAlignment(Pos.CENTER_LEFT);EqFilterEditor editor=new EqFilterEditor(type,frequency,gain,q);editors.add(editor);Button remove=button("−",()->{});remove.setAccessibleText(t("Удалить фильтр","Remove filter"));remove.setOnAction(e->{rows.getChildren().remove(row);editors.remove(editor);addButton.setDisable(editors.size()>=12);changed.run();});row.getChildren().add(remove);rows.getChildren().add(row);addButton.setDisable(editors.size()>=12);
        type.valueProperty().addListener((o,a,v)->changed.run());frequency.textProperty().addListener((o,a,v)->changed.run());gain.textProperty().addListener((o,a,v)->changed.run());q.textProperty().addListener((o,a,v)->changed.run());
    }

    protected javafx.scene.control.ListCell<String> outputCell(){return new javafx.scene.control.ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:outputName(item));}};}

    protected String outputName(String output){if("profile_default".equals(output))return t("Выход профиля","Profile output");if("system_default".equals(output))return t("Системный выход","System default");String name=AudioEngine.outputDisplayName(output,audioOutputOptions);if(!AudioEngine.outputAvailable(output,audioOutputs))return t("Недоступен: ","Unavailable: ")+name;return name;}

    protected javafx.scene.control.ListCell<String> clickModeCell(){return new javafx.scene.control.ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:"wait_and_suppress".equals(item)?t("Ждать двойной клик","Wait and suppress"):t("Сразу + двойной клик","Immediate + double-click"));}};}

    protected javafx.scene.control.ListCell<String> keyboardRepeatModeCell(){return new javafx.scene.control.ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:"throttle".equals(item)?t("Ограничить частоту","Throttle repeat rate"):t("Игнорировать повторы","Ignore repeats"));}};}

    protected String eqProblem(List<EqFilterEditor> editors){
        if(editors.size()>12)return t("Максимум 12 фильтров EQ.","Maximum 12 EQ filters.");
        for(int i=0;i<editors.size();i++){EqFilterEditor editor=editors.get(i);try{double frequency=Double.parseDouble(editor.frequency().getText().trim()),gain=Double.parseDouble(editor.gain().getText().trim()),q=Double.parseDouble(editor.q().getText().trim());if(editor.type().getValue()==null||!Double.isFinite(frequency)||frequency<20||frequency>20_000||!Double.isFinite(gain)||gain< -24||gain>12||!Double.isFinite(q)||q<.1||q>12)return t("Фильтр "+(i+1)+": частота 20–20000 Гц, усиление −24–12 дБ, Q 0,1–12.","Filter "+(i+1)+": frequency 20–20000 Hz, gain −24–12 dB, Q 0.1–12.");}catch(NumberFormatException e){return t("Фильтр "+(i+1)+": введите числовые частоту, усиление и Q.","Filter "+(i+1)+": enter numeric frequency, gain, and Q.");}}
        return null;
    }

    protected void addBinding(){
        Map<String,Object> config=store.snapshot();Dialog<Map<String,Object>> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Новая привязка","New binding"));dialog.setHeaderText(t("Свяжите событие со звуком","Connect an event to a sound"));styleDialog(dialog,520);
        TextField name=text(t("Новая привязка","New binding"),t("Имя привязки","Binding name"));ComboBox<String> event=new ComboBox<>();event.getItems().setAll(bindingEventChoices(config));event.setEditable(true);if(!event.getItems().isEmpty())event.setValue(event.getItems().getFirst());event.setAccessibleText(t("Событие","Event"));
        ComboBox<String> sound=new ComboBox<>();library.entries().stream().filter(SoundLibrary.Entry::ready).forEach(entry->sound.getItems().add(entry.id()));if(!sound.getItems().isEmpty())sound.setValue(sound.getItems().getFirst());sound.setAccessibleText(t("Звук","Sound"));
        TextField priority=text("10",t("Приоритет 0–1000","Priority 0–1000")),cooldown=text("0",t("Cooldown, мс","Cooldown in ms"));Label error=labelNode("","danger");GridPane form=new GridPane();form.setHgap(12);form.setVgap(10);form.setPadding(new Insets(8));Label nameCaption=labelNode(t("Имя","Name"),"");nameCaption.setLabelFor(name);Label eventCaption=labelNode(t("Событие","Event"),"");eventCaption.setLabelFor(event);Label soundCaption=labelNode(t("Звук","Sound"),"");soundCaption.setLabelFor(sound);Label priorityCaption=labelNode(t("Приоритет","Priority"),"");priorityCaption.setLabelFor(priority);Label cooldownCaption=labelNode(t("Cooldown, мс","Cooldown, ms"),"");cooldownCaption.setLabelFor(cooldown);form.addRow(0,nameCaption,name);form.addRow(1,eventCaption,event);form.addRow(2,soundCaption,sound);form.addRow(3,priorityCaption,priority);form.addRow(4,cooldownCaption,cooldown);form.add(error,0,5,2,1);dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.control.Button save=(javafx.scene.control.Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);Runnable validate=()->{String eventId=event.getEditor().getText().trim();Integer p=boundedInt(priority,0,1000),c=boundedInt(cooldown,0,60_000);boolean known=bindingEventChoices(config).contains(eventId)||eventId.startsWith("key:")||eventId.startsWith("button:")||eventId.startsWith("wheel:");String issue=name.getText().isBlank()?t("Введите имя.","Enter a name."):!known?t("Выберите существующее событие или тип ввода.","Choose an existing event or input event."):sound.getValue()==null?t("Выберите загруженный звук.","Choose a loaded sound."):p==null?t("Приоритет должен быть 0–1000.","Priority must be 0–1000."):c==null?t("Cooldown должен быть 0–60000 мс.","Cooldown must be 0–60000 ms."):"";error.setText(issue);save.setDisable(!issue.isEmpty());};
        name.textProperty().addListener((o,a,v)->validate.run());event.getEditor().textProperty().addListener((o,a,v)->validate.run());sound.valueProperty().addListener((o,a,v)->validate.run());priority.textProperty().addListener((o,a,v)->validate.run());cooldown.textProperty().addListener((o,a,v)->validate.run());validate.run();
        dialog.setResultConverter(button->button==javafx.scene.control.ButtonType.OK?DefaultConfig.map("name",name.getText().trim(),"event",event.getEditor().getText().trim(),"sound",sound.getValue(),"priority",boundedInt(priority,0,1000),"cooldown_ms",boundedInt(cooldown,0,60_000)):null);
        dialog.showAndWait().ifPresent(values->{String id="binding-"+UUID.randomUUID().toString().substring(0,8);Map<String,Object> binding=DefaultConfig.binding(id,String.valueOf(values.get("event")),String.valueOf(values.get("sound")),false,(Integer)values.get("priority"),.5,(Integer)values.get("cooldown_ms"),"mix");binding.put("name",values.get("name"));mutate(c->maps(c.get("bindings")).add(binding));});
    }

    protected VBox inputsPage(){
        VBox root=page(t("На Wayland глобальные клавиши и мышь читаются только из выбранных evdev-устройств.","On Wayland, global keyboard and mouse events are read only from selected evdev devices."));Map<String,Object> config=store.snapshot();Map<String,Object> settings=map(config.get("settings"));Map<String,Object> inputConfig=map(settings.get("input"));
        VBox privacy=card(t("Разрешения и приватность","Permissions and privacy"));privacy.getChildren().addAll(labelNode(t("Никакие клавиши не читаются и не логируются, пока вы не включите источник и не выберете конкретное устройство ниже.","No keyboard input is read or logged until you enable a source and select a specific device below."),"warning"),labelNode(t("Программа не добавляет пользователя в группу input. Если ОС отказывает в доступе, настройте права отдельно и нажмите «Обновить устройства».","The app does not add the user to the input group. If the OS denies access, configure permissions separately and refresh the device list."),"muted"));root.getChildren().add(privacy);
        CheckBox keyboard=new CheckBox(t("Включить клавиатурные события","Enable keyboard events"));keyboard.setSelected(Boolean.TRUE.equals(inputConfig.get("enable_keyboard")));
        CheckBox mouse=new CheckBox(t("Включить мышь и колесо","Enable mouse and wheel events"));mouse.setSelected(Boolean.TRUE.equals(inputConfig.get("enable_mouse")));
        CheckBox gnomeBox=new CheckBox(t("GNOME: смена окна и рабочего стола","GNOME: focus and workspace events"));gnomeBox.setSelected(Boolean.TRUE.equals(inputConfig.get("enable_gnome")));
        CheckBox altTabDedup=new CheckBox(t("Не дублировать звук GNOME о фокусе после Alt+Tab","Suppress GNOME focus sound after Alt+Tab"));altTabDedup.setSelected(Boolean.TRUE.equals(inputConfig.get("deduplicate_alt_tab_focus")));altTabDedup.setTooltip(new javafx.scene.control.Tooltip(t("Звуки клавиш Alt и Tab сохраняются. Если GNOME-события включены и клавиатура выбрана во входах, отдельный сигнал смены фокуса подавляется в течение 750 мс после Alt+Tab. По умолчанию выключено.","Alt and Tab key sounds remain. With GNOME events enabled and the keyboard selected in Inputs, the separate focus signal is suppressed for 750 ms after Alt+Tab. Off by default.")));
        Set<String> allow=new java.util.HashSet<>(list(inputConfig.get("device_allowlist")).stream().map(String::valueOf).toList());VBox deviceRows=new VBox(8);List<EvdevDeviceScanner.Device> devices=scanner.scan();
        if(devices.isEmpty())deviceRows.getChildren().add(labelNode(t("Устройств /dev/input/event* не обнаружено.","No /dev/input/event* devices were found."),"muted"));
        for(EvdevDeviceScanner.Device device:devices){CheckBox choice=new CheckBox(device.label()+" · "+device.kind());choice.setUserData(device.id());choice.setSelected(allow.contains(device.id()));choice.setDisable(!device.readable());if(!device.readable())choice.setTooltip(new javafx.scene.control.Tooltip(t("ОС не разрешает чтение этого устройства.","The OS does not permit reading this device.")));deviceRows.getChildren().add(choice);}
        Runnable saveInputs=()->mutate(c->{Map<String,Object> updated=map(c.get("settings"));Map<String,Object> in=map(updated.get("input"));in.put("enable_keyboard",keyboard.isSelected());in.put("enable_mouse",mouse.isSelected());in.put("enable_gnome",gnomeBox.isSelected());in.put("deduplicate_alt_tab_focus",altTabDedup.isSelected());List<String> selected=new ArrayList<>();for(Node node:deviceRows.getChildren())if(node instanceof CheckBox cb&&cb.isSelected()&&cb.getUserData() instanceof String id)selected.add(id);in.put("device_allowlist",selected);in.put("log_key_sequences",false);updated.put("input",in);c.put("settings",updated);});
        keyboard.setOnAction(e->saveInputs.run());mouse.setOnAction(e->saveInputs.run());gnomeBox.setOnAction(e->saveInputs.run());altTabDedup.setOnAction(e->saveInputs.run());for(Node node:deviceRows.getChildren())if(node instanceof CheckBox cb)cb.setOnAction(e->saveInputs.run());
        VBox devicesCard=card(t("Выбранные устройства","Selected devices"));devicesCard.getChildren().addAll(keyboard,mouse,gnomeBox,altTabDedup,deviceRows,button(t("Обновить устройства","Refresh devices"),()->render(Page.INPUTS)));root.getChildren().add(devicesCard);
        VBox status=card(t("Состояние источников","Source status"));status.getChildren().addAll(labelNode(sourceState,"status-pill"),labelNode(t("EVDEV SYN_REPORT обрабатывается как граница пакета. Потеря источника сбрасывает состояние зажатых клавиш.","EVDEV SYN_REPORT is treated as a packet boundary. Losing a device clears pressed-key state."),"muted"));root.getChildren().add(status);return root;
    }

    protected VBox profilesPage(){
        Map<String,Object> config=store.snapshot();VBox root=page(t("Профили управляют громкостью и активным набором привязок. Выходы событий настраиваются в графе Audio Matrix.","Profiles control effect level and active bindings. Event outputs are configured in the Audio Matrix graph."));VBox card=card(t("Профили","Profiles"));String active=String.valueOf(config.get("active_profile"));List<Map<String,Object>> visibleProfiles=maps(config.get("profiles")).stream().filter(profile->matches(String.valueOf(profile.get("name"))+" "+String.valueOf(profile.get("id")),search.getText())).toList();
        card.getChildren().add(button(t("Обновить список выходов","Refresh audio output list"),this::refreshAudioOutputs));
        if(visibleProfiles.isEmpty()){Label noProfiles=labelNode(t("Профили не найдены.","No profiles found."),"muted");noProfiles.getStyleClass().add("empty-state");card.getChildren().add(noProfiles);}
        for(Map<String,Object> profile:visibleProfiles){String id=String.valueOf(profile.get("id"));VBox row=new VBox(8);row.getStyleClass().add("list-row");VBox info=new VBox(4,labelNode(String.valueOf(profile.get("name")),"card-title"),labelNode(id,"muted"));Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);HBox header=new HBox(12,info,spacer);header.setAlignment(Pos.CENTER_LEFT);if(active.equals(id))header.getChildren().add(labelNode(t("Активен","Active"),"accent"));row.getChildren().add(header);
            Button volume=button(Math.round(num(profile.get("master_volume"),.65)*100)+"%",()->editProfileVolume(id));volume.setAccessibleText(t("Изменить громкость профиля ","Edit profile volume ")+String.valueOf(profile.get("name")));
            FlowPane actions=new FlowPane(8,8);actions.getChildren().add(volume);if(!active.equals(id))actions.getChildren().add(button(t("Активировать","Activate"),()->mutate(c->c.put("active_profile",id))));actions.getChildren().add(button(t("Экспорт ZIP…","Export ZIP…"),()->exportProfile(id)));actions.getChildren().add(button(t("Переименовать","Rename"),()->renameProfile(id)));if(maps(store.snapshot().get("profiles")).size()>1)actions.getChildren().add(button(t("Удалить","Delete"),()->deleteProfile(id)));row.getChildren().add(actions);card.getChildren().add(row);}
        card.getChildren().add(button(t("Создать копию активного профиля","Copy active profile"),()->mutate(c->{Map<String,Object> source=activeProfile(c);Map<String,Object> copy=deepMap(source);String id="profile-"+UUID.randomUUID().toString().substring(0,8);copy.put("id",id);copy.put("name",source.getOrDefault("name","Profile")+" copy");maps(c.get("profiles")).add(copy);})));root.getChildren().add(card);
        root.getChildren().add(sourceProfilesCard(config));
        VBox transfer=card(t("Резервная копия и перенос","Backup and transfer"));transfer.getChildren().addAll(labelNode(t("Полный ZIP включает конфигурацию и WAV; его импорт заменяет текущую конфигурацию и отключает input/API. Профильный ZIP добавляет новый неактивный профиль; ZIP-набор звуков дополняет библиотеку, а набор привязок импортируется как профиль.","A full ZIP contains the configuration and WAV files; importing it replaces the current configuration and disables input/API. A profile ZIP adds a new inactive profile; a sound-set ZIP merges into the library, and a binding-set ZIP imports as a profile."),"muted"),button(t("Экспортировать полную копию…","Export full backup…"),this::exportArchive),button(t("Проверить и импортировать ZIP…","Preview and import ZIP…"),this::importArchive),labelNode(t("При совпадении ID профильного/звукового архива связанные ссылки переназначаются. Выходы устройства в экспортируемом профиле сбрасываются на переносимые значения.","Profile and sound-set ID conflicts are remapped with their references. Device outputs in exported profiles are reset to portable defaults."),"warning"));root.getChildren().add(transfer);return root;
    }

    protected VBox sourceProfilesCard(Map<String,Object> config){
        VBox card=card(t("Профили источников событий","Event source profiles"));card.getChildren().add(labelNode(t("Клавиатура, мышь и системные события могут использовать разные профили. «Активный профиль» наследует выбор в карточке выше.","Keyboard, mouse, and system events can use different profiles. “Follow active profile” inherits the selection above."),"muted"));Map<String,Object> overrides=map(config.get("source_profiles"));List<Map<String,Object>> profiles=maps(config.get("profiles"));
        for(String source:List.of("keyboard","mouse","system")){String category=source;String label=switch(source){case "keyboard"->t("Клавиатура","Keyboard");case "mouse"->t("Мышь и колесо","Mouse and wheel");default->t("Системные события / API","System events / API");};ComboBox<String> choice=new ComboBox<>();choice.getItems().add("inherit");profiles.forEach(profile->choice.getItems().add(String.valueOf(profile.get("id"))));choice.setValue(String.valueOf(overrides.getOrDefault(source,"inherit")));choice.setAccessibleText(label+t(" — профиль источника"," source profile"));choice.setButtonCell(sourceProfileCell(config));choice.setCellFactory(list->sourceProfileCell(config));choice.setOnAction(e->mutate(c->{Map<String,Object> selected=new LinkedHashMap<>(map(c.get("source_profiles")));if("inherit".equals(choice.getValue()))selected.remove(category);else selected.put(category,choice.getValue());c.put("source_profiles",selected);}));Label rowLabel=labelNode(label,"");rowLabel.setLabelFor(choice);HBox row=new HBox(12,rowLabel,choice);row.setAlignment(Pos.CENTER_LEFT);HBox.setHgrow(choice,Priority.ALWAYS);card.getChildren().add(row);}
        return card;
    }

    protected javafx.scene.control.ListCell<String> sourceProfileCell(Map<String,Object> config){return new javafx.scene.control.ListCell<>(){@Override protected void updateItem(String item,boolean empty){super.updateItem(item,empty);if(empty||item==null){setText(null);return;}if("inherit".equals(item)){setText(t("Активный профиль","Follow active profile"));return;}Map<String,Object> profile=findProfile(config,item);setText(profile==null?item:String.valueOf(profile.get("name"))+" · "+item);}};}

    protected void exportArchive(){
        FileChooser chooser=new FileChooser();chooser.setTitle(t("Экспортировать резервную копию","Export backup"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Event Sound Studio archive","*.ess.zip"));chooser.setInitialFileName("event-sound-studio.ess.zip");java.io.File file=chooser.showSaveDialog(getScene().getWindow());if(file==null)return;
        Map<String,Object> snapshot=store.snapshot();Path data=store.dataDirectory();Path destination=file.toPath();setNotice(t("Создаю ZIP-архив…","Creating ZIP archive…"),false);
        runtime.execute(()->{try{ConfigArchive.exportArchive(destination,snapshot,data);Platform.runLater(()->setNotice(t("Архив сохранён: ","Archive saved: ")+destination,false));}catch(Exception e){Platform.runLater(()->setNotice(t("Экспорт не выполнен: ","Export failed: ")+message(e),true));}});
    }

    protected void exportSoundSet(){
        Set<String> selection=Set.copyOf(selectedSoundExports);if(selection.isEmpty())return;
        FileChooser chooser=new FileChooser();chooser.setTitle(t("Экспорт набора звуков","Export sound set"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Event Sound Studio sound set","*.ess.zip"));chooser.setInitialFileName("event-sound-studio-sounds.ess.zip");java.io.File file=chooser.showSaveDialog(getScene().getWindow());if(file==null)return;
        Map<String,Object> snapshot=store.snapshot();Path data=store.dataDirectory(),destination=file.toPath();setNotice(t("Упаковываю выбранные звуки и зависимости…","Packaging selected sounds and dependencies…"),false);
        runtime.execute(()->{try{ConfigArchive.exportSoundSetArchive(destination,snapshot,data,selection);Platform.runLater(()->setNotice(t("Набор звуков сохранён: ","Sound set saved: ")+destination,false));}catch(Exception e){Platform.runLater(()->setNotice(t("Набор звуков не экспортирован: ","Sound set export failed: ")+message(e),true));}});
    }

    protected void exportBindingSet(){
        Set<String> selection=Set.copyOf(selectedBindings);if(selection.isEmpty())return;
        FileChooser chooser=new FileChooser();chooser.setTitle(t("Экспорт набора привязок","Export binding set"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Event Sound Studio binding set","*.ess.zip"));chooser.setInitialFileName("event-sound-studio-bindings.ess.zip");java.io.File file=chooser.showSaveDialog(getScene().getWindow());if(file==null)return;
        Map<String,Object> snapshot=store.snapshot();Path data=store.dataDirectory(),destination=file.toPath();setNotice(t("Упаковываю выбранные привязки и зависимости…","Packaging selected bindings and dependencies…"),false);
        runtime.execute(()->{try{ConfigArchive.exportBindingSetArchive(destination,snapshot,data,selection);Platform.runLater(()->setNotice(t("Набор привязок сохранён: ","Binding set saved: ")+destination,false));}catch(Exception e){Platform.runLater(()->setNotice(t("Набор привязок не экспортирован: ","Binding set export failed: ")+message(e),true));}});
    }

    protected void exportProfile(String profileId){
        Map<String,Object> snapshot=store.snapshot();Map<String,Object> profile=findProfile(snapshot,profileId);
        if(profile==null){setNotice(t("Профиль уже удалён.","The profile no longer exists."),true);return;}
        FileChooser chooser=new FileChooser();chooser.setTitle(t("Экспорт профиля","Export profile"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Event Sound Studio profile","*.ess.zip"));
        String safeId=profileId.replaceAll("[^A-Za-z0-9._-]","-");chooser.setInitialFileName("event-sound-studio-"+safeId+".ess.zip");java.io.File file=chooser.showSaveDialog(getScene().getWindow());if(file==null)return;
        Path destination=file.toPath();Path data=store.dataDirectory();setNotice(t("Упаковываю профиль и связанные звуки…","Packaging profile and linked sounds…"),false);
        runtime.execute(()->{try{ConfigArchive.exportProfileArchive(destination,snapshot,data,profileId);Platform.runLater(()->setNotice(t("Архив профиля сохранён: ","Profile archive saved: ")+destination,false));}catch(Exception e){Platform.runLater(()->setNotice(t("Профиль не экспортирован: ","Profile export failed: ")+message(e),true));}});
    }

    protected void importArchive(){
        FileChooser chooser=new FileChooser();chooser.setTitle(t("Выбрать резервную копию","Choose backup"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Event Sound Studio archive","*.ess.zip","*.zip"));java.io.File file=chooser.showOpenDialog(getScene().getWindow());if(file==null)return;Path archive=file.toPath();setNotice(t("Проверяю архив и ресурсы…","Checking archive and resources…"),false);
        runtime.execute(()->{try{ConfigArchive.PreparedImport prepared=ConfigArchive.prepare(archive);Platform.runLater(()->confirmArchiveImport(prepared));}catch(Exception e){Platform.runLater(()->setNotice(t("Архив не прошёл проверку: ","Archive validation failed: ")+message(e),true));}});
    }
}
