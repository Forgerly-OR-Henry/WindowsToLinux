package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.text.JTextComponent;

import com.formdev.flatlaf.ui.*;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.appapi.ui.page.UiMotionKind;

/** Focus feedback retaining FlatLaf's text, caret, icons and input semantics. */
public final class DesktopTextFieldUi extends FlatTextFieldUI {
    private DesktopColorMotion motion;

    public static ComponentUI createUI(JComponent component) {
        return new DesktopTextFieldUi();
    }

    @Override
    protected void paintBackground(Graphics graphics) {
        if (motion == null) motion = new DesktopColorMotion(getComponent());
        paintSurface(graphics, getComponent(), motion);
    }

    @Override
    protected void uninstallDefaults() {
        if (motion != null) motion.close();
        motion = null;
        super.uninstallDefaults();
    }

    private static void paintSurface(Graphics graphics, JTextComponent component, DesktopColorMotion motion) {
        Color target = component.getBackground();
        if (component.isEnabled() && component.isEditable() && FlatUIUtils.isPermanentFocusOwner(component)) {
            Color accent = UIManager.getColor("Component.focusColor");
            if (accent != null) target = DesktopColorMotion.blend(target, accent, .06);
        }
        FlatUIUtils.paintParentBackground(graphics, component);
        var g = (Graphics2D) graphics.create();
        try {
            FlatUIUtils.setRenderingHints(g);
            g.setColor(motion.color(target, UiMotionKind.HOVER));
            FlatUIUtils.paintComponentBackground(g, 0, 0, component.getWidth(), component.getHeight(), 0,
                UIScale.scale((float) UIManager.getInt("TextComponent.arc")));
        } finally {
            g.dispose();
        }
    }

    public static final class Password extends FlatPasswordFieldUI {
        private DesktopColorMotion motion;

        public static ComponentUI createUI(JComponent component) {
            return new Password();
        }

        @Override
        protected void paintBackground(Graphics graphics) {
            if (motion == null) motion = new DesktopColorMotion(getComponent());
            paintSurface(graphics, getComponent(), motion);
        }

        @Override
        protected void uninstallDefaults() {
            if (motion != null) motion.close();
            motion = null;
            super.uninstallDefaults();
        }
    }

    public static final class Formatted extends FlatFormattedTextFieldUI {
        private DesktopColorMotion motion;

        public static ComponentUI createUI(JComponent component) {
            return new Formatted();
        }

        @Override
        protected void paintBackground(Graphics graphics) {
            if (motion == null) motion = new DesktopColorMotion(getComponent());
            paintSurface(graphics, getComponent(), motion);
        }

        @Override
        protected void uninstallDefaults() {
            if (motion != null) motion.close();
            motion = null;
            super.uninstallDefaults();
        }
    }
}
