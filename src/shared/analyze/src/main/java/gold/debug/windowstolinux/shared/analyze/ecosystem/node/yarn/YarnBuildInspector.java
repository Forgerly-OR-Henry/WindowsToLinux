package gold.debug.windowstolinux.shared.analyze.ecosystem.node.yarn;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the Yarn lockfile architecture. / 检查 Yarn 锁文件架构。 */
public final class YarnBuildInspector {
    /** Returns Yarn facts when yarn.lock exists. / 在 yarn.lock 存在时返回 Yarn 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "yarn.lock";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
