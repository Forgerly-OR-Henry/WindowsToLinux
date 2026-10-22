package gold.debug.windowstolinux.app.ui.display;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatScrollBarUI;
import com.formdev.flatlaf.util.UIScale;

/** Keeps inventory scroll indicators visible without changing their range models. / 保持清单滚动指示常驻，不修改范围模型。 */
public final class DesktopScrollBarUi extends FlatScrollBarUI {
    public static ComponentUI createUI(JComponent component) {
        return new DesktopScrollBarUi();
    }

    private boolean card() {
        return scrollbar.getOrientation() == JScrollBar.VERTICAL
            && Boolean.TRUE.equals(scrollbar.getClientProperty(DesktopCardSupport.SCROLLBAR));
    }

    private boolean fullTrack() {
        var model = scrollbar.getModel();
        return (long) model.getMaximum() - model.getMinimum() <= model.getExtent();
    }

    private Insets cardInsets() {
        int inset = DesktopCardSupport.SCROLLBAR_WIDTH - DesktopCardSupport.THUMB_WIDTH;
        return scrollbar.getComponentOrientation().isLeftToRight()
            ? new Insets(0, inset, 0, 0) : new Insets(0, 0, 0, inset);
    }

    @Override
    protected PropertyChangeListener createPropertyChangeListener() {
        var original = super.createPropertyChangeListener();
        return event -> {
            original.propertyChange(event);
            if (DesktopCardSupport.SCROLLBAR.equals(event.getPropertyName())) {
                scrollbar.revalidate();
                scrollbar.repaint();
            }
        };
    }

    @Override
    public Dimension getPreferredSize(JComponent component) {
        var size = super.getPreferredSize(component);
        return card() ? new Dimension(UIScale.scale(DesktopCardSupport.SCROLLBAR_WIDTH), size.height) : size;
    }

    @Override
    protected boolean isShowButtons() {
        return !card() && super.isShowButtons();
    }

    @Override
    protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
        if (!card()) {
            super.paintTrack(graphics, component, bounds);
            return;
        }
        graphics.setColor(getTrackColor(component, hoverTrack, false));
        paintTrackOrThumb(graphics, component, bounds, cardInsets(), 999);
        if (fullTrack()) paintCardThumb(graphics, component, bounds);
    }

    @Override
    protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
        if (!card()) super.paintThumb(graphics, component, bounds);
        else if (!fullTrack() && !bounds.isEmpty()) paintCardThumb(graphics, component, bounds);
    }

    private void paintCardThumb(Graphics graphics, JComponent component, Rectangle bounds) {
        graphics.setColor(getThumbColor(component, hoverThumb, isDragging));
        paintTrackOrThumb(graphics, component, bounds, cardInsets(), 999);
    }
}
