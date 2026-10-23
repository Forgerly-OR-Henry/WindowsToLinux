package gold.debug.windowstolinux.mod.deployment.service.core.model.build;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Reviewed non-secret values for one fixed remote build. / 一次固定远端构建的已审阅非秘密值。
 * @param applicationId original managed application / 原受管应用
 * @param entries bounded, type-checked build exports / 有界且经类型检查的构建导出值 */
public record DeploymentBuildEnvironment(String applicationId, Map<String, String> entries) {
    /** Freezes canonical exports before remote execution. / 远端执行前冻结规范化导出值。
     * @param applicationId original managed application / 原受管应用
     * @param entries bounded build exports / 有界构建导出值 */
    public DeploymentBuildEnvironment {
        if (!Objects.requireNonNull(applicationId).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid build application identity");
        entries = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(entries)));
        entries.forEach((key, value) -> {
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || value == null || value.length() > 4096
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid build export");
        });
    }
}
