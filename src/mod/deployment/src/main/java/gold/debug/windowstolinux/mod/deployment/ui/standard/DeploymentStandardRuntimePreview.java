package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.List;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Reuses ordinary runtime controls with fixed values and no task, database, SSH or model capability. / 以固定值复用普通运行控件，不持有任务、数据库、SSH或模型能力。 */
public final class DeploymentStandardRuntimePreview implements ModPage {
    /** Explicit sample project type. / 明确示例项目类型。 */
    private final DeploymentProjectKind kind;

    /** Same owned local surface across appearance updates. / 外观更新间保持同一所属本地界面。 */
    private final JPanel root = new JPanel(new BorderLayout());

    /** Prevents displaying a released author instance. / 阻止显示已释放作者实例。 */
    private boolean closed;

    /** Captures only an immutable sample kind. / 仅捕获不可变示例类型。
     * @param kind explicit sample kind / 明确示例类型 */
    public DeploymentStandardRuntimePreview(DeploymentProjectKind kind) {
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    /** Creates the exact ordinary fields without admitting a task. / 不接纳任务地创建精确普通字段。
     * @param environment display only / 仅显示
     * @return owned pure controls / 所属纯控件 */
    @Override
    public JComponent create(UiEnvironment environment) {
        if (!SwingUtilities.isEventDispatchThread() || closed)
            throw new IllegalStateException("Preview requires an active EDT view");
        environmentChanged(environment);
        return root;
    }

    /** Rebuilds fixed sample display without reading source or runtime state. / 不读取来源或运行状态地重建固定示例显示。
     * @param environment original author display / 原作者显示 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        if (closed)
            return;
        var values = new LinkedHashMap<String, String>();
        values.put("version", kind == DeploymentProjectKind.NODE_SERVICE ? "22" : "1.25");
        values.put("port", "8080");
        values.put("health", "TCP");
        values.put("healthPort", "8080");
        values.put("accessUrl", "https://example.invalid/preview");
        values.put("configuration", "APP_MODE=preview");
        values.put("secrets", "");
        var fields = values.entrySet().stream().map(entry -> new DeploymentReviewField("preview/" + entry.getKey(),
            entry.getKey(), entry.getKey(), entry.getValue(), List.of())).toList();
        var prompt = new DeploymentStandardInputs.Prompt("preview", "preview-application", kind, values, fields);
        root.removeAll();
        int padding = DeploymentPageStyle.scale(20);
        root.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        root.add(DeploymentStandardPageReviewer.runtimePreview(prompt, environment));
        root.revalidate();
        root.repaint();
    }

    /** Releases only local preview controls. / 仅释放本地预览控件。 */
    @Override
    public void close() {
        closed = true;
        root.removeAll();
    }
}
