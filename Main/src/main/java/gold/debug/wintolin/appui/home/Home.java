package gold.debug.wintolin.appui.home;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Home extends JPanel {

    // Split layout
    private final JSplitPane splitPane;
    private final JPanel sidebar;
    private final JPanel content;
    private final CardLayout contentCards = new CardLayout();

    // Sidebar state
    private boolean collapsed = false;
    private int lastExpandedDivider = -1;

    // Constants
    private static final double SIDEBAR_RATIO = 0.20;     // 20%
    private static final int COLLAPSED_WIDTH = 64;        // collapsed sidebar width
    private static final String CARD_HOME = "home";

    public Home() {
        super(new BorderLayout());

        // Left: Sidebar
        sidebar = buildSidebar();

        // Right: Content
        content = new JPanel(contentCards);
        content.setBorder(new EmptyBorder(16, 16, 16, 16));
        content.add(buildHomeCard(), CARD_HOME);

        // SplitPane to keep 20% ratio
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, content);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        splitPane.setContinuousLayout(true);
        splitPane.setResizeWeight(SIDEBAR_RATIO); // tends to keep left proportion

        // Minimum sizes
        sidebar.setMinimumSize(new Dimension(COLLAPSED_WIDTH, 0));
        content.setMinimumSize(new Dimension(300, 0));

        add(splitPane, BorderLayout.CENTER);

        // When the panel is first shown / resized, set divider to 20% (only when expanded)
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!collapsed) {
                    setDividerToRatio();
                }
            }

            @Override
            public void componentShown(ComponentEvent e) {
                if (!collapsed) {
                    setDividerToRatio();
                }
            }
        });

        // Default show "home"
        contentCards.show(content, CARD_HOME);
    }

    /* ---------------- Sidebar ---------------- */

    private JPanel buildSidebar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Top bar: logo left, collapse button right
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);

        JLabel logoSmall = new JLabel(loadScaledIcon("appui/logo.png", 26, 26));
        logoSmall.setText("  "); // spacing
        logoSmall.setHorizontalAlignment(SwingConstants.LEFT);

        JButton btnToggle = new JButton("⟨");
        btnToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        btnToggle.setFocusPainted(false);
        btnToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnToggle.setToolTipText("最小化/恢复侧边栏");
        btnToggle.addActionListener(e -> toggleSidebar(btnToggle));

        topBar.add(logoSmall, BorderLayout.WEST);
        topBar.add(btnToggle, BorderLayout.EAST);

        // Center: nav buttons (only 首页 for now)
        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(new EmptyBorder(12, 0, 12, 0));

        JToggleButton homeBtn = new JToggleButton("首页");
        homeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        homeBtn.putClientProperty("JButton.buttonType", "roundRect");
        homeBtn.setFocusPainted(false);
        homeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        homeBtn.addActionListener(e -> contentCards.show(content, CARD_HOME));
        homeBtn.setSelected(true);

        ButtonGroup group = new ButtonGroup();
        group.add(homeBtn);

        nav.add(homeBtn);
        nav.add(Box.createVerticalGlue());

        // Bottom: settings button bottom-right
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottomBar.setOpaque(false);

        JButton settingsBtn = new JButton("设置");
        settingsBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        settingsBtn.setFocusPainted(false);
        settingsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        settingsBtn.addActionListener(e -> showInfo("设置", "设置界面后续开发（当前仅占位）"));

        bottomBar.add(settingsBtn);

        // Assemble
        p.add(topBar, BorderLayout.NORTH);
        p.add(nav, BorderLayout.CENTER);
        p.add(bottomBar, BorderLayout.SOUTH);

        return p;
    }

    private void toggleSidebar(JButton btnToggle) {
        if (!collapsed) {
            // collapse
            lastExpandedDivider = splitPane.getDividerLocation();
            splitPane.setDividerLocation(COLLAPSED_WIDTH);
            collapsed = true;
            btnToggle.setText("⟩");
        } else {
            // expand
            collapsed = false;
            if (lastExpandedDivider > 0) {
                splitPane.setDividerLocation(lastExpandedDivider);
            } else {
                setDividerToRatio();
            }
            btnToggle.setText("⟨");
        }
        revalidate();
        repaint();
    }

    private void setDividerToRatio() {
        int w = getWidth();
        if (w <= 0) return;
        int target = (int) Math.round(w * SIDEBAR_RATIO);
        target = Math.max(target, COLLAPSED_WIDTH);
        splitPane.setDividerLocation(target);
        lastExpandedDivider = target;
    }

    /* ---------------- Content Cards ---------------- */

    private JPanel buildHomeCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Big logo
        JLabel bigLogo = new JLabel(loadScaledIcon("appui/logo.png", 220, 220));
        bigLogo.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Title
        JLabel title = new JLabel("首页");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setBorder(new EmptyBorder(12, 0, 18, 0));

        // Buttons row
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        btnRow.setOpaque(false);

        JButton tutorialBtn = new JButton("查看教程");
        tutorialBtn.putClientProperty("JButton.buttonType", "roundRect");
        tutorialBtn.setFocusPainted(false);
        tutorialBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tutorialBtn.addActionListener(e -> showInfo("教程", "教程页面后续开发（当前仅占位）"));

        JButton notesBtn = new JButton("用户须知");
        notesBtn.putClientProperty("JButton.buttonType", "roundRect");
        notesBtn.setFocusPainted(false);
        notesBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        notesBtn.addActionListener(e -> openViewOnlyNotes());

        btnRow.add(tutorialBtn);
        btnRow.add(notesBtn);

        card.add(Box.createVerticalGlue());
        card.add(bigLogo);
        card.add(title);
        card.add(btnRow);
        card.add(Box.createVerticalGlue());

        return card;
    }

    /* ---------------- Dialogs ---------------- */

    private void openViewOnlyNotes() {
        Window owner = SwingUtilities.getWindowAncestor(this);

        JDialog dialog = new JDialog(owner, "用户须知（只读）", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(700, 520);
        dialog.setLocationRelativeTo(owner);
        dialog.setLayout(new BorderLayout());

        JTextArea area = new JTextArea(loadTextResource("appui/notes.txt"));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(new EmptyBorder(16, 16, 16, 16));
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        area.setCaretPosition(0);

        // ✅ 隐藏“文本输入光标”(caret)，但不影响鼠标指针
        area.getCaret().setVisible(false);
        area.getCaret().setSelectionVisible(false);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(new EmptyBorder(0, 12, 0, 12));

        JButton close = new JButton("关闭");
        close.putClientProperty("JButton.buttonType", "roundRect");
        close.setFocusPainted(false);
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addActionListener(e -> dialog.dispose());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        bottom.add(close);

        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showInfo(String title, String msg) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JOptionPane.showMessageDialog(owner, msg, title, JOptionPane.INFORMATION_MESSAGE);
    }

    /* ---------------- Resource helpers ---------------- */

    private Icon loadScaledIcon(String resourcePath, int w, int h) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return UIManager.getIcon("OptionPane.informationIcon");
            }
            byte[] bytes = in.readAllBytes();
            ImageIcon icon = new ImageIcon(bytes);
            Image scaled = icon.getImage().getScaledInstance(w, h, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return UIManager.getIcon("OptionPane.informationIcon");
        }
    }

    private String loadTextResource(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return "未找到资源文件：" + resourcePath + "\n\n请确认位于：src/main/resources/" + resourcePath;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }
}
