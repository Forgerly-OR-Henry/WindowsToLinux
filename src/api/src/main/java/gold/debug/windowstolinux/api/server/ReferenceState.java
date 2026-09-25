package gold.debug.windowstolinux.api.server;

/**
 * Reservation state that continues protecting unknown references. / 持续保护未知引用的预留状态。
 */
public enum ReferenceState {
    /**
     * A pending business commit still protects the referenced resource. / 待完成业务提交仍保护被引用资源。
     */
    RESERVED,
    /**
     * A confirmed business record owns the resource reference. / 已确认的业务记录持有资源引用。
     */
    ACTIVE,
    /**
     * The effect or completion cannot yet be confirmed. / 副作用或完成状态尚无法确认。
     */
    UNKNOWN
}
