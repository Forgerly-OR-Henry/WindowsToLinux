package gold.debug.windowstolinux.shared.analyze.ecosystem.node.pnpm;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.nio.file.Path;
import java.util.Optional;

/** Inspects the pnpm lockfile architecture. / 检查 pnpm 锁文件架构。 */
public final class PnpmBuildInspector {
    /** Returns pnpm facts when pnpm-lock.yaml exists. / 在 pnpm-lock.yaml 存在时返回 pnpm 事实。 */
    public Optional<String> inspect(Path root) {
        String lockFile = "pnpm-lock.yaml";
        return BoundedMetadataInspector.regular(root.resolve(lockFile))
                ? Optional.of(lockFile) : Optional.empty();
    }
}
