package gold.debug.windowstolinux.app.secret;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;

/** Reports credential failures without copying secret input or provider messages. / 报告凭据失败，不复制秘密输入或提供方消息。 */
public final class SecretStoreException extends ApiException {
    /** Binds immutable public evidence and the internal original cause. / 绑定不可变公开证据与内部原始原因。
     * @param problem safe public diagnostic / 安全公开诊断
     * @param cause original internal cause / 原内部原因 */
    public SecretStoreException(Problem problem, Throwable cause) {
        super(problem);
        initCause(cause);
    }

    /** Creates an occurrence from a safe host-authored explanation. / 从宿主提供的安全说明创建失败实例。
     * @param type credential classification / 凭据分类
     * @param diagnostic non-secret host explanation / 不含秘密的宿主说明
     * @return typed failure / 类型化失败 */
    public static SecretStoreException create(SecretStoreFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /** Preserves the internal cause without using its message as public evidence. / 保留内部原因，不将其消息作为公开证据。
     * @param type credential classification / 凭据分类
     * @param diagnostic non-secret host explanation / 不含秘密的宿主说明
     * @param cause original internal cause / 原内部原因
     * @return typed failure / 类型化失败 */
    public static SecretStoreException create(SecretStoreFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /** Copies declared non-secret context and assigns one stable diagnostic identity. / 复制已声明非秘密上下文并分配一个稳定诊断身份。
     * @param type credential classification / 凭据分类
     * @param arguments non-secret host context / 不含秘密的宿主上下文
     * @param diagnostic non-secret host explanation / 不含秘密的宿主说明
     * @param cause original internal cause / 原内部原因
     * @return typed failure with immutable evidence / 带不可变证据的类型化失败 */
    public static SecretStoreException create(SecretStoreFailureType type, Map<String, ?> arguments, String diagnostic,
            Throwable cause) {
        var context = new TreeMap<String, String>();
        arguments.forEach((key, value) -> context.put(key, Objects.toString(value, "")));
        return new SecretStoreException(
                new Problem(type.problemCode(), type.eventCode(), diagnostic, context, Optional.of(UUID.randomUUID())),
                cause);
    }
}
