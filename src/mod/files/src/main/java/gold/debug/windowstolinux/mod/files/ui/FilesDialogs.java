package gold.debug.windowstolinux.mod.files.ui;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Window;
import java.util.List;
import java.util.Optional;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;

/** Owns unsubmitted modal input so lock and close can revoke it. / 持有未提交的模态输入，以供锁定及关闭撤销。 */
final class FilesDialogs {
    /** Original root used solely for window ownership. / 仅用于窗口归属的原根。 */
    private final JComponent root;

    /** Shared live catalog. / 共享实时文案目录。 */
    private final FilesMessages messages;

    /** Current unsubmitted dialog, if any. / 当前未提交对话框（若有）。 */
    private JDialog current;

    /** Original dialog title identity. / 原对话框标题身份。 */
    private String currentKey;

    /** Captures author ownership. / 捕获作者归属。
     * @param root original root / 原根
     * @param messages current catalog / 当前文案目录 */
    FilesDialogs(JComponent root, FilesMessages messages) {
        this.root = root;
        this.messages = messages;
    }

    /** Collects a complete direct-child name starting empty. / 从空输入收集完整直接子项名称。
     * @param key action title key / 动作标题键
     * @return accepted input or absence / 已接受输入或空值 */
    Optional<String> name(String key) {
        var input = new JTextField(24);
        input.setName("files.dialog.name");
        var body = FilesDialogLayout.fields(prompt("fullName", 2, 34), input);
        return show(key, body) ? Optional.of(input.getText()) : Optional.empty();
    }

    /** Confirms replacing only the active pane's unsubmitted path. / 确认仅替换活动栏未提交路径。
     * @return whether replacement was accepted / 是否接受替换 */
    boolean replaceLocation() {
        return show("replaceLocation", prompt("replaceLocationPrompt", 3, 34));
    }

    /** Presents the actual deletion scope before platform authorization. / 在平台授权前展示实际删除范围。
     * @param items actual selected observations / 实际所选观测
     * @return whether explicitly accepted / 是否明确接受 */
    boolean delete(List<FileInfo> items) {
        var text = new JTextArea(messages.text("deleteScope") + "\n\n" + String.join("\n", items.stream().map(i -> i.requested().path()).toList()), 8, 32);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.putClientProperty("files.dialog.key", "deleteScope");
        text.putClientProperty("files.dialog.suffix", "\n\n" + String.join("\n", items.stream().map(i -> i.requested().path()).toList()));
        return show("delete", new JScrollPane(text));
    }

    /** Collects an explicit saved server and absolute Linux path. / 收集明确的已保存服务器及绝对 Linux 路径。
     * @param servers actual saved-server results / 实际已保存服务器结果
     * @return accepted explicit destination / 已接受明确位置 */
    Optional<FilePath> remote(List<ServerInfo> servers) {
        var choices = new JComboBox<ServerInfo>(servers.toArray(ServerInfo[]::new));
        choices.setSelectedIndex(-1);
        choices.setRenderer(new DefaultListCellRenderer() {
            /** Displays non-secret server names. / 显示不含秘密的服务器名称。
             * @param list original list / 原列表
             * @param value original value / 原值
             * @param index row / 行
             * @param selected selection / 选择状态
             * @param focus focus / 焦点
             * @return rendered name / 已渲染名称 */
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof ServerInfo info ? info.name() : "", index, selected, focus);
            }
        });
        var path = new JTextField(24);
        choices.setName("files.dialog.server");
        path.setName("files.dialog.remotePath");
        var body = FilesDialogLayout.fields(label("remote"), choices, label("absolutePath"), path);
        if (!show("remote", body) || !(choices.getSelectedItem() instanceof ServerInfo server)) return Optional.empty();
        return Optional.of(FilePath.remote(server.ref(), path.getText()));
    }

    /** Offers only currently observed links, unchecked by default. / 仅提供当前已观测链接，默认均不勾选。
     * @param links current two-pane observations / 当前两栏观测
     * @return explicit included links or cancelled input / 明确纳入的链接或已取消输入 */
    Optional<List<FileInfo>> links(List<FileInfo> links) {
        var body = new JPanel(new BorderLayout());
        body.putClientProperty("files.dialog.sectionGap", 12);
        var scope = prompt("linkScope", 3, 34);
        body.putClientProperty("files.dialog.listScope", scope);
        body.add(scope, BorderLayout.NORTH);
        var rows = new JPanel(new java.awt.GridBagLayout());
        rows.putClientProperty("files.dialog.links", true);
        var boxes = links.stream().map(info -> new JCheckBox(info.requested().key())).toList();
        for (int index = 0; index < boxes.size(); index++) addLink(rows, boxes.get(index), index);
        body.add(rows, BorderLayout.CENTER);
        if (!show("move", body)) return Optional.empty();
        var included = new java.util.ArrayList<FileInfo>();
        for (int index = 0; index < boxes.size(); index++)
            if (boxes.get(index).isSelected()) included.add(links.get(index));
        return Optional.of(List.copyOf(included));
    }

    /** Keeps each original checkbox beside a naturally wrapping full path. / 在原复选框旁保留自然换行的完整路径。
     * @param rows original link form / 原链接表单
     * @param box original selection model and full key / 原选择模型及完整位置键
     * @param index original observation order / 原观测顺序 */
    private void addLink(JPanel rows, JCheckBox box, int index) {
        String key = box.getText();
        box.setText("");
        box.setToolTipText(key);
        box.getAccessibleContext().setAccessibleName(key);
        var path = new JTextArea(key, 0, 24) {
            /** Measures every complete wrapped line at the row's current width. / 按当前行宽测量全部换行内容。
             * @return width-aware natural text size / 随宽度变化的自然文本尺寸 */
            @Override
            public java.awt.Dimension getPreferredSize() {
                var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
                if (viewport == null || viewport.getExtentSize().width <= 0) return super.getPreferredSize();
                int width = Math.max(1, viewport.getExtentSize().width - box.getPreferredSize().width - FilesDialogLayout.scale(8));
                var insets = getInsets();
                var view = getUI().getRootView(this);
                view.setSize(Math.max(1, width - insets.left - insets.right), Float.MAX_VALUE);
                int height = (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + insets.top + insets.bottom;
                return new java.awt.Dimension(width, height);
            }

            /** Keeps the same wrapped height when GridBag needs a narrower minimum width. / GridBag 使用更窄最小宽度时保留相同换行高度。
             * @return shrinkable width and complete natural height / 可收缩宽度及完整自然高度 */
            @Override
            public java.awt.Dimension getMinimumSize() {
                return new java.awt.Dimension(0, getPreferredSize().height);
            }
        };
        path.setName("files.dialog.linkPath");
        path.putClientProperty("files.dialog.linkKey", key);
        path.setEditable(false);
        path.setFocusable(false);
        path.setLineWrap(true);
        path.setWrapStyleWord(false);
        var position = new java.awt.GridBagConstraints();
        position.gridx = 0;
        position.gridy = index;
        position.anchor = java.awt.GridBagConstraints.NORTHWEST;
        position.insets.bottom = FilesDialogLayout.scale(8);
        rows.add(box, position);
        position.gridx = 1;
        position.weightx = 1;
        position.fill = java.awt.GridBagConstraints.HORIZONTAL;
        position.insets.left = FilesDialogLayout.scale(8);
        rows.add(path, position);
        box.putClientProperty("files.dialog.revealTarget", path);
        path.addMouseListener(new java.awt.event.MouseAdapter() {
            /** Toggles the original checkbox when its visible label is clicked. / 点击可见标签时切换原复选框。
             * @param event original pointer event / 原指针事件 */
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && box.isEnabled()) {
                    box.doClick(0);
                    box.requestFocusInWindow();
                }
            }
        });
    }

    /** Mounts one owned dialog and preserves cancellation as absence. / 挂载一个所属对话框并将取消保留为空值。
     * @param key localized title key / 本地化标题键
     * @param body actual input controls / 实际输入控件
     * @return explicit confirmation / 明确确认 */
    private boolean show(String key, JComponent body) {
        Window owner = SwingUtilities.getWindowAncestor(root);
        var dialog = new JDialog(owner, messages.text(key), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("files.dialog");
        current = dialog;
        currentKey = key;
        boolean[] accepted = {false};
        var ok = new JButton(messages.text("ok"));
        var cancel = new JButton(messages.text("cancel"));
        ok.setName("files.dialog.ok");
        cancel.setName("files.dialog.cancel");
        ok.putClientProperty("files.dialog.key", "ok");
        cancel.putClientProperty("files.dialog.key", "cancel");
        ok.addActionListener(event -> {
            accepted[0] = true;
            dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        FilesDialogLayout.install(dialog, body, ok, cancel);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        try {
            dialog.setVisible(true);
            return accepted[0];
        } finally {
            dialog.dispose();
            if (current == dialog) current = null;
        }
    }

    /** Revokes the original unsubmitted input. / 撤销原未提交输入。 */
    void revoke() {
        if (current != null) current.dispose();
    }

    /** Tags existing labels for retained-dialog language changes. / 标记已有标签以供保留对话框语言变化。
     * @param key original resource key / 原资源键
     * @return original labeled control / 原标签控件 */
    private JLabel label(String key) {
        var label = new JLabel(messages.text(key));
        label.putClientProperty("files.dialog.key", key);
        return label;
    }

    /** Retains localized prose in a wrapping, non-editable Swing text surface. / 在换行只读 Swing 文本区域保留本地化说明。
     * @param key localized message key / 本地化文案键
     * @param rows natural minimum line count / 自然最小行数
     * @param columns preferred text width / 首选文本宽度
     * @return original wrapping text surface / 原换行文本区域 */
    private JTextArea prompt(String key, int rows, int columns) {
        var text = new JTextArea(messages.text(key), rows, columns) {
            /** Measures prose against the viewport extent before parent layout assigns child widths. / 父布局分配子项宽度前先按视口实际范围测量说明。
             * @return natural width and complete wrapped height / 自然宽度及完整换行高度 */
            @Override
            public java.awt.Dimension getPreferredSize() {
                var preferred = super.getPreferredSize();
                var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
                if (viewport == null || viewport.getExtentSize().width <= 0) return preferred;
                var insets = getInsets();
                var view = getUI().getRootView(this);
                view.setSize(Math.max(1, viewport.getExtentSize().width - insets.left - insets.right), Float.MAX_VALUE);
                int height = Math.max(getRows() * getRowHeight(), (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)))
                    + insets.top + insets.bottom;
                return new java.awt.Dimension(preferred.width, height);
            }
        };
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.putClientProperty("files.dialog.key", key);
        return text;
    }

    /** Applies actual host appearance without replacing unsubmitted input. / 应用实际宿主外观，不替换未提交输入。 */
    void environmentChanged() {
        if (current == null) return;
        SwingUtilities.updateComponentTreeUI(current);
        current.setTitle(messages.text(currentKey));
        localize(current);
        FilesDialogLayout.refresh(current);
        current.repaint();
    }

    /** Updates tagged presentation controls while preserving text-field drafts. / 更新已标记展示控件并保留文本框草稿。
     * @param container original dialog subtree / 原对话框子树 */
    private void localize(java.awt.Container container) {
        for (var child : container.getComponents()) {
            if (child instanceof JComponent component && component.getClientProperty("files.dialog.key") instanceof String key) {
                String value = messages.text(key) + java.util.Objects.toString(component.getClientProperty("files.dialog.suffix"), "");
                if (component instanceof JLabel label) label.setText(value);
                else if (component instanceof AbstractButton button) button.setText(value);
                else if (component instanceof JTextArea text) text.setText(value);
            }
            if (child instanceof java.awt.Container nested) localize(nested);
        }
    }
}
