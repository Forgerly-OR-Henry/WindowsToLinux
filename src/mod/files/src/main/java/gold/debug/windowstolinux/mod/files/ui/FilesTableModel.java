package gold.debug.windowstolinux.mod.files.ui;

import java.util.List;
import javax.swing.table.AbstractTableModel;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;

/** Displays two columns while retaining actual no-follow observations. / 显示两列并保留实际不跟随链接观测。 */
final class FilesTableModel extends AbstractTableModel {
    /** Original observations in platform order. / 按平台顺序排列的原观测。 */
    private List<FileInfo> entries = List.of();

    /** Current catalog shared by the root. / 根共享的当前文案目录。 */
    private final FilesMessages messages;

    /** Starts with an empty directory view. / 以空目录视图开始。
     * @param messages shared catalog / 共享文案目录 */
    FilesTableModel(FilesMessages messages) {
        this.messages = messages;
    }

    /** Retains only observed metadata. / 仅保留已观测元数据。
     * @param values original directory observations / 原目录观测 */
    void entries(List<FileInfo> values) {
        entries = List.copyOf(values);
        fireTableDataChanged();
    }

    /** Returns the retained observation at a model row. / 返回模型行保留的观测。
     * @param row model row / 模型行
     * @return original observation / 原观测 */
    FileInfo entry(int row) {
        return entries.get(row);
    }

    /** Returns all observed entries without authorization. / 返回全部已观测条目，不授予权限。
     * @return immutable original observations / 不可变原观测 */
    List<FileInfo> entries() {
        return entries;
    }

    /** Returns actual displayed entry count. / 返回实际展示条目数。
     * @return count / 数量 */
    @Override
    public int getRowCount() {
        return entries.size();
    }

    /** Keeps the agreed name and type columns. / 保留约定名称与类型两列。
     * @return two columns / 两列 */
    @Override
    public int getColumnCount() {
        return 2;
    }

    /** Localizes a column identity. / 本地化列身份。
     * @param column model column / 模型列
     * @return label / 标签 */
    @Override
    public String getColumnName(int column) {
        return messages.text(column == 0 ? "name" : "type");
    }

    /** Maps actual kinds without following link targets. / 映射实际类型，不跟随链接目标。
     * @param row model row / 模型行
     * @param column model column / 模型列
     * @return name or type / 名称或类型 */
    @Override
    public Object getValueAt(int row, int column) {
        var info = entry(row);
        if (column == 0) return info.requested().name();
        String kind = messages.text("kind." + info.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (info.kind() != FileInfo.Kind.FILE) return kind;
        String name = info.requested().name();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) + " " + kind : kind;
    }
}
