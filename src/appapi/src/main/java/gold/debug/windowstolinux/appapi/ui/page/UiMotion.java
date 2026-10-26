package gold.debug.windowstolinux.appapi.ui.page;

import java.util.function.DoubleConsumer;
import javax.swing.JComponent;

/** Optional host clock for Mod-owned painting and layout. / Mod 所属绘制及布局可选的宿主时钟。 */
public interface UiMotion {
    /** Starts on the EDT; frames are eased values in [0,1], never business callbacks. Hidden or reduced-motion
     * owners receive the final frame immediately. Stopping or locked activations receive no further frames.
     * / 在EDT启动；帧为[0,1]的缓动值，不用于业务回调。隐藏或关闭动画时立即绘制最终帧，停止或锁定的启用不再收到帧。
     * @param owner original visual component / 原可视组件
     * @param kind common timing / 共用时长
     * @param frame painting/layout only / 仅用于绘制或布局
     * @return owned cancellable transition / 所属可取消过渡 */
    UiMotionHandle animate(JComponent owner, UiMotionKind kind, DoubleConsumer frame);
}
