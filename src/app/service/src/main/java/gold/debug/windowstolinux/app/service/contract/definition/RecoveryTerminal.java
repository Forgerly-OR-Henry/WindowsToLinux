package gold.debug.windowstolinux.app.service.contract.definition;

/** Exposes a selectable rescue terminal without leaking browser implementation types. / 提供可选救援终端，不泄漏浏览器实现类型。
 * @param id opaque selection identity / 不透明选择身份
 * @param description safe terminal description / 安全终端说明 */
public record RecoveryTerminal(String id, String description) {
    /** Converts a browser selection into a UI-only value. / 将浏览器选择转换为仅供 UI 的值。
     * @param target original browser selection / 原浏览器选择
     * @return UI selection / UI 选择 */
    public static RecoveryTerminal from(gold.debug.windowstolinux.app.linux.recovery.TerminalTarget target) {
        return new RecoveryTerminal(target.id(), target.description());
    }

    /** Returns the user-facing description used by the selection control. / 返回选择控件使用的用户可见说明。
     * @return safe terminal description / 安全终端说明 */
    @Override
    public String toString() {
        return description;
    }
}
