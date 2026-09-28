package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Preserves the former exact compatibility retry cases. / 保留原有精确兼容性重试用例。 */
class BuildCompatibilityPolicyTest {
    /** Rejects generic failures while recognizing every former ecosystem marker. / 拒绝普通失败并识别全部原生态提示。 */
    @Test
    void retriesOnlyExplicitCompatibilityFailures() {
        assertTrue(BuildCompatibilityPolicy.retryable("error: invalid target release: 17"));
        for (String reason : List.of("package requires a different Python: 3.8.20 not in '>=3.9'",
                "Unknown Kotlin JVM target: 25", "Your Ruby version is 3.0.7, but your Gemfile specified 3.1.0",
                "your php version (8.0.30) does not satisfy that requirement", "error NETSDK1045",
                "npm ERR! EBADENGINE"))
            assertTrue(BuildCompatibilityPolicy.retryable(reason));
        for (String reason : List.of("connection timeout", "Permission denied", "SyntaxError", "OutOfMemoryError",
                "health check failed"))
            assertFalse(BuildCompatibilityPolicy.retryable(reason));
    }
}
