package gold.debug.windowstolinux.app.ui.recovery;

import java.awt.*;
import java.awt.event.*;
import java.util.Optional;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.app.service.contract.definition.RecoveryTerminal;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter;

/**
 * Modeless manual login handoff and exact command approval. / 非模态人工登录交接及精确命令批准。
 */
public final class SshRecoveryDialog {
    /**
     * Swing control for dialog.
     * <p>对话框对应的 Swing 控件。
     */
    private final JDialog dialog;

    /**
     * Session used for the current scoped operation.
     * <p>当前限定作用域操作使用的会话。
     */
    private final SshRecoverySession session;

    /**
     * Server identity or selected server configuration.
     * <p>服务器身份或所选服务器配置。
     */
    private final ServerProfile server;

    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;

    /**
     * Swing control for details.
     * <p>详情对应的 Swing 控件。
     */
    private final JTextArea details = DesktopComponentFactory.outputArea();

    /**
     * Terminals.
     * <p>终端集合。
     */
    private final JComboBox<RecoveryTerminal> terminals = new JComboBox<>();

    /**
     * Swing control for identity.
     * <p>身份对应的 Swing 控件。
     */
    private final JTextField identity = new JTextField(16);

    /**
     * Swing control for consent.
     * <p>同意对应的 Swing 控件。
     */
    private final JCheckBox consent;

    /**
     * Swing control for reconciled.
     * <p>已核对对应的 Swing 控件。
     */
    private final JCheckBox reconciled;

    /**
     * Swing control for bind.
     * <p>绑定对应的 Swing 控件。
     */
    private final JButton bind;

    /**
     * Confirms the currently displayed action, requiring separate high-impact consent when applicable.
     * <p>确认当前显示的动作，适用时要求额外的高影响同意。
     */
    private final JButton approve;

    /**
     * Swing control for refresh.
     * <p>刷新对应的 Swing 控件。
     */
    private final JButton refresh;

    /**
     * Swing event-thread timer for timer.
     * <p>定时器使用的 Swing 事件线程定时器。
     */
    private final Timer timer;

    /**
     * Displayed.
     * <p>已展示。
     */
    private RecoverySnapshot displayed;

    /** Opens the original platform rescue with navigation to permanent model settings. / 打开原平台救援并导航到常驻模型设置。
     * @param owner actual platform parent / 实际平台父级
     * @param server admitted server / 准入服务器
     * @param session original central-task session / 原中央任务会话
     * @param settings model-settings navigation / 模型设置导航
     * @param components themed controls / 主题控件
     * @param messages localized messages / 本地化消息
     * @return original modeless rescue view / 原非模态救援视图 */
    public static SshRecoveryDialog platform(Component owner, ServerProfile server, SshRecoverySession session,
            Runnable settings, DesktopComponentFactory components, PageMessagePresenter messages) {
        return new SshRecoveryDialog(owner, server, session, () -> {
            session.pause();
            settings.run();
        }, components, messages);
    }

    /** Builds one modeless view with the supplied settings navigation. / 使用给定设置导航构建一个非模态视图。
     * @param owner actual parent / 实际父级
     * @param server admitted server / 准入服务器
     * @param session original rescue / 原救援
     * @param settings settings navigation / 设置导航
     * @param components themed controls / 主题控件
     * @param messages localized messages / 本地化消息 */
    private SshRecoveryDialog(Component owner, ServerProfile server, SshRecoverySession session, Runnable settings,
            DesktopComponentFactory components, PageMessagePresenter messages) {
        this.server = server;
        this.session = session;
        this.messages = messages;
        dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), messages.text("recovery.title"),
                Dialog.ModalityType.MODELESS);
        gold.debug.windowstolinux.app.ui.i18n.LocaleBinding.title(dialog,
                gold.debug.windowstolinux.app.ui.i18n.LocalizedMessage.of("recovery.title"), messages.catalog());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setName("recovery.dialog");
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(15);
        details.setColumns(80);
        panel.add(new JScrollPane(details), BorderLayout.CENTER);
        JPanel handoff = new JPanel(new GridLayout(0, 1, 4, 4));
        handoff.add(new JLabel(messages.text("recovery.target",
                java.util.Map.of("id", server.id(), "host", server.host(), "port", server.sshPort()))));
        handoff.add(messages.bind(new JLabel(), "recovery.handoff"));
        handoff.add(terminals);
        JPanel target = new JPanel(new FlowLayout(FlowLayout.LEFT));
        target.add(messages.bind(new JLabel(), "recovery.identity"));
        target.add(identity);
        handoff.add(target);
        consent = new JCheckBox(messages.text("recovery.consent"));
        handoff.add(consent);
        reconciled = new JCheckBox(messages.text("recovery.reconciled"));
        handoff.add(reconciled);
        panel.add(handoff, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        refresh = messages.bind(components.secondaryButton(""), "recovery.refresh");
        buttons.add(refresh);
        refresh.addActionListener(event -> refresh());
        bind = messages.bind(components.secondaryButton(""), "recovery.bind");
        buttons.add(bind);
        bind.addActionListener(event -> bindSelection());
        approve = messages.bind(components.secondaryButton(""), "recovery.approve");
        buttons.add(approve);
        approve.addActionListener(event -> approve());
        JButton pause = messages.bind(components.secondaryButton(""), "recovery.pause");
        buttons.add(pause);
        pause.addActionListener(event -> {
            session.pause();
            consent.setSelected(false);
        });
        JButton resume = messages.bind(components.secondaryButton(""), "recovery.resume");
        buttons.add(resume);
        resume.addActionListener(event -> {
            session.resume();
            terminals.removeAllItems();
            consent.setSelected(false);
        });
        JButton settingsButton = messages.bind(components.secondaryButton(""), "auto.ai.setup");
        buttons.add(settingsButton);
        settingsButton.addActionListener(event -> settings.run());
        JButton end = messages.bind(components.secondaryButton(""), "recovery.end");
        buttons.add(end);
        end.addActionListener(event -> close());
        panel.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.addWindowListener(new WindowAdapter() {
            /**
             * Handles the user's close request through the owning window's cleanup path.
             * <p>通过所属窗口的清理路径处理用户关闭请求。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }

            /**
             * Stops view polling after disposal; original task ownership remains with the service.
             * <p>销毁后停止视图轮询；原任务归属仍由服务持有。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void windowClosed(WindowEvent event) {
                timer.stop();
            }
        });
        timer = new Timer(400, event -> update());
        timer.start();
        update();
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Binds selection.
     * <p>绑定选择。
     */
    private void bindSelection() {
        RecoveryTerminal target = (RecoveryTerminal) terminals.getSelectedItem();
        if (target != null && identity.getText().trim().equals(server.id()) && consent.isSelected()) {
            session.bindTerminal(target.id(), identity.getText().trim(), true, reconciled.isSelected());
            terminals.removeAllItems();
            consent.setSelected(false);
            reconciled.setSelected(false);
        }
    }

    /**
     * Refreshes ssh recovery dialog.
     * <p>刷新SSH恢复对话框。
     */
    private void refresh() {
        refresh.setEnabled(false);
        DesktopTaskExecutor.run(session::terminals, values -> {
            terminals.removeAllItems();
            values.forEach(terminals::addItem);
            refresh.setEnabled(true);
            if (values.isEmpty())
                details.setText(messages.text("recovery.noTerminal"));
        }, failure -> {
            refresh.setEnabled(true);
            details.setText(messages.safe(failure));
        });
    }

    /**
     * Confirms the currently displayed action, requiring separate high-impact consent when applicable.
     * <p>确认当前显示的动作，适用时要求额外的高影响同意。
     */
    private void approve() {
        RecoverySnapshot current = displayed;
        if (current == null || current.action().isEmpty())
            return;
        boolean high = current.action().orElseThrow().highImpact();
        if (high && !confirmImpact(current))
            return;
        session.confirmAction(current.confirmation(), high);
        approve.setEnabled(false);
    }

    /**
     * Confirms impact.
     * <p>确认影响。
     *
     * @param current current / 当前
     * @return true when confirms impact, false otherwise / 确认影响时为 true，否则为 false
     */
    private boolean confirmImpact(RecoverySnapshot current) {
        JTextArea review = DesktopComponentFactory.outputArea();
        review.setRows(15);
        review.setColumns(70);
        review.setLineWrap(true);
        review.setWrapStyleWord(true);
        review.setText(messages.text("recovery.highImpact") + "\n\n" + current.action().orElseThrow().command() + "\n\n"
                + current.action().orElseThrow().expected());
        Object cancel = messages.text("button.cancel"), accept = messages.text("recovery.approve");
        return JOptionPane.showOptionDialog(dialog, new JScrollPane(review), messages.text("recovery.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, new Object[]{cancel, accept},
                cancel) == 1;
    }

    /**
     * Refreshes recovery state, safe error text and the availability of binding and approval controls from the latest snapshot.
     * <p>根据最新快照刷新救援状态、安全错误文本及绑定和批准控件的可用性。
     */
    private void update() {
        if (gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor.isLocked())
            return;
        RecoverySnapshot value = session.snapshot();
        if (!value.equals(displayed)) {
            displayed = value;
            String text = messages.text("recovery." + value.messageCode());
            if (value.failure().isPresent())
                text += "\n\n" + messages.safe(value.failure().orElseThrow());
            if (value.resultUnknown())
                text += "\n\n" + messages.text("recovery.resultUnknown");
            for (var attempt : value.modelAttempts().entrySet())
                text += "\n" + attempt.getKey() + " · " + messages
                        .text("recovery.modelStatus." + attempt.getValue().toLowerCase(java.util.Locale.ROOT));
            if (value.action().isPresent()) {
                var action = value.action().orElseThrow();
                text += "\n\n" + action.command() + "\n\n" + action.reason() + "\n\n" + action.expected();
            }
            details.setText(text);
        }
        boolean waiting = value.state() == RecoverySnapshot.State.WAITING_TERMINAL
                || value.state() == RecoverySnapshot.State.PAUSED
                || value.state() == RecoverySnapshot.State.INTERRUPTED;
        refresh.setEnabled(waiting);
        bind.setEnabled(waiting);
        approve.setEnabled(value.state() == RecoverySnapshot.State.AWAITING_CONFIRMATION);
        reconciled.setVisible(value.resultUnknown());
        if (value.ended()) {
            approve.setEnabled(false);
            bind.setEnabled(false);
            refresh.setEnabled(false);
        }
    }

    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    private void close() {
        if (gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor.isLocked())
            return;
        DesktopTaskExecutor.run(() -> {
            session.close();
            return null;
        }, ignored -> {
            timer.stop();
            details.setText("");
            dialog.dispose();
        }, failure -> details.setText(messages.safe(failure)));
    }
}
