package gold.debug.windowstolinux.mod.deployment.ui.flow;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.*;

/** Fades only a changed authored card; polling the same card never starts another transition. */
final class DeploymentContentCards extends JPanel implements AutoCloseable {
    private final Supplier<Optional<UiMotion>> motion;

    private UiMotionHandle transition;

    private String selected;

    private BufferedImage outgoing;

    private float opacity;

    DeploymentContentCards(Supplier<Optional<UiMotion>> motion) {
        super(new CardLayout());
        this.motion = motion;
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) reset();
        });
    }

    void showCard(String name) {
        if (name.equals(selected)) return;
        var clock = motion.get();
        BufferedImage image = null;
        if (isShowing() && selected != null && clock.isPresent() && getWidth() > 0 && getHeight() > 0) {
            image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            printAll(graphics);
            graphics.dispose();
        }
        reset();
        selected = name;
        ((CardLayout) getLayout()).show(this, name);
        outgoing = image;
        if (image == null) return;
        transition = clock.orElseThrow().animate(this, UiMotionKind.CONTENT, progress -> {
            opacity = (float) (1 - progress);
            if (progress == 1) outgoing = null;
            repaint();
        });
    }

    void reset() {
        if (transition != null) transition.close();
        transition = null;
        outgoing = null;
        opacity = 0;
        repaint();
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (outgoing == null) return;
        var paint = (Graphics2D) graphics.create();
        paint.setComposite(AlphaComposite.SrcOver.derive(opacity));
        paint.drawImage(outgoing, 0, 0, null);
        paint.dispose();
    }

    @Override
    public void close() {
        reset();
    }
}
