package gold.debug.windowstolinux.shared.analyze.ecosystem.python.uv;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the uv lockfile architecture. / 检查 uv 锁文件架构。 */
public final class UvBuildInspector {
    /** Returns uv facts when uv.lock exists. / 在 uv.lock 存在时返回 uv 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "uv.lock";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
