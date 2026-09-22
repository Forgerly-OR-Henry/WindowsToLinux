package gold.debug.windowstolinux.shared.git.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Rejects submodule, LFS, and symbolic-link entries until explicit bounded materialization policies exist. / 在存在显式有界物化策略前拒绝 Submodule、LFS 与符号链接条目。
 */
final class GitRepositoryFeaturePolicy {
    /**
     * MAX GIT ATTRIBUTES BYTES.
     * <p>最大Git属性字节。
     */
    private static final int MAX_GIT_ATTRIBUTES_BYTES = 256 * 1024;

    /**
     * Verifies git repository feature policy.
     * <p>验证Git仓库Feature策略。
     *
     * @param checkout checkout / 检出
     * @param indexEntries index entries / 索引条目
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    void verify(Path checkout, String indexEntries) throws IOException {
        if (java.util.regex.Pattern.compile("(?m)^120000 ").matcher(indexEntries).find()) {
            throw new IOException("Git symbolic-link entries are not accepted by the source-only snapshot policy");
        }
        if (Files.exists(checkout.resolve(".gitmodules"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Git submodules require an explicit controlled policy and are not accepted by this snapshot");
        }
        Path attributes = checkout.resolve(".gitattributes");
        if (Files.isRegularFile(attributes, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(attributes) > MAX_GIT_ATTRIBUTES_BYTES) {
                throw new IOException(".gitattributes exceeds the Git snapshot inspection bound");
            }
            String content = Files.readString(attributes, StandardCharsets.UTF_8);
            if (content.matches("(?s).*\\bfilter=lfs\\b.*")) {
                throw new IOException(
                        "Git LFS requires an explicit bounded materialization policy and is not accepted by this snapshot");
            }
        }
    }
}
