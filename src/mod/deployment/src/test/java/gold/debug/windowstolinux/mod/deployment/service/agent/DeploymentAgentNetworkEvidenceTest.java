package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Inspects only retained nonsecret network metadata, without opening a platform runtime or remote session. */
class DeploymentAgentNetworkEvidenceTest extends DeploymentRuntimeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
    void inspectsOriginalNetworkMetadataWithoutServerDispatch() throws Exception {
        try {
            String[] states = requiredParameter("managed.live.originalStates");
            String[] tasks = requiredParameter("managed.live.originalTasks");
            assertEquals(states.length, tasks.length, "Original state/task pairs must match");
            Path dataRoot = workspace().resolve("src/app/db/data").toRealPath();
            for (int index = 0; index < states.length; index++) {
                Path state = Path.of(states[index]).toRealPath();
                assertEquals(dataRoot, state.getParent(), "Original state must be a direct workspace data child");
                assertTrue(state.getFileName().toString().matches("agent-live-[a-z0-9-]{1,160}"),
                    "Only original Agent live states are admitted");
                Path data = state.resolve("data").toRealPath();
                assertEquals(state, data.getParent(), "Original database must remain inside the admitted state");
                assertTrue(Files.isRegularFile(data.resolve("windowstolinux.mv.db"), LinkOption.NOFOLLOW_LINKS),
                    "Original database must already exist without a file link");
                inspect(data, UUID.fromString(tasks[index]));
            }
        } catch (Exception failure) {
            throw new AssertionError("Original nonsecret network metadata inspection failed");
        }
    }

    private static String[] requiredParameter(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "Original evidence parameters are required");
        String[] values = value.split(",", -1);
        assertTrue(values.length >= 1 && values.length <= 16, "Original evidence pairs exceed the inspection bound");
        for (String item : values) assertFalse(item.isBlank(), "Original evidence parameters cannot be empty");
        return values;
    }

    private static void inspect(Path data, UUID original) throws Exception {
        try (var database = PlatformDatabase.open(data, currentUserProtection())) {
            SchemaIdentity identity;
            try (var connection = database.openConnection();
                 var query = connection.prepareStatement("SELECT schema_version,generation FROM mod_schema WHERE mod_id=? AND state='READY'")) {
                query.setString(1, OWNER);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next(), "Original READY deployment owner schema must exist");
                    identity = new SchemaIdentity(OWNER, rows.getString(1), UUID.fromString(rows.getString(2)));
                    assertFalse(rows.next(), "Only one exact READY owner schema is admitted");
                }
            }
            try (var access = new SchemaAccessScope(database, identity, UUID.randomUUID(), true);
                 var connection = access.openConnection();
                 var query = connection.prepareStatement("SELECT execution_id,request_payload FROM deployment_execution WHERE platform_task_id=?")) {
                query.setObject(1, original);
                int executions = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        assertTrue(++executions <= 16, "Original executions exceed the inspection bound");
                        UUID execution = UUID.fromString(rows.getString(1));
                        byte[] request = rows.getBytes(2);
                        assertTrue(request != null && request.length <= DeploymentPublishTemplateCodec.LIMIT,
                            "Original request exceeds the inspection bound");
                        JsonNode stored = JSON.readTree(request).path("publicationTemplate");
                        assertTrue(stored.isObject(), "Original publication template must exist");
                        var template = DeploymentPublishTemplateCodec.decodeTemplate(stored.toString(), execution);
                        assertTrue(template.graph().components().size() <= 16, "Original Agent components exceed the inspection bound");
                        for (var part : template.graph().components()) {
                            var output = JSON.createObjectNode().put("task", original.toString())
                                .put("execution", execution.toString()).put("component", part.componentId());
                            var endpoints = output.putArray("endpoints");
                            for (var endpoint : part.reviewedRuntime().workload().endpoints())
                                endpoints.addObject().put("address", endpoint.bindAddress())
                                    .put("hostPort", endpoint.hostPort()).put("targetPort", endpoint.targetPort())
                                    .put("exposure", endpoint.exposure().name());
                            var unit = template.units().stream().filter(candidate -> candidate.unit().equals(part.unit()))
                                .findFirst().orElseThrow();
                            output.set("configuration", networkConfiguration(unit.configuration().entries()));
                            String line = output.toString();
                            assertTrue(line.length() <= 8192, "Network metadata output exceeds the inspection bound");
                            System.out.println("AGENT_LIVE_ORIGINAL_NETWORK " + line);
                        }
                    }
                }
                assertTrue(executions > 0, "Original task must have a retained deployment execution");
            }
        }
    }

    private static ArrayNode networkConfiguration(List<DeploymentConfigEntry> entries) {
        var output = JSON.createArrayNode();
        for (var entry : entries) {
            if (!List.of("HOST", "PORT", "API_URL").contains(entry.key())) continue;
            var item = output.addObject().put("key", entry.key()).put("scope", entry.scope().name());
            if (entry.key().equals("PORT") && entry.value() instanceof DeploymentConfigValue.Number number
                && number.value() >= 1 && number.value() <= 65535)
                item.put("value", number.value());
            else if (entry.key().equals("HOST") && entry.value() instanceof DeploymentConfigValue.Text text
                && text.value().matches("[A-Za-z0-9.\\[\\]:-]{1,253}"))
                item.put("value", text.value());
            else if (entry.key().equals("API_URL") && entry.value() instanceof DeploymentConfigValue.Text text)
                item.set("value", apiAddress(text.value()));
            else item.put("status", "INVALID");
        }
        return output;
    }

    private static ObjectNode apiAddress(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (!List.of("http", "https").contains(scheme) || uri.getHost() == null || port == 0 || port > 65535)
                return JSON.createObjectNode().put("status", "INVALID");
            return JSON.createObjectNode().put("scheme", scheme).put("host", uri.getHost())
                .put("port", port < 0 ? scheme.equals("https") ? 443 : 80 : port);
        } catch (IllegalArgumentException invalid) {
            return JSON.createObjectNode().put("status", "INVALID");
        }
    }

    private static DatabaseProtection currentUserProtection() {
        var protection = new CurrentUserProtection();
        return new DatabaseProtection() {
            public byte[] protect(byte[] input) {
                return protection.protect(input);
            }

            public byte[] unprotect(byte[] input) {
                return protection.unprotect(input);
            }
        };
    }

    @Test
    void projectsOnlyNetworkKeysAndRemovesApiCredentialsPathAndQuery() {
        var result = networkConfiguration(List.of(
            new DeploymentConfigEntry("HOST", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text("0.0.0.0")),
            new DeploymentConfigEntry("PORT", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Number(46110)),
            new DeploymentConfigEntry("API_URL", DeploymentConfigScopeType.RUNTIME,
                new DeploymentConfigValue.Text("http://private-user:private-password@127.0.0.1:46111/private-path?token=private-token#private-fragment")),
            new DeploymentConfigEntry("PRIVATE_DATA", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text("private-value"))));
        assertEquals(3, result.size());
        assertEquals("0.0.0.0", result.get(0).path("value").asText());
        assertEquals(46110, result.get(1).path("value").asInt());
        assertEquals(JSON.createObjectNode().put("scheme", "http").put("host", "127.0.0.1").put("port", 46111),
            result.get(2).path("value"));
        assertFalse(result.toString().contains("private"));
        assertFalse(result.toString().contains("PRIVATE_DATA"));
    }

    @Test
    void invalidNetworkTextIsNeverEchoed() {
        var result = networkConfiguration(List.of(
            new DeploymentConfigEntry("HOST", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text("private@host")),
            new DeploymentConfigEntry("API_URL", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text("private invalid URL"))));
        assertEquals("INVALID", result.get(0).path("status").asText());
        assertEquals("INVALID", result.get(1).path("value").path("status").asText());
        assertFalse(result.toString().contains("private"));
        assertEquals(443, apiAddress("https://localhost/private?secret=private").path("port").asInt());
    }
}
