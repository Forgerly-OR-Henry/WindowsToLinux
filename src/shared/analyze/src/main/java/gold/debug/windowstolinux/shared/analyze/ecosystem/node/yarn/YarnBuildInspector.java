package gold.debug.windowstolinux.shared.analyze.ecosystem.node.yarn;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/** Inspects the Yarn lockfile architecture. / 检查 Yarn 锁文件架构。 */
public final class YarnBuildInspector {
    private static final Pattern MANAGER = Pattern.compile(
            "\\\"packageManager\\\"\\s*:\\s*\\\"yarn@([234])\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z._-]+)?\\\"");
    private static final Pattern METADATA_VERSION = Pattern.compile("(?m)^\\s+version:\\s*[0-9]+\\s*$");

    /** Returns Yarn facts when yarn.lock exists. / 在 yarn.lock 存在时返回 Yarn 事实。 */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "yarn.lock";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path)) return Optional.empty();
        String lock = BoundedMetadataInspector.read(path);
        String manifest = BoundedMetadataInspector.read(root.resolve("package.json"));
        return lock.startsWith("__metadata:\n") && METADATA_VERSION.matcher(lock).find()
                && MANAGER.matcher(manifest).find() ? Optional.of(lockFile) : Optional.empty();
    }
}
