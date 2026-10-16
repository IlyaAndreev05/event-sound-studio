package io.github.ilyaandreev05.eventsoundstudio.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {
    @Test void defaultConfigurationIsCompleteAndValid(){assertEquals(List.of(),ConfigValidator.validate(DefaultConfig.create()));}
    @Test void protectedGroupMembersCannotBeRemoved(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> groups=(List<Map<String,Object>>)config.get("groups");
        groups.stream().filter(g->"builtin.letters".equals(g.get("id"))).findFirst().orElseThrow().put("members",List.of("KEY_A"));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(e->e.contains("member list is protected")));
    }
    @Test void outOfRangeMasterVolumeIsRejected(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");settings.put("master_volume",1.1);
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(e->e.contains("master_volume")));
    }
    @Test void soundMetadataHasTypedTagsAndTextFields(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> sounds=(List<Map<String,Object>>)config.get("sounds");
        sounds.getFirst().put("tags",List.of(42));sounds.getFirst().put("description",List.of("wrong type"));
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e->e.contains("sounds[0].tags")));
        assertTrue(errors.stream().anyMatch(e->e.contains("sounds[0].description")));
    }
    @Test void perBindingAudioValuesAreBounded(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> bindings=(List<Map<String,Object>>)config.get("bindings");
        bindings.getFirst().put("pan",1.5);bindings.getFirst().put("delay_ms",-1);bindings.getFirst().put("repeat",21);bindings.getFirst().put("duration_ms",30_001);bindings.getFirst().put("attack_ms",-1);bindings.getFirst().put("remove_dc","yes");
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e->e.contains(".pan")));assertTrue(errors.stream().anyMatch(e->e.contains(".delay_ms")));assertTrue(errors.stream().anyMatch(e->e.contains(".repeat")));assertTrue(errors.stream().anyMatch(e->e.contains(".duration_ms")));assertTrue(errors.stream().anyMatch(e->e.contains(".attack_ms")));assertTrue(errors.stream().anyMatch(e->e.contains(".remove_dc")));
    }
    @Test void bindingNamesPriorityAndCooldownAreBounded(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> bindings=(List<Map<String,Object>>)config.get("bindings");
        bindings.getFirst().put("name"," ");bindings.getFirst().put("priority",1001);bindings.getFirst().put("cooldown_ms",60_001);
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e->e.contains(".name")));assertTrue(errors.stream().anyMatch(e->e.contains(".priority")));assertTrue(errors.stream().anyMatch(e->e.contains(".cooldown_ms")));
    }
    @Test void sourceProfileOverridesMustNameKnownSourcesAndProfiles(){
        Map<String,Object> config=DefaultConfig.create();config.put("source_profiles",DefaultConfig.map("keyboard","missing-profile","unknown","desk"));
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e->e.contains("source_profiles.keyboard")));assertTrue(errors.stream().anyMatch(e->e.contains("unsupported source unknown")));
    }
    @Test void perBindingEqFiltersAreTypedAndBounded(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> bindings=(List<Map<String,Object>>)config.get("bindings");
        bindings.getFirst().put("eq",DefaultConfig.map("enabled",true,"filters",List.of(DefaultConfig.map("type","unknown","frequency_hz",1000,"gain_db",18,"q",0.01))));
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e->e.contains(".type")));assertTrue(errors.stream().anyMatch(e->e.contains(".gain_db")));assertTrue(errors.stream().anyMatch(e->e.contains(".q")));
    }
    @Test void mouseAndWheelPoliciesRejectInvalidRanges(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");
        @SuppressWarnings("unchecked") Map<String,Object> click=(Map<String,Object>)settings.get("mouse_click_policy");click.put("mode","unsupported");click.put("double_click_window_ms",2000);
        @SuppressWarnings("unchecked") Map<String,Object> wheel=(Map<String,Object>)settings.get("wheel");wheel.put("units_per_detent",Double.POSITIVE_INFINITY);
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e->e.contains("mouse_click_policy.mode")));assertTrue(errors.stream().anyMatch(e->e.contains("double_click_window_ms")));assertTrue(errors.stream().anyMatch(e->e.contains("units_per_detent")));
    }
    @Test void altTabFocusDeduplicationIsOptionalForOlderConfigsAndMustBeBoolean(){
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");
        @SuppressWarnings("unchecked") Map<String,Object> input=(Map<String,Object>)settings.get("input");
        assertFalse(Boolean.TRUE.equals(input.get("deduplicate_alt_tab_focus")));
        input.remove("deduplicate_alt_tab_focus");
        assertEquals(List.of(),ConfigValidator.validate(config));
        input.put("deduplicate_alt_tab_focus","yes");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(e->e.contains("deduplicate_alt_tab_focus")));
    }
}
