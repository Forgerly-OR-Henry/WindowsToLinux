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

/** Assembles the root-owned managed helper from fixed responsibility fragments and rejects protocol drift. / 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移。 */
public final class ManagedHelperBundle {
    /** Protocol version printed by this exact helper bundle. / 此精确 helper 包输出的协议版本。 */
    public static final int PROTOCOL_VERSION = ManagedHelperProtocol.VERSION;
    /** Platform-owned helper installation directory. / 平台持有的 helper 安装目录。 */
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux";
    /** Fixed root management entrypoint. / 固定的 root 管理入口。 */
    public static final String PATH = DIRECTORY + "/managed-helper";
    /** Platform-owned Java 21 launcher used by every managed systemd unit. / 每个受管 systemd 单元使用的平台持有 Java 21 启动器。 */
    public static final String JAVA_RUNTIME_PATH = DIRECTORY + "/java-21";
    /** Expected byte-for-byte helper bundle identity. / 预期的 helper 逐字节身份。 */
    public static final String EXPECTED_SHA256 = "1f10cece3ee7a3c9d75e9000c2b1c6bbe950afc11da65b65cc71ed26f4a2af87";
    private static final String ROOT = "/gold/debug/windowstolinux/shared/linux/sshd/";
    private static final Map<String, String> RESOURCE_INSERTS = Map.of(
            "# @compat:apparmor@\n", "execution/protocol/helper/fragments/workspace/apparmor-namespace.sh",
            "# @compat:systemd-isolation@\n", "runtime/systemd/helper/systemd-manager-isolation.sh",
            "# @compat:selinux-entry@\n", "runtime/systemd/helper/selinux-command-entry.sh",
            "# @compat:centos-repositories@\n", "distro/dnf/centos-source-repositories.py");
    private static final List<String> FRAGMENTS = List.of(
            "execution/protocol/helper/fragments/00-protocol-foundation.sh",
            "execution/protocol/helper/fragments/release/10-typed-release.sh",
            "execution/protocol/helper/fragments/release/12-container-image-input.sh",
            "execution/protocol/helper/fragments/input/15-deployment-input.sh",
            "execution/protocol/helper/fragments/input/17-managed-content.sh",
            "execution/protocol/helper/fragments/workspace/20-candidate-workspace.sh",
            "execution/protocol/helper/fragments/workspace/21-workspace-volume.sh",
            "execution/protocol/helper/fragments/workspace/22-restricted-build.sh",
            "execution/protocol/helper/fragments/workspace/23-container-builder.sh",
            "execution/protocol/helper/fragments/workspace/25-workspace-recovery.sh",
            "execution/protocol/helper/fragments/workspace/26-build-output.sh",
            "execution/protocol/helper/fragments/ecosystem/35-ecosystem-dispatch.sh",
            "toolchain/10-release-metadata.py", "toolchain/20-installation-boundaries.py",
            "toolchain/30-managed-installation.py", "toolchain/40-binding-protocol.py",
            "execution/protocol/helper/fragments/runtime/40-typed-runtime.sh",
            "execution/protocol/helper/fragments/release/50-container-release.sh",
            "execution/protocol/helper/fragments/release/52-container-recovery.sh",
            "execution/protocol/helper/fragments/restore/54-restore-candidate.sh",
            "execution/protocol/helper/fragments/restore/56-restore-commit.sh",
            "runtime/container/helper/55-podman-quadlet.sh", "runtime/systemd/helper/60-lifecycle.sh",
            "runtime/systemd/helper/61-dynamic-identity.sh",
            "execution/protocol/helper/fragments/database/64-database-client.sh",
            "execution/protocol/helper/fragments/database/65-database-backup.sh",
            "execution/protocol/helper/fragments/database/66-database-activation.sh",
            "execution/protocol/helper/fragments/backup/67-managed-backup.sh",
            "execution/protocol/helper/fragments/database/10-native-instances.sh",
            "execution/protocol/helper/fragments/database/20-native-targets.sh",
            "execution/protocol/helper/fragments/70-command-dispatch.sh");

    /** Installed non-privileged build entrypoint. / 安装后的非特权构建入口。 */
    public static String renderBuildEntry() {
        try (InputStream stream = ManagedHelperBundle.class.getResourceAsStream(ROOT
                + "execution/protocol/helper/fragments/workspace/24-build-entry.sh")) {
            if (stream == null) throw new IllegalStateException("Build entry resource unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Build entry resource unreadable", failure);
        }
    }

    private ManagedHelperBundle() { }

    /** Assembles and verifies the immutable helper protocol script. / 拼装并验证不可变 helper 协议脚本。 */
    public static String renderScript() {
        byte[] bytes = assemble();
        String actual = sha256(bytes);
        if (!EXPECTED_SHA256.equals(actual)) {
            throw new IllegalStateException("managed-deployment privilege helper bundle identity changed: " + actual);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] assemble() {
        return assemble(gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults());
    }

    static byte[] assemble(gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog catalog) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String fragment : FRAGMENTS) {
            try (InputStream stream = ManagedHelperBundle.class.getResourceAsStream(ROOT + fragment)) {
                if (stream == null) {
                    throw new IllegalStateException("managed-deployment privilege helper fragment is unavailable: " + fragment);
                }
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                content = expandResourceInserts(content);
                if (fragment.startsWith("toolchain/")) {
                    if (fragment.endsWith("10-release-metadata.py")) output.write(
                            ("prepare_official_toolchains() {\n  /usr/bin/python3 -I - \"$@\" <<'WTL_OFFICIAL_TOOLCHAINS'\n"
                                    + catalogLiteral(catalog)).getBytes(StandardCharsets.UTF_8));
                    output.write((content + "\n").getBytes(StandardCharsets.UTF_8));
                    if (fragment.endsWith("40-binding-protocol.py"))
                        output.write("WTL_OFFICIAL_TOOLCHAINS\n}\n".getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (fragment.endsWith("00-protocol-foundation.sh")) {
                    content += "build_entry_sha256=" + sha256(renderBuildEntry().getBytes(StandardCharsets.UTF_8)) + "\n";
                }
                // The embedded Python resource is literal; legacy shell resources retain their established normalization. / 嵌入的 Python 资源保持字面内容，旧版 Shell 资源继续使用既有规范化处理。
                output.write(((fragment.endsWith("10-native-instances.sh") || fragment.endsWith("20-native-targets.sh") || fragment.endsWith("23-container-builder.sh") || fragment.endsWith("12-container-image-input.sh")) ? content : content.replace("\\\\", "\\")).getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("managed-deployment privilege helper fragment cannot be read: " + fragment,
                        exception);
            }
        }
        return output.toByteArray();
    }

    private static String expandResourceInserts(String script) throws IOException {
        for (var insert : RESOURCE_INSERTS.entrySet()) {
            if (!script.contains(insert.getKey())) continue;
            try (InputStream input = ManagedHelperBundle.class.getResourceAsStream(ROOT + insert.getValue())) {
                if (input == null) {
                    throw new IllegalStateException("managed-deployment privilege helper insert is unavailable: " + insert.getValue());
                }
                script = script.replace(insert.getKey(),
                        new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return script;
    }

    private static String catalogLiteral(gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog catalog) {
        StringBuilder data = new StringBuilder("SHIPPED_CATALOG = {\n");
        for (var ecosystem : gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.values()) {
            data.append("    '").append(ecosystem.name()).append("': [");
            data.append(catalog.branches(ecosystem).stream().map(b -> "'" + b.version() + "'")
                    .collect(java.util.stream.Collectors.joining(", "))).append("],\n");
        }
        return data.append("}\n").toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
