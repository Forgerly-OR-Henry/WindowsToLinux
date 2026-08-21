package gold.debug.windowstolinux.shared.ai.provider;

import gold.debug.windowstolinux.shared.ai.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.AiAnalysisFailureType;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates the provider endpoint and model before any request is created.
 *
 * <p>在创建任何请求之前验证 Provider 端点和模型。
 */
public final class ProviderEndpointPolicy {
    /**
     * Validates the input through {@code validateEndpoint}.
     *
     * <p>通过 {@code validateEndpoint} 验证输入。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @return the operation result / 操作结果
     * @throws AiAnalysisException if the operation cannot be completed / 无法完成操作时
     */
    public URI validateEndpoint(URI endpoint) throws AiAnalysisException {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw AiAnalysisException.create(AiAnalysisFailureType.ENDPOINT_INVALID,
                    "AI endpoint must be an HTTP(S) Chat Completions URL without user info, query, or fragment");
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme()) && !isLoopback(endpoint.getHost())) {
            throw AiAnalysisException.create(AiAnalysisFailureType.HTTPS_REQUIRED,
                    "Remote AI endpoints must use HTTPS; HTTP is allowed only for loopback addresses");
        }
        return endpoint;
    }

    /**
     * Validates the input through {@code requireModel}.
     *
     * <p>通过 {@code requireModel} 验证输入。
     *
     * @param model the {@code model} value / {@code model} 值
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public String requireModel(String model) {
        model = Objects.requireNonNull(model, "model").trim();
        if (!model.matches("[A-Za-z0-9._:/-]{1,128}")) {
            throw new IllegalArgumentException("model is invalid");
        }
        return model;
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }
}
