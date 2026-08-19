package gold.debug.windowstolinux.shared.analyze.ecosystem.python.uv;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Inspects the uv lockfile architecture. / 检查 uv 锁文件架构。 */
public final class UvBuildInspector {
    /** Returns uv facts when uv.lock exists. / 在 uv.lock 存在时返回 uv 事实。 */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "uv.lock";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path)) return Optional.empty();
        String content = BoundedMetadataInspector.read(path);
        boolean shape = content.matches("(?s)^version\\s*=\\s*[1-9][0-9]*.*")
                && content.matches("(?s).*revision\\s*=\\s*[1-9][0-9]*.*")
                && content.matches("(?s).*requires-python\\s*=\\s*\\\"[^\\\"\\r\\n]+\\\".*")
                && content.contains("[[package]]");
        return shape ? Optional.of(lockFile) : Optional.empty();
    }
}
