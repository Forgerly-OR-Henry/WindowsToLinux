package gold.debug.windowstolinux.shared.analyze.ecosystem.python.pip;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the hash-locked pip architecture. / 检查使用哈希锁定的 pip 架构。 */
public final class PipBuildInspector {
    /** Returns pip facts when requirements.lock exists. / 在 requirements.lock 存在时返回 pip 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "requirements.lock";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
