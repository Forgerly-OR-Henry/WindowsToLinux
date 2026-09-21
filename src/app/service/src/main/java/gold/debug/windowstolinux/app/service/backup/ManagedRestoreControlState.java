package gold.debug.windowstolinux.app.service.backup;

/**
 * Desktop control-plane adoption state after a verified remote restore. / 远端恢复验证后的桌面控制面接管状态。
 */
public enum ManagedRestoreControlState {
    /**
     * Exact graph and release bindings were persisted. / 已持久化精确图及发布绑定。
     */
    UPDATED,
    /**
     * A retained source graph remains authoritative until manual migration finalization. / 在人工迁移收尾前保留的源图仍具权威性。
     */
    DEFERRED_SOURCE_RETAINED,
    /**
     * Remote activation succeeded but local persistence failed. / 远端激活成功但本地持久化失败。
     */
    FAILED
}
