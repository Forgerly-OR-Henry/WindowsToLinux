package gold.debug.windowstolinux.app.ui.deployment.multi;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import gold.debug.windowstolinux.app.service.contract.MultiComponentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput;
import gold.debug.windowstolinux.app.service.contract.definition.ComponentHealthMode;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.ui.component.ToggleSwitch;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Owns multi-component controls, draft state, selection, and domain input mapping. / 持有多组件控件、草稿状态、选择与领域输入映射。
 */
final class MultiComponentDraftController {
    /**
     * Managed target with its server and ownership identity.
     * <p>携带服务器及归属身份的受管目标。
     */
    final ApplicationControls application = new ApplicationControls();

    /**
     * Swing control for component id.
     * <p>组件标识对应的 Swing 控件。
     */
    final JTextField componentId = new JTextField(14);

    /**
     * Swing control for relative root.
     * <p>相对根目录对应的 Swing 控件。
     */
    final JTextField relativeRoot = new JTextField(20);

    /**
     * Supported project deployment category.
     * <p>受支持的项目部署类别。
     */
    final JComboBox<DeploymentProjectType> projectType = new JComboBox<>(java.util.Arrays
            .stream(DeploymentProjectType.values()).filter(type -> type != DeploymentProjectType.MANAGED_PROCESS)
            .toArray(DeploymentProjectType[]::new));

    /**
     * Swing control for runtime primary.
     * <p>运行时主对应的 Swing 控件。
     */
    final JTextField runtimePrimary = new JTextField(18);

    /**
     * Swing control for runtime secondary.
     * <p>运行时次要对应的 Swing 控件。
     */
    final JTextField runtimeSecondary = new JTextField(18);

    /**
     * Swing control for kotlin jvm target.
     * <p>kotlinJvm目标对应的 Swing 控件。
     */
    final JTextField kotlinJvmTarget = new JTextField(6);

    /**
     * Swing control for runtime version.
     * <p>运行时版本对应的 Swing 控件。
     */
    final JTextField runtimeVersion = new JTextField(10);

    /**
     * Swing control for runtime arguments.
     * <p>运行时参数对应的 Swing 控件。
     */
    final JTextField runtimeArguments = new JTextField(18);

    /**
     * Swing control for runtime additional.
     * <p>运行时额外对应的 Swing 控件。
     */
    final JTextField runtimeAdditional = new JTextField(18);

    /**
     * Health mode.
     * <p>健康模式。
     */
    final JComboBox<ComponentHealthMode> healthMode = new JComboBox<>(ComponentHealthMode.values());

    /**
     * Swing control for health endpoint.
     * <p>健康端点对应的 Swing 控件。
     */
    final JTextField healthEndpoint = new JTextField(22);

    /**
     * Swing control for expected status.
     * <p>预期状态对应的 Swing 控件。
     */
    final JTextField expectedStatus = new JTextField("200", 6);

    /**
     * Swing control for timeout seconds.
     * <p>超时秒对应的 Swing 控件。
     */
    final JTextField timeoutSeconds = new JTextField("20", 6);

    /**
     * Swing control for stability seconds.
     * <p>稳定性秒对应的 Swing 控件。
     */
    final JTextField stabilitySeconds = new JTextField("5", 6);

    /**
     * Swing control for access url.
     * <p>访问URL对应的 Swing 控件。
     */
    final JTextField accessUrl = new JTextField(22);

    /**
     * Swing control for artifacts.
     * <p>制品集合对应的 Swing 控件。
     */
    final JTextField artifacts = new JTextField(20);

    /**
     * Swing control for ports.
     * <p>端口集合对应的 Swing 控件。
     */
    final JTextField ports = new JTextField(20);

    /**
     * Swing control for dependencies.
     * <p>依赖对应的 Swing 控件。
     */
    final JTextField dependencies = new JTextField(20);

    /**
     * Reviewed file, configuration and database bindings for this component.
     * <p>当前组件已审阅的文件、配置及数据库绑定。
     */
    final ResourceControls resources = new ResourceControls();

    /**
     * Whether the whole application requires this component.
     * <p>整体应用是否需要此组件。
     */
    final ToggleSwitch required;

    /**
     * Lifecycle.
     * <p>生命周期。
     */
    final LifecycleControls lifecycle = new LifecycleControls();

    /**
     * Draft controls.
     * <p>草稿控件集合。
     */
    final DraftControls draftControls = new DraftControls();

    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;

    /**
     * Bound multi component application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的多组件应用门面协作对象。
     */
    private final MultiComponentApplicationFacade service;

    /**
     * Binds the supplied dependencies and state for multi component draft controller.
     * <p>为多组件草稿控制器绑定传入的依赖及状态。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @param service application service used by the caller / 调用方使用的应用服务
     */
    MultiComponentDraftController(PageMessagePresenter messages, MultiComponentApplicationFacade service) {
        this.messages = messages;
        this.service = service;
        required = new ToggleSwitch(null, true);
        messages.localize(projectType, "project.type.");
        messages.localize(healthMode, "health.mode.");
        messages.localize(resources.databaseMode, "database.review.mode.");
        messages.localize(lifecycle.action, "lifecycle.action.");
        resources.databaseMode.addActionListener(event -> resources.databaseDetails
                .setEnabled(resources.databaseMode.getSelectedItem() != DatabaseReviewMode.UNREVIEWED
                        && resources.databaseMode.getSelectedItem() != DatabaseReviewMode.NONE));
        resources.databaseMode.setSelectedItem(DatabaseReviewMode.UNREVIEWED);
        resources.databaseDetails.setEnabled(false);
        draftControls.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        draftControls.list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting())
                selectedDraft().ifPresent(this::applyDraft);
        });
    }

    /**
     * Builds multi component page state from the supplied capture inputs.
     * <p>根据所提供捕获输入构建多组件页面状态。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparation preparation / 准备
     * @param review review / 审阅
     * @return multi component page state from the supplied capture inputs / 根据所提供捕获输入构建多组件页面状态
     */
    MultiComponentPageState capture(String output, PreparedMultiComponentSource preparation,
            ReviewedMultiComponentApplication review) {
        return new MultiComponentPageState(application.root.getText(), application.id.getText(),
                application.healthComponentId.getText(), lifecycle.targets.getText(), lifecycleAction(), formState(),
                orderedDrafts(), output, preparation, review);
    }

    /**
     * Restores multi component draft.
     * <p>恢复多组件草稿。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    void restore(MultiComponentPageState state) {
        application.root.setText(state.applicationRoot());
        application.id.setText(state.applicationId());
        application.healthComponentId.setText(state.healthComponentId());
        lifecycle.targets.setText(state.lifecycleTargets());
        lifecycle.action.setSelectedItem(state.lifecycleAction());
        applyFormState(state.form());
        draftControls.drafts.clear();
        state.drafts().forEach(draft -> draftControls.drafts.put(draft.componentId(), draft));
        refreshDraftList();
    }

    /**
     * Adds or update.
     * <p>添加或更新。
     *
     * @return constructed or resolved component form input / 构造或解析得到的组件表单输入
     */
    ComponentFormInput addOrUpdate() {
        ComponentFormInput draft = draftFromForm();
        service.parseComponentAnalysis(draft);
        draftControls.drafts.put(draft.componentId(), draft);
        if (application.healthComponentId.getText().isBlank()) {
            application.healthComponentId.setText(draft.componentId());
        }
        refreshDraftList();
        return draft;
    }

    /**
     * Removes explicitly selected item or state.
     * <p>移除显式选择的项目或状态。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    Optional<ComponentFormInput> removeSelected() {
        Optional<ComponentFormInput> selected = selectedDraft();
        selected.ifPresent(draft -> draftControls.drafts.remove(draft.componentId()));
        if (selected.isPresent())
            refreshDraftList();
        return selected;
    }

    /**
     * Returns ordered drafts.
     * <p>返回有序草稿集合。
     *
     * @return ordered drafts / 有序草稿集合
     */
    List<ComponentFormInput> orderedDrafts() {
        return draftControls.drafts.values().stream().sorted(Comparator.comparing(ComponentFormInput::componentId))
                .toList();
    }

    /**
     * Returns draft ids.
     * <p>返回草稿标识集合。
     *
     * @return draft ids / 草稿标识集合
     */
    Set<String> draftIds() {
        return Set.copyOf(draftControls.drafts.keySet());
    }

    /**
     * Reports whether the empty condition holds for this contract.
     * <p>判断当前契约是否满足空条件。
     *
     * @return true when empty condition holds for this contract, false otherwise / 当前契约是否满足空条件时为 true，否则为 false
     */
    boolean isEmpty() {
        return draftControls.drafts.isEmpty();
    }

    /**
     * Looks up the current editable draft for the component, returning null when absent.
     * <p>查找组件当前可编辑草稿，不存在时返回 null。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @return current component draft, or null when not registered / 当前组件草稿；未登记时为 null
     */
    ComponentFormInput draft(String componentId) {
        return draftControls.drafts.get(componentId);
    }

    /**
     * Returns application root.
     * <p>返回应用根目录。
     *
     * @return application root / 应用根目录
     */
    String applicationRoot() {
        return application.root.getText().trim();
    }

    /**
     * Returns managed application identifier.
     * <p>返回受管应用标识。
     *
     * @return managed application identifier / 受管应用标识
     */
    String applicationId() {
        return application.id.getText().trim();
    }

    /**
     * Returns health component id.
     * <p>返回健康组件标识。
     *
     * @return health component id / 健康组件标识
     */
    String healthComponentId() {
        return application.healthComponentId.getText().trim();
    }

    /**
     * Returns lifecycle action.
     * <p>返回生命周期动作。
     *
     * @return lifecycle action / 生命周期动作
     */
    LifecycleAction lifecycleAction() {
        return (LifecycleAction) lifecycle.action.getSelectedItem();
    }

    /**
     * Returns lifecycle targets.
     * <p>返回生命周期目标集合。
     *
     * @return lifecycle targets / 生命周期目标集合
     */
    Set<String> lifecycleTargets() {
        return identifiers(lifecycle.targets.getText());
    }

    /**
     * Builds component form input from the supplied draft from form inputs.
     * <p>根据所提供草稿来源表单输入构建组件表单输入。
     *
     * @return component form input from the supplied draft from form inputs / 根据所提供草稿来源表单输入构建组件表单输入
     */
    private ComponentFormInput draftFromForm() {
        return new ComponentFormInput(componentId.getText(), relativeRoot.getText(),
                (DeploymentProjectType) projectType.getSelectedItem(), runtimePrimary.getText(),
                runtimeSecondary.getText(), runtimeVersion.getText(), runtimeArguments.getText(),
                runtimeAdditional.getText(), (ComponentHealthMode) healthMode.getSelectedItem(),
                healthEndpoint.getText(), expectedStatus.getText(), timeoutSeconds.getText(),
                stabilitySeconds.getText(), accessUrl.getText(), artifacts.getText(), ports.getText(),
                dependencies.getText(), resources.configuration.getText(),
                (DatabaseReviewMode) resources.databaseMode.getSelectedItem(), resources.databaseDetails.getText(),
                resources.secrets.getText(), required.isSelected(), false, kotlinJvmTarget.getText(),
                resources.applicationDeclaration.getText());
    }

    /**
     * Builds multi component form state from the supplied form state inputs.
     * <p>根据所提供表单状态输入构建多组件表单状态。
     *
     * @return multi component form state from the supplied form state inputs / 根据所提供表单状态输入构建多组件表单状态
     */
    private MultiComponentFormState formState() {
        return new MultiComponentFormState(componentId.getText(), relativeRoot.getText(),
                ((DeploymentProjectType) projectType.getSelectedItem()).name(), runtimePrimary.getText(),
                runtimeSecondary.getText(), runtimeVersion.getText(), runtimeArguments.getText(),
                runtimeAdditional.getText(), ((ComponentHealthMode) healthMode.getSelectedItem()).name(),
                healthEndpoint.getText(), expectedStatus.getText(), timeoutSeconds.getText(),
                stabilitySeconds.getText(), accessUrl.getText(), artifacts.getText(), ports.getText(),
                dependencies.getText(), resources.configuration.getText(),
                ((DatabaseReviewMode) resources.databaseMode.getSelectedItem()).name(),
                resources.databaseDetails.getText(), resources.secrets.getText(), required.isSelected(), false,
                kotlinJvmTarget.getText(), resources.applicationDeclaration.getText());
    }

    /**
     * Restores the selected component's saved form values and runtime choices into the editor controls.
     * <p>将所选组件保存的表单值及运行选择恢复到编辑器控件。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    private void applyFormState(MultiComponentFormState state) {
        resources.applicationDeclaration.setText(state.applicationDeclaration());
        componentId.setText(state.componentId());
        relativeRoot.setText(state.relativeRoot());
        projectType.setSelectedItem(DeploymentProjectType.valueOf(state.projectType()));
        runtimePrimary.setText(state.runtimePrimary());
        runtimeSecondary.setText(state.runtimeSecondary());
        runtimeVersion.setText(state.runtimeVersion());
        kotlinJvmTarget.setText(state.kotlinJvmTarget());
        runtimeArguments.setText(state.runtimeArguments());
        runtimeAdditional.setText(state.runtimeAdditional());
        healthMode.setSelectedItem(ComponentHealthMode.valueOf(state.healthMode()));
        healthEndpoint.setText(state.healthEndpoint());
        expectedStatus.setText(state.expectedStatus());
        timeoutSeconds.setText(state.timeoutSeconds());
        stabilitySeconds.setText(state.stabilitySeconds());
        accessUrl.setText(state.accessUrl());
        artifacts.setText(state.artifacts());
        ports.setText(state.ports());
        dependencies.setText(state.dependencies());
        resources.configuration.setText(state.configuration());
        resources.databaseMode.setSelectedItem(DatabaseReviewMode.valueOf(state.databaseMode()));
        resources.databaseDetails.setText(state.databaseDetails());
        resources.secrets.setText(state.secrets());
        required.setSelected(state.required());
    }

    /**
     * Applies draft.
     * <p>应用草稿。
     *
     * @param draft draft / 草稿
     */
    private void applyDraft(ComponentFormInput draft) {
        applyFormState(new MultiComponentFormState(draft.componentId(), draft.relativeSourceRoot(),
                draft.projectType().name(), draft.runtimePrimary(), draft.runtimeSecondary(), draft.runtimeVersion(),
                draft.runtimeArguments(), draft.runtimeAdditional(), draft.healthMode().name(), draft.healthEndpoint(),
                draft.expectedStatus(), draft.timeoutSeconds(), draft.stabilitySeconds(), draft.userAccessUrl(),
                draft.artifactPaths(), draft.declaredPorts(), draft.dependencies(), draft.configurationEntries(),
                draft.databaseMode().name(), draft.databaseDetails(), draft.secretReferences(), draft.required(),
                draft.rootBuild(), draft.kotlinJvmTarget(), draft.applicationDeclaration()));
    }

    /**
     * Returns selected draft.
     * <p>返回已选草稿。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private Optional<ComponentFormInput> selectedDraft() {
        int index = draftControls.list.getSelectedIndex();
        List<ComponentFormInput> ordered = orderedDrafts();
        return index < 0 || index >= ordered.size() ? Optional.empty() : Optional.of(ordered.get(index));
    }

    /**
     * Refreshes draft list.
     * <p>刷新草稿列表。
     */
    private void refreshDraftList() {
        draftControls.listModel.clear();
        orderedDrafts().forEach(draft -> draftControls.listModel.addElement(draft.componentId() + " | "
                + messages.text("project.type." + draft.projectType().name().toLowerCase(Locale.ROOT)) + " | "
                + draft.relativeSourceRoot()));
    }

    /**
     * Checks identifiers syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查标识集合语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved set / 构造或解析得到的集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static Set<String> identifiers(String value) {
        if (value.isBlank())
            return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split("[,;]")) {
            String id = token.trim();
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("lifecycle target component identifier is invalid");
            }
            result.add(id);
        }
        return Set.copyOf(result);
    }

    /**
     * Groups the application-level Swing controls used by the component draft editor.
     * <p>组合组件草稿编辑器使用的应用级 Swing 控件。
     */
    static final class ApplicationControls {
        /**
         * Swing control for root.
         * <p>根目录对应的 Swing 控件。
         */
        final JTextField root = new JTextField(30);

        /**
         * Swing control for id.
         * <p>标识对应的 Swing 控件。
         */
        final JTextField id = new JTextField(18);

        /**
         * Swing control for health component id.
         * <p>健康组件标识对应的 Swing 控件。
         */
        final JTextField healthComponentId = new JTextField(14);
    }

    /**
     * Groups the lifecycle controls associated with the current application selection.
     * <p>组合与当前应用选择关联的生命周期控件。
     */
    static final class LifecycleControls {
        /**
         * Swing control for targets.
         * <p>目标集合对应的 Swing 控件。
         */
        final JTextField targets = new JTextField(22);

        /**
         * Explicit action selected for the current target.
         * <p>为当前目标显式选择的动作。
         */
        final JComboBox<LifecycleAction> action = new JComboBox<>(LifecycleAction.values());
    }

    /**
     * Groups the non-secret resource review controls for one component. / 组合一个组件不含秘密值的资源审阅控件。
     */
    static final class ResourceControls {
        /**
         * Application declaration.
         * <p>应用声明。
         */
        final javax.swing.JTextArea applicationDeclaration = new javax.swing.JTextArea(6, 30);

        /**
         * Swing control for configuration.
         * <p>配置对应的 Swing 控件。
         */
        final JTextField configuration = new JTextField(22);

        /**
         * The explicit database review mode.
         * <p>显式数据库审阅模式。
         */
        final JComboBox<DatabaseReviewMode> databaseMode = new JComboBox<>(DatabaseReviewMode.values());

        /**
         * Swing control for database details.
         * <p>数据库详情对应的 Swing 控件。
         */
        final JTextField databaseDetails = new JTextField(22);

        /**
         * Swing control for secrets.
         * <p>秘密集合对应的 Swing 控件。
         */
        final JTextField secrets = new JTextField(20);
    }

    /**
     * Groups component draft editing controls and their current selections.
     * <p>组合组件草稿编辑控件及其当前选择。
     */
    static final class DraftControls {
        /**
         * List model.
         * <p>列表模型。
         */
        final DefaultListModel<String> listModel = new DefaultListModel<>();

        /**
         * List.
         * <p>列表。
         */
        final JList<String> list = new JList<>(listModel);

        /**
         * Drafts.
         * <p>草稿集合。
         */
        private final Map<String, ComponentFormInput> drafts = new LinkedHashMap<>();
    }
}
