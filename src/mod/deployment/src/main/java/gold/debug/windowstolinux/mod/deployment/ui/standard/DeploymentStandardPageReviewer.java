package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentNativeDatabaseFacts.Candidate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentNativeDatabaseFacts.Instance;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentNativeDatabaseFacts.InstanceList;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretSelection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.database.DeploymentDatabaseSourceInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewFieldException;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver;
import gold.debug.windowstolinux.mod.deployment.ui.runtime.DeploymentReviewInputs;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;

/** Translates one current owner-view decision into typed static review values. / 将当前所属视图的一次决定转换为类型化静态审阅值。 */
final class DeploymentStandardPageReviewer implements DeploymentStandardInputs.Reviewer {
    /** Owning page. / 所属页面。 */
    private final DeploymentStandardPage page;

    /** Original view generation. / 原视图代次。 */
    private final long view;

    private Map<String, Set<SourceLanguageType>> componentLanguages = Map.of();

    private Map<String, String> componentPaths = Map.of();

    private Map<String, DeploymentProjectKind> componentTypes = Map.of();

    /** Source-bound questions retained for the single final consent. / 为唯一最终同意保留的源码绑定问题。 */
    private List<DeploymentStandardInputs.Prompt> reviewPrompts = List.of();

    /** Binds one original view, not the publication task lifetime. / 绑定原视图，而非发布任务寿命。
     * @param page original page / 原页面
     * @param view captured generation / 已捕获代次 */
    DeploymentStandardPageReviewer(DeploymentStandardPage page, long view) {
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
    public OperationResult<Map<String, String>> databaseInputs(ServerId server, String componentId,
                                                               String sourceSha256, java.nio.file.Path root, String applicationId,
                                                               DatabaseProjectChecker.Report assessment, TaskContext task) throws Exception {
        if (!page.active(view)) return cancelled();
        var supplied = new LinkedHashMap<String, String>();
        DeploymentDatabaseSourceInputs.questions(assessment, applicationId, Map.of()).forEach(field -> {
            if (!field.value().isBlank()) supplied.put(field.id(), field.value());
        });
        DeploymentDatabaseSourceInputs.complete(root, applicationId, assessment, supplied);
        return success(Map.copyOf(supplied));
    }

    /** Shows every first-initialization source file before creating a planned review. / 生成计划批准前展示每份首次初始化源码文件。
     * @param server selected target / 选定目标
     * @param componentId actual component / 实际组件
     * @param sourceSha256 frozen manifest / 冻结清单
     * @param assessment completed declaration / 已补齐声明
     * @param task original task / 原任务
     * @return explicit consent or cancellation / 明确同意或取消 */
    @Override
    public OperationResult<Boolean> confirmSqliteInitialization(ServerId server, String componentId,
                                                                String sourceSha256, DatabaseProjectChecker.Report assessment, TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = databaseQuestion(page::text, page.serverDisplayName(server), page.text("database.firstPreparation"));
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> page.text("sourceDigest") + ": " + sourceSha256));
            for (var database : assessment.databases()) {
                var sqlite = database.sqlite().orElseThrow();
                technical.add(DeploymentDialogs.fact(() -> componentId + " / " + database.id() + " / " + sqlite.fileName()));
                technical.add(DeploymentDialogs.fact(() -> page.text("database.path") + ": " + sqlite.accessPath()));
                technical.add(DeploymentDialogs.fact(() -> page.text("database.seed") + ": " + sqlite.seedFile()));
                technical.add(DeploymentDialogs.fact(() ->
                    page.text("database.initialize") + ": " + String.join(", ", database.initializationFiles())));
            }
            addDetails(rows, page::text, technical);
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
    public OperationResult<String> choose(ServerId server, String componentId, DatabaseRequirement database,
                                          InstanceList inventory, TaskContext task) {
        var selected = page.onEdt(() -> {
            var box = instanceChoices(page::text, inventory.instances());
            var panel = databaseQuestion(page::text, page.serverDisplayName(server), page.text("database.chooseQuestion"));
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> componentId + " / " + database.id() + " / " + database.engine()));
            for (int index = 0; index < inventory.instances().size(); index++) {
                var instance = inventory.instances().get(index);
                String label = page.text("database.instanceChoice") + " " + (index + 1);
                technical.add(DeploymentDialogs.fact(() -> label + ": " + instance.id() + " / " + instance.version() + " / " + instance.service() + " / "
                    + (instance.running()
                    ? page.text("database.nativeRunning")
                    : page.text("database.nativeStopped"))));
            }
            panel.add(box);
            addDetails(panel, page::text, technical);
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
    public OperationResult<Boolean> install(ServerId server, DatabaseRequirement database, Candidate candidate,
                                            TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = databaseQuestion(page::text, page.serverDisplayName(server), page.text("database.installQuestion"));
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> page.text("database.nativeInstallWarning")));
            technical.add(DeploymentDialogs.fact(() -> server.id() + " / " + database.engine() + " / " + database.id()));
            technical.add(DeploymentDialogs.fact(() ->
                candidate.name() + " / " + candidate.packageVersion() + " / " + candidate.engineVersion()));
            addDetails(rows, page::text, technical);
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
    public OperationResult<Boolean> replacement(ServerId server, Instance previous, Candidate candidate,
                                                TaskContext task) {
        var form = page.onEdt(() -> new NativeReplacementForm(page, server, previous, candidate));
        while (page.active(view)) {
            if (!page.approve(view, form.panel, page.text("database.nativeReplacementTitle"), task))
                return cancelled();
            if (page.onEdt(form::confirmed))
                return success(Boolean.TRUE);
            page.onEdt(() -> {
                form.error.setText(page.text("database.nativeReplacementCheckAll"));
                return null;
            });
        }
        return cancelled();
    }

    /** Keeps three independent initially refused replacement consents on the same form. / 在同一表单保留三项独立且默认拒绝的替换同意。 */
    private static final class NativeReplacementForm {
        /** Original visible reviewed facts and decisions. / 原可见审阅事实及决定。 */
        private final JPanel panel;

        /** Backup, downtime and exact software replacement are independent decisions. / 备份、停机与精确软件替换是独立决定。 */
        private final List<JCheckBox> confirmations = new ArrayList<>();

        /** Visible feedback without accepting an incomplete decision. / 不接受不完整决定的可见反馈。 */
        private final JLabel error = new JLabel();

        /** Binds actual original instance and candidate facts without performing effects. / 绑定实际原实例及候选事实，不执行效果。
         * @param page original owner labels / 原所属标签
         * @param server admitted target / 已准入目标
         * @param previous exact original instance / 精确原实例
         * @param candidate selected replacement / 选定替换 */
        private NativeReplacementForm(DeploymentStandardPage page, ServerId server, Instance previous,
                                      Candidate candidate) {
            this(page::text, page.serverDisplayName(server), server, previous, candidate);
        }

        /** Builds the same risk controls from pure display inputs. / 从纯展示输入构建相同风险控件。
         * @param texts localized labels / 本地化标签
         * @param serverName readable target name / 可读目标名称
         * @param server exact target identity / 精确目标身份
         * @param previous observed original database / 已观察原数据库
         * @param candidate exact replacement software / 精确替换软件 */
        private NativeReplacementForm(java.util.function.Function<String, String> texts, String serverName,
                                      ServerId server, Instance previous, Candidate candidate) {
            panel = databaseQuestion(texts, serverName, texts.apply("database.nativeReplacementWarning"));
            panel.setName("deployment.database.replacement");
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> server.id() + " / " + previous.engine() + " / " + previous.id()));
            technical.add(DeploymentDialogs.fact(() -> previous.version() + " → " + candidate.engineVersion()));
            technical.add(DeploymentDialogs.fact(() -> candidate.name() + " / " + candidate.packageVersion()));
            for (String key : List.of("backup", "downtime", "software")) {
                String statement = texts.apply("database.nativeReplacement." + key);
                var check = new JCheckBox("", false);
                check.setName("database.replacement." + key);
                check.setToolTipText(statement);
                check.getAccessibleContext().setAccessibleName(statement);
                check.getAccessibleContext().setAccessibleDescription(statement);
                confirmations.add(check);
                var row = new JPanel(new java.awt.BorderLayout(8, 0));
                row.add(check, java.awt.BorderLayout.WEST);
                row.add(DeploymentDialogs.fact(() -> statement), java.awt.BorderLayout.CENTER);
                panel.add(row);
            }
            panel.add(error);
            addDetails(panel, texts, technical);
        }

        /** Requires all exact current decisions before returning consent. / 返回同意前要求当前全部精确决定。
         * @return whether all independent decisions were explicitly selected / 全部独立决定是否均已明确选择 */
        private boolean confirmed() {
            return confirmations.stream().allMatch(JCheckBox::isSelected);
        }
    }

    /** Confirms user-completed restoration after package replacement. / 软件包替换后确认用户已完成人工恢复。
     * @param server admitted server / 已准入服务器
     * @param instance newly observed instance / 新观察实例
     * @param task original task / 原任务
     * @return explicit decision / 明确决定 */
    @Override
    public OperationResult<Boolean> restored(ServerId server, Instance instance, TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = databaseQuestion(page::text, page.serverDisplayName(server), page.text("database.nativeRestoreWarning"));
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> server.id() + " / " + instance.engine() + " / " + instance.id()));
            technical.add(DeploymentDialogs.fact(() -> instance.version() + " / " + instance.service()));
            addDetails(rows, page::text, technical);
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
    public OperationResult<byte[]> administratorPassword(ServerId server, Instance instance, TaskContext task)
        throws Exception {
        try {
            return success(
                DeploymentStandardPasswordInput.request(page, view, task, page.text("database.nativeAdminTitle"),
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
    public OperationResult<byte[]> applicationPassword(ServerId server, DatabaseRequirement database, TaskContext task)
        throws Exception {
        try {
            return success(DeploymentStandardPasswordInput.request(page, view, task,
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
    public OperationResult<Boolean> existingSchema(ServerId server, String componentId, DatabaseRequirement database,
                                                   String sourceDigest, TaskContext task) {
        var panel = page.onEdt(() -> {
            var rows = databaseQuestion(page::text, page.serverDisplayName(server), page.text("database.existingChangeQuestion"));
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> server.id() + " / " + componentId + " / " + database.id() + " / " + database.engine()
                + " / " + database.database()));
            technical.add(DeploymentDialogs.fact(() -> page.text("database.inspectionDigest") + ": " + sourceDigest));
            technical.add(DeploymentDialogs.fact(() ->
                page.text("database.initialize") + ": " + String.join(", ", database.initializationFiles())));
            addDetails(rows, page::text, technical);
            return rows;
        });
        return page.approve(view, panel, page.text("database.nativeExistingSchema"), task)
            ? success(Boolean.TRUE)
            : cancelled();
    }

    /** Bounded non-secret database fields on the EDT. / EDT 上有界非秘密数据库字段。 */
    private static final class DatabaseInputForm {
        /** Source-bound form. / 来源绑定表单。 */
        private final JPanel panel = DeploymentDialogs.form();

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
        private DatabaseInputForm(DeploymentStandardPage page, String componentId, String sourceSha256,
                                  DatabaseProjectChecker.Report assessment, List<DeploymentReviewField> questions) {
            panel.add(DeploymentDialogs.fact(() -> page.text("database.completeQuestion")));
            var technical = DeploymentDialogs.form();
            technical.add(DeploymentDialogs.fact(() -> componentId + " / " + page.text("sourceDigest")));
            technical.add(DeploymentDialogs.fact(() -> sourceSha256));
            for (var database : assessment.databases()) {
                technical.add(DeploymentDialogs.fact(() -> database.id() + " / " + database.engine()));
                technical.add(DeploymentDialogs.fact(() -> database.sqlite().map(value -> value.accessPath() + " / " + value.seedFile())
                    .orElse(database.database())));
                technical.add(DeploymentDialogs.fact(() -> page.text("database.initialize")));
                technical.add(DeploymentDialogs.fact(() -> String.join(", ", database.initializationFiles())));
            }
            for (var question : questions) {
                JComponent input = question.choices().isEmpty() || question.id().endsWith(".initialize")
                    ? new JTextField(question.value())
                    : new JComboBox<>(question.choices().toArray(String[]::new));
                if (input instanceof JTextField field && question.id().endsWith(".initialize"))
                    field.setToolTipText(String.join(", ", question.choices()));
                if (input instanceof JComboBox<?> combo && !question.value().isBlank())
                    combo.setSelectedItem(question.value());
                var label = DeploymentReviewInputs.label(question.titleKey(), page::text);
                label.setLabelFor(input);
                panel.add(label);
                panel.add(input);
                fields.put(question.id(), input);
            }
            panel.add(error);
            panel.add(new JLabel());
            addDetails(panel, page::text, technical);
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

    /** Keeps the database question and readable target in the primary view. / 在主界面保留数据库问题及可读目标。
     * @param texts localized labels / 本地化标签
     * @param serverName readable target name / 可读目标名称
     * @param question plain-language impact and question / 通俗影响说明及问题
     * @return primary question panel / 主问题面板 */
    private static JPanel databaseQuestion(java.util.function.Function<String, String> texts, String serverName, String question) {
        var panel = DeploymentDialogs.form();
        panel.add(DeploymentDialogs.fact(() -> question));
        panel.add(DeploymentDialogs.facts(Map.of(texts.apply("server"), serverName)));
        return panel;
    }

    /** Preserves technical evidence behind an explicit expansion. / 将专业依据保留在明确展开之后。
     * @param panel primary question container / 主问题容器
     * @param texts localized labels / 本地化标签
     * @param technical complete original facts / 完整原事实 */
    private static void addDetails(JPanel panel, java.util.function.Function<String, String> texts, JComponent technical) {
        technical.setName("deployment.review.technical");
        technical.setVisible(false);
        var toggle = new JToggleButton(texts.apply("automatic.details"));
        toggle.setName("deployment.review.details");
        toggle.addActionListener(ignored -> {
            technical.setVisible(toggle.isSelected());
            panel.revalidate();
        });
        panel.add(toggle);
        panel.add(technical);
    }

    /** Keeps exact selection values while rendering readable ordinal choices. / 保留精确选择值并呈现可读序号选项。
     * @param texts localized labels / 本地化标签
     * @param instances authentic ordered inventory / 真实有序库存
     * @return exact-value readable selector / 保留精确值的可读选择器 */
    static JComboBox<String> instanceChoices(java.util.function.Function<String, String> texts, List<Instance> instances) {
        var values = instances.stream().map(Instance::id).toList();
        var box = new JComboBox<>(values.toArray(String[]::new));
        box.setRenderer(new DefaultListCellRenderer() {
            /** Renders a readable choice without changing its identity. / 不改变身份地呈现可读选项。
             * @param list original option list / 原选项列表
             * @param value exact instance identity / 精确实例身份
             * @param index original rendering index / 原呈现索引
             * @param selected actual selection state / 实际选择状态
             * @param focused actual focus state / 实际焦点状态
             * @return configured renderer / 已配置呈现器 */
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                int position = values.indexOf(value);
                if (position >= 0) setText(texts.apply("database.instanceChoice") + " " + (position + 1) + " · "
                    + texts.apply(instances.get(position).running() ? "database.nativeRunning" : "database.nativeStopped"));
                return this;
            }
        });
        return box;
    }

    /** Reuses actual replacement controls with harmless fixed facts. / 以无害固定事实复用实际替换控件。
     * @param texts localized labels / 本地化标签
     * @return capability-free replacement preview / 不持有能力的替换预览 */
    static JPanel databaseReplacementPreview(java.util.function.Function<String, String> texts) {
        var previous = new Instance(gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType.POSTGRESQL,
            "private-instance-id", "16.3", 5432, "postgresql.service", "/var/lib/private-database", "a".repeat(64), true);
        return new NativeReplacementForm(texts, "Development server", new ServerId("private-server-id"), previous,
            new Candidate("postgresql-package", "17.1-package-revision", "17.1")).panel;
    }

    /** Edits the actual graph inside this original frozen source. / 在本原冻结来源内编辑实际图。
     * @param server exact selected server / 精确选定服务器
     * @param sourceSha256 frozen source digest / 冻结来源摘要
     * @param discovered actual roots / 实际根
     * @param task original task / 原任务
     * @return reviewed graph selection / 经审阅图选择 */
    @Override
    public OperationResult<DeploymentStandardInputs.Selection> select(ServerId server, String sourceSha256,
                                                                      List<DiscoveredProjectPart> discovered, TaskContext task) {
        var form = page.onEdt(() -> new DeploymentComponentEditor(page::text, discovered));
        while (page.active(view)) {
            if (!page.approve(view, form.panel(), page.text("componentReview"), task))
                return cancelled();
            try {
                var selection = page.onEdt(form::selection);
                if (!DeploymentStandardWorkflow.validSelection(discovered, selection))
                    throw new IllegalArgumentException("Review choices are incomplete");
                return success(selection);
            } catch (IllegalArgumentException invalid) {
                page.onEdt(() -> {
                    form.showInvalid();
                    return null;
                });
            }
        }
        return cancelled();
    }

    @Override
    public OperationResult<DeploymentStandardInputs.Selection> select(ServerId server, String sourceSha256,
                                                                      List<DiscoveredProjectPart> discovered,
                                                                      DeploymentStandardInputs.Selection automatic,
                                                                      Map<String, Set<SourceLanguageType>> languages, TaskContext task) {
        componentLanguages = Map.copyOf(languages);
        var paths = new LinkedHashMap<String, String>();
        var types = new LinkedHashMap<String, DeploymentProjectKind>();
        discovered.forEach(part -> {
            paths.put(part.id(), part.relativeRoot().toString().isBlank() ? "." : part.relativeRoot().toString());
            types.put(part.id(), automatic.choices().get(part.id()).type());
        });
        componentPaths = Map.copyOf(paths);
        componentTypes = Map.copyOf(types);
        return page.active(view) ? success(automatic) : cancelled();
    }

    /** Validates runtime defaults and collects required risk decisions before final consent. / 最终同意前校验运行默认值并收集必要风险决定。
     * @param server exact target / 精确目标
     * @param sourceSha256 source digest / 来源摘要
     * @param prompts typed prompts / 类型化问题
     * @param task original task / 原任务
     * @return reviewed values / 经审阅值 */
    @Override
    public OperationResult<Map<String, DeploymentStandardInputs.Answers>> review(ServerId server, String sourceSha256,
                                                                                 List<DeploymentStandardInputs.Prompt> prompts, TaskContext task) {
        if (!page.active(view)) return cancelled();
        reviewPrompts = List.copyOf(prompts);
        var riskPrompts = prompts.stream().filter(prompt -> (requiresAdapterRisk(prompt)
            && !Boolean.parseBoolean(prompt.defaults().getOrDefault("adapterRisk", "false")))
            || (requiresContainerRisk(prompt) && !Boolean.parseBoolean(prompt.defaults().getOrDefault("containerRisk", "false")))).toList();
        if (!riskPrompts.isEmpty()) {
            var panel = page.onEdt(() -> {
                var rows = DeploymentDialogs.form();
                rows.add(DeploymentDialogs.fact(() -> page.text("automatic.riskNotice")));
                for (var prompt : riskPrompts) {
                    if (requiresAdapterRisk(prompt))
                        rows.add(DeploymentDialogs.fact(() -> page.text("automatic.type." + prompt.projectType()) + ": " + page.text("automatic.adapterWarning")));
                    if (requiresContainerRisk(prompt))
                        rows.add(DeploymentDialogs.fact(() -> page.text("automatic.type." + prompt.projectType()) + ": " + page.text("automatic.containerWarning")));
                }
                return rows;
            });
            if (!page.approve(view, panel, page.text("automatic.riskReview"), task)) return cancelled();
        }
        var answers = new LinkedHashMap<String, DeploymentStandardInputs.Answers>();
        for (var prompt : prompts) {
            DeploymentStandardInputs.Answers answer;
            try {
                answer = automaticAnswer(prompt);
            } catch (IllegalArgumentException invalid) {
                var failed = incompleteRuntime(invalid);
                return new OperationResult<>(failed.outcome(), Optional.empty(), failed.problem());
            }
            try {
                page.validateReview(prompt, answer);
                answers.put(prompt.componentId(), answer);
            } catch (IllegalArgumentException invalid) {
                var completed = completeRuntime(prompt, answer, invalid, task);
                if (completed.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(completed.outcome(), Optional.empty(), completed.problem());
                answers.put(prompt.componentId(), completed.value().orElseThrow());
            }
        }
        return success(Map.copyOf(answers));
    }

    /** Limits source-completion questions to runtime values, excluding existing bindings and consent. / 将来源补全问题限定为运行值，排除已有绑定及同意。
     */
    private static final Set<String> COMPLETABLE_RUNTIME = Set.of("exposure", "port", "primary", "secondary", "version", "ports",
        "jvmTarget", "healthEndpoint", "expectedStatus", "timeout", "stability");

    /** Requests only deterministically missing or exactly identified invalid runtime inputs. / 仅请求确定缺失或精确标识无效的运行输入。
     * @param prompt original source-bound component / 原源码绑定组件
     * @param invalid actual validation failure / 实际校验失败
     * @return bounded necessary questions without invented fields / 不虚构字段的有界必要问题 */
    private static List<DeploymentReviewField> completionQuestions(DeploymentStandardInputs.Prompt prompt, IllegalArgumentException invalid) {
        var fields = new LinkedHashMap<String, DeploymentReviewField>();
        try {
            new DeploymentStandardRuntimeResolver().missing(prompt.componentId(), prompt.projectType(), prompt.defaults()).stream()
                .filter(field -> COMPLETABLE_RUNTIME.contains(field.id().substring(field.id().lastIndexOf('/') + 1)))
                .forEach(field -> fields.put(field.id(), field));
        } catch (IllegalArgumentException unidentifiable) {
            return List.of();
        }
        if (invalid instanceof DeploymentReviewFieldException exact && COMPLETABLE_RUNTIME.contains(exact.field())) {
            var field = DeploymentStandardRuntimeResolver.field(prompt.componentId(), exact.field(),
                prompt.defaults().getOrDefault(exact.field(), ""), List.of());
            fields.putIfAbsent(field.id(), field);
        }
        return List.copyOf(fields.values());
    }

    /** Keeps invalid necessary inputs in the same host opening without expanding resource authority. / 将无效必要输入保留在同一宿主打开中，不扩展资源授权。
     * @param prompt original component identity and defaults / 原组件身份及默认值
     * @param original original resource and risk decisions / 原资源及风险决定
     * @param invalid authentic initial rejection / 真实首次拒绝
     * @param task authentic original callback / 真实原回调
     * @return validated bounded correction, actual failure or withdrawal / 已校验有界修正、实际失败或撤回 */
    private OperationResult<DeploymentStandardInputs.Answers> completeRuntime(DeploymentStandardInputs.Prompt prompt,
                                                                              DeploymentStandardInputs.Answers original, IllegalArgumentException invalid, TaskContext task) {
        var questions = completionQuestions(prompt, invalid);
        if (questions.isEmpty()) return incompleteRuntime(invalid);
        var form = page.onEdt(() -> new RequiredRuntimeForm(page::text, prompt, original, questions, invalid));
        var accepted = new java.util.concurrent.atomic.AtomicReference<OperationResult<DeploymentStandardInputs.Answers>>();
        Runnable validator = () -> {
            var current = form.answer();
            try {
                page.validateReview(prompt, current);
                accepted.set(success(current));
            } catch (IllegalArgumentException rejected) {
                if (rejected instanceof DeploymentReviewFieldException exact && !COMPLETABLE_RUNTIME.contains(exact.field())) {
                    accepted.set(incompleteRuntime(rejected));
                    return;
                }
                var currentPrompt = new DeploymentStandardInputs.Prompt(prompt.componentId(), prompt.applicationId(), prompt.projectType(),
                    current.values(), prompt.fields());
                form.addQuestions(completionQuestions(currentPrompt, rejected));
                form.error.setText(page.text("invalidForm"));
                form.technical.setText(rejected.getMessage());
                throw rejected;
            }
        };
        return page.approveValidated(view, form.panel, page.text("automatic.completeTitle"), task, false, validator)
            ? accepted.get() : cancelled();
    }

    /** Fails unidentifiable or protected input errors instead of inventing editable fields. / 对无法定位或受保护输入错误真实失败，不虚构可编辑字段。
     * @param invalid actual local validation failure / 实际本地校验失败
     * @return classified failure with non-secret field metadata / 带不含秘密字段元数据的分类失败 */
    private OperationResult<DeploymentStandardInputs.Answers> incompleteRuntime(IllegalArgumentException invalid) {
        var details = new LinkedHashMap<String, String>();
        details.put("failureType", invalid.getClass().getSimpleName());
        if (invalid instanceof DeploymentReviewFieldException exact) details.put("field", exact.field());
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INVALID_INPUT,
            "DEPLOYMENT_AUTOMATIC_INPUT_INCOMPLETE", page.text("automatic.incomplete"), details, Optional.empty())));
    }

    /** Holds only necessary runtime controls and immutable reviewed bindings. / 仅持有必要运行控件及不可变经审阅绑定。 */
    private static final class RequiredRuntimeForm {
        /** Primary bounded input surface. / 有界主输入界面。 */
        private final JPanel panel = DeploymentDialogs.form();

        /** Runtime controls without resource or consent fields. / 不含资源或同意字段的运行控件。 */
        private final JPanel inputs = DeploymentDialogs.form();

        /** Generic same-window correction explanation. / 同窗修正的通用说明。 */
        private final JLabel error = new JLabel();

        /** Collapsed original technical rejection. / 折叠的原专业拒绝原因。 */
        private final JTextArea technical;

        /** Exact necessary field identities and controls. / 精确必要字段身份及控件。 */
        private final Map<String, JComponent> fields = new LinkedHashMap<>();

        /** Original immutable values, resources and risk choices. / 原不可变值、资源及风险选择。 */
        private final DeploymentStandardInputs.Answers original;

        /** Current localized labels without authority. / 不持有授权的当前本地化标签。 */
        private final java.util.function.Function<String, String> texts;

        /** Builds the required input form without opening unrelated advanced fields. / 构建必要输入表单，不开放无关高级字段。
         * @param texts localized labels / 本地化标签
         * @param prompt exact source-bound identity / 精确源码绑定身份
         * @param original immutable reviewed values / 不可变经审阅值
         * @param questions necessary runtime descriptors / 必要运行描述
         * @param invalid authentic technical rejection / 真实专业拒绝原因 */
        private RequiredRuntimeForm(java.util.function.Function<String, String> texts, DeploymentStandardInputs.Prompt prompt,
                                    DeploymentStandardInputs.Answers original, List<DeploymentReviewField> questions, IllegalArgumentException invalid) {
            this.texts = texts;
            this.original = original;
            panel.setName("deployment.runtime.completion");
            error.setName("deployment.runtime.completion.error");
            panel.add(DeploymentDialogs.fact(() -> texts.apply("automatic.completePrompt")));
            panel.add(inputs);
            panel.add(error);
            technical = DeploymentDialogs.text(prompt.componentId() + " / " + prompt.applicationId() + "\n" + invalid.getMessage());
            addDetails(panel, texts, technical);
            addQuestions(questions);
        }

        /** Adds only newly established necessary questions, retaining all edited documents. / 仅加入新确定的必要问题，保留全部已编辑文档。
         * @param questions actual necessary descriptors / 实际必要描述 */
        private void addQuestions(List<DeploymentReviewField> questions) {
            for (var question : questions) {
                String key = question.id().substring(question.id().lastIndexOf('/') + 1);
                if (fields.containsKey(key)) continue;
                var input = DeploymentReviewInputs.create(question);
                if (key.equals("exposure") && input instanceof JComboBox<?> choices) {
                    var renderer = new DefaultListCellRenderer();
                    choices.setRenderer((list, value, index, selected, focused) -> renderer.getListCellRendererComponent(list,
                        "EXTERNAL".equals(value) ? texts.apply("automatic.completeExternal")
                            : "INTERNAL".equals(value) ? texts.apply("automatic.completeInternal") : "", index, selected, focused));
                }
                fields.put(key, input);
                var label = DeploymentReviewInputs.label(question.titleKey(), texts);
                label.setLabelFor(input);
                inputs.add(label);
                inputs.add(DeploymentReviewInputs.decorated(input, question, texts));
            }
            panel.revalidate();
        }

        /** Copies original binding decisions while replacing only requested runtime values. / 复制原绑定决定，仅替换所请求运行值。
         * @return same-scope current answers / 同范围当前答案 */
        private DeploymentStandardInputs.Answers answer() {
            var values = new LinkedHashMap<>(original.values());
            fields.forEach((key, input) -> values.put(key, DeploymentReviewInputs.value(input)));
            return new DeploymentStandardInputs.Answers(values, original.files(), original.limits(), original.containerRisk(),
                original.adapterRisk(), original.workspaceRequest(), original.workspaceSelection());
        }
    }

    private static final class AutomaticRuntimeForm {
        private final JPanel panel = DeploymentDialogs.form();

        private final JLabel error = new JLabel();

        private final Map<String, LaunchFields> fields = new LinkedHashMap<>();

        private final Map<String, DeploymentStandardInputs.Answers> original;

        /** Refreshes expanded exact facts when ordinary fields change. / 普通字段改变时刷新已展开的精确事实。 */
        private Runnable changed = () -> {
        };

        private AutomaticRuntimeForm(java.util.function.Function<String, String> texts,
                                     List<DeploymentStandardInputs.Prompt> prompts,
                                     Map<String, DeploymentStandardInputs.Answers> original) {
            this.original = Map.copyOf(original);
            panel.add(DeploymentDialogs.fact(() -> texts.apply("automatic.launchSummary")));
            for (var prompt : prompts) {
                var values = new LinkedHashMap<>(prompt.defaults());
                values.putAll(original.get(prompt.componentId()).values());
                var effective = new DeploymentStandardInputs.Prompt(prompt.componentId(), prompt.applicationId(),
                    prompt.projectType(), values, prompt.fields());
                var command = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.ApplicationSpec.resolve(values).command();
                String beforeCommand = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentLaunchSettings.preview(prompt.projectType(), values);
                var summary = new JPanel(new java.awt.BorderLayout());
                summary.setName("deployment.launch." + prompt.componentId() + ".summary");
                panel.add(summary);
                var form = DeploymentDialogs.form();
                var port = new JTextField(values.getOrDefault("port", ""));
                var url = new JTextField(values.getOrDefault("accessUrl", ""));
                var entry = new JTextField(command.entrypoint());
                var args = new JTextField(gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentCommandText.format(command.arguments()));
                port.setName("deployment.launch." + prompt.componentId() + ".port");
                url.setName("deployment.launch." + prompt.componentId() + ".accessUrl");
                entry.setName("deployment.launch." + prompt.componentId() + ".entrypoint");
                args.setName("deployment.launch." + prompt.componentId() + ".arguments");
                if (values.containsKey("port")) addField(form, texts.apply("port"), port);
                if (values.containsKey("accessUrl")) addField(form, texts.apply("accessUrl"), url);
                addField(form, texts.apply("commandEntrypoint"), entry);
                addField(form, texts.apply("commandArguments"), args);
                form.add(new JLabel(texts.apply("automatic.launchBeforeCommand")));
                form.add(DeploymentDialogs.text(beforeCommand));
                var afterCommand = DeploymentDialogs.text(beforeCommand);
                keepPreviewCaretLocal(afterCommand);
                afterCommand.setName("deployment.launch." + prompt.componentId() + ".preview");
                form.add(new JLabel(texts.apply("automatic.launchAfterCommand")));
                form.add(afterCommand);
                form.add(DeploymentDialogs.fact(() -> texts.apply("automatic.launchHelp")));
                form.setVisible(false);
                var toggle = new JToggleButton(texts.apply("automatic.editLaunch"));
                toggle.setName("deployment.launch." + prompt.componentId() + ".edit");
                toggle.addActionListener(ignored -> {
                    form.setVisible(toggle.isSelected());
                    panel.revalidate();
                });
                panel.add(toggle);
                panel.add(form);
                var launch = new LaunchFields(effective, port, url, entry, args);
                fields.put(prompt.componentId(), launch);
                bindPreview(texts, launch, summary, afterCommand);
            }
            panel.add(error);
        }

        private void bindPreview(java.util.function.Function<String, String> texts, LaunchFields launch,
                                 JPanel summary, JTextArea afterCommand) {
            class Preview implements javax.swing.event.DocumentListener {
                private String automaticUrl = launch.url().getText().trim();

                private boolean synchronizing;

                private void changed(javax.swing.event.DocumentEvent event) {
                    if (synchronizing) return;
                    if (event.getDocument() == launch.port().getDocument()) syncUrl();
                    error.setText("");
                    render();
                }

                private void syncUrl() {
                    if (automaticUrl.isBlank() || !launch.url().getText().trim().equals(automaticUrl)) return;
                    try {
                        int port = Integer.parseInt(launch.port().getText().trim());
                        if (port < 1 || port > 65535) return;
                        var previous = java.net.URI.create(automaticUrl);
                        String next = new java.net.URI(previous.getScheme(), previous.getUserInfo(), previous.getHost(),
                            port, previous.getPath(), previous.getQuery(), previous.getFragment()).toString();
                        synchronizing = true;
                        try {
                            automaticUrl = next;
                            if (!next.equals(launch.url().getText())) launch.url().setText(next);
                        } finally {
                            synchronizing = false;
                        }
                    } catch (IllegalArgumentException | java.net.URISyntaxException incomplete) {
                        // Incomplete edits are shown by render; no previous plan is presented as the new preview.
                    }
                }

                private void render() {
                    var input = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, panel);
                    var position = viewport != null && (input == launch.port() || input == launch.url()
                        || input == launch.entry() || input == launch.args()) ? viewport.getViewPosition() : null;
                    var anchor = position == null ? null : SwingUtilities.convertPoint(input, 0, 0, viewport.getView());
                    String preview;
                    try {
                        var edited = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentLaunchSettings.edit(
                            launch.prompt(), launch.port().getText().trim(), launch.url().getText().trim(),
                            launch.entry().getText().trim(), launch.args().getText());
                        preview = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentLaunchSettings.preview(
                            edited.projectType(), edited.defaults());
                    } catch (IllegalArgumentException incomplete) {
                        preview = texts.apply("automatic.launchPreviewInvalid");
                    }
                    if (!afterCommand.getText().equals(preview)) afterCommand.setText(preview);
                    var facts = new LinkedHashMap<String, String>();
                    if (!launch.url().getText().isBlank())
                        facts.put(texts.apply("accessUrl"), launch.url().getText().trim());
                    facts.put(texts.apply("executionMode"), texts.apply(launch.prompt().defaults().getOrDefault("executionMode", "DAEMON").equals("ON_DEMAND")
                        ? "automatic.mode.onDemand" : "automatic.mode.daemon"));
                    var updatedSummary = DeploymentDialogs.facts(facts);
                    keepPreviewCaretLocal(updatedSummary);
                    summary.removeAll();
                    summary.add(updatedSummary, java.awt.BorderLayout.CENTER);
                    panel.revalidate();
                    panel.repaint();
                    changed.run();
                    if (anchor != null) SwingUtilities.invokeLater(() -> {
                        if (!panel.isShowing() || !input.isFocusOwner()
                            || !viewport.getViewPosition().equals(position)) return;
                        var window = SwingUtilities.getWindowAncestor(panel);
                        if (window != null) window.validate();
                        int delta = SwingUtilities.convertPoint(input, 0, 0, viewport.getView()).y - anchor.y;
                        int maximum = Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height);
                        if (delta != 0) viewport.setViewPosition(new java.awt.Point(position.x,
                            Math.max(0, Math.min(maximum, position.y + delta))));
                    });
                }

                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent event) {
                    changed(event);
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent event) {
                    changed(event);
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent event) {
                    changed(event);
                }
            }
            var preview = new Preview();
            for (var control : List.of(launch.port(), launch.url(), launch.entry(), launch.args()))
                control.getDocument().addDocumentListener(preview);
            preview.render();
        }

        /** Lets only deliberately focused preview text reveal its caret. / 仅允许用户主动聚焦的预览文字显示其光标位置。 */
        private static void keepPreviewCaretLocal(java.awt.Component component) {
            if (component instanceof JTextArea text) {
                var caret = new javax.swing.text.DefaultCaret() {
                    @Override
                    protected void adjustVisibility(java.awt.Rectangle location) {
                        if (text.isFocusOwner()) super.adjustVisibility(location);
                    }
                };
                caret.setBlinkRate(text.getCaret().getBlinkRate());
                text.setCaret(caret);
            } else if (component instanceof java.awt.Container container) {
                for (var child : container.getComponents()) keepPreviewCaretLocal(child);
            }
        }

        private static void addField(JPanel form, String label, JTextField field) {
            var caption = new JLabel(label);
            caption.setLabelFor(field);
            form.add(caption);
            form.add(field);
        }

        private Map<String, DeploymentStandardInputs.Answers> answers() {
            var answers = new LinkedHashMap<String, DeploymentStandardInputs.Answers>();
            fields.forEach((id, form) -> {
                var prompt = form.prompt();
                var values = gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentLaunchSettings.edit(prompt,
                    form.port().getText().trim(), form.url().getText().trim(), form.entry().getText().trim(), form.args().getText()).defaults();
                var previous = original.get(id);
                answers.put(id, new DeploymentStandardInputs.Answers(values, previous.files(), previous.limits(),
                    previous.containerRisk(), previous.adapterRisk(), previous.workspaceRequest(), previous.workspaceSelection()));
            });
            return Map.copyOf(answers);
        }

        private record LaunchFields(DeploymentStandardInputs.Prompt prompt, JTextField port, JTextField url,
                                    JTextField entry, JTextField args) {
        }
    }

    private DeploymentStandardInputs.Answers automaticAnswer(DeploymentStandardInputs.Prompt prompt) {
        var values = prompt.defaults();
        var defaults = page.buildDefaults();
        var limits = new DeploymentBuildLimits(Integer.parseInt(values.getOrDefault("timeoutSeconds", "" + defaults.timeoutSeconds())),
            Integer.parseInt(values.getOrDefault("maxProcesses", "" + defaults.maxProcesses())),
            Integer.parseInt(values.getOrDefault("memoryMiB", "" + defaults.maxMemoryMiB())),
            Long.parseLong(values.getOrDefault("maxOutputBytes", "" + defaults.maxOutputBytes())),
            Long.parseLong(values.getOrDefault("maxWorkspaceBytes", "" + defaults.maxWorkspaceBytes())), false, defaults.hostReserveBytes());
        for (String key : List.of("containerRisk", "adapterRisk"))
            if (values.containsKey(key) && !Set.of("true", "false").contains(values.get(key)))
                throw new IllegalArgumentException("Invalid advanced boolean: " + key);
        var workspace = values.containsKey("maxWorkspaceBytes")
            ? gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(limits.maxWorkspaceBytes())
            : gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.automatic(0, 0);
        return new DeploymentStandardInputs.Answers(values, List.of(), limits,
            requiresContainerRisk(prompt) || Boolean.parseBoolean(values.getOrDefault("containerRisk", "false")),
            requiresAdapterRisk(prompt) || Boolean.parseBoolean(values.getOrDefault("adapterRisk", "false")),
            workspace, Optional.empty());
    }

    private static boolean requiresAdapterRisk(DeploymentStandardInputs.Prompt prompt) {
        return Boolean.parseBoolean(prompt.defaults().getOrDefault("requiresAdapterRisk", "false"))
            || !prompt.defaults().getOrDefault("application.companions", "").isBlank();
    }

    private static boolean requiresContainerRisk(DeploymentStandardInputs.Prompt prompt) {
        return prompt.projectType() == gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.DOCKERFILE_CONTAINER
            && prompt.defaults().getOrDefault("containerEngine", "PODMAN").equals("DOCKER");
    }

    /** Edits only four build fields while retaining the original resource decisions. / 仅编辑四个构建字段并保留原资源决定。
     * @param server original target / 原目标
     * @param sourceSha256 frozen source digest / 冻结来源摘要
     * @param prompt original component and correction fields / 原组件及纠正字段
     * @param original complete original answers / 完整原答案
     * @param task original publication task / 原发布任务
     * @return explicitly reviewed correction or cancellation / 明确审阅的纠正或取消 */
    @Override
    public OperationResult<DeploymentStandardInputs.Answers> reviewBuildCorrection(ServerId server,
                                                                                   String sourceSha256, DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers original,
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
        private final DeploymentStandardInputs.Answers original;

        /** Visible correction fields. / 可见纠正字段。 */
        private final Map<String, JComponent> fields = new LinkedHashMap<>();

        /** Current owner-view form. / 当前所属视图表单。 */
        private final JPanel panel = DeploymentDialogs.form();

        /** Validation message. / 校验提示。 */
        private final JLabel error = new JLabel();

        /** Builds exactly the original component's four correction controls. / 仅创建原组件的四个纠正控件。
         * @param page original owner view / 原所属视图
         * @param sourceSha256 frozen source / 冻结来源
         * @param prompt typed correction prompt / 类型化纠正问题
         * @param original complete original answers / 完整原答案 */
        private BuildCorrectionForm(DeploymentStandardPage page, String sourceSha256,
                                    DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers original) {
            this.original = original;
            panel.add(new JLabel(page.text("sourceDigest")));
            panel.add(DeploymentDialogs.text(sourceSha256));
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
        private DeploymentStandardInputs.Answers answer() {
            var values = new LinkedHashMap<>(original.values());
            fields.forEach((key, control) -> values.put(key,
                control instanceof JTextField text
                    ? text.getText().trim()
                    : String.valueOf(((JComboBox<?>) control).getSelectedItem())));
            return new DeploymentStandardInputs.Answers(values, original.files(), original.limits(),
                original.containerRisk(), original.adapterRisk());
        }
    }

    /** One complete editable review on the EDT. / EDT 上的一份完整可编辑审阅。 */
    private static final class ReviewForm {
        /** Tabbed review container. / 分页审阅容器。 */
        private final JPanel panel = new JPanel(new java.awt.BorderLayout(0, DeploymentPageStyle.scale(12)));

        /** Validation message. / 校验提示。 */
        private final JLabel error = new JLabel();

        /** Per-component forms. / 逐组件表单。 */
        private final Map<String, PartForm> forms = new LinkedHashMap<>();

        /** Actual component selection used for invalid-field focus. / 无效字段聚焦使用的实际组件选择。 */
        private final JTabbedPane tabs = new JTabbedPane();

        /** Current owner resources. / 当前所属资源。 */
        private final java.util.function.Function<String, String> texts;

        /** Creates source-bound component tabs. / 创建来源绑定的组件页。
         * @param page owner page / 所属页
         * @param sourceSha256 source manifest digest / 来源清单摘要
         * @param prompts actual review prompts / 实际审阅问题 */
        private ReviewForm(DeploymentStandardPage page, String sourceSha256,
                           List<DeploymentStandardInputs.Prompt> prompts) {
            this(page::text, page.secretChoices(), page.buildDefaults(), sourceSha256, prompts);
        }

        /** Shares the production review layout without task capabilities. / 无任务能力地共享生产审阅布局。
         * @param texts original localized labels / 原本地化标签
         * @param choices non-secret credential revisions / 非秘密凭据修订
         * @param defaults original build defaults / 原构建默认值
         * @param sourceSha256 frozen source digest / 冻结来源摘要
         * @param prompts actual component prompts / 实际组件问题 */
        private ReviewForm(java.util.function.Function<String, String> texts, List<DeploymentSecretSelection> choices,
                           DeploymentBuildLimits defaults, String sourceSha256, List<DeploymentStandardInputs.Prompt> prompts) {
            this.texts = texts;
            panel.putClientProperty("deployment.dialog.scrollManaged", true);
            panel.putClientProperty("deployment.dialog.preferredHeight", 700);
            for (var prompt : prompts) {
                var form = new PartForm(texts, choices, prompt, defaults);
                tabs.addTab(prompt.componentId(), DeploymentDialogs.scroll(form.panel()));
                forms.put(prompt.componentId(), form);
            }
            panel.add(DeploymentDialogs.fact(() -> texts.apply("sourceDigest") + "  " + sourceSha256), java.awt.BorderLayout.NORTH);
            panel.add(tabs, java.awt.BorderLayout.CENTER);
            panel.add(error, java.awt.BorderLayout.SOUTH);
        }

        /** Reads all edited component values. / 读取全部已编辑组件值。
         * @return reviewed values by component / 按组件的审阅值 */
        private Map<String, DeploymentStandardInputs.Answers> answers() {
            error.setText("");
            var answers = new LinkedHashMap<String, DeploymentStandardInputs.Answers>();
            forms.forEach((id, form) -> {
                try {
                    answers.put(id, form.answer());
                } catch (IllegalArgumentException invalid) {
                    problem(id, invalid);
                    throw invalid;
                }
            });
            return Map.copyOf(answers);
        }

        /** Selects the actual invalid component and exact known field without replacing any input. / 选择实际无效组件及精确已知字段，不替换任何输入。
         * @param component original component / 原组件
         * @param invalid actual parser rejection / 实际解析拒绝 */
        private void problem(String component, IllegalArgumentException invalid) {
            String key = invalid instanceof gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewFieldException field ? field.field() : "";
            var form = forms.get(component);
            tabs.setSelectedIndex(new ArrayList<>(forms.keySet()).indexOf(component));
            var control = form.fields.get(key);
            if (control == null) control = form.fields.values().stream().findFirst().orElse(null);
            panel.putClientProperty("deployment.review.focus", control);
            error.setText(texts.apply("invalidForm") + " " + component + (key.isEmpty() ? "" : " / " + texts.apply("auto.field." + key)));
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
    public OperationResult<Boolean> confirm(ServerId server, String sourceSha256, Map<String, String> archiveSha256,
                                            Map<String, DeploymentStandardInputs.Answers> answers, Map<String, DeploymentPreparedDatabase> databases,
                                            Optional<DeploymentStandardInputs.GitReview> git, TaskContext task) {
        var confirmed = confirmAnswers(server, sourceSha256, archiveSha256, answers, databases, git, task);
        return confirmed.outcome() == TaskResult.SUCCEEDED ? success(Boolean.TRUE)
            : new OperationResult<>(confirmed.outcome(), Optional.empty(), confirmed.problem());
    }

    /** Validates current launch controls in the same final review window. / 在同一最终核验窗口校验当前启动控件。
     * @param server exact target server / 精确目标服务器
     * @param sourceSha256 original frozen source digest / 原冻结来源摘要
     * @param archiveSha256 exact component archives / 精确组件归档
     * @param answers already reviewed values and bindings / 已审阅值及绑定
     * @param databases exact prepared databases / 精确已准备数据库
     * @param git original pinned Git identity / 原固定 Git 身份
     * @param task authentic original task / 真实原任务
     * @return current confirmed values or actual refusal / 当前已确认值或实际拒绝 */
    @Override
    public OperationResult<Map<String, DeploymentStandardInputs.Answers>> confirmAnswers(ServerId server, String sourceSha256,
                                                                                         Map<String, String> archiveSha256, Map<String, DeploymentStandardInputs.Answers> answers,
                                                                                         Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStandardInputs.GitReview> git, TaskContext task) {
        if (!page.active(view)) return cancelled();
        var form = reviewPrompts.isEmpty() ? null : page.onEdt(() -> new AutomaticRuntimeForm(page::text, reviewPrompts, answers));
        var panel = page.onEdt(() -> confirmation(page::text, server, sourceSha256, archiveSha256, answers, databases, git,
            componentLanguages, componentPaths, componentTypes, page.serverDisplayName(server), page.applicationDisplayName(), form));
        var edited = new java.util.concurrent.atomic.AtomicReference<Map<String, DeploymentStandardInputs.Answers>>();
        Runnable validator = () -> {
            try {
                var current = form == null ? answers : form.answers();
                for (var prompt : reviewPrompts) page.validateReview(prompt, current.get(prompt.componentId()));
                edited.set(current);
            } catch (IllegalArgumentException invalid) {
                if (form != null) form.error.setText(page.text("automatic.launchInvalid"));
                throw invalid;
            }
        };
        return page.approveValidated(view, panel, page.text("finalConfirm"), task, true, validator)
            ? success(edited.get()) : cancelled();
    }

    /** Renders complete facts using a live catalog and immutable reviewed values. / 用实时文案及不可变审阅值呈现完整事实。
     * @param texts live localized labels / 实时本地化标签
     * @param server exact selected server / 精确选定服务器
     * @param sourceSha256 original source digest / 原来源摘要
     * @param archiveSha256 actual archive digests / 实际归档摘要
     * @param answers reviewed component answers / 经审阅组件答案
     * @param databases reviewed database bindings / 经审阅数据库绑定
     * @param git frozen Git identity or absence / 冻结 Git 身份或空值
     * @param languages original component language observations / 原组件语言观察
     * @param paths original source-root paths / 原来源根路径
     * @param types original component kinds / 原组件种类
     * @param serverName readable exact target name / 可读精确目标名称
     * @param applicationName readable selected application name / 可读选定应用名称
     * @param runtime current ordinary launch controls or absence / 当前普通启动控件或空值
     * @return naturally sized complete confirmation / 自然尺寸的完整确认 */
    private static JPanel confirmation(java.util.function.Function<String, String> texts, ServerId server, String sourceSha256,
                                       Map<String, String> archiveSha256, Map<String, DeploymentStandardInputs.Answers> answers,
                                       Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStandardInputs.GitReview> git,
                                       Map<String, Set<SourceLanguageType>> languages, Map<String, String> paths,
                                       Map<String, DeploymentProjectKind> types, String serverName, String applicationName,
                                       AutomaticRuntimeForm runtime) {
        var panel = DeploymentDialogs.form();
        panel.setName("deployment.confirmation");
        panel.add(DeploymentDialogs.fact(() -> texts.apply("automatic.ready")));
        var overview = new LinkedHashMap<String, String>();
        overview.put(texts.apply("confirmation.application"), applicationName);
        overview.put(texts.apply("server"), serverName);
        var displayedLanguages = displayLanguages(languages.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()));
        if (!displayedLanguages.isEmpty())
            overview.put(texts.apply("automatic.languages"), languageNames(displayedLanguages));
        panel.add(DeploymentDialogs.facts(overview));
        answers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var values = entry.getValue().values();
            if (runtime == null) {
                var facts = new LinkedHashMap<String, String>();
                if (!values.getOrDefault("accessUrl", "").isBlank())
                    facts.put(texts.apply("accessUrl"), values.get("accessUrl"));
                facts.put(texts.apply("executionMode"), texts.apply(values.getOrDefault("executionMode", "DAEMON").equals("ON_DEMAND")
                    ? "automatic.mode.onDemand" : "automatic.mode.daemon"));
                panel.add(DeploymentDialogs.facts(facts));
            }
            if (entry.getValue().adapterRisk())
                panel.add(DeploymentDialogs.fact(() -> texts.apply("automatic.adapterWarning")));
            if (entry.getValue().containerRisk())
                panel.add(DeploymentDialogs.fact(() -> texts.apply("automatic.containerWarning")));
        });
        if (runtime != null) panel.add(runtime.panel);
        var technical = DeploymentDialogs.form();
        technical.setName("deployment.confirmation.technical");
        technical.setVisible(false);
        var toggle = new JToggleButton(texts.apply("automatic.details"));
        toggle.setName("deployment.confirmation.details");
        Runnable refreshTechnical = () -> {
            if (toggle.isSelected()) {
                technical.removeAll();
                try {
                    var current = runtime == null ? answers : runtime.answers();
                    current.keySet().stream().sorted().forEach(id -> technical.add(DeploymentDialogs.facts(
                        componentFacts(texts, id, types.get(id), paths.get(id), languages.getOrDefault(id, Set.of())))));
                    technical.add(technicalConfirmation(texts, server, sourceSha256, archiveSha256, current, databases, git));
                } catch (IllegalArgumentException invalid) {
                    technical.add(DeploymentDialogs.fact(() -> texts.apply("automatic.launchPreviewInvalid")));
                    technical.add(DeploymentDialogs.text(invalid.getMessage()));
                }
            }
        };
        if (runtime != null) runtime.changed = refreshTechnical;
        toggle.addActionListener(ignored -> {
            refreshTechnical.run();
            technical.setVisible(toggle.isSelected());
            panel.revalidate();
        });
        panel.add(toggle);
        panel.add(technical);
        return panel;
    }

    private static LinkedHashMap<String, String> languageOverview(java.util.function.Function<String, String> texts,
                                                                  Map<String, Set<SourceLanguageType>> languages) {
        var all = displayLanguages(languages.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()));
        var facts = new LinkedHashMap<String, String>();
        if (!all.isEmpty()) {
            facts.put(texts.apply("automatic.languageCount"), Integer.toString(all.size()));
            facts.put(texts.apply("automatic.languages"), languageNames(all));
        }
        return facts;
    }

    private static LinkedHashMap<String, String> componentFacts(java.util.function.Function<String, String> texts,
                                                                String id, DeploymentProjectKind type, String path,
                                                                Set<SourceLanguageType> languages) {
        var facts = new LinkedHashMap<String, String>();
        facts.put(texts.apply("automatic.componentId"), id);
        if (type != null)
            facts.put(texts.apply("automatic.componentType"), texts.apply("automatic.type." + type.name()));
        if (path != null) facts.put(texts.apply("automatic.sourcePath"), path);
        var displayed = displayLanguages(languages);
        if (!displayed.isEmpty()) facts.put(texts.apply("automatic.languages"), languageNames(displayed));
        return facts;
    }

    private static Set<SourceLanguageType> displayLanguages(Set<SourceLanguageType> languages) {
        var programming = languages.stream().filter(language -> !Set.of(SourceLanguageType.UNKNOWN, SourceLanguageType.HTML,
                SourceLanguageType.CONTAINERFILE, SourceLanguageType.SHELL).contains(language))
            .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(SourceLanguageType.class)));
        return programming.isEmpty() ? languages.stream().filter(language -> language != SourceLanguageType.UNKNOWN)
            .collect(java.util.stream.Collectors.toSet()) : programming;
    }

    private static String languageNames(Set<SourceLanguageType> languages) {
        return languages.stream().sorted().map(language -> switch (language) {
            case JAVA -> "Java";
            case JAVASCRIPT -> "JavaScript";
            case TYPESCRIPT -> "TypeScript";
            case PYTHON -> "Python";
            case GO -> "Go";
            case RUST -> "Rust";
            case CSHARP -> "C#";
            case KOTLIN -> "Kotlin";
            case RUBY -> "Ruby";
            case CPP -> "C++";
            case SCALA -> "Scala";
            case CLOJURE -> "Clojure";
            case ELIXIR -> "Elixir";
            case DART -> "Dart";
            case LUA -> "Lua";
            case PERL -> "Perl";
            case SWIFT -> "Swift";
            case SHELL -> "Shell";
            case CONTAINERFILE -> "Dockerfile";
            default -> language.name();
        }).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String storageSummary(DeploymentFileBinding file, java.util.function.Function<String, String> texts) {
        return file.bindingId() + " → " + file.dataPath().path() + " ("
            + texts.apply(file.dataPath().access() == DeploymentStoragePath.AccessMode.READ_WRITE ? "fileReadWrite" : "fileReadOnly")
            + ")" + (file.seedFile().isEmpty() ? "" : " · " + texts.apply("fileSeed") + ": " + file.seedFile());
    }

    private static JPanel technicalConfirmation(java.util.function.Function<String, String> texts, ServerId server, String sourceSha256,
                                                Map<String, String> archiveSha256, Map<String, DeploymentStandardInputs.Answers> answers,
                                                Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStandardInputs.GitReview> git) {
        var panel = DeploymentDialogs.form();
        panel.add(DeploymentDialogs.fact(() -> texts.apply("server") + ": " + server.id()));
        git.ifPresent(identity -> panel.add(DeploymentDialogs.fact(() -> texts.apply("gitSource") + ": " + identity.remote() + " / "
            + identity.reference() + " / " + identity.commit())));
        if (git.isPresent()) {
            var notice = DeploymentDialogs.fact(() -> texts.apply("gitSaveNotice"));
            notice.setName("deployment.confirmation.gitNotice");
            panel.add(notice);
        }
        panel.add(DeploymentDialogs.fact(() -> texts.apply("sourceDigest") + ": " + sourceSha256));
        archiveSha256.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> panel.add(DeploymentDialogs.fact(() -> entry.getKey() + " tar SHA-256: " + entry.getValue())));
        answers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            panel.add(DeploymentDialogs.fact(() -> entry.getKey()));
            entry.getValue().values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
                if (value.getKey().equals("requiresAdapterRisk")) return;
                if (!databases.containsKey(entry.getKey()) || databases.get(entry.getKey()).bindings().isEmpty()
                    || !Set.of("databaseMode", "databaseDetails").contains(value.getKey()))
                    panel.add(DeploymentDialogs.fact(() -> "  "
                        + (value.getKey().startsWith("application.") ? value.getKey() : texts.apply(value.getKey()))
                        + ": " + value.getValue()));
            });
            var database = databases.get(entry.getKey());
            if (database != null)
                database.bindings().forEach(binding -> {
                    if (binding.connection() instanceof DeploymentDatabaseConnection.Sqlite sqlite) {
                        panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("database.sqliteBinding") + ": " + binding.databaseId()
                            + " / " + sqlite.fileName() + " / " + sqlite.accessPath()));
                        panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("database.seed") + ": " + sqlite.seedFile()));
                        panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("database.initialize") + ": "
                            + String.join(", ", sqlite.initializationFiles())));
                    } else if (binding.connection() instanceof DeploymentDatabaseConnection.Server nativeDatabase) {
                        panel.add(DeploymentDialogs.fact(() -> "  " + binding.databaseId() + ": " + nativeDatabase.engine() + " / "
                            + nativeDatabase.host() + ":" + nativeDatabase.port() + " / "
                            + nativeDatabase.database() + " / " + nativeDatabase.username()));
                        panel.add(DeploymentDialogs.fact(() -> "  " + nativeDatabase.passwordReference().identifier() + ":"
                            + nativeDatabase.passwordReference().revision()));
                    }
                    panel.add(DeploymentDialogs.fact(() ->
                        "  " + texts.apply("database.planned") + ": " + database.schemaReview().isPresent()));
                });
            if (database != null) {
                database.configuration().forEach(
                    config -> panel.add(DeploymentDialogs.fact(() -> "  " + config.key() + " = " + config.value().canonicalValue())));
                database.schemaReview().ifPresent(review -> panel.add(
                    DeploymentDialogs.fact(() -> "  " + texts.apply("database.inspectionDigest") + ": " + review.inspectionSha256())));
            }
            entry.getValue().files().forEach(file -> panel.add(
                DeploymentDialogs.fact(() -> "  " + texts.apply("files") + ": " + storageSummary(file, texts))));
            panel.add(
                DeploymentDialogs.fact(() -> "  " + texts.apply("timeoutSeconds") + ": " + entry.getValue().limits().timeoutSeconds()));
            panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("maxWorkspaceBytes") + ": " + entry.getValue().limits().maxWorkspaceBytes()
                + " [" + entry.getValue().workspaceRequest().mode() + "]"));
            entry.getValue().workspaceSelection().ifPresent(selection -> panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("workspaceBasis") + ": " + selection.facts())));
            panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("containerRisk") + ": " + entry.getValue().containerRisk()));
            panel.add(DeploymentDialogs.fact(() -> "  " + texts.apply("adapterRisk") + ": " + entry.getValue().adapterRisk()));
        });
        return panel;
    }

    /** Returns a successful copied value. / 返回成功的复制值。
     * @param value observed value / 已观察值
     * @param <T> value type / 值类型
     * @return successful result / 成功结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Returns an owner-decision cancellation. / 返回所属决定取消。
     * @param <T> value type / 值类型
     * @return cancelled result / 已取消结果 */
    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_STATIC_VIEW_REVOKED", "Review was closed or locked", Map.of(), Optional.empty())));
    }

    /** Returns one safe invalid-form result. / 返回安全的无效表单结果。
     * @param <T> value type / 值类型
     * @return invalid form / 无效表单 */
    private static <T> OperationResult<T> invalid() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_STATIC_FORM_INVALID",
                "Review fields need correction", Map.of(), Optional.empty())));
    }

    /** Creates the actual runtime controls for a capability-free fixed-data preview. / 为不持有能力的固定数据预览创建真实运行控件。
     * @param prompt explicit sample fields / 明确示例字段
     * @param environment current display only / 仅当前显示
     * @return same ordinary runtime form controls / 同一普通运行表单控件 */
    static JPanel runtimePreview(DeploymentStandardInputs.Prompt prompt,
                                 gold.debug.windowstolinux.appapi.ui.page.UiEnvironment environment) {
        var messages = java.util.ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", environment.locale(),
            java.util.ResourceBundle.Control.getNoFallbackControl(java.util.ResourceBundle.Control.FORMAT_DEFAULT));
        var content = new ReviewForm(messages::getString, List.of(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(),
            "b".repeat(64), List.of(prompt)).panel;
        DeploymentPageStyle.refresh(content, messages);
        return content;
    }

    /** Reuses the actual final review with fixed non-secret sample values. / 以固定非秘密样例值复用实际最终审阅。
     * @param texts original localized labels / 原本地化标签
     * @return capability-free production confirmation / 无能力的生产确认 */
    static JPanel confirmationPreview(java.util.function.Function<String, String> texts) {
        var limits = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits();
        var values = Map.of("configuration", "APP_MODE=preview\nPUBLIC_URL=https://example.invalid/" + "long/".repeat(20),
            "primary", "dist", "version", "22", "port", "8080", "accessUrl", "http://preview.example.invalid:8080/",
            "executionMode", "DAEMON", "exposure", "EXTERNAL", "healthMode", "HTTP");
        var answer = new DeploymentStandardInputs.Answers(values,
            List.of(), limits, false, false);
        var answers = Map.of("preview", answer);
        var runtime = new AutomaticRuntimeForm(texts, List.of(new DeploymentStandardInputs.Prompt("preview", "preview-application",
            DeploymentProjectKind.STATIC_SITE, values, List.of())), answers);
        return confirmation(texts, new ServerId("preview-server"), "b".repeat(64), Map.of("preview", "c".repeat(64)),
            answers, Map.of(), Optional.of(new DeploymentStandardInputs.GitReview(
                "https://example.invalid/" + "source/".repeat(12), "DEFAULT:HEAD", "a".repeat(40))),
            Map.of("preview", Set.of(SourceLanguageType.TYPESCRIPT)), Map.of("preview", "frontend"),
            Map.of("preview", DeploymentProjectKind.STATIC_SITE), "Preview server", "Preview application", runtime);
    }

    /** One typed component form with bounded resource rows. / 一份带有界资源行的类型化组件表单。 */
    private static final class PartForm {
        /** Source-bound prompt. / 来源绑定问题。 */
        private final DeploymentStandardInputs.Prompt prompt;

        /** Pure localized labels without task or service capture. / 不捕获任务或服务的纯本地化标签。 */
        private final java.util.function.Function<String, String> texts;

        /** Visible controls. / 可见控件。 */
        private final JPanel panel = DeploymentDialogs.form();

        /** Current field inputs. / 当前字段输入。 */
        private final Map<String, JComponent> fields = new LinkedHashMap<>();

        /** Current file rows. / 当前文件行。 */
        private final List<FileRow> files = new ArrayList<>();

        /** Build limits. / 构建限额。 */
        private final JTextField timeout = new JTextField(), processes = new JTextField(),
            memory = new JTextField(), output = new JTextField(), workspace = new JTextField();

        private final JComboBox<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode> workspaceMode =
            new JComboBox<>(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.values());

        /** Explicit risk decisions. / 明确风险决定。 */
        private final JCheckBox containerRisk = new JCheckBox(), adapterRisk = new JCheckBox();

        /** Builds editable controls from actual source-backed values. / 从真实来源依据值构建可编辑控件。
         * @param page owner page / 所属页
         * @param prompt original prompt / 原问题 */
        private PartForm(DeploymentStandardPage page, DeploymentStandardInputs.Prompt prompt) {
            this(page::text, page.secretChoices(), prompt, page.buildDefaults());
        }

        /** Builds the same controls from pure fixed or reviewed values. / 从纯固定或审阅值构建同一控件。
         * @param texts localized labels / 本地化标签
         * @param choices exact non-secret revisions / 精确非秘密修订
         * @param prompt bounded original values / 有界原值
         * @param defaults configured initial build limits / 已配置初始构建限额 */
        private PartForm(java.util.function.Function<String, String> texts,
                         List<DeploymentSecretSelection> choices, DeploymentStandardInputs.Prompt prompt,
                         DeploymentBuildLimits defaults) {
            this.texts = texts;
            this.prompt = prompt;
            panel.setName("deployment.runtime.component." + prompt.componentId());
            timeout.setText(Integer.toString(defaults.timeoutSeconds()));
            processes.setText(Integer.toString(defaults.maxProcesses()));
            memory.setText(Integer.toString(defaults.maxMemoryMiB()));
            output.setText(Long.toString(defaults.maxOutputBytes()));
            workspace.setText(Long.toString(defaults.maxWorkspaceBytes()));
            workspace.setEnabled(false);
            workspaceMode.setName("deployment.runtime.workspaceMode");
            workspaceMode.addActionListener(ignored -> workspace.setEnabled(workspaceMode.getSelectedItem() ==
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.EXPLICIT));
            panel.add(new JLabel(texts.apply("application") + ": " + prompt.applicationId()));
            panel.add(new JLabel());
            for (DeploymentReviewField field : prompt.fields()) {
                String key = field.id().substring(field.id().indexOf('/') + 1);
                if (fields.containsKey(key))
                    continue;
                JComponent input = key.equals("secrets")
                    ? new DeploymentSecretRefField(texts, field.value(), choices)
                    : DeploymentReviewInputs.create(field);
                fields.put(key, input);
                var label = DeploymentReviewInputs.label(field.titleKey(), texts);
                label.setLabelFor(input);
                panel.add(label);
                panel.add(DeploymentReviewInputs.decorated(input, field, texts));
            }
            add("timeoutSeconds", timeout);
            add("maxProcesses", processes);
            add("memoryMiB", memory);
            add("maxOutputBytes", output);
            panel.add(new JLabel(texts.apply("workspaceMode")));
            panel.add(workspaceMode);
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
        private DeploymentStandardInputs.Answers answer() {
            var values = new LinkedHashMap<>(prompt.defaults());
            fields.forEach((key, control) -> {
                String value = control instanceof DeploymentSecretRefField secret
                    ? secret.value()
                    : DeploymentReviewInputs.value(control);
                values.put(key, value);
            });
            return new DeploymentStandardInputs.Answers(values, files.stream().map(FileRow::binding).toList(),
                new DeploymentBuildLimits(Integer.parseInt(timeout.getText().trim()),
                    Integer.parseInt(processes.getText().trim()), Integer.parseInt(memory.getText().trim()),
                    Long.parseLong(output.getText().trim()), Long.parseLong(workspace.getText().trim()), false),
                containerRisk.isSelected(), adapterRisk.isSelected(),
                workspaceMode.getSelectedItem() == gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.EXPLICIT
                    ? gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(Long.parseLong(workspace.getText().trim()))
                    : gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.automatic(0, 0), Optional.empty());
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
            DeploymentDialogs.bindReveal(panel);
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
    private static String label(DeploymentStandardPage page, String key) {
        return page.text(key);
    }
}
