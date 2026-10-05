package gold.debug.windowstolinux.mod.files.ui;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.TransferHandler;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;

/** Accepts only one root's internal cross-pane move payload. / 仅接受一个根内的跨栏移动载荷。 */
final class FilesTransferHandler extends TransferHandler {
    /** Exact private JVM-local flavor. / 精确私有 JVM 本地格式。 */
    private static final DataFlavor FLAVOR = new DataFlavor(Payload.class, "Files workspace move");

    /** Original root identity. / 原根身份。 */
    private final FilesWorkspace workspace;

    /** Original pane identity. / 原栏身份。 */
    private final FilesPane pane;

    /** Captures the exact internal destination. / 捕获精确内部目标。
     * @param workspace original root / 原根
     * @param pane original pane / 原栏 */
    FilesTransferHandler(FilesWorkspace workspace, FilesPane pane) {
        this.workspace = workspace;
        this.pane = pane;
    }

    /** Fixes drag semantics to move. / 将拖拽语义固定为移动。
     * @param component source control / 来源控件
     * @return move only / 仅移动 */
    @Override
    public int getSourceActions(JComponent component) {
        return MOVE;
    }

    /** Captures observed metadata without effect or system clipboard access. / 捕获观测元数据，不产生副作用及系统剪贴板访问。
     * @param component source control / 来源控件
     * @return internal payload or no drag / 内部载荷或无拖拽 */
    @Override
    protected Transferable createTransferable(JComponent component) {
        var items = pane.selected();
        if (!workspace.available() || items.isEmpty() || items.stream().anyMatch(info -> info.kind() == FileInfo.Kind.OTHER))
            return null;
        return new Payload(workspace, pane, items);
    }

    /** Rejects external flavors and same-pane drops. / 拒绝外部格式及同栏放置。
     * @param support original drop / 原放置
     * @return whether this exact internal move is allowed / 是否允许此精确内部移动 */
    @Override
    public boolean canImport(TransferSupport support) {
        if (!workspace.available() || !workspace.dual() || pane.directory() == null || !support.isDrop() || !support.isDataFlavorSupported(FLAVOR))
            return false;
        try {
            if (!(support.getTransferable().getTransferData(FLAVOR) instanceof Payload value) || value.workspace != workspace || value.pane == pane)
                return false;
            support.setDropAction(MOVE);
            return true;
        } catch (java.io.IOException | UnsupportedFlavorException failure) {
            return false;
        }
    }

    /** Submits the same actual move used by cut and paste. / 提交与剪切粘贴相同的实际移动。
     * @param support original drop / 原放置
     * @return whether the request was accepted by the view / 视图是否接受请求 */
    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) return false;
        try {
            var value = (Payload) support.getTransferable().getTransferData(FLAVOR);
            workspace.commands().move(value.items, pane);
            return true;
        } catch (java.io.IOException | UnsupportedFlavorException failure) {
            return false;
        }
    }

    /** Ends visual export without deleting any source. / 结束可视导出，不删除任何来源。
     * @param source source component / 来源组件
     * @param data original payload / 原载荷
     * @param action original drag outcome / 原拖拽结果 */
    @Override
    protected void exportDone(JComponent source, Transferable data, int action) {
    }

    /** Private in-memory metadata, never an authorization. / 私有内存元数据，不是授权。 */
    private static final class Payload implements Transferable {
        /** Exact workspace identity. / 精确工作区身份。 */
        private final FilesWorkspace workspace;

        /** Exact source pane. / 精确来源栏。 */
        private final FilesPane pane;

        /** Original observations. / 原观测。 */
        private final List<FileInfo> items;

        /** Copies the exact internal selection. / 复制精确内部选择。
         * @param workspace root / 根
         * @param pane source pane / 来源栏
         * @param items observations / 观测 */
        private Payload(FilesWorkspace workspace, FilesPane pane, List<FileInfo> items) {
            this.workspace = workspace;
            this.pane = pane;
            this.items = List.copyOf(items);
        }

        /** Exposes only the private flavor. / 仅公开私有格式。
         * @return exact flavor / 精确格式 */
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{FLAVOR};
        }

        /** Matches only the internal flavor. / 仅匹配内部格式。
         * @param flavor requested flavor / 请求格式
         * @return exact match / 精确匹配 */
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return FLAVOR.equals(flavor);
        }

        /** Returns original metadata to an exact internal receiver. / 向精确内部接收方返回原元数据。
         * @param flavor requested flavor / 请求格式
         * @return original payload / 原载荷
         * @throws UnsupportedFlavorException if flavor differs / 格式不同时 */
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return this;
        }
    }
}
