package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader.ApplicationFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Builds complete fixed helper inputs from the one retained release, never from archive guesses. / 从同一保留发布构造完整固定helper输入，绝不从归档猜测。 */
public final class DeploymentCollectionPlan {
    /** Creates a stateless planner for complete original bindings. / 创建完整原绑定的无状态计划器。 */
    public DeploymentCollectionPlan() {
    }

    /** Original complete archive byte limit sent to the helper. / 发送给helper的原完整归档字节上限。 */
    private static final String MAX_ARCHIVE = "68719476736";

    /** Original complete database byte limit sent to the helper. / 发送给helper的原完整数据库字节上限。 */
    private static final String MAX_DATABASE = "4294967296";

    /** One planned original-operation artifact. / 一项计划中的原操作产物。
     * @param bindingId public binding identity / 公开绑定身份
     * @param componentId owning component / 所属组件
     * @param unitId deployment unit / 部署单元
     * @param release unit release digest / 单元发布摘要
     * @param manifest owner digest / 所有权摘要
     * @param kind release, file, oci or database / 发布、文件、镜像或数据库
     * @param resource original resource identity / 原资源身份
     * @param maximum fixed byte limit / 固定字节上限
     * @param databaseArguments database-specific fixed arguments / 数据库专用固定参数 */
    public record ArtifactPlan(String bindingId, String componentId, String unitId, String release, String manifest,
                               String kind, String resource, String maximum, List<String> databaseArguments) {
        /** Copies reviewed arguments. / 复制已审阅参数。
         * @param bindingId public binding / 公开绑定
         * @param componentId owner / 所属组件
         * @param unitId unit / 单元
         * @param release unit release / 单元发布
         * @param manifest owner / 所有者
         * @param kind kind / 类别
         * @param resource resource / 资源
         * @param maximum maximum / 上限
         * @param databaseArguments database arguments / 数据库参数 */
        public ArtifactPlan {
            databaseArguments = List.copyOf(databaseArguments);
        }

        /** Exact remote create command under one handoff token. / 同一交接令牌下的精确远端创建命令。
         * @param operation original backup operation / 原备份操作
         * @param application original application / 原应用
         * @return fixed helper arguments / 固定helper参数 */
        public List<String> createArguments(String operation, String application) {
            var result = new ArrayList<>(List.of(operation, application, componentId, unitId, release, manifest));
            if (kind.equals("database")) {
                result.add(resource);
                result.add(maximum);
                result.addAll(databaseArguments);
            } else
                result.addAll(List.of(kind, resource, maximum));
            return List.copyOf(result);
        }

        /** Exact read-only status arguments, including the complete reviewed request. / 包含完整已审阅请求的精确只读状态参数。
         * @param operation original backup operation / 原备份操作
         * @param application original application / 原应用
         * @return fixed helper arguments / 固定helper参数 */
        public List<String> statusArguments(String operation, String application) {
            if (kind.equals("database"))
                return createArguments(operation, application);
            return List.of(operation, application, componentId, unitId, release, manifest, kind, resource, maximum);
        }
    }

    /** Validates selected exact binding IDs and plans every release needed for restore. / 校验所选精确绑定并计划恢复所需的每项发布。
     * @param data current immutable release / 当前不可变发布
     * @param selected requested binding IDs / 请求绑定ID
     * @return complete sorted plan / 完整排序计划
     * @throws ApiException if selection or a database has no consistent protocol / 选择或数据库无一致协议时 */
    public List<ArtifactPlan> build(ApplicationFacts data, List<String> selected) throws ApiException {
        var bindings = data.snapshot().bindings();
        if (selected.size() > 256 || selected.stream().anyMatch(value -> value == null || value.isBlank())
            || selected.size() != new HashSet<>(selected).size())
            throw invalid("DEPLOYMENT_COLLECTION_BINDINGS_INVALID");
        Set<String> chosen = Set.copyOf(selected);
        for (String id : selected) {
            if (id == null || id.isBlank() || bindings.stream().filter(value -> value.id().equals(id)).count() != 1)
                throw invalid("DEPLOYMENT_COLLECTION_BINDING_AMBIGUOUS");
        }
        var plan = new ArrayList<ArtifactPlan>();
        for (var component : data.graph().components()) {
            var release = data.release().bindings().stream()
                .filter(value -> value.unitId().equals(component.unit().id())).findFirst().orElseThrow();
            plan.add(new ArtifactPlan("release:" + component.componentId(), component.componentId(),
                component.unit().id(), release.releaseSha256(), component.unit().ownershipManifestSha256(),
                "release", "release", MAX_ARCHIVE, List.of()));
            if (component.reviewedRuntime().identityPolicy() == DeploymentIdentityMode.CONTAINER_NON_ROOT)
                plan.add(new ArtifactPlan("image:" + component.componentId(), component.componentId(),
                    component.unit().id(), release.releaseSha256(), component.unit().ownershipManifestSha256(),
                    "oci", "image", MAX_ARCHIVE, List.of()));
            for (var binding : bindings) {
                if (!binding.componentId().equals(component.componentId()) || !chosen.contains(binding.id()))
                    continue;
                if (binding.kind().equals("DATABASE"))
                    plan.add(database(component, release, binding));
                else
                    plan.add(new ArtifactPlan(binding.id(), component.componentId(), component.unit().id(),
                        release.releaseSha256(), component.unit().ownershipManifestSha256(), "file",
                        component.resources().fileBindings().stream()
                            .filter(value -> binding.id()
                                .equals(component.componentId() + ":" + value.bindingId()))
                            .findFirst().orElseThrow().bindingId(),
                        MAX_ARCHIVE, List.of()));
            }
        }
        return plan.stream().sorted(Comparator.comparing(ArtifactPlan::componentId).thenComparing(ArtifactPlan::kind)
            .thenComparing(ArtifactPlan::resource)).toList();
    }

    /** Resolves one exact reviewed database and immutable password revision. / 解析一项精确已审阅数据库及不可变密码修订。
     * @param component complete original component / 完整原组件
     * @param release exact original unit release / 精确原单元发布
     * @param publicBinding original component-qualified binding / 原带组件身份的绑定
     * @return complete supported database plan / 完整受支持数据库计划
     * @throws ApiException if protocol or secret revision differs / 协议或秘密修订不符时 */
    private static ArtifactPlan database(DeploymentApplicationGraph.Part component,
                                         gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseSnapshot.Binding release,
                                         DeploymentBinding publicBinding) throws ApiException {
        var database = component.resources().databaseBindings().orElse(List.of()).stream()
            .filter(value -> publicBinding.id().equals(component.componentId() + ":" + value.databaseId()))
            .findFirst().orElseThrow(() -> invalid("DEPLOYMENT_COLLECTION_DATABASE_MISSING"));
        var connection = database.connection();
        var args = new ArrayList<String>();
        if (connection instanceof DeploymentDatabaseConnection.Sqlite sqlite) {
            args.addAll(
                List.of("sqlite-online", "0", "0", "sqlite", database.databaseId(), sqlite.location().type().name(),
                    sqlite.location().path().isEmpty() ? "-" : sqlite.location().path(), sqlite.fileName()));
        } else if (connection instanceof DeploymentDatabaseConnection.Server server) {
            if (server.engine() == DeploymentDatabaseEngineType.REDIS)
                throw invalid("DEPLOYMENT_COLLECTION_REDIS_UNSUPPORTED");
            if (!release.secrets().contains(server.passwordReference()))
                throw invalid("DEPLOYMENT_COLLECTION_DATABASE_SECRET_REVISION_CHANGED");
            String mode = server.engine() == DeploymentDatabaseEngineType.POSTGRESQL
                ? "postgresql-logical"
                : "mysql-transaction";
            args.addAll(List.of(mode, "0", "0", server.engine().name().toLowerCase(java.util.Locale.ROOT),
                server.host(), Integer.toString(server.port()), server.database(), server.username(),
                server.passwordReference().identifier(), Long.toString(server.passwordReference().revision()),
                server.tlsRequired() ? "1" : "0"));
        } else
            throw invalid("DEPLOYMENT_COLLECTION_DATABASE_UNSUPPORTED");
        return new ArtifactPlan(publicBinding.id(), component.componentId(), component.unit().id(),
            release.releaseSha256(), component.unit().ownershipManifestSha256(), "database", database.databaseId(),
            MAX_DATABASE, args);
    }

    /** Refuses an unreviewed or unsupported binding before any effect. / 在任何副作用前拒绝未审阅或不支持的绑定。
     * @param event stable known refusal / 稳定已知拒绝
     * @return bounded original binding exception / 有界原绑定异常 */
    private static ApiException invalid(String event) {
        return new ApiException(new Problem(ProblemCode.INCOMPATIBLE, event,
            "Selected binding has no complete reviewed collection protocol", Map.of(), Optional.empty()));
    }
}
