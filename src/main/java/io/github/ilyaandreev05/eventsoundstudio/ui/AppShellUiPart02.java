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

abstract class AppShellUiPart02 extends AppShellUiPart01 {
    protected AppShellUiPart02(ConfigStore store, AudioEngine engine, SoundLibrary library, EventRouter router, EvdevDeviceScanner scanner, EvdevInputService input, InputEventDispatcher dispatcher, LocalControlApi api, ThemeManager theme) { super(store, engine, library, router, scanner, input, dispatcher, api, theme); }

    protected void exportGeneratedWav(TextField name,io.github.ilyaandreev05.eventsoundstudio.audio.SynthesisParameters parameters){
        try{
            if(name.getText().isBlank())throw new IllegalArgumentException(t("Введите имя файла.","Enter a file name."));
            FileChooser chooser=new FileChooser();chooser.setTitle(t("Экспорт готового звука","Export generated sound"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio","*.wav","*.WAV"));chooser.setInitialFileName(name.getText().trim().replaceAll("[\\\\/:*?\\\"<>|]","_")+".wav");java.io.File file=chooser.showSaveDialog(getScene().getWindow());if(file==null)return;
            Path destination=ensureWavSuffix(file.toPath());boolean replace=Files.exists(destination);if(replace&&!confirmOverwrite(destination))return;
            setNotice(t("Экспортирую WAV…","Exporting WAV…"),false);library.exportGenerated(parameters,destination,replace).whenComplete((saved,error)->Platform.runLater(()->setNotice(error==null?t("WAV сохранён: ","WAV exported: ")+saved:t("Экспорт не выполнен: ","Export failed: ")+message(error),error!=null)));
        }catch(Exception e){setNotice(t("Параметры генератора не приняты: ","Invalid generator settings: ")+message(e),true);}
    }

    protected void editFragment(SoundLibrary.Entry entry){
        if(!entry.ready()){setNotice(message(new IllegalStateException("Sound file is unavailable")),true);return;}
        Dialog<Void> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Осциллограмма и фрагмент","Waveform and fragment"));dialog.setHeaderText(entry.name());styleDialog(dialog,960);
        PcmSound sound=entry.pcm();int frames=sound.frames();
        if(frames<1){setNotice(t("WAV не содержит аудиокадров.","The WAV contains no audio frames."),true);return;}
        Canvas wave=new Canvas(900,145);wave.getStyleClass().add("waveform");wave.setAccessibleText(t("Осциллограмма WAV; начало и конец фрагмента задаются ползунками ниже.","WAV waveform; fragment start and end are set with the sliders below."));
        Canvas spectrum=new Canvas(900,125);spectrum.getStyleClass().add("waveform");spectrum.setAccessibleText(t("Относительный спектр WAV, от 0 до 24 килогерц.","Relative WAV spectrum from 0 to 24 kilohertz."));
        Slider start=new Slider(0,0.999,0),end=new Slider(0.001,1,1);start.setAccessibleText(t("Начало фрагмента","Fragment start"));end.setAccessibleText(t("Конец фрагмента","Fragment end"));
        Label startTime=labelNode("0.000 s","muted"),endTime=labelNode(String.format(Locale.ROOT,"%.3f s",frames/(double)sound.sampleRate()),"muted");
        Label startLabel=labelNode(t("Начало","Start"),"");startLabel.setLabelFor(start);Label endLabel=labelNode(t("Конец","End"),"");endLabel.setLabelFor(end);
        HBox startRow=new HBox(10,startLabel,start,startTime);HBox endRow=new HBox(10,endLabel,end,endTime);startRow.setAlignment(Pos.CENTER_LEFT);endRow.setAlignment(Pos.CENTER_LEFT);HBox.setHgrow(start,Priority.ALWAYS);HBox.setHgrow(end,Priority.ALWAYS);
        TextField fragmentName=text(entry.name()+t(" — фрагмент"," — fragment"),t("Имя нового фрагмента","New fragment name"));Label fragmentNameLabel=labelNode(t("Сохранить как","Save as"),"");fragmentNameLabel.setLabelFor(fragmentName);
        Label selectionInfo=labelNode("","muted");
        Button preview=button(t("Прослушать выделение","Preview selection"),()->{int from=selectedStart(start.getValue(),frames);int to=selectedEnd(end.getValue(),frames);if(to<=from)return;float[] pcm=java.util.Arrays.copyOfRange(sound.samples(),from*sound.channels(),to*sound.channels());engine.play(new PcmSound("fragment-preview",entry.name(),pcm,sound.channels(),sound.sampleRate()),num(entry.metadata().get("volume"),.7),0,0,1,"restart","sample-editor:"+entry.id());});
        Button save=button(t("Сохранить фрагмент в библиотеку","Save fragment to library"),()->{int from=selectedStart(start.getValue(),frames);int to=selectedEnd(end.getValue(),frames);if(fragmentName.getText().isBlank()||to<=from)return;setNotice(t("Сохраняю фрагмент…","Saving fragment…"),false);library.saveFragment(entry,fragmentName.getText(),from,to).whenComplete((saved,error)->Platform.runLater(()->{if(error!=null){setNotice(t("Фрагмент не сохранён: ","Fragment was not saved: ")+message(error),true);return;}mutate(c->maps(c.get("sounds")).add(saved.metadata()));}));});
        save.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(()->fragmentName.getText().isBlank(),fragmentName.textProperty()));
        Runnable redraw=()->{int from=selectedStart(start.getValue(),frames);int to=selectedEnd(end.getValue(),frames);drawWave(wave,sound,start.getValue(),end.getValue());drawSpectrum(spectrum,SoundSynthesizer.magnitudeSpectrum(sound,from,to));double startSeconds=from/(double)sound.sampleRate(),endSeconds=to/(double)sound.sampleRate();startTime.setText(String.format(Locale.ROOT,"%.3f s",startSeconds));endTime.setText(String.format(Locale.ROOT,"%.3f s",endSeconds));selectionInfo.setText(t("Выделено ","Selected ")+String.format(Locale.ROOT,"%.3f s",endSeconds-startSeconds)+" · "+(to-from)+t(" кадров"," frames"));};
        start.valueProperty().addListener((observable,oldValue,newValue)->{if(newValue.doubleValue()>=end.getValue())end.setValue(Math.min(1,newValue.doubleValue()+1.0/frames));redraw.run();});
        end.valueProperty().addListener((observable,oldValue,newValue)->{if(newValue.doubleValue()<=start.getValue())start.setValue(Math.max(0,newValue.doubleValue()-1.0/frames));redraw.run();});redraw.run();
        VBox content=new VBox(10,wave,new Label(t("Спектр · 0–24 кГц, относительный уровень","Spectrum · 0–24 kHz, relative level")),spectrum,startRow,endRow,new HBox(10,fragmentNameLabel,fragmentName),selectionInfo,labelNode(t("Исходный WAV не меняется: выделение сохранится отдельным звуком.","The source WAV stays unchanged; the selection is saved as a separate sound."),"muted"),new HBox(10,preview,save));VBox.setVgrow(wave,Priority.NEVER);VBox.setVgrow(spectrum,Priority.NEVER);HBox.setHgrow(fragmentName,Priority.ALWAYS);content.setPadding(new Insets(8));dialog.getDialogPane().setContent(content);dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);dialog.setOnShown(event->start.requestFocus());dialog.showAndWait();
    }

    protected void drawWave(Canvas canvas,PcmSound sound){drawWave(canvas,sound,0,1);}

    protected void drawWave(Canvas canvas,PcmSound sound,double selectionStart,double selectionEnd){GraphicsContext g=canvas.getGraphicsContext2D();double width=canvas.getWidth(),height=canvas.getHeight(),mid=height/2;g.clearRect(0,0,width,height);javafx.scene.paint.Color accent=accentColor();if(sound!=null&&selectionEnd>selectionStart){g.setFill(accent.deriveColor(0,1,1,.14));g.fillRect(selectionStart*width,0,(selectionEnd-selectionStart)*width,height);}g.setStroke(accent);g.setLineWidth(1.3);g.strokeLine(0,mid,width,mid);if(sound==null||sound.frames()==0)return;float[] samples=sound.samples();int channels=sound.channels(),frames=sound.frames();for(int x=0;x<(int)width;x++){int from=x*frames/(int)width,to=Math.max(from+1,(x+1)*frames/(int)width);to=Math.min(frames,to);float min=1,max=-1;for(int frame=from;frame<to;frame++)for(int channel=0;channel<channels;channel++){float value=samples[frame*channels+channel];min=Math.min(min,value);max=Math.max(max,value);}g.strokeLine(x,mid-max*mid*.82,x,mid-min*mid*.82);}if(selectionStart>0||selectionEnd<1){g.setLineWidth(1.8);g.strokeLine(selectionStart*width,0,selectionStart*width,height);g.strokeLine(selectionEnd*width,0,selectionEnd*width,height);}}

    protected void drawSpectrum(Canvas canvas,double[] magnitudes){GraphicsContext g=canvas.getGraphicsContext2D();double width=canvas.getWidth(),height=canvas.getHeight(),base=height-18;g.clearRect(0,0,width,height);javafx.scene.paint.Color accent=accentColor();g.setStroke(accent.deriveColor(0,1,1,.28));for(int row=1;row<=3;row++){double y=base-(height-28)*row/4;g.strokeLine(0,y,width,y);}g.setStroke(accent);g.setFill(accent);g.setLineWidth(1);if(magnitudes.length>0)for(int x=0;x<(int)width;x++){int bin=Math.min(magnitudes.length-1,x*magnitudes.length/(int)width);double amplitude=Math.max(0,Math.min(1,magnitudes[bin]));g.strokeLine(x,base,x,base-amplitude*(height-28));}g.setFill(accentColor());g.fillText("0",2,height-3);g.fillText("12 kHz",width/2-22,height-3);g.fillText("24 kHz",width-45,height-3);}

    protected javafx.scene.paint.Color accentColor(){boolean dark=getScene()!=null&&getScene().getRoot().getStyleClass().contains("dark-theme");return javafx.scene.paint.Color.web(dark?"#48c5d6":"#006e80");}

    protected VBox visualInputCard(){
        Map<String,Object> config=store.snapshot();Set<String> enabledIds=new java.util.HashSet<>(list(activeProfile(config).get("enabled_bindings")).stream().map(String::valueOf).toList());List<Map<String,Object>> configBindings=maps(config.get("bindings")),groups=maps(config.get("groups"));Set<String> mappedKeys=new java.util.HashSet<>();
        for(Map<String,Object> binding:configBindings){String id=String.valueOf(binding.get("id")),event=String.valueOf(binding.get("event"));if(!Boolean.TRUE.equals(binding.get("enabled"))||!enabledIds.contains(id))continue;if(event.startsWith("key:")){String[] parts=event.split(":");if(parts.length>=3)mappedKeys.add(parts[1]);}else if(event.startsWith("group:")){String groupId=event.substring("group:".length());for(Map<String,Object> group:groups)if(groupId.equals(group.get("id")))mappedKeys.addAll(list(group.get("members")).stream().map(String::valueOf).toList());}}
        VBox card=card(t("Клавиатура и мышь","Keyboard and mouse"));card.getChildren().add(labelNode(t("Подсвеченные клавиши входят в активные привязки. Нажмите кнопку для локального предпрослушивания без evdev.","Highlighted keys have active bindings. Press a key to preview it locally without evdev."),"muted"));GridPane grid=new GridPane();grid.setHgap(4);grid.setVgap(4);
        List<List<String>> rows=List.of(List.of("KEY_ESC","KEY_1","KEY_2","KEY_3","KEY_4","KEY_5","KEY_6","KEY_7","KEY_8","KEY_9","KEY_0","KEY_BACKSPACE"),List.of("KEY_TAB","KEY_Q","KEY_W","KEY_E","KEY_R","KEY_T","KEY_Y","KEY_U","KEY_I","KEY_O","KEY_P"),List.of("KEY_LEFTCTRL","KEY_A","KEY_S","KEY_D","KEY_F","KEY_G","KEY_H","KEY_J","KEY_K","KEY_L","KEY_ENTER"),List.of("KEY_LEFTSHIFT","KEY_Z","KEY_X","KEY_C","KEY_V","KEY_B","KEY_N","KEY_M","KEY_RIGHTSHIFT"),List.of("KEY_LEFTCTRL","KEY_LEFTMETA","KEY_LEFTALT","KEY_SPACE","KEY_RIGHTALT","KEY_RIGHTMETA","KEY_RIGHTCTRL"),List.of("KEY_F1","KEY_F2","KEY_F3","KEY_F4","KEY_F5","KEY_F6","KEY_F7","KEY_F8","KEY_F9","KEY_F10","KEY_F11","KEY_F12"),List.of("KEY_MUTE","KEY_VOLUMEDOWN","KEY_VOLUMEUP","KEY_PLAYPAUSE","KEY_STOPCD","KEY_PREVIOUSSONG","KEY_NEXTSONG","KEY_MICMUTE","KEY_BRIGHTNESSDOWN","KEY_BRIGHTNESSUP"));
        for(int row=0;row<rows.size();row++)for(int column=0;column<rows.get(row).size();column++){String key=rows.get(row).get(column);Button cap=button(keyLabel(key),()->dispatcher.previewKey(key));cap.getStyleClass().add("key-cap");if(mappedKeys.contains(key))cap.getStyleClass().add("key-cap-bound");cap.setTooltip(new javafx.scene.control.Tooltip(key));cap.setAccessibleText(t("Проверить клавишу ","Preview key ")+key);grid.add(cap,column,row);}
        HBox arrows=new HBox(5,keyCap("KEY_LEFT","←"),keyCap("KEY_DOWN","↓"),keyCap("KEY_UP","↑"),keyCap("KEY_RIGHT","→"));HBox mouse=new HBox(8);for(String button:List.of("BTN_LEFT","BTN_MIDDLE","BTN_RIGHT","BTN_SIDE","BTN_EXTRA")){String label=switch(button){case "BTN_LEFT"->t("ЛКМ","Left");case "BTN_MIDDLE"->t("Колесо","Middle");case "BTN_RIGHT"->t("ПКМ","Right");case "BTN_SIDE"->t("Назад","Back");default->t("Вперёд","Forward");};Button control=button(label,()->dispatcher.previewMouseButton(button));control.setAccessibleText(t("Проверить кнопку мыши ","Preview mouse button ")+label);mouse.getChildren().add(control);}
        card.getChildren().addAll(grid,arrows);VBox mouseCard=card(t("Кнопки мыши · локальный тест","Mouse buttons · local test"));mouseCard.getChildren().add(mouse);VBox section=new VBox(18,card,mouseCard);return section;
    }

    protected Button keyCap(String key,String label){Button cap=button(label,()->dispatcher.previewKey(key));cap.getStyleClass().add("key-cap");cap.setTooltip(new javafx.scene.control.Tooltip(key));cap.setAccessibleText(t("Проверить клавишу ","Preview key ")+key);return cap;}

    protected VBox bindingsPage(){
        Map<String,Object> config=store.snapshot();
        VBox root=page(t("Привязка задаёт звук, событие, приоритет и локальные overrides.","A binding defines sound, event, priority, and per-binding overrides."));
        root.getChildren().add(visualInputCard());
        if(undoAction!=null)root.getChildren().add(button(undoLabel,this::undoLastDelete));
        root.getChildren().add(eventsCard(config));
        VBox card=card(t("Таблица привязок","Bindings"));
        ComboBox<String> sounds=new ComboBox<>();library.entries().stream().filter(SoundLibrary.Entry::ready).forEach(sound->sounds.getItems().add(sound.id()));
        Map<String,String> eventNames=new java.util.HashMap<>();for(Map<String,Object> event:maps(config.get("event_types")))eventNames.put(String.valueOf(event.get("id")),String.valueOf(event.getOrDefault("name",event.get("id"))));
        List<Map<String,Object>> bindings=maps(config.get("bindings")).stream().filter(binding->matches(binding.getOrDefault("name",binding.get("id"))+" "+binding.get("id")+" "+binding.get("event")+" "+eventNames.getOrDefault(String.valueOf(binding.get("event")),"")+" "+binding.get("sound"),search.getText())).toList();
        ComboBox<String> batchSound=new ComboBox<>();batchSound.getItems().addAll(sounds.getItems());batchSound.setPromptText(t("Звук для выбранных…","Sound for selected…"));batchSound.setAccessibleText(t("Новый звук для всех выбранных привязок","Replacement sound for selected bindings"));Label selectedCount=labelNode("","muted");
        Button chooseVisible=button(t("Выбрать видимые","Select visible"),()->{}),clearSelection=button(t("Снять выбор","Clear selection"),()->{}),applyBatch=button(t("Назначить выбранным","Assign to selected"),()->{}),exportSelection=button(t("Экспорт набора ZIP…","Export binding set ZIP…"),this::exportBindingSet);
        Runnable refreshBatch=()->{selectedCount.setText(t("Выбрано: ","Selected: ")+selectedBindings.size());applyBatch.setDisable(selectedBindings.isEmpty()||batchSound.getValue()==null);exportSelection.setDisable(selectedBindings.isEmpty());};
        chooseVisible.setOnAction(e->{bindings.forEach(binding->selectedBindings.add(String.valueOf(binding.get("id"))));refreshBatch.run();});clearSelection.setOnAction(e->{selectedBindings.clear();refreshBatch.run();});batchSound.valueProperty().addListener((o,a,v)->refreshBatch.run());
        applyBatch.setOnAction(e->{String soundId=batchSound.getValue();if(soundId==null||selectedBindings.isEmpty())return;Set<String> targets=Set.copyOf(selectedBindings);mutate(c->{for(Map<String,Object> item:maps(c.get("bindings")))if(targets.contains(String.valueOf(item.get("id"))))item.put("sound",soundId);});});refreshBatch.run();FlowPane batchBar=new FlowPane(8,8);batchBar.getChildren().addAll(chooseVisible,clearSelection,batchSound,applyBatch,selectedCount,exportSelection);card.getChildren().add(batchBar);
        if(bindings.isEmpty()){Label noBindings=labelNode(t("Привязки не найдены. Измените запрос поиска или добавьте привязку ниже.","No bindings found. Adjust the search query or add a binding below."),"muted");noBindings.getStyleClass().add("empty-state");card.getChildren().add(noBindings);}
        for(Map<String,Object> binding:bindings){
            String id=String.valueOf(binding.get("id")),event=String.valueOf(binding.get("event")),sound=String.valueOf(binding.get("sound"));
            String eventLabel=eventNames.getOrDefault(event,event);String bindingName=String.valueOf(binding.getOrDefault("name",id));
            VBox row=new VBox(8);row.getStyleClass().add("list-row");
            VBox detail=new VBox(4,labelNode(bindingName,"card-title"),labelNode(eventLabel+" · "+event+" · priority "+binding.getOrDefault("priority",0)+" · cooldown "+binding.getOrDefault("cooldown_ms",0)+" ms · "+binding.getOrDefault("overlap","mix"),"muted"));HBox.setHgrow(detail,Priority.ALWAYS);
            CheckBox selected=new CheckBox(t("Выбрать","Select"));selected.setAccessibleText(t("Выбрать привязку ","Select binding ")+bindingName);selected.setSelected(selectedBindings.contains(id));selected.setOnAction(e->{if(selected.isSelected())selectedBindings.add(id);else selectedBindings.remove(id);refreshBatch.run();});
            CheckBox enabled=new CheckBox(t("Включено","Enabled"));enabled.setSelected(Boolean.TRUE.equals(binding.get("enabled"))&&profileEnabled(id,event));enabled.setOnAction(e->setBindingEnabled(id,event,enabled.isSelected()));
            ComboBox<String> soundBox=new ComboBox<>();soundBox.getItems().addAll(sounds.getItems());soundBox.setValue(sound);soundBox.setAccessibleText(t("Звук привязки "+bindingName,"Sound for binding "+bindingName));soundBox.setOnAction(e->{if(soundBox.getValue()!=null)updateBinding(id,"sound",soundBox.getValue());});
            HBox summary=new HBox(10,selected,detail,enabled,soundBox);summary.setAlignment(Pos.CENTER_LEFT);HBox.setHgrow(detail,Priority.ALWAYS);
            MenuItem edit=new MenuItem(t("Изменить…","Edit…"));edit.setOnAction(e->editBindingMetadata(id));MenuItem options=new MenuItem(t("Аудио…","Audio…"));options.setOnAction(e->editBinding(binding));MenuItem copy=new MenuItem(t("Копировать…","Copy…"));copy.setOnAction(e->copyBinding(id));MenuItem remove=new MenuItem(t("Удалить…","Delete…"));remove.setOnAction(e->deleteBinding(id));
            MenuButton actions=new MenuButton(t("Действия","Actions"));actions.setAccessibleText(t("Действия для привязки ","Actions for binding ")+bindingName);actions.getItems().addAll(edit,options,copy,remove);Button test=button(t("Прослушать","Preview"),()->router.playBinding(id));
            HBox rowActions=new HBox(8,actions,test);rowActions.setAlignment(Pos.CENTER_RIGHT);row.getChildren().addAll(summary,rowActions);card.getChildren().add(row);
        }
        card.getChildren().add(button(t("Добавить привязку","Add binding"),this::addBinding));root.getChildren().add(card);
        VBox explain=card(t("Порядок разрешения конфликтов","Conflict priority"));explain.getChildren().addAll(labelNode(t("Комбинации 2–4 клавиш имеют приоритет над обычным событием. Индивидуальная клавиша переопределяет свою защищённую группу.","Two-to-four-key combinations take priority over regular events. An individual key overrides its protected group."),""),labelNode(t("Одно событие может запускать несколько привязок; приоритет задаёт порядок, а каждый биндинг хранит собственные аудио-настройки.","One event can trigger several bindings; priority sets their order and every binding keeps its own audio settings."),"muted"));root.getChildren().add(explain);return root;
    }

    protected VBox eventsCard(Map<String,Object> config){
        VBox card=card(t("События","Events"));card.getChildren().add(labelNode(t("Пользовательские события вызываются через локальный API trigger(eventId). Встроенные источники и условия ввода заданы приложением.","Custom events can be fired through the local trigger(eventId) API. Built-in input sources and match rules are provided by the app."),"muted"));
        List<Map<String,Object>> events=maps(config.get("event_types")).stream().filter(event->matches(event.getOrDefault("name",event.get("id"))+" "+event.get("id")+" "+event.get("source"),search.getText())).toList();
        for(Map<String,Object> event:events){String id=String.valueOf(event.get("id")),source=String.valueOf(event.getOrDefault("source","custom"));boolean custom="custom".equals(source);long references=maps(config.get("bindings")).stream().filter(binding->id.equals(binding.get("event"))).count();long comboReferences=maps(config.get("combos")).stream().filter(combo->id.equals(combo.get("event"))).count();
            HBox row=new HBox(10);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("list-row");VBox detail=new VBox(4,labelNode(String.valueOf(event.getOrDefault("name",id)),"card-title"),labelNode(id+" · "+source+" · "+t("привязок: ","bindings: ")+references+" · "+t("комбинаций: ","combos: ")+comboReferences,"muted"));HBox.setHgrow(detail,Priority.ALWAYS);
            Button edit=button(t("Изменить","Edit"),()->editEvent(id));Button copy=button(t("Копировать","Copy"),()->copyEvent(id));Button remove=button(t("Удалить","Delete"),()->deleteEvent(id));remove.setDisable(!custom||references>0||comboReferences>0);remove.setTooltip(new javafx.scene.control.Tooltip(!custom?t("Встроенные события задаются приложением.","Built-in events are defined by the app."):t("Сначала удалите связанные привязки и комбинации.","Remove linked bindings and combos first.")));
            row.getChildren().addAll(detail,edit,copy,remove);card.getChildren().add(row);
        }
        if(events.isEmpty()){Label noEvents=labelNode(t("События не найдены. Измените запрос поиска или добавьте пользовательское событие ниже.","No events found. Adjust the search query or add a custom event below."),"muted");noEvents.getStyleClass().add("empty-state");card.getChildren().add(noEvents);}
        card.getChildren().add(button(t("Добавить пользовательское событие","Add custom event"),this::addEvent));return card;
    }

    protected List<String> bindingEventChoices(Map<String,Object> config){
        java.util.LinkedHashSet<String> choices=new java.util.LinkedHashSet<>();for(Map<String,Object> event:maps(config.get("event_types")))choices.add(String.valueOf(event.get("id")));for(Map<String,Object> group:maps(config.get("groups")))choices.add("group:"+group.get("id"));for(Map<String,Object> binding:maps(config.get("bindings")))choices.add(String.valueOf(binding.get("event")));return List.copyOf(choices);
    }

    protected void setBindingEnabled(String id,String event,boolean enabled){mutate(c->{String source=eventSourceCategory(event,c);Map<String,Object> overrides=map(c.get("source_profiles"));Map<String,Object> profile=findProfile(c,String.valueOf(overrides.getOrDefault(source,c.get("active_profile"))));if(profile==null)profile=activeProfile(c);List<Object> ids=list(profile.get("enabled_bindings"));if(enabled&&!ids.contains(id))ids.add(id);else if(!enabled)ids.remove(id);profile.put("enabled_bindings",ids);boolean activeAnywhere=maps(c.get("profiles")).stream().anyMatch(candidate->list(candidate.get("enabled_bindings")).contains(id));for(Map<String,Object> binding:maps(c.get("bindings")))if(id.equals(binding.get("id")))binding.put("enabled",activeAnywhere);});}

    protected void updateBinding(String id,String key,Object value){mutate(c->{for(Map<String,Object>b:maps(c.get("bindings")))if(id.equals(b.get("id")))b.put(key,value);});}

    protected void editBindingMetadata(String id){
        Map<String,Object> current=maps(store.snapshot().get("bindings")).stream().filter(binding->id.equals(binding.get("id"))).findFirst().orElse(null);if(current==null)return;
        Map<String,Object> config=store.snapshot();Dialog<Map<String,Object>> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Изменить привязку","Edit binding"));dialog.setHeaderText(String.valueOf(current.getOrDefault("name",id)));styleDialog(dialog,520);
        TextField name=text(String.valueOf(current.getOrDefault("name",id)),t("Имя привязки","Binding name"));ComboBox<String> event=new ComboBox<>();event.getItems().setAll(bindingEventChoices(config));event.setEditable(true);event.setValue(String.valueOf(current.get("event")));event.setAccessibleText(t("Событие","Event"));
        TextField priority=text(String.valueOf(current.getOrDefault("priority",10)),t("Приоритет 0–1000","Priority 0–1000")),cooldown=text(String.valueOf(current.getOrDefault("cooldown_ms",0)),t("Cooldown, мс","Cooldown in ms"));Label error=labelNode("","danger");
        GridPane form=new GridPane();form.setHgap(12);form.setVgap(10);form.setPadding(new Insets(8));Label nameCaption=labelNode(t("Имя","Name"),"");nameCaption.setLabelFor(name);Label eventCaption=labelNode(t("Событие","Event"),"");eventCaption.setLabelFor(event);Label priorityCaption=labelNode(t("Приоритет","Priority"),"");priorityCaption.setLabelFor(priority);Label cooldownCaption=labelNode(t("Cooldown, мс","Cooldown, ms"),"");cooldownCaption.setLabelFor(cooldown);form.addRow(0,nameCaption,name);form.addRow(1,eventCaption,event);form.addRow(2,priorityCaption,priority);form.addRow(3,cooldownCaption,cooldown);form.add(error,0,4,2,1);dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.control.Button save=(javafx.scene.control.Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);Runnable validate=()->{String eventId=event.getEditor().getText().trim();Integer p=boundedInt(priority,0,1000),c=boundedInt(cooldown,0,60_000);boolean known=bindingEventChoices(config).contains(eventId)||eventId.startsWith("key:")||eventId.startsWith("button:")||eventId.startsWith("wheel:");String issue=name.getText().isBlank()?t("Введите имя.","Enter a name."):eventId.isBlank()?t("Выберите или введите событие.","Choose or enter an event."):!known?t("Такого события нет.","That event does not exist."):p==null?t("Приоритет должен быть 0–1000.","Priority must be 0–1000."):c==null?t("Cooldown должен быть 0–60000 мс.","Cooldown must be 0–60000 ms."):"";error.setText(issue);save.setDisable(!issue.isEmpty());};
        name.textProperty().addListener((o,a,v)->validate.run());event.getEditor().textProperty().addListener((o,a,v)->validate.run());priority.textProperty().addListener((o,a,v)->validate.run());cooldown.textProperty().addListener((o,a,v)->validate.run());validate.run();
        dialog.setResultConverter(button->button==javafx.scene.control.ButtonType.OK?DefaultConfig.map("name",name.getText().trim(),"event",event.getEditor().getText().trim(),"priority",boundedInt(priority,0,1000),"cooldown_ms",boundedInt(cooldown,0,60_000)):null);
        dialog.showAndWait().ifPresent(values->mutate(c->{for(Map<String,Object> binding:maps(c.get("bindings")))if(id.equals(binding.get("id"))){binding.putAll(values);break;}}));
    }

    protected void copyBinding(String id){
        Map<String,Object> source=maps(store.snapshot().get("bindings")).stream().filter(binding->id.equals(binding.get("id"))).findFirst().orElse(null);if(source==null)return;
        Map<String,Object> copy=deepMap(source);String newId="binding-"+UUID.randomUUID().toString().substring(0,8);copy.put("id",newId);copy.put("name",String.valueOf(source.getOrDefault("name",id))+t(" — копия"," copy"));copy.put("enabled",false);
        mutate(config->{List<Map<String,Object>> bindings=maps(config.get("bindings"));int index=0;for(;index<bindings.size();index++)if(id.equals(bindings.get(index).get("id")))break;bindings.add(Math.min(index+1,bindings.size()),copy);});
    }

    protected void deleteBinding(String id){
        Map<String,Object> config=store.snapshot();List<Map<String,Object>> bindings=maps(config.get("bindings"));int index=-1;Map<String,Object> target=null;for(int i=0;i<bindings.size();i++)if(id.equals(bindings.get(i).get("id"))){index=i;target=bindings.get(i);break;}if(target==null)return;
        Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,t("Удалить привязку «","Delete binding ")+target.getOrDefault("name",id)+"?",javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);confirm.initOwner(getScene().getWindow());confirm.setHeaderText(null);
        int originalIndex=index;Map<String,Object> saved=deepMap(target);Map<String,Boolean> memberships=new LinkedHashMap<>();for(Map<String,Object> profile:maps(config.get("profiles")))memberships.put(String.valueOf(profile.get("id")),list(profile.get("enabled_bindings")).contains(id));
        confirm.showAndWait().filter(button->button==javafx.scene.control.ButtonType.OK).ifPresent(button->{selectedBindings.remove(id);offerUndo(t("Отменить удаление привязки","Undo binding deletion"),()->mutate(c->{List<Map<String,Object>> current=maps(c.get("bindings"));if(current.stream().noneMatch(binding->id.equals(binding.get("id"))))current.add(Math.min(originalIndex,current.size()),deepMap(saved));for(Map<String,Object> profile:maps(c.get("profiles"))){List<Object> active=list(profile.get("enabled_bindings"));if(Boolean.TRUE.equals(memberships.get(profile.get("id")))&&!active.contains(id))active.add(id);profile.put("enabled_bindings",active);}}));mutate(c->{maps(c.get("bindings")).removeIf(binding->id.equals(binding.get("id")));for(Map<String,Object> profile:maps(c.get("profiles")))list(profile.get("enabled_bindings")).remove(id);});});
    }

    protected void offerUndo(String label,Runnable action){undoLabel=label;undoAction=action;}

    protected void undoLastDelete(){Runnable action=undoAction;undoAction=null;undoLabel="";if(action!=null){action.run();render(page);}}

    protected void addEvent(){showEventDialog(null).ifPresent(values->{String id=values.get("id");mutate(c->maps(c.get("event_types")).add(DefaultConfig.map("id",id,"name",values.get("name"),"source","custom","match",DefaultConfig.map("event_id",id))));});}

    protected void editEvent(String id){
        Map<String,Object> event=maps(store.snapshot().get("event_types")).stream().filter(candidate->id.equals(candidate.get("id"))).findFirst().orElse(null);if(event==null)return;
        if(!"custom".equals(event.get("source"))){TextInputDialog dialog=new TextInputDialog(String.valueOf(event.getOrDefault("name",id)));dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Название события","Event name"));dialog.setHeaderText(id);dialog.setContentText(t("Имя","Name"));dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->mutate(c->{for(Map<String,Object> current:maps(c.get("event_types")))if(id.equals(current.get("id"))){current.put("name",name);break;}}));return;}
        showEventDialog(event).ifPresent(values->{String nextId=values.get("id");mutate(c->{for(Map<String,Object> current:maps(c.get("event_types")))if(id.equals(current.get("id"))){current.put("id",nextId);current.put("name",values.get("name"));current.put("source","custom");current.put("match",DefaultConfig.map("event_id",nextId));break;}if(!id.equals(nextId)){for(Map<String,Object> binding:maps(c.get("bindings")))if(id.equals(binding.get("event")))binding.put("event",nextId);for(Map<String,Object> combo:maps(c.get("combos")))if(id.equals(combo.get("event")))combo.put("event",nextId);}});});
    }

    protected java.util.Optional<Map<String,String>> showEventDialog(Map<String,Object> existing){
        boolean editing=existing!=null;String oldId=editing?String.valueOf(existing.get("id")):"";Dialog<Map<String,String>> dialog=new Dialog<>();dialog.initOwner(getScene().getWindow());dialog.setTitle(t(editing?"Настроить событие":"Новое пользовательское событие",editing?"Edit event":"New custom event"));styleDialog(dialog,460);
        TextField id=text(editing?oldId:"user.event",t("ID события","Event ID")),name=text(editing?String.valueOf(existing.getOrDefault("name",oldId)):t("Новое событие","New event"),t("Имя события","Event name"));if(editing&& !"custom".equals(existing.get("source")))id.setDisable(true);
        Label error=labelNode("","danger");GridPane form=new GridPane();form.setHgap(12);form.setVgap(10);form.setPadding(new Insets(8));Label idCaption=labelNode("ID","");idCaption.setLabelFor(id);Label nameCaption=labelNode(t("Имя","Name"),"");nameCaption.setLabelFor(name);form.addRow(0,idCaption,id);form.addRow(1,nameCaption,name);form.add(error,0,2,2,1);dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);javafx.scene.control.Button save=(javafx.scene.control.Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        Runnable validate=()->{String eventId=id.getText().trim(),eventName=name.getText().trim();boolean validId=eventId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");boolean duplicate=maps(store.snapshot().get("event_types")).stream().anyMatch(candidate->eventId.equals(candidate.get("id"))&&!eventId.equals(oldId));String issue=!validId?t("ID: латинские буквы, цифры, точка, дефис или подчёркивание.","ID: use ASCII letters, digits, dot, dash, or underscore."):duplicate?t("Такой ID уже занят.","That ID is already in use."):eventName.isBlank()?t("Введите имя события.","Enter an event name."):"";error.setText(issue);save.setDisable(!issue.isEmpty());};
        id.textProperty().addListener((o,a,v)->validate.run());name.textProperty().addListener((o,a,v)->validate.run());validate.run();dialog.setResultConverter(button->button==javafx.scene.control.ButtonType.OK?Map.of("id",id.getText().trim(),"name",name.getText().trim()):null);return dialog.showAndWait();
    }
}
