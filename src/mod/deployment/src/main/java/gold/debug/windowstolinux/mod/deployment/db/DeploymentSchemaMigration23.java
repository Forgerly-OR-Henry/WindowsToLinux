package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.db.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.SchemaMigration;

/** Copies exact schema 2 into a separate schema 3 generation without changing its source. / 将精确结构2复制到独立结构3代次，不改变来源。 */
public final class DeploymentSchemaMigration23 implements SchemaMigration {
    /** Creates the explicit authored migration edge. / 创建显式作者迁移边。 */
    public DeploymentSchemaMigration23() {
    }

    /** Copies original rows through protected source and fresh target accounts. / 通过受保护来源和全新目标账户复制原行。
     * @param source original version 2 / 原版本2
     * @param target independent version 3 / 独立版本3
     * @throws Exception if source, target or copied constraints differ / 来源、目标或复制约束不符时 */
    @Override
    public void migrate(SchemaAccess source, SchemaAccess target) throws Exception {
        DeploymentSchemaMigration.migrate(source, target, "2", "3");
    }
}
