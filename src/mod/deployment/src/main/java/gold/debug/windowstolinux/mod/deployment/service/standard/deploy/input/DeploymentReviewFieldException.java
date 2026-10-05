package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

/** Identifies an actual invalid input without exposing its entered value in UI diagnostics. / 标识实际无效输入，不在界面诊断暴露输入值。 */
public final class DeploymentReviewFieldException extends IllegalArgumentException {
    /** Exact original input key, never the submitted content. / 精确原输入键，非提交内容。 */
    private final String field;

    /** Preserves the original validation cause for controlled diagnostics. / 为受控诊断保留原校验原因。
     * @param field original bounded key / 原有界键
     * @param cause original validation failure / 原校验失败 */
    public DeploymentReviewFieldException(String field, IllegalArgumentException cause) {
        super("Original deployment review field requires correction", cause);
        if (field == null || !field.matches("[A-Za-z][A-Za-z0-9]{0,127}"))
            throw new IllegalArgumentException("Invalid review field identity");
        this.field = field;
    }

    /** Returns only the original field identity. / 仅返回原字段身份。
     * @return exact key / 精确键 */
    public String field() {
        return field;
    }
}
