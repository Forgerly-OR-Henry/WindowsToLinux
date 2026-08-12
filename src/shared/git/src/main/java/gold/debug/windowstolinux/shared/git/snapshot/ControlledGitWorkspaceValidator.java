package gold.debug.windowstolinux.shared.git.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Validates and creates only the platform-owned Git workspace boundary. / 仅验证并创建平台拥有的 Git 工作区边界。 */
final class ControlledGitWorkspaceValidator {
    Path require(Path workspaceRoot) throws GitSnapshotException {
        if (workspaceRoot == null) {
            throw new GitSnapshotException("Git source snapshot workspace is required", null);
        }
        try {
            Path root = workspaceRoot.toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace root is not a non-symbolic-link directory");
            }
            return root;
        } catch (IOException exception) {
            throw new GitSnapshotException("Git source snapshot workspace is unavailable", exception);
        }
    }
}
