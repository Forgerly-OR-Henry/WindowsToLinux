package gold.debug.windowstolinux.shared.model.lifecycle;

import java.util.Objects;

/**
 * Exact existing unit or immutable container identity, bound to its scanned fingerprint. / 绑定扫描指纹的精确 unit 或不可变容器身份。
 *
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param identity identity / 身份
 * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
 */
public record ExternalApplicationTarget(ExternalApplicationKind kind, String identity, String fingerprint) {
    /**
     * Rejects command notation and incomplete identities. / 拒绝命令表示及不完整身份。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param identity identity / 身份
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ExternalApplicationTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identity, "identity");
        if (kind == ExternalApplicationKind.DOCKER
                ? !identity.matches("[a-f0-9]{64}")
                : !identity.matches("[A-Za-z0-9_][A-Za-z0-9_.:@\\\\-]{0,240}\\.service")
                        || identity.contains("@.service"))
            throw new IllegalArgumentException("invalid external application identity");
        if (!Objects.requireNonNull(fingerprint, "fingerprint").matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid external application fingerprint");
    }

    /**
     * Stable runtime lookup key on one server. / 单服务器上的稳定运行时查询键。
     *
     * @return key text / 键文本
     */
    public String key() {
        return kind.name() + "/" + identity;
    }
}
