package gold.debug.windowstolinux.appapi.ui.extension;

/** Observable registration lifetime. / 可观测的扩展登记寿命。 */
public enum UiExtensionState {
    /** Waiting for publication or a mount. / 等待发布或挂载。 */
    WAITING,
    /** At least one live mount. / 至少一个有效挂载。 */
    MOUNTED,
    /** Rejected callback or retained cleanup. / 回调失败或保留清理。 */
    FAILED,
    /** Fully revoked and released. / 已撤销并释放。 */
    CLOSED
}
