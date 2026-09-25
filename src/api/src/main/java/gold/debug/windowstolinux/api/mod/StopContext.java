package gold.debug.windowstolinux.api.mod;

/**
 * Reason for an idempotent background lifecycle stop. / 后台幂等停止所使用的原因上下文。
 */
public interface StopContext {

    /**
     * Returns the lifecycle cause for this stop request. / 返回此次停止请求的生命周期原因。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    StopReason reason();
}
