package gold.debug.windowstolinux.appapi.mod.service;

/** Host observation of one declared provider; calls still require live admission. / 宿主对一个声明提供方的观察，调用仍须实时准入。
 */
public enum AvailabilityState {
    /** Provider services are published. / 提供方服务已发布。
     */
    AVAILABLE,
    /** No selected provider exists. / 不存在已选择提供方。
     */
    MISSING,
    /** Provider selection is disabled. / 提供方选择未启用。
     */
    DISABLED,
    /** Selected provider is outside the declared range. / 选中提供方不在声明范围内。
     */
    INCOMPATIBLE,
    /** Selected provider has no active published binding. / 选中提供方没有已发布的活动绑定。
     */
    START_FAILED,
    /** Original recovery blocks normal activation. / 原恢复阻止普通启用。
     */
    RECOVERY_BLOCKED,
    /** Retained intent requires restart before new calls. / 保留意图要求重启后才能新调用。
     */
    RESTART_PENDING
}
