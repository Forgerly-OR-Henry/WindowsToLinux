package gold.debug.windowstolinux.shared.standard.deploy.input;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;

class AutomaticRuntimeResolverTest {
    private final AutomaticRuntimeResolver resolver = new AutomaticRuntimeResolver();

    @Test
    void invalidAdvancedFieldsRemainAvailableForCorrection() {
        var values = Map.of("port", "8080", "timeout", "bad", "stability", "bad", "expectedStatus", "bad",
                "healthEndpoint", "bad", "jvmArguments", "bad", "arguments", "bad", "volumes", "bad", "accessUrl",
                "bad");
        assertEquals(
                java.util.stream.Stream
                        .concat(values.keySet().stream(), java.util.stream.Stream.of("applicationDeclaration"))
                        .map(key -> "app/" + key).collect(Collectors.toSet()),
                resolver.corrections("app", values).stream().map(field -> field.id()).collect(Collectors.toSet()));
    }

    @Test
    void incompatibleHealthChoicesAreValidationFailuresAndCannotSilentlyLoseAnAccessUrl() {
        assertThrows(IllegalArgumentException.class, () -> resolver.runtime(DeploymentProjectType.STATIC_SITE,
                Map.of("port", "8080", "healthMode", "TCP", "primary", "public")));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.health(Map.of("port", "8080", "healthMode", "invalid")));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.access(Map.of("healthMode", "TCP", "accessUrl", "https://example.test"), "192.0.2.1"));
    }

    @Test
    void selectingAManualDatabaseWithoutDetailsCanBeCompletedInTheCorrectionForm() {
        var fields = resolver.corrections("app", Map.of("databaseMode", "POSTGRESQL"));
        assertTrue(
                fields.stream().anyMatch(field -> field.id().equals("app/databaseDetails") && field.value().isEmpty()));
    }
}
