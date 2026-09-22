package gold.debug.windowstolinux.shared.standard.deploy.input;

import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;

/** Validates grouped human answers without model access. / 不访问模型地验证分组人工回答。 */
public final class DeploymentInputAnswers {
    /** Prevents construction. / 禁止实例化。 */
    private DeploymentInputAnswers() {
    }

    /** Asks once and validates every declared field. / 一次询问并验证每个声明字段。
     * @param fields declared fields / 已声明字段
     * @param interaction caller-owned interaction / 调用方持有的交互
     * @return complete checked answers / 完整已检查回答
     */
    public static Map<String, String> ask(List<DeploymentInputField> fields,
            AutomaticDeploymentInteraction interaction) {
        if (fields.isEmpty())
            return Map.of();
        var answered = interaction.requestInputs(fields).orElseThrow(CancellationException::new);
        var values = new LinkedHashMap<String, String>();
        for (var field : fields) {
            String value = answered.get(field.id());
            int limit = field.id().equals("applicationDeclaration") || field.id().endsWith("/applicationDeclaration")
                    ? 65536
                    : 4096;
            if (value == null || value.length() > limit
                    || !field.choices().isEmpty() && !field.choices().contains(value))
                throw new IllegalArgumentException("missing or invalid answer: " + field.id());
            values.put(field.id(), value.trim());
        }
        return Map.copyOf(values);
    }
}
