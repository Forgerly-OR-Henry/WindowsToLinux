package gold.debug.windowstolinux.shared.analyze.ecosystem.node.npm;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the npm package-lock architecture. / 检查 npm package-lock 架构。 */
public final class NpmBuildInspector {
    /** Returns npm facts when package-lock.json exists. / 在 package-lock.json 存在时返回 npm 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "package-lock.json";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
