package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Fixed data opens the real dialog shell without a task or external capability. / 固定数据打开实际对话框外壳，不持有任务或外部能力。 */
public final class DeploymentDialogPreview implements ModPage {
    /** Production content families. / 生产内容类别。 */
    public enum Kind {
        /** Manual graph and health-owner review. / 手工组件图及健康所属者审阅。 */
        COMPONENTS(760, 480, true, false, "componentReview"),
        /** Ordinary runtime fields and bindings. / 普通运行字段及绑定。 */
        RUNTIME(760, 480, true, false, "runtimeReview"),
        /** Complete final publication facts. / 完整最终发布事实。 */
        CONFIRMATION(760, 480, true, false, "finalConfirm"),
        /** Retained advanced inputs. / 保留的高级输入。 */
        ADVANCED(600, 680, false, false, "style.advanced"),
        /** Explicit target-server selection. / 明确目标服务器选择。 */
        SERVER(520, 440, false, false, "flow.target.select"),
        /** Saved presentation metadata. / 已保存展示元数据。 */
        APPLICATION_EDITOR(540, 360, true, true, "edit"),
        /** Exact retained release selection. / 精确保留发布选择。 */
        APPLICATION_HISTORY(650, 260, true, true, "history"),
        /** Observed external applications. / 已观测外部应用。 */
        DISCOVERY(650, 360, false, true, "style.discovery"),
        /** Original task activity and results. / 原任务活动及结果。 */
        LOGS(780, 480, false, true, "style.results"),
        /** Original and replacement runtime association. / 原及替换运行关联。 */
        REATTACHMENT(780, 480, true, true, "reattach");

        /** Unscaled original window dimensions. / 未缩放的原窗口尺寸。 */
        private final int width, height;

        /** Original modality and owning message catalog. / 原模态性及所属文案目录。 */
        private final boolean modal, application;

        /** Original localized window title key. / 原本地化窗口标题键。 */
        private final String title;

        /** Records the existing production window contract. / 记录现有生产窗口合同。
         * @param width logical preferred width / 逻辑首选宽
         * @param height logical preferred height / 逻辑首选高
         * @param modal original modality / 原模态性
         * @param application whether application messages apply / 是否使用应用文案
         * @param title original title key / 原标题键 */
        Kind(int width, int height, boolean modal, boolean application, String title) {
            this.width = width;
            this.height = height;
            this.modal = modal;
            this.application = application;
            this.title = title;
        }

        /** Retains the production modality. / 保留生产模态性。
         * @return whether the window is modal / 窗口是否模态 */
        public boolean modal() {
            return modal;
        }
    }

    /** Selected production form family. / 所选生产表单类别。 */
    private final Kind kind;

    /** Original preview page without business capabilities. / 无业务能力的原预览页面。 */
    private final JPanel root = new JPanel(new BorderLayout());

    /** Explicit local preview entry. / 明确的本地预览入口。 */
    private final JButton open = new JButton();

    /** Current owned sample window. / 当前所属样例窗口。 */
    private JDialog dialog;

    /** Current display and lock state. / 当前显示及锁定状态。 */
    private UiEnvironment environment;

    /** Current original production catalog. / 当前原生产文案。 */
    private ResourceBundle messages;

    /** Prevents reuse after the preview is released. / 预览释放后阻止复用。 */
    private boolean closed;

    /** Selects one concrete production form. / 选择一份具体生产表单。
     * @param kind exact form / 精确表单 */
    public DeploymentDialogPreview(Kind kind) {
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    /** Creates a capability-free entry to the real production shell. / 创建无能力的真实生产外壳入口。
     * @param environment current display / 当前显示
     * @return retained preview root / 保留的预览根 */
    @Override
    public JComponent create(UiEnvironment environment) {
        root.add(open, BorderLayout.NORTH);
        open.setName("deployment.dialog.preview.open");
        open.addActionListener(event -> {
            if (closed || this.environment.locked()) return;
            if (dialog != null && dialog.isDisplayable()) {
                dialog.toFront();
                return;
            }
            var body = content(kind, this.environment);
            dialog = window(root, kind, body, messages);
            dialog.setName("deployment.dialog.preview." + kind.name().toLowerCase(Locale.ROOT));
            DeploymentDialogs.refresh(dialog, messages);
            dialog.setVisible(true);
            if (kind.modal()) dialog.dispose();
        });
        environmentChanged(environment);
        return root;
    }

    /** Refreshes an open sample without submitting decisions. / 刷新已打开样例而不提交决定。
     * @param environment current display and lock state / 当前显示及锁定状态 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        this.environment = environment;
        messages = messages(kind, environment.locale());
        open.setText(title(kind, messages));
        open.setEnabled(!closed && !environment.locked());
        DeploymentPageStyle.refresh(root, messages);
        if (dialog != null && dialog.isDisplayable()) {
            if (environment.locked()) dialog.dispose();
            else {
                dialog.setTitle(title(kind, messages));
                DeploymentDialogs.refresh(dialog, messages);
            }
        }
    }

    /** Releases only preview-owned windows and controls. / 仅释放预览所属窗口及控件。 */
    @Override
    public void close() {
        closed = true;
        if (dialog != null) dialog.dispose();
        root.removeAll();
    }

    /** Reuses production controls with immutable non-secret sample facts. / 以不可变非秘密样例事实复用生产控件。
     * @param kind actual production form family / 实际生产表单类别
     * @param environment display only / 仅显示
     * @return same production content / 同一生产内容 */
    public static JPanel content(Kind kind, UiEnvironment environment) {
        var messages = messages(kind, environment.locale());
        JPanel panel = switch (kind) {
            case COMPONENTS -> {
                var editor = new DeploymentComponentEditor(messages::getString, List.of(
                    new DiscoveredProjectPart("backend-" + "x".repeat(55), Path.of("backend"), List.of(DeploymentProjectKind.GO_SERVICE)),
                    new DiscoveredProjectPart("web", Path.of("web"), List.of(DeploymentProjectKind.NODE_SERVICE))));
                editor.showInvalid();
                yield editor.panel();
            }
            case RUNTIME -> {
                var values = new java.util.LinkedHashMap<String, String>();
                values.put("version", "1.25");
                values.put("port", "8080");
                values.put("configuration", "APP_MODE=preview\nPUBLIC_URL=https://example.invalid/very/long/path");
                var fields = values.entrySet().stream().map(entry -> new DeploymentReviewField("preview/" + entry.getKey(),
                    entry.getKey(), entry.getKey(), entry.getValue(), List.of())).toList();
                yield DeploymentStandardPageReviewer.runtimePreview(new DeploymentStandardInputs.Prompt("preview", "preview-application",
                    DeploymentProjectKind.GO_SERVICE, values, fields), environment);
            }
            case CONFIRMATION -> DeploymentStandardPageReviewer.confirmationPreview(messages::getString);
            case ADVANCED -> DeploymentPageStyle.card("runtimeDraft.title",
                new gold.debug.windowstolinux.mod.deployment.ui.runtime.DeploymentRuntimeDraftForm(messages::getString).panel());
            case SERVER -> {
                var search = new JTextField();
                search.putClientProperty("JTextField.placeholderText", messages.getString("flow.target.search"));
                var info = new gold.debug.windowstolinux.appapi.server.model.ServerInfo(
                    new gold.debug.windowstolinux.appapi.server.model.ServerId("preview-server"), "preview-server", "192.0.2.10", 22,
                    "deploy", java.util.Optional.empty(), java.util.Optional.empty());
                var list = new JList<>(new gold.debug.windowstolinux.appapi.server.model.ServerInfo[]{info});
                list.setSelectedIndex(0);
                var body = gold.debug.windowstolinux.mod.deployment.ui.flow.DeploymentFlowPage.serverPickerBody(search, list);
                var choose = new JButton(messages.getString("flow.target.use"));
                body.add(choose, BorderLayout.SOUTH);
                body.putClientProperty("deployment.dialog.fixedActions", choose);
                yield body;
            }
            case APPLICATION_EDITOR ->
                gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationForms.editor(
                    "preview-application", "WEBSITE", java.util.Optional.of("https://example.invalid/"), true, messages::getString).panel();
            case APPLICATION_HISTORY ->
                gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationForms.history(
                    new JComboBox<>(new String[]{"2026-10-24T00:00:00Z · " + "a".repeat(64), "2026-10-23T00:00:00Z · " + "b".repeat(64)}), messages::getString);
            case DISCOVERY -> {
                var scan = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, DeploymentPageStyle.scale(8), 0));
                scan.add(new JComboBox<>(new String[]{"preview-server"}));
                scan.add(new JButton(messages.getString("scan")));
                var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, DeploymentPageStyle.scale(8), 0));
                actions.add(new JButton(messages.getString("adopt")));
                actions.add(new JButton(messages.getString("reattachConfirm")));
                var issues = new JTextArea("preview: observed metadata only", 3, 36);
                issues.setEditable(false);
                issues.setLineWrap(true);
                issues.setWrapStyleWord(true);
                yield gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationForms.discovery(scan,
                    new JComboBox<>(new String[]{"preview-application · UNKNOWN"}), issues, actions);
            }
            case LOGS -> {
                var tasks = new JTextArea("preview-task · UNKNOWN · original task identity retained\n".repeat(30), 5, 60);
                tasks.setEditable(false);
                tasks.setLineWrap(true);
                tasks.setWrapStyleWord(true);
                var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, DeploymentPageStyle.scale(8), 0));
                actions.add(new JButton(messages.getString("refreshLogs")));
                actions.add(new JButton(messages.getString("cancel")));
                yield gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationForms.logs(tasks, actions);
            }
            case REATTACHMENT -> {
                var body = DeploymentDialogs.form();
                body.add(gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationForms.reattachment(() ->
                    java.text.MessageFormat.format(messages.getString("reattachReview"), "preview-registration", "SERVICE", "preview.service",
                        "a".repeat(64), "b".repeat(64), "deploy@192.0.2.10:22", "deploy@192.0.2.10:22", "preview-application", "WEBSITE", "https://example.invalid/")));
                yield body;
            }
        };
        DeploymentPageStyle.refresh(panel, messages);
        return panel;
    }

    /** Resolves the same production title. / 解析同一生产标题。
     * @param kind actual form family / 实际表单类别
     * @param messages current catalog / 当前文案
     * @return production title / 生产标题 */
    public static String title(Kind kind, ResourceBundle messages) {
        return messages.getString(kind.title);
    }

    /** Selects the production catalog for a form family. / 为表单类别选择生产文案。
     * @param kind actual form family / 实际表单类别
     * @param locale current locale / 当前语言
     * @return original catalog / 原文案 */
    public static ResourceBundle messages(Kind kind, Locale locale) {
        return ResourceBundle.getBundle(kind.application
                ? "gold.debug.windowstolinux.mod.deployment.ui.application.deployment-applications-page"
                : "gold.debug.windowstolinux.mod.deployment.ui.deployment-page", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
    }

    /** Opens the production shell with its original modality and preferred dimensions. / 按原模态性及首选尺寸打开生产外壳。
     * @param owner actual owner / 实际所属者
     * @param kind exact family / 精确类别
     * @param body reused production content / 复用生产内容
     * @param messages actual catalog / 实际文案
     * @return undecided sample window / 未答样例窗口 */
    public static JDialog window(JComponent owner, Kind kind, JPanel body, ResourceBundle messages) {
        return kind.modal
            ? DeploymentDialogs.option(owner, title(kind, messages), body,
            new JOptionPane(null, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION), kind.width, kind.height)
            : DeploymentDialogs.window(owner, title(kind, messages), body, null, kind.width, kind.height);
    }

}
