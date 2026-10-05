package gold.debug.windowstolinux.mod.files.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.mod.files.file.model.FilesDirectory;

/** Owns one independently located multi-selection pane. / 持有一个独立定位及多选的栏。 */
final class FilesPane extends JPanel {
    /** Original observed directory, absent before explicit choice. / 原已观测目录，明确选择前为空。 */
    private FilePath location;

    /** Author-owned metadata model. / 作者所属元数据模型。 */
    private final FilesTableModel model;

    /** Multiple selection display. / 多选展示。 */
    private final JTable table;

    /** Explicit absolute location draft. / 明确绝对位置草稿。 */
    private final JTextField path = new JTextField();

    /** Local directory selection. / 本机目录选择。 */
    private final JButton local = new JButton();

    /** Saved server selection. / 已保存服务器选择。 */
    private final JButton remote = new JButton();

    /** Draft path submission. / 草稿路径提交。 */
    private final JButton go = new JButton();

    /** Parent directory submission. / 父目录提交。 */
    private final JButton up = new JButton();

    /** Current pane title. / 当前栏标题。 */
    private final JLabel heading = new JLabel();

    /** Empty initial-state instruction. / 空初始状态提示。 */
    private final JLabel empty = new JLabel();

    /** Actual scroll container. / 实际滚动容器。 */
    private final JScrollPane scroll;

    /** Stable side identity. / 稳定侧身份。 */
    private final String side;

    /** Latest explicit directory request revision. / 最新明确目录请求修订。 */
    private long revision;

    /** Builds a symmetric pane without selecting any location. / 创建对称栏，不选择任何位置。
     * @param side stable left or right identity / 稳定左右身份
     * @param messages shared catalog / 共享文案目录
     * @param workspace root action owner / 根动作所有者 */
    FilesPane(String side, FilesMessages messages, FilesWorkspace workspace) {
        super(new BorderLayout(4, 4));
        this.side = side;
        model = new FilesTableModel(messages);
        table = new JTable(model);
        table.setName("files." + side + ".table");
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        table.setTransferHandler(new FilesTransferHandler(workspace, this));
        table.getSelectionModel().addListSelectionListener(event -> workspace.update());
        var activate = new FocusAdapter() {
            /** Selects the pane receiving actual focus. / 选择获得实际焦点的栏。
             * @param event original focus event / 原焦点事件 */
            @Override
            public void focusGained(FocusEvent event) {
                workspace.activate(FilesPane.this);
            }
        };
        table.addFocusListener(activate);
        for (var control : new JComponent[]{path, local, remote, go, up}) control.addFocusListener(activate);
        table.addMouseListener(new MouseAdapter() {
            /** Resolves popup context on press. / 按下时解析菜单上下文。
             * @param event original pointer event / 原指针事件 */
            @Override
            public void mousePressed(MouseEvent event) {
                popup(event, workspace);
            }

            /** Resolves popup context and ordinary directory opening. / 解析菜单上下文及普通目录打开。
             * @param event original pointer event / 原指针事件 */
            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event, workspace);
            }

            /** Opens only observed ordinary directories. / 仅打开已观测普通目录。
             * @param event original pointer event / 原指针事件 */
            @Override
            public void mouseClicked(MouseEvent event) {
                workspace.activate(FilesPane.this);
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event))
                    workspace.commands().openSelected();
            }
        });
        path.setName("files." + side + ".path");
        local.setName("files." + side + ".local");
        remote.setName("files." + side + ".remote");
        go.setName("files." + side + ".go");
        up.setName("files." + side + ".up");
        var navigation = new JPanel(new BorderLayout(4, 4));
        var choices = new JPanel(new java.awt.GridLayout(1, 3, 4, 0));
        choices.add(local);
        choices.add(remote);
        choices.add(up);
        navigation.add(heading, BorderLayout.NORTH);
        navigation.add(choices, BorderLayout.CENTER);
        var address = new JPanel(new BorderLayout(4, 0));
        address.add(path, BorderLayout.CENTER);
        address.add(go, BorderLayout.EAST);
        navigation.add(address, BorderLayout.SOUTH);
        local.addActionListener(event -> workspace.commands().chooseLocal(this));
        remote.addActionListener(event -> workspace.commands().chooseRemote(this));
        go.addActionListener(event -> workspace.commands().openPath(this));
        path.addActionListener(event -> workspace.commands().openPath(this));
        up.addActionListener(event -> workspace.commands().parent(this));
        scroll = new JScrollPane(table);
        var content = new JPanel(new BorderLayout());
        content.add(empty, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        add(navigation, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        setMinimumSize(new Dimension(120, 100));
        localize(messages, false);
    }

    /** Targets popup actions at this pane and its actual selection. / 将菜单动作指向本栏及其实际选择。
     * @param event original popup event / 原菜单事件
     * @param workspace shared action owner / 共享动作所有者 */
    private void popup(MouseEvent event, FilesWorkspace workspace) {
        if (!event.isPopupTrigger() || !table.isEnabled()) return;
        table.requestFocusInWindow();
        int row = table.rowAtPoint(event.getPoint());
        if (row < 0) table.clearSelection();
        else if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
        workspace.activate(this);
        workspace.popup().show(table, event.getX(), event.getY());
    }

    /** Applies authentic listing while preserving surviving selection identities. / 应用真实列表并保留仍存在的选择身份。
     * @param directory original task result / 原任务结果 */
    void display(FilesDirectory directory) {
        var selected = selected().stream().map(info -> info.requested().key() + "|" + info.identity()).toList();
        location = directory.location();
        path.setText(location.path());
        model.entries(directory.entries());
        for (int row = 0; row < model.getRowCount(); row++) {
            var info = model.entry(row);
            if (selected.contains(info.requested().key() + "|" + info.identity()))
                table.addRowSelectionInterval(row, row);
        }
        empty.setVisible(false);
    }

    /** Returns selected metadata in stable visible order. / 按稳定可见顺序返回所选元数据。
     * @return immutable actual observations / 不可变实际观测 */
    List<FileInfo> selected() {
        return java.util.Arrays.stream(table.getSelectedRows()).mapToObj(row -> model.entry(table.convertRowIndexToModel(row))).toList();
    }

    /** Returns all directly observed links and entries. / 返回全部直接观测链接及条目。
     * @return immutable metadata / 不可变元数据 */
    List<FileInfo> entries() {
        return model.entries();
    }

    /** Returns the current observed directory. / 返回当前观测目录。
     * @return position or null before selection / 位置，选择前为空 */
    FilePath directory() {
        return location;
    }

    /** Returns the current path draft. / 返回当前路径草稿。
     * @return user input / 用户输入 */
    String pathDraft() {
        return path.getText();
    }

    /** Returns the original table for shared keyboard actions. / 返回用于共享键盘动作的原表格。
     * @return original display / 原展示 */
    JTable table() {
        return table;
    }

    /** Revokes older directory callbacks for this independent pane. / 撤销本独立栏的更早目录回调。
     * @return new request revision / 新请求修订 */
    long request() {
        return ++revision;
    }

    /** Checks exact original pane request identity. / 核对精确原栏请求身份。
     * @param value original revision / 原修订
     * @return whether still current / 是否仍为当前 */
    boolean current(long value) {
        return value == revision;
    }

    /** Localizes existing controls without losing drafts or selection. / 本地化已有控件，不丢失草稿及选择。
     * @param messages current catalog / 当前文案目录
     * @param active whether this is the action target / 是否为动作目标 */
    void localize(FilesMessages messages, boolean active) {
        heading.setText(messages.text(side) + (active ? " •" : ""));
        local.setText(messages.text("local"));
        remote.setText(messages.text("remote"));
        go.setText(messages.text("go"));
        up.setText(messages.text("up"));
        empty.setText(messages.text("empty"));
        for (int column = 0; column < 2; column++)
            table.getColumnModel().getColumn(column).setHeaderValue(model.getColumnName(column));
        table.getTableHeader().repaint();
        table.repaint();
        table.setRowHeight(Math.max(24, table.getFontMetrics(table.getFont()).getHeight() + 8));
    }

    /** Blocks all pane input and scrolling while locked. / 锁定时阻断本栏输入及滚动。
     * @param enabled whether input is allowed / 是否允许输入 */
    void interactive(boolean enabled) {
        local.setEnabled(enabled);
        remote.setEnabled(enabled);
        path.setEnabled(enabled);
        go.setEnabled(enabled && location != null);
        up.setEnabled(enabled && location != null);
        table.setEnabled(enabled);
        table.getTableHeader().setEnabled(enabled);
        scroll.setWheelScrollingEnabled(enabled);
        scroll.getVerticalScrollBar().setEnabled(enabled);
        scroll.getHorizontalScrollBar().setEnabled(enabled);
    }
}
