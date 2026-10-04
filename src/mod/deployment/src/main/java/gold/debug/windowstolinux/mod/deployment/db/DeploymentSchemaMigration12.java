package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.db.schema.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.schema.SchemaMigration;

/** Copies exact schema 1 into a separate schema 2 generation without changing its source. / 将精确结构1复制到独立结构2代次，不改变来源。 */
public final class DeploymentSchemaMigration12 implements SchemaMigration {
    /** Creates the explicit authored migration edge. / 创建显式作者迁移边。 */
    public DeploymentSchemaMigration12() {
    }

    /** Copies original rows through protected source and fresh target accounts. / 通过受保护来源和全新目标账户复制原行。
     * @param source original version 1 / 原版本1
     * @param target independent version 2 / 独立版本2
     * @throws Exception if source, target or copied constraints differ / 来源、目标或复制约束不符时 */
    @Override
    public void migrate(SchemaAccess source, SchemaAccess target) throws Exception {
        DeploymentSchemaMigration.migrate(source, target, "1", "2");
    }
}
