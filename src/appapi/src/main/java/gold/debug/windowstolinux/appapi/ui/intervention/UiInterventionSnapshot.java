package gold.debug.windowstolinux.appapi.ui.intervention;

import java.util.Optional;

/** Display-only request count and actual progress for one original task. / 单个原任务的纯展示请求数及真实进度。
 * @param pendingCount unresolved request count / 未决请求数
 * @param fraction actual reported progress, absent when unknown / 实际上报进度，未知时为空 */
public record UiInterventionSnapshot(int pendingCount, Optional<Double> fraction) {
    /** Checks counts and actual progress bounds. / 检查计数及真实进度范围。
     * @param pendingCount unresolved requests / 未决请求
     * @param fraction actual reported progress / 实际上报进度 */
    public UiInterventionSnapshot {
        if (pendingCount < 0) throw new IllegalArgumentException("Negative intervention count");
        java.util.Objects.requireNonNull(fraction);
        if (fraction.isPresent() && (!Double.isFinite(fraction.get()) || fraction.get() < 0 || fraction.get() > 1))
            throw new IllegalArgumentException("Invalid progress fraction");
    }

    /** Creates an observation with unknown progress. / 创建进度未知的观察。
     * @param pendingCount unresolved requests / 未决请求 */
    public UiInterventionSnapshot(int pendingCount) {
        this(pendingCount, Optional.empty());
    }
}
