package gold.debug.windowstolinux.app.ui.ai;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.*;

/** Native vertical drag ordering for actual interactive model cards. / 真实交互模型卡片的原生纵向拖动排序。 */
final class AiProviderDragTransfer extends TransferHandler {
    private final JPanel cards;
    private final Supplier<List<String>> order;
    private final Consumer<List<String>> changed;
    private final BooleanSupplier busy;
    AiProviderDragTransfer(JPanel cards, Supplier<List<String>> order, Consumer<List<String>> changed, BooleanSupplier busy) {
        this.cards = cards; this.order = order; this.changed = changed; this.busy = busy;
    }
    void install(JLabel handle, String id) {
        handle.putClientProperty("ai.provider.id", id); handle.setTransferHandler(this); handle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        handle.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent event) { if (!busy.getAsBoolean()) exportAsDrag(handle, event, MOVE); }
        });
    }
    @Override protected Transferable createTransferable(JComponent source) { return new StringSelection((String) source.getClientProperty("ai.provider.id")); }
    @Override public int getSourceActions(JComponent component) { return MOVE; }
    @Override public boolean canImport(TransferSupport support) { return support.isDrop() && !busy.getAsBoolean() && support.isDataFlavorSupported(DataFlavor.stringFlavor); }
    @Override public boolean importData(TransferSupport support) {
        if (!canImport(support)) return false;
        try {
            String id = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            List<String> current = order.get(); if (!current.contains(id)) return false;
            Point point = SwingUtilities.convertPoint(support.getComponent(), support.getDropLocation().getDropPoint(), cards);
            int position = current.size();
            for (int index = 0; index < cards.getComponentCount(); index++) {
                Component card = cards.getComponent(index);
                if (point.y < card.getY() + card.getHeight() / 2) { position = index; break; }
            }
            changed.accept(move(current, id, position)); return true;
        } catch (Exception failure) { return false; }
    }
    static List<String> move(List<String> order, String id, int dropPosition) {
        List<String> changed = new ArrayList<>(order); int from = changed.indexOf(id);
        if (from < 0 || dropPosition < 0 || dropPosition > order.size()) throw new IllegalArgumentException("invalid model move");
        changed.remove(from); changed.add(dropPosition > from ? dropPosition - 1 : dropPosition, id); return List.copyOf(changed);
    }
}
