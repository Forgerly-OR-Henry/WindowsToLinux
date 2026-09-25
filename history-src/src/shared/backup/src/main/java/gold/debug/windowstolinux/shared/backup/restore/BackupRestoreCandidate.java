package gold.debug.windowstolinux.shared.backup.restore;

import java.nio.file.Path;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

/**
 * Fully extracted but not yet activated restore candidate. / 已完整提取但尚未激活的恢复候选。
 *
 * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
 * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
 * @param extractedBytes extracted bytes / 已提取字节
 */
public record BackupRestoreCandidate(Path root, BackupManifest manifest, long extractedBytes) {
    /**
     * Validates isolated candidate evidence. / 校验隔离候选证据。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param extractedBytes extracted bytes / 已提取字节
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupRestoreCandidate {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (extractedBytes < 0)
            throw new IllegalArgumentException("extractedBytes must not be negative");
    }
}
