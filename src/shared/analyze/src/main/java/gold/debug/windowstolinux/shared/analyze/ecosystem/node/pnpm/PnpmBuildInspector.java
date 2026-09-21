package gold.debug.windowstolinux.shared.analyze.ecosystem.node.pnpm;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Inspects the pnpm lockfile architecture. / 检查 pnpm 锁文件架构。
 */
public final class PnpmBuildInspector {
    /**
     * Pattern recognizing MANAGER.
     * <p>用于识别管理器的匹配模式。
     */
    private static final Pattern MANAGER = Pattern.compile(
            "\\\"packageManager\\\"\\s*:\\s*\\\"pnpm@[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z._-]+)?\\\"");

    /**
     * Returns pnpm facts when pnpm-lock.yaml exists. / 在 pnpm-lock.yaml 存在时返回 pnpm 事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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
