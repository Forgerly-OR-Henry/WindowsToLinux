package gold.debug.windowstolinux.app.ui.control;

import java.util.concurrent.CompletionStage;

/** Asynchronous departure boundary for non-secret card drafts. / 非秘密卡片草稿的异步离开边界。 */
public interface PendingCardEdits {
    /** Flushes and ends the current editing session. / 补存并结束当前编辑会话。 */
    CompletionStage<Boolean> saveBeforeLeave();

    /** Reports drafts that still need storage. / 报告仍需保存的草稿。 */
    boolean hasPendingEdits();
}
