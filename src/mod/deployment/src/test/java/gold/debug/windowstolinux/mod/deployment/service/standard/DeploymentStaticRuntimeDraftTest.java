package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewFieldException;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import org.junit.jupiter.api.Test;

/** Keeps explicit runtime draft values ahead of suggestions while preserving mandatory review and resource scope. / 保持明确运行草稿优先于建议，同时保留必需审阅及资源范围。 */
class DeploymentStaticRuntimeDraftTest {
    @Test
    void explicitMultilineAndPreviouslyAbsentRuntimeValuesBecomeActualEditableReviewFields() {
        var resolver = new DeploymentStaticRuntimeResolver();
        var defaults = Map.of("primary", "source-default", "version", "1.24", "port", "8080", "configuration", "SOURCE=value");
        var prompt = new DeploymentStaticWorkflow.Prompt("api", "app", DeploymentProjectKind.GO_SERVICE, defaults,
            resolver.corrections("api", defaults));
        var declaration = "version=1\nhealth.mode=TCP\n";
        var draft = Map.of("primary", "reviewed-bin", "configuration", "RUNTIME:PORT=9090\nRUNTIME:MODE=fixture",
            "applicationDeclaration", declaration, "arguments", "--safe input", "jvmArguments", "-Xmx256m");
        var actual = DeploymentStaticRuntimeDraft.apply(prompt, draft);
        draft.forEach((key, value) -> assertEquals(value, actual.defaults().get(key)));
        assertEquals("source-default", prompt.defaults().get("primary"));
        for (String key : draft.keySet()) {
            var field = actual.fields().stream().filter(item -> item.id().equals("api/" + key)).findFirst().orElseThrow();
            assertEquals(draft.get(key), field.value());
            assertFalse(ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH)
                .getString(field.helpKey()).isBlank());
        }
        assertFalse(actual.fields().isEmpty(), "Explicit prefill is editable input, not consent");
    }

    @Test
    void draftCannotAddResourceAuthorizationAndInvalidNumberIdentifiesOnlyOriginalField() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentStaticWorkflow.SourceOptions(
            Optional.empty(), Optional.empty(), Map.of("secrets", "protected:1")));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentStaticWorkflow.SourceOptions(
            Optional.empty(), Optional.empty(), Map.of("databaseDetails", "foreign-binding")));
        var resolver = new DeploymentStaticRuntimeResolver();
        var invalid = assertThrows(DeploymentReviewFieldException.class, () -> resolver.health(Map.of(
            "healthMode", "TCP", "port", "private-entered-text", "timeout", "30")));
        assertEquals("port", invalid.field());
        assertFalse(invalid.getMessage().contains("private-entered-text"));
    }
}
