package gold.debug.windowstolinux.app.ui.deployment.single;


import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.server.ServerSelectionPane;
import javax.swing.*;
import java.awt.Dimension;
import java.awt.Desktop;
import java.util.LinkedHashMap;
import java.util.Arrays;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import javax.swing.JButton;
import gold.debug.windowstolinux.app.ui.component.ToggleSwitch;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Owns source selection, review state, deployment forms, and the complete reviewed deployment workflow. / 持有源码选择、审阅状态、部署表单与完整经审阅部署流程。
 */
public final class DeploymentPage implements ReviewContext {
    /** Session-only automation selection. / 仅会话内的自动化选择。 */
    private final DeploymentModeSelector modeSelector;

    /**
     * Component or resource identity owning the operation.
     * <p>持有操作的组件或资源身份。
     */
    private final JFrame owner;
    /**
     * Bound automatic deployment application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的自动部署应用门面协作对象。
     */
    private final AutomaticDeploymentApplicationFacade service;
    /**
     * Server context.
     * <p>服务器上下文。
     */
    private final ServerContext serverContext;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Application selection.
     * <p>应用选择。
     */
    private final Consumer<String> applicationSelection;
    /**
     * Form.
     * <p>表单。
     */
    private final DeploymentForm form;
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
     * Reviewed preparation.
     * <p>已审阅准备。
     */
    private ReviewedSourcePreparation reviewedPreparation;
    /**
     * Swing control for source path.
     * <p>源码路径对应的 Swing 控件。
     */
    private final JTextField sourcePath = new JTextField();
    /**
     * Swing control for git address.
     * <p>Git地址对应的 Swing 控件。
     */
    private final JTextField gitAddress = new JTextField();
    /**
     * Swing control for git reference.
     * <p>Git引用对应的 Swing 控件。
     */
    private final JTextField gitReference = new JTextField();
    /**
     * Source mode.
     * <p>源码模式。
     */
    private final JComboBox<String> sourceMode = new JComboBox<>();
    /**
     * Git kind.
     * <p>Git种类。
     */
    private final JComboBox<String> gitKind = new JComboBox<>();
    /**
     * Detect type.
     * <p>识别类型。
     */
    private final ToggleSwitch detectType = new ToggleSwitch();
    /**
     * Swing control for handoffs.
     * <p>交接集合对应的 Swing 控件。
     */
    private final JPanel handoffs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    /**
     * Swing control for handoff scroll.
     * <p>交接Scroll对应的 Swing 控件。
     */
    private final JScrollPane handoffScroll = new JScrollPane(handoffs,ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    /**
     * Server selection.
     * <p>服务器选择。
     */
    private ServerSelectionPane serverSelection;
    /**
     * Source card.
     * <p>源码卡片。
     */
    private DeploymentSourceCard sourceCard;
    /**
     * Swing control for start.
     * <p>启动对应的 Swing 控件。
     */
    private JButton start;
    /**
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     */
    private boolean busy;
    /** Process-local Agent controls and audit history. / 进程内 Agent 控制及审计历史。 */
    private DeploymentTaskControls taskControls;
    /**
     * Completed handoffs.
     * <p>已完成交接集合。
     */
    private final Map<String, DeploymentHandoff> completedHandoffs = new LinkedHashMap<>();
    /**
     * Reviewed components in the application graph.
     * <p>应用图中的已审阅组件。
     */
    private DesktopComponentFactory components;

    /**
     * Creates the stateful deployment controller. / 创建有状态部署控制器。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param serverContext server context / 服务器上下文
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param messages localized message resolver / 本地化消息解析器
     * @param openServers open servers / 打开服务器集合
     * @param applicationSelection application selection / 应用选择
     * @param openAi model configuration navigation / 模型配置导航
     */
    public DeploymentPage(JFrame owner, AutomaticDeploymentApplicationFacade service, ServerContext serverContext,
                          DesktopComponentFactory components, PageMessagePresenter messages, Runnable openServers,
                          Consumer<String> applicationSelection, Runnable openAi) {
        this.owner = owner;
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        this.applicationSelection = applicationSelection;
        modeSelector = new DeploymentModeSelector(messages);
        form = new DeploymentForm(messages, () -> reviewedPreparation = null);
        panel = createPanel(components,openAi,openServers);
    }

    /**
     * Returns the page panel. / 返回页面面板。
     *
     * @return the page panel / 页面面板
     */
    public JPanel panel() { return panel; }
    /**
     * Returns reviewed preparation.
     * <p>返回已审阅准备。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    @Override public Optional<ReviewedSourcePreparation> reviewedPreparation() { return Optional.ofNullable(reviewedPreparation); }

    /**
     * Captures all unsaved deployment and review state. / 捕获全部未保存部署与审阅状态。
     *
     * @return constructed or resolved deployment page state / 构造或解析得到的部署页面状态
     */
    public DeploymentPageState captureState() {
        return form.capture(output.getText(), reviewedPreparation);
    }

    /**
     * Restores all unsaved deployment and review state. / 恢复全部未保存部署与审阅状态。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public void restoreState(DeploymentPageState state) {
        form.restore(state);
        output.setText(state.output()); reviewedPreparation = state.preparation();
    }

    /**
     * Builds the source-to-server deployment page and binds its automatic and advanced configuration actions.
     * <p>构建从源码到服务器的部署页面，并绑定自动及高级配置动作。
     *
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @return the source-to-server deployment page and binds its automatic and advanced configuration actions / 从源码到服务器的部署页面，并绑定自动及高级配置动作
     * @param openAi model configuration navigation / 模型配置导航
     * @param openServers server configuration navigation / 服务器配置导航
     */
    private JPanel createPanel(DesktopComponentFactory c,Runnable openAi,Runnable openServers) {
        components = c;
        JPanel page = c.pagePanel();
        ((BorderLayout) page.getLayout()).setVgap(12);
        AdvancedOptionsPane advanced = new AdvancedOptionsPane(page, c, messages);
        JPanel selection = c.transparent(new BorderLayout(12, 0));
        JPanel source = c.card(new BorderLayout(0, 12));
        source.add(c.sectionHeading(messages.text("auto.source"), messages.text("auto.source.hint")), BorderLayout.NORTH);
        sourceMode.addItem(messages.text("auto.local")); sourceMode.addItem(messages.text("auto.git"));
        sourceCard = new DeploymentSourceCard(service, c, messages, value -> {
            sourceMode.setSelectedIndex(value.directory().isPresent() ? 0 : 1);
            sourcePath.setText(value.directory().map(Path::toString).orElse("")); gitAddress.setText(value.gitAddress());
        });
        source.add(sourceCard, BorderLayout.CENTER);
        JPanel target = c.card(new BorderLayout(0, 12));
        target.add(c.sectionHeading(messages.text("auto.server"), messages.text("auto.server.hint")), BorderLayout.NORTH);
        serverSelection = new ServerSelectionPane(service, c, messages, serverContext::selectProfile);
        target.add(serverSelection, BorderLayout.CENTER);
        JPanel columns = sourceAndTarget(c, source, target);
        selection.add(columns, BorderLayout.CENTER);
        JPanel top = c.transparent(new BorderLayout(0, 12)); top.add(selection, BorderLayout.CENTER);
        start = c.primaryButton(messages.text("auto.start"));
        Dimension actionSize = start.getPreferredSize(); start.setPreferredSize(new Dimension(Math.max(160, actionSize.width), Math.max(36, actionSize.height)));
        start.addActionListener(event -> startAutomatic());
        JPanel actions = c.transparent(new BorderLayout(12, 0)); actions.add(modeSelector, BorderLayout.WEST);
        JPanel launch = c.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton models = c.secondaryButton(messages.text("deployment.configureModels")); models.addActionListener(event -> openAi.run());
        launch.add(models); launch.add(start); actions.add(launch, BorderLayout.EAST);
        top.add(actions, BorderLayout.SOUTH); page.add(top, BorderLayout.NORTH);
        output.setText(messages.text("auto.idle"));
        JPanel log = c.outputCard(messages.text("auto.log"), messages.text("auto.log.hint"), output);
        handoffs.setOpaque(false); handoffScroll.setVisible(false); handoffScroll.setPreferredSize(new Dimension(0,46));
        taskControls=new DeploymentTaskControls(service,messages,this::append);
        JPanel taskFooter=c.transparent(new BorderLayout(0,8));taskFooter.add(taskControls,BorderLayout.NORTH);taskFooter.add(handoffScroll,BorderLayout.SOUTH);
        log.add(taskFooter, BorderLayout.SOUTH); page.add(log, BorderLayout.CENTER);
        configureAdvanced(advanced, c,openServers);
        return advanced;
    }

    /**
     * Places source and target cards side by side with matched preferred height.
     * <p>将源码及目标卡片并排放置，并统一首选高度。
     *
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @return constructed or resolved J panel / 构造或解析得到的J面板
     */
    private JPanel sourceAndTarget(DesktopComponentFactory c, JPanel source, JPanel target) {
        JPanel columns = c.transparent(new GridBagLayout());
        int height = Math.max(source.getPreferredSize().height, target.getPreferredSize().height);
        source.setPreferredSize(new Dimension(0, height)); target.setPreferredSize(new Dimension(0, height));
        JLabel direction = c.badge("");
        direction.setName("deployment.direction");
        direction.setIcon(gold.debug.windowstolinux.app.ui.component.DesktopIcons.icon("arrow-right", 24, direction::getForeground));
        direction.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        direction.setToolTipText(messages.text("auto.direction"));
        direction.getAccessibleContext().setAccessibleName(messages.text("auto.direction"));
        var constraints = new java.awt.GridBagConstraints();
        constraints.fill = java.awt.GridBagConstraints.BOTH; constraints.weightx = 1; constraints.weighty = 1;
        columns.add(source, constraints);
        constraints.gridx = 1; constraints.weightx = 0; constraints.fill = java.awt.GridBagConstraints.NONE;
        constraints.insets = new java.awt.Insets(0, 12, 0, 12); columns.add(direction, constraints);
        constraints.gridx = 2; constraints.weightx = 1; constraints.fill = java.awt.GridBagConstraints.BOTH;
        constraints.insets = new java.awt.Insets(0, 0, 0, 0); columns.add(target, constraints);
        return columns;
    }

    /**
     * Registers automatic detection, Git reference and runtime overrides in the advanced pane.
     * <p>在高级面板中登记自动识别、Git 引用及运行规格覆盖项。
     *
     * @param advanced advanced / 高级
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param openServers server configuration navigation / 服务器配置导航
     */
    private void configureAdvanced(AdvancedOptionsPane advanced, DesktopComponentFactory c,Runnable openServers) {
        detectType.setSelected(true);
        advanced.field("auto.detectType", detectType);
        gitKind.addItem(messages.text("git.reference.kind.branch")); gitKind.addItem(messages.text("git.reference.kind.tag"));
        gitKind.addItem(messages.text("git.reference.kind.commit"));
        advanced.field("auto.gitKind", gitKind); advanced.field("auto.gitReference", gitReference);
        advanced.field("field.projectType", form.projectType);
        advanced.field("field.applicationDeclaration", new JScrollPane(form.applicationDeclaration));
        advanced.field("field.healthMode", form.healthMode);
        advanced.field("field.healthEndpoint", form.healthEndpoint);
        advanced.field("field.expectedStatus", form.expectedStatus);
        advanced.field("field.timeout", form.timeout);
        advanced.field("field.tcpStability", form.stability);
        advanced.field("field.userAccessUrl", form.accessUrl);
        advanced.field("field.runtimePrimary", form.runtimePrimary);
        advanced.field("field.runtimeSecondary", form.runtimeSecondary);
        advanced.field("field.runtimeVersion", form.runtimeVersion);
        advanced.field("auto.field.jvmTarget", form.kotlinJvmTarget);
        advanced.field("field.jvmArguments", form.jvmArguments);
        advanced.field("field.applicationArguments", form.applicationArguments);
        advanced.field("field.containerEngine", form.containerEngine);
        advanced.field("field.containerPorts", form.containerPorts);
        advanced.field("field.containerVolumes", form.containerVolumes);
        advanced.field("field.configurationEntries", form.configurationEntries);
        advanced.field("field.databaseReviewMode", form.databaseMode);
        advanced.field("field.databaseDetails", form.databaseDetails);
        advanced.field("field.secretReferences", form.secretReferences);
        advanced.field("experimentalAdapterRisk", form.experimentalAdapterRisk);
        JButton secret = c.secondaryButton(messages.text("button.saveSecretRevision"));
        secret.addActionListener(event -> saveSecret()); advanced.field("field.secretRevision", secret);
        JButton manual = c.secondaryButton(messages.text("auto.components"));
        manual.addActionListener(event -> openServers.run()); advanced.addOption(manual);
    }

    /**
     * Captures source selection independently from typed advanced inputs. / 独立于类型化高级输入捕获源码选择。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    public Map<String, String> captureSelection() {
        return Map.of("sourceMode", Integer.toString(sourceMode.getSelectedIndex()), "source", sourcePath.getText(),
                "git", gitAddress.getText(), "reference", gitReference.getText(), "kind", Integer.toString(gitKind.getSelectedIndex()),
                "detect", Boolean.toString(detectType.isSelected()), "server", serverSelection.selectedId(), "sourceText", sourceCard.inputText(), "automation", modeSelector.mode().name(), "approval", modeSelector.approval().name());
    }

    /**
     * Restores source selection after an appearance change. / 外观变更后恢复源码选择。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public void restoreSelection(Map<String, String> state) {
        if (state.isEmpty()) return;
        sourceMode.setSelectedIndex(Integer.parseInt(state.get("sourceMode"))); sourcePath.setText(state.get("source"));
        gitAddress.setText(state.get("git")); gitReference.setText(state.get("reference"));
        gitKind.setSelectedIndex(Integer.parseInt(state.get("kind"))); detectType.setSelected(Boolean.parseBoolean(state.get("detect")));
        String acceptedSource = sourceMode.getSelectedIndex() == 0 ? sourcePath.getText() : gitAddress.getText();
        sourceCard.restore(state.getOrDefault("sourceText", acceptedSource), !acceptedSource.isBlank());
        serverSelection.select(state.get("server"));
        modeSelector.restore(DeploymentAutomationMode.valueOf(state.getOrDefault("automation", "STATIC")), AgentApprovalMode.valueOf(state.getOrDefault("approval", "AUTOMATIC")));
    }

    /**
     * Reports active work so an appearance rebuild cannot detach a running operation. / 报告正在执行的任务，防止外观重建使操作脱离页面。
     *
     * @return true when reports active work so an appearance rebuild cannot detach a running operation, false otherwise / 报告正在执行的任务，防止外观重建使操作脱离页面时为 true，否则为 false
     */
    public boolean busy() { return busy; }

    /**
     * Validates the selected source and server, then runs automatic deployment with page-owned progress and cancellation handling.
     * <p>校验所选源码及服务器，随后运行自动部署，由页面负责进度及取消处理。
     */
    private void startAutomatic() {
        if (busy) return;
        try {
            ServerProfile server = serverSelection.profile();
            if (server == null || (sourceMode.getSelectedIndex() == 0 ? sourcePath.getText().isBlank() : gitAddress.getText().isBlank())) {
                append(messages.text("auto.selectRequired")); return;
            }
            Optional<Path> directory = sourceMode.getSelectedIndex() == 0 ? Optional.of(Path.of(sourcePath.getText())) : Optional.empty();
            AutomaticDeploymentRequest request = service.createAutomaticDeploymentRequest(
                    new gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput(directory,
                            gitAddress.getText(), gitKind.getSelectedIndex(), gitReference.getText()), server,
                    form.input(detectType.isSelected())).withAutomation(modeSelector.mode(), modeSelector.approval());
            char[] entered = serverContext.masterPassword();
            if (server.credentialMode() == CredentialStorageMode.MASTER_PASSWORD && entered.length == 0)
                entered = new DeploymentInputDialog(owner, service, components, messages).requestSecret("field.masterPassword");
            final char[] master = entered;
            DeploymentInputDialog interaction = new DeploymentInputDialog(owner, service, components, messages);
            busy = true; modeSelector.setBusy(true); start.setEnabled(false); start.setText(messages.text("auto.running"));
            ((AdvancedOptionsPane) panel).setBusy(true);
            handoffs.removeAll(); handoffScroll.setVisible(false); completedHandoffs.clear(); output.setText("");
            if(request.automationMode()==DeploymentAutomationMode.AGENT)taskControls.begin(request.taskId());
            DesktopTaskExecutor.run(() -> {
                try { service.requireDeploymentModels(request.automationMode()); return service.deployAutomatically(request, master.clone(), interaction, serverContext::confirmFingerprint,
                        message -> SwingUtilities.invokeLater(() -> append(messages.catalog().text(message)))); }
                finally { Arrays.fill(master, '\0'); }
            }, result -> {
                finish(); append(messages.text("deployment.status." + result.status().name().toLowerCase(Locale.ROOT)));
                if (result.status() == DeploymentStatus.SUCCEEDED) {
                    applicationSelection.accept(result.applicationId());
                    completedHandoffs.putAll(result.handoffs());
                    result.handoffs().forEach((id, value) -> showHandoff(id, value, true));
                }
            }, failure -> {
                finish();
                Throwable reason = failure; while (reason.getCause() != null) reason = reason.getCause();
                append(reason instanceof java.util.concurrent.CancellationException ? messages.text("auto.cancelled")
                        : messages.safe(failure));
            });
        } catch (Exception failure) { append(messages.safe(failure)); }
    }

    /**
     * Appends deployment page.
     * <p>追加部署页面。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     */
    private void append(String text) {
        output.append(text + "\n");
        if (output.getDocument().getLength() > 250_000) output.setText(output.getText().substring(50_000));
        output.setCaretPosition(output.getDocument().getLength());
    }

    /**
     * Finishes deployment page.
     * <p>完成部署页面。
     */
    private void finish() {
        busy = false; taskControls.finish(); ((AdvancedOptionsPane) panel).setBusy(false); modeSelector.setBusy(false);
        start.setEnabled(true); start.setText(messages.text("auto.start"));
    }

    /**
     * Captures only successful delivery entries for appearance rebuilds. / 仅捕获成功交付项，供外观重建使用。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    public Map<String, DeploymentHandoff> captureHandoffs() { return Map.copyOf(completedHandoffs); }

    /**
     * Restores clickable and copyable entries without duplicating the execution log. / 恢复可点击和复制的条目，不重复执行日志。
     *
     * @param saved saved / 已保存
     */
    public void restoreHandoffs(Map<String, DeploymentHandoff> saved) {
        completedHandoffs.clear(); completedHandoffs.putAll(saved); handoffs.removeAll();
        handoffScroll.setVisible(!saved.isEmpty());
        saved.forEach((id, value) -> showHandoff(id, value, false));
    }

    /**
     * Displays the verified deployment's access or application-command handoff with optional result logging.
     * <p>展示已验证部署的访问或应用命令交接，并可选记录结果。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param log log / 日志
     */
    private void showHandoff(String id, DeploymentHandoff result, boolean log) {
        String text = result instanceof DeploymentHandoff.HttpAccessUrl url ? url.url().toString()
                : result instanceof DeploymentHandoff.ApplicationEntry entry ? entry.command()
                : ((DeploymentHandoff.SystemdStartCommand) result).command();
        if (log) append(id + ": " + text);
        JButton copy = components.secondaryButton(id + " · " + messages.text("auto.copy"));
        copy.addActionListener(event -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null));
        handoffScroll.setVisible(true); handoffs.add(copy);
        if (result instanceof DeploymentHandoff.HttpAccessUrl url) {
            JButton open = components.secondaryButton(messages.text("auto.open"));
            open.addActionListener(event -> { try { Desktop.getDesktop().browse(url.url()); } catch (Exception failure) { append(messages.safe(failure)); } });
            handoffs.add(open);
        }
        handoffs.revalidate(); handoffs.repaint();
    }

    /**
     * Stores the entered application secret through the service and displays only its immutable reference.
     * <p>通过服务保存输入的应用秘密，并仅展示其不可变引用。
     */
    private void saveSecret() {
        if (busy) return;
        JPasswordField field = new JPasswordField(24);
        try {
            String referenceInput = form.secretReferences.getText();
            if (JOptionPane.showConfirmDialog(owner, field, messages.text("secret.value.title"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            CredentialStorageMode mode = serverContext.credentialMode();
            char[] master = serverContext.masterPassword(), value = field.getPassword();
            busy = true; ((AdvancedOptionsPane) panel).setBusy(true);
            DesktopTaskExecutor.run(() -> {
                try { return service.saveDeploymentSecretRevision(referenceInput, mode, master, value); }
                finally { Arrays.fill(master, '\0'); Arrays.fill(value, '\0'); }
            }, saved -> {
                finish(); output.setText(messages.text("secret.saved", Map.of("reference", saved.identifier() + ":" + saved.revision())));
            }, failure -> {
                finish(); output.setText(messages.text("secret.saveFailed", Map.of("detail", messages.safe(failure))));
            });
        } catch (Exception exception) {
            output.setText(messages.text("secret.saveFailed", Map.of("detail", messages.safe(exception))));
        } finally { field.setText(""); }
    }

    /**
     * Shows the reviewed HTTP access URL or the TCP-only access message.
     * <p>显示已审阅 HTTP 访问 URL 或仅 TCP 访问提示。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return access review text / 访问审阅文本
     */
    private String accessReview(Optional<UserAccessUrl> value) {
        return value.map(url -> messages.text("deployment.httpAccess", Map.of("url", url.url().toASCIIString())))
                .orElse(messages.text("deployment.tcpAccess"));
    }
}
