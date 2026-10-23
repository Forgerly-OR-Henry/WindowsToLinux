package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real strict plan parser's original nonsecret and immutable-binding contracts. / 验证真实严格计划解析器的原非秘密及不可变绑定合同。 */
class DeploymentAgentPlanProtocolTest {
    @Test
    void acceptedDeclarationRoundTripsEveryNativeFieldWithoutAssessmentOrJavaRecordShapes() throws Exception {
        var json = new ObjectMapper();
        var health = java.util.List.of(
            "{\"type\":\"PROCESS\",\"timeout\":30,\"stability\":3}",
            "{\"type\":\"TCP\",\"port\":8080,\"timeout\":30,\"stability\":3}",
            "{\"type\":\"HTTP\",\"url\":\"http://localhost:8080/health\",\"status\":204,\"timeout\":30}",
            "{\"type\":\"COMMAND\",\"entrypoint\":\"verify.sh\",\"arguments\":[\"--once\"],\"expected\":\"ok\",\"timeout\":30}");
        for (int index = 0; index < health.size(); index++) {
            var input = plan();
            input.put("explanation", "Retain only the model's source-backed declaration");
            var component = (ObjectNode) input.path("components").get(0);
            component.put("backend", java.util.List.of("PROCESS", "DOCKER", "PODMAN", "PROCESS").get(index));
            component.set("health", json.readTree(health.get(index)));
            ((ObjectNode) input.path("applicationHealth")).set("health", json.readTree(health.get(index)));
            component.putArray("arguments").add("--serve").add("quoted value");
            component.put("workingDirectory", "work");
            if (index == 3) component.put("mode", "ON_DEMAND");
            else {
                var ports = component.putArray("ports");
                ports.addObject().put("id", "api").put("protocol", "TCP").put("host", 8080).put("target", 8081).put("exposure", "EXTERNAL").put("accessUrl", "");
                ports.addObject().put("id", "admin").put("protocol", "TCP").put("host", 9090).put("target", 9091).put("exposure", "INTERNAL").put("accessUrl", "");
            }
            if (index == 0) component.putNull("databases");
            else if (index == 1) component.putArray("databases").add("catalog");
            var resources = component.putArray("resources");
            resources.addObject().put("id", "cache").put("path", "data/cache").put("access", "READ_WRITE")
                .put("schema", "cache-v1").put("reversible", false).put("type", "FILE").put("seedFile", "").put("digest", "");
            resources.addObject().put("id", "settings").put("path", "config/settings.json").put("access", "READ_ONLY")
                .put("schema", "settings-v1").put("reversible", true).put("type", "CONFIGURATION")
                .put("seedFile", "settings.json").put("digest", "a".repeat(64));
            var configuration = component.putArray("configuration");
            configuration.addObject().put("key", "LABEL").put("value", "declared");
            configuration.addObject().put("key", "COUNT").put("value", Long.MAX_VALUE);
            configuration.addObject().put("key", "ENABLED").put("value", true);
            component.putArray("secretReferences").addObject().put("identifier", "app-password").put("revision", 2);
            var declaration = new DeploymentAgentProposal.Plan(DeploymentAgentPlanProtocol.parse(input), input.path("explanation").asText());
            var encoded = DeploymentAgentPlanProtocol.encode(declaration);
            assertEquals(input, json.readTree(encoded.toString()), "The original native shape includes original READ IDs, null/empty databases and explicit reference metadata");
            assertEquals(declaration.plan(), DeploymentAgentPlanProtocol.parse(encoded));
            assertFalse(encoded.has("assessment"));
            encoded.putObject("assessment").putArray("basis").add("o1");
            ((ObjectNode) encoded.path("assessment")).put("suitability", "Current source evidence").put("differences", "No prior judgment reused")
                .put("resolution", "RESOLVED").put("decision", "New model assessment");
            var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class, DeploymentAgentProtocol.parse("plan", encoded));
            assertEquals(declaration, assertInstanceOf(DeploymentAgentProposal.Plan.class, assessed.action()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTPS", "TCP", "UDP"})
    void explicitEndpointFactsSurviveNativeSchemaParsingAndEncoding(String protocol) throws Exception {
        var input = plan();
        input.put("explanation", "Source-backed endpoint identity");
        input.putObject("assessment").putArray("basis").add("o1");
        ((ObjectNode) input.path("assessment")).put("suitability", "Observed source").put("differences", "Current declaration")
            .put("resolution", "RESOLVED").put("decision", "Keep the declared protocol");
        String address = protocol.startsWith("HTTP") ? protocol.toLowerCase(java.util.Locale.ROOT) + "://site.example:8080/ui?view=main" : "";
        ((ObjectNode) input.path("components").get(0)).putArray("ports").addObject()
            .put("id", "web").put("protocol", protocol).put("host", 8080).put("target", 8080)
            .put("exposure", "EXTERNAL").put("accessUrl", address);
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class, DeploymentAgentProtocol.parse("plan", input));
        var declaration = assertInstanceOf(DeploymentAgentProposal.Plan.class, assessed.action());
        var endpoint = declaration.plan().components().getFirst().runtime().workload().endpoints().getFirst();
        assertEquals(protocol, endpoint.protocol().name());
        assertEquals(address, endpoint.accessUrl());
        var encoded = DeploymentAgentPlanProtocol.encode(declaration);
        assertEquals(input.path("components").get(0).path("ports"), encoded.path("components").get(0).path("ports"));
        assertEquals(declaration.plan(), DeploymentAgentPlanProtocol.parse(encoded));
    }

    @ParameterizedTest
    @ValueSource(strings = {"protocol", "accessUrl", "legacy-four-fields"})
    void missingEndpointIdentityIsRejectedInsteadOfInferringHttp(String missing) throws Exception {
        var input = plan();
        var endpoint = ((ObjectNode) input.path("components").get(0)).putArray("ports").addObject()
            .put("id", "web").put("protocol", "HTTP").put("host", 8080).put("target", 8080)
            .put("exposure", "EXTERNAL").put("accessUrl", "http://site.example:8080");
        if (missing.equals("legacy-four-fields")) endpoint.remove(java.util.List.of("protocol", "accessUrl"));
        else endpoint.remove(missing);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanProtocol.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"different-scheme", "different-port", "userinfo", "fragment", "loopback", "tcp-with-url"})
    void endpointUrlsRequireExplicitMatchingLegalHttpIdentity(String scenario) throws Exception {
        var input = plan();
        String address = switch (scenario) {
            case "different-scheme" -> "https://site.example:8080";
            case "different-port" -> "http://site.example:8081";
            case "userinfo" -> "http://private-value@site.example:8080";
            case "fragment" -> "http://site.example:8080/#section";
            case "loopback" -> "http://127.0.0.1:8080";
            default -> "http://site.example:8080";
        };
        ((ObjectNode) input.path("components").get(0)).putArray("ports").addObject()
            .put("id", "web").put("protocol", scenario.equals("tcp-with-url") ? "TCP" : "HTTP")
            .put("host", 8080).put("target", 8080).put("exposure", "EXTERNAL").put("accessUrl", address);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanProtocol.parse(input));
    }

    @Test
    void strictNativeFieldsLeaveOriginalTypedTcpFrozenStorageReadable() throws Exception {
        var input = plan();
        var port = ((ObjectNode) input.path("components").get(0)).putArray("ports").addObject()
            .put("id", "service").put("protocol", "TCP").put("host", 8080).put("target", 8080)
            .put("exposure", "EXTERNAL").put("accessUrl", "");
        var runtime = DeploymentAgentPlanProtocol.parse(input).components().getFirst().runtime();
        var frozen = new gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitRuntimeConfig(
            runtime.healthCheck(), java.util.Optional.empty(), runtime.identityPolicy(), runtime.workload());
        var codec = new gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentUnitRuntimeCodec();
        byte[] stored = codec.write(frozen);
        port.remove(java.util.List.of("protocol", "accessUrl"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanProtocol.parse(input));
        var original = codec.read(stored);
        assertEquals(frozen, original);
        assertEquals("TCP", original.workload().endpoints().getFirst().protocol().name());
        assertEquals("", original.workload().endpoints().getFirst().accessUrl());
        assertArrayEquals(stored, codec.write(original), "Existing typed storage is independent of the native tool grammar");
    }

    @ParameterizedTest(name = "reject {0}")
    @ValueSource(strings = {"PASSWORD", "PASSWD", "SECRET", "TOKEN", "APIKEY", "API_KEY", "PRIVATEKEY", "PRIVATE_KEY"})
    void refusesCredentialLikeLiteralConfigurationBeforeCreatingTypedPlan(String key) throws Exception {
        var plan = plan();
        ((ObjectNode) plan.path("components").get(0)).putArray("configuration").addObject().put("key", key).put("value", "must-not-be-stored");
        var rejected = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanProtocol.parse(plan));
        assertTrue(rejected.getMessage().contains("separate user secret workflow"));
    }

    @Test
    void keepsExplicitImmutableConfigurationAndTypedNonsecretRuntimeValues() throws Exception {
        var plan = plan();
        var component = (ObjectNode) plan.path("components").get(0);
        component.putArray("resources").addObject().put("id", "settings").put("path", "/etc/example/settings.json")
            .put("access", "READ_ONLY").put("schema", "settings-v1").put("reversible", true)
            .put("type", "CONFIGURATION").put("seedFile", "settings.json").put("digest", "a".repeat(64));
        component.putArray("configuration").addObject().put("key", "PORT").put("value", 8080);
        var result = DeploymentAgentPlanProtocol.parse(plan).components().getFirst();
        var binding = result.resources().fileBindings().getFirst();
        assertEquals(DeploymentStorageLocation.StorageResourceType.CONFIGURATION, binding.resourceType());
        assertEquals("settings.json", binding.seedFile());
        assertEquals("a".repeat(64), binding.contentSha256());
        assertEquals(new DeploymentConfigValue.Number(8080), result.configuration().getFirst().value());
        assertTrue(result.resources().databaseBindings().orElseThrow().isEmpty());
    }

    private static ObjectNode plan() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"applicationId":"app","components":[{"id":"main","dependencies":[],"sourceRoot":".","databases":[],"secretReferences":[],"evidence":["o1"],
            "workspace":{"mode":"AUTO","minimumBuildBytes":0,"preferredBuildBytes":134217728,"explicitBytes":0},
            "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
            "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[]}],
            "applicationHealth":{"component":"main","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
    }
}
