package gold.debug.windowstolinux.appapi.mod;

/**
 * Synchronous cross-Mod work that must not leak provider resources. / 不得泄漏提供方资源的同步跨插件调用。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 * @param <R> callback result type / 回调结果类型
 */
public interface ServiceCall<T, R> {

    /**
     * Completes synchronous provider work without leaking resources outside the call. / 同步完成提供方工作，不将资源泄漏到调用外。
     * @param service provider implementing the shared public contract / 实现共享公开契约的提供方
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    R invoke(T service) throws Exception;
}
