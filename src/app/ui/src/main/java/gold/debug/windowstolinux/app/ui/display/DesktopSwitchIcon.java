package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.util.AnimatedIcon;
import com.formdev.flatlaf.util.Animator;
import com.formdev.flatlaf.util.UIScale;

/** Global checkbox presentation with a round sliding thumb and unchanged button semantics. / 保留按钮语义的全局复选圆形滑块展示。 */
public final class DesktopSwitchIcon implements AnimatedIcon, UIResource {
    /** Uses the selected model state rather than a separate preference. / 使用原选中模型状态，不持有独立偏好。
     * @param component original control / 原控件
     * @return selected position / 选中位置 */
    @Override
    public float getValue(Component component) {
        return ((AbstractButton) component).isSelected() ? 1 : 0;
    }

    /** Scales the horizontal switch space. / 缩放横向开关空间。
     * @return scaled width / 缩放宽度 */
    @Override
    public int getIconWidth() {
        return UIScale.scale(36);
    }

    /** Includes space for colored keyboard focus. / 包含颜色键盘焦点的空间。
     * @return scaled height / 缩放高度 */
    @Override
    public int getIconHeight() {
        return UIScale.scale(24);
    }

    /** Respects the same system motion choice as sidebar and card motion. / 遵守与侧栏及卡片相同的系统动画选择。
     * @return whether to animate / 是否播放动画 */
    @Override
    public boolean isAnimationEnabled() {
        return DesktopMotion.enabled() && AnimatedIcon.super.isAnimationEnabled();
    }

    /** Uses a short continuous transition. / 使用短连续过渡。
     * @return duration in milliseconds / 毫秒时长 */
    @Override
    public int getAnimationDuration() {
        return 180;
    }

    /** Uses the shared visual frame cadence. / 使用共用视觉帧节奏。
     * @return frame interval / 帧间隔 */
    @Override
    public int getAnimationResolution() {
        return 16;
    }

    /** Softens both ends of thumb movement. / 平滑滑块运动的两端。
     * @return interpolation function / 插值函数 */
    @Override
    public Animator.Interpolator getAnimationInterpolator() {
        return value -> value * value * (3 - 2 * value);
    }

    /** Avoids animation state leaking between cells sharing one renderer. / 避免共用渲染器的单元格之间泄漏动画状态。
     * @param component original checkbox / 原复选控件
     * @param graphics paint context / 绘制上下文
     * @param x icon origin / 图标原点
     * @param y icon origin / 图标原点 */
    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        if (component.getParent() instanceof CellRendererPane)
            paintIconAnimated(component, graphics, x, y, getValue(component));
        else AnimatedIcon.super.paintIcon(component, graphics, x, y);
    }

    /** Paints only filled round surfaces, preserving disabled and keyboard feedback. / 仅绘制填充圆形表面，保留禁用与键盘反馈。
     * @param component original control / 原控件
     * @param graphics paint context / 绘制上下文
     * @param x icon origin / 图标原点
     * @param y icon origin / 图标原点
     * @param value animated selected position / 动画选中位置 */
    @Override
    public void paintIconAnimated(Component component, Graphics graphics, int x, int y, float value) {
        var button = (AbstractButton) component;
        var g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.scale(UIScale.getUserScaleFactor(), UIScale.getUserScaleFactor());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean enabled = button.isEnabled();
            Color off = UIManager.getColor(enabled ? "Button.background" : "Button.disabledBackground");
            Color on = UIManager.getColor("Component.focusColor");
            if (button.isFocusOwner()) {
                g.setColor(new Color(on.getRed(), on.getGreen(), on.getBlue(), 65));
                g.fillRoundRect(0, 0, 36, 24, 24, 24);
            }
            Color track = blend(off, enabled ? on : UIManager.getColor("Label.disabledForeground"), value);
            if (enabled && button.getModel().isRollover())
                track = blend(track, UIManager.getColor("Label.foreground"), .1f);
            g.setColor(track);
            g.fillRoundRect(2, 3, 32, 18, 18, 18);
            float position = component.getComponentOrientation().isLeftToRight() ? value : 1 - value;
            g.setColor(enabled ? Color.WHITE : UIManager.getColor("Button.background"));
            g.fill(new java.awt.geom.Ellipse2D.Float(4 + position * 14, 5, 14, 14));
        } finally {
            g.dispose();
        }
    }

    /** Interpolates the original host palette colors. / 插值原宿主配色。
     * @param from original color / 原颜色
     * @param to destination color / 目标颜色
     * @param value transition progress / 过渡进度
     * @return interpolated color / 插值颜色 */
    private static Color blend(Color from, Color to, float value) {
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * value),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * value),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * value));
    }
}
