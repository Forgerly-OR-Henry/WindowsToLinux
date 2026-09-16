package gold.debug.windowstolinux.shared.analyze.ecosystem.node.pnpm;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/** Inspects the pnpm lockfile architecture. / 检查 pnpm 锁文件架构。 */
public final class PnpmBuildInspector {
    private static final Pattern MANAGER = Pattern.compile(
            "\\\"packageManager\\\"\\s*:\\s*\\\"pnpm@[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z._-]+)?\\\"");

    /** Returns pnpm facts when pnpm-lock.yaml exists. / 在 pnpm-lock.yaml 存在时返回 pnpm 事实。 */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "pnpm-lock.yaml";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path)) return Optional.empty();
        String lock = BoundedMetadataInspector.read(path);
        String manifest = BoundedMetadataInspector.read(root.resolve("package.json"));
        return lock.matches("(?s).*lockfileVersion:\\s*['\\\"]?[69](?:\\.0)?['\\\"]?.*")
                 && MANAGER.matcher(manifest).find()
                ? Optional.of(lockFile) : Optional.empty();
    }
}
