package gold.debug.windowstolinux.app.ui.window;

import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JFrame;

/** Keeps normal-window content at 16:9 without including native decorations. / 保持普通窗口内容区为 16:9，不计入原生窗框。 */
final class DesktopWindowGeometry extends ComponentAdapter {
    /** Owned window; its component listener shares the same lifetime. / 所属窗口，组件监听器与其共用生命周期。 */
    private final JFrame frame;

    /** Last accepted content dimensions used to identify the resized axis. / 用于识别调整轴的上次已接纳内容尺寸。 */
    private Dimension previous = new Dimension(1600, 900);

    /** Last exterior size distinguishes decoration changes from a user resize. / 上次外部尺寸用于区分窗框变化及用户调整。 */
    private Dimension exterior;

    /** Binds the normal-window sizing policy. / 绑定普通窗口尺寸策略。
     * @param frame owned window / 所属窗口 */
    private DesktopWindowGeometry(JFrame frame) {
        this.frame = frame;
        exterior = frame.getSize();
    }

    /** Packs exact default content and applies a decoration-aware minimum. / 打包精确默认内容尺寸，并设置计入窗框的最小尺寸。
     * @param frame owned window / 所属窗口 */
    static void install(JFrame frame) {
        frame.getContentPane().setPreferredSize(new Dimension(1600, 900));
        frame.pack();
        frame.validate();
        frame.setMinimumSize(new Dimension(1280 + frame.getWidth() - frame.getContentPane().getWidth(),
            720 + frame.getHeight() - frame.getContentPane().getHeight()));
        var geometry = new DesktopWindowGeometry(frame);
        frame.addComponentListener(geometry);
        frame.getContentPane().addComponentListener(geometry);
    }

    /** Follows the dragged axis while leaving all reserved shell dimensions unchanged. / 跟随拖动轴，保持外壳各预留尺寸不变。
     * @param event original window resize / 原窗口尺寸变化 */
    @Override
    public void componentResized(ComponentEvent event) {
        if (!frame.isDisplayable()) return;
        frame.validate();
        int width = frame.getContentPane().getWidth(), height = frame.getContentPane().getHeight();
        int decorationWidth = frame.getWidth() - width, decorationHeight = frame.getHeight() - height;
        if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
            previous = new Dimension(width, height);
            exterior = frame.getSize();
            return;
        }
        boolean decorationChanged = frame.getSize().equals(exterior)
            && !previous.equals(new Dimension(width, height));
        if (decorationChanged) {
            width = previous.width;
            height = previous.height;
        }
        if (width >= 1280 && height >= 720 && Math.abs(width * 9L - height * 16L) <= 8) {
            previous = new Dimension(width, height);
            exterior = new Dimension(width + decorationWidth, height + decorationHeight);
            if (!frame.getSize().equals(exterior)) frame.setSize(exterior);
            return;
        }
        if (Math.abs(width - previous.width) * 9L >= Math.abs(height - previous.height) * 16L) {
            width = Math.max(1280, width);
            height = (int) Math.round(width * 9d / 16);
        } else {
            height = Math.max(720, height);
            width = (int) Math.round(height * 16d / 9);
        }
        previous = new Dimension(width, height);
        exterior = new Dimension(width + decorationWidth, height + decorationHeight);
        frame.setSize(exterior);
    }
}
