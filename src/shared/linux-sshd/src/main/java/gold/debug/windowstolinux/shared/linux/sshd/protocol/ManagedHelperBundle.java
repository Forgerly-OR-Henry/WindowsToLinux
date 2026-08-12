package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Assembles the root-owned managed helper from fixed responsibility fragments and rejects protocol drift. / 从固定职责片段拼装 root 持有的受管 helper 并拒绝协议漂移。 */
public final class ManagedHelperBundle {
    /** Platform-owned helper installation directory. / 平台持有的 helper 安装目录。 */
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux";
    /** The only sudoers-allowlisted helper path. / sudoers 唯一列入白名单的 helper 路径。 */
    public static final String PATH = DIRECTORY + "/managed-helper";
    /** Expected byte-for-byte helper bundle identity. / 预期的 helper 逐字节身份。 */
    public static final String EXPECTED_SHA256 = "399bc1f0fc0cc6d8abec2abf887fca956b18bc294670e5a1d0dc4b17faa10a9f";
    private static final String ROOT = "/gold/debug/windowstolinux/shared/linux/sshd/protocol/managed-helper-fragments/";
    private static final List<String> FRAGMENTS = List.of(
            "00-common.sh", "10-typed-release.sh", "20-candidate-workspace.sh", "30-ordinary-release.sh",
            "40-typed-runtime.sh", "50-container-release.sh", "60-lifecycle.sh", "70-command-dispatch.sh");

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
