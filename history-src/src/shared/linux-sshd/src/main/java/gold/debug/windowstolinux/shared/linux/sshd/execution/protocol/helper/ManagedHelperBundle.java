package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;

/**
 * Assembles the root-owned managed helper from fixed responsibility fragments and rejects protocol drift. / 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移。
 */
public final class ManagedHelperBundle {
    /**
     * Protocol version printed by this exact helper bundle. / 此精确 helper 包输出的协议版本。
     */
    public static final int PROTOCOL_VERSION = ManagedHelperProtocol.VERSION;

    /**
     * Platform-owned helper installation directory. / 平台持有的 helper 安装目录。
     */
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux";

    /**
     * Fixed root management entrypoint. / 固定的 root 管理入口。
     */
    public static final String PATH = ManagedHelperProtocol.PATH;

    /**
     * Platform-owned Java 21 launcher used by every managed systemd unit. / 每个受管 systemd 单元使用的平台持有 Java 21 启动器。
     */
    public static final String JAVA_RUNTIME_PATH = DIRECTORY + "/java-21";

    /**
     * Expected byte-for-byte helper bundle identity. / 预期的 helper 逐字节身份。
     */
    public static final String EXPECTED_SHA256 = "7e8ea768f8cadb4e023fa8f50279b772d0b047ff0820dab1a66ac7bb31d5257f";

    /**
     * ROOT.
     * <p>根目录。
     */
    private static final String ROOT = "/gold/debug/windowstolinux/shared/linux/sshd/";

    /**
     * RESOURCE INSERTS.
     * <p>资源INSERTS。
     */
    private static final Map<String, String> RESOURCE_INSERTS = Map.of("# @compat:agent-source@\n",
            "workspace/source-operations.py", "# @compat:apparmor@\n",
            "execution/protocol/helper/fragments/workspace/apparmor-namespace.sh", "# @compat:systemd-isolation@\n",
            "runtime/systemd/helper/systemd-manager-isolation.sh", "# @compat:selinux-entry@\n",
            "runtime/systemd/helper/selinux-command-entry.sh", "# @compat:centos-repositories@\n",
            "distro/dnf/centos-source-repositories.py");

    /**
     * Ordered packaged helper fragments.
     * <p>有序打包 helper 片段。
     */
    private static final List<String> FRAGMENTS = List.of(
            "execution/protocol/helper/fragments/00-protocol-foundation.sh",
            "execution/protocol/helper/fragments/release/10-typed-release.sh",
            "execution/protocol/helper/fragments/release/12-container-image-input.sh",
            "execution/protocol/helper/fragments/input/15-deployment-input.sh",
            "execution/protocol/helper/fragments/input/16-application-input.sh",
            "execution/protocol/helper/fragments/input/17-managed-content.sh",
            "execution/protocol/helper/fragments/release/18-container-storage.sh",
            "execution/protocol/helper/fragments/workspace/20-candidate-workspace.sh",
            "execution/protocol/helper/fragments/workspace/21-workspace-volume.sh",
            "execution/protocol/helper/fragments/workspace/22-restricted-build.sh",
            "execution/protocol/helper/fragments/workspace/23-container-builder.sh",
            "execution/protocol/helper/fragments/workspace/25-workspace-recovery.sh",
            "execution/protocol/helper/fragments/workspace/26-build-output.sh",
            "execution/protocol/helper/fragments/workspace/27-agent-workspace.sh",
            "execution/protocol/helper/fragments/ecosystem/35-ecosystem-dispatch.sh",
            "toolchain/10-release-metadata.py", "toolchain/20-installation-boundaries.py",
            "toolchain/30-managed-installation.py", "toolchain/40-binding-protocol.py",
            "execution/protocol/helper/fragments/runtime/40-typed-runtime.sh",
            "execution/protocol/helper/fragments/runtime/41-application-runtime.sh",
            "execution/protocol/helper/fragments/runtime/42-application-job.sh",
            "execution/protocol/helper/fragments/runtime/43-application-container.sh",
            "execution/protocol/helper/fragments/runtime/44-application-health.sh",
            "execution/protocol/helper/fragments/runtime/45-application-container-contract.sh",
            "execution/protocol/helper/fragments/release/50-container-release.sh",
            "execution/protocol/helper/fragments/release/52-container-recovery.sh",
            "execution/protocol/helper/fragments/restore/54-restore-candidate.sh",
            "execution/protocol/helper/fragments/restore/56-restore-commit.sh",
            "execution/protocol/helper/fragments/restore/57-application-restore.sh",
            "runtime/container/helper/55-podman-quadlet.sh", "runtime/systemd/helper/60-lifecycle.sh",
            "runtime/systemd/helper/61-service-identity.sh",
            "execution/protocol/helper/fragments/database/64-database-client.sh",
            "execution/protocol/helper/fragments/database/65-database-backup.sh",
            "execution/protocol/helper/fragments/database/66-database-activation.sh",
            "execution/protocol/helper/fragments/backup/67-managed-backup.sh",
            "execution/protocol/helper/fragments/database/10-native-instances.sh",
            "execution/protocol/helper/fragments/database/20-native-targets.sh",
            "execution/protocol/helper/fragments/70-command-dispatch.sh");

    /**
     * Installed non-privileged build entrypoint. / 安装后的非特权构建入口。
     *
     * @return render build entry text / 渲染构建条目文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static String renderBuildEntry() {
        try (InputStream stream = ManagedHelperBundle.class
                .getResourceAsStream(ROOT + "execution/protocol/helper/fragments/workspace/24-build-entry.sh")) {
            if (stream == null)
                throw new IllegalStateException("Build entry resource unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Build entry resource unreadable", failure);
        }
    }

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedHelperBundle() {
    }

    /**
     * Assembles and verifies the immutable helper protocol script. / 拼装并验证不可变 helper 协议脚本。
     *
     * @return render script text / 渲染脚本文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public static String renderScript() {
        byte[] bytes = assemble();
        String actual = sha256(bytes);
        if (!EXPECTED_SHA256.equals(actual)) {
            throw new IllegalStateException("managed-deployment privilege helper bundle identity changed: " + actual);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Combines fixed helper fragments with resource inserts and the shipped toolchain catalog into UTF-8 bytes.
     * <p>将固定 helper 片段、资源插入内容及随附工具链目录组合为 UTF-8 字节。
     *
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    static byte[] assemble() {
        return assemble(gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults());
    }

    /**
     * Combines fixed helper fragments with resource inserts and the shipped toolchain catalog into UTF-8 bytes.
     * <p>将固定 helper 片段、资源插入内容及随附工具链目录组合为 UTF-8 字节。
     *
     * @param catalog catalog / 目录
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    static byte[] assemble(gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog catalog) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String fragment : FRAGMENTS) {
            try (InputStream stream = ManagedHelperBundle.class.getResourceAsStream(ROOT + fragment)) {
                if (stream == null) {
                    throw new IllegalStateException(
                            "managed-deployment privilege helper fragment is unavailable: " + fragment);
                }
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                content = expandResourceInserts(content);
                if (fragment.startsWith("toolchain/")) {
                    if (fragment.endsWith("10-release-metadata.py"))
                        output.write(
                                ("prepare_official_toolchains() {\n  /usr/bin/python3 -I - \"$@\" <<'WTL_OFFICIAL_TOOLCHAINS'\n"
                                        + catalogLiteral(catalog)).getBytes(StandardCharsets.UTF_8));
                    output.write((content + "\n").getBytes(StandardCharsets.UTF_8));
                    if (fragment.endsWith("40-binding-protocol.py"))
                        output.write("WTL_OFFICIAL_TOOLCHAINS\n}\n".getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (fragment.endsWith("00-protocol-foundation.sh")) {
                    content += "build_entry_sha256=" + sha256(renderBuildEntry().getBytes(StandardCharsets.UTF_8))
                            + "\n";
                }
                // The embedded Python resource is literal; legacy shell resources retain their established normalization. / 嵌入的 Python 资源保持字面内容，旧版 Shell 资源继续使用既有规范化处理。
                output.write(((fragment.endsWith("27-agent-workspace.sh") || fragment.endsWith("10-native-instances.sh")
                        || fragment.endsWith("20-native-targets.sh") || fragment.endsWith("23-container-builder.sh")
                        || fragment.endsWith("12-container-image-input.sh")
                        || fragment.endsWith("18-container-storage.sh") || fragment.contains("application-"))
                                ? content
                                : content.replace("\\\\", "\\"))
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "managed-deployment privilege helper fragment cannot be read: " + fragment, exception);
            }
        }
        return output.toByteArray();
    }

    /**
     * Replaces known helper insertion markers with their required packaged resource contents.
     * <p>使用必需的打包资源内容替换已知 helper 插入标记。
     *
     * @param script build script path / 构建脚本路径
     * @return expand resource inserts text / expand资源Inserts文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String expandResourceInserts(String script) throws IOException {
        for (var insert : RESOURCE_INSERTS.entrySet()) {
            if (!script.contains(insert.getKey()))
                continue;
            try (InputStream input = ManagedHelperBundle.class.getResourceAsStream(ROOT + insert.getValue())) {
                if (input == null) {
                    throw new IllegalStateException(
                            "managed-deployment privilege helper insert is unavailable: " + insert.getValue());
                }
                script = script.replaceAll("(?m)^[ \t]*" + java.util.regex.Pattern.quote(insert.getKey()),
                        java.util.regex.Matcher.quoteReplacement(
                                new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")));
            }
        }
        return script;
    }

    /**
     * Renders the supported toolchain catalog as the helper's fixed Python data literal.
     * <p>将受支持工具链目录渲染为 helper 的固定 Python 数据字面量。
     *
     * @param catalog catalog / 目录
     * @return catalog literal text / 目录Literal文本
     */
    private static String catalogLiteral(
            gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog catalog) {
        StringBuilder data = new StringBuilder("SHIPPED_CATALOG = {\n");
        for (var ecosystem : gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.values()) {
            data.append("    '").append(ecosystem.name()).append("': [");
            data.append(catalog.branches(ecosystem).stream().map(b -> "'" + b.version() + "'")
                    .collect(java.util.stream.Collectors.joining(", "))).append("],\n");
        }
        return data.append("}\n").toString();
    }

    /**
     * Computes the SHA-256 content identity used for independent integrity checks.
     * <p>计算独立完整性检查使用的 SHA-256 内容身份。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
