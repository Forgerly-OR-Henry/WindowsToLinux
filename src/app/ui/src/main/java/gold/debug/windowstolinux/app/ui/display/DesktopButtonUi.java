package gold.debug.windowstolinux.app.ui.display;

import java.awt.Color;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.UiMotionKind;

/** Keeps native action models and hit regions while easing visual feedback. */
public class DesktopButtonUi extends FlatButtonUI {
    private DesktopColorMotion motion;

    protected DesktopButtonUi() {
        super(false);
    }

    public static ComponentUI createUI(JComponent component) {
        return new DesktopButtonUi();
    }

    @Override
    protected Color getBackground(JComponent component) {
        if (motion == null) motion = new DesktopColorMotion(component);
        var button = (AbstractButton) component;
        return motion.color(super.getBackground(component), button.getModel().isPressed() ? UiMotionKind.PRESS : UiMotionKind.HOVER);
    }

    @Override
    protected void uninstallDefaults(AbstractButton button) {
        if (motion != null) motion.close();
        motion = null;
        super.uninstallDefaults(button);
    }

    public static final class Toggle extends FlatToggleButtonUI {
        private DesktopColorMotion motion;

        private Toggle() {
            super(false);
        }

        public static ComponentUI createUI(JComponent component) {
            return new Toggle();
        }

        @Override
        protected Color getBackground(JComponent component) {
            if (motion == null) motion = new DesktopColorMotion(component);
            var button = (AbstractButton) component;
            return motion.color(super.getBackground(component), button.getModel().isPressed() ? UiMotionKind.PRESS : UiMotionKind.HOVER);
        }

        @Override
        protected void uninstallDefaults(AbstractButton button) {
            if (motion != null) motion.close();
            motion = null;
            super.uninstallDefaults(button);
        }
    }
}
