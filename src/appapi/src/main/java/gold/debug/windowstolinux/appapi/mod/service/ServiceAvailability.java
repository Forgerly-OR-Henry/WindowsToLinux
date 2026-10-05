package gold.debug.windowstolinux.appapi.mod.service;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/** Small availability observation without loading classes or retaining resources. / 不加载类或保留资源的小型可用性观察。
 * @param providerId explicitly declared provider / 明确声明提供方身份
 * @param state current host observation / 当前宿主可用性观察
 * @param selectedVersion observed selected release, if one exists / 存在时观察的选中提供方发布
 * @param problem diagnostic for unavailable service, when available / 可选有界不可用诊断
 */
public record ServiceAvailability(String providerId, AvailabilityState state, Optional<String> selectedVersion,
                                  Optional<Problem> problem) {
    /** Validates and copies the exact original boundary identity. / 校验并复制精确原边界身份。
     * @param providerId explicitly declared provider identity / 明确声明提供方身份
     * @param state current host availability observation / 当前宿主可用性观察
     * @param selectedVersion observed selected provider release if present / 存在时观察的选中提供方发布
     * @param problem optional bounded unavailable diagnostic / 可选有界不可用诊断
     */
    public ServiceAvailability {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Empty provider identity");
        Objects.requireNonNull(state);
        Objects.requireNonNull(selectedVersion);
        Objects.requireNonNull(problem);
    }
}
