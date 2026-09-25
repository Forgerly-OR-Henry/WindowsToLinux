package gold.debug.windowstolinux.api.mod;

import gold.debug.windowstolinux.api.task.ApiException;

/**
 * Explicit dependency services with shared public type identity. / 使用共享公开类型身份的显式依赖服务。
 */
public interface ModServices {

    /**
     * Registers an owned contribution; visibility follows successful startup. / 登记所属扩展；仅在启动成功后对外可见。
     * @param <T> public payload or service contract type / 公开载荷或服务契约类型
     * @param contract public interface shared with the dependency provider / 与依赖提供方共享的公开接口
     * @param implementation provider instance implementing the public contract / 实现公开契约的提供方实例
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <T> ServiceRegistration register(Class<T> contract, T implementation) throws ApiException;

    /**
     * Finds an explicitly declared compatible dependency using shared public types. / 使用共享公开类型查找已明确声明的兼容依赖。
     * @param <T> public payload or service contract type / 公开载荷或服务契约类型
     * @param providerModId stable identity of the declared dependency / 已声明依赖的稳定身份
     * @param contract public interface shared with the dependency provider / 与依赖提供方共享的公开接口
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <T> ServiceHandle<T> require(String providerModId, Class<T> contract) throws ApiException;
}
