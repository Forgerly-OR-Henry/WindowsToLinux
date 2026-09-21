package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import gold.debug.windowstolinux.shared.model.recovery.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;

/**
 * Separates visual evidence from regular-model decisions using fresh ordered snapshots. / 通过新的有序快照分离视觉证据和常规模型决策。
 */
public final class RecoveryAiUseCase {
    /**
     * Bound ai profile repository collaborator for profiles.
     * <p>处理配置资料集合的AI配置资料仓库协作对象。
     */
    private final AiProfileRepository profiles;
    /**
     * Bound desktop secret store service collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService secrets;
    /**
     * Client.
     * <p>客户端。
     */
    private final RecoveryModelClient client;
    /**
     * Wires provider metadata, protected credentials and bounded model calls. / 装配提供者元数据、受保护凭据和有界模型调用。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param client client / 客户端
     */
    public RecoveryAiUseCase(AiProfileRepository profiles, DesktopSecretStoreService secrets, RecoveryModelClient client) {
        this.profiles = profiles; this.secrets = secrets; this.client = client;
    }
    /**
     * Contains only status evidence and transient result; its string form excludes content. / 仅持有状态证据和临时结果，字符串不含内容。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param content content / 内容
     * @param attempts attempts / 尝试集合
     */
    public record Result(String code, String content, Map<String, RecoveryModelClient.OutcomeStatus> attempts) {
        /**
         * Binds the supplied dependencies and state for result.
         * <p>为结果绑定传入的依赖及状态。
         *
         * @param code stable machine-readable classification code / 稳定的机器可读分类码
         * @param content content / 内容
         * @param attempts attempts / 尝试集合
         */
        public Result { attempts = Collections.unmodifiableMap(new LinkedHashMap<>(attempts)); }
        /**
         * Returns the diagnostic text representation of this object.
         * <p>返回当前对象的诊断文本表示。
         *
         * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
         */
        @Override public String toString() { return "RecoveryModelResult[" + code + "]"; }
    }
    /**
     * Converts text directly or routes images through the configured vision order. / 直接返回文字，或按配置的视觉顺序处理图片。
     *
     * @param observation observation / 观测
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @return constructed or resolved result / 构造或解析得到的结果
     * @throws java.sql.SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Result observe(TerminalObservation observation, char[] master) throws java.sql.SQLException {
        if (!observation.text().isBlank()) return new Result("ok", observation.text(), Map.of());
        return invoke(profiles.configuredFor(AiPurposeType.VISION), true, profile -> key ->
                client.observe(profile.chatCompletionsEndpoint(), profile.model(), key, observation.image()), master);
    }
    /**
     * Uses regular models only to produce a separate approved-action proposal. / 仅使用常规模型生成独立的待批准动作建议。
     *
     * @param observation observation / 观测
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @return constructed or resolved result / 构造或解析得到的结果
     * @throws java.sql.SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Result decide(String observation, char[] master) throws java.sql.SQLException {
        return invoke(profiles.configuredFor(AiPurposeType.DEPLOYMENT), false, profile -> key ->
                client.propose(profile.chatCompletionsEndpoint(), profile.model(), key, observation), master);
    }
    /**
     * Invokes the captured recovery model route with scoped credentials and retains safe per-provider attempt classifications.
     * <p>使用限定作用域凭据调用已捕获救援模型路由，并保留各提供者的安全尝试分类。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param vision vision / 视觉
     * @param invocation invocation / 调用
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @return constructed or resolved result / 构造或解析得到的结果
     */
    private Result invoke(List<StoredAiProviderConfiguration> snapshot, boolean vision,
            java.util.function.Function<AiProviderProfile, Invocation> invocation, char[] master) {
        return route(snapshot, vision, (stored, ignored) -> {
            char[] key = null;
            try {
                AiProviderProfile profile = AiProviderProfile.fromStored(stored.profile());
                try (var store = secrets.open(profile.credentialMode(), master)) {
                checkCancelled(); key = store.read(profile.credentialKey()).orElseThrow();
                if (key.length == 0) return new RecoveryModelClient.Reply(RecoveryModelClient.OutcomeStatus.UNAVAILABLE, "");
                return invocation.apply(profile).call(key);
                }
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            catch (CancellationException failure) { throw failure; }
            catch (Exception failure) { return new RecoveryModelClient.Reply(RecoveryModelClient.OutcomeStatus.UNAVAILABLE, ""); }
            finally { if (key != null) Arrays.fill(key, '\0'); }
        });
    }
    /**
     * Builds result from the supplied route inputs.
     * <p>根据所提供路由输入构建结果。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param vision vision / 视觉
     * @param call call / 调用
     * @return result from the supplied route inputs / 根据所提供路由输入构建结果
     */
    static Result route(List<StoredAiProviderConfiguration> snapshot, boolean vision,
            BiFunction<StoredAiProviderConfiguration, Boolean, RecoveryModelClient.Reply> call) {
        var enabled = List.copyOf(snapshot);
        Map<String, RecoveryModelClient.OutcomeStatus> attempts = new LinkedHashMap<>();
        for (var profile : enabled) {
            checkCancelled(); if (attempts.containsKey(profile.profile().id())) continue;
            var reply = call.apply(profile, vision); checkCancelled();
            attempts.put(profile.profile().id(), reply.status());
            if (reply.status() == RecoveryModelClient.OutcomeStatus.VALID) return new Result("ok", reply.content(), attempts);
        }
        String code = attempts.containsValue(RecoveryModelClient.OutcomeStatus.UNAVAILABLE) ? "modelUnavailable"
                : attempts.containsValue(RecoveryModelClient.OutcomeStatus.INVALID) ? "modelInvalid"
                : vision ? "visionUnsupported" : "regularUnavailable";
        return new Result(code, "", attempts);
    }
    /**
     * Checks cancelled.
     * <p>检查已取消。
     */
    private static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    /**
     * Invokes one recovery model selected by the configured group routing.
     * <p>调用按已配置分组路由选中的一个救援模型。
     */
    @FunctionalInterface private interface Invocation {
    /**
     * Runs the supplied callback through this adapter's execution boundary.
     * <p>通过当前适配器的执行边界运行所提供回调。
     *
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @return constructed or resolved reply / 构造或解析得到的回复
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
     RecoveryModelClient.Reply call(char[] key) throws Exception; }
}
