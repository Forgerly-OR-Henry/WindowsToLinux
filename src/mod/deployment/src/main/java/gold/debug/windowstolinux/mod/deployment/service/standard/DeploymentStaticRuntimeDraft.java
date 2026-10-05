package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies explicit non-secret runtime prefill after source suggestions, preserving component resource scope. / 在来源建议之后应用明确非秘密运行预填，保留组件资源范围。 */
final class DeploymentStaticRuntimeDraft {
    /** No instances. / 不创建实例。 */
    private DeploymentStaticRuntimeDraft() {
    }

    /** Keeps explicit user text ahead of source-derived defaults; author review remains mandatory. / 使明确用户文本优先于来源默认值，作者审阅仍必需。
     * @param prompt actual component prompt / 实际组件问题
     * @param draft captured non-secret text / 已抓取非秘密文本
     * @return source-bound prompt with explicit prefill / 带明确预填的来源绑定问题 */
    static DeploymentStaticWorkflow.Prompt apply(DeploymentStaticWorkflow.Prompt prompt, Map<String, String> draft) {
        var values = new LinkedHashMap<>(prompt.defaults());
        if (!DeploymentStaticWorkflow.SourceOptions.RUNTIME_FIELDS.containsAll(draft.keySet()))
            throw new IllegalArgumentException("Runtime prefill differs from this component's editable scope");
        values.putAll(draft);
        var fields = new LinkedHashMap<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField>();
        prompt.fields().forEach(field -> {
            String key = field.id().substring(field.id().lastIndexOf('/') + 1);
            fields.put(field.id(), draft.containsKey(key) ? new gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField(
                field.id(), field.titleKey(), field.helpKey(), draft.get(key), field.choices()) : field);
        });
        new gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver()
            .corrections(prompt.componentId(), values).stream()
            .filter(field -> draft.containsKey(field.id().substring(field.id().lastIndexOf('/') + 1)))
            .forEach(field -> fields.putIfAbsent(field.id(), field));
        return new DeploymentStaticWorkflow.Prompt(prompt.componentId(), prompt.applicationId(), prompt.projectType(), values, java.util.List.copyOf(fields.values()));
    }
}
