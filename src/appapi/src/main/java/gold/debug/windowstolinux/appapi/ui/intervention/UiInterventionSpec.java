package gold.debug.windowstolinux.appapi.ui.intervention;

/** Non-secret presentation of one original-task decision. / 单次原任务决定的非秘密展示信息。
 * @param title host window title / 宿主窗口标题
 * @param summary non-secret question context / 非秘密问题背景
 * @param acceptLabel explicit acceptance caption / 明确接受按钮文字
 * @param keepOpen keep the same window while original checks continue / 原检查继续期间保留同一窗口 */
public record UiInterventionSpec(String title, String summary, String acceptLabel, boolean keepOpen) {
    /** Validates bounded non-secret presentation fields. / 校验有界非秘密展示字段。
     * @param title host window title / 宿主窗口标题
     * @param summary question context / 问题背景
     * @param acceptLabel acceptance caption / 接受按钮文字
     * @param keepOpen whether original checks continue in this window / 是否在同一窗口继续原检查 */
    public UiInterventionSpec {
        title = text(title, 160, false);
        summary = text(summary, 4000, true);
        acceptLabel = text(acceptLabel, 80, false);
    }

    /** Creates a decision that closes its window after acceptance. / 创建接受后关闭窗口的决定。
     * @param title host window title / 宿主窗口标题
     * @param summary question context / 问题背景
     * @param acceptLabel acceptance caption / 接受按钮文字 */
    public UiInterventionSpec(String title, String summary, String acceptLabel) {
        this(title, summary, acceptLabel, false);
    }

    /** Rejects oversized or empty required labels. / 拒绝超长或缺失的必需文字。
     * @param value non-secret display text / 非秘密展示文字
     * @param limit maximum character count / 最大字符数
     * @param empty whether empty text is permitted / 是否允许空文字
     * @return unchanged valid text / 未变的有效文字 */
    private static String text(String value, int limit, boolean empty) {
        if (value == null || (!empty && value.isBlank()) || value.length() > limit || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid intervention presentation");
        return value;
    }
}
