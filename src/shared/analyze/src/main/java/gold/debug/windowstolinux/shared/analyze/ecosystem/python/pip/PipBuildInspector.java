package gold.debug.windowstolinux.shared.analyze.ecosystem.python.pip;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/** Inspects the hash-locked pip architecture. / 检查使用哈希锁定的 pip 架构。 */
public final class PipBuildInspector {
    private static final Pattern HASH = Pattern.compile("--hash=sha256:[0-9a-fA-F]{64}");

    /** Returns pip facts when requirements.lock exists. / 在 requirements.lock 存在时返回 pip 事实。 */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "requirements.lock";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path)) return Optional.empty();
        String content = BoundedMetadataInspector.read(path);
        String logical = content.replace("\r\n", "\n").replace("\\\n", " ");
        boolean valid = logical.lines().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .allMatch(line -> !line.startsWith("-") && line.contains("==") && HASH.matcher(line).find());
        return valid ? Optional.of(lockFile) : Optional.empty();
    }
}
