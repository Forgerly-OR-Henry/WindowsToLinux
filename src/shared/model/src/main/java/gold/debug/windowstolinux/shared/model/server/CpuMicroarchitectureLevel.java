package gold.debug.windowstolinux.shared.model.server;

/**
 * The highest cumulative x86-64 microarchitecture level confirmed by the target runtime linker.
 *
 *  <p>目标运行时链接器确认的最高累积 x86-64 微架构级别。
 */
public enum CpuMicroarchitectureLevel {
    /**
     * No cumulative level was confirmed. / 未确认累积级别。
     */
    UNKNOWN(0),
    /**
     * Baseline x86-64. / 基线 x86-64。
     */
    X86_64_V1(1),
    /**
     * Cumulative x86-64-v2. / 累积 x86-64-v2。
     */
    X86_64_V2(2),
    /**
     * Cumulative x86-64-v3. / 累积 x86-64-v3。
     */
    X86_64_V3(3),
    /**
     * Cumulative x86-64-v4. / 累积 x86-64-v4。
     */
    X86_64_V4(4);

    /**
     * Evidence-backed support level.
     * <p>证据支撑的支持等级。
     */
    private final int level;

    /**
     * Binds the supplied dependencies and state for cpu microarchitecture level.
     * <p>为CpuMicroarchitecture级别绑定传入的依赖及状态。
     *
     * @param level evidence-backed support level / 证据支撑的支持等级
     */
    CpuMicroarchitectureLevel(int level) {
        this.level = level;
    }

    /**
     * Returns whether this confirmed level includes the required cumulative level. / 返回已确认级别是否包含所需累积级别。
     *
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @return true when returns whether this confirmed level includes the required cumulative level, false otherwise / 返回已确认级别是否包含所需累积级别时为 true，否则为 false
     */
    public boolean supports(CpuMicroarchitectureLevel required) {
        return required != null && level >= required.level;
    }
}
