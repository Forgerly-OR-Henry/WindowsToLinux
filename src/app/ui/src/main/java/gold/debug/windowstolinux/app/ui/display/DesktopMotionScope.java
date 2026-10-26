package gold.debug.windowstolinux.app.ui.display;

import java.util.*;
import java.util.function.*;
import javax.swing.JComponent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import gold.debug.windowstolinux.appapi.ui.page.*;

/** Activation-owned visual clocks; cancellation releases author callbacks. / 启用所属视觉时钟，取消时释放作者回调。 */
public final class DesktopMotionScope implements UiMotion, AutoCloseable {
    private final BooleanSupplier active;
    private final Set<Tween> running = new LinkedHashSet<>();
    private boolean closed;

    public DesktopMotionScope(BooleanSupplier active) { this.active = Objects.requireNonNull(active); }

    @Override public UiMotionHandle animate(JComponent owner, UiMotionKind kind, DoubleConsumer frame) {
        DesktopMotion.requireEdt();
        var tween = new Tween(Objects.requireNonNull(owner), Objects.requireNonNull(kind), Objects.requireNonNull(frame));
        running.add(tween);
        if (closed || !active.getAsBoolean()) tween.close();
        else if (!DesktopMotion.enabled() || !owner.isShowing()) tween.finish();
        else {
            try { frame.accept(0); if (tween.callback != null) tween.clock.start(); }
            catch (RuntimeException | Error failure) { tween.close(); throw failure; }
        }
        return tween;
    }

    public void cancel() {
        DesktopMotion.requireEdt();
        for (var tween : List.copyOf(running)) tween.close();
    }

    @Override public void close() { closed = true; cancel(); }

    private final class Tween implements UiMotionHandle {
        private JComponent owner;
        private DoubleConsumer callback;
        private final double seconds;
        private double elapsed;
        private final DesktopMotion clock = new DesktopMotion(this::frame);
        private final HierarchyListener lifetime = this::hierarchyChanged;

        Tween(JComponent owner, UiMotionKind kind, DoubleConsumer callback) {
            this.owner = owner; this.callback = callback; seconds = kind.milliseconds() / 1000d;
            owner.addHierarchyListener(lifetime);
        }

        private void hierarchyChanged(HierarchyEvent event) {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && owner != null && !owner.isShowing()) close();
        }

        private void frame(double interval) {
            if (callback == null) return;
            if (closed || !active.getAsBoolean() || !owner.isShowing()) { close(); return; }
            elapsed += interval;
            double progress = Math.min(1, elapsed / seconds);
            if (progress >= 1) finish(); else {
                try { callback.accept(DesktopMotion.ease(progress)); }
                catch (RuntimeException | Error failure) { close(); throw failure; }
            }
        }

        @Override public void finish() {
            DesktopMotion.requireEdt();
            var frame = callback;
            boolean present = frame != null && !closed && active.getAsBoolean();
            close();
            if (present) frame.accept(1);
        }

        @Override public void close() {
            DesktopMotion.requireEdt(); clock.close(); running.remove(this); callback = null;
            if (owner != null) owner.removeHierarchyListener(lifetime);
            owner = null;
        }
    }
}
