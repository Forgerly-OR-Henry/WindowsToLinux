package gold.debug.windowstolinux.mod.deployment.api;

/** Durable collection or restore handoff phase. / 持久采集或恢复交接阶段。 */
public enum DeploymentHandoffState {
    /** Reviewed without remote effect. / 已审阅且无远端副作用。 */ REVIEWED,
    /** Source maintenance is active. / 来源维护中。 */ MAINTENANCE,
    /** Source artifacts have been collected. / 来源产物已采集。 */ COLLECTED,
    /** Target candidate is prepared. / 目标候选已准备。 */ PREPARED,
    /** Target activation may have begun. / 目标激活可能已开始。 */ ACTIVATING,
    /** Target is verified active. / 目标已验证激活。 */ ACTIVE,
    /** Explicit compensation is underway. / 明确补偿正在进行。 */ COMPENSATING,
    /** Handoff cleanup is complete. / 交接清理已完成。 */ FINISHED,
    /** Outcome must be reconciled before a new effect. / 新副作用前必须核对未知结果。 */ UNKNOWN
}
