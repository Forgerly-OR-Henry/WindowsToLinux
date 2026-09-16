package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.shared.ai.collaboration.role.DeploymentInputRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import java.util.*;
import java.util.concurrent.CancellationException;

/** AI-first resolution with deterministic candidate verification and a grouped user fallback. / 优先使用 AI 解析，通过确定性候选验证，并在需要时集中询问用户。 */
public final class AutomaticInputCompletion {
    private final AiApplicationFacade ai;
    public AutomaticInputCompletion(AiApplicationFacade ai) { this.ai = Objects.requireNonNull(ai); }
    public Map<String, String> resolve(List<DeploymentInputField> fields, char[] master, AutomaticDeploymentInteraction interaction) {
        if (fields.isEmpty()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        try {
            var result = ai.invokeAiRole(new DeploymentInputRoleContext(fields.stream().limit(64).toList(),
                    "Suggest only a uniquely evidenced supplied candidate. Leave conflicts and unknown free text unresolved.", List.of()), master.clone());
            result.flatMap(response -> response.evidence().output()).filter(advice -> advice.decision() == AiAdviceDecision.CLEAR)
                    .ifPresent(advice -> {
                        for (String finding : advice.findings()) {
                            String[] pair = finding.split("=",2);
                            if (pair.length == 2) fields.stream().filter(field -> field.id().equals(pair[0]) && field.choices().size() == 1
                                    && field.choices().contains(pair[1])).findFirst().ifPresent(field -> values.put(field.id(),pair[1]));
                        }
                    });
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
        List<DeploymentInputField> remaining = fields.stream().filter(field -> !values.containsKey(field.id())).toList();
        if (!remaining.isEmpty()) values.putAll(ask(remaining, interaction));
        return Map.copyOf(values);
    }
    public static Map<String, String> ask(List<DeploymentInputField> fields, AutomaticDeploymentInteraction interaction) {
        if (fields.isEmpty()) return Map.of();
        Map<String, String> answered = interaction.requestInputs(fields).orElseThrow(CancellationException::new);
        Map<String, String> values = new LinkedHashMap<>();
        for (var field : fields) {
            String value = answered.get(field.id());
            if (value == null || value.length() > 4096 || !field.choices().isEmpty() && !field.choices().contains(value))
                throw new IllegalArgumentException("missing or invalid answer: " + field.id());
            values.put(field.id(),value.trim());
        }
        return Map.copyOf(values);
    }
}
