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
 *  <p>仅使用已脱敏的确定性事实调用 OpenAI 兼容端点。
 */
public final class OpenAiCompatibleStructuralAnalysisClient {
    /**
     * Transport.
     * <p>传输。
     */
    private final RoleChatTransport transport;
    /**
     * Bound provider endpoint policy collaborator for endpoint policy.
     * <p>处理端点策略的提供者端点策略协作对象。
     */
    private final ProviderEndpointPolicy endpointPolicy;
    /**
     * Response parser.
     * <p>响应解析器。
     */
    private final ChatCompletionResponseParser responseParser;

    /**
     * Initializes open ai compatible structural analysis client through its shared constructor contract.
     * <p>通过共享构造契约初始化打开AI兼容Structural分析客户端。
     */
    public OpenAiCompatibleStructuralAnalysisClient() {
        this(new HttpRoleChatTransport());
    }

    /**
     * Validates and binds the inputs required by open ai compatible structural analysis client.
     * <p>校验并绑定打开AI兼容Structural分析客户端所需输入。
     *
     * @param transport transport / 传输
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    OpenAiCompatibleStructuralAnalysisClient(RoleChatTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpointPolicy = new ProviderEndpointPolicy();
        this.responseParser = new ChatCompletionResponseParser();
    }

    /**
     * Analyzes selected typed deployment facts without transmitting a source path or source contents. / 在不传输源码路径或内容的情况下分析选定类型化部署事实。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param apiKey api key / api键
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param responseLanguage response language / 响应语言
     * @return constructed or resolved ai structural assessment / 构造或解析得到的AIStructural评估
     * @throws AiAnalysisException if the ai analysis boundary rejects the operation / AI分析边界拒绝当前操作时
     */
    public AiStructuralAssessment analyze(URI endpoint, String model, char[] apiKey, DeploymentProjectFacts facts,
                                        AiResponseLanguageType responseLanguage)
            throws AiAnalysisException {
        endpoint = endpointPolicy.validateEndpoint(endpoint);
        model = endpointPolicy.requireModel(model);
        return send(endpoint, apiKey, StructuralAnalysisPrompt.requestBody(model,
                RedactedDeploymentProjectFacts.from(facts), responseLanguage));
    }

    /**
     * Sends the bounded analysis request with the scoped API key, validates the HTTP response and parses only the supported structured result.
     * <p>使用限定作用域 API 密钥发送有界分析请求，验证 HTTP 响应，并仅解析受支持结构化结果。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param apiKey api key / api键
     * @param requestBody request body / 请求正文
     * @return constructed or resolved ai structural assessment / 构造或解析得到的AIStructural评估
     * @throws AiAnalysisException if the ai analysis boundary rejects the operation / AI分析边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
