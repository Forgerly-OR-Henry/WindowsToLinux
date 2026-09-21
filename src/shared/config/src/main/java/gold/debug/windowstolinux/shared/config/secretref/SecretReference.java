package gold.debug.windowstolinux.shared.config.secretref;

import gold.debug.windowstolinux.shared.config.ConfigurationException;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;

import java.util.Objects;

/**
 * An opaque reference to one immutable application-secret revision; it never contains a secret value.
 *
 *  <p>一个指向不可变应用秘密修订的透明引用；它绝不包含秘密值。
 *
 * @param identifier the stable secret identifier / 稳定的秘密标识
 * @param revision the immutable positive revision / 不可变正修订号
 */
public record SecretReference(String identifier, long revision) {
    /**
     * Validates and binds the inputs required by secret reference.
     * <p>校验并绑定秘密引用所需输入。
     *
     * @param identifier the stable secret identifier / 稳定的秘密标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SecretReference {
        identifier = Objects.requireNonNull(identifier, "identifier").trim();
        if (!identifier.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw ConfigurationException.create(ConfigurationFailureType.IDENTIFIER_INVALID, "A configuration identifier must be bounded and lowercase");
        }
        if (revision < 1) {
            throw ConfigurationException.create(ConfigurationFailureType.REVISION_INVALID, "A configuration revision must be positive");
        }
    }
}
