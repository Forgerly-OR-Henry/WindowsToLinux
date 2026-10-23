package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationAutostartState;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.api.VersionedRuntime;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseSnapshot;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentConfigStore;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentHealthCodec;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentUnitRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentArchivedRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentCurrentRelease;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentReleaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorker;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRevision;

/** Reads complete published applications in one owned snapshot, excluding drafts and unconfirmed releases. / 在一份所属快照中读取完整已发布应用，排除草稿及未确认发布。 */
public final class DeploymentApplicationReader {
    /** Exact provider Schema used by the snapshot transaction. / 快照事务使用的精确提供方Schema。 */
    private final ModDatabase database;

    /** Canonical complete release validator. / 规范完整发布校验器。 */
    private final DeploymentReleaseStore releases;

    /** Binds the original deployment Schema. / 绑定原部署Schema。
     * @param database exact deployment database / 精确部署数据库 */
    public DeploymentApplicationReader(ModDatabase database) {
        this.database = java.util.Objects.requireNonNull(database);
        this.releases = new DeploymentReleaseStore(database);
    }

    /** Reads one persisted page without claiming live remote status. / 读取一页持久记录，不宣称实时远端状态。
     * @param cursor last application ID or absence / 上一应用ID或无
     * @param limit bounded page size / 有界页大小
     * @param task original task / 原任务
     * @return consistent application page / 一致的应用页
     * @throws ApiException if access or stored evidence fails / 访问或存储依据失败时 */
    public PageSlice<DeploymentApplicationSnapshot> list(Optional<String> cursor, int limit, TaskContext task)
        throws ApiException {
        if (limit < 1 || limit > 100 || cursor == null
            || cursor.isPresent() && !cursor.orElseThrow().matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw problem("DEPLOYMENT_APPLICATION_PAGE_INVALID");
        try (var connection = open(task)) {
            var ids = new ArrayList<String>();
            try (var query = connection.prepareStatement(
                "SELECT application_id FROM managed_application WHERE graph_ready=TRUE AND application_id>? ORDER BY application_id LIMIT ?")) {
                query.setString(1, cursor.orElse(""));
                query.setInt(2, limit + 1);
                try (var rows = query.executeQuery()) {
                    while (rows.next())
                        ids.add(rows.getString(1));
                }
            }
            boolean more = ids.size() > limit;
            if (more)
                ids.removeLast();
            var values = new ArrayList<DeploymentApplicationSnapshot>();
            long bytes = 0;
            int estimatedNodes = 0;
            for (String id : ids) {
                var value = read(connection, id, task).snapshot();
                long next = bytes + value.runtime().payload().length;
                int nodes = estimatedNodes + 24 + 16 * value.bindings().size();
                if (next > 16L * 1_048_576 || nodes > 3500) {
                    if (values.isEmpty())
                        throw problem("DEPLOYMENT_APPLICATION_PAGE_ENTRY_OVERSIZE");
                    more = true;
                    break;
                }
                values.add(value);
                bytes = next;
                estimatedNodes = nodes;
            }
            connection.rollback();
            return new PageSlice<>(values, more ? Optional.of(values.getLast().application().id()) : Optional.empty());
        } catch (SQLException | IOException failure) {
            throw problem("DEPLOYMENT_APPLICATION_READ_FAILED");
        }
    }

    /** Reads exact current facts from the deployment owner only. / 仅从部署所有者读取精确当前事实。
     * @param application exact application and server / 精确应用与服务器
     * @param task original task / 原任务
     * @return current persisted graph and public copy / 当前持久图与公开副本
     * @throws ApiException if identity or stored evidence fails / 身份或存储依据失败时 */
    public ApplicationFacts find(DeploymentApplicationId application, TaskContext task) throws ApiException {
        try (var connection = open(task)) {
            var value = read(connection, application.id(), task);
            connection.rollback();
            if (!value.snapshot().application().equals(application))
                throw problem("DEPLOYMENT_APPLICATION_SERVER_CHANGED");
            return value;
        } catch (SQLException | IOException failure) {
            throw problem("DEPLOYMENT_APPLICATION_READ_FAILED");
        }
    }

    /** Reads only the exact successful original task's still-active complete publication. / 仅读取精确成功原任务仍活动的完整发布。
     * @param originalTask exact completed platform task UUID / 精确已完成平台任务UUID
     * @param task currently admitted read task, never the ended context / 当前准入读取任务，绝非已结束上下文
     * @return eligible original publication or absence / 符合条件的原发布或无值
     * @throws ApiException if stored original evidence is inconsistent / 存储原依据不一致时 */
    public Optional<ApplicationFacts> findForCompletedTask(UUID originalTask, TaskContext task) throws ApiException {
        java.util.Objects.requireNonNull(originalTask);
        try (var connection = open(task)) {
            var receipt = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentOperationReceipts.read(connection, originalTask);
            if (receipt.isPresent()) {
                var settled = receipt.orElseThrow();
                if (settled.outcome() != gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED)
                    return Optional.empty();
                gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentOperationReceipts.validateBusiness(connection, settled);
                var release = gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore.read(connection,
                    settled.applicationId().orElseThrow(), settled.release().orElseThrow().id()).orElseThrow();
                if (release.state() != DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE) return Optional.empty();
                var data = read(connection, settled.applicationId().orElseThrow(), task);
                if (!data.snapshot().revisionSha256().equals(settled.release().orElseThrow().sha256())
                    || !data.snapshot().application().server().equals(settled.server()))
                    throw new SQLException("Settled original task differs from its active application");
                connection.rollback();
                return Optional.of(data);
            }
            connection.rollback();
        } catch (SQLException | IOException failure) {
            throw problem("DEPLOYMENT_APPLICATION_READ_FAILED");
        }
        try (var connection = open(task);
             var query = connection.prepareStatement("SELECT e.execution_id,e.application_id,e.server_profile_id,r.release_sha256,r.release_id FROM deployment_execution e JOIN deployment_release r ON r.execution_id=e.execution_id AND r.application_id=e.application_id WHERE e.platform_task_id=? AND r.state='ACTIVE'")) {
            query.setObject(1, originalTask);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                UUID executionId = rows.getObject(1, UUID.class);
                String application = rows.getString(2), server = rows.getString(3), revision = rows.getString(4);
                UUID releaseId = rows.getObject(5, UUID.class);
                if (rows.next())
                    throw new SQLException("Original task publication is not unique");
                var executions = new DeploymentExecutionStore(database);
                var execution = executions.find(task, executionId).orElseThrow();
                if (!execution.identity().taskId().equals(originalTask)
                    || !execution.identity().applicationId().equals(application)
                    || !execution.identity().server().id().equals(server)
                    || execution.outcome().orElse(null) != gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED
                    || !execution.businessCommitted() || !execution.cleanupComplete())
                    return Optional.empty();
                if (!executions.releaseIdentity(execution).equals(Optional.of(new DeploymentReleaseIdentity(releaseId, revision))))
                    throw new SQLException("Original formal release result differs from its active publication");
                var data = read(connection, application, task);
                if (!data.snapshot().revisionSha256().equals(revision)
                    || !data.snapshot().application().server().id().equals(server))
                    throw new SQLException("Original task active publication changed");
                connection.rollback();
                return Optional.of(data);
            }
        } catch (SQLException | IOException | java.util.NoSuchElementException failure) {
            throw problem("DEPLOYMENT_DELIVERY_ORIGINAL_TASK_UNVERIFIED");
        }
    }

    /** Projects one reviewed exact active snapshot using the currently authorized host. / 使用当前授权主机投影一个已审阅精确活动快照。
     * @param selected original selected complete snapshot / 原所选完整快照
     * @param host current caller-bound nonsecret server host / 当前绑定调用方的非秘密服务器主机
     * @param task currently authenticated read task / 当前认证读取任务
     * @return copied public usage / 复制的公开用法
     * @throws ApiException if the selected publication changed / 所选发布改变时 */
    public DeploymentApplicationUsage usage(DeploymentApplicationSnapshot selected, String host, TaskContext task)
        throws ApiException {
        var data = find(selected.application(), task);
        if (!data.snapshot().revisionSha256().equals(selected.revisionSha256())
            || !data.snapshot().ownershipSha256().equals(selected.ownershipSha256()))
            throw problem("DEPLOYMENT_USAGE_REVISION_CHANGED");
        return projectUsage(data, host);
    }

    /** Copies component-qualified fixed commands and validated endpoints from the complete graph. / 从完整图复制带组件身份的固定命令与已校验端点。
     * @param data complete validated active publication / 完整已校验活动发布
     * @param host current caller-bound public host / 当前绑定调用方的公开主机
     * @return immutable nonsecret usage / 不可变无秘密用法 */
    static DeploymentApplicationUsage projectUsage(ApplicationFacts data, String host) {
        var components = new ArrayList<DeploymentApplicationUsage.Component>();
        for (var component : data.graph().components()) {
            var workload = component.runtime().workload();
            var observed = data.observations().get(component.unit().id());
            boolean reviewed = workload.reviewed() && workload.equals(component.reviewedRuntime().workload());
            var mode = reviewed ? DeploymentApplicationUsage.Mode.valueOf(workload.mode().name())
                : DeploymentApplicationUsage.Mode.UNVERIFIED;
            var endpoints = new ArrayList<DeploymentApplicationUsage.Endpoint>();
            if (reviewed)
                for (var endpoint : workload.endpoints())
                    if (endpoint.exposure() == DeploymentEndpoint.ExposureType.EXTERNAL)
                        endpoint(endpoint, host).ifPresent(endpoints::add);
            var binding = data.release().bindings().stream().filter(value -> value.unitId().equals(component.unit().id()))
                .findFirst().orElseThrow();
            components.add(new DeploymentApplicationUsage.Component(component.componentId(), component.unit().id(), mode,
                observed.state(), observed.autostart(), reviewed ? Optional.of(command(component, binding))
                : Optional.empty(), endpoints,
                reviewed ? workload.workers().stream().map(DeploymentWorker::id).toList() : List.of()));
        }
        boolean reviewed = components.stream().noneMatch(value -> value.mode() == DeploymentApplicationUsage.Mode.UNVERIFIED);
        boolean daemon = components.stream().anyMatch(value -> value.mode() == DeploymentApplicationUsage.Mode.DAEMON);
        var snapshot = data.snapshot();
        return new DeploymentApplicationUsage(snapshot.application(), snapshot.revisionSha256(), snapshot.ownershipSha256(),
            snapshot.state(), snapshot.autostart(), components,
            reviewed && daemon ? java.util.Set.of(DeploymentLifecycleAction.values()) : java.util.Set.of());
    }

    /** Builds only the real helper's fixed managed command, without startup arguments. / 仅构造真实helper的固定受管命令，不含启动参数。
     * @param component original reviewed component / 原已审阅组件
     * @param binding exact current component release / 精确当前组件发布
     * @return target-machine managed command / 目标机受管命令 */
    private static String command(DeploymentApplicationGraph.Part component, gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentReleaseBinding binding) {
        var workload = component.runtime().workload();
        var unit = component.unit();
        String helper = "sudo " + unit.layout().helperPath() + " ";
        if (workload.mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND || workload.client().isPresent())
            return helper + (workload.mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND ? "app-run " : "app-client ")
                + unit.id() + " " + unit.ownershipManifestSha256() + " --";
        return helper + (component.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container
            ? "lifecycle-container " : "lifecycle-deployment ") + unit.id() + " start "
            + unit.ownershipManifestSha256() + " " + binding.releaseSha256();
    }

    /** Validates a public endpoint and strictly brackets literal IPv6 without DNS lookups. / 校验公开端点并严格括起字面IPv6，不查询DNS。
     * @param endpoint original reviewed endpoint / 原已审阅端点
     * @param host current authorized host / 当前授权主机
     * @return legal endpoint or explicit absence / 合法端点或明确无值 */
    private static Optional<DeploymentApplicationUsage.Endpoint> endpoint(DeploymentEndpoint endpoint, String host) {
        try {
            String protocol = endpoint.protocol().name().toLowerCase(java.util.Locale.ROOT);
            String value = endpoint.accessUrl();
            if (value.isEmpty()) {
                if (host.contains(":")) {
                    if (!host.matches("[0-9a-fA-F:.]{2,64}"))
                        return Optional.empty();
                    java.net.InetAddress.getByName(host);
                    host = "[" + host + "]";
                } else if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}"))
                    return Optional.empty();
                value = protocol + "://" + host + ":" + endpoint.hostPort();
            }
            URI target = URI.create(value);
            if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null)
                return Optional.empty();
            Optional<URI> browser = Optional.empty();
            if (endpoint.protocol() == DeploymentEndpoint.ProtocolType.HTTP || endpoint.protocol() == DeploymentEndpoint.ProtocolType.HTTPS) {
                URI uri = DeploymentEndpoint.requireAccessUrl(URI.create(value));
                if (uri.getPort() == 0 || uri.getPort() > 65535 || value.length() > 4096)
                    return Optional.empty();
                browser = Optional.of(uri);
            }
            return Optional.of(new DeploymentApplicationUsage.Endpoint(endpoint.id(), protocol, value, browser));
        } catch (IllegalArgumentException | java.net.UnknownHostException invalid) {
            return Optional.empty();
        }
    }

    /** Opens a transaction snapshot bound to this Mod generation. / 打开绑定此Mod代次的事务快照。
     * @param task authenticated original task / 已认证原任务
     * @return original owned snapshot connection / 原所属快照连接
     * @throws SQLException if the transaction cannot be configured / 事务无法设置时
     * @throws ApiException if original Schema access fails / 原Schema访问失败时 */
    private Connection open(TaskContext task) throws SQLException, ApiException {
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        var connection = database.openConnection(task);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        return connection;
    }

    /** Reconstructs only an ACTIVE release and its complete original graph. / 仅重建活动发布及其完整原图。
     * @param connection original snapshot transaction / 原快照事务
     * @param id selected application identity / 所选应用身份
     * @param task authenticated original task / 已认证原任务
     * @return complete validated application / 完整已校验应用
     * @throws SQLException if persisted identities differ / 持久身份不符时
     * @throws IOException if complete archive encoding fails / 完整归档编码失败时
     * @throws ApiException if original evidence access fails / 原依据访问失败时 */
    private ApplicationFacts read(Connection connection, String id, TaskContext task)
        throws SQLException, IOException, ApiException {
        DeploymentReleaseRecord record = null;
        try (var query = connection
            .prepareStatement("SELECT * FROM deployment_release WHERE application_id=? AND state='ACTIVE'")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (record != null)
                        throw new SQLException("Multiple active releases");
                    record = release(rows);
                }
            }
        }
        if (record == null)
            throw problem("DEPLOYMENT_APPLICATION_NOT_PUBLISHED");
        gold.debug.windowstolinux.mod.deployment.db.check.DeploymentReleaseChecker.validateRecord(connection, record);
        DeploymentReleaseSnapshot release = releases.snapshot(record);
        var graph = release.graph();
        if (!graph.applicationId().equals(id))
            throw new SQLException("Release application differs");
        var server = graph.components().getFirst().unit().server();
        String display = id;
        var observations = new HashMap<String, Observation>();
        Instant observed = record.createdAt();
        for (var component : graph.components()) {
            try (var query = connection.prepareStatement(
                "SELECT current_release_sha256,runtime_state,is_ownership_verified,display_name,observed_at,ownership_manifest_sha256,server_profile_id,systemd_unit_name,release_root_path,runtime_payload,autostart_state FROM deployment_unit WHERE deployment_unit_id=?")) {
                query.setString(1, component.unit().id());
                try (var rows = query.executeQuery()) {
                    if (!rows.next())
                        throw new SQLException("Published unit is absent");
                    var binding = release.bindings().stream()
                        .filter(value -> value.unitId().equals(component.unit().id())).findFirst().orElseThrow();
                    if (!binding.releaseSha256().equals(rows.getString(1)))
                        throw new SQLException("Current unit differs from release");
                    var unit = component.unit();
                    if (!unit.ownershipManifestSha256().equals(rows.getString(6))
                        || !unit.server().id().equals(rows.getString(7))
                        || !unit.systemdUnit().equals(rows.getString(8)) || !unit.releaseRoot().equals(rows.getString(9))
                        || !component.runtime().equals(new DeploymentUnitRuntimeCodec().read(bounded(rows, "runtime_payload", 16 * 1_048_576))))
                        throw new SQLException("Current component identity differs from release");
                    String runtimeState = rows.getString(2);
                    boolean owned = rows.getObject(3) != null && rows.getInt(3) == 1;
                    observations.put(unit.id(), new Observation(state(runtimeState, owned), autostart(rows.getString(11), owned)));
                    String name = rows.getString(4);
                    if (name != null && component.componentId().equals(graph.healthComponentId()))
                        display = name;
                    if (rows.getObject(5) != null) {
                        var at = Instant.ofEpochMilli(rows.getLong(5));
                        if (at.isAfter(observed))
                            observed = at;
                    }
                }
            }
        }
        var bindings = DeploymentApplicationIdentity.bindings(graph);
        var snapshot = new DeploymentApplicationSnapshot(new DeploymentApplicationId(id, server), display,
            record.identity().sha256(), DeploymentApplicationIdentity.ownership(graph), overall(observations), observed, archived(release, record, task),
            bindings, DeploymentApplicationIdentity.sha256(new DeploymentHealthCodec().write(graph.healthCheck())),
            ApplicationAutostartState.combine(observations.values().stream().map(Observation::autostart).toList()));
        return new ApplicationFacts(snapshot, graph, release, Map.copyOf(observations));
    }

    /** Captures every original typed configuration and nonsecret credential purpose. / 捕获每份原类型化配置与非秘密凭据用途。
     * @param release complete canonical original release / 完整规范原发布
     * @param record original release identity and time / 原发布身份及时间
     * @param task authenticated original task / 已认证原任务
     * @return bounded complete archive / 有界完整归档
     * @throws SQLException if original configuration or metadata differs / 原配置或元数据不符时
     * @throws IOException if the complete archive exceeds its bound / 完整归档超出界限时
     * @throws ApiException if original metadata access fails / 原元数据访问失败时 */
    private VersionedRuntime archived(DeploymentReleaseSnapshot release, DeploymentReleaseRecord record,
                                      TaskContext task) throws SQLException, IOException, ApiException {
        var configurations = new DeploymentConfigStore(database);
        var credentials = new DeploymentSecretStore(database);
        var commits = new ArrayList<DeploymentUnitCommit>();
        var metadata = new java.util.LinkedHashMap<DeploymentSecretRef, DeploymentSecretRevision>();
        for (var component : release.graph().components()) {
            var binding = release.bindings().stream().filter(value -> value.unitId().equals(component.unit().id()))
                .findFirst().orElseThrow();
            var configuration = configurations.find(task, component.unit().id(), binding.configurationRevision())
                .orElseThrow(() -> new SQLException("Published configuration is absent"));
            if (!configuration.sha256().equals(binding.configurationSha256()))
                throw new SQLException("Published configuration changed");
            commits.add(new DeploymentUnitCommit(component.unit(), component.runtime(),
                new DeploymentCurrentRelease(component.unit().id(), binding.releaseSha256(), record.createdAt()),
                configuration, binding.secrets()));
            for (var secret : binding.secrets())
                metadata.putIfAbsent(secret, credentials.findRevision(task, secret)
                    .orElseThrow(() -> new SQLException("Published credential metadata is absent")));
        }
        var proposal = new DeploymentPublishProposal(release.graph(), commits);
        if (!DeploymentReleaseSnapshot.capture(proposal.graph(), proposal.units()).equals(release))
            throw new SQLException("Published complete archive differs");
        return DeploymentArchivedRuntimeCodec.encode(proposal, List.copyOf(metadata.values()), releases);
    }

    /** Decodes one retained release without consulting another Schema. / 不读取其他Schema而解码一项保留发布。
     * @param rows selected original release row / 所选原发布行
     * @return complete original release record / 完整原发布记录
     * @throws SQLException if identity or payload is invalid / 身份或载荷无效时 */
    private static DeploymentReleaseRecord release(ResultSet rows) throws SQLException {
        try {
            return new DeploymentReleaseRecord(rows.getString("application_id"),
                new DeploymentReleaseIdentity(rows.getObject("release_id", java.util.UUID.class),
                    rows.getString("release_sha256")),
                rows.getObject("execution_id", java.util.UUID.class),
                DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE,
                new String(bounded(rows, "runtime_payload", 16 * 1_048_576), StandardCharsets.UTF_8),
                new String(bounded(rows, "health_payload", 1_048_576), StandardCharsets.UTF_8),
                Instant.ofEpochMilli(rows.getLong("created_at")));
        } catch (IOException | IllegalArgumentException failure) {
            throw new SQLException("Stored release is invalid", failure);
        }
    }

    /** Reads one non-null BLOB under the original format bound. / 在原格式界限内读取一项非空BLOB。
     * @param rows selected original row / 所选原行
     * @param column original payload column / 原载荷列
     * @param limit complete payload byte bound / 完整载荷字节界限
     * @return complete bounded bytes / 完整有界字节
     * @throws SQLException if the original column cannot be read / 原列无法读取时
     * @throws IOException if the payload is absent or oversized / 载荷缺失或过大时 */
    private static byte[] bounded(ResultSet rows, String column, int limit) throws SQLException, IOException {
        try (var stream = rows.getBinaryStream(column)) {
            if (stream == null)
                throw new IOException("Release payload is absent");
            byte[] value = stream.readNBytes(limit + 1);
            if (value.length > limit)
                throw new IOException("Release payload exceeds its format bound");
            return value;
        }
    }

    /** Maps persisted observations conservatively. / 保守映射持久观察。
     * @param runtime persisted runtime observation / 持久运行观察
     * @param owned verified original ownership / 已核实原归属
     * @return conservative saved state / 保守保存状态 */
    private static ApplicationRunState state(String runtime, boolean owned) {
        if (!owned || runtime == null)
            return ApplicationRunState.UNKNOWN;
        return switch (runtime) {
            case "RUNNING" -> ApplicationRunState.RUNNING;
            case "STOPPED" -> ApplicationRunState.STOPPED;
            case "INSTALLED" -> ApplicationRunState.INSTALLED;
            case "ERROR", "DEGRADED" -> ApplicationRunState.DEGRADED;
            default -> ApplicationRunState.UNKNOWN;
        };
    }

    /** Combines daemon state while retaining installed on-demand availability. / 合并守护进程状态并保留已安装按需可用性。
     * @param observations exact complete component facts / 精确完整组件事实
     * @return conservative aggregate / 保守汇总 */
    static ApplicationRunState overall(Map<String, Observation> observations) {
        var states = observations.values().stream().map(Observation::state).toList();
        if (states.contains(ApplicationRunState.UNKNOWN))
            return ApplicationRunState.UNKNOWN;
        if (states.contains(ApplicationRunState.DEGRADED)
            || states.contains(ApplicationRunState.RUNNING) && states.contains(ApplicationRunState.STOPPED))
            return ApplicationRunState.DEGRADED;
        return states.contains(ApplicationRunState.RUNNING) ? ApplicationRunState.RUNNING
            : states.contains(ApplicationRunState.STOPPED) ? ApplicationRunState.STOPPED : ApplicationRunState.INSTALLED;
    }

    /** Keeps missing saved automatic-startup evidence unknown. / 将缺失的已保存自动启动依据保持为未知。
     * @param value saved observed value / 保存的观测值
     * @param owned verified original ownership / 已核实原归属
     * @return exact supported observation or unknown / 精确支持的观察或未知 */
    private static ApplicationAutostartState autostart(String value, boolean owned) {
        if (!owned || value == null)
            return ApplicationAutostartState.UNKNOWN;
        try {
            return ApplicationAutostartState.valueOf(value);
        } catch (IllegalArgumentException unsupported) {
            return ApplicationAutostartState.UNKNOWN;
        }
    }

    /** Copies reviewed file and database bindings from the full release graph. / 从完整发布图复制已审阅文件和数据库绑定。
     * @param graph complete reviewed graph / 完整已审阅图
     * @return complete component-qualified bindings / 完整带组件身份的绑定 */

    /** Refuses an unknown binding instead of fabricating a physical path. / 拒绝未知绑定而不捏造物理路径。
     * @param connection unsupported original connection / 不支持的原连接
     * @return no value because the connection is rejected / 因拒绝连接而无返回值
     * @throws IllegalArgumentException for the unsupported connection / 连接不受支持时 */

    /** Hashes complete ordered ownership facts. / 哈希完整有序归属事实。
     * @param graph complete original graph / 完整原图
     * @return exact ownership digest / 精确归属摘要 */

    /** Computes SHA-256 using the JDK. / 使用JDK计算SHA-256。
     * @param value complete original bytes / 完整原字节
     * @return lowercase complete digest / 小写完整摘要 */

    /** Exposes bounded public failure without leaking SQL or secret content. / 不泄漏SQL或秘密内容的有界公开失败。
     * @param event stable evidence failure / 稳定依据失败
     * @return bounded original evidence exception / 有界原依据异常 */
    private static ApiException problem(String event) {
        return new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.INCOMPATIBLE, event,
            "Deployment application evidence cannot be confirmed", java.util.Map.of(), Optional.empty()));
    }

    /** Full original facts retained only inside deployment service. / 仅在部署服务内部保留的完整原事实。
     * @param snapshot public copy / 公开副本
     * @param graph original graph / 原图
     * @param release original active release / 原活动发布
     * @param observations exact saved component observations / 精确保存组件观察 */
    public record ApplicationFacts(DeploymentApplicationSnapshot snapshot, DeploymentApplicationGraph graph,
                                   DeploymentReleaseSnapshot release, Map<String, Observation> observations) {
        public gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout() {
            var layout = graph.components().getFirst().unit().layout();
            if (graph.components().stream().anyMatch(value -> !value.unit().layout().equals(layout)))
                throw new IllegalArgumentException("Application component remote layouts differ");
            return layout;
        }

        /** Keeps restoration archives without asserting missing current observations. / 保留恢复归档而不宣称缺失的当前观察。
         * @param snapshot original archived public facts / 原归档公开事实
         * @param graph original complete graph / 原完整图
         * @param release original complete release / 原完整发布 */
        public ApplicationFacts(DeploymentApplicationSnapshot snapshot, DeploymentApplicationGraph graph,
                                DeploymentReleaseSnapshot release) {
            this(snapshot, graph, release, graph.components().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                value -> value.unit().id(), value -> new Observation(ApplicationRunState.UNKNOWN, ApplicationAutostartState.UNKNOWN))));
        }
    }

    /** One exact component observation without inferred enablement. / 一项不推断启用状态的精确组件观察。
     * @param state observed runtime state / 观测运行状态
     * @param autostart observed automatic startup / 观测自动启动 */
    public record Observation(ApplicationRunState state, ApplicationAutostartState autostart) {
    }
}
