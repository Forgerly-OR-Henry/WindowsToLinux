package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.util.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.service.contract.profile.AiProviderProfile;
import gold.debug.windowstolinux.app.service.contract.result.AiProviderSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.shared.model.ai.AiCapabilityType;

/** Edits a model and observes its centrally owned verification task. / 编辑模型并观察中央持有的验证任务。 */
final class PlatformModelDialog extends JDialog {
    /** Exact host settings owner. / 精确宿主设置所有者。 */
    private final PlatformModelService service;

    /** Safe localized messages. / 安全本地化消息。 */
    private final PageMessagePresenter messages;

    /** Completion refresh callback. / 完成刷新回调。 */
    private final Runnable saved;

    /** Original metadata or new-model identity. / 原元数据或新模型身份。 */
    private final String id, credential;

    /** Non-secret editable fields. / 非秘密可编辑字段。 */
    private final JTextField name = new JTextField(28), endpoint = new JTextField(28), model = new JTextField(28),
            limit = new JTextField(12);

    /** Temporary input credential, cleared immediately on submission. / 提交时立即清空的临时输入凭据。 */
    private final JPasswordField key = new JPasswordField(28);

    /** Independently selected verification capability. / 独立选择的验证能力。 */
    private final JComboBox<AiCapabilityType> capability = new JComboBox<>(AiCapabilityType.values());

    /** Safe outcome display. / 安全结果展示。 */
    private final JTextArea status = DesktopComponentFactory.outputArea();

    /** Explicit test-and-save action. / 明确测试并保存操作。 */
    private final JButton save;

    /** Input panel whose controls are frozen during verification. / 验证期间冻结控件的输入面板。 */
    private final JPanel form;

    /** Pending actual task handle. / 待完成实际任务句柄。 */
    private TaskHandle<Void> task;

    /** Whether admission or execution remains outstanding. / 准入或执行是否仍未完成。 */
    private boolean busy, closing, unknown;

    /** Observes retained uncertainty without waiting for terminal completion. / 无需等待终态完成即可观察保留的不确定状态。 */
    private final javax.swing.Timer timer;

    /** Builds the existing model form around explicit budgets and task outcomes. / 围绕明确预算及任务结果构造模型表单。
     * @param owner actual desktop owner / 实际桌面所有者
     * @param service actual host settings / 实际宿主设置
     * @param components themed controls / 主题控件
     * @param messages localized messages / 本地化消息
     * @param existing optional current row / 可选当前行
     * @param outputLimit explicit existing budget / 明确已有预算
     * @param saved successful refresh callback / 成功刷新回调 */
    PlatformModelDialog(Window owner, PlatformModelService service, DesktopComponentFactory components,
            PageMessagePresenter messages, AiProviderSummary existing, OptionalInt outputLimit, Runnable saved) {
        super(owner, messages.text(existing == null ? "ai.models.add" : "ai.models.edit"),
                ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.messages = messages;
        this.saved = saved;
        id = existing == null ? "model-" + UUID.randomUUID().toString().substring(0, 12) : existing.profile().id();
        credential = existing == null ? "ai/provider/" + id + "/api-key" : existing.profile().credentialKey();
        setName("platform.model.editor");
        form = components.card(new GridBagLayout());
        var fields = new JTextField[]{name, endpoint, model, key, limit};
        var labels = new String[]{"ai.models.name", "field.aiEndpoint", "field.model", "field.apiKey",
                "platform.models.limit"};
        var names = new String[]{"model.name", "model.endpoint", "model.model", "model.key", "model.limit"};
        for (int i = 0; i < fields.length; i++) {
            fields[i].setName(names[i]);
            components.addField(form, i, 0, messages.text(labels[i]), fields[i]);
        }
        if (existing != null) {
            name.setText(existing.name());
            endpoint.setText(existing.profile().chatCompletionsEndpoint().toString());
            model.setText(existing.profile().model());
            key.putClientProperty("JTextField.placeholderText", messages.text("ai.models.keepKey"));
        }
        if (outputLimit.isPresent())
            limit.setText(Integer.toString(outputLimit.getAsInt()));
        capability.setName("model.capability");
        messages.localize(capability, "ai.capability.");
        components.addField(form, 5, 0, messages.text("ai.capability.label"), capability);
        save = components.primaryButton(messages.text("ai.models.testSave"));
        save.setName("model.save");
        save.addActionListener(event -> submit());
        var cancel = components.secondaryButton(messages.text("button.cancel"));
        cancel.setName("model.cancel");
        cancel.addActionListener(event -> closeEditor());
        var actions = components.transparent(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(save);
        status.setName("model.status");
        status.setRows(4);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setText(messages.text("platform.models.probeHint"));
        var body = components.pagePanel();
        body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        body.add(form, BorderLayout.NORTH);
        body.add(new JScrollPane(status));
        body.add(actions, BorderLayout.SOUTH);
        setContentPane(body);
        setSize(650, 540);
        setMinimumSize(new Dimension(600, 520));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /** Delegates close through cancellation. / 通过取消处理关闭。
             * @param event close event / 关闭事件 */
            @Override
            public void windowClosing(WindowEvent event) {
                closeEditor();
            }
        });
        timer = new javax.swing.Timer(250, event -> {
            if (task != null && task.snapshot().state() == TaskState.UNKNOWN) {
                unknown = true;
                status.setText(messages.text("platform.models.unknown"));
                if (closing)
                    dispose();
            }
        });
        timer.start();
    }

    /** Captures fields and sends only explicit user-approved probe data. / 捕获字段并仅发送用户明确启动的探测数据。 */
    private void submit() {
        if (busy)
            return;
        char[] supplied = null;
        try {
            var profile = new AiProviderProfile(id, URI.create(endpoint.getText().strip()), model.getText().strip(),
                    credential);
            int ceiling = Integer.parseInt(limit.getText().strip());
            if (ceiling < 1 || name.getText().isBlank())
                throw new IllegalArgumentException("Invalid model form");
            String label = name.getText();
            var selected = (AiCapabilityType) capability.getSelectedItem();
            supplied = key.getPassword();
            key.setText("");
            char[] input = supplied;
            setBusy(true);
            status.setText(messages.text("ai.models.testing"));
            DesktopTaskExecutor.run(() -> {
                try {
                    return service.verifyAndSave(profile, label, input, selected, ceiling);
                } finally {
                    Arrays.fill(input, '\0');
                }
            }, handle -> {
                task = handle;
                if (closing)
                    cancelTask();
                handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                    task = null;
                    if (!isDisplayable())
                        return;
                    setBusy(false);
                    if (failure != null)
                        status.setText(messages.text("platform.models.failed"));
                    else if (result.outcome() == Outcome.SUCCEEDED) {
                        saved.run();
                        dispose();
                    } else
                        status.setText(result.problem().map(Problem::message).orElse(messages.text("button.cancel")));
                    if (closing)
                        dispose();
                }));
            }, failure -> {
                setBusy(false);
                status.setText(messages.safe(failure));
                if (closing)
                    dispose();
            });
        } catch (Exception failure) {
            if (supplied != null)
                Arrays.fill(supplied, '\0');
            setBusy(false);
            status.setText(messages.text("ai.models.invalidForm"));
        }
    }

    /** Cancels the real task without interrupting the UI worker. / 取消真实任务，不中断界面工作线程。 */
    private void cancelTask() {
        var current = task;
        if (current != null)
            DesktopTaskExecutor.run(() -> {
                current.requestCancel();
                return true;
            }, ignored -> {
            }, failure -> status.setText(messages.safe(failure)));
    }

    /** Allows an unknown task to remain in the permanent recovery list. / 允许未知任务保留在常驻恢复列表。 */
    private void closeEditor() {
        if (!busy || unknown) {
            dispose();
            return;
        }
        closing = true;
        cancelTask();
        status.setText(messages.text("ai.models.cancelling"));
    }

    /** Freezes fields while the exact submitted draft is running. / 精确提交草稿执行期间冻结字段。
     * @param value whether operation is pending / 操作是否待完成 */
    private void setBusy(boolean value) {
        busy = value;
        save.setEnabled(!value);
        for (var child : form.getComponents())
            child.setEnabled(!value);
    }

    /** Stops observation and clears the credential control. / 停止观察并清空凭据控件。 */
    @Override
    public void dispose() {
        if (timer != null)
            timer.stop();
        key.setText("");
        super.dispose();
    }
}
