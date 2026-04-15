package gold.debug.wintolin.appui.welcome;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class UserNotes extends JDialog {

    private final JButton agreeBtn = new JButton();
    private final JButton disagreeBtn = new JButton("不同意");

    private Timer countdownTimer;
    private int secondsLeft = 5;

    public UserNotes(JFrame owner, Runnable onAgree, Runnable onDisagree) {
        super(owner, "用户须知", true);

        setSize(640, 520);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        JLabel top = new JLabel("请认真阅读以下内容", SwingConstants.LEFT);
        top.setBorder(new EmptyBorder(14, 16, 10, 16));
        top.setFont(top.getFont().deriveFont(Font.BOLD, 14f));
        add(top, BorderLayout.NORTH);

        JTextArea area = new JTextArea(loadNotesText());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(new EmptyBorder(16, 16, 16, 16));
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        area.setCaretPosition(0);
        // 隐藏文本输入光标
        area.setCaretColor(new Color(0, 0, 0, 0)); // 透明
        area.getCaret().setSelectionVisible(false);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(new EmptyBorder(0, 16, 0, 16));
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));

        agreeBtn.setEnabled(false);
        agreeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        disagreeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        bottom.add(disagreeBtn);
        bottom.add(agreeBtn);

        add(bottom, BorderLayout.SOUTH);

        // 进入页面就开始倒计时
        startCountdown(top);

        agreeBtn.addActionListener(e -> {
            stopCountdown();
            dispose();
            onAgree.run();
        });

        disagreeBtn.addActionListener(e -> {
            stopCountdown();
            dispose();
            onDisagree.run();
        });
    }

    /* 倒计时逻辑 */
    private void startCountdown(JLabel topLabel) {
        secondsLeft = 5;
        agreeBtn.setText("同意（请等待 " + secondsLeft + " 秒）");

        countdownTimer = new Timer(1000, e -> {
            secondsLeft--;
            if (secondsLeft > 0) {
                agreeBtn.setText("同意（请等待 " + secondsLeft + " 秒）");
            } else {
                agreeBtn.setEnabled(true);
                agreeBtn.setText("同意并继续");
                topLabel.setText("已满足条件：可以点击“同意并继续”进入软件");
                stopCountdown();
            }
        });

        countdownTimer.setInitialDelay(0);
        countdownTimer.start();
    }

    private void stopCountdown() {
        if (countdownTimer != null) {
            countdownTimer.stop();
            countdownTimer = null;
        }
    }

    /* 读取 resources/appui/notes.txt */
    private String loadNotesText() {
        String path = "appui/notes.txt";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return "未找到资源文件：" + path +
                        "\n\n请确认文件位于：src/main/resources/appui/notes.txt";
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取用户须知失败：" + e.getMessage();
        }
    }
}
