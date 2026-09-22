package gold.debug.windowstolinux.shared.backup.contract.definition;

import java.util.*;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Reviewed inputs for one stopped-writer backup window. / 一次停写备份窗口的已审阅输入。
 *
 * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
 * @param ownsMaintenance whether this collection is responsible for releasing maintenance markers; false leaves release to the outer operation / 本次采集是否负责释放维护标记；为 false 时由外层操作负责释放
 * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
 * @param healthTriggers component identifiers whose original running state requires the whole-application health gate after recovery / 其原始运行状态会触发恢复后整应用健康门的组件标识
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param maximumArtifactBytes maximum size of one downloaded artifact in bytes, from one byte through 64 GiB / 单个下载制品的字节数上限，范围为一字节至 64 GiB
 */
public record BackupCollectionRequest(MultiComponentDeploymentPlan plan, List<Component> components, String operationId,
        boolean ownsMaintenance, ApplicationHealthGate applicationHealth, Set<String> healthTriggers,
        Optional<Database> database, long maximumArtifactBytes) {
    /**
     * Validates and binds the inputs required by backup collection request.
     * <p>校验并绑定备份采集请求所需输入。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @param ownsMaintenance whether this collection is responsible for releasing maintenance markers; false leaves release to the outer operation / 本次采集是否负责释放维护标记；为 false 时由外层操作负责释放
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @param healthTriggers component identifiers whose original running state requires the whole-application health gate after recovery / 其原始运行状态会触发恢复后整应用健康门的组件标识
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param maximumArtifactBytes maximum size of one downloaded artifact in bytes, from one byte through 64 GiB / 单个下载制品的字节数上限，范围为一字节至 64 GiB
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupCollectionRequest {
        Objects.requireNonNull(plan, "plan");
        components = List.copyOf(components);
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(applicationHealth, "applicationHealth");
        healthTriggers = Set.copyOf(healthTriggers);
        database = Objects.requireNonNull(database, "database");
        Set<String> ids = new HashSet<>(components.stream().map(Component::id).toList());
        if (ids.size() != components.size() || !ids.equals(new HashSet<>(plan.startOrder()))
                || !ids.contains(applicationHealth.componentId()) || !ids.containsAll(healthTriggers)
                || database.isPresent() && !ids.contains(database.orElseThrow().componentId())
                || !operationId.matches("backup-[a-f0-9]{32}") || maximumArtifactBytes < 1
                || maximumArtifactBytes > 64L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "Backup inputs must exactly cover the reviewed plan and bounded operation");
        }
    }

    /**
     * Exact successful release and reviewed runtime resources. / 精确的成功发布及已审阅运行资源。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     */
    public record Component(String id, ManagedApplication application, DeploymentRuntimeSpecification runtime,
            String releaseSha256, ManagedComponentResourceBindings resources) {
        /**
         * Validates and binds the inputs required by component.
         * <p>校验并绑定组件所需输入。
         *
         * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
         * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
         * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
         * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
         * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Component {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(resources, "resources");
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}") || !Objects.requireNonNull(releaseSha256).matches("[a-f0-9]{64}")
                    || resources.databaseBindings().isEmpty()) {
                throw new IllegalArgumentException("Backup requires a successful release and reviewed resources");
            }
        }
    }

    /**
     * Single database admitted by the platform's reviewed binding checks. / 平台绑定审查准入的单个数据库。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param databaseId database id / 数据库标识
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     */
    public record Database(String componentId, String databaseId, DatabaseConnectionProfile profile) {
        /**
         * Validates and binds the inputs required by database.
         * <p>校验并绑定数据库所需输入。
         *
         * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
         * @param databaseId database id / 数据库标识
         * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Database {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(profile, "profile");
            if (!Objects.requireNonNull(databaseId, "databaseId").matches("[a-z0-9][a-z0-9-]{0,62}"))
                throw new IllegalArgumentException("Database identity must be bounded");
        }
    }
}
