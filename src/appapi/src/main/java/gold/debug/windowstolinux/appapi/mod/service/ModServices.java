package gold.debug.windowstolinux.appapi.mod.service;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Explicit dependency services with shared public type identity. / 使用共享公开类型身份的显式依赖服务。
 */
public interface ModServices {

    /** Observes one declared provider without loading its types or starting work. / 观察声明提供方，不加载其类型或开始工作。
     * @param providerModId exact declared dependency identity / 明确声明提供方身份
     * @return current availability; service calls perform their own live checks / 当前可用性观察；服务调用仍独立实时核验
     * @throws ApiException if the dependency is undeclared or this capability is revoked / 依赖未声明或本能力已撤销时
     */
    ServiceAvailability availability(String providerModId) throws ApiException;

    /** Registers an owned contribution; visibility follows successful startup. / 登记所属扩展；仅在启动成功后对外可见。
     * @param <T> public payload or service contract type / 公开载荷或服务契约类型
     * @param contract public interface shared with the dependency provider / 与依赖提供方共享的公开接口
     * @param implementation provider instance implementing the public contract / 实现公开契约的提供方实例
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <T> ServiceRegistration register(Class<T> contract, T implementation) throws ApiException;

    /** Finds an explicitly declared compatible dependency using shared public types. / 使用共享公开类型查找已明确声明的兼容依赖。
     * @param <T> public payload or service contract type / 公开载荷或服务契约类型
     * @param providerModId stable identity of the declared dependency / 已声明依赖的稳定身份
     * @param contract public interface shared with the dependency provider / 与依赖提供方共享的公开接口
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <T> ServiceHandle<T> require(String providerModId, Class<T> contract) throws ApiException;
}
