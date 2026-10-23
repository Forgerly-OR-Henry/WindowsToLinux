package gold.debug.windowstolinux.mod.deployment.service.core.model.secret;

import java.util.Objects;

/** Non-secret selectable revision and its recorded purpose. / 不含明文的可选修订及其已记录用途。
 * @param reference exact immutable business revision / 精确不可变业务修订
 * @param purpose original recorded purpose / 原始记录用途 */
public record DeploymentSecretSelection(DeploymentSecretRef reference, String purpose) {
    /** Requires an actual purpose rather than guessing from an identifier. / 要求实际用途而非从标识猜测。
     * @param reference exact business revision / 精确业务修订
     * @param purpose original purpose / 原始用途 */
    public DeploymentSecretSelection {
        Objects.requireNonNull(reference);
        if (purpose == null || purpose.isBlank())
            throw new IllegalArgumentException("Selectable secret purpose is missing");
    }
}
