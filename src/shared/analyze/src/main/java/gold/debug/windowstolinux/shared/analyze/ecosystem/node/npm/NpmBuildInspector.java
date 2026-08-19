package gold.debug.windowstolinux.shared.analyze.ecosystem.node.npm;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/** Inspects the npm package-lock architecture. / 检查 npm package-lock 架构。 */
public final class NpmBuildInspector {
    private static final Pattern LOCK_VERSION = Pattern.compile("\\\"lockfileVersion\\\"\\s*:\\s*[23]");

    /** Returns npm facts when package-lock.json exists. / 在 package-lock.json 存在时返回 npm 事实。 */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "package-lock.json";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path)) return Optional.empty();
        String content = BoundedMetadataInspector.read(path);
        return LOCK_VERSION.matcher(content).find() && content.matches("(?s).*\\\"packages\\\"\\s*:\\s*\\{.*")
                && content.matches("(?s).*\\\"\\\"\\s*:\\s*\\{.*") ? Optional.of(lockFile) : Optional.empty();
    }
}
