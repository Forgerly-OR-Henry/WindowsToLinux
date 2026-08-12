package gold.debug.windowstolinux.shared.ai.collaboration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Credential-free evidence for one explicitly selected role invocation. / 一次显式选择角色调用的不含凭据证据。 */
public record AiInvocationEvidence(
        AiCollaborationRole role,
        String providerId,
        String model,
        String redactedInputSummary,
        String inputSha256,
        AiInvocationStatus status,
        Optional<RoleAdvice> output,
        String validationDetail,
        Instant observedAt
) {
    /** Validates the auditable evidence without retaining provider response bodies. / 验证可审计证据且不保留提供者响应正文。 */
    public AiInvocationEvidence {
        role = Objects.requireNonNull(role, "role");
        providerId = bounded(providerId, "providerId", 64);
        model = bounded(model, "model", 128);
        redactedInputSummary = bounded(redactedInputSummary, "redactedInputSummary", 8_192);
        inputSha256 = Objects.requireNonNull(inputSha256, "inputSha256");
        if (!inputSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("inputSha256 is invalid");
        status = Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
        validationDetail = bounded(validationDetail, "validationDetail", 256);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if ((status == AiInvocationStatus.VALIDATED) != output.isPresent()) {
            throw new IllegalArgumentException("validated status and output must agree");
        }
    }

    private static String bounded(String value, String name, int maximum) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
