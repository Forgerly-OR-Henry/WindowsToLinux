package gold.debug.windowstolinux.mod.deployment.service.core.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Complete observed source facts; a stored location never authorizes reading. / 完整已观察来源事实；已存位置绝不授予读取权。
 * @param kind source category / 来源类别
 * @param location original canonical location / 原规范位置
 * @param gitRef exact encoded reference / 精确编码引用
 * @param gitCommit resolved full commit / 已解析完整提交
 * @param sha256 platform frozen source-content digest / 平台冻结源码内容摘要 */
public record DeploymentProjectSource(KindType kind, URI location, Optional<String> gitRef, Optional<String> gitCommit,
        String sha256) {
    /** Selected original source categories. / 已选原来源类别。 */
    public enum KindType {
        /** Original user-selected local directory. / 原用户选择的本地目录。 */
        LOCAL,
        /** Original pinned Git request. / 原固定Git请求。 */
        GIT
    }

    /** Validates exact persisted metadata without accessing the source. / 不访问来源地校验精确持久元数据。
     * @param kind source category / 来源类别
     * @param location original canonical location / 原规范位置
     * @param gitRef exact encoded reference / 精确编码引用
     * @param gitCommit resolved full commit / 已解析完整提交
     * @param sha256 platform frozen source-content digest / 平台冻结源码内容摘要 */
    public DeploymentProjectSource {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(location);
        gitRef = Objects.requireNonNull(gitRef);
        gitCommit = Objects.requireNonNull(gitCommit);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid frozen source digest");
        if (kind == KindType.LOCAL) {
            if (!"file".equals(location.getScheme()) || location.getRawQuery() != null
                    || location.getRawFragment() != null || !location.isAbsolute()
                    || !location.normalize().equals(location) || !Path.of(location).isAbsolute()
                    || !Path.of(location).normalize().equals(Path.of(location)) || gitRef.isPresent()
                    || gitCommit.isPresent())
                throw new IllegalArgumentException("Invalid original local source facts");
        } else {
            String scheme = location.getScheme();
            boolean file = scheme != null && scheme.equalsIgnoreCase("file");
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("ssh") || file)
                    || !location.normalize().equals(location) || location.getRawUserInfo() != null
                    || location.getRawQuery() != null || location.getRawFragment() != null
                    || (!file && (location.getHost() == null || location.getHost().isBlank()))
                    || (file && (location.getRawAuthority() != null && !location.getRawAuthority().isEmpty()
                            || location.getPath() == null || !location.getPath().startsWith("/")))
                    || location.toString().length() > 2048 || gitRef.isEmpty() || gitCommit.isEmpty()
                    || !gitCommit.get().matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("Incomplete or unsafe original Git facts");
            validateRef(gitRef.get(), gitCommit.get());
        }
    }

    /** Checks exact ref encoding, including the distinction between branch and tag. / 校验精确引用编码，包括分支与标签的区别。
     * @param value stored ref text / 已存引用文本
     * @param commit resolved exact commit / 已解析精确提交 */
    private static void validateRef(String value, String commit) {
        if (value.equals("DEFAULT:HEAD"))
            return;
        if (value.startsWith("COMMIT:") && value.substring(7).equals(commit))
            return;
        if (value.startsWith("BRANCH:") || value.startsWith("TAG:")) {
            String name = value.substring(value.indexOf(':') + 1);
            if (name.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}") && !name.contains("..") && !name.contains("//")
                    && !name.endsWith("/"))
                return;
        }
        throw new IllegalArgumentException("Invalid stored Git reference");
    }
}
