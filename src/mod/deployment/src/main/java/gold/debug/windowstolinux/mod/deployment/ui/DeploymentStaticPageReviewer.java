package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Candidate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Inventory;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentDatabaseSourceInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField;

/** Translates one current owner-view decision into typed static review values. / 将当前所属视图的一次决定转换为类型化静态审阅值。 */
final class DeploymentStaticPageReviewer implements DeploymentStaticWorkflow.Reviewer {
    /** Owning page. / 所属页面。 */
    private final DeploymentStaticPage page;

    /** Original view generation. / 原视图代次。 */
    private final long view;

    /** Binds one original view, not the publication task lifetime. / 绑定原视图，而非发布任务寿命。
     * @param page original page / 原页面
     * @param view captured generation / 已捕获代次 */
    DeploymentStaticPageReviewer(DeploymentStaticPage page, long view) {
        this.page = page;
        this.view = view;
    }

    /** Reviews actual source-derived database fields on the owner view, checking files on the task worker. / 在所属视图审阅真实源码数据库字段，并在任务线程检查文件。
     * @param server selected target / 选定目标
     * @param componentId actual component / 实际组件
     * @param sourceSha256 frozen manifest / 冻结清单
     * @param root frozen component root / 冻结组件根
     * @param applicationId final managed identity / 最终受管身份
     * @param assessment original source facts / 原源码事实
     * @param task original task / 原任务
     * @return explicit non-secret answers / 明确非秘密答案
     * @throws Exception if the owner view cannot complete the interaction / 所属视图无法完成交互时 */
    @Override
    public OperationResult<Map<String, String>> databaseInputs(ServerRef server, String componentId,
            String sourceSha256, java.nio.file.Path root, String applicationId,
            DatabaseProjectInspector.Assessment assessment, TaskContext task) throws Exception {
        Map<String, String> supplied = Map.of();
        var form = page.onEdt(() -> new DatabaseInputForm(page, componentId, sourceSha256, assessment,
                DeploymentDatabaseSourceInputs.questions(assessment, applicationId, Map.of())));
        while (page.active(view)) {
            if (!page.approve(view, form.panel, page.text("databaseSourceReview"), task))
                return cancelled();
            var edited = page.onEdt(form::answers);
            var merged = new LinkedHashMap<>(supplied);
            merged.putAll(edited);
            supplied = Map.copyOf(merged);
            try {
                DeploymentDatabaseSourceInputs.complete(root, applicationId, assessment, supplied);
                return success(supplied);
            } catch (IllegalArgumentException | java.io.IOException invalid) {
                var answers = supplied;
                form = page.onEdt(() -> new DatabaseInputForm(page, componentId, sourceSha256, assessment,
                        DeploymentDatabaseSourceInputs.questions(assessment, applicationId, answers, true)));
                var current = form;
                page.onEdt(() -> {
                    current.error.setText(page.text("invalidForm"));
                    return null;
                });
            }
        }
        return cancelled();
    }

    /** Shows every first-initialization source file before creating a planned review. / 生成计划批准前展示每份首次初始化源码文件。
     * @param server selected target / 选定目标
     * @param componentId actual component / 实际组件
     * @param sourceSha256 frozen manifest / 冻结清单
     * @param assessment completed declaration / 已补齐声明
     * @param task original task / 原任务
     * @return explicit consent or cancellation / 明确同意或取消 */
    @Override
    public OperationResult<Boolean> confirmSqliteInitialization(ServerRef server, String componentId,
            String sourceSha256, DatabaseProjectInspector.Assessment assessment, TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = new JPanel(new GridLayout(0, 1));
            rows.add(new JLabel(page.text("sqliteFirstInitialization")));
            rows.add(new JLabel(page.text("sourceDigest") + ": " + sourceSha256));
            for (var database : assessment.databases()) {
                var sqlite = database.sqlite().orElseThrow();
                rows.add(new JLabel(componentId + " / " + database.id() + " / " + sqlite.fileName()));
                rows.add(new JLabel(page.text("database.path") + ": " + sqlite.accessPath()));
                rows.add(new JLabel(page.text("database.seed") + ": " + sqlite.seedFile()));
                rows.add(new JLabel(
                        page.text("database.initialize") + ": " + String.join(", ", database.initializationFiles())));
            }
            return rows;
        });
        if (!page.approve(view, panel, page.text("sqliteFirstInitialization"), task))
            return cancelled();
        return success(Boolean.TRUE);
    }

    /** Selects an observed running instance by its exact helper identity. / 按 helper 精确身份选择已观察运行实例。
     * @param server selected server / 选中服务器
     * @param componentId frozen component / 冻结组件
     * @param database completed declaration / 已补齐声明
     * @param inventory exact helper inventory / 精确 helper 库存
     * @param task original task / 原任务
     * @return selected exact ID or refusal / 选中精确 ID 或拒绝 */
    @Override
    public OperationResult<String> choose(ServerRef server, String componentId, DatabaseRequirement database,
            Inventory inventory, TaskContext task) {
        var selected = page.onEdt(() -> {
            var values = inventory.instances().stream().map(Instance::id).toArray(String[]::new);
            var box = new JComboBox<>(values);
            var panel = new JPanel(new GridLayout(0, 1));
            panel.add(new JLabel(componentId + " / " + database.id() + " / " + database.engine()));
            for (var instance : inventory.instances())
                panel.add(new JLabel(instance.id() + " / " + instance.version() + " / " + instance.service() + " / "
                        + (instance.running()
                                ? page.text("database.nativeRunning")
                                : page.text("database.nativeStopped"))));
            panel.add(box);
            return new java.util.AbstractMap.SimpleImmutableEntry<>(panel, box);
        });
        if (!page.approve(view, selected.getKey(), page.text("database.nativeChoose"), task))
            return cancelled();
        return success(page.onEdt(() -> (String) selected.getValue().getSelectedItem()));
    }

    /** Confirms the exact package before installing an absent database family. / 缺失数据库族安装前确认精确软件包。
     * @param server admitted server / 已准入服务器
     * @param database source requirement / 来源要求
     * @param candidate observed package / 已观察软件包
     * @param task original task / 原任务
     * @return explicit decision / 明确决定 */
    @Override
    public OperationResult<Boolean> install(ServerRef server, DatabaseRequirement database, Candidate candidate,
            TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = new JPanel(new GridLayout(0, 1));
            rows.add(new JLabel(page.text("database.nativeInstallWarning")));
            rows.add(new JLabel(server.id() + " / " + database.engine() + " / " + database.id()));
            rows.add(new JLabel(
                    candidate.name() + " / " + candidate.packageVersion() + " / " + candidate.engineVersion()));
            return rows;
        });
        return page.approve(view, panel, page.text("database.nativeInstallTitle"), task)
                ? success(Boolean.TRUE)
                : cancelled();
    }

    /** Confirms backup and downtime before exact package replacement. / 精确替换软件包前确认自行备份与停机。
     * @param server admitted server / 已准入服务器
     * @param previous selected instance / 选中实例
     * @param candidate exact candidate / 精确候选
     * @param task original task / 原任务
     * @return explicit decision / 明确决定 */
    @Override
    public OperationResult<Boolean> replacement(ServerRef server, Instance previous, Candidate candidate,
            TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = new JPanel(new GridLayout(0, 1));
            rows.add(new JLabel(page.text("database.nativeReplacementWarning")));
            rows.add(new JLabel(server.id() + " / " + previous.engine() + " / " + previous.id()));
            rows.add(new JLabel(previous.version() + " → " + candidate.engineVersion()));
            rows.add(new JLabel(candidate.name() + " / " + candidate.packageVersion()));
            return rows;
        });
        return page.approve(view, panel, page.text("database.nativeReplacementTitle"), task)
                ? success(Boolean.TRUE)
                : cancelled();
    }

    /** Confirms user-completed restoration after package replacement. / 软件包替换后确认用户已完成人工恢复。
     * @param server admitted server / 已准入服务器
     * @param instance newly observed instance / 新观察实例
     * @param task original task / 原任务
     * @return explicit decision / 明确决定 */
    @Override
    public OperationResult<Boolean> restored(ServerRef server, Instance instance, TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = new JPanel(new GridLayout(0, 1));
            rows.add(new JLabel(page.text("database.nativeRestoreWarning")));
            rows.add(new JLabel(server.id() + " / " + instance.engine() + " / " + instance.id()));
            rows.add(new JLabel(instance.version() + " / " + instance.service()));
            return rows;
        });
        return page.approve(view, panel, page.text("database.nativeRestoreTitle"), task)
                ? success(Boolean.TRUE)
                : cancelled();
    }

    /** Requests a short-lived administrator password after the helper actually reports AUTH_REQUIRED. / helper 实际报告 AUTH_REQUIRED 后请求短期管理员密码。
     * @param server selected server / 选中服务器
     * @param instance exact instance / 精确实例
     * @param task original task / 原任务
     * @return caller-owned UTF-8 bytes or cancellation / 调用方所属 UTF-8 字节或取消
     * @throws Exception if the current view fails / 当前视图失败时 */
    @Override
    public OperationResult<byte[]> administratorPassword(ServerRef server, Instance instance, TaskContext task)
            throws Exception {
        try {
            return success(
                    DeploymentStaticPasswordInput.request(page, view, task, page.text("database.nativeAdminTitle"),
                            instance.id() + " / " + page.text("database.nativeAdminPassword"), true, () -> {
                            }));
        } catch (CancellationException declined) {
            return cancelled();
        }
    }

    /** Requests a short-lived application password without converting it to String. / 不转换为 String 地请求短期应用密码。
     * @param server selected server / 选中服务器
     * @param database completed declaration / 已补齐声明
     * @param task original task / 原任务
     * @return caller-owned UTF-8 bytes or cancellation / 调用方所属 UTF-8 字节或取消
     * @throws Exception if the current view fails / 当前视图失败时 */
    @Override
    public OperationResult<byte[]> applicationPassword(ServerRef server, DatabaseRequirement database, TaskContext task)
            throws Exception {
        try {
            return success(DeploymentStaticPasswordInput.request(page, view, task,
                    page.text("database.nativeApplicationTitle"),
                    database.id() + " / " + page.text("database.nativeApplicationPassword"), false, () -> {
                    }));
        } catch (CancellationException declined) {
            return cancelled();
        }
    }

    /** Requires a separate existing-schema decision bound to the component digest. / 要求绑定组件摘要的独立已有结构决定。
     * @param server selected server / 选中服务器
     * @param componentId frozen component / 冻结组件
     * @param database completed declaration / 已补齐声明
     * @param sourceDigest actual component inspection digest / 实际组件检查摘要
     * @param task original task / 原任务
     * @return explicit affirmative decision or cancellation / 显式肯定决定或取消 */
    @Override
    public OperationResult<Boolean> existingSchema(ServerRef server, String componentId, DatabaseRequirement database,
            String sourceDigest, TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = new JPanel(new GridLayout(0, 1));
            rows.add(new JLabel(page.text("database.nativeExistingSchema")));
            rows.add(new JLabel(server.id() + " / " + componentId + " / " + database.id() + " / " + database.engine()
                    + " / " + database.database()));
            rows.add(new JLabel(page.text("database.inspectionDigest") + ": " + sourceDigest));
            rows.add(new JLabel(
                    page.text("database.initialize") + ": " + String.join(", ", database.initializationFiles())));
            return rows;
        });
        return page.approve(view, panel, page.text("database.nativeExistingSchema"), task)
                ? success(Boolean.TRUE)
                : cancelled();
    }

    /** Bounded non-secret database fields on the EDT. / EDT 上有界非秘密数据库字段。 */
    private static final class DatabaseInputForm {
        /** Source-bound form. / 来源绑定表单。 */
        private final JPanel panel = new JPanel(new GridLayout(0, 1, 8, 8));

        /** Local error message. / 本地错误提示。 */
        private final JLabel error = new JLabel();

        /** Typed input controls. / 类型化输入控件。 */
        private final Map<String, JComponent> fields = new LinkedHashMap<>();

        /** Builds questions without reading source files on the EDT. / 不在 EDT 读取源码文件而构建问题。
         * @param page owner page / 所属页
         * @param componentId reviewed component / 经审阅组件
         * @param sourceSha256 frozen manifest / 冻结清单
         * @param assessment source-derived declarations / 源码派生声明
         * @param questions actual missing inputs / 实际缺失输入 */
        private DatabaseInputForm(DeploymentStaticPage page, String componentId, String sourceSha256,
                DatabaseProjectInspector.Assessment assessment, List<DeploymentReviewField> questions) {
            panel.add(new JLabel(componentId + " / " + page.text("sourceDigest")));
            panel.add(new JLabel(sourceSha256));
            for (var database : assessment.databases()) {
                panel.add(new JLabel(database.id() + " / " + database.engine()));
                panel.add(new JLabel(database.sqlite().map(value -> value.accessPath() + " / " + value.seedFile())
                        .orElse(database.database())));
                panel.add(new JLabel(page.text("database.initialize")));
                panel.add(new JLabel(String.join(", ", database.initializationFiles())));
            }
            for (var question : questions) {
                JComponent input = question.choices().isEmpty() || question.id().endsWith(".initialize")
                        ? new JTextField(question.value())
                        : new JComboBox<>(question.choices().toArray(String[]::new));
                if (input instanceof JTextField field && question.id().endsWith(".initialize"))
                    field.setToolTipText(String.join(", ", question.choices()));
                if (input instanceof JComboBox<?> combo && !question.value().isBlank())
                    combo.setSelectedItem(question.value());
                panel.add(new JLabel(page.text(question.titleKey())));
                panel.add(input);
                fields.put(question.id(), input);
            }
            panel.add(error);
            panel.add(new JLabel());
        }

        /** Reads only the current EDT text values. / 仅读取当前 EDT 文本值。
         * @return non-secret reviewed answers / 非秘密经审阅答案 */
        private Map<String, String> answers() {
            var values = new LinkedHashMap<String, String>();
            fields.forEach((id, input) -> values.put(id,
                    input instanceof JTextField field
                            ? field.getText().trim()
                            : String.valueOf(((JComboBox<?>) input).getSelectedItem())));
            return Map.copyOf(values);
        }
    }

    /** Selects only actual discovered component roots. / 仅选择实际发现的组件根。
     * @param server exact selected server / 精确选定服务器
     * @param sourceSha256 frozen source digest / 冻结来源摘要
     * @param discovered actual roots / 实际根
     * @param task original task / 原任务
     * @return reviewed graph selection / 经审阅图选择 */
    @Override
    public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String sourceSha256,
            List<DiscoveredProjectComponent> discovered, TaskContext task) {
        var form = page.onEdt(() -> new SelectionForm(page, discovered));
        while (page.active(view)) {
            if (!page.approve(view, form.panel, page.text("componentReview"), task))
                return cancelled();
            try {
                var selection = page.onEdt(form::selection);
                if (!DeploymentStaticWorkflow.validSelection(discovered, selection))
                    throw new IllegalArgumentException("Review choices are incomplete");
                return success(selection);
            } catch (IllegalArgumentException invalid) {
                page.onEdt(() -> {
                    form.error.setText(page.text("invalidForm"));
                    return null;
                });
            }
        }
        return cancelled();
    }

    /** The component choices are built and read only by the EDT. / 组件选择仅由 EDT 构建和读取。 */
    private static final class SelectionForm {
        /** Editable selection controls. / 可编辑选择控件。 */
        private final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));

        /** Validation message. / 校验提示。 */
        private final JLabel error = new JLabel();

        /** Shared application identity. / 共享应用身份。 */
        private final JTextField application;

        /** Actual discovered components. / 实际发现组件。 */
        private final List<DiscoveredProjectComponent> discovered;

        /** Project-kind choices. / 项目类型选择。 */
        private final Map<String, JComboBox<DeploymentProjectKind>> types = new LinkedHashMap<>();

        /** Explicit dependency inputs. / 明确依赖输入。 */
        private final Map<String, JTextField> dependencies = new LinkedHashMap<>();

        /** Graph health owner. / 图健康所属组件。 */
        private final JComboBox<String> healthOwner;

        /** Builds a form from the discovered roots. / 从已发现根构建表单。
         * @param page owner page / 所属页
         * @param discovered actual components / 实际组件 */
        private SelectionForm(DeploymentStaticPage page, List<DiscoveredProjectComponent> discovered) {
            this.discovered = List.copyOf(discovered);
            application = discovered.size() > 1 ? new JTextField("application") : null;
            if (application != null) {
                panel.add(new JLabel(page.text("application")));
                panel.add(application);
            }
            for (var component : discovered) {
                var type = new JComboBox<>(component.types().stream().filter(DeploymentProjectKind::deployable)
                        .toArray(DeploymentProjectKind[]::new));
                type.setRenderer((list, value, index, selected, focus) -> {
                    var label = new JLabel(value == null ? "" : page.text("type." + value.name()));
                    label.setOpaque(true);
                    label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
                    label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                    return label;
                });
                panel.add(new JLabel(component.id() + " — " + component.relativeRoot()));
                panel.add(type);
                types.put(component.id(), type);
                if (application != null) {
                    var dependency = new JTextField();
                    panel.add(new JLabel(page.text("dependencies") + " · " + component.id()));
                    panel.add(dependency);
                    dependencies.put(component.id(), dependency);
                }
            }
            healthOwner = application == null
                    ? null
                    : new JComboBox<>(discovered.stream().map(DiscoveredProjectComponent::id).toArray(String[]::new));
            if (healthOwner != null) {
                panel.add(new JLabel(page.text("healthOwner")));
                panel.add(healthOwner);
            }
            panel.add(error);
            panel.add(new JLabel());
        }

        /** Reads the current EDT choices. / 读取当前 EDT 选择。
         * @return selected graph / 选定图 */
        private DeploymentStaticWorkflow.Selection selection() {
            var choices = new LinkedHashMap<String, DeploymentStaticWorkflow.Choice>();
            for (var component : discovered) {
                var type = (DeploymentProjectKind) types.get(component.id()).getSelectedItem();
                if (type == null)
                    throw new IllegalArgumentException("Component type is required");
                var edges = dependencies.containsKey(component.id())
                        ? Set.copyOf(java.util.Arrays
                                .stream(dependencies.get(component.id()).getText().trim().split("[,;\\s]+"))
                                .filter(value -> !value.isBlank()).toList())
                        : Set.<String>of();
                choices.put(component.id(), new DeploymentStaticWorkflow.Choice(type, edges));
            }
            return new DeploymentStaticWorkflow.Selection(
                    application == null ? discovered.getFirst().id() : application.getText().trim(), choices,
                    healthOwner == null ? Optional.empty() : Optional.of((String) healthOwner.getSelectedItem()));
        }
    }

    /** Collects editable runtime and resource values for the source-bound prompts. / 为来源绑定问题收集可编辑运行与资源值。
     * @param server exact target / 精确目标
     * @param sourceSha256 source digest / 来源摘要
     * @param prompts typed prompts / 类型化问题
     * @param task original task / 原任务
     * @return reviewed values / 经审阅值 */
    @Override
    public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server, String sourceSha256,
            List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
        var form = page.onEdt(() -> new ReviewForm(page, sourceSha256, prompts));
        while (page.active(view)) {
            if (!page.approve(view, form.panel, page.text("runtimeReview"), task))
                return cancelled();
            try {
                var answers = page.onEdt(form::answers);
                for (var prompt : prompts)
                    page.validateReview(prompt, answers.get(prompt.componentId()));
                return success(answers);
            } catch (IllegalArgumentException invalid) {
                page.onEdt(() -> {
                    form.error.setText(page.text("invalidForm"));
                    return null;
                });
            }
        }
        return cancelled();
    }

    /** Edits only four build fields while retaining the original resource decisions. / 仅编辑四个构建字段并保留原资源决定。
     * @param server original target / 原目标
     * @param sourceSha256 frozen source digest / 冻结来源摘要
     * @param prompt original component and correction fields / 原组件及纠正字段
     * @param original complete original answers / 完整原答案
     * @param task original publication task / 原发布任务
     * @return explicitly reviewed correction or cancellation / 明确审阅的纠正或取消 */
    @Override
    public OperationResult<DeploymentStaticWorkflow.Answers> reviewBuildCorrection(ServerRef server,
            String sourceSha256, DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers original,
            TaskContext task) {
        var form = page.onEdt(() -> new BuildCorrectionForm(page, sourceSha256, prompt, original));
        while (page.active(view)) {
            if (!page.approve(view, form.panel, page.text("runtimeReview"), task))
                return cancelled();
            try {
                var answer = page.onEdt(form::answer);
                page.validateReview(prompt, answer);
                return success(answer);
            } catch (IllegalArgumentException invalid) {
                page.onEdt(() -> {
                    form.error.setText(page.text("invalidForm"));
                    return null;
                });
            }
        }
        return cancelled();
    }

    /** Four bounded build controls, without editable resource or risk controls. / 四项有界构建控件，不含可编辑资源或风险控件。 */
    private static final class BuildCorrectionForm {
        /** Original complete answers. / 完整原答案。 */
        private final DeploymentStaticWorkflow.Answers original;

        /** Visible correction fields. / 可见纠正字段。 */
        private final Map<String, JComponent> fields = new LinkedHashMap<>();

        /** Current owner-view form. / 当前所属视图表单。 */
        private final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));

        /** Validation message. / 校验提示。 */
        private final JLabel error = new JLabel();

        /** Builds exactly the original component's four correction controls. / 仅创建原组件的四个纠正控件。
         * @param page original owner view / 原所属视图
         * @param sourceSha256 frozen source / 冻结来源
         * @param prompt typed correction prompt / 类型化纠正问题
         * @param original complete original answers / 完整原答案 */
        private BuildCorrectionForm(DeploymentStaticPage page, String sourceSha256,
                DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers original) {
            this.original = original;
            panel.add(new JLabel(page.text("sourceDigest")));
            panel.add(new JLabel(sourceSha256));
            panel.add(new JLabel(page.text("application")));
            panel.add(new JLabel(prompt.applicationId()));
            for (var field : prompt.fields()) {
                String prefix = prompt.componentId() + "/";
                if (!field.id().startsWith(prefix))
                    throw new IllegalArgumentException("Build correction belongs to another component");
                String key = field.id().substring(prefix.length());
                if (!Set.of("primary", "secondary", "version", "jvmTarget").contains(key) || fields.containsKey(key))
                    throw new IllegalArgumentException("Build correction field differs from the original scope");
                JComponent input = field.choices().isEmpty()
                        ? new JTextField(field.value())
                        : new JComboBox<>(field.choices().toArray(String[]::new));
                if (input instanceof JComboBox<?> choices)
                    choices.setSelectedItem(field.value());
                input.setName(key);
                fields.put(key, input);
                panel.add(new JLabel(label(page, key)));
                panel.add(input);
            }
            if (!fields.keySet().equals(Set.of("primary", "secondary", "version", "jvmTarget")))
                throw new IllegalArgumentException("Build correction requires exactly four fields");
            panel.add(error);
            panel.add(new JLabel());
        }

        /** Copies the original answers and replaces only the four visible values. / 复制原答案并仅替换四项可见值。
         * @return corrected values with identical resources, limits and risks / 资源、限额和风险相同的纠正值 */
        private DeploymentStaticWorkflow.Answers answer() {
            var values = new LinkedHashMap<>(original.values());
            fields.forEach((key, control) -> values.put(key,
                    control instanceof JTextField text
                            ? text.getText().trim()
                            : String.valueOf(((JComboBox<?>) control).getSelectedItem())));
            return new DeploymentStaticWorkflow.Answers(values, original.files(), original.limits(),
                    original.containerRisk(), original.adapterRisk());
        }
    }

    /** One complete editable review on the EDT. / EDT 上的一份完整可编辑审阅。 */
    private static final class ReviewForm {
        /** Tabbed review container. / 分页审阅容器。 */
        private final JPanel panel = new JPanel(new java.awt.BorderLayout());

        /** Validation message. / 校验提示。 */
        private final JLabel error = new JLabel();

        /** Per-component forms. / 逐组件表单。 */
        private final Map<String, ComponentForm> forms = new LinkedHashMap<>();

        /** Creates source-bound component tabs. / 创建来源绑定的组件页。
         * @param page owner page / 所属页
         * @param sourceSha256 source manifest digest / 来源清单摘要
         * @param prompts actual review prompts / 实际审阅问题 */
        private ReviewForm(DeploymentStaticPage page, String sourceSha256,
                List<DeploymentStaticWorkflow.Prompt> prompts) {
            var tabs = new JTabbedPane();
            for (var prompt : prompts) {
                var form = new ComponentForm(page, prompt);
                tabs.addTab(prompt.componentId(), new JScrollPane(form.panel()));
                forms.put(prompt.componentId(), form);
            }
            panel.add(new JLabel(page.text("sourceDigest") + "  " + sourceSha256), java.awt.BorderLayout.NORTH);
            panel.add(tabs, java.awt.BorderLayout.CENTER);
            panel.add(error, java.awt.BorderLayout.SOUTH);
        }

        /** Reads all edited component values. / 读取全部已编辑组件值。
         * @return reviewed values by component / 按组件的审阅值 */
        private Map<String, DeploymentStaticWorkflow.Answers> answers() {
            var answers = new LinkedHashMap<String, DeploymentStaticWorkflow.Answers>();
            forms.forEach((id, form) -> answers.put(id, form.answer()));
            return Map.copyOf(answers);
        }
    }

    /** Confirms actual archive bytes after source and runtime review. / 在来源及运行时审阅后确认实际归档字节。
     * @param server exact target / 精确目标
     * @param sourceSha256 source manifest digest / 来源清单摘要
     * @param archiveSha256 each actual tar digest / 各实际 tar 摘要
     * @param answers final edited answers / 最终编辑答案
     * @param databases source-derived SQLite facts / 源码派生 SQLite 事实
     * @param git original URL, selected ref and pinned commit / 原 URL、选定引用和固定提交
     * @param task original task / 原任务
     * @return current explicit confirmation / 当前明确确认 */
    @Override
    public OperationResult<Boolean> confirm(ServerRef server, String sourceSha256, Map<String, String> archiveSha256,
            Map<String, DeploymentStaticWorkflow.Answers> answers, Map<String, DeploymentPreparedDatabase> databases,
            Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
        var panel = page.onEdt(() -> confirmation(page, server, sourceSha256, archiveSha256, answers, databases, git));
        if (!page.approve(view, panel, page.text("finalConfirm"), task, true))
            return cancelled();
        return success(Boolean.TRUE);
    }

    /** Shows the exact reviewed values as separate rows, never a Java map dump. / 逐行显示精确审阅值，而非 Java 映射转储。
     * @param page owner page / 所属页
     * @param server exact target / 精确目标
     * @param sourceSha256 source manifest digest / 来源清单摘要
     * @param archiveSha256 actual tar digests / 实际 tar 摘要
     * @param answers final edited values / 最终编辑值
     * @param databases source-derived SQLite facts / 源码派生 SQLite 事实
     * @param git original and pinned Git identity / 原及固定 Git 身份
     * @return confirmation rows / 确认行 */
    private static JPanel confirmation(DeploymentStaticPage page, ServerRef server, String sourceSha256,
            Map<String, String> archiveSha256, Map<String, DeploymentStaticWorkflow.Answers> answers,
            Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStaticWorkflow.GitReview> git) {
        var panel = new JPanel(new GridLayout(0, 1));
        panel.add(new JLabel(page.text("server") + ": " + server.id()));
        git.ifPresent(identity -> panel.add(new JLabel(page.text("gitSource") + ": " + identity.remote() + " / "
                + identity.reference() + " / " + identity.commit())));
        if (git.isPresent())
            panel.add(new JLabel(page.text("gitSaveNotice")));
        panel.add(new JLabel(page.text("sourceDigest") + ": " + sourceSha256));
        archiveSha256.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> panel.add(new JLabel(entry.getKey() + " tar SHA-256: " + entry.getValue())));
        answers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            panel.add(new JLabel(entry.getKey()));
            entry.getValue().values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
                if (!databases.containsKey(entry.getKey()) || databases.get(entry.getKey()).bindings().isEmpty()
                        || !Set.of("databaseMode", "databaseDetails").contains(value.getKey()))
                    panel.add(new JLabel("  " + label(page, value.getKey()) + ": " + value.getValue()));
            });
            var database = databases.get(entry.getKey());
            if (database != null)
                database.bindings().forEach(binding -> {
                    if (binding.connection() instanceof DeploymentDatabaseConnection.Sqlite sqlite) {
                        panel.add(new JLabel("  " + page.text("database.sqliteBinding") + ": " + binding.databaseId()
                                + " / " + sqlite.fileName() + " / " + sqlite.accessPath()));
                        panel.add(new JLabel("  " + page.text("database.seed") + ": " + sqlite.seedFile()));
                        panel.add(new JLabel("  " + page.text("database.initialize") + ": "
                                + String.join(", ", sqlite.initializationFiles())));
                    } else if (binding.connection() instanceof DeploymentDatabaseConnection.Server nativeDatabase) {
                        panel.add(new JLabel("  " + binding.databaseId() + ": " + nativeDatabase.engine() + " / "
                                + nativeDatabase.host() + ":" + nativeDatabase.port() + " / "
                                + nativeDatabase.database() + " / " + nativeDatabase.username()));
                        panel.add(new JLabel("  " + nativeDatabase.passwordReference().identifier() + ":"
                                + nativeDatabase.passwordReference().revision()));
                    }
                    panel.add(new JLabel(
                            "  " + page.text("database.planned") + ": " + database.schemaReview().isPresent()));
                });
            if (database != null) {
                database.configuration().forEach(
                        config -> panel.add(new JLabel("  " + config.key() + " = " + config.value().canonicalValue())));
                database.schemaReview().ifPresent(review -> panel.add(
                        new JLabel("  " + page.text("database.inspectionDigest") + ": " + review.inspectionSha256())));
            }
            entry.getValue().files().forEach(file -> panel.add(
                    new JLabel("  " + page.text("files") + ": " + file.bindingId() + " → " + file.dataPath().path())));
            panel.add(
                    new JLabel("  " + page.text("timeoutSeconds") + ": " + entry.getValue().limits().timeoutSeconds()));
            panel.add(new JLabel("  " + page.text("containerRisk") + ": " + entry.getValue().containerRisk()));
            panel.add(new JLabel("  " + page.text("adapterRisk") + ": " + entry.getValue().adapterRisk()));
        });
        return panel;
    }

    /** Returns a successful copied value. / 返回成功的复制值。
     * @param value observed value / 已观察值
     * @param <T> value type / 值类型
     * @return successful result / 成功结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Returns an owner-decision cancellation. / 返回所属决定取消。
     * @param <T> value type / 值类型
     * @return cancelled result / 已取消结果 */
    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
                "DEPLOYMENT_STATIC_VIEW_REVOKED", "Review was closed or locked", Map.of(), Optional.empty())));
    }

    /** Returns one safe invalid-form result. / 返回安全的无效表单结果。
     * @param <T> value type / 值类型
     * @return invalid form / 无效表单 */
    private static <T> OperationResult<T> invalid() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_STATIC_FORM_INVALID",
                        "Review fields need correction", Map.of(), Optional.empty())));
    }

    /** Creates the actual runtime controls for a capability-free fixed-data preview. / 为不持有能力的固定数据预览创建真实运行控件。
     * @param prompt explicit sample fields / 明确示例字段
     * @param environment current display only / 仅当前显示
     * @return same ordinary runtime form controls / 同一普通运行表单控件 */
    static JPanel runtimePreview(DeploymentStaticWorkflow.Prompt prompt,
            gold.debug.windowstolinux.appapi.ui.UiEnvironment environment) {
        var messages = java.util.ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page",
                environment.locale());
        return new ComponentForm(messages::getString, List.of(), prompt).panel();
    }

    /** One typed component form with bounded resource rows. / 一份带有界资源行的类型化组件表单。 */
    private static final class ComponentForm {
        /** Source-bound prompt. / 来源绑定问题。 */
        private final DeploymentStaticWorkflow.Prompt prompt;

        /** Pure localized labels without task or service capture. / 不捕获任务或服务的纯本地化标签。 */
        private final java.util.function.Function<String, String> texts;

        /** Visible controls. / 可见控件。 */
        private final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));

        /** Current field inputs. / 当前字段输入。 */
        private final Map<String, JComponent> fields = new LinkedHashMap<>();

        /** Current file rows. / 当前文件行。 */
        private final List<FileRow> files = new ArrayList<>();

        /** Build limits. / 构建限额。 */
        private final JTextField timeout = new JTextField("1800"), processes = new JTextField("1024"),
                memory = new JTextField("4096"), output = new JTextField("4194304"),
                workspace = new JTextField("4294967296");

        /** Explicit risk decisions. / 明确风险决定。 */
        private final JCheckBox containerRisk = new JCheckBox(), adapterRisk = new JCheckBox();

        /** Builds editable controls from actual source-backed values. / 从真实来源依据值构建可编辑控件。
         * @param page owner page / 所属页
         * @param prompt original prompt / 原问题 */
        private ComponentForm(DeploymentStaticPage page, DeploymentStaticWorkflow.Prompt prompt) {
            this(page::text, page.secretChoices(), prompt);
        }

        /** Builds the same controls from pure fixed or reviewed values. / 从纯固定或审阅值构建同一控件。
         * @param texts localized labels / 本地化标签
         * @param choices exact non-secret revisions / 精确非秘密修订
         * @param prompt bounded original values / 有界原值 */
        private ComponentForm(java.util.function.Function<String, String> texts,
                List<DeploymentSecretSelection> choices, DeploymentStaticWorkflow.Prompt prompt) {
            this.texts = texts;
            this.prompt = prompt;
            panel.add(new JLabel(texts.apply("application") + ": " + prompt.applicationId()));
            panel.add(new JLabel());
            for (DeploymentReviewField field : prompt.fields()) {
                String key = field.id().substring(field.id().indexOf('/') + 1);
                if (fields.containsKey(key))
                    continue;
                JComponent input = key.equals("secrets")
                        ? new DeploymentSecretReferenceField(texts, field.value(), choices)
                        : field.choices().isEmpty()
                                ? new JTextField(field.value())
                                : new JComboBox<>(field.choices().toArray(String[]::new));
                if (input instanceof JComboBox<?> box && !field.value().isBlank())
                    box.setSelectedItem(field.value());
                fields.put(key, input);
                panel.add(new JLabel(texts.apply(key)));
                panel.add(input);
            }
            add("timeoutSeconds", timeout);
            add("maxProcesses", processes);
            add("memoryMiB", memory);
            add("maxOutputBytes", output);
            add("maxWorkspaceBytes", workspace);
            panel.add(new JLabel(texts.apply("containerRisk")));
            panel.add(containerRisk);
            panel.add(new JLabel(texts.apply("adapterRisk")));
            panel.add(adapterRisk);
            var addFile = new JButton(texts.apply("addFile"));
            addFile.addActionListener(ignored -> {
                if (files.size() < 32) {
                    var row = new FileRow(panel, texts);
                    files.add(row);
                    panel.revalidate();
                }
            });
            panel.add(addFile);
            panel.add(new JLabel(texts.apply("files")));
        }

        /** Adds one field label and control. / 添加一项字段标签和控件。
         * @param key label / 标签
         * @param control text input / 文本输入 */
        private void add(String key, JTextField control) {
            panel.add(new JLabel(texts.apply(key)));
            panel.add(control);
        }

        /** Returns the actual form panel. / 返回真实表单面板。
         * @return form / 表单 */
        private JPanel panel() {
            return panel;
        }

        /** Parses completed non-secret values without retaining secret material. / 解析已补齐非秘密值，不保留秘密明文。
         * @return typed reviewed answers / 类型化审阅答案 */
        private DeploymentStaticWorkflow.Answers answer() {
            var values = new LinkedHashMap<String, String>();
            fields.forEach((key, control) -> {
                String value = control instanceof JTextField field
                        ? field.getText().trim()
                        : control instanceof DeploymentSecretReferenceField secret
                                ? secret.value()
                                : String.valueOf(((JComboBox<?>) control).getSelectedItem());
                values.put(key, value);
            });
            return new DeploymentStaticWorkflow.Answers(values, files.stream().map(FileRow::binding).toList(),
                    new DeploymentBuildLimits(Integer.parseInt(timeout.getText().trim()),
                            Integer.parseInt(processes.getText().trim()), Integer.parseInt(memory.getText().trim()),
                            Long.parseLong(output.getText().trim()), Long.parseLong(workspace.getText().trim()), false),
                    containerRisk.isSelected(), adapterRisk.isSelected());
        }
    }

    /** One explicit managed file binding row. / 一项明确受管文件绑定行。 */
    private static final class FileRow {
        /** Identity. / 标识。 */
        private final JTextField id = new JTextField();

        /** Application path. / 应用路径。 */
        private final JTextField path = new JTextField();

        /** Data compatibility schema. / 数据兼容模式。 */
        private final JTextField schema = new JTextField("data-v1");

        /** Custom location, blank means managed default. / 自定义位置，空则受管默认。 */
        private final JTextField location = new JTextField();

        /** Access mode. / 访问模式。 */
        private final JComboBox<String> access = new JComboBox<>(new String[]{"READ_WRITE", "READ_ONLY"});

        /** Adds one typed row to the form. / 向表单添加一项类型化行。
         * @param panel original component panel / 原组件面板
         * @param texts pure localized labels / 纯本地化标签 */
        private FileRow(JPanel panel, java.util.function.Function<String, String> texts) {
            for (var entry : List.<Map.Entry<String, JComponent>>of(Map.entry(texts.apply("fileId"), id),
                    Map.entry(texts.apply("filePath"), path), Map.entry(texts.apply("fileSchema"), schema),
                    Map.entry(texts.apply("fileLocation"), location), Map.entry(texts.apply("fileAccess"), access))) {
                panel.add(new JLabel(entry.getKey()));
                panel.add(entry.getValue());
            }
        }

        /** Forms a managed file identity from explicit row controls. / 由明确行控件生成受管文件身份。
         * @return reviewed file binding / 经审阅文件绑定 */
        private DeploymentFileBinding binding() {
            var mode = DeploymentStoragePath.AccessMode.valueOf((String) access.getSelectedItem());
            var data = new DeploymentStoragePath(path.getText().trim(), mode, schema.getText().trim(), true);
            var storage = location.getText().isBlank()
                    ? DeploymentStorageLocation.defaults()
                    : DeploymentStorageLocation.custom(location.getText().trim());
            return new DeploymentFileBinding(id.getText().trim(), data, storage);
        }
    }

    /** Resolves a user-facing field label from the Mod resources. / 从 Mod 资源解析面向用户的字段标签。
     * @param page owner page / 所属页
     * @param key typed field key / 类型化字段键
     * @return localized label / 本地化标签 */
    private static String label(DeploymentStaticPage page, String key) {
        return page.text(key);
    }
}
