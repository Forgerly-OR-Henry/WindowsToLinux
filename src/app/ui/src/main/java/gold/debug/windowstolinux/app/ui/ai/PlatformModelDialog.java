package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskState;
import gold.debug.windowstolinux.appapi.ai.VoiceRequest;

import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.ui.control.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Saves explicit model configuration and separately observes capability verification. / 保存明确模型配置并单独观察能力验证。 */
final class PlatformModelDialog extends JDialog {
    /** Actual service or isolated preview absence. / 实际服务或隔离预览空值。 */
    private final Optional<PlatformModelService> service;

    /** Safe localized messages. / 安全本地化消息。 */
    private final PageMessagePresenter messages;

    /** Inventory refresh callback. / 清单刷新回调。 */
    private final Runnable saved;

    /** Stable model identity. / 稳定模型标识。 */
    private final String id;

    /** Current protected credential reference. / 当前受保护凭据引用。 */
    private String credential;

    /** Original editable configuration controls. / 原可编辑配置控件。 */
    private final ModelFields fields = new ModelFields();

    /** Independently chosen verification mode. / 独立选择的验证方式。 */
    private final JComboBox<AiCapabilityType> capability = new JComboBox<>(AiCapabilityType.values());

    /** Safe operation feedback. / 安全操作反馈。 */
    private final JTextArea status = DesktopComponentFactory.outputArea();

    /** Independent configuration action. / 独立配置操作。 */
    private final JButton save;

    /** Explicit verification action. / 明确验证操作。 */
    private final JButton verify;

    /** Input layout frozen during accepted work. / 已接纳工作期间冻结的输入布局。 */
    private final JPanel form;

    /** Last saved form, used to reject unsaved verification drafts. / 最近保存表单，用于拒绝验证未保存草稿。 */
    private SavedDraft stored;

    /** Exact central task handle. / 精确中央任务句柄。 */
    private TaskHandle<Void> task;

    /** Admission, closing and retained-uncertainty flags. / 准入、关闭及保留不确定状态标志。 */
    private boolean busy, closing, unknown;

    /** Observer of the actual task state. / 实际任务状态观察器。 */
    private final javax.swing.Timer timer;

    /** Builds the model editor around explicit save and verification actions. / 围绕明确保存与验证操作构造模型编辑器。
     * @param owner actual desktop owner / 实际桌面所有者
     * @param service actual host settings / 实际宿主设置
     * @param components themed controls / 主题控件
     * @param messages localized messages / 本地化消息
     * @param existing optional current row / 可选当前行
     * @param outputLimit explicit existing budget / 明确已有预算
     * @param saved inventory refresh callback / 清单刷新回调 */
    PlatformModelDialog(Window owner, PlatformModelService service, DesktopComponentFactory components,
                        PageMessagePresenter messages, AiProviderSummary existing, OptionalInt outputLimit, Runnable saved) {
        this(owner, Optional.of(Objects.requireNonNull(service)), components, messages, existing, outputLimit, saved);
    }

    /** Creates the editor with explicit service absence only for preview. / 仅为预览以明确空服务创建编辑器。
     * @param owner original owner / 原所有者
     * @param service capability or preview absence / 能力或预览空值
     * @param components themed controls / 主题控件
     * @param messages localized display / 本地化显示
     * @param existing original row / 原行
     * @param outputLimit explicit budget / 明确预算
     * @param saved refresh callback / 刷新回调 */
    private PlatformModelDialog(Window owner, Optional<PlatformModelService> service,
                                DesktopComponentFactory components, PageMessagePresenter messages, AiProviderSummary existing,
                                OptionalInt outputLimit, Runnable saved) {
        super(owner, messages.text(existing == null ? "ai.models.add" : "ai.models.edit"), ModalityType.APPLICATION_MODAL);
        gold.debug.windowstolinux.app.ui.language.LocaleBinding.title(this,
            gold.debug.windowstolinux.app.ui.language.LocalizedMessage.of(existing == null ? "ai.models.add" : "ai.models.edit"),
            messages.catalog());
        this.service = service;
        this.messages = messages;
        this.saved = saved;
        id = existing == null ? "model-" + UUID.randomUUID().toString().substring(0, 12) : existing.profile().id();
        credential = existing == null ? "ai/provider/" + id + "/api-key" : existing.profile().credentialKey();
        form = components.card(new GridBagLayout());
        fields.install(form, components, messages);
        initialize(existing, outputLimit);
        capability.setName("model.capability");
        messages.localize(capability, "ai.capability.");
        components.addField(form, 9, 0, messages, "ai.capability.label", capability);
        save = messages.bind(components.primaryButton(""), "ai.models.saveConfiguration");
        save.setName("model.save");
        save.addActionListener(event -> saveConfiguration());
        verify = messages.bind(components.secondaryButton(""), "ai.models.verify");
        verify.setName("model.verify");
        verify.addActionListener(event -> verifyCapability());
        capability.addActionListener(event -> updateVerification());
        var cancel = messages.bind(components.secondaryButton(""), "button.cancel");
        cancel.setName("model.cancel");
        cancel.addActionListener(event -> closeEditor());
        var actions = components.transparent(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(verify);
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
        setName("platform.model.editor");
        setSize(760, 730);
        setMinimumSize(new Dimension(650, 650));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /** Closes through the original cancellation path. / 通过原取消路径关闭。
             * @param event close event / 关闭事件 */
            @Override
            public void windowClosing(WindowEvent event) {
                closeEditor();
            }
        });
        timer = new javax.swing.Timer(250, event -> observe());
        timer.start();
        fields.observe(this::updateVerification);
        updateVerification();
    }

    /** Loads saved values without assuming a chat protocol exists. / 加载保存值，不假设聊天协议存在。
     * @param existing saved model or absent / 保存模型或空
     * @param outputLimit explicit budget / 明确预算 */
    private void initialize(AiProviderSummary existing, OptionalInt outputLimit) {
        if (existing != null) {
            fields.name.setText(existing.name());
            fields.endpoint.setText(address(existing.profile().chatCompletionsEndpoint()));
            fields.fileEndpoint.setText(address(existing.profile().fileTranscriptionEndpoint()));
            fields.realtimeEndpoint.setText(address(existing.profile().realtimeTranscriptionEndpoint()));
            fields.model.setText(existing.profile().model());
            fields.key.putClientProperty("JTextField.placeholderText", messages.text("ai.models.keepKey"));
            if (outputLimit.isPresent())
                stored = new SavedDraft(existing.profile(), existing.name(), outputLimit.getAsInt());
        }
        if (outputLimit.isPresent()) fields.limit.setText(Integer.toString(outputLimit.getAsInt()));
    }

    /** Creates fixed fields without a real provider, task or credential. / 不持有真实提供方、任务或凭据地创建固定字段。
     * @param owner gallery owner / 目录所有者
     * @param components original controls / 原控件
     * @param messages localized display / 本地化显示
     * @return editor with real effects unavailable / 真实副作用不可用的编辑器 */
    static JDialog preview(Window owner, DesktopComponentFactory components, PageMessagePresenter messages) {
        var dialog = new PlatformModelDialog(owner, Optional.empty(), components, messages, null, OptionalInt.of(4096), () -> {
        });
        dialog.fields.name.setText(messages.text("debug.sample.model"));
        dialog.fields.endpoint.setText(messages.text("debug.sample.endpoint"));
        dialog.fields.model.setText(messages.text("debug.sample.modelId"));
        dialog.status.setText(messages.text("debug.blocked"));
        dialog.save.setEnabled(false);
        dialog.verify.setEnabled(false);
        return dialog;
    }

    /** Stores the configuration without issuing any network probe. / 保存配置，不发送网络探测。 */
    private void saveConfiguration() {
        if (busy || service.isEmpty()) return;
        char[] supplied = null;
        try {
            var draft = draft();
            supplied = fields.key.getPassword();
            fields.key.setText("");
            char[] input = supplied;
            setBusy(true);
            DesktopTaskExecutor.run(() -> {
                try {
                    var modelService = service.orElseThrow();
                    modelService.save(draft.profile(), draft.name(), input, draft.limit());
                    return modelService.listAiConfigurations().stream().filter(row -> row.profile().id().equals(id))
                        .findFirst().orElseThrow();
                } finally {
                    Arrays.fill(input, '\0');
                }
            }, row -> {
                credential = row.profile().credentialKey();
                stored = new SavedDraft(row.profile(), row.name(), draft.limit());
                setBusy(false);
                saved.run();
                status.setText(messages.text("ai.models.savedConfiguration") + "\n\n" + messages.text("platform.models.probeHint"));
                if (closing) dispose();
            }, this::failed);
        } catch (Exception failure) {
            if (supplied != null) Arrays.fill(supplied, '\0');
            status.setText(messages.text("ai.models.invalidForm"));
        }
    }

    /** Starts one explicit saved capability check with caller-supplied audio when required. / 必要时以调用者提供的音频启动一次明确保存能力检查。 */
    private void verifyCapability() {
        if (busy || service.isEmpty() || !verify.isEnabled()) return;
        var selected = (AiCapabilityType) capability.getSelectedItem();
        String path = fields.audio.getText().strip(), expected = fields.expected.getText().strip();
        setBusy(true);
        unknown = false;
        status.setText(messages.text("ai.models.testing"));
        DesktopTaskExecutor.run(() -> {
            VoiceRequest audio = null;
            if (selected == AiCapabilityType.VOICE_FILE || selected == AiCapabilityType.VOICE_REALTIME) {
                var file = Path.of(path);
                if (!Files.isRegularFile(file) || Files.size(file) > VoiceRequest.MAX_BYTES)
                    throw new IllegalArgumentException("Invalid voice sample");
                byte[] bytes;
                try (var input = Files.newInputStream(file)) {
                    bytes = input.readNBytes(VoiceRequest.MAX_BYTES + 1);
                }
                try {
                    audio = new VoiceRequest(bytes, "verification.wav", Duration.ofSeconds(30));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            return service.orElseThrow().verify(id, selected, audio, expected);
        }, handle -> {
            task = handle;
            if (closing) cancelTask();
            handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                task = null;
                if (!isDisplayable()) return;
                setBusy(false);
                saved.run();
                if (failure != null) status.setText(messages.text("platform.models.failed"));
                else if (result.outcome() == Outcome.SUCCEEDED) dispose();
                else status.setText(result.problem().map(messages::safe).orElse(messages.text("button.cancel")));
                if (closing) dispose();
            }));
        }, this::failed);
    }

    /** Restores editor controls after a safe admission or storage failure. / 安全准入或存储失败后恢复编辑器控件。
     * @param failure actual failure / 实际失败 */
    private void failed(Exception failure) {
        setBusy(false);
        status.setText(messages.safe(failure));
        if (closing) dispose();
    }

    /** Captures one valid current configuration. / 捕获一个有效当前配置。
     * @return exact visible draft / 精确可见草稿 */
    private SavedDraft draft() {
        var profile = new AiProviderProfile(id, uri(fields.endpoint.getText()), fields.model.getText().strip(), credential,
            uri(fields.fileEndpoint.getText()), uri(fields.realtimeEndpoint.getText()));
        String configuredLimit = fields.limit.getText().strip();
        int limit = profile.chatCompletionsEndpoint() == null && configuredLimit.isEmpty()
            ? (stored == null ? 128 : stored.limit()) : Integer.parseInt(configuredLimit);
        if (limit < 1 || fields.name.getText().isBlank()) throw new IllegalArgumentException("Invalid model form");
        return new SavedDraft(profile, fields.name.getText().strip(), limit);
    }

    /** Permits checks only against a saved configured protocol. / 仅允许检查已保存且配置地址的协议。 */
    private void updateVerification() {
        boolean clean = false;
        try {
            clean = stored != null && stored.equals(draft()) && fields.key.getDocument().getLength() == 0
                && switch ((AiCapabilityType) capability.getSelectedItem()) {
                case TEXT, VISION -> stored.profile().chatCompletionsEndpoint() != null;
                case VOICE_FILE -> stored.profile().fileTranscriptionEndpoint() != null;
                case VOICE_REALTIME -> stored.profile().realtimeTranscriptionEndpoint() != null;
            };
        } catch (IllegalArgumentException invalid) {
            clean = false;
        }
        verify.setEnabled(!busy && service.isPresent() && clean);
    }

    /** Observes the retained original task without replaying it. / 观察保留原任务，不重放。 */
    private void observe() {
        if (task != null && task.snapshot().state() == TaskState.UNKNOWN) {
            unknown = true;
            status.setText(messages.text("platform.models.unknown"));
            if (closing) dispose();
        }
    }

    /** Cancels the real task without interrupting the UI worker. / 取消真实任务，不中断界面工作线程。 */
    private void cancelTask() {
        var current = task;
        if (current != null) DesktopTaskExecutor.run(() -> {
                current.requestCancel();
                return true;
            }, ignored -> {
            },
            failure -> status.setText(messages.safe(failure)));
    }

    /** Lets unknown work remain in the permanent recovery list. / 允许未知工作保留在常驻恢复列表。 */
    private void closeEditor() {
        if (!busy || unknown) {
            dispose();
            return;
        }
        closing = true;
        cancelTask();
        status.setText(messages.text("ai.models.cancelling"));
    }

    /** Freezes inputs while the exact original operation runs. / 精确原操作执行期间冻结输入。
     * @param value whether operation is pending / 操作是否待完成 */
    private void setBusy(boolean value) {
        busy = value;
        save.setEnabled(!value && service.isPresent());
        for (var child : form.getComponents()) child.setEnabled(!value);
        updateVerification();
    }

    /** Returns an empty display for an unconfigured protocol. / 未配置协议返回空显示。
     * @param value optional address / 可选地址
     * @return editable address / 可编辑地址 */
    private static String address(URI value) {
        return value == null ? "" : value.toString();
    }

    /** Parses only explicitly supplied addresses. / 仅解析明确提供的地址。
     * @param value visible address / 可见地址
     * @return address or absent / 地址或空 */
    private static URI uri(String value) {
        return value.isBlank() ? null : URI.create(value.strip());
    }

    /** Stops observation and clears the credential control. / 停止观察并清空凭据控件。 */
    @Override
    public void dispose() {
        if (timer != null) timer.stop();
        fields.key.setText("");
        super.dispose();
    }

    /** Exact stored form values. / 精确保存表单值。
     * @param profile protocol configuration / 协议配置
     * @param name display name / 显示名
     * @param limit text output ceiling / 文字输出上限 */
    private record SavedDraft(AiProviderProfile profile, String name, int limit) {
    }

    /** Owns the editable configuration and caller-provided verification sample controls. / 持有可编辑配置及调用者提供的验证样本控件。 */
    private static final class ModelFields {
        /** Configuration fields and local sample inputs. / 配置字段及本地样本输入。 */
        private final JTextField name = new JTextField(28), endpoint = new JTextField(28), fileEndpoint = new JTextField(28),
            realtimeEndpoint = new JTextField(28), model = new JTextField(28), limit = new JTextField(12),
            audio = new JTextField(28), expected = new JTextField(28);

        /** Temporary credential input. / 临时凭据输入。 */
        private final JPasswordField key = new JPasswordField(28);

        /** Installs independently labelled protocol addresses and sample inputs. / 安装独立标注的协议地址及样本输入。
         * @param form actual layout / 实际布局
         * @param components themed controls / 主题控件
         * @param messages localized labels / 本地化标签 */
        private void install(JPanel form, DesktopComponentFactory components, PageMessagePresenter messages) {
            var fields = new JTextField[]{name, endpoint, fileEndpoint, realtimeEndpoint, model, key, limit, audio, expected};
            var labels = new String[]{"ai.models.name", "ai.models.chatEndpoint", "ai.models.fileEndpoint", "ai.models.realtimeEndpoint",
                "field.model", "field.apiKey", "platform.models.limit", "ai.models.audio", "ai.models.expected"};
            var names = new String[]{"model.name", "model.endpoint", "model.fileEndpoint", "model.realtimeEndpoint", "model.model",
                "model.key", "model.limit", "model.audio", "model.expected"};
            for (int i = 0; i < fields.length; i++) {
                fields[i].setName(names[i]);
                components.addField(form, i, 0, messages, labels[i], fields[i]);
            }
        }

        /** Observes all saved configuration controls without persisting the sample path. / 观察全部保存配置控件，不持久保存样本路径。
         * @param changed verification eligibility refresh / 验证资格刷新 */
        private void observe(Runnable changed) {
            var listener = new DocumentListener() {
                /** Refreshes after insertion. / 插入后刷新。
                 * @param event edit event / 编辑事件 */
                @Override
                public void insertUpdate(DocumentEvent event) {
                    changed.run();
                }

                /** Refreshes after removal. / 删除后刷新。
                 * @param event edit event / 编辑事件 */
                @Override
                public void removeUpdate(DocumentEvent event) {
                    changed.run();
                }

                /** Refreshes after style changes. / 样式变化后刷新。
                 * @param event edit event / 编辑事件 */
                @Override
                public void changedUpdate(DocumentEvent event) {
                    changed.run();
                }
            };
            for (var field : new JTextField[]{name, endpoint, fileEndpoint, realtimeEndpoint, model, limit, key})
                field.getDocument().addDocumentListener(listener);
        }
    }
}
