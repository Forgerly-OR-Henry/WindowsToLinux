package gold.debug.windowstolinux.shared.ai.client;

import gold.debug.windowstolinux.shared.ai.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.AiAnalysisFailureType;
import gold.debug.windowstolinux.shared.ai.AiStructuralAssessment;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionResponseParser;
import gold.debug.windowstolinux.shared.ai.generation.prompt.StructuralAnalysisPrompt;
import gold.debug.windowstolinux.shared.ai.generation.prompt.AiResponseLanguageType;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.redaction.RedactedDeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.io.IOException;
import java.net.URI;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatTransport;
import gold.debug.windowstolinux.shared.ai.transport.HttpRoleChatTransport;
import java.util.Arrays;
import java.util.Objects;

/**
 * Calls an OpenAI-compatible endpoint with redacted deterministic facts only.
 *
 * <p>仅使用已脱敏的确定性事实调用 OpenAI 兼容端点。
 */
public final class OpenAiCompatibleStructuralAnalysisClient {
    private final RoleChatTransport transport;
    private final ProviderEndpointPolicy endpointPolicy;
    private final ChatCompletionResponseParser responseParser;

    /**
     * Creates a {@code OpenAiCompatibleStructuralAnalysisClient} instance.
     *
     * <p>创建 {@code OpenAiCompatibleStructuralAnalysisClient} 实例。
     */
    public OpenAiCompatibleStructuralAnalysisClient() {
        this(new HttpRoleChatTransport());
    }

    OpenAiCompatibleStructuralAnalysisClient(RoleChatTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpointPolicy = new ProviderEndpointPolicy();
        this.responseParser = new ChatCompletionResponseParser();
    }

    /**
     * Performs the {@code analyze} operation.
     *
     * <p>执行 {@code analyze} 操作。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param model the {@code model} value / {@code model} 值
     * @param apiKey the {@code apiKey} value / {@code apiKey} 值
     * @param facts the {@code facts} value / {@code facts} 值
     * @param responseLanguage the {@code responseLanguage} value / {@code responseLanguage} 值
     * @return the operation result / 操作结果
     * @throws AiAnalysisException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    /** Analyzes selected typed deployment facts without transmitting a source path or source contents. / 在不传输源码路径或内容的情况下分析选定类型化部署事实。 */
    public AiStructuralAssessment analyze(URI endpoint, String model, char[] apiKey, DeploymentProjectFacts facts,
                                        AiResponseLanguageType responseLanguage)
            throws AiAnalysisException {
        endpoint = endpointPolicy.validateEndpoint(endpoint);
        model = endpointPolicy.requireModel(model);
        return send(endpoint, apiKey, StructuralAnalysisPrompt.requestBody(model,
                RedactedDeploymentProjectFacts.from(facts), responseLanguage));
    }

    private AiStructuralAssessment send(URI endpoint, char[] apiKey, String requestBody)
            throws AiAnalysisException {
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.length == 0) {
            throw AiAnalysisException.create(AiAnalysisFailureType.API_KEY_MISSING, "AI API key must not be empty");
        }
        char[] keyCopy = Arrays.copyOf(apiKey, apiKey.length);
        try {
            var response = transport.send(endpoint, keyCopy, requestBody);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw AiAnalysisException.create(AiAnalysisFailureType.HTTP_REJECTED,
                        java.util.Map.of("status", response.statusCode()),
                        "AI service rejected the request with a non-success HTTP status", null);
            }
            return responseParser.parse(response.body());
        } catch (IOException exception) {
            throw AiAnalysisException.create(AiAnalysisFailureType.SERVICE_UNAVAILABLE,
                    "AI service is unavailable; deterministic analysis results were preserved", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AiAnalysisException.create(AiAnalysisFailureType.REQUEST_INTERRUPTED,
                    "AI explanation request was interrupted; deterministic analysis results were preserved",
                    exception);
        } finally {
            Arrays.fill(keyCopy, '\0');
        }
    }
}
