package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiProviderAttempt;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Shared ordered invocation policy; each enabled provider is attempted once per immutable configuration snapshot. / 共用有序调用策略，每个启用提供者在配置快照中仅尝试一次。
 */
final class AiProviderChain {
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
     * Binds the supplied dependencies and state for ai provider chain.
     * <p>为AI提供者调用链绑定传入的依赖及状态。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    AiProviderChain(AiProfileRepository profiles, DesktopSecretStoreService secrets) { this.profiles = profiles; this.secrets = secrets; }

    /**
     * Tries enabled providers in the captured priority order, retaining classified attempt evidence and stopping on accepted advice.
     * <p>按捕获的优先级顺序尝试已启用提供者，保留分类尝试证据，并在建议被接受时停止。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param invocation invocation / 调用
     * @return constructed or resolved outcome / 构造或解析得到的结果
     * @throws java.sql.SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    <T> Outcome<T> invoke(char[] master, Invocation<T> invocation) throws java.sql.SQLException {
        return invoke(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT,master,invocation);
    }
    /** Routes only within the frozen purpose chain. / 仅在冻结用途链中路由。
     * @param purpose explicit purpose / 显式用途
     * @param master secret-store unlock buffer / 秘密存储解锁缓冲区
     * @param invocation bounded request / 有界请求
     * @param <T> response type / 响应类型
     * @return outcome and attempts / 结果及尝试记录
     * @throws java.sql.SQLException when inventory cannot be read / 无法读取模型库时
     */
    <T> Outcome<T> invoke(gold.debug.windowstolinux.shared.model.ai.AiPurposeType purpose,char[] master,Invocation<T> invocation) throws java.sql.SQLException {
        List<AiProviderAttempt> attempts = new ArrayList<>(); Optional<T> last = Optional.empty();
        try {
            checkCancelled();
            var scope=DeploymentAiScope.current();
            var snapshot = scope.isPresent() ? scope.get().remaining(purpose)
                    : profiles.configuredFor(purpose).stream().map(value -> AiProviderProfile.fromStored(value.profile())).toList();
            for (AiProviderProfile profile : snapshot) {
                checkCancelled(); char[] key = null; Attempt<T> result = null;
                try (var store = secrets.open(profile.credentialMode(), master)) {
                    key = store.read(profile.credentialKey()).orElseGet(() -> new char[0]);
                    if (key.length == 0) throw new IllegalStateException("credential-unavailable");
                    checkCancelled(); scope.ifPresent(DeploymentAiScope::called); result = invocation.call(profile, key); checkCancelled();
                } catch (CancellationException cancelled) { throw cancelled; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("AI invocation cancelled"); }
                catch (Exception failure) {
                    checkCancelled(); result = null;
                } finally { if (key != null) Arrays.fill(key, '\0'); }
                attempts.add(new AiProviderAttempt(profile.id(), profile.model(), result == null ? AiInvocationStatus.UNAVAILABLE : result.status(),
                        result == null ? "credential-or-provider-unavailable" : result.detail(), Instant.now()));
                String recordedStatus=result==null?"UNAVAILABLE":result.status().name();
                scope.ifPresent(value->value.attempted(purpose,profile.id(),recordedStatus));
                if (result != null) {
                    last = Optional.of(result.value());
                    if (result.status() == AiInvocationStatus.VALIDATED) return new Outcome<>(last, List.copyOf(attempts), true, snapshot);
                }
                scope.ifPresent(value -> value.advance(purpose));
            }
            return new Outcome<>(last, List.copyOf(attempts), false, snapshot);
        } finally { if (master != null) Arrays.fill(master, '\0'); }
    }
    /**
     * Checks cancelled.
     * <p>检查已取消。
     */
    static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("AI invocation cancelled"); }
    /**
     * Calls one selected provider with its scoped plaintext credential.
     * <p>使用限定作用域的明文凭据调用一个已选提供者。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     */
    @FunctionalInterface interface Invocation<T> {
    /**
     * Runs the supplied callback through this adapter's execution boundary.
     * <p>通过当前适配器的执行边界运行所提供回调。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @return constructed or resolved attempt / 构造或解析得到的尝试
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
     Attempt<T> call(AiProviderProfile profile, char[] key) throws Exception; }
    /**
     * Captures one provider result and its validation classification.
     * <p>记录一次提供者结果及其验证分类。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param detail detail / 详情
     */
    record Attempt<T>(T value, AiInvocationStatus status, String detail) { }
    /**
     * Combines the stable provider snapshot, attempt evidence and selected result.
     * <p>组合稳定的提供者快照、尝试证据及所选结果。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param attempts attempts / 尝试集合
     * @param valid valid / 有效
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     */
    record Outcome<T>(Optional<T> value, List<AiProviderAttempt> attempts, boolean valid, List<AiProviderProfile> snapshot) { }
}
