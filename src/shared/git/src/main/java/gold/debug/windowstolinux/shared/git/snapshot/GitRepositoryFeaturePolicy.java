package gold.debug.windowstolinux.shared.git.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Rejects submodule and LFS repositories until an explicit bounded materialization policy exists. / 在存在显式有界物化策略前拒绝 Submodule 与 LFS 仓库。 */
final class GitRepositoryFeaturePolicy {
    private static final int MAX_GIT_ATTRIBUTES_BYTES = 256 * 1024;

    void verify(Path checkout) throws IOException {
        if (Files.exists(checkout.resolve(".gitmodules"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Git submodules require an explicit controlled policy and are not accepted by this snapshot");
        }
        Path attributes = checkout.resolve(".gitattributes");
        if (Files.isRegularFile(attributes, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(attributes) > MAX_GIT_ATTRIBUTES_BYTES) {
                throw new IOException(".gitattributes exceeds the Git snapshot inspection bound");
            }
            String content = Files.readString(attributes, StandardCharsets.UTF_8);
            if (content.matches("(?s).*\\bfilter=lfs\\b.*")) {
                throw new IOException("Git LFS requires an explicit bounded materialization policy and is not accepted by this snapshot");
            }
        }
    }
}
