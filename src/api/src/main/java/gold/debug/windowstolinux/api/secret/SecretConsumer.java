package gold.debug.windowstolinux.api.secret;

/**
 * Short-lived secret callback whose input is cleared after use. / 输入在使用后清零的短期秘密回调。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 */
public interface SecretConsumer<T> {

    /**
     * Consumes temporarily authorized secret bytes without retaining or logging them. / 使用临时授权的秘密字节，不保留或记录秘密。
     * @param value optional observed result payload / 可选的已观察结果载荷
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    T use(byte[] value) throws Exception;
}
