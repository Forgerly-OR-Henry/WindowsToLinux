package gold.debug.wintolin.appui;

import javax.swing.*;
import java.awt.*;

public final class PopUp {
    // ====== 业务弹窗：集中管理文案 ======

    /** 用户须知：点击不同意后的提示 */
    public static void userNotesDisagree(Window owner) {
        JOptionPane.showMessageDialog(
                owner,
                "如不同意用户须知，可联系开发者获取授权或更换软件。\n将返回开始欢迎界面。",
                "提示",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    /** 设置页面暂未实现 */
    public static void settingsNotReady(Component parent) {
        JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(parent),
                "设置界面后续开发（当前仅占位）",
                "设置",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    /** 教程页面暂未实现 */
    public static void tutorialNotReady(Component parent) {
        JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(parent),
                "教程页面后续开发（当前仅占位）",
                "教程",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    // 你后续每加一个弹窗，就在这里加一个函数即可
}