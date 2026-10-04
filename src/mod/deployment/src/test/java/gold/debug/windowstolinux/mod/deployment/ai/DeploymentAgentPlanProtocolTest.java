package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentStorageLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real strict plan parser's original nonsecret and immutable-binding contracts. / 验证真实严格计划解析器的原非秘密及不可变绑定合同。 */
class DeploymentAgentPlanProtocolTest {
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
            {"applicationId":"app","components":[{"id":"main","dependencies":[],"evidence":["README.md"],
            "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
            "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[]}],
            "applicationHealth":{"component":"main","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
    }
}
