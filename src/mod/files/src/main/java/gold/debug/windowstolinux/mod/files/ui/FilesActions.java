package gold.debug.windowstolinux.mod.files.ui;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import javax.swing.*;

/** Shares toolbar, popup and actual keyboard actions. / 共享工具栏、菜单及实际键盘动作。 */
final class FilesActions {
    /** Stable shared original actions. / 稳定共享的原动作。 */
    private final LinkedHashMap<String, Action> actions = new LinkedHashMap<>();

    /** Root action target. / 根动作目标。 */
    private final FilesWorkspace workspace;

    /** Current catalog. / 当前文案目录。 */
    private final FilesMessages messages;

    /** Toolbar's actual scroll container. / 工具栏实际滚动容器。 */
    private JScrollPane toolbar;

    /** Creates each behavior exactly once. / 每项行为仅创建一次。
     * @param workspace action target / 动作目标
     * @param messages current catalog / 当前文案目录 */
    FilesActions(FilesWorkspace workspace, FilesMessages messages) {
        this.workspace = workspace;
        this.messages = messages;
        add("copy", () -> workspace.commands().clip(false));
        add("cut", () -> workspace.commands().clip(true));
        add("paste", workspace.commands()::paste);
        add("folder", () -> workspace.commands().createItem(true));
        add("file", () -> workspace.commands().createItem(false));
        add("rename", workspace.commands()::rename);
        add("delete", workspace.commands()::delete);
        add("refresh", workspace.commands()::refresh);
    }

    /** Builds one guarded shared action. / 创建一个受保护共享动作。
     * @param key stable resource key / 稳定资源键
     * @param work exact behavior / 精确行为 */
    private void add(String key, Runnable work) {
        actions.put(key, new AbstractAction(messages.text(key)) {
            /** Runs only when the author root permits input. / 仅在作者根允许输入时执行。
             * @param event original action event / 原动作事件 */
            @Override
            public void actionPerformed(ActionEvent event) {
                if (isEnabled() && workspace.available()) work.run();
            }
        });
    }

    /** Creates a compact toolbar from shared actions. / 从共享动作创建紧凑工具栏。
     * @return toolbar / 工具栏 */
    JComponent toolbar() {
        var bar = new JToolBar();
        bar.setFloatable(false);
        actions.forEach((key, action) -> {
            var button = bar.add(action);
            button.setName("files.action." + key);
        });
        toolbar = new JScrollPane(bar, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
            /** Reserves full toolbar and scrollbar heights using current scaled components. / 按当前缩放控件保留完整工具栏及滚动条高度。
             * @return preferred size including actual borders and horizontal scrollbar / 包含实际边框及水平滚动条的首选尺寸 */
            @Override
            public java.awt.Dimension getPreferredSize() {
                var size = super.getPreferredSize();
                var edge = getInsets();
                var viewportEdge = getViewportBorder() == null ? new java.awt.Insets(0, 0, 0, 0) : getViewportBorder().getBorderInsets(this);
                size.height = bar.getPreferredSize().height + getHorizontalScrollBar().getPreferredSize().height
                    + edge.top + edge.bottom + viewportEdge.top + viewportEdge.bottom;
                return size;
            }
        };
        return toolbar;
    }

    /** Creates popup entries from the same original actions. / 从相同原动作创建菜单项。
     * @return shared-action menu / 共享动作菜单 */
    JPopupMenu popup() {
        var popup = new JPopupMenu();
        actions.values().forEach(popup::add);
        return popup;
    }

    /** Installs real key bindings on the original table. / 在原表格安装实际键绑定。
     * @param table target pane table / 目标栏表格 */
    void bind(JTable table) {
        String[] keys = {"copy", "cut", "paste", "rename", "delete", "refresh"};
        KeyStroke[] strokes = {KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK), KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)};
        for (int index = 0; index < keys.length; index++) {
            table.getInputMap(JComponent.WHEN_FOCUSED).put(strokes[index], keys[index]);
            table.getActionMap().put(keys[index], actions.get(keys[index]));
        }
    }

    /** Applies labels and context availability uniformly. / 统一应用标签及上下文可用状态。 */
    void update() {
        boolean ready = workspace.available();
        if (toolbar != null) {
            toolbar.setWheelScrollingEnabled(ready);
            toolbar.getHorizontalScrollBar().setEnabled(ready);
        }
        var selected = workspace.active().selected();
        boolean supported = !selected.isEmpty() && selected.stream().noneMatch(info -> info.kind() == gold.debug.windowstolinux.appapi.file.model.FileInfo.Kind.OTHER);
        boolean directory = workspace.active().directory() != null;
        actions.forEach((key, action) -> {
            action.putValue(Action.NAME, messages.text(key));
            action.setEnabled(ready && switch (key) {
                case "copy", "cut", "delete" -> supported;
                case "rename" -> supported && selected.size() == 1;
                case "paste" -> directory && workspace.hasClipboard();
                default -> directory;
            });
        });
    }
}
