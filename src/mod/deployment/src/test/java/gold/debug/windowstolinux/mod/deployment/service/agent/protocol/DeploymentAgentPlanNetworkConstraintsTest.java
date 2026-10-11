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
        worker.putArray("ports").addObject().put("id", "private").put("host", 18101).put("target", 18101).put("exposure", "INTERNAL");
        ((com.fasterxml.jackson.databind.node.ArrayNode) declaration.path("components")).add(worker);
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(DeploymentAgentPlanProtocol.parse(declaration),
            Map.of("publicUrl", "http://site.example:46100", "network", "Do not infer extra ports from this text")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://site.example", "https://site.example", "HTTP://site.example/path", "https://[2001:db8::1]/"})
    void publicUrlUsesSchemeDefaultPort(String url) throws Exception {
        int port = url.toLowerCase(java.util.Locale.ROOT).startsWith("https:") ? 443 : 80;
        assertDoesNotThrow(() -> DeploymentAgentPlanNetworkConstraints.require(plan("PROCESS", port, port, "EXTERNAL"), Map.of("publicUrl", url)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "/relative", "ftp://site.example:46100", "http:///missing-host", "http://site.example:0",
        "http://site.example:65536", "http://site.example:-1", "http://site.example:bad", "http://private-value@site.example:46100"})
    void invalidPublicUrlIsRejectedWithoutEchoingInput(String url) throws Exception {
        var plan = plan("PROCESS", 46100, 46100, "EXTERNAL");
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPlanNetworkConstraints.require(plan, Map.of("publicUrl", url)));
        assertTrue(failure.getMessage().contains("valid HTTP/HTTPS URL"));
        assertFalse(failure.getMessage().contains("private-value"));
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
             "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
             "databases":[],"secretReferences":[]}],"applicationHealth":{"component":"web","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
        var component = (ObjectNode) declaration.path("components").get(0);
        component.put("backend", backend).putArray("ports").addObject().put("id", "web").put("host", host).put("target", target).put("exposure", exposure);
        return declaration;
    }
}
