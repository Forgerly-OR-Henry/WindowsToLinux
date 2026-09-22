package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node.yarn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;

/**
 * Inspects the Yarn lockfile architecture. / 检查 Yarn 锁文件架构。
 */
public final class YarnBuildInspector {
    /**
     * Pattern recognizing MANAGER.
     * <p>用于识别管理器的匹配模式。
     */
    private static final Pattern MANAGER = Pattern
            .compile("\\\"packageManager\\\"\\s*:\\s*\\\"yarn@[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z._-]+)?\\\"");

    /**
     * Pattern recognizing METADATA.
     * <p>用于识别元数据的匹配模式。
     */
    private static final Pattern METADATA = Pattern.compile("(?m)^__metadata:\\s*$");

    /**
     * Pattern recognizing METADATA VERSION.
     * <p>用于识别元数据版本的匹配模式。
     */
    private static final Pattern METADATA_VERSION = Pattern.compile("(?m)^\\s+version:\\s*[0-9]+\\s*$");

    /**
     * Returns Yarn facts when yarn.lock exists. / 在 yarn.lock 存在时返回 Yarn 事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "yarn.lock";
        Path path = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(path))
            return Optional.empty();
        String lock = BoundedMetadataInspector.read(path);
        String manifest = BoundedMetadataInspector.read(root.resolve("package.json"));
        return METADATA.matcher(lock).find() && METADATA_VERSION.matcher(lock).find()
                && MANAGER.matcher(manifest).find() ? Optional.of(lockFile) : Optional.empty();
    }
}
