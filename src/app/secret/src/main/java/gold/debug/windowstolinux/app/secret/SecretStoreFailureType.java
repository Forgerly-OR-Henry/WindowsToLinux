package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Credential failures expressed using the public platform vocabulary. / 使用平台公开词汇表达凭据失败。 */
public enum SecretStoreFailureType {
    /** Credential encryption failed. / 凭据加密失败。 */
    ENCRYPT_FAILED("secret.persistence.encrypt-failed", ProblemCode.IO_FAILURE),
    /** Credential deletion failed. / 凭据删除失败。 */
    DELETE_FAILED("secret.persistence.delete-failed", ProblemCode.IO_FAILURE),
    /** Stored credential format is unsupported. / 不支持已存凭据格式。 */
    VERSION_UNSUPPORTED("secret.persistence.version-unsupported", ProblemCode.INCOMPATIBLE),
    /** Credential reading failed. / 凭据读取失败。 */
    READ_FAILED("secret.persistence.read-failed", ProblemCode.IO_FAILURE),
    /** Credential decryption or authentication failed. / 凭据解密或认证失败。 */
    DECRYPT_FAILED("secret.persistence.decrypt-failed", ProblemCode.IO_FAILURE),
    /** Required application secret input is missing. / 缺少必需的应用秘密输入。 */
    APPLICATION_VALUE_MISSING("secret.configuration.application-value-missing", ProblemCode.INVALID_INPUT),
    /** Referenced application secret is missing. / 引用的应用秘密不存在。 */
    APPLICATION_REFERENCE_MISSING("secret.configuration.application-reference-missing", ProblemCode.NOT_FOUND),
    /** Selected SSH credential is missing. / 所选 SSH 凭据不存在。 */
    SSH_CREDENTIAL_MISSING("secret.configuration.ssh-credential-missing", ProblemCode.NOT_FOUND);

    /** Stable diagnostic event retained through the migration. / 迁移前后保留的稳定诊断事件。 */
    private final String eventCode;

    /** Public failure category. / 公开失败类别。 */
    private final ProblemCode problemCode;

    /** Binds one credential event to its public category. / 将凭据事件绑定到公开类别。
     * @param eventCode stable event code / 稳定事件码
     * @param problemCode public category / 公开类别 */
    SecretStoreFailureType(String eventCode, ProblemCode problemCode) {
        this.eventCode = eventCode;
        this.problemCode = problemCode;
    }

    /** Returns the stable diagnostic event. / 返回稳定诊断事件。
     * @return original event code / 原事件码 */
    public String eventCode() {
        return eventCode;
    }

    /** Returns the public failure category. / 返回公开失败类别。
     * @return public category / 公开类别 */
    public ProblemCode problemCode() {
        return problemCode;
    }
}
