package gold.debug.windowstolinux.app.ui.display;

import java.awt.Component;
import javax.swing.*;

/** Wraps popup content while retaining native show/hide and menu selection semantics. */
final class DesktopPopupFactory extends PopupFactory {
    private final PopupFactory delegate;

    private DesktopPopupFactory(PopupFactory delegate) {
        this.delegate = delegate;
    }

    static void install() {
        var current = PopupFactory.getSharedInstance();
        if (!(current instanceof DesktopPopupFactory)) PopupFactory.setSharedInstance(new DesktopPopupFactory(current));
    }

    @Override
    public Popup getPopup(Component owner, Component content, int x, int y) throws IllegalArgumentException {
        if (!(content instanceof JPopupMenu menu)) return delegate.getPopup(owner, content, x, y);
        // Let FlatLaf choose the original menu window and shadow before adding visual content.
        var popup = delegate.getPopup(owner, menu, x, y);
        var parent = menu.getParent();
        if (parent == null) return popup;
        var surface = new DesktopRevealPane(menu);
        parent.add(surface);
        parent.validate();
        return new Popup() {
            @Override
            public void show() {
                popup.show();
            }

            @Override
            public void hide() {
                surface.close();
                popup.hide();
                surface.remove(menu);
            }
        };
    }
}
