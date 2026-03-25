package gold.debug.wintolin.appui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class Welcome extends JPanel {
    private final Runnable onStart;

    public Welcome(Runnable onStart) {
        this.onStart = onStart;

        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(40, 40, 40, 40));

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("WELCOME");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 44f));
        title.setForeground(new Color(235, 245, 255));

        JLabel sub = new JLabel("Minimal • Modern • High-Tech");
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 16f));
        sub.setForeground(new Color(170, 200, 220));

        JButton start = new JButton("开始使用");
        start.setAlignmentX(Component.CENTER_ALIGNMENT);
        start.setFont(start.getFont().deriveFont(Font.BOLD, 16f));
        start.setFocusPainted(false);
        start.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        start.setBorder(new EmptyBorder(12, 28, 12, 28));
        start.putClientProperty("JButton.buttonType", "roundRect");
        start.addActionListener(e -> onStart.run());

        center.add(title);
        center.add(Box.createVerticalStrut(10));
        center.add(sub);
        center.add(Box.createVerticalStrut(30));
        center.add(start);

        add(center, new GridBagConstraints());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();

        g2.setPaint(new GradientPaint(0, 0, new Color(10, 14, 20),
                0, h, new Color(18, 24, 34)));
        g2.fillRect(0, 0, w, h);

        g2.setComposite(AlphaComposite.SrcOver.derive(0.18f));
        int glow = Math.min(w, h);
        g2.setColor(new Color(80, 180, 255));
        g2.fillOval((w - glow) / 2, (h - glow) / 2, glow, glow);

        g2.setComposite(AlphaComposite.SrcOver.derive(0.10f));
        g2.setColor(new Color(160, 210, 255));
        for (int x = 0; x < w; x += 28) g2.drawLine(x, 0, x, h);
        for (int y = 0; y < h; y += 28) g2.drawLine(0, y, w, y);

        g2.dispose();
    }
}
