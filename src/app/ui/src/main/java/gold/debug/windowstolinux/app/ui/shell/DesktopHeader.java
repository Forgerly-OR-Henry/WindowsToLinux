package gold.debug.windowstolinux.app.ui.shell;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.ThemePalette;

/** Builds the localized header independently of window security and lifecycle. / 独立于窗口安全及生命周期构建本地化页头。 */
final class DesktopHeader {
    /** Prevents instances of this layout factory. / 禁止实例化布局工厂。 */
    private DesktopHeader() {
    }

    /** Assembles the current page heading and action area. / 装配当前页面标题及动作区域。
     * @param palette selected colors / 所选配色
     * @param pageTitle current page title / 当前页面标题
     * @param pageDescription current page description / 当前页面说明
     * @param headerActions page actions / 页面动作
     * @param badge management indicator / 管理状态指示
     * @return assembled header panel / 装配后的页头面板
     */
    static JComponent create(ThemePalette palette, JLabel pageTitle, JLabel pageDescription, JPanel headerActions,
            JComponent badge) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(palette.cardBackground());
        header.setBorder(
                BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.cardBorder()),
                        BorderFactory.createEmptyBorder(14, 22, 14, 22)));

        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.setOpaque(false);
        pageTitle.setFont(pageTitle.getFont().deriveFont(Font.BOLD, 20f));
        pageTitle.setForeground(palette.sidebarForeground());
        pageDescription.setFont(pageDescription.getFont().deriveFont(12f));
        pageDescription.setForeground(palette.subduedText());
        heading.add(pageTitle, BorderLayout.NORTH);
        heading.add(pageDescription, BorderLayout.SOUTH);
        header.add(heading, BorderLayout.CENTER);
        JPanel status = new JPanel(new java.awt.GridBagLayout());
        status.setOpaque(false);
        status.add(badge);
        headerActions.setOpaque(false);
        status.add(headerActions);
        header.add(status, BorderLayout.EAST);
        return header;
    }
}
