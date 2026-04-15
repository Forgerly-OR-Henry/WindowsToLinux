package gold.debug.wintolin.appui;

import gold.debug.wintolin.exceptionanderror.MyException;

import javax.swing.*;
import java.awt.*;

public final class PopUp {
    // ====== 业务弹窗：集中管理文案 ======

    /**
     * 标准报错弹窗
     *
     * @param e 标准错误
     * @param parent 当前报错产生的窗口（用于将弹窗居中）
     */
    public static void showErrorDialog(MyException e, Component parent) {
        if (e == null) {
            JOptionPane.showMessageDialog(
                    parent,
                    "发生未知错误：MyException 对象为空。",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        StringBuilder content = new StringBuilder();
        content.append("错误码：").append(e.getErrorCode()).append("\n")
               .append("来源类：").append(e.getSourceClassName()).append("\n")
               .append("来源方法：").append(e.getSourceMethodName()).append("\n")
               .append("错误信息：").append(e.getMessage());

        if (e.getCause() != null) {
            content.append("\n")
                   .append("原始异常：").append(e.getCause().getClass().getName());

            if (e.getCause().getMessage() != null && !e.getCause().getMessage().isBlank()) {
                content.append("\n")
                       .append("原始异常信息：").append(e.getCause().getMessage());
            }
        }

        JOptionPane.showMessageDialog(
                parent,
                content.toString(),
                "错误",
                JOptionPane.ERROR_MESSAGE
        );
    }

    /** 用户须知：点击不同意后的提示 */
    public static void userNotesDisagree(Component parent) {
        JOptionPane.showMessageDialog(
                parent,
                "如不同意用户须知，可联系开发者获取授权或更换软件。\n将返回开始欢迎界面。",
                "提示",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    /** 设置页面暂未实现 */
    public static void settingsNotReady(Component parent) {
        JOptionPane.showMessageDialog(
                parent,
                "设置界面后续开发（当前仅占位）",
                "设置",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    /** 教程页面暂未实现 */
    public static void tutorialNotReady(Component parent) {
        JOptionPane.showMessageDialog(
                parent,
                "教程页面后续开发（当前仅占位）",
                "教程",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    // 你后续每加一个弹窗，就在这里加一个函数即可
}