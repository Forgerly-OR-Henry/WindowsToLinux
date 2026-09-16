package gold.debug.windowstolinux.shared.linux.protocol;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Non-secret execution binding; it contains no configuration definitions or revision history. / 不含配置定义和历史的非秘密执行绑定。 */
public record RemoteDeploymentInputs(String configurationSha256, List<SecretDigest> secrets) {
    /** Rejects ambiguous wire bindings before any helper call. / 在调用 helper 前拒绝歧义绑定。 */
    public RemoteDeploymentInputs {
        Objects.requireNonNull(configurationSha256, "configurationSha256");
        if (!configurationSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid configuration digest");
        secrets = Objects.requireNonNull(secrets, "secrets").stream()
                .sorted(Comparator.comparing(SecretDigest::identifier).thenComparingLong(SecretDigest::revision)).toList();
        if (secrets.size() > 32 || secrets.stream().map(value -> value.identifier().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_")).distinct().count() != secrets.size())
            throw new IllegalArgumentException("secret bindings must be bounded and unambiguous");
    }

    /** Exact public identity and content metadata of one secret payload. / 单个秘密载荷的精确公开身份与内容元数据。 */
    public record SecretDigest(String identifier, long revision, String sha256, int byteCount) {
        /** Validates only execution metadata, without loading a secret. / 仅校验执行元数据，不读取秘密。 */
        public SecretDigest {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(sha256, "sha256");
            if (!identifier.matches("[a-z0-9][a-z0-9._-]{0,63}") || revision < 1
                    || !sha256.matches("[0-9a-f]{64}") || byteCount < 1 || byteCount > 65536)
                throw new IllegalArgumentException("invalid secret metadata");
        }
    }
}
