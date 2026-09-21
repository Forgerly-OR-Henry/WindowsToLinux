package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSnapshotFailureType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Validates and creates only the platform-owned Git workspace boundary. / 仅验证并创建平台拥有的 Git 工作区边界。
 */
final class ControlledGitWorkspaceValidator {
    /**
     * Validates and returns path and rejects inputs outside the declared constraints.
     * <p>校验并返回路径并拒绝超出已声明约束的输入。
     *
     * @param workspaceRoot workspace root / 工作区根目录
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     */
    Path require(Path workspaceRoot) throws GitSnapshotException {
        if (workspaceRoot == null) {
            throw GitSnapshotException.create(GitSnapshotFailureType.WORKSPACE_REQUIRED,
                    "Git source snapshot workspace is required");
        }
        try {
            Path root = workspaceRoot.toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace root is not a non-symbolic-link directory");
            }
            return root;
        } catch (IOException exception) {
            throw GitSnapshotException.create(GitSnapshotFailureType.WORKSPACE_UNAVAILABLE,
                    "Git source snapshot workspace is unavailable", exception);
        }
    }
}
