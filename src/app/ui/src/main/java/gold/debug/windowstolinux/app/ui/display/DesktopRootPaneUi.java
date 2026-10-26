package gold.debug.windowstolinux.app.ui.display;

import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatRootPaneUI;

/** Applies the same content entrance to owned Swing dialogs, including official Mods. */
public final class DesktopRootPaneUi extends FlatRootPaneUI {
    private JRootPane root;

    private boolean wrapping;

    private final HierarchyListener hierarchy = event -> decorate();

    private final PropertyChangeListener content = event -> {
        if ("contentPane".equals(event.getPropertyName()) || "desktop.motion".equals(event.getPropertyName()))
            decorate();
    };

    public static ComponentUI createUI(JComponent component) {
        return new DesktopRootPaneUi();
    }

    @Override
    public void installUI(JComponent component) {
        super.installUI(component);
        root = (JRootPane) component;
        root.addHierarchyListener(hierarchy);
        root.addPropertyChangeListener(content);
        decorate();
    }

    private void decorate() {
        if (wrapping || root == null || !(SwingUtilities.getWindowAncestor(root) instanceof JDialog dialog)) return;
        if (!Boolean.TRUE.equals(root.getClientProperty("desktop.motion"))) return;
        wrapping = true;
        try {
            DesktopRevealPane.install(dialog);
        } finally {
            wrapping = false;
        }
    }

    @Override
    public void uninstallUI(JComponent component) {
        root.removeHierarchyListener(hierarchy);
        root.removePropertyChangeListener(content);
        root = null;
        super.uninstallUI(component);
    }
}
