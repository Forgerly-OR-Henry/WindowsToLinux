package gold.debug.windowstolinux.shared.config.secretref;

import java.util.Objects;

/**
 * An opaque reference to one immutable application-secret revision; it never contains a secret value.
 *
 * <p>一个指向不可变应用秘密修订的透明引用；它绝不包含秘密值。
 *
 * @param identifier the stable secret identifier / 稳定的秘密标识
 * @param revision the immutable positive revision / 不可变正修订号
 */
public record SecretReference(String identifier, long revision) {
    /**
     * Creates a {@code SecretReference} instance.
     *
     * <p>创建 {@code SecretReference} 实例。
     */
    public SecretReference {
        identifier = Objects.requireNonNull(identifier, "identifier").trim();
        if (!identifier.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("identifier must be a bounded lowercase identifier");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }
}
