package gold.debug.windowstolinux.app.ui.component;

import com.formdev.flatlaf.FlatClientProperties;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Objects;

/**
 * Provides the {@code DesktopComponentFactory} implementation.
 *
 * <p>提供 {@code DesktopComponentFactory} 实现。
 */
public final class DesktopComponentFactory {
    private final ThemePalette palette;

    /**
     * Creates a {@code DesktopComponentFactory} instance.
     *
     * <p>创建 {@code DesktopComponentFactory} 实例。
     *
     * @param palette the {@code palette} value / {@code palette} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopComponentFactory(ThemePalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    /**
     * Performs the {@code pagePanel} operation.
     *
     * <p>执行 {@code pagePanel} 操作。
     *
     * @return the operation result / 操作结果
     */
    public JPanel pagePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setBackground(palette.pageBackground());
        return panel;
    }

    /**
     * Performs the {@code transparent} operation.
     *
     * <p>执行 {@code transparent} 操作。
     *
     * @param layout the {@code layout} value / {@code layout} 值
     * @return the operation result / 操作结果
     */
    public JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /**
     * Performs the {@code badge} operation.
     *
     * <p>执行 {@code badge} 操作。
     *
     * @param text the {@code text} value / {@code text} 值
     * @return the operation result / 操作结果
     */
    public JLabel badge(String text) {
        JLabel badge = new JLabel(text);
        badge.setOpaque(true);
        badge.setBackground(palette.badgeBackground());
        badge.setForeground(palette.accentDark());
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
        badge.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        return badge;
    }

    /**
     * Performs the {@code card} operation.
     *
     * <p>执行 {@code card} 操作。
     *
     * @param layout the {@code layout} value / {@code layout} 值
     * @return the operation result / 操作结果
     */
    public JPanel card(LayoutManager layout) {
        JPanel card = new JPanel(layout);
        card.setBackground(palette.cardBackground());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.cardBorder()),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        return card;
    }

    /**
     * Performs the {@code sectionHeading} operation.
     *
     * <p>执行 {@code sectionHeading} 操作。
     *
     * @param title the {@code title} value / {@code title} 值
     * @param description the {@code description} value / {@code description} 值
     * @return the operation result / 操作结果
     */
    public JComponent sectionHeading(String title, String description) {
        JPanel heading = transparent(new BorderLayout(0, 3));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        JLabel detail = new JLabel(description);
        detail.setForeground(palette.subduedText());
        detail.setFont(detail.getFont().deriveFont(12f));
        heading.add(label, BorderLayout.NORTH);
        heading.add(detail, BorderLayout.SOUTH);
        return heading;
    }

    /**
     * Performs the {@code stepCard} operation.
     *
     * <p>执行 {@code stepCard} 操作。
     *
     * @param index the {@code index} value / {@code index} 值
     * @param title the {@code title} value / {@code title} 值
     * @param description the {@code description} value / {@code description} 值
     * @param action the {@code action} value / {@code action} 值
     * @return the operation result / 操作结果
     */
    public JPanel stepCard(String index, String title, String description, JButton action) {
        JPanel card = card(new BorderLayout(0, 12));
        JPanel copy = transparent(new BorderLayout(0, 4));
        JLabel number = new JLabel(index);
        number.setForeground(palette.accent());
        number.setFont(number.getFont().deriveFont(Font.BOLD, 12f));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        JLabel detail = new JLabel("<html><body style='width: 170px'>" + description + "</body></html>");
        detail.setForeground(palette.subduedText());
        detail.setFont(detail.getFont().deriveFont(12f));
        copy.add(number, BorderLayout.NORTH);
        copy.add(heading, BorderLayout.CENTER);
        copy.add(detail, BorderLayout.SOUTH);
        card.add(copy, BorderLayout.CENTER);
        card.add(action, BorderLayout.SOUTH);
        return card;
    }

    /**
     * Performs the {@code primaryButton} operation.
     *
     * <p>执行 {@code primaryButton} 操作。
     *
     * @param text the {@code text} value / {@code text} 值
     * @return the operation result / 操作结果
     */
    public JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(palette.accent());
        button.setForeground(Color.WHITE);
        decorate(button);
        return button;
    }

    /**
     * Performs the {@code secondaryButton} operation.
     *
     * <p>执行 {@code secondaryButton} 操作。
     *
     * @param text the {@code text} value / {@code text} 值
     * @return the operation result / 操作结果
     */
    public JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(palette.secondaryButtonBackground());
        button.setForeground(palette.secondaryButtonForeground());
        decorate(button);
        return button;
    }

    /**
     * Performs the {@code addField} operation.
     *
     * <p>执行 {@code addField} 操作。
     *
     * @param panel the {@code panel} value / {@code panel} 值
     * @param row the {@code row} value / {@code row} 值
     * @param column the {@code column} value / {@code column} 值
     * @param label the {@code label} value / {@code label} 值
     * @param component the {@code component} value / {@code component} 值
     */
    public void addField(JPanel panel, int row, int column, String label, Component component) {
        int leftColumn = column * 2;
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = leftColumn;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(0, 0, 10, 8);
        JLabel labelComponent = new JLabel(label);
        labelComponent.setForeground(palette.subduedText());
        panel.add(labelComponent, labelConstraints);
        GridBagConstraints inputConstraints = new GridBagConstraints();
        inputConstraints.gridx = leftColumn + 1;
        inputConstraints.gridy = row;
        inputConstraints.weightx = 1;
        inputConstraints.fill = GridBagConstraints.HORIZONTAL;
        inputConstraints.insets = new Insets(0, 0, 10, column == 0 ? 22 : 0);
        panel.add(component, inputConstraints);
    }

    /**
     * Performs the {@code outputCard} operation.
     *
     * <p>执行 {@code outputCard} 操作。
     *
     * @param title the {@code title} value / {@code title} 值
     * @param description the {@code description} value / {@code description} 值
     * @param output the {@code output} value / {@code output} 值
     * @return the operation result / 操作结果
     */
    public JPanel outputCard(String title, String description, JTextArea output) {
        JPanel card = card(new BorderLayout(0, 12));
        card.add(sectionHeading(title, description), BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(output);
        scrollPane.setBorder(BorderFactory.createLineBorder(palette.inputBorder()));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        card.add(scrollPane, BorderLayout.CENTER);
        return card;
    }

    /**
     * Performs the {@code informationCard} operation.
     *
     * <p>执行 {@code informationCard} 操作。
     *
     * @param title the {@code title} value / {@code title} 值
     * @param message the {@code message} value / {@code message} 值
     * @return the operation result / 操作结果
     */
    public JPanel informationCard(String title, String message) {
        JPanel card = card(new BorderLayout(0, 10));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        JTextArea detail = new JTextArea(message.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", ""));
        detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true); detail.setOpaque(false);
        detail.setRows(5); detail.setColumns(16);
        detail.setForeground(palette.subduedText());
        detail.setFont(detail.getFont().deriveFont(13f));
        card.add(heading, BorderLayout.NORTH);
        card.add(detail, BorderLayout.CENTER);
        return card;
    }

    /**
     * Performs the {@code outputArea} operation.
     *
     * <p>执行 {@code outputArea} 操作。
     *
     * @return the operation result / 操作结果
     */
    public static JTextArea outputArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMargin(new Insets(12, 12, 12, 12));
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    private static void decorate(JButton button) {
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
    }
}
