package gold.debug.windowstolinux.shared.analyze.ecosystem.node.npm;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Inspects the npm package-lock architecture. / 检查 npm package-lock 架构。
 */
public final class NpmBuildInspector {
    /**
     * Pattern recognizing LOCK VERSION.
     * <p>用于识别锁版本的匹配模式。
     */
    private static final Pattern LOCK_VERSION = Pattern.compile("\\\"lockfileVersion\\\"\\s*:\\s*[23]");

    /**
     * Returns npm facts when package-lock.json exists. / 在 package-lock.json 存在时返回 npm 事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "package-lock.json";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path)) return Optional.empty();
        String content = BoundedMetadataInspector.read(path);
        return LOCK_VERSION.matcher(content).find() && content.matches("(?s).*\\\"packages\\\"\\s*:\\s*\\{.*")
                && content.matches("(?s).*\\\"\\\"\\s*:\\s*\\{.*") ? Optional.of(lockFile) : Optional.empty();
    }
}
