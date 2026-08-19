package gold.debug.windowstolinux.shared.analyze.ecosystem.python.poetry;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the Poetry lockfile architecture. / 检查 Poetry 锁文件架构。 */
public final class PoetryBuildInspector {
    /** Returns Poetry facts when poetry.lock exists. / 在 poetry.lock 存在时返回 Poetry 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "poetry.lock";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
