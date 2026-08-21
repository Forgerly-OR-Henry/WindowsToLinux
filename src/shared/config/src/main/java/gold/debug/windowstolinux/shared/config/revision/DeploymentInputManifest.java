package gold.debug.windowstolinux.shared.config.revision;

import gold.debug.windowstolinux.shared.config.ConfigurationException;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;

import gold.debug.windowstolinux.shared.config.secretref.SecretRevisionDigest;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Non-secret manifest binding one release to an immutable configuration and exact secret revisions.
 *
 * <p>将一个发布绑定到不可变配置和精确秘密修订的非秘密清单。
 *
 * @param configurationSha256 the immutable configuration digest / 不可变配置摘要
 * @param secrets the exact secret revision digests / 精确秘密修订摘要
 */
public record DeploymentInputManifest(String configurationSha256, List<SecretRevisionDigest> secrets) {
    /** Creates and validates the non-secret release-input manifest. / 创建并校验非秘密发布输入清单。 */
    public DeploymentInputManifest {
        configurationSha256 = Objects.requireNonNull(configurationSha256, "configurationSha256");
        if (!configurationSha256.matches("[0-9a-f]{64}")) {
            throw ConfigurationException.create(ConfigurationFailureType.HASH_INVALID, "A configuration manifest hash must use canonical lowercase SHA-256");
        }
        secrets = List.copyOf(Objects.requireNonNull(secrets, "secrets"));
        if (secrets.size() > 32 || secrets.stream().map(SecretRevisionDigest::reference).distinct().count() != secrets.size()) {
            throw ConfigurationException.create(ConfigurationFailureType.SIZE_LIMIT_EXCEEDED, "Secret revisions must be unique and within the configured bound");
        }
        Set<String> environmentNames = new HashSet<>();
        for (SecretRevisionDigest secret : secrets) {
            if (!environmentNames.add(environmentName(secret.reference().identifier()))) {
                throw ConfigurationException.create(ConfigurationFailureType.SECRET_COLLISION, "Secret identifiers collide after environment-name normalization");
            }
        }
    }

    /** Maps one public identifier to its stable secret-file environment variable. / 将公开标识映射为稳定的秘密文件环境变量。 */
    public static String environmentName(String identifier) {
        String normalized = Objects.requireNonNull(identifier, "identifier").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_");
        return "WINDOWSTOLINUX_SECRET_" + normalized + "_FILE";
    }
}
