package gold.debug.windowstolinux.shared.analyze.ecosystem.python.pipenv;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the Pipenv lockfile architecture. / 检查 Pipenv 锁文件架构。 */
public final class PipenvBuildInspector {
    /** Returns Pipenv facts when Pipfile.lock exists. / 在 Pipfile.lock 存在时返回 Pipenv 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "Pipfile.lock";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
