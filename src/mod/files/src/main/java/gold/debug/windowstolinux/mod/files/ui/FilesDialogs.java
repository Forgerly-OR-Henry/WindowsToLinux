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
        var body = new JPanel(new BorderLayout(4, 4));
        body.add(label("fullName"), BorderLayout.NORTH);
        body.add(input, BorderLayout.CENTER);
        return show(key, body) ? Optional.of(input.getText()) : Optional.empty();
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
        var body = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        body.add(label("remote"));
        body.add(choices);
        body.add(label("absolutePath"));
        body.add(path);
        if (!show("remote", body) || !(choices.getSelectedItem() instanceof ServerInfo server)) return Optional.empty();
        return Optional.of(FilePath.remote(server.ref(), path.getText()));
    }

    /** Offers only currently observed links, unchecked by default. / 仅提供当前已观测链接，默认均不勾选。
     * @param links current two-pane observations / 当前两栏观测
     * @return explicit included links or cancelled input / 明确纳入的链接或已取消输入 */
    Optional<List<FileInfo>> links(List<FileInfo> links) {
        var body = new JPanel(new BorderLayout(4, 4));
        var scope = new JTextArea(messages.text("linkScope"), 3, 34);
        scope.setEditable(false);
        scope.setLineWrap(true);
        scope.setWrapStyleWord(true);
        scope.putClientProperty("files.dialog.key", "linkScope");
        body.add(scope, BorderLayout.NORTH);
        var rows = new JPanel(new java.awt.GridLayout(0, 1));
        var boxes = links.stream().map(info -> new JCheckBox(info.requested().key())).toList();
        boxes.forEach(rows::add);
        var scroll = new JScrollPane(rows);
        scroll.setPreferredSize(new java.awt.Dimension(360, 150));
        body.add(scroll, BorderLayout.CENTER);
        if (!show("move", body)) return Optional.empty();
        var included = new java.util.ArrayList<FileInfo>();
        for (int index = 0; index < boxes.size(); index++)
            if (boxes.get(index).isSelected()) included.add(links.get(index));
        return Optional.of(List.copyOf(included));
    }

    /** Mounts one owned dialog and preserves cancellation as absence. / 挂载一个所属对话框并将取消保留为空值。
     * @param key localized title key / 本地化标题键
     * @param body actual input controls / 实际输入控件
     * @return explicit confirmation / 明确确认 */
    private boolean show(String key, JComponent body) {
        Window owner = SwingUtilities.getWindowAncestor(root);
        var dialog = new JDialog(owner, messages.text(key), Dialog.ModalityType.APPLICATION_MODAL);
        current = dialog;
        currentKey = key;
        boolean[] accepted = {false};
        var buttons = new JPanel();
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
        buttons.add(ok);
        buttons.add(cancel);
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(root);
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

    /** Applies actual host appearance without replacing unsubmitted input. / 应用实际宿主外观，不替换未提交输入。 */
    void environmentChanged() {
        if (current == null) return;
        SwingUtilities.updateComponentTreeUI(current);
        current.setTitle(messages.text(currentKey));
        localize(current);
        current.validate();
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
