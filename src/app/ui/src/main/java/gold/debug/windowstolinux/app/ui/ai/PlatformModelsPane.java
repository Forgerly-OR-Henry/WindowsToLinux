package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;

import java.awt.*;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** Three model views sharing one retained inventory and ordering sessions. / 三种模型视图共用清单及排序会话。 */
public final class PlatformModelsPane extends JPanel implements AutoCloseable, PendingCardEdits {
    private final AiModelListPane inventory;

    private boolean locked = true, closed, editing;

    /** Current editor is revoked with the page, independently of accepted saves. / 当前编辑器随页面撤销，已接纳保存独立完成。 */
    private PlatformModelDialog editor;

    public PlatformModelsPane(Window owner, PlatformModelService service, DesktopPartFactory c, PageMessagePresenter messages) {
        super(new BorderLayout());
        setName("platform.models");
        c.stylePage(this);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        inventory = new AiModelListPane(service, c, messages, existing -> edit(owner, service, c, messages, existing),
            model -> new ModelDetailsPane(service, model, c, messages,
                () -> edit(owner, service, c, messages, model), this::collapse, this::refresh));
        add(inventory);
    }

    private void edit(Window owner, PlatformModelService service, DesktopPartFactory c, PageMessagePresenter messages, AiModelInfo existing) {
        if (locked || closed || editing) return;
        if (existing != null && !service.checks(existing.profile().id()).isEmpty()) return;
        editing = true;
        inventory.savePending().whenComplete((ok, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(ok) || locked || closed) {
                editing = false;
                return;
            }
            DesktopTaskRunner.run(() -> existing == null ? OptionalInt.empty() : service.outputLimit(existing.profile().id()), limit -> {
                editing = false;
                if (!closed && !locked) {
                    var dialog = new PlatformModelDialog(owner, service, c, messages, existing, limit, this::refresh);
                    editor = dialog;
                    try {
                        dialog.setVisible(true);
                    } finally {
                        dialog.dispose();
                        if (editor == dialog) editor = null;
                    }
                }
            }, problem -> {
                editing = false;
                if (!closed) DesktopDialogs.showMessageDialog(owner, messages.safe(problem));
            });
        });
    }

    public static JDialog preview(Window owner, DesktopPartFactory c, PageMessagePresenter messages) {
        return PlatformModelDialog.preview(owner, c, messages);
    }

    private void collapse() {
        inventory.collapse();
    }

    private void refresh() {
        if (!locked && !closed) inventory.refresh();
    }

    public void setLocked(boolean value) {
        locked = value;
        inventory.setLocked(value);
        if (!value) refresh();
    }

    public void localize() {
        inventory.localize();
    }

    @Override
    public boolean hasPendingEdits() {
        return inventory.hasPendingEdits();
    }

    @Override
    public CompletionStage<Boolean> saveBeforeLeave() {
        return inventory.saveBeforeLeave();
    }

    @Override
    public void close() {
        closed = true;
        if (editor != null) editor.dispose();
        inventory.close();
    }
}
