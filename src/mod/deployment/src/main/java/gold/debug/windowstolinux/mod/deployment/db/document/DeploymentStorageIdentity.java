package gold.debug.windowstolinux.mod.deployment.db.document;

import java.util.Objects;

/** Pure stored identity syntax without repository access. / 不访问仓库的纯存储身份语法。 */
public final class DeploymentStorageIdentity {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentStorageIdentity() {
    }

    /** Validates the bounded printable identity used by stored documents. / 校验存储文档使用的有界可打印身份。
     * @param value caller-owned value validated by this contract / 当前合同校验的调用方所属值
     * @param field required JSON field name / 必需 JSON 字段名称
     * @return validated bounded stored identity / 已校验有界存储身份 */
    public static String boundedIdentity(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded release identity");
        }
        return value;
    }
}
