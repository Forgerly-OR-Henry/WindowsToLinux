package gold.debug.windowstolinux.shared.model.server;

/**
 * The highest cumulative x86-64 microarchitecture level confirmed by the target runtime linker.
 *
 * <p>目标运行时链接器确认的最高累积 x86-64 微架构级别。
 */
public enum CpuMicroarchitectureLevel {
    /** No cumulative level was confirmed. / 未确认累积级别。 */
    UNKNOWN(0),
    /** Baseline x86-64. / 基线 x86-64。 */
    X86_64_V1(1),
    /** Cumulative x86-64-v2. / 累积 x86-64-v2。 */
    X86_64_V2(2),
    /** Cumulative x86-64-v3. / 累积 x86-64-v3。 */
    X86_64_V3(3),
    /** Cumulative x86-64-v4. / 累积 x86-64-v4。 */
    X86_64_V4(4);

    private final int level;

    CpuMicroarchitectureLevel(int level) {
        this.level = level;
    }

    /** Returns whether this confirmed level includes the required cumulative level. / 返回已确认级别是否包含所需累积级别。 */
    public boolean supports(CpuMicroarchitectureLevel required) {
        return required != null && level >= required.level;
    }
}
