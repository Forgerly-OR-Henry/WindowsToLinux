package gold.debug.windowstolinux.mod.deployment.file;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentCommand;

/** Deployment-specific exclusions migrated from the original source archive rules. / 从原源码归档规则迁入的部署专用排除策略。 */
final class DeploymentSourcePolicy {
    /** Original exact directory exclusions. / 原精确目录排除集合。 */
    private static final Set<String> DIRECTORIES = Set.of(".git", ".idea", "target", "node_modules", ".m2", ".gradle",
        "logs");

    /** Original case-insensitive sensitive file names. / 原不区分大小写的敏感文件名。 */
    private static final Set<String> SECRET_FILES = Set.of(".env", ".npmrc", ".pypirc", "id_rsa", "id_dsa", "id_ecdsa",
        "id_ed25519", "known_hosts");

    /** Prevents construction of fixed policy. / 防止构造固定策略。 */
    private DeploymentSourcePolicy() {
    }

    /** Checks one final path segment against the original directory policy. / 按原目录策略检查最后一个路径段。
     * @param path original relative name / 原相对名称
     * @return whether the directory is omitted / 是否排除该目录 */
    static boolean directory(String path) {
        return DIRECTORIES.contains(name(path));
    }

    /** Checks file exclusion, allowing only explicitly named sample logs through. / 检查文件排除，仅允许明确命名的样例日志通过。
     * @param path original relative file / 原相对文件
     * @param explicit exact reviewed inclusions / 精确审阅纳入集合
     * @return whether the file is omitted / 是否排除该文件 */
    static boolean file(String path, Set<String> explicit) {
        String name = name(path).toLowerCase(Locale.ROOT);
        return secret(name) || (name.endsWith(".log") && !explicit.contains(path));
    }

    /** Parses the original bounded source.include override without overriding secret boundaries. / 解析原有界source.include覆盖项，不覆盖秘密边界。
     * @param value original property value / 原属性值
     * @return immutable exact inclusion set / 不可变精确纳入集合 */
    static Set<String> explicit(String value) {
        if (value.isBlank())
            return Set.of();
        var names = new HashSet<String>();
        for (String item : value.split(",", -1)) {
            String path = DeploymentCommand.relative(item.trim(), false);
            if (names.size() >= 32 || !names.add(path) || secret(name(path).toLowerCase(Locale.ROOT))
                || Arrays.stream(path.split("/")).anyMatch(DIRECTORIES::contains))
                throw new IllegalArgumentException("Explicit source resources violate the original policy");
        }
        return Set.copyOf(names);
    }

    /** Applies original secret-name rules to a lowercase final segment. / 对小写末尾路径段应用原秘密名称规则。
     * @param name lowercase final segment / 小写末尾路径段
     * @return whether the file is sensitive / 是否为敏感文件 */
    private static boolean secret(String name) {
        return SECRET_FILES.contains(name) || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key")
            || name.endsWith(".p12") || name.endsWith(".pfx");
    }

    /** Reads the last portable path segment without resolving a filesystem path. / 读取最后的可移植路径段，不解析文件系统路径。
     * @param path original relative name / 原相对名称
     * @return final segment / 最后路径段 */
    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
