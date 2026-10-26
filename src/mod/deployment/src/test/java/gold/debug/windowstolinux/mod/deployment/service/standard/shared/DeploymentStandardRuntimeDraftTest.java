package gold.debug.windowstolinux.mod.deployment.service.standard.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewFieldException;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps explicit runtime draft values ahead of suggestions while preserving mandatory review and resource scope. / 保持明确运行草稿优先于建议，同时保留必需审阅及资源范围。 */
class DeploymentStandardRuntimeDraftTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitProxyUrlKeepsItsPortWhenTheServicePortChanges(boolean declared) {
        var values = new LinkedHashMap<String, String>();
        values.put("port", "8080");
        values.put("accessUrl", "http://source.example.test:8080/");
        values.put("healthMode", "HTTP");
        values.put("healthEndpoint", "http://127.0.0.1:8080/health");
        values.put("exposure", "EXTERNAL");
        if (declared) {
            values.put("application.endpoints", "public");
            values.put("application.endpoint.public.protocol", "HTTP");
            values.put("application.endpoint.public.port", "8080");
            values.put("application.endpoint.public.exposure", "EXTERNAL");
            values.put("application.endpoint.public.url", values.get("accessUrl"));
        }
        var prompt = new DeploymentStandardInputs.Prompt("web", "app", DeploymentProjectKind.STATIC_SITE, values, List.of());
        String proxy = "http://proxy.example.test:8080/site/";
        var actual = DeploymentStandardRuntimeDraft.apply(prompt, Map.of("port", "46100", "accessUrl", proxy));
        assertEquals(proxy, actual.defaults().get("accessUrl"));
        assertEquals("46100", actual.defaults().get("port"));
        assertEquals("http://127.0.0.1:46100/health", actual.defaults().get("healthEndpoint"));
        var endpoints = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.ApplicationSpec
            .resolve(actual.defaults()).endpoints();
        assertEquals(1, endpoints.size());
        assertEquals(46100, endpoints.getFirst().hostPort());
        assertEquals(proxy, endpoints.getFirst().accessUrl());
        assertEquals("8080", prompt.defaults().get("port"));
    }

    @Test
    void explicitMultilineAndPreviouslyAbsentRuntimeValuesBecomeActualEditableReviewFields() {
        var resolver = new DeploymentStandardRuntimeResolver();
        var defaults = Map.of("primary", "source-default", "version", "1.24", "port", "8080", "configuration", "SOURCE=value");
        var prompt = new DeploymentStandardInputs.Prompt("api", "app", DeploymentProjectKind.GO_SERVICE, defaults,
            resolver.corrections("api", defaults));
        var declaration = "version=1\nhealth.mode=TCP\n";
        var draft = Map.of("primary", "reviewed-bin", "configuration", "RUNTIME:PORT=9090\nRUNTIME:MODE=fixture",
            "applicationDeclaration", declaration, "arguments", "--safe input", "jvmArguments", "-Xmx256m");
        var actual = DeploymentStandardRuntimeDraft.apply(prompt, draft);
        assertEquals("reviewed-bin", actual.defaults().get("primary"));
        assertEquals("--safe input", actual.defaults().get("arguments"));
        assertEquals("-Xmx256m", actual.defaults().get("jvmArguments"));
        assertEquals("RUNTIME:SOURCE=value\nRUNTIME:PORT=9090\nRUNTIME:MODE=fixture", actual.defaults().get("configuration"));
        assertEquals("TCP", actual.defaults().get("healthMode"));
        assertFalse(actual.defaults().containsKey("applicationDeclaration"));
        assertEquals("source-default", prompt.defaults().get("primary"));
        for (String key : draft.keySet()) {
            if (key.equals("applicationDeclaration")) continue;
            var field = actual.fields().stream().filter(item -> item.id().equals("api/" + key)).findFirst().orElseThrow();
            assertEquals(actual.defaults().get(key), field.value());
            assertFalse(ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH)
                .getString(field.helpKey()).isBlank());
        }
        assertFalse(actual.fields().isEmpty(), "Explicit prefill is editable input, not consent");
    }

    @Test
    void draftKeepsPublicSecretReferencesWithoutResourceAuthorizationAndInvalidNumberIdentifiesOnlyOriginalField() {
        var references = new DeploymentStandardInputs.SourceOptions(Optional.empty(), Optional.empty(), Map.of("secrets", "protected:1"));
        assertEquals(Map.of("secrets", "protected:1"), references.runtimeDraft());
        assertThrows(IllegalArgumentException.class, () -> new DeploymentStandardInputs.SourceOptions(
            Optional.empty(), Optional.empty(), Map.of("rememberAuthorization", "true")));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentStandardInputs.SourceOptions(
            Optional.empty(), Optional.empty(), Map.of("databaseDetails", "foreign-binding")));
        var resolver = new DeploymentStandardRuntimeResolver();
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
