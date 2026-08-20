package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;

/** Assembles the root-owned managed helper from fixed responsibility fragments and rejects protocol drift. / 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移。 */
public final class ManagedHelperBundle {
    /** Protocol version printed by this exact helper bundle. / 此精确 helper 包输出的协议版本。 */
    public static final int PROTOCOL_VERSION = ManagedHelperProtocol.VERSION;
    /** Platform-owned helper installation directory. / 平台持有的 helper 安装目录。 */
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux";
    /** The only sudoers-allowlisted helper path. / sudoers 唯一列入白名单的 helper 路径。 */
    public static final String PATH = DIRECTORY + "/managed-helper";
    /** Platform-owned Java 21 launcher used by every managed systemd unit. / 每个受管 systemd 单元使用的平台持有 Java 21 启动器。 */
    public static final String JAVA_RUNTIME_PATH = DIRECTORY + "/java-21";
    /** Expected byte-for-byte helper bundle identity. / 预期的 helper 逐字节身份。 */
    public static final String EXPECTED_SHA256 = "339153b9ddd5073fb1c046f5d271dfd23e9e6ece62dfced18cc9a068022b41d8";
    private static final String ROOT = "/gold/debug/windowstolinux/shared/linux/sshd/";
    private static final List<String> FRAGMENTS = List.of(
            "execution/protocol/helper/fragments/00-protocol-foundation.sh",
            "execution/protocol/helper/fragments/release/10-typed-release.sh",
            "execution/protocol/helper/fragments/input/15-deployment-input.sh",
            "execution/protocol/helper/fragments/workspace/20-candidate-workspace.sh",
            "execution/protocol/helper/fragments/release/30-ordinary-release.sh",
            "execution/protocol/helper/fragments/ecosystem/35-ecosystem-dispatch.sh",
            "execution/protocol/helper/fragments/runtime/40-typed-runtime.sh",
            "execution/protocol/helper/fragments/release/50-container-release.sh",
            "runtime/container/helper/55-podman-quadlet.sh", "runtime/systemd/helper/60-lifecycle.sh",
            "execution/protocol/helper/fragments/70-command-dispatch.sh");

    private ManagedHelperBundle() { }

    /** Assembles and verifies the immutable helper protocol script. / 拼装并验证不可变 helper 协议脚本。 */
    public static String renderScript() {
        byte[] bytes = assemble();
        String actual = sha256(bytes);
        if (!EXPECTED_SHA256.equals(actual)) {
            throw new IllegalStateException("managed-deployment privilege helper bundle identity changed: " + actual);
        }
        return new String(bytes, StandardCharsets.UTF_8).replace("\\\\", "\\");
    }

    static byte[] assemble() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String fragment : FRAGMENTS) {
            try (InputStream stream = ManagedHelperBundle.class.getResourceAsStream(ROOT + fragment)) {
                if (stream == null) {
                    throw new IllegalStateException("managed-deployment privilege helper fragment is unavailable: " + fragment);
                }
                stream.transferTo(output);
            } catch (IOException exception) {
                throw new IllegalStateException("managed-deployment privilege helper fragment cannot be read: " + fragment,
                        exception);
            }
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }
}
