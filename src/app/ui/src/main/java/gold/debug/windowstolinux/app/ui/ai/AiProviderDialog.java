package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

/**
 * Independent model form; only a successful fixed probe can save it. / 独立模型表单，仅固定探测成功后才能保存。
 */
public final class AiProviderDialog extends JDialog {
    /**
     * Swing control for name.
     * <p>名称对应的 Swing 控件。
     * <p>endpoint:
     * Swing control for endpoint.
     * <p>端点对应的 Swing 控件。
     * <p>model:
     * Swing control for model.
     * <p>模型对应的 Swing 控件。
     */
    private final JTextField name = new JTextField(28), endpoint = new JTextField(28), model = new JTextField(28);
    /**
     * Swing control for key.
     * <p>键对应的 Swing 控件。
     * <p>master:
     * Swing control for master.
     * <p>主对应的 Swing 控件。
     */
    private final JPasswordField key = new JPasswordField(28), master = new JPasswordField(28);
    /**
     * Selected operating or storage mode.
     * <p>所选运行或存储模式。
     */
    private final JComboBox<CredentialStorageMode> mode = new JComboBox<>(CredentialStorageMode.values());
    /**
     * Swing control for status.
     * <p>状态对应的 Swing 控件。
     */
    private final JTextArea status = DesktopComponentFactory.outputArea();
    /**
     * Validates provider form data and saves the selected model group's configuration asynchronously, clearing entered credentials after use.
     * <p>校验提供者表单数据并异步保存所选模型组配置，使用后清空输入凭据。
     */
    private final JButton save;
    /**
     * Swing control for form.
     * <p>表单对应的 Swing 控件。
     */
    private final JPanel form;
    /**
     * Bound ai application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的AI应用门面协作对象。
     */
    private final AiApplicationFacade service;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Stable identifier within the owning registry.
     * <p>所属登记表内的稳定标识。
     * <p>credentialKey:
     * Opaque lookup key in the platform secret store.
     * <p>平台秘密存储中的不透明查找键。
     */
    private final String id, credentialKey;
    /**
     * Saved.
     * <p>已保存。
     */
    private final Runnable saved;
    /**
     * Task.
     * <p>任务。
     */
    private DesktopTaskHandle task;
    /**
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     * <p>closing:
     * Closing.
     * <p>关闭中。
     */
    private boolean busy, closing;
    /**
     * Independent model group whose order and enablement apply.
     * <p>独立模型分组，其顺序及启用设置分别生效。
     */
    private final JComboBox<gold.debug.windowstolinux.shared.model.ai.AiCapabilityType> capability = new JComboBox<>(gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.values());

    /**
     * Creates either a new model or an editor preserving the exact credential reference. / 创建新模型或保留精确凭据引用的编辑表单。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param existing existing / 既有
     * @param saved saved / 已保存
     */
    public AiProviderDialog(Window owner, AiApplicationFacade service, DesktopComponentFactory c,
                            PageMessagePresenter messages, AiProviderSummary existing, Runnable saved) {
        super(owner, messages.text(existing == null ? "ai.models.add" : "ai.models.edit"), ModalityType.APPLICATION_MODAL);
        this.service = service; this.messages = messages; this.saved = saved;
        id = existing == null ? "model-" + UUID.randomUUID().toString().substring(0, 12) : existing.profile().id();
        credentialKey = existing == null ? "ai/provider/" + id + "/api-key" : existing.profile().credentialKey();
        mode.setSelectedItem(existing == null ? CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER : existing.profile().credentialMode()); messages.localize(mode, "credential.mode.");
        if (existing != null) { name.setText(existing.name()); endpoint.setText(existing.profile().chatCompletionsEndpoint().toString()); model.setText(existing.profile().model()); }
        key.putClientProperty("JTextField.placeholderText", messages.text(existing == null ? "field.apiKey" : "ai.models.keepKey"));
        mode.addActionListener(event -> master.setEnabled(mode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        master.setEnabled(mode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
        form = c.card(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("ai.models.name"), name);
        c.addField(form, 1, 0, messages.text("field.aiEndpoint"), endpoint);
        c.addField(form, 2, 0, messages.text("field.model"), model);
        c.addField(form, 3, 0, messages.text("field.apiKey"), key);
        c.addField(form, 4, 0, messages.text("field.credentialStorage"), mode);
        c.addField(form, 5, 0, messages.text("field.masterPassword"), master);
        messages.localize(capability,"ai.capability.");
        if (existing != null && existing.textVerifiedAt().isEmpty() && existing.visionVerifiedAt().isPresent()) capability.setSelectedItem(gold.debug.windowstolinux.shared.model.ai.AiCapabilityType.VISION);
        c.addField(form, 6, 0, messages.text("ai.capability.label"), capability);
        save = c.primaryButton(messages.text("ai.models.testSave")); save.addActionListener(event -> save());
        JButton cancel = c.secondaryButton(messages.text("button.cancel")); cancel.addActionListener(event -> close());
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(save);
        status.setRows(4); status.setLineWrap(true); status.setWrapStyleWord(true); status.setText(messages.text("ai.models.probeHint"));
        JPanel body = c.pagePanel(); body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20)); body.add(form, BorderLayout.NORTH);
        body.add(new JScrollPane(status)); body.add(actions, BorderLayout.SOUTH); setContentPane(body);
        setSize(620, 500); setMinimumSize(new Dimension(560, 450)); setLocationRelativeTo(owner); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /**
             * Handles the user's close request through the owning window's cleanup path.
             * <p>通过所属窗口的清理路径处理用户关闭请求。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void windowClosing(WindowEvent event) { close(); }
            /**
             * Completes resource cleanup after the Swing window has closed.
             * <p>在 Swing 窗口关闭后完成资源清理。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void windowClosed(WindowEvent event) { key.setText(""); master.setText(""); }
        });
    }
    /**
     * Validates provider form data and saves the selected model group's configuration asynchronously, clearing entered credentials after use.
     * <p>校验提供者表单数据并异步保存所选模型组配置，使用后清空输入凭据。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void save() {
        if (busy) return;
        try {
            if (name.getText().isBlank() || name.getText().length() > 120 || name.getText().chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid model display name");
            var profile = new AiProviderProfile(id, URI.create(endpoint.getText().trim()), model.getText().trim(), credentialKey, (CredentialStorageMode) mode.getSelectedItem());
            var selectedCapability = (gold.debug.windowstolinux.shared.model.ai.AiCapabilityType)capability.getSelectedItem();
            String label = name.getText(); char[] apiKey = key.getPassword(), unlock = master.getPassword();
            setBusy(true); status.setText(messages.text("ai.models.testing"));
            task = DesktopTaskExecutor.submit(() -> {
                try { service.saveAiConfiguration(profile, label, unlock, apiKey, selectedCapability); return true; }
                finally { Arrays.fill(apiKey, '\0'); Arrays.fill(unlock, '\0'); }
            }, value -> { setBusy(false); saved.run(); dispose(); }, failure -> {
                setBusy(false); if (closing) dispose(); else status.setText(messages.safe(failure));
            });
        } catch (IllegalArgumentException failure) { status.setText(messages.text("ai.models.invalidForm")); }
        catch (Exception failure) { status.setText(messages.safe(failure)); }
    }
    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    private void close() { if (busy && task != null) { closing = true; task.cancel(); status.setText(messages.text("ai.models.cancelling")); } else dispose(); }
    /**
     * Updates whether a page action is in progress and conflicting controls must remain disabled.
     * <p>更新页面动作是否正在进行且冲突控件须保持禁用。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private void setBusy(boolean value) {
        busy = value; save.setEnabled(!value);
        for (Component child : form.getComponents()) child.setEnabled(!value);
        master.setEnabled(!value && mode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD);
    }
}
