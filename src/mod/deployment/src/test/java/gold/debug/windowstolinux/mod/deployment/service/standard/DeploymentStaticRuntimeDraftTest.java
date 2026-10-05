package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
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
        Problem problem = invalid.problem();
        assertSame(problem, invalid.problem());
        assertEquals(ProblemCode.INVALID_INPUT, problem.code());
        assertEquals("DEPLOYMENT_REVIEW_FIELD_INVALID", problem.eventCode());
        assertEquals("Original deployment review field requires correction", problem.message());
        assertEquals(Map.of("field", "port"), problem.context());
        assertTrue(problem.diagnosticId().isEmpty());
        assertFalse(problem.toString().contains("private-entered-text"));
        assertInstanceOf(NumberFormatException.class, invalid.getCause());
    }

    @Test
    void structuredFieldRejectionPreservesTheExactBoundedIdentityAndOriginalCause() throws Exception {
        assertEquals(Problem.class, DeploymentReviewFieldException.class.getMethod("problem").getReturnType());
        var cause = new IllegalArgumentException("private submitted input");
        for (String field : List.of("a", "Z" + "9".repeat(127))) {
            var invalid = new DeploymentReviewFieldException(field, cause);
            assertEquals(field, invalid.field());
            assertEquals(Map.of("field", field), invalid.problem().context());
            assertSame(cause, invalid.getCause());
            assertEquals("Original deployment review field requires correction", invalid.getMessage());
            assertFalse(invalid.problem().toString().contains("private submitted input"));
        }
        assertThrows(IllegalArgumentException.class, () -> new DeploymentReviewFieldException(null, cause));
        for (String field : List.of("", "0port", "port/name", "port_name", "a".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> new DeploymentReviewFieldException(field, cause));
    }
}
