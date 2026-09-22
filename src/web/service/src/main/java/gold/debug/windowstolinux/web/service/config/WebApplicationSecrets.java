package gold.debug.windowstolinux.web.service.config;

import java.util.*;

import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import tools.jackson.databind.JsonNode;

/**
 * Manages encrypted application and transient decision secrets; task records never contain plaintext.
 * <p>管理加密应用秘密及临时决策秘密；任务记录绝不包含明文。
 */
public final class WebApplicationSecrets {
    /**
     * Bound web credential store collaborator for store.
     * <p>处理存储的Web凭据存储协作对象。
     */
    private final WebCredentialStore store;
    /**
     * Binds the supplied dependencies and state for web application secrets.
     * <p>为Web应用秘密集合绑定传入的依赖及状态。
     *
     * @param store store / 存储
     */
    public WebApplicationSecrets(WebCredentialStore store) {
        this.store = store;
    }

    /**
     * Persists json node.
     * <p>持久化JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public JsonNode save(WebRequestContext context, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "environment", "value");
        String value = WebRequestValidator.text(input, "value", 65536);
        if (!input.has("environment"))
            return WebJsonCodec.object().put("secretId", store.save(scope(context), "input", value.toCharArray()));
        String environment = WebRequestValidator.text(input, "environment", 40);
        if (!environment.matches("[A-Z][A-Z0-9_]{0,39}"))
            throw new IllegalArgumentException("Invalid environment name");
        String id = "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".env."
                + environment.toLowerCase(Locale.ROOT).replace('_', '-');
        store.saveRevision(scope(context), id, 1, "application", value.toCharArray());
        return WebJsonCodec.object().put("identifier", id).put("revision", 1);
    }

    /**
     * Requests a secret reference through task interaction and returns a caller-owned plaintext copy from scoped storage.
     * <p>通过任务交互请求秘密引用，并从作用域存储返回由调用方持有的明文副本。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @return plaintext copy that the caller must clear after use / 明文副本，调用方须在使用后清空
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public char[] decisionSecret(WebRequestContext context, TaskInteraction interaction, String code) throws Exception {
        var answer = interaction.decide("SECRET", WebJsonCodec.object().put("code", code));
        WebRequestValidator.fields(answer, "secretId");
        return store.use(scope(context), WebRequestValidator.text(answer, "secretId", 63), "input", char[]::clone);
    }

    /**
     * Builds database credential port from the supplied database credentials inputs.
     * <p>根据所提供数据库凭据输入构建数据库凭据端口。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return database credential port from the supplied database credentials inputs / 根据所提供数据库凭据输入构建数据库凭据端口
     */
    public DatabaseCredentialPort databaseCredentials(WebRequestContext context) {
        return new DatabaseCredentialPort() {
            /**
             * Returns the latest application-purpose secret reference, or empty when no revision exists.
             * <p>返回应用用途秘密的最新引用；不存在修订时返回空值。
             *
             * @param identifier the stable secret identifier / 稳定的秘密标识
             * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override
            public Optional<SecretReference> latest(String identifier) throws Exception {
                int version = store.latestVersion(scope(context), identifier, "application");
                return version == 0 ? Optional.empty() : Optional.of(new SecretReference(identifier, version));
            }

            /**
             * Loads a caller-owned copy of the exact encrypted application secret revision.
             * <p>加载精确加密应用秘密修订的调用方持有副本。
             *
             * @param reference immutable public secret identity / 不可变公开秘密身份
             * @return caller-owned secret characters to clear after use / 调用方持有且须在使用后清空的秘密字符
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override
            public char[] load(SecretReference reference) throws Exception {
                return store.useRevision(scope(context), reference.identifier(), Math.toIntExact(reference.revision()),
                        "application", char[]::clone);
            }

            /**
             * Persists anonymous.
             * <p>持久化匿名。
             *
             * @param reference immutable public secret identity / 不可变公开秘密身份
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override
            public void save(SecretReference reference, char[] value) throws Exception {
                store.saveRevision(scope(context), reference.identifier(), Math.toIntExact(reference.revision()),
                        "application", value);
            }
        };
    }

    /**
     * Resolves list.
     * <p>解析列表。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param references references / 引用集合
     * @return list / 列表
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public List<ResolvedSecretRevision> resolve(WebRequestContext context, List<SecretReference> references)
            throws Exception {
        List<ResolvedSecretRevision> result = new ArrayList<>();
        try {
            for (var reference : references)
                result.add(
                        store.useRevision(scope(context), reference.identifier(), Math.toIntExact(reference.revision()),
                                "application", value -> new ResolvedSecretRevision(reference, value)));
            return List.copyOf(result);
        } catch (Exception failure) {
            result.forEach(ResolvedSecretRevision::close);
            throw failure;
        }
    }

    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) {
        return new ResourceScope(context.workspaceId(), context.userId());
    }
}
