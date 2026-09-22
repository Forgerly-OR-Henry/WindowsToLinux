package gold.debug.windowstolinux.app.ui.deployment.multi;

import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import gold.debug.windowstolinux.app.service.contract.MultiComponentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.server.ServerContext;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel;

/**
 * Desktop product page for explicit mixed-project review, whole-application deployment, and lifecycle.
 *
 *  <p>用于显式混合项目审阅、整应用部署和生命周期的桌面产品页面。
 */
public final class MultiComponentPage {
    /**
     * Component or resource identity owning the operation.
     * <p>持有操作的组件或资源身份。
     */
    private final JFrame owner;

    /**
     * Bound multi component application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的多组件应用门面协作对象。
     */
    private final MultiComponentApplicationFacade service;

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
     * Open servers.
     * <p>打开服务器集合。
     */
    private final Runnable openServers;

    /**
     * Application selection.
     * <p>应用选择。
     */
    private final Consumer<String> applicationSelection;

    /**
     * Bound multi component result presenter collaborator for presenter.
     * <p>处理展示器的多组件结果展示器协作对象。
     */
    private final MultiComponentResultPresenter presenter;

    /**
     * Editor.
     * <p>编辑器。
     */
    private final MultiComponentDraftController editor;

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
     * Preparation.
     * <p>准备。
     */
    private PreparedMultiComponentSource preparation;

    /**
     * Review.
     * <p>审阅。
     */
    private ReviewedMultiComponentApplication review;

    /**
     * Advanced.
     * <p>高级。
     */
    private gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane advanced;

    /**
     * Creates the stateful multi-component controller. / 创建有状态多组件控制器。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param serverContext server context / 服务器上下文
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param messages localized message resolver / 本地化消息解析器
     * @param openServers open servers / 打开服务器集合
     * @param applicationSelection application selection / 应用选择
     */
    public MultiComponentPage(JFrame owner, MultiComponentApplicationFacade service, ServerContext serverContext,
            DesktopComponentFactory components, PageMessagePresenter messages, Runnable openServers,
            Consumer<String> applicationSelection) {
        this.owner = owner;
        this.service = service;
        this.serverContext = serverContext;
        this.messages = messages;
        this.openServers = openServers;
        this.applicationSelection = applicationSelection;
        presenter = new MultiComponentResultPresenter(messages);
        editor = new MultiComponentDraftController(messages, service);
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
     * Captures all non-secret editor and review state. / 捕获全部不含秘密的编辑与审阅状态。
     *
     * @return constructed or resolved multi component page state / 构造或解析得到的多组件页面状态
     */
    public MultiComponentPageState captureState() {
        return editor.capture(output.getText(), preparation, review);
    }

    /**
     * Restores all non-secret editor and review state. / 恢复全部不含秘密的编辑与审阅状态。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    public void restoreState(MultiComponentPageState state) {
        editor.restore(state);
        output.setText(state.output());
        preparation = state.preparation();
        review = state.review();
    }

    /**
     * Builds component discovery, graph editing, review and deployment controls for the whole application.
     * <p>构建整应用组件发现、图编辑、审阅及部署控件。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return component discovery, graph editing, review and deployment controls for the whole application / 整应用组件发现、图编辑、审阅及部署控件
     */
    private JPanel createPanel(DesktopComponentFactory components) {
        JPanel page = components.pagePanel();
        advanced = new gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane(page, components, messages);
        JPanel actions = components.transparent(new java.awt.GridLayout(2, 2, 8, 8));
        JButton root = components.secondaryButton(messages.text("component.button.selectRoot"));
        root.addActionListener(event -> chooseRoot());
        JButton add = components.secondaryButton(messages.text("component.button.add"));
        add.addActionListener(event -> addOrUpdate());
        JButton remove = components.secondaryButton(messages.text("component.button.remove"));
        remove.addActionListener(event -> removeSelected());
        JButton analyze = components.primaryButton(messages.text("component.button.analyze"));
        analyze.addActionListener(event -> analyze());
        JButton servers = components.secondaryButton(messages.text("button.goServer"));
        servers.addActionListener(event -> openServers.run());
        JButton deploy = components.primaryButton(messages.text("component.button.deploy"));
        deploy.addActionListener(event -> deploy());
        actions.add(root);
        actions.add(add);
        actions.add(remove);
        actions.add(deploy);
        advanced.addOption(analyze);
        advanced.addOption(servers);
        page.add(actions, BorderLayout.NORTH);

        JPanel contents = components.transparent(new BorderLayout(0, 12));
        contents.add(graphPanel(components), BorderLayout.NORTH);
        contents.add(resultPanel(components), BorderLayout.CENTER);
        page.add(contents, BorderLayout.CENTER);
        return advanced;
    }

    /**
     * Builds the application graph editor and its advanced configuration fields.
     * <p>构建应用图编辑器及其高级配置字段。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return the application graph editor and its advanced configuration fields / 应用图编辑器及其高级配置字段
     */
    private JPanel graphPanel(DesktopComponentFactory components) {
        JPanel graph = components.transparent(new BorderLayout(12, 12));
        JPanel form = components.card(new GridBagLayout());
        int row = 0;
        advanced.field("component.field.applicationRoot", editor.application.root);
        advanced.field("component.field.applicationId", editor.application.id);
        advanced.field("component.field.id", editor.componentId);
        advanced.field("component.field.relativeRoot", editor.relativeRoot);
        advanced.field("field.projectType", editor.projectType);
        advanced.field("component.field.healthOwner", editor.application.healthComponentId);
        advanced.field("field.runtimePrimary", editor.runtimePrimary);
        advanced.field("field.runtimeSecondary", editor.runtimeSecondary);
        advanced.field("field.runtimeVersion", editor.runtimeVersion);
        advanced.field("auto.field.jvmTarget", editor.kotlinJvmTarget);
        advanced.field("field.jvmArguments", editor.runtimeArguments);
        advanced.field("component.field.runtimeAdditional", editor.runtimeAdditional);
        advanced.field("field.applicationDeclaration",
                new javax.swing.JScrollPane(editor.resources.applicationDeclaration));
        advanced.field("field.healthMode", editor.healthMode);
        advanced.field("field.healthEndpoint", editor.healthEndpoint);
        advanced.field("field.expectedStatus", editor.expectedStatus);
        advanced.field("field.timeout", editor.timeoutSeconds);
        advanced.field("field.tcpStability", editor.stabilitySeconds);
        advanced.field("field.userAccessUrl", editor.accessUrl);
        advanced.field("component.field.artifacts", editor.artifacts);
        advanced.field("component.field.ports", editor.ports);
        advanced.field("component.field.dependencies", editor.dependencies);
        advanced.field("field.configurationEntries", editor.resources.configuration);
        advanced.field("field.secretReferences", editor.resources.secrets);
        advanced.field("field.databaseReviewMode", editor.resources.databaseMode);
        advanced.field("field.databaseDetails", editor.resources.databaseDetails);
        editor.required.setBorder(BorderFactory.createEmptyBorder());
        advanced.field("component.required", editor.required);

        editor.draftControls.list.setVisibleRowCount(4);
        graph.add(new JScrollPane(editor.draftControls.list), BorderLayout.CENTER);
        return graph;
    }

    /**
     * Builds the deployment result area with its review and execution controls.
     * <p>构建部署结果区域及其审阅和执行控件。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return the deployment result area with its review and execution controls / 部署结果区域及其审阅和执行控件
     */
    private JPanel resultPanel(DesktopComponentFactory components) {
        JPanel result = components.transparent(new BorderLayout(0, 12));
        result.add(components.outputCard(messages.text("component.result.title"),
                messages.text("component.result.description"), output), BorderLayout.CENTER);
        JPanel lifecycle = components.card(new BorderLayout(8, 8));
        JPanel selection = components.transparent(new BorderLayout(8, 0));
        selection.add(editor.lifecycle.action, BorderLayout.WEST);
        selection.add(editor.lifecycle.targets, BorderLayout.CENTER);
        lifecycle.add(selection, BorderLayout.NORTH);
        JButton execute = components.primaryButton(messages.text("component.button.lifecycle"));
        execute.addActionListener(event -> lifecycle());
        lifecycle.add(execute, BorderLayout.SOUTH);
        result.add(lifecycle, BorderLayout.SOUTH);
        return result;
    }

    /**
     * Chooses root directory defining the filesystem boundary.
     * <p>选择定义文件系统边界的根目录。
     */
    private void chooseRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            editor.application.root.setText(chooser.getSelectedFile().toPath().toString());
            invalidateReview();
        }
    }

    /**
     * Adds or update.
     * <p>添加或更新。
     */
    private void addOrUpdate() {
        try {
            ComponentFormInput draft = editor.addOrUpdate();
            invalidateReview();
            output.setText(messages.text("component.draft.saved", Map.of("component", draft.componentId())));
        } catch (Exception exception) {
            output.setText(messages.text("component.draft.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    /**
     * Removes explicitly selected item or state.
     * <p>移除显式选择的项目或状态。
     */
    private void removeSelected() {
        editor.removeSelected().ifPresent(draft -> {
            invalidateReview();
        });
    }

    /**
     * Analyzes the selected source asynchronously and populates component drafts from deterministic discovery evidence.
     * <p>异步分析所选源码，并根据确定性发现证据填充组件草稿。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void analyze() {
        if (busy)
            return;
        try {
            if (editor.isEmpty())
                throw new IllegalArgumentException(messages.text("component.validation.empty"));
            Path root = Path.of(editor.applicationRoot());
            String id = editor.applicationId();
            List<gold.debug.windowstolinux.shared.standard.analyze.component.ComponentAnalysisRequest> requests = editor
                    .orderedDrafts().stream().map(service::parseComponentAnalysis).toList();
            output.setText(messages.text("component.analysis.running"));
            setBusy(true);
            DesktopTaskExecutor.run(() -> service.prepareReviewedMultiComponentSource(root, id, requests), result -> {
                setBusy(false);
                preparation = result;
                review = null;
                output.setText(presenter.analysis(preparation));
            }, exception -> {
                setBusy(false);
                output.setText(messages.text("component.analysis.failed", Map.of("detail", messages.safe(exception))));
            });
        } catch (Exception exception) {
            output.setText(messages.text("component.analysis.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    /**
     * Requires a current complete review before submitting whole-application deployment and reporting its result.
     * <p>提交整应用部署并报告结果前，要求当前完整审阅有效。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void deploy() {
        if (busy)
            return;
        if (preparation == null || preparation.components().isEmpty()) {
            output.setText(messages.text("component.validation.analyzeFirst"));
            return;
        }
        try {
            ensureDraftCoverage();
            ServerProfile profile = serverContext.profile();
            var server = service.findTrustedServer(profile.id())
                    .orElseThrow(() -> new IllegalStateException(messages.text("deployment.serverFirst")));
            List<String> dockerComponents = editor.orderedDrafts().stream()
                    .filter(draft -> draft.projectType() == DeploymentProjectType.DOCKERFILE_CONTAINER
                            && "DOCKER".equalsIgnoreCase(draft.runtimePrimary()))
                    .map(ComponentFormInput::componentId).toList();
            if (!confirmRisk("deployment.dockerRisk.title", "component.dockerRisk", dockerComponents))
                return;
            List<String> experimentalComponents = preparation.assessment().components().stream().filter(
                    component -> component.facts().support().level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER)
                    .map(component -> component.componentId()).toList();
            if (!confirmRisk("component.experimentalRisk.title", "component.experimentalRisk", experimentalComponents))
                return;
            List<MultiComponentReviewInput> inputs = new ArrayList<>();
            for (var component : preparation.assessment().components()) {
                ComponentFormInput draft = editor.draft(component.componentId());
                inputs.add(service.parseComponentReview(draft, component.facts().applicationId(),
                        dockerComponents.contains(component.componentId()),
                        experimentalComponents.contains(component.componentId())));
            }
            String healthOwner = editor.healthComponentId();
            ComponentFormInput ownerDraft = editor.draft(healthOwner);
            if (ownerDraft == null)
                throw new IllegalArgumentException(messages.text("component.validation.healthOwner"));
            ReviewedMultiComponentApplication candidate = service.createReviewedMultiComponentApplication(preparation,
                    server, inputs, new ApplicationHealthGate(healthOwner,
                            service.parseComponentAnalysis(ownerDraft).runtime().orElseThrow().healthCheck()));
            if (JOptionPane.showConfirmDialog(owner, presenter.review(candidate),
                    messages.text("component.review.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
                return;
            char[] masterPassword = serverContext.masterPassword();
            var credentialMode = serverContext.credentialMode();
            output.setText(messages.text("component.deployment.running"));
            setBusy(true);
            DesktopTaskExecutor.run(() -> {
                for (MultiComponentReviewInput input : inputs) {
                    service.saveDeploymentConfigurationSnapshot(input.configuration());
                }
                return service.deployReviewedMultiComponentWithStoredPassword(candidate, profile, credentialMode,
                        masterPassword, serverContext::confirmFingerprint);
            }, result -> {
                setBusy(false);
                output.setText(presenter.deployment(result));
                if (result.status() == DeploymentStatus.SUCCEEDED) {
                    review = candidate;
                    applicationSelection.accept(candidate.components().getFirst().application().id());
                }
            }, exception -> {
                setBusy(false);
                output.setText(
                        messages.text("component.deployment.failed", Map.of("detail", messages.safe(exception))));
            });
        } catch (Exception exception) {
            output.setText(messages.text("component.deployment.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    /**
     * Starts the selected managed application's lifecycle action after validating the current selection.
     * <p>校验当前选择后启动所选受管应用的生命周期动作。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void lifecycle() {
        if (busy)
            return;
        try {
            String managedApplicationId = editor.applicationId();
            if (managedApplicationId.isEmpty())
                throw new IllegalArgumentException(messages.text("component.validation.applicationId"));
            LifecycleAction action = editor.lifecycleAction();
            Set<String> targets = editor.lifecycleTargets();
            ServerProfile profile = serverContext.profile();
            char[] masterPassword = serverContext.masterPassword();
            var credentialMode = serverContext.credentialMode();
            Set<String> selectedTargets = Set.copyOf(targets);
            output.setText(messages.text("component.lifecycle.running"));
            setBusy(true);
            DesktopTaskExecutor.run(() -> {
                var managed = service.findManagedMultiComponentApplication(managedApplicationId).orElseThrow(
                        () -> new IllegalStateException(messages.text("component.validation.deployFirst")));
                Set<String> effectiveTargets = selectedTargets.isEmpty() && action != LifecycleAction.REFRESH_STATUS
                        ? Set.copyOf(managed.plan().startOrder())
                        : selectedTargets;
                return service.executeManagedMultiComponentLifecycleWithStoredPassword(managedApplicationId,
                        effectiveTargets, action, profile, credentialMode, masterPassword);
            }, result -> {
                setBusy(false);
                output.setText(presenter.lifecycle(result));
            }, exception -> {
                setBusy(false);
                output.setText(messages.text("component.lifecycle.failed", Map.of("detail", messages.safe(exception))));
            });
        } catch (Exception exception) {
            output.setText(messages.text("component.lifecycle.failed", Map.of("detail", messages.safe(exception))));
        }
    }

    /**
     * Confirms risk.
     * <p>确认风险。
     *
     * @param titleKey title key / 标题键
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param componentIds affected component identifiers / 受影响的组件标识符
     * @return true when confirms risk, false otherwise / 确认风险时为 true，否则为 false
     */
    private boolean confirmRisk(String titleKey, String messageKey, List<String> componentIds) {
        return componentIds.isEmpty() || JOptionPane.showConfirmDialog(owner,
                messages.text(messageKey, Map.of("components", String.join(", ", componentIds))),
                messages.text(titleKey), JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /**
     * Whether a page action is in progress and conflicting controls must remain disabled.
     * <p>页面动作是否正在进行且冲突控件须保持禁用。
     */
    private boolean busy;
    /**
     * Updates whether a page action is in progress and conflicting controls must remain disabled.
     * <p>更新页面动作是否正在进行且冲突控件须保持禁用。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private void setBusy(boolean value) {
        busy = value;
        advanced.setBusy(value);
    }

    /**
     * Requires prepared component identifiers and source root to match the current editable drafts.
     * <p>要求已准备组件标识及源码根目录与当前可编辑草稿一致。
     *
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void ensureDraftCoverage() {
        Set<String> preparedIds = preparation.components().keySet();
        Path currentRoot = Path.of(editor.applicationRoot()).toAbsolutePath().normalize();
        if (!preparedIds.equals(editor.draftIds()) || !preparation.assessment().applicationRoot().equals(currentRoot)
                || !preparation.assessment().applicationId().equals(editor.applicationId())) {
            throw new IllegalStateException(messages.text("component.validation.graphChanged"));
        }
    }

    /**
     * Discards the cached preparation and review after inputs have changed.
     * <p>输入变化后丢弃缓存的准备及审阅结果。
     */
    private void invalidateReview() {
        preparation = null;
        review = null;
    }

}
