package gold.debug.windowstolinux.api.server;

/**
 * Confirmed or still unknown business commit result. / 业务提交已经核实或仍然未知的结果。
 */
public enum ReferenceResolution {
    /**
     * Reconciliation confirmed that the business reference exists. / 核对确认业务引用存在。
     */
    REFERENCED,
    /**
     * Reconciliation confirmed that the business reference is absent. / 核对确认业务引用不存在。
     */
    NOT_REFERENCED,
    /**
     * The effect or completion cannot yet be confirmed. / 副作用或完成状态尚无法确认。
     */
    UNKNOWN
}
