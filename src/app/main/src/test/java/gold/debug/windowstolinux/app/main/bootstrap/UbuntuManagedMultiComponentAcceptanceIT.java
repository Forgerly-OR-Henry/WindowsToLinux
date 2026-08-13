package gold.debug.windowstolinux.app.main.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Opt-in product-entrypoint acceptance for atomic multi-component deployment and durable lifecycle. / 原子多组件部署与持久生命周期的可选产品入口验收。 */
@EnabledIfSystemProperty(named = "managed.runtime.multi", matches = "true")
class UbuntuManagedMultiComponentAcceptanceIT {
    @TempDir Path temporaryDirectory;

    @Test
    void deploysRollsBackAndRestoresWholeApplicationLifecycleAfterDesktopRestart() throws Exception {
        ManagedMultiComponentDeploymentAcceptance.exercise(temporaryDirectory, "multi-live");
    }
}
