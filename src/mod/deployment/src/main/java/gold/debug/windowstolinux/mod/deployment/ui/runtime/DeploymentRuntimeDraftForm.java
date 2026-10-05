package gold.debug.windowstolinux.mod.deployment.ui.runtime;

import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;

/** Retains explicit non-secret pre-start inputs; source suggestions only fill untouched values. / 保留明确非秘密启动前输入，来源建议仅补未编辑值。 */
public final class DeploymentRuntimeDraftForm {
    /** Actual localized resources. / 实际本地化资源。 */
    private final Function<String, String> text;

    /** Real advanced form. / 真实高级表单。 */
    private final JPanel panel = DeploymentPageStyle.form();

    /** Original retained controls. / 原保留控件。 */
    private final Map<String, JComponent> fields = new LinkedHashMap<>();

    /** Labels independent of the retained values. / 与保留值独立的标签。 */
    private final Map<String, JLabel> labels = new LinkedHashMap<>();

    /** Creates editable values without tasks, source reads or default consent. / 创建可编辑值，不产生任务、来源读取或默认同意。
     * @param text original resources / 原资源 */
    public DeploymentRuntimeDraftForm(Function<String, String> text) {
        this.text = text;
        panel.setName("deployment.runtimeDraft");
        for (String key : gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow.SourceOptions.RUNTIME_FIELDS) {
            var field = descriptor(key);
            var input = DeploymentReviewInputs.create(field);
            var label = DeploymentReviewInputs.label(key, text);
            label.setLabelFor(input);
            fields.put(key, input);
            labels.put(key, label);
            DeploymentPageStyle.field(panel, label, DeploymentReviewInputs.decorated(input, field, text));
        }
    }

    /** Returns the actual advanced form. / 返回实际高级表单。
     * @return retained panel / 保留面板 */
    public JPanel panel() {
        return panel;
    }

    /** Captures only explicit nonblank values for the original task. / 仅为原任务抓取明确非空值。
     * @return bounded non-secret prefill / 有界非秘密预填 */
    public Map<String, String> values() {
        var result = new LinkedHashMap<String, String>();
        fields.forEach((key, field) -> {
            String value = DeploymentReviewInputs.value(field);
            if (!value.isBlank()) result.put(key, value);
        });
        return Map.copyOf(result);
    }

    /** Disables edits after the original task captures the draft. / 原任务抓取草稿后禁用编辑。
     * @param enabled actual owner availability / 实际所属可用性 */
    public void enabled(boolean enabled) {
        fields.values().forEach(field -> field.setEnabled(enabled));
    }

    /** Updates labels without replacing text or choices. / 更新标签，不替换文本或选项。 */
    public void localize() {
        labels.forEach((key, label) -> {
            label.setText(text.apply(key));
            DeploymentReviewInputs.localize(fields.get(key), descriptor(key), text);
        });
    }

    /** Retains actual supported choices instead of treating a label as a runtime value. / 保留实际受支持选项，不把标签当运行值。
     * @param key original field identity / 原字段身份
     * @return exact editable contract / 精确可编辑合同 */
    private static gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField descriptor(String key) {
        var choices = switch (key) {
            case "healthMode" -> java.util.List.of("HTTP", "TCP", "UDP", "PROCESS", "COMMAND");
            case "containerEngine" -> java.util.List.of("PODMAN", "DOCKER");
            default -> java.util.List.<String>of();
        };
        return DeploymentStaticRuntimeResolver.field("draft", key, "", choices);
    }
}
