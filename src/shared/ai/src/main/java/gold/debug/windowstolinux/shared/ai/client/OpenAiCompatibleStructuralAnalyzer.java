package gold.debug.windowstolinux.shared.ai.client;

import gold.debug.windowstolinux.shared.ai.parser.AiStructuralAnalysis;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionsResponseParser;
import gold.debug.windowstolinux.shared.ai.prompt.StructuralAnalysisPrompt;
import gold.debug.windowstolinux.shared.ai.prompt.AiResponseLanguage;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.redaction.RedactedDeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * Calls an OpenAI-compatible endpoint with redacted deterministic facts only.
 *
 * <p>仅使用已脱敏的确定性事实调用 OpenAI 兼容端点。
 */
public final class OpenAiCompatibleStructuralAnalyzer {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient httpClient;
    private final ProviderEndpointPolicy endpointPolicy;
    private final ChatCompletionsResponseParser responseParser;

    /**
     * Creates a {@code OpenAiCompatibleStructuralAnalyzer} instance.
     *
     * <p>创建 {@code OpenAiCompatibleStructuralAnalyzer} 实例。
     */
    public OpenAiCompatibleStructuralAnalyzer() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenAiCompatibleStructuralAnalyzer(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpointPolicy = new ProviderEndpointPolicy();
        this.responseParser = new ChatCompletionsResponseParser();
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
    public AiStructuralAnalysis analyze(URI endpoint, String model, char[] apiKey, DeploymentProjectFacts facts,
                                        AiResponseLanguage responseLanguage)
            throws AiAnalysisException {
        endpoint = endpointPolicy.validateEndpoint(endpoint);
        model = endpointPolicy.requireModel(model);
        return send(endpoint, apiKey, StructuralAnalysisPrompt.requestBody(model,
                RedactedDeploymentProjectFacts.from(facts), responseLanguage));
    }

    private AiStructuralAnalysis send(URI endpoint, char[] apiKey, String requestBody)
            throws AiAnalysisException {
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.length == 0) {
            throw new AiAnalysisException(LocalizedMessage.of("ai.error.apiKeyMissing"), "AI API key must not be empty");
        }
        char[] keyCopy = Arrays.copyOf(apiKey, apiKey.length);
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + new String(keyCopy))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody,
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiAnalysisException(LocalizedMessage.of("ai.error.httpRejected",
                        "status", response.statusCode()),
                        "AI service rejected the request with HTTP status " + response.statusCode());
            }
            return responseParser.parse(response.body());
        } catch (IOException exception) {
            throw new AiAnalysisException(LocalizedMessage.of("ai.error.unavailable"),
                    "AI service is unavailable; deterministic analysis results were preserved", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiAnalysisException(LocalizedMessage.of("ai.error.interrupted"),
                    "AI explanation request was interrupted; deterministic analysis results were preserved",
                    exception);
        } finally {
            Arrays.fill(keyCopy, '\0');
        }
    }
}
