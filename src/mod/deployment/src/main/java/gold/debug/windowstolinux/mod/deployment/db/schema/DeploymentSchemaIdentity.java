package gold.debug.windowstolinux.mod.deployment.db.schema;

import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;

/** Fixed deployment data identity, independent of repositories. / 独立于仓库的固定部署数据身份。 */
public final class DeploymentSchemaIdentity {
    /** Fixed private deployment Schema owner identity. / 固定部署私有 Schema 所有者身份。 */
    public static final String OWNER = "gold.debug.windowstolinux.mod.deployment";

    /** Native-interaction deployment Schema version. / 原生交互部署 Schema 版本。 */
    public static final String VERSION = "1";

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentSchemaIdentity() {
    }

    /** Rejects a Schema identity outside the fixed deployment owner and version. / 拒绝固定部署所有者和版本之外的 Schema 身份。
     * @param identity fixed owner and version identity / 固定所有者及版本身份 */
    public static void requireIdentity(SchemaIdentity identity) {
        if (!OWNER.equals(identity.ownerId()) || !VERSION.equals(identity.schemaVersion()))
            throw new IllegalArgumentException(
                "Deployment schema identity differs from the supported owner and version");
    }
}
