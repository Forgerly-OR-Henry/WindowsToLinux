package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentPlanNetworkConstraintsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void processCannotAdvertisePortForwardingEvenWithoutPublicUrl() throws Exception {
        var plan = plan("PROCESS", 46100, 18100, "EXTERNAL");
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanNetworkConstraints.require(plan, Map.of()));
        assertTrue(failure.getMessage().contains("PROCESS creates no port forwarding"));
        assertEquals(18100, plan.components().getFirst().runtime().workload().endpoints().getFirst().targetPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCKER", "PODMAN"})
    void containerKeepsActualPublishedMapping(String backend) throws Exception {
        var plan = plan(backend, 46100, 18100, "EXTERNAL");
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(plan, Map.of("publicUrl", "http://site.example:46100")));
        assertEquals(18100, plan.components().getFirst().runtime().workload().endpoints().getFirst().targetPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-port", "internal-only", "no-endpoint"})
    void publicUrlNeedsMatchingExternalEndpoint(String scenario) throws Exception {
        var declaration = declaration("PROCESS", scenario.equals("wrong-port") ? 18100 : 46100,
            scenario.equals("wrong-port") ? 18100 : 46100, scenario.equals("internal-only") ? "INTERNAL" : "EXTERNAL");
        if (scenario.equals("no-endpoint")) ((ObjectNode) declaration.path("components").get(0)).putArray("ports");
        var plan = DeploymentAgentPlanProtocol.parse(declaration);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentPlanNetworkConstraints.require(plan, Map.of("publicUrl", "http://site.example:46100")));
        assertTrue(failure.getMessage().contains("EXTERNAL endpoint on host port 46100"));
    }

    @Test
    void matchingWebsiteDoesNotRequireInternalWorkerExposure() throws Exception {
        var declaration = declaration("PROCESS", 46100, 46100, "EXTERNAL");
        var worker = ((ObjectNode) declaration.path("components").get(0)).deepCopy().put("id", "worker");
        worker.putArray("ports").addObject().put("id", "private").put("protocol", "TCP").put("host", 18101).put("target", 18101)
            .put("exposure", "INTERNAL").put("accessUrl", "");
        ((com.fasterxml.jackson.databind.node.ArrayNode) declaration.path("components")).add(worker);
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(DeploymentAgentPlanProtocol.parse(declaration),
            Map.of("publicUrl", "http://site.example:46100", "network", "Do not infer extra ports from this text")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://site.example", "https://site.example", "HTTP://site.example/path", "https://[2001:db8::1]/"})
    void publicUrlUsesSchemeDefaultPort(String url) throws Exception {
        int port = url.toLowerCase(java.util.Locale.ROOT).startsWith("https:") ? 443 : 80;
        var declaration = declaration("PROCESS", port, port, "EXTERNAL");
        ((ObjectNode) declaration.path("components").get(0).path("ports").get(0))
            .put("protocol", url.toLowerCase(java.util.Locale.ROOT).startsWith("https:") ? "HTTPS" : "HTTP").put("accessUrl", url);
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(DeploymentAgentPlanProtocol.parse(declaration), Map.of("publicUrl", url)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "/relative", "ftp://site.example:46100", "http:///missing-host", "http://site.example:0",
        "http://site.example:65536", "http://site.example:-1", "http://site.example:bad", "http://private-value@site.example:46100",
        "http://site.example:46100/#fragment", "http://127.0.0.1:46100"})
    void invalidPublicUrlIsRejectedWithoutEchoingInput(String url) throws Exception {
        var plan = plan("PROCESS", 46100, 46100, "EXTERNAL");
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanNetworkConstraints.require(plan, Map.of("publicUrl", url)));
        assertTrue(failure.getMessage().contains("valid HTTP/HTTPS URL"));
        assertFalse(failure.getMessage().contains("private-value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TCP", "UDP", "missing-url", "different-host", "different-path", "different-query", "different-protocol"})
    void anOpenPublicPortCannotReplaceTheRequestedHttpIdentity(String scenario) throws Exception {
        var declaration = declaration("PROCESS", 46200, 46200, "EXTERNAL");
        var endpoint = (ObjectNode) declaration.path("components").get(0).path("ports").get(0);
        switch (scenario) {
            case "TCP", "UDP" -> endpoint.put("protocol", scenario).put("accessUrl", "");
            case "missing-url" -> endpoint.put("accessUrl", "");
            case "different-host" -> endpoint.put("accessUrl", "http://other.example:46200");
            case "different-path" -> endpoint.put("accessUrl", "http://site.example:46200/different");
            case "different-query" -> endpoint.put("accessUrl", "http://site.example:46200?view=other");
            case "different-protocol" ->
                endpoint.put("protocol", "HTTPS").put("accessUrl", "https://site.example:46200");
            default -> throw new AssertionError(scenario);
        }
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanNetworkConstraints.require(
            DeploymentAgentPlanProtocol.parse(declaration), Map.of("publicUrl", "http://site.example:46200")));
        assertTrue(failure.getMessage().contains("protocol HTTP"));
        assertTrue(failure.getMessage().contains("accessUrl equal to publicUrl"));
        assertFalse(failure.getMessage().contains("other.example"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TCP", "UDP"})
    void aNonWebsiteWithoutPublicUrlKeepsItsExplicitSocketProtocol(String protocol) throws Exception {
        var declaration = declaration("PROCESS", 18100, 18100, "EXTERNAL");
        ((ObjectNode) declaration.path("components").get(0).path("ports").get(0)).put("protocol", protocol).put("accessUrl", "");
        var plan = DeploymentAgentPlanProtocol.parse(declaration);
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(plan, Map.of("goal", "Run a source-backed non-browser service")));
        assertEquals(protocol, plan.components().getFirst().runtime().workload().endpoints().getFirst().protocol().name());
        assertEquals("", plan.components().getFirst().runtime().workload().endpoints().getFirst().accessUrl());
    }

    @Test
    void cliWithoutPublicUrlKeepsNoPersistentEndpoints() throws Exception {
        var declaration = declaration("PROCESS", 46100, 46100, "EXTERNAL");
        var component = (ObjectNode) declaration.path("components").get(0);
        component.put("mode", "ON_DEMAND").putArray("ports");
        var health = JSON.readTree("""
            {"type":"COMMAND","entrypoint":"verify.sh","arguments":[],"expected":"ok","timeout":30}
            """);
        component.set("health", health);
        ((ObjectNode) declaration.path("applicationHealth")).set("health", health);
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(DeploymentAgentPlanProtocol.parse(declaration), Map.of("goal", "Install CLI")));
    }

    private static DeploymentAgentPlan plan(String backend, int host, int target, String exposure) throws Exception {
        return DeploymentAgentPlanProtocol.parse(declaration(backend, host, target, exposure));
    }

    private static ObjectNode declaration(String backend, int host, int target, String exposure) throws Exception {
        var declaration = (ObjectNode) JSON.readTree("""
            {"applicationId":"demo","components":[{"id":"web","sourceRoot":".","dependencies":[],"evidence":["o1"],
             "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
             "workspace":{"mode":"AUTO","minimumBuildBytes":0,"preferredBuildBytes":134217728,"explicitBytes":0},
             "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
             "databases":[],"secretReferences":[]}],"applicationHealth":{"component":"web","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
        var component = (ObjectNode) declaration.path("components").get(0);
        component.put("backend", backend).putArray("ports").addObject().put("id", "web").put("protocol", "HTTP")
            .put("host", host).put("target", target).put("exposure", exposure).put("accessUrl", "http://site.example:" + host);
        return declaration;
    }
}
