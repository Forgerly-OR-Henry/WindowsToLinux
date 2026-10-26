package gold.debug.windowstolinux.app.ui.display;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.ui.FlatTextAreaUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/** Rounds multiline surfaces while retaining FlatLaf's text, selection and caret rendering. / 多行表面使用圆角，保留 FlatLaf 原文字、选择及光标绘制。 */
public final class DesktopTextAreaUi extends FlatTextAreaUI {
    private DesktopColorMotion motion;
    /** Creates an independent delegate for each text area. / 为每个多行文本框创建独立绘制器。 */
    private DesktopTextAreaUi() {
    }

    /** Creates the shared multiline surface behavior. / 创建统一多行表面行为。
     * @param component text area receiving this delegate / 使用当前绘制器的多行文本框
     * @return independent text area delegate / 独立多行文本绘制器 */
    public static ComponentUI createUI(JComponent component) {
        return new DesktopTextAreaUi();
    }

    /** Paints only the opaque background; the inherited text pipeline supplies all content. / 仅绘制不透明背景，所有内容由继承的文字流程提供。
     * @param graphics current rendering context / 当前绘制上下文 */
    @Override
    protected void paintBackground(Graphics graphics) {
        var component = getComponent();
        Color background = component.getBackground();
        if (background instanceof UIResource && focusedBackground != null
            && FlatUIUtils.isPermanentFocusOwner(component))
            background = focusedBackground;
        if (component.isEnabled() && component.isEditable() && FlatUIUtils.isPermanentFocusOwner(component))
            background = DesktopColorMotion.blend(background, UIManager.getColor("Component.focusColor"), .06);
        if (motion == null) motion = new DesktopColorMotion(component);
        background = motion.color(background, gold.debug.windowstolinux.appapi.ui.page.UiMotionKind.HOVER);
        FlatUIUtils.paintParentBackground(graphics, component);
        var rounded = (Graphics2D) graphics.create();
        try {
            FlatUIUtils.setRenderingHints(rounded);
            rounded.setColor(background);
            FlatUIUtils.paintComponentBackground(rounded, 0, 0, component.getWidth(), component.getHeight(), 0,
                UIScale.scale((float) UIManager.getInt("TextComponent.arc")));
        } finally {
            rounded.dispose();
        }
    }

    @Override protected void uninstallDefaults() {
        if (motion != null) motion.close();
        motion = null;
        super.uninstallDefaults();
    }
}
