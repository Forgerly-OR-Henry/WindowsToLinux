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
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
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
     * @param task original task / 原任务
     * @return current explicit confirmation / 当前明确确认 */
    @Override
    public OperationResult<Boolean> confirm(ServerRef server, String sourceSha256, Map<String, String> archiveSha256,
            Map<String, DeploymentStaticWorkflow.Answers> answers, TaskContext task) {
        var panel = page.onEdt(() -> confirmation(page, server, sourceSha256, archiveSha256, answers));
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
     * @return confirmation rows / 确认行 */
    private static JPanel confirmation(DeploymentStaticPage page, ServerRef server, String sourceSha256,
            Map<String, String> archiveSha256, Map<String, DeploymentStaticWorkflow.Answers> answers) {
        var panel = new JPanel(new GridLayout(0, 1));
        panel.add(new JLabel(page.text("server") + ": " + server.id()));
        panel.add(new JLabel(page.text("sourceDigest") + ": " + sourceSha256));
        archiveSha256.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> panel.add(new JLabel(entry.getKey() + " tar SHA-256: " + entry.getValue())));
        answers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            panel.add(new JLabel(entry.getKey()));
            entry.getValue().values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(
                    value -> panel.add(new JLabel("  " + label(page, value.getKey()) + ": " + value.getValue())));
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

    /** One typed component form with bounded resource rows. / 一份带有界资源行的类型化组件表单。 */
    private static final class ComponentForm {
        /** Source-bound prompt. / 来源绑定问题。 */
        private final DeploymentStaticWorkflow.Prompt prompt;

        /** Mod messages. / Mod 消息。 */
        private final DeploymentStaticPage page;

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
            this.page = page;
            this.prompt = prompt;
            panel.add(new JLabel(page.text("application") + ": " + prompt.applicationId()));
            panel.add(new JLabel());
            for (DeploymentReviewField field : prompt.fields()) {
                String key = field.id().substring(field.id().indexOf('/') + 1);
                if (fields.containsKey(key))
                    continue;
                JComponent input = field.choices().isEmpty()
                        ? new JTextField(field.value())
                        : new JComboBox<>(field.choices().toArray(String[]::new));
                if (input instanceof JComboBox<?> box && !field.value().isBlank())
                    box.setSelectedItem(field.value());
                fields.put(key, input);
                panel.add(new JLabel(label(page, key)));
                panel.add(input);
            }
            add("timeoutSeconds", timeout);
            add("maxProcesses", processes);
            add("memoryMiB", memory);
            add("maxOutputBytes", output);
            add("maxWorkspaceBytes", workspace);
            panel.add(new JLabel(page.text("containerRisk")));
            panel.add(containerRisk);
            panel.add(new JLabel(page.text("adapterRisk")));
            panel.add(adapterRisk);
            var addFile = new JButton(page.text("addFile"));
            addFile.addActionListener(ignored -> {
                if (files.size() < 32) {
                    var row = new FileRow(panel, page);
                    files.add(row);
                    panel.revalidate();
                }
            });
            panel.add(addFile);
            panel.add(new JLabel(page.text("files")));
        }

        /** Adds one field label and control. / 添加一项字段标签和控件。
         * @param key label / 标签
         * @param control text input / 文本输入 */
        private void add(String key, JTextField control) {
            panel.add(new JLabel(label(page, key)));
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
         * @param page owning localized page / 所属本地化页 */
        private FileRow(JPanel panel, DeploymentStaticPage page) {
            for (var entry : List.<Map.Entry<String, JComponent>>of(Map.entry(page.text("fileId"), id),
                    Map.entry(page.text("filePath"), path), Map.entry(page.text("fileSchema"), schema),
                    Map.entry(page.text("fileLocation"), location), Map.entry(page.text("fileAccess"), access))) {
                panel.add(new JLabel(entry.getKey()));
                panel.add(entry.getValue());
            }
        }

        /** Forms a managed file identity from explicit row controls. / 由明确行控件生成受管文件身份。
         * @return reviewed file binding / 经审阅文件绑定 */
        private DeploymentFileBinding binding() {
            var mode = DeploymentDataPath.AccessMode.valueOf((String) access.getSelectedItem());
            var data = new DeploymentDataPath(path.getText().trim(), mode, schema.getText().trim(), true);
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
