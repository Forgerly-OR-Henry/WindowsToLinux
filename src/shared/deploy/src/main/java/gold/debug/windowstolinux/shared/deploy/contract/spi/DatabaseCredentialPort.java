package gold.debug.windowstolinux.shared.deploy.contract.spi;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import java.util.Optional;

/**
 * Provides platform-owned immutable database credentials; returned character arrays belong to the caller.
 * <p>提供平台持有的不可变数据库凭据；返回字符数组由调用方持有。
 */
public interface DatabaseCredentialPort {
    /**
     * Finds the latest stored revision for a logical secret identifier without exposing its plaintext.
     * <p>查找逻辑秘密标识的最新持久化修订，不暴露明文。
     *
     * @param identifier the stable secret identifier / 稳定的秘密标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    Optional<SecretReference> latest(String identifier) throws Exception;
    /**
     * Loads a caller-owned plaintext copy of the exact stored secret revision; the caller must clear it after use.
     * <p>加载由调用方持有的精确持久化秘密修订明文副本；调用方须在使用后清空。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @return caller-owned secret characters to clear after use / 调用方持有且须在使用后清空的秘密字符
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    char[] load(SecretReference reference) throws Exception;
    /**
     * Persists database credential.
     * <p>持久化数据库凭据。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    void save(SecretReference reference, char[] value) throws Exception;
}
