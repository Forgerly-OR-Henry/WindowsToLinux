package gold.debug.windowstolinux.app.service.deployment.automatic;

import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.collaboration.role.DeploymentInputRoleContext;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;

/**
 * AI-first resolution with deterministic candidate verification and a grouped user fallback. / 优先使用 AI 解析，通过确定性候选验证，并在需要时集中询问用户。
 */
public final class AutomaticInputCompletion {
    /**
     * Bound ai application facade collaborator for the supplied ai application facade.
     * <p>处理所提供的AI应用门面的AI应用门面协作对象。
     */
    private final AiApplicationFacade ai;
    /**
     * Validates and binds the inputs required by automatic input completion.
     * <p>校验并绑定自动输入完成所需输入。
     *
     * @param ai the supplied ai application facade / 所提供的AI应用门面
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AutomaticInputCompletion(AiApplicationFacade ai) {
        this.ai = Objects.requireNonNull(ai);
    }

    /**
     * Resolves map.
     * <p>解析映射。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return map / 映射
     */
    public Map<String, String> resolve(List<DeploymentInputField> fields, char[] master,
            AutomaticDeploymentInteraction interaction) {
        if (fields.isEmpty())
            return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        try {
            var result = ai.invokeAiRole(new DeploymentInputRoleContext(fields.stream().limit(64).toList(),
                    "Suggest only a uniquely evidenced supplied candidate. Leave conflicts and unknown free text unresolved.",
                    List.of()), master.clone());
            result.flatMap(response -> response.evidence().output())
                    .filter(advice -> advice.decision() == AiAdviceDecision.CLEAR).ifPresent(advice -> {
                        for (String finding : advice.findings()) {
                            String[] pair = finding.split("=", 2);
                            if (pair.length == 2)
                                fields.stream()
                                        .filter(field -> field.id().equals(pair[0]) && field.choices().size() == 1
                                                && field.choices().contains(pair[1]))
                                        .findFirst().ifPresent(field -> values.put(field.id(), pair[1]));
                        }
                    });
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException();
        }
        List<DeploymentInputField> remaining = fields.stream().filter(field -> !values.containsKey(field.id()))
                .toList();
        if (!remaining.isEmpty())
            values.putAll(ask(remaining, interaction));
        return Map.copyOf(values);
    }

    /**
     * Checks ask syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查请求语法及边界。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static Map<String, String> ask(List<DeploymentInputField> fields,
            AutomaticDeploymentInteraction interaction) {
        return gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentInputAnswers.ask(fields, interaction);
    }
}
