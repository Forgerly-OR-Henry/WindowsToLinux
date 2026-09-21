package gold.debug.windowstolinux.app.ui.ai;

import javax.swing.*;
import java.awt.datatransfer.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Reorders one model list without transferring purpose membership. / 只调整一个模型列表顺序，不转移用途成员。 */
final class AiProviderDragTransfer extends TransferHandler {
    /** Owned list. / 所属列表。 */
    private final JList<String> list;
    /** Receives the complete reordered list. / 接收完整新顺序。 */
    private final Consumer<List<String>> changed;
    /** Current operation guard. / 当前操作保护。 */
    private final BooleanSupplier busy;
    /** Binds drag operations to one list. / 将拖动绑定到一个列表。
     * @param list owned list / 所属列表
     * @param changed reorder callback / 排序回调
     * @param busy operation guard / 操作保护
     */
    AiProviderDragTransfer(JList<String> list, Consumer<List<String>> changed, BooleanSupplier busy) {
        this.list=list; this.changed=changed; this.busy=busy;
    }
    /** Exports only an owned model identity. / 仅导出所属模型标识。
     * @param source source component / 来源控件
     * @return selected identity / 所选标识
     */
    @Override protected Transferable createTransferable(JComponent source) { return new StringSelection(list.getSelectedValue()); }
    /** Allows reorder gestures. / 允许排序手势。
     * @param component source component / 来源控件
     * @return move operation / 移动操作
     */
    @Override public int getSourceActions(JComponent component) { return MOVE; }
    /** Rejects transfers outside the owned list. / 拒绝所属列表之外的传输。
     * @param support transfer details / 传输详情
     * @return whether the transfer is admissible / 是否准入传输
     */
    @Override public boolean canImport(TransferSupport support) {
        return !busy.getAsBoolean() && support.isDrop() && support.getComponent()==list
                && support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }
    /** Applies a validated drop. / 应用已校验放置。
     * @param support transfer details / 传输详情
     * @return whether the order changed / 是否改变顺序
     */
    @Override public boolean importData(TransferSupport support) {
        if (!canImport(support)) return false;
        try {
            String id=(String)support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            var order=new ArrayList<String>(); for(int i=0;i<list.getModel().getSize();i++) order.add(list.getModel().getElementAt(i));
            if (!order.contains(id)) return false;
            changed.accept(move(order,id,((JList.DropLocation)support.getDropLocation()).getIndex())); return true;
        } catch (java.io.IOException | UnsupportedFlavorException | IllegalArgumentException failure) { return false; }
    }
    /** Moves one identity to a validated insertion position. / 将一个标识移动到已校验插入位置。
     * @param order original order / 原顺序
     * @param id moved identity / 移动标识
     * @param dropPosition insertion position / 插入位置
     * @return changed immutable order / 改变后的不可变顺序
     */
    static List<String> move(List<String> order,String id,int dropPosition) {
        var result=new ArrayList<>(order); int from=result.indexOf(id);
        if(from<0 || dropPosition<0 || dropPosition>order.size()) throw new IllegalArgumentException("invalid model move");
        result.remove(from); result.add(dropPosition>from ? dropPosition-1 : dropPosition,id); return List.copyOf(result);
    }
}
