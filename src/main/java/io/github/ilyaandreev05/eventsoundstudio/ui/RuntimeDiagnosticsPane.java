package io.github.ilyaandreev05.eventsoundstudio.ui;

import io.github.ilyaandreev05.eventsoundstudio.audio.AudioEngine;
import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;
import io.github.ilyaandreev05.eventsoundstudio.audio.SoundLibrary;
import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.gnome.GnomeEventBridge;
import io.github.ilyaandreev05.eventsoundstudio.input.EvdevInputService;
import io.github.ilyaandreev05.eventsoundstudio.input.InputEventDispatcher;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** On-demand, aggregate-only diagnostics; never captures key or notification content. */
final class RuntimeDiagnosticsPane extends VBox {
    RuntimeDiagnosticsPane(AudioEngine engine, EventRouter router, SoundLibrary library,
                           EvdevInputService input, InputEventDispatcher dispatcher,
                           Supplier<GnomeEventBridge> gnome, ConfigStore store,
                           Consumer<Consumer<Map<String,Object>>> mutate, String title) {
        super(10);
        getStyleClass().add("card");
        Label heading=new Label(title);
        TextArea status=new TextArea();status.setEditable(false);status.setWrapText(true);status.setPrefRowCount(14);
        status.setAccessibleText("Aggregate input, routing and PCM diagnostics");
        ComboBox<String> device=new ComboBox<>();device.setPromptText("Selected input device / Устройство");
        device.setAccessibleText("Calibration device (explicitly authorized inputs only)");
        ComboBox<Integer> detents=new ComboBox<>();detents.getItems().setAll(1,5,10);detents.setValue(1);
        detents.setAccessibleText("Physical detents to rotate: 1, 5 or 10");
        ComboBox<String> axis=new ComboBox<>();axis.getItems().setAll("vertical","horizontal");axis.setValue("vertical");axis.setAccessibleText("Calibration axis");
        Label result=new Label("1 → 5 → 10: select device, start, rotate exactly that many detents in one direction, finish. Repeat each count. / Начните, прокрутите точное число делений, завершите.");result.setWrapText(true);
        Runnable refresh=()->{
            var audio=engine.diagnostics();var routing=router.diagnostics();
            StringBuilder text=new StringBuilder("EVENT: persistent isolated audiomatrix_events; no hardware fallback.\n");
            text.append("PCM enqueued=").append(audio.enqueuedRequests()).append(" rendered=").append(audio.playedRequests())
                .append(" dropped=").append(audio.droppedRequests()).append(" clipped samples=").append(audio.clippedSamples())
                .append(" queue=").append(audio.queueDepth()).append('\n');
            for(var output:audio.outputs())text.append(output.selector()).append(" | ").append(output.status()).append(" | ready=").append(output.ready())
                .append(" opens=").append(output.opens()).append(" restarts=").append(output.restarts()).append(" frames=").append(output.writtenFrames())
                .append(" dropped blocks=").append(output.droppedBlocks()).append(" queued blocks=").append(output.queuedBlocks()).append(" stalled=").append(output.stalled())
                .append(" buffer bytes=").append(output.bufferBytes()).append(" format=").append(output.format()).append('\n');
            text.append("Routing matches=").append(routing.bindingMatches()).append(" accepted=").append(routing.acceptedRequests())
                .append(" missing sounds=").append(routing.missingSounds()).append(" cooldown drops=").append(routing.droppedCooldowns()).append('\n');
            text.append("WAV ready=").append(library.entries().stream().filter(SoundLibrary.Entry::ready).count()).append('/').append(library.entries().size()).append('\n');
            text.append("Input devices: ").append(input.statusSnapshot()).append('\n');
            text.append("Input received EV_KEY/EV_REL records + aggregate wheel counters: ").append(dispatcher.diagnostics().snapshot()).append('\n');
            GnomeEventBridge bridge=gnome.get();text.append("GNOME: ").append(bridge==null?"disabled":bridge.status()).append('\n');
            text.append("Rendered/accepted counters do not prove hardware audibility. No key sequences or notification text are recorded.");
            status.setText(text.toString());
            var authorized=authorizedDevices(store.snapshot());
            if(!device.getItems().equals(authorized)){String selected=device.getValue();device.getItems().setAll(authorized);if(device.getItems().contains(selected))device.setValue(selected);}
        };
        Button update=new Button("Refresh / Обновить");update.setOnAction(e->refresh.run());
        Button start=new Button("Start / Начать"),finish=new Button("Finish / Завершить"),apply=new Button("Apply units / Применить units");
        apply.setDisable(true);
        final double[] proposed={Double.NaN};
        start.setOnAction(e->{
            if(device.getValue()==null){result.setText("Select an explicitly authorized device first / Выберите устройство");return;}
            dispatcher.beginCalibration(device.getValue(),detents.getValue());proposed[0]=Double.NaN;apply.setDisable(true);
            result.setText("Recording aggregate wheel deltas only. Rotate "+detents.getValue()+" physical detents in one direction, then Finish. / Прокрутите деления и завершите.");
        });
        finish.setOnAction(e->{
            var calibration=dispatcher.endCalibration();
            if(calibration==null){result.setText("No calibration running / Калибровка не начата");return;}
            var totals=calibration.axes().get(axis.getValue());
            proposed[0]=totals==null?Double.NaN:Math.abs((double)totals.rawHighResolution())/calibration.physicalDetents();
            apply.setDisable(!Double.isFinite(proposed[0])||proposed[0]<=0||proposed[0]>10000);
            result.setText("Device="+calibration.deviceId()+" physical="+calibration.physicalDetents()+" axis="+axis.getValue()+" observed="+totals+"; proposed HI_RES units/detent="+proposed[0]+". Legacy-only devices use one legacy step; nothing is applied automatically. Accepted impulses are router-accepted events, not proof of sound.");refresh.run();
        });
        apply.setOnAction(e->{
            double units=proposed[0];if(!Double.isFinite(units)||units<=0||units>10000)return;
            mutate.accept(config->{Map<String,Object> settings=copy(config.get("settings"));Map<String,Object> wheel=copy(settings.get("wheel"));wheel.put("units_per_detent",units);settings.put("wheel",wheel);config.put("settings",settings);});
            apply.setDisable(true);result.setText("Calibration submitted for atomic configuration save; repeat 1/5/10 tests to compare raw detents and accepted impulses. / Настройка отправлена на сохранение.");
        });
        device.valueProperty().addListener((o,a,b)->{apply.setDisable(true);proposed[0]=Double.NaN;});
        axis.valueProperty().addListener((o,a,b)->{apply.setDisable(true);proposed[0]=Double.NaN;});
        getChildren().addAll(heading,update,status,new Label("Wheel calibration / Калибровка колеса"),new HBox(8,device,detents,axis),new HBox(8,start,finish,apply),result);
        refresh.run();
    }
    private static java.util.List<String> authorizedDevices(Map<String,Object> config){
        Object raw=copy(copy(config.get("settings")).get("input")).get("device_allowlist");
        return raw instanceof java.util.List<?> list?list.stream().map(String::valueOf).toList():java.util.List.of();
    }
    private static Map<String,Object> copy(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?> map)map.forEach((k,v)->result.put(String.valueOf(k),v));return result;}
}
