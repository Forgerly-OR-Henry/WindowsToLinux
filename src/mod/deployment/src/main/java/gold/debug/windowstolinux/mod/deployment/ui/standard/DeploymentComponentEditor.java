package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.ui.runtime.DeploymentReviewInputs;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Edits the actual graph before source analysis, preserving all explicit component boundaries. / 源码分析前编辑实际图，保留全部明确组件边界。 */
public final class DeploymentComponentEditor {
    /** Original localized labels. / 原本地化标签。 */
    private final Function<String, String> text;

    /** Actual frozen-source discovery suggestions. / 实际冻结来源发现建议。 */
    private final List<DiscoveredProjectPart> discovered;

    /** Actual complete editable rows. / 实际完整可编辑行。 */
    private final LinkedHashMap<String, DeploymentStandardInputs.Choice> choices = new LinkedHashMap<>();

    /** Complete editor surface. / 完整编辑表面。 */
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    /** Application identity. / 应用身份。 */
    private final JTextField application = new JTextField("application", 24);

    /** Selected actual component IDs. / 所选实际组件 ID。 */
    private final DefaultListModel<String> components = new DefaultListModel<>();

    /** Explicit selection for editing or deletion. / 明确编辑或删除选择。 */
    private final JList<String> list = new JList<>(components);

    /** Actual health owner, absent in a one-component graph. / 实际健康所属者，单组件图时为空。 */
    private final JComboBox<String> healthOwner = new JComboBox<>();

    /** Real bounded component input. / 真实有界组件输入。 */
    private final ComponentForm form;

    /** Current visible validation message. / 当前可见校验提示。 */
    private final JLabel error = new JLabel();

    /** Revision invalidating every previously captured selection. / 撤销所有先前抓取选择的修订。 */
    private long revision;

    /** Initializes editable suggestions from this one frozen task. / 从本次冻结任务初始化可编辑建议。
     * @param text original labels / 原标签
     * @param discovered actual frozen-source suggestions / 实际冻结来源建议 */
    public DeploymentComponentEditor(Function<String, String> text, List<DiscoveredProjectPart> discovered) {
        this.text = text;
        this.discovered = List.copyOf(discovered);
        form = new ComponentForm(text, this::invalidate);
        panel.setName("deployment.components.editor");
        list.setName("deployment.components.list");
        application.setName("deployment.components.application");
        healthOwner.setName("deployment.components.health");
        var identity = new JPanel(new GridLayout(0, 2, 8, 8));
        identity.add(DeploymentReviewInputs.label("application", text));
        identity.add(application);
        identity.add(DeploymentReviewInputs.label("healthOwner", text));
        identity.add(healthOwner);
        panel.add(identity, BorderLayout.NORTH);
        var center = new JPanel(new BorderLayout(8, 8));
        center.add(new JScrollPane(list), BorderLayout.WEST);
        center.add(form.panel, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        var actions = new JPanel();
        var add = new JButton(text.apply("component.addUpdate"));
        add.setName("deployment.components.addUpdate");
        var remove = new JButton(text.apply("component.remove"));
        remove.setName("deployment.components.remove");
        var create = new JButton(text.apply("component.new"));
        create.setName("deployment.components.new");
        actions.add(add);
        actions.add(remove);
        actions.add(create);
        actions.add(error);
        panel.add(actions, BorderLayout.SOUTH);
        add.addActionListener(ignored -> {
            try {
                update();
            } catch (IllegalArgumentException invalid) {
                showInvalid();
            }
        });
        remove.addActionListener(ignored -> remove());
        create.addActionListener(ignored -> {
            list.clearSelection();
            form.empty();
        });
        list.addListSelectionListener(ignored -> {
            if (!list.getValueIsAdjusting() && list.getSelectedValue() != null)
                form.load(list.getSelectedValue(), choices.get(list.getSelectedValue()));
        });
        for (var component : discovered) {
            var type = component.types().stream().filter(DeploymentProjectKind::deployable).findFirst().orElse(null);
            if (type != null) choices.put(component.id(), new DeploymentStandardInputs.Choice(type, Set.of(),
                Optional.of(new DeploymentStandardInputs.ComponentInputs(relative(component), true, Optional.empty(), Optional.empty()))));
        }
        if (discovered.size() == 1) application.setText(discovered.getFirst().id());
        rebuild();
        if (!components.isEmpty()) list.setSelectedIndex(0);
        application.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            /** Invalidates a changed application identity. / 撤销变化的应用身份。
             * @param event actual text edit / 实际文本编辑 */
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                invalidate();
            }

            /** Invalidates a cleared application identity. / 撤销清空的应用身份。
             * @param event actual text edit / 实际文本编辑 */
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                invalidate();
            }

            /** Invalidates a changed document. / 撤销变化的文档。
             * @param event actual text edit / 实际文本编辑 */
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                invalidate();
            }
        });
        healthOwner.addActionListener(ignored -> invalidate());
    }

    /** Returns the actual editor; no reviewed plan is cached here. / 返回实际编辑器，此处不缓存已审阅计划。
     * @return editor panel / 编辑面板 */
    public JPanel panel() {
        return panel;
    }

    /** Displays a current graph error without dropping the editable rows. / 显示当前图错误，不丢弃可编辑行。 */
    public void showInvalid() {
        error.setText(text.apply("component.invalid"));
    }

    /** Captures all current rows for fresh real source analysis. / 抓取全部当前行供新的真实源码分析。
     * @return current immutable graph / 当前不可变图 */
    public DeploymentStandardInputs.Selection selection() {
        if (form.dirty()) update();
        var selection = new DeploymentStandardInputs.Selection(application.getText().trim(), choices,
            choices.size() == 1 ? Optional.empty() : Optional.ofNullable((String) healthOwner.getSelectedItem()));
        if (!DeploymentStandardWorkflow.validSelection(discovered, selection))
            throw new IllegalArgumentException("Manual component graph is invalid");
        panel.putClientProperty("deployment.components.reviewedRevision", revision);
        return selection;
    }

    /** Applies an actual bounded row and invalidates the old review revision. / 应用实际有界行并撤销旧审阅修订。 */
    private void update() {
        try {
            var id = form.id.getText().trim();
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}") || choices.size() >= 16 && !choices.containsKey(id))
                throw new IllegalArgumentException("Invalid component ID or graph limit");
            String old = list.getSelectedValue();
            if (old != null && !old.equals(id) && choices.containsKey(id))
                throw new IllegalArgumentException("Component ID already exists");
            var next = form.choice();
            if (old != null && !old.equals(id)) choices.remove(old);
            choices.put(id, next);
            invalidate();
            rebuild();
            list.setSelectedValue(id, true);
            form.load(id, next);
        } catch (IllegalArgumentException invalid) {
            error.setText(text.apply("component.invalid"));
            throw invalid;
        }
    }

    /** Removes the selected actual component, preserving dangling edges for explicit correction. / 删除所选实际组件，保留悬空边供明确纠正。 */
    private void remove() {
        String selected = list.getSelectedValue();
        if (selected == null) return;
        choices.remove(selected);
        invalidate();
        rebuild();
        form.empty();
    }

    /** Rebuilds only component choices, retaining the explicit health ID when still present. / 仅重建组件选项，保留仍存在的明确健康 ID。 */
    private void rebuild() {
        Object health = healthOwner.getSelectedItem();
        components.clear();
        healthOwner.removeAllItems();
        choices.keySet().forEach(id -> {
            components.addElement(id);
            healthOwner.addItem(id);
        });
        healthOwner.setSelectedItem(health != null && choices.containsKey(health) ? health : null);
        healthOwner.setEnabled(choices.size() > 1);
    }

    /** Invalidates captured review data before another analysis can use the graph. / 另一分析使用图前撤销已抓取审阅数据。 */
    private void invalidate() {
        revision++;
        panel.putClientProperty("deployment.components.draftRevision", revision);
        panel.putClientProperty("deployment.components.reviewedRevision", null);
        error.setText("");
    }

    /** Normalizes a discovered relative source root without reading live files. / 不读取活动文件地规范发现相对来源根。
     * @param component actual suggestion / 实际建议
     * @return canonical relative notation / 规范相对记法 */
    private static String relative(DiscoveredProjectPart component) {
        String root = component.relativeRoot().toString().replace('\\', '/');
        return root.isEmpty() ? "." : root;
    }

    /** Retains a complete edit row without converting blank automatic fields into explicit empty values. / 保留完整编辑行，不把空自动字段转换成明确空值。 */
    private static final class ComponentForm {
        /** Real input rows. / 真实输入行。 */
        private final JPanel panel = DeploymentPageStyle.form();

        /** Explicit ID and relative root. / 明确 ID 及相对根。 */
        private final JTextField id = new JTextField(20), root = new JTextField(".", 20);

        /** Actual supported selected kind. / 实际受支持所选类型。 */
        private final JComboBox<DeploymentProjectKind> type = new JComboBox<>(Arrays.stream(DeploymentProjectKind.values()).filter(DeploymentProjectKind::deployable).toArray(DeploymentProjectKind[]::new));

        /** Whole application requirement. / 整体应用必需性。 */
        private final JCheckBox required = new JCheckBox();

        /** Automatic suggestions are explicitly distinct from empty manual declarations. / 自动建议明确区别于空手工声明。 */
        private final JCheckBox autoArtifacts = new JCheckBox(), autoPorts = new JCheckBox();

        /** Actual explicit artifacts, ports and edges. / 实际明确产物、端口及边。 */
        private final JTextField artifacts = new JTextField(24), ports = new JTextField(24), depends = new JTextField(24);

        /** Last installed original row, used only to detect pending edits. / 最近安装原行，仅用于识别待应用编辑。 */
        private String loadedId;

        /** Last original complete choice. / 最近原完整选择。 */
        private DeploymentStandardInputs.Choice loaded;

        /** Creates actual bounded controls. / 创建实际有界控件。
         * @param text localized labels / 本地化标签
         * @param changed invalidates the old reviewed graph before a field edit / 字段编辑前撤销旧审阅图 */
        private ComponentForm(Function<String, String> text, Runnable changed) {
            id.setName("deployment.component.id");
            root.setName("deployment.component.root");
            type.setName("deployment.component.type");
            type.setRenderer((list, value, index, selected, focus) -> new JLabel(value == null ? "" : text.apply("type." + value.name())));
            required.setName("deployment.component.required");
            artifacts.setName("deployment.component.artifacts");
            ports.setName("deployment.component.ports");
            depends.setName("deployment.component.depends");
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("component.id", text), id);
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("component.root", text), root);
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("component.type", text), type);
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("component.required", text), required);
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("component.artifacts", text), artifacts);
            autoArtifacts.setName("deployment.component.autoArtifacts");
            autoArtifacts.setText(text.apply("component.autoArtifacts"));
            panel.add(autoArtifacts);
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("component.ports", text), ports);
            autoPorts.setName("deployment.component.autoPorts");
            autoPorts.setText(text.apply("component.autoPorts"));
            panel.add(autoPorts);
            DeploymentPageStyle.field(panel, DeploymentReviewInputs.label("dependencies", text), depends);
            autoArtifacts.addActionListener(ignored -> artifacts.setEnabled(!autoArtifacts.isSelected()));
            autoPorts.addActionListener(ignored -> ports.setEnabled(!autoPorts.isSelected()));
            empty();
            for (var input : List.of(id, root, artifacts, ports, depends))
                input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    /** Invalidates a previously reviewed graph before accepting new input. / 接受新输入前撤销先前审阅图。
                     * @param event actual edit / 实际编辑 */
                    @Override
                    public void insertUpdate(javax.swing.event.DocumentEvent event) {
                        changed.run();
                    }

                    /** Invalidates a previously reviewed graph before accepting removed input. / 接受删除输入前撤销先前审阅图。
                     * @param event actual edit / 实际编辑 */
                    @Override
                    public void removeUpdate(javax.swing.event.DocumentEvent event) {
                        changed.run();
                    }

                    /** Invalidates a previously reviewed graph before accepting changed input. / 接受变更输入前撤销先前审阅图。
                     * @param event actual edit / 实际编辑 */
                    @Override
                    public void changedUpdate(javax.swing.event.DocumentEvent event) {
                        changed.run();
                    }
                });
            type.addActionListener(ignored -> changed.run());
            required.addActionListener(ignored -> changed.run());
            autoArtifacts.addActionListener(ignored -> changed.run());
            autoPorts.addActionListener(ignored -> changed.run());
        }

        /** Loads a complete actual selected row. / 加载完整实际所选行。
         * @param identifier exact component / 精确组件
         * @param choice complete selected row / 完整所选行 */
        private void load(String identifier, DeploymentStandardInputs.Choice choice) {
            loadedId = identifier;
            loaded = choice;
            id.setText(identifier);
            type.setSelectedItem(choice.type());
            var values = choice.inputs().orElseThrow();
            root.setText(values.relativeRoot());
            required.setSelected(values.required());
            autoArtifacts.setSelected(values.artifactPaths().isEmpty());
            autoPorts.setSelected(values.ports().isEmpty());
            artifacts.setText(values.artifactPaths().map(paths -> String.join(",", paths)).orElse(""));
            ports.setText(values.ports().map(ports -> ports.stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(","))).orElse(""));
            depends.setText(String.join(",", choice.dependencies().stream().sorted().toList()));
            artifacts.setEnabled(!autoArtifacts.isSelected());
            ports.setEnabled(!autoPorts.isSelected());
        }

        /** Clears only the edit row, never another component. / 仅清空编辑行，不清空其他组件。 */
        private void empty() {
            loadedId = null;
            loaded = null;
            id.setText("");
            root.setText(".");
            required.setSelected(true);
            artifacts.setText("");
            ports.setText("");
            depends.setText("");
            autoArtifacts.setSelected(true);
            autoPorts.setSelected(true);
            artifacts.setEnabled(false);
            ports.setEnabled(false);
        }

        /** Detects unsaved values before graph confirmation. / 整图确认前识别未保存值。
         * @return whether the current row needs applying / 当前行是否需应用 */
        private boolean dirty() {
            if (id.getText().isBlank()) return false;
            return loaded == null || !id.getText().trim().equals(loadedId) || !choice().equals(loaded);
        }

        /** Captures all explicit values without derived overwrites. / 抓取全部明确值，不以派生值覆盖。
         * @return actual full choice / 实际完整选择 */
        private DeploymentStandardInputs.Choice choice() {
            var edges = Set.copyOf(tokens(depends.getText()));
            var values = new DeploymentStandardInputs.ComponentInputs(root.getText(), required.isSelected(),
                autoArtifacts.isSelected() ? Optional.empty() : Optional.of(tokens(artifacts.getText())),
                autoPorts.isSelected() ? Optional.empty() : Optional.of(tokens(ports.getText()).stream().map(Integer::parseInt).collect(java.util.stream.Collectors.toSet())));
            return new DeploymentStandardInputs.Choice((DeploymentProjectKind) type.getSelectedItem(), edges, Optional.of(values));
        }

        /** Parses a bounded explicit list without dropping literal path identity. / 解析有界明确列表，不丢失字面路径身份。
         * @param value actual text / 实际文本
         * @return current tokens / 当前项 */
        private static List<String> tokens(String value) {
            return Arrays.stream(value.trim().split("[,;\\r\\n]+")).map(String::trim).filter(token -> !token.isEmpty()).toList();
        }
    }
}
