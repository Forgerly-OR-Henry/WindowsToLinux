package gold.debug.windowstolinux.shared.model.deployment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical dependency-ordered identity for one complete component release set. / 完整组件发布集合的规范依赖有序身份。
 */
public final class ReleaseSetDigest {
    /**
     * DOMAIN.
     * <p>领域。
     */
    private static final byte[] DOMAIN = "windowstolinux-backup-release-set-v1".getBytes(StandardCharsets.UTF_8);

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ReleaseSetDigest() {
    }

    /**
     * Computes a domain- and length-separated SHA-256 over ordered component release identities. / 对有序组件发布身份计算域及长度分隔的 SHA-256。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
                updateLengthPrefixed(digest,
                        Objects.requireNonNull(component, "component").componentId().getBytes(StandardCharsets.UTF_8));
                updateLengthPrefixed(digest, component.releaseSha256().getBytes(StandardCharsets.US_ASCII));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Updates length prefixed.
     * <p>更新长度Prefixed。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    /**
     * One canonical managed component and its immutable release digest. / 单个规范受管组件及其不可变发布摘要。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     */
    public record ComponentRelease(String componentId, String releaseSha256) {
        /**
         * Validates the bounded component identity and canonical digest. / 校验有界组件身份及规范摘要。
         *
         * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
         * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public ComponentRelease {
            componentId = Objects.requireNonNull(componentId, "componentId").trim();
            if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("componentId must be a bounded managed identifier");
            }
            releaseSha256 = Objects.requireNonNull(releaseSha256, "releaseSha256").trim().toLowerCase(Locale.ROOT);
            if (!releaseSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("releaseSha256 must be canonical SHA-256");
            }
        }
    }
}
