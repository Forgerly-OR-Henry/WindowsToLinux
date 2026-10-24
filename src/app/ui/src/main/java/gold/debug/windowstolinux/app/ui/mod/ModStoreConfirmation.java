package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;
import java.awt.Dialog;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.LocalizedMessage;

/** Owns an explicitly accepted, revocable review of the entire current plan. / 持有明确接纳且可撤销的完整当前计划审阅。 */
final class ModStoreConfirmation {
    /** Current themed control factory. / 当前主题控件工厂。 */
    private final DesktopPartFactory components;

    /** Current localized display support. / 当前本地化展示支持。 */
    private final PageMessagePresenter messages;

    /** Exact currently owned dialog. / 精确当前所属对话框。 */
    private JDialog dialog;

    /** Acceptance of this one review only. / 仅对此次审阅的接纳。 */
    private boolean accepted;

    /** Captures display collaborators without opening a dialog. / 捕获展示协作者，不打开对话框。
     * @param components current controls / 当前控件
     * @param messages current messages / 当前消息 */
    ModStoreConfirmation(DesktopPartFactory components, PageMessagePresenter messages) {
        this.components = components;
        this.messages = messages;
    }

    /** Presents the full current plan and returns only an explicit acceptance. / 展示完整当前计划，仅返回明确接纳。
     * @param parent original store view / 原商店视图
     * @param review complete immutable plan display / 完整不可变计划展示
     * @return whether this exact review was accepted / 是否接纳此次精确审阅 */
    boolean show(JComponent parent, JComponent review) {
        revoke();
        accepted = false;
        var owned = new JDialog(SwingUtilities.getWindowAncestor(parent), Dialog.ModalityType.APPLICATION_MODAL);
        dialog = owned;
        owned.setName("store.confirmation");
        LocaleBinding.title(owned, LocalizedMessage.of("store.confirmTitle"), messages.catalog());
        var content = components.pagePanel();
        content.setLayout(new BorderLayout(8, 8));
        var notice = new JTextArea(2, 32);
        notice.setEditable(false);
        notice.setLineWrap(true);
        notice.setWrapStyleWord(true);
        messages.bind(notice, "store.confirm");
        content.add(notice, BorderLayout.NORTH);
        content.add(review);
        var buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        var cancel = messages.bind(components.secondaryButton(""), "button.cancel");
        cancel.setName("store.confirm.cancel");
        cancel.addActionListener(event -> revoke());
        var proceed = messages.bind(components.primaryButton(""), "store.install");
        proceed.setName("store.confirm.accept");
        proceed.addActionListener(event -> {
            if (dialog == owned) {
                accepted = true;
                owned.dispose();
            }
        });
        buttons.add(cancel);
        buttons.add(proceed);
        DesktopDialogs.install(owned, content, buttons, 760, 640);
        try {
            owned.setVisible(true);
            return accepted;
        } finally {
            owned.dispose();
            if (dialog == owned) dialog = null;
        }
    }

    /** Revokes an unsubmitted review without affecting any accepted batch. / 撤销未提交审阅，不影响任何已接纳批次。 */
    void revoke() {
        accepted = false;
        if (dialog != null) dialog.dispose();
        dialog = null;
    }
}
