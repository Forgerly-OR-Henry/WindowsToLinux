package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.generation.script;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentBuildEnvironment;
import org.junit.jupiter.api.Test;

/** Preserves literal shell exports for reviewed build values. / 保留已审阅构建值的shell字面导出。 */
class DeploymentBuildEnvironmentRendererTest {
    /** Quotes values and excludes values absent from the build projection. / 引用构建值并排除不在构建投影中的值。 */
    @Test
    void exportsOnlyBuildScopeWithShellQuoting() {
        var snapshot = new DeploymentBuildEnvironment("demo", Map.of("BUILD_LABEL", "value'; touch /tmp/pwned; '"));
        String rendered = DeploymentBuildEnvironmentRenderer.render(snapshot);
        assertTrue(rendered.contains("export BUILD_LABEL='value'\"'\"'; touch /tmp/pwned; '\"'\"''"));
        assertFalse(rendered.contains("export PORT="));
    }
}
