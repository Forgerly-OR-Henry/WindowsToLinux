package gold.debug.windowstolinux.shared.agent.execution.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AutonomousAiProtocolClientTest {
    private final ObjectMapper json = new ObjectMapper();

    private static final String PLAN = """
            {"tool":"plan","arguments":{"explanation":"custom source evidence","applicationHealth":{"component":"app","health":{"type":"PROCESS","timeout":5,"stability":1}},"components":[
              {"id":"app","dependencies":[],"evidence":["build.zig"],"backend":"PROCESS","mode":"DAEMON",
               "entrypoint":"bin/server","arguments":[],"workingDirectory":"","health":{"type":"PROCESS","timeout":5,"stability":1},"ports":[],
               "resources":[{"id":"files","path":"/data/files","access":"READ_WRITE","schema":"files-v1","reversible":false,"type":"FILE","seedFile":"","digest":""}],
               "configuration":[{"key":"PORT","value":18080}]}]}}
            """;
    @Test
    void unknownLanguageNeedsNoStaticTypeAndPreservesExplicitResources() throws Exception {
        var plan = (AgentProposal.Plan) AutonomousAiProtocolClient.parse(json.readTree(PLAN));
        var component = plan.delivery().components().getFirst();
        assertEquals("bin/server", component.runtime().workload().command().entrypoint());
        assertEquals("/data/files", component.resources().fileBindings().getFirst().dataPath().path());
        assertEquals("18080", component.configuration().getFirst().value().canonicalValue());
    }

    @Test
    void missingResourceReviewSecretsAndUnsupportedFieldsAreRejected() throws Exception {
        for (String invalid : new String[]{
                PLAN.replace("\"configuration\":[{\"key\":\"PORT\",\"value\":18080}]",
                        "\"configuration\":[{\"key\":\"PASSWORD\",\"value\":\"private\"}]"),
                PLAN.replace("\"ports\":[]", "\"ports\":[],\"projectType\":\"ZIG\""),
                PLAN.replace("\"PROCESS\"", "\"UNMANAGED\"")})
            assertThrows(IllegalArgumentException.class,
                    () -> AutonomousAiProtocolClient.parse(json.readTree(invalid)));
        var missing = json.readTree(PLAN);
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing.get("arguments").get("components").get(0))
                .remove("resources");
        assertThrows(IllegalArgumentException.class, () -> AutonomousAiProtocolClient.parse(missing));
    }
}
