package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview;

/** Actual source-bound database preparation handed to review and publication. / 交给审阅与发布的真实源码绑定数据库准备。 */
public interface DeploymentPreparedDatabase {
    /** Generated non-secret runtime configuration. / 生成的非秘密运行配置。
     * @return immutable entries / 不可变条目 */
    List<DeploymentConfigEntry> configuration();

    /** Exact managed database resource bindings. / 精确受管数据库资源绑定。
     * @return immutable bindings / 不可变绑定 */
    List<DeploymentDatabaseBinding> bindings();

    /** Exact immutable references created or selected for database use. / 为数据库使用而创建或选择的精确不可变引用。
     * @return immutable references / 不可变引用 */
    List<DeploymentSecretRef> secrets();

    /** Independently reviewed source-bound schema mutation, if required. / 必要时独立审阅且绑定源码的结构变更。
     * @return verified review or absence / 已核实审阅或不存在 */
    Optional<DatabaseSchemaReview> schemaReview();
}
