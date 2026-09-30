package gold.debug.windowstolinux.app.ui.setting;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.ui.*;

/** Mounts isolated author previews in host-owned dialogs. / 在宿主持有的对话框中挂载隔离作者预览。 */
public final class UiDebugPreviewHost implements ModPageRegistry.PageHost {
    /** Original owner registration and cleanup. / 原所有者登记及清理。 */
    private final ModPageRegistry pages;

    /** Current gallery owner, never a runtime capability. / 当前目录所有者；不是运行时能力。 */
    private final Supplier<Window> owner;

    /** Actual live preview windows. / 实际存活的预览窗口。 */
    private final Map<ModPageRegistry.PageKey, JDialog> windows = new LinkedHashMap<>();

    /** Binds only local UI ownership. / 仅绑定本地UI归属。
     * @param pages original author registry / 原作者登记
     * @param owner current gallery / 当前目录 */
    public UiDebugPreviewHost(ModPageRegistry pages, Supplier<Window> owner) {
        this.pages = pages;
        this.owner = owner;
    }

    /** Opens one exact freshly created author view. / 打开一个精确新建作者视图。
     * @param key original identity / 原身份
     * @param spec exact metadata / 精确元数据
     * @param component isolated component / 隔离组件 */
    @Override
    public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
        var dialog = new JDialog(owner.get(), spec.defaultTitle(), java.awt.Dialog.ModalityType.MODELESS);
        dialog.setName("debug.preview." + key.ownerId() + "." + key.pageId());
        dialog.setContentPane(new JScrollPane(component));
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            /** Releases the author before disposing its host dialog. / 先释放作者再销毁宿主对话框。
             * @param event original close request / 原关闭请求 */
            @Override
            public void windowClosing(WindowEvent event) {
                try {
                    pages.closeView(key);
                } catch (gold.debug.windowstolinux.appapi.task.ApiException failure) {
                    dialog.getRootPane().putClientProperty("debug.cleanupProblem", failure.problem());
                }
            }
        });
        windows.put(key, dialog);
        dialog.setSize(760, 620);
        dialog.setLocationRelativeTo(owner.get());
        dialog.setVisible(true);
    }

    /** Focuses the same existing preview. / 聚焦同一既有预览。
     * @param key original identity / 原身份 */
    @Override
    public void focus(ModPageRegistry.PageKey key) {
        var dialog = windows.get(key);
        if (dialog != null)
            dialog.toFront();
    }

    /** Disposes only a view whose author cleanup succeeded. / 仅销毁作者清理已成功的视图。
     * @param key original identity / 原身份 */
    @Override
    public void remove(ModPageRegistry.PageKey key) {
        var dialog = windows.remove(key);
        if (dialog != null)
            dialog.dispose();
    }

    /** Leaves author environment changes with the original registry. / 将作者环境变化留给原登记处理。
     * @param environment current immutable environment / 当前不可变环境 */
    @Override
    public void environment(UiEnvironment environment) {
        for (var dialog : windows.values()) {
            if (environment.locked())
                dialog.setVisible(false);
            SwingUtilities.updateComponentTreeUI(dialog);
        }
    }
}
