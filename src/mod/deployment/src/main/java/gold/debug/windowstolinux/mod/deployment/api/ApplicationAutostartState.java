package gold.debug.windowstolinux.mod.deployment.api;

import java.util.Collection;

/** Actual automatic-startup observations, including mixed component facts. / 实际自动启动观察，包括混合组件事实。 */
public enum ApplicationAutostartState {
    /** All applicable components start automatically. / 所有适用组件自动启动。 */ ENABLED,
    /** No applicable component starts automatically. / 所有适用组件均不自动启动。 */ DISABLED,
    /** Applicable components disagree. / 适用组件状态不一致。 */ PARTIALLY_ENABLED,
    /** Reviewed on-demand applications have no daemon automatic startup. / 已审阅按需应用不适用守护进程自动启动。 */ NOT_APPLICABLE,
    /** Current evidence does not establish automatic startup. / 当前依据无法确认自动启动。 */ UNKNOWN;

    /** Combines actual applicable facts without inventing historical enablement. / 合并实际适用事实，不虚构历史启用状态。
     * @param values exact component observations / 精确组件观察
     * @return conservative application observation / 保守整应用观察 */
    public static ApplicationAutostartState combine(Collection<ApplicationAutostartState> values) {
        if (values.contains(UNKNOWN))
            return UNKNOWN;
        boolean enabled = values.contains(ENABLED), disabled = values.contains(DISABLED);
        if (values.contains(PARTIALLY_ENABLED) || enabled && disabled)
            return PARTIALLY_ENABLED;
        return enabled ? ENABLED : disabled ? DISABLED : NOT_APPLICABLE;
    }
}
