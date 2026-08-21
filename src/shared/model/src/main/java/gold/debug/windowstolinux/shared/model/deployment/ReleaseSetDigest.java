package gold.debug.windowstolinux.shared.model.deployment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Canonical dependency-ordered identity for one complete component release set. / 完整组件发布集合的规范依赖有序身份。 */
public final class ReleaseSetDigest {
    private static final byte[] DOMAIN =
            "windowstolinux-backup-release-set-v1".getBytes(StandardCharsets.UTF_8);

    private ReleaseSetDigest() {
    }

    /** Computes a domain- and length-separated SHA-256 over ordered component release identities. / 对有序组件发布身份计算域及长度分隔的 SHA-256。 */
    public static String sha256(List<ComponentRelease> components) {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 256) {
            throw new IllegalArgumentException("components must contain one to 256 release identities");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, DOMAIN);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(components.size()).array());
            for (ComponentRelease component : components) {
                updateLengthPrefixed(digest, Objects.requireNonNull(component, "component")
                        .componentId().getBytes(StandardCharsets.UTF_8));
                updateLengthPrefixed(digest, component.releaseSha256().getBytes(StandardCharsets.US_ASCII));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    /** One canonical managed component and its immutable release digest. / 单个规范受管组件及其不可变发布摘要。 */
    public record ComponentRelease(String componentId, String releaseSha256) {
        /** Validates the bounded component identity and canonical digest. / 校验有界组件身份及规范摘要。 */
        public ComponentRelease {
            componentId = Objects.requireNonNull(componentId, "componentId").trim();
            if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("componentId must be a bounded managed identifier");
            }
            releaseSha256 = Objects.requireNonNull(releaseSha256, "releaseSha256")
                    .trim().toLowerCase(Locale.ROOT);
            if (!releaseSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("releaseSha256 must be canonical SHA-256");
            }
        }
    }
}
