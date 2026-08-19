package gold.debug.windowstolinux.shared.ai.client;

import gold.debug.windowstolinux.shared.ai.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationEvidence;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.RoleAdviceAssessment;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionResponseParser;
import gold.debug.windowstolinux.shared.ai.parser.RoleAdviceParser;
import gold.debug.windowstolinux.shared.ai.prompt.RolePrompt;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.transport.HttpRoleChatTransport;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Invokes exactly one configured provider and retains only validated credential-free evidence. / 仅调用一个已配置提供者并只保留已验证的不含凭据证据。 */
public final class OpenAiCompatibleRoleClient {
    private final RoleChatTransport transport;
    private final Clock clock;
    private final ProviderEndpointPolicy endpointPolicy = new ProviderEndpointPolicy();
    private final ChatCompletionResponseParser envelopeParser = new ChatCompletionResponseParser();
    private final RoleAdviceParser adviceParser = new RoleAdviceParser();

    /** Creates the production HTTP client. / 创建生产 HTTP 客户端。 */
    public OpenAiCompatibleRoleClient() { this(new HttpRoleChatTransport(), Clock.systemUTC()); }

    /** Creates a testable client with one transport and clock. / 使用单个传输和时钟创建可测试客户端。 */
    public OpenAiCompatibleRoleClient(RoleChatTransport transport, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Calls the exact binding once; failures are returned without consulting another provider. / 精确调用绑定一次；失败直接返回且不咨询其他提供者。 */
    public AiRoleInvocationResult invoke(AiRoleBinding binding, char[] apiKey, AiRoleContext context) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(context, "context");
        String summary = context.redactedSummary();
        if (binding.role() != context.role()) {
            return result(binding, summary, AiInvocationStatus.INVALID_OUTPUT, Optional.empty(), "role-context-mismatch");
        }
        if (apiKey == null || apiKey.length == 0) {
            return result(binding, summary, AiInvocationStatus.UNAVAILABLE, Optional.empty(), "selected-api-key-missing");
        }
        char[] keyCopy = Arrays.copyOf(apiKey, apiKey.length);
        try {
            var endpoint = endpointPolicy.validateEndpoint(binding.endpoint());
            endpointPolicy.requireModel(binding.model());
            RoleChatResult response = transport.send(endpoint, keyCopy, RolePrompt.requestBody(binding, context));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return result(binding, summary, AiInvocationStatus.UNAVAILABLE, Optional.empty(), "selected-provider-http-rejected");
            }
            RoleAdviceAssessment advice = adviceParser.parse(envelopeParser.parse(response.body()).explanation());
            return result(binding, summary, AiInvocationStatus.VALIDATED, Optional.of(advice), "fixed-schema-validated");
        } catch (AiAnalysisException | IllegalArgumentException exception) {
            return result(binding, summary, AiInvocationStatus.INVALID_OUTPUT, Optional.empty(), "selected-provider-output-invalid");
        } catch (IOException exception) {
            return result(binding, summary, AiInvocationStatus.UNAVAILABLE, Optional.empty(), "selected-provider-unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(binding, summary, AiInvocationStatus.UNAVAILABLE, Optional.empty(), "selected-provider-interrupted");
        } finally {
            Arrays.fill(keyCopy, '\0');
        }
    }

    private AiRoleInvocationResult result(AiRoleBinding binding, String summary, AiInvocationStatus status,
                                          Optional<RoleAdviceAssessment> output, String detail) {
        return new AiRoleInvocationResult(new AiInvocationEvidence(binding.role(), binding.providerId(), binding.model(),
                summary, sha256(summary), status, output, detail, clock.instant()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
