package gold.debug.windowstolinux.app.ui.server;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.service.contract.ServerApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Owns the server form, credentials-in-memory state, and server workflows. / 持有服务器表单、内存凭据状态与服务器流程。
 */
public final class ServerPage implements ServerContext {
    /**
     * Component or resource identity owning the operation.
     * <p>持有操作的组件或资源身份。
     */
    private final JFrame owner;

    /**
     * Bound server application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的服务器应用门面协作对象。
     */
    private final ServerApplicationFacade service;

    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;

    /**
     * Swing control for id.
     * <p>标识对应的 Swing 控件。
     */
    private final JTextField id = new JTextField("server-one", 20);

    /**
     * Swing control for host.
     * <p>主机对应的 Swing 控件。
     */
    private final JTextField host = new JTextField(20);

    /**
     * Swing control for port.
     * <p>端口对应的 Swing 控件。
     */
    private final JTextField port = new JTextField("22", 6);

    /**
     * Swing control for username.
     * <p>用户名对应的 Swing 控件。
     */
    private final JTextField username = new JTextField(20);

    /**
     * Swing control for password.
     * <p>密码对应的 Swing 控件。
     */
    private final JPasswordField password = new JPasswordField(20);

    /**
     * Selected platform credential-storage mode.
     * <p>所选平台凭据存储模式。
     */
    private final JComboBox<CredentialStorageMode> credentialMode = new JComboBox<>(CredentialStorageMode.values());

    /**
     * Swing control for master password.
     * <p>主密码对应的 Swing 控件。
     */
    private final JPasswordField masterPassword = new JPasswordField(20);

    /**
     * Swing control for output.
     * <p>输出对应的 Swing 控件。
     */
    private final JTextArea output = DesktopComponentFactory.outputArea();

    /**
     * Swing control for panel.
     * <p>面板对应的 Swing 控件。
     */
    private final JPanel panel;

    /**
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     */
    private boolean busy;

    /**
     * Inventory.
     * <p>清单。
     */
    private ServerInventoryPane inventory;

    /**
     * Display name.
     * <p>显示名称。
     */
    private String displayName = "server-one";

    /**
     * Opaque lookup key in the platform secret store.
     * <p>平台秘密存储中的不透明查找键。
     */
    private String credentialKey = "ssh/server-one/password";

    /**
     * Creates the stateful page controller. / 创建有状态页面控制器。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param messages localized message resolver / 本地化消息解析器
     */
    public ServerPage(JFrame owner, ServerApplicationFacade service, DesktopComponentFactory components,
            PageMessagePresenter messages) {
        this.owner = owner;
        this.service = service;
        this.messages = messages;
        credentialMode.setSelectedItem(CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);
        messages.localize(credentialMode, "credential.mode.");
        credentialMode.addActionListener(event -> masterPassword
                .setEnabled(credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        masterPassword.setEnabled(false);
        panel = createPanel(components);
    }

    /**
     * Returns the page panel. / 返回页面面板。
     *
     * @return the page panel / 页面面板
     */
    public JPanel panel() {
        return panel;
    }

    /**
     * Captures unsaved state including temporary password copies. / 捕获包含临时密码副本的未保存状态。
     *
     * @return constructed or resolved server page state / 构造或解析得到的服务器页面状态
     */
    public ServerPageState captureState() {
        return new ServerPageState(id.getText(), host.getText(), port.getText(), username.getText(),
                password.getPassword(), credentialMode(), masterPassword(), output.getText(), displayName,
                inventory.search(), credentialKey);
    }

    /**
     * Restores unsaved state. / 恢复未保存状态。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public void restoreState(ServerPageState state) {
        id.setText(state.id());
        host.setText(state.host());
        port.setText(state.port());
        username.setText(state.username());
        password.setText(new String(state.password()));
        credentialMode.setSelectedItem(state.credentialMode());
        masterPassword.setText(new String(state.masterPassword()));
        output.setText(state.output());
        displayName = state.displayName();
        credentialKey = state.credentialKey();
        inventory.search(state.search());
    }

    /**
     * Builds server profile from the supplied profile inputs.
     * <p>根据所提供配置资料输入构建服务器配置资料。
     *
     * @return server profile from the supplied profile inputs / 根据所提供配置资料输入构建服务器配置资料
     */
    @Override
    public ServerProfile profile() {
        String serverId = id.getText().trim();
        return new ServerProfile(serverId, host.getText().trim(), Integer.parseInt(port.getText().trim()),
                username.getText().trim(), credentialKey, credentialMode(), displayName);
    }

    /**
     * Synchronizes saved selection with manual server and component operations. / 同步已保存选择与手动服务器及组件操作。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     */
    @Override
    public void selectProfile(ServerProfile profile) {
        credentialKey = profile.credentialKey();
        displayName = profile.displayName();
        id.setText(profile.id());
        host.setText(profile.host());
        port.setText(Integer.toString(profile.sshPort()));
        username.setText(profile.username());
        credentialMode.setSelectedItem(profile.credentialMode());
        password.setText("");
    }

    /**
     * Returns selected platform credential-storage mode.
     * <p>返回所选平台凭据存储模式。
     *
     * @return selected platform credential-storage mode / 所选平台凭据存储模式
     */
    @Override
    public CredentialStorageMode credentialMode() {
        return (CredentialStorageMode) credentialMode.getSelectedItem();
    }

    /**
     * Returns master-password buffer used to unlock protected credentials.
     * <p>返回用于解锁受保护凭据的主密码缓冲区。
     *
     * @return master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     */
    @Override
    public char[] masterPassword() {
        return masterPassword.getPassword();
    }

    /**
     * Confirms pinned or freshly observed host-key fingerprint.
     * <p>确认固定或新近观测的主机密钥指纹。
     *
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return true when confirms pinned or freshly observed host-key fingerprint, false otherwise / 确认固定或新近观测的主机密钥指纹时为 true，否则为 false
     */
    @Override
    public boolean confirmFingerprint(String fingerprint) {
        return ServerTrustPrompt.confirm(owner, messages, fingerprint);
    }

    /**
     * Builds saved-server selection and environment preparation controls with progress output.
     * <p>构建已保存服务器选择及环境准备控件，并配备进度输出。
     *
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @return saved-server selection and environment preparation controls with progress output / 已保存服务器选择及环境准备控件，并配备进度输出
     */
    private JPanel createPanel(DesktopComponentFactory c) {
        JPanel page = c.pagePanel();
        var advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, c, messages);
        inventory = new ServerInventoryPane(service, c, messages, false, this::selectProfile);
        page.add(inventory, BorderLayout.CENTER);
        advanced.field("field.masterPassword", masterPassword);
        JButton prepare = c.secondaryButton(messages.text("button.prepareEnvironment"));
        prepare.addActionListener(event -> prepare(prepare, c));
        advanced.addOption(prepare);
        if (service instanceof gold.debug.windowstolinux.app.service.contract.SshRecoveryApplicationFacade rescue
                && service instanceof gold.debug.windowstolinux.app.service.contract.AiApplicationFacade ai) {
            JButton recover = c.secondaryButton(messages.text("recovery.title"));
            recover.addActionListener(event -> {
                try {
                    ServerProfile selected = service.findServerProfile(profile().id()).orElseThrow();
                    var session = rescue.startSshRecovery(selected, masterPassword(), this::confirmFingerprint);
                    new gold.debug.windowstolinux.app.ui.recovery.SshRecoveryDialog(owner, selected, session, ai, c,
                            messages);
                } catch (Exception failure) {
                    output.setText(messages.safe(failure));
                }
            });
            advanced.addOption(recover);
        }
        advanced.addOption(new javax.swing.JScrollPane(output));
        return advanced;
    }

    /**
     * Updates whether a page action is in progress and conflicting controls must remain disabled.
     * <p>更新页面动作是否正在进行且冲突控件须保持禁用。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private void setBusy(boolean value) {
        busy = value;
        ((gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane) panel).setBusy(value);
    }

    /**
     * Runs the selected server's confirmed environment preparation and displays the resulting capabilities.
     * <p>执行所选服务器已确认环境准备，并展示所得能力。
     *
     * @param trigger trigger / 触发条件
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void prepare(JButton trigger, DesktopComponentFactory components) {
        if (busy)
            return;
        try {
            ServerProfile entered = profile();
            ServerProfile saved = service.findServerProfile(entered.id())
                    .orElseThrow(() -> new IllegalStateException(messages.text("environment.serverSaveFirst")));
            if (!saved.equals(entered)) {
                throw new IllegalStateException(messages.text("environment.serverChanged"));
            }
            if (!gold.debug.windowstolinux.app.ui.component.SystemPreparationDialog.confirmEnvironment(owner, messages,
                    Map.of("serverId", saved.id(), "host", saved.host(), "port", saved.sshPort(), "username",
                            saved.username()))) {
                return;
            }
            char[] master = masterPassword();
            setBusy(true);
            output.setText(messages.text("environment.preparing"));
            DesktopTaskExecutor.run(() -> service.prepareEnvironmentRecovering(saved, saved.credentialMode(), master,
                    ServerPage.this::confirmFingerprint, true,
                    plan -> gold.debug.windowstolinux.app.ui.component.SystemPreparationDialog.confirm(owner, messages,
                            Map.of("serverId", saved.id(), "host", saved.host(), "security",
                                    plan.securityState().name(), "reboot",
                                    plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.UNPREPARED
                                            || plan.state() == gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState.REBOOT_PENDING)),
                    new gold.debug.windowstolinux.app.ui.recovery.RecoveryInteractionPresenter(owner,
                            service instanceof gold.debug.windowstolinux.app.service.contract.AiApplicationFacade ai
                                    ? ai
                                    : null,
                            components, messages)),
                    result -> {
                        setBusy(false);
                        output.setText(environmentSummary(result));
                    }, exception -> {
                        setBusy(false);
                        output.setText(
                                messages.text("environment.incomplete", Map.of("detail", messages.safe(exception))));
                    });
        } catch (Exception exception) {
            output.setText(messages.text("environment.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    /**
     * Localizes the observed operating system, architecture and environment readiness evidence.
     * <p>本地化已观测操作系统、架构及环境就绪证据。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return environment summary text / 环境摘要文本
     */
    private String environmentSummary(EnvironmentSetupResult result) {
        var value = result.capabilities();
        return messages.text("environment.completed",
                Map.ofEntries(Map.entry("os", value.operatingSystem()), Map.entry("architecture", value.architecture()),
                        Map.entry("java21", availability(value.java21Available())),
                        Map.entry("maven", availability(value.mavenAvailable())),
                        Map.entry("tar", availability(value.tarAvailable())),
                        Map.entry("curl", availability(value.curlAvailable())),
                        Map.entry("systemd", availability(value.systemdAvailable())),
                        Map.entry("socket", availability(value.socketInspectionAvailable())),
                        Map.entry("limits", availability(value.buildLimitToolsAvailable())),
                        Map.entry("sudo", availability(value.nonInteractiveSudoAvailable())),
                        Map.entry("space", value.availableBytes())));
    }

    /**
     * Localizes the ready or not-ready status.
     * <p>本地化就绪或未就绪状态。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return availability text / 可用性文本
     */
    private String availability(boolean value) {
        return messages.text(value ? "availability.ready" : "availability.notReady");
    }
}
