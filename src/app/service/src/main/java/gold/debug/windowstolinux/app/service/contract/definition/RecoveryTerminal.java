package gold.debug.windowstolinux.app.service.contract.definition;

/** Exposes a selectable rescue terminal without leaking browser implementation types. / 提供可选救援终端，不泄漏浏览器实现类型。
 * @param id opaque selection identity / 不透明选择身份
 * @param description safe terminal description / 安全终端说明 */
public record RecoveryTerminal(String id, String description) {
    /** Returns the user-facing description used by the selection control. / 返回选择控件使用的用户可见说明。
     * @return safe terminal description / 安全终端说明 */
    @Override
    public String toString() {
        return description;
    }
}
