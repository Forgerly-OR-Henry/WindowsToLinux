package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrap;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;

/** Retains the original publication policy and authenticated wire without consulting later preferences. / 保留原发布策略及认证 wire，不读取后续偏好。 */
public final class DeploymentPublishPolicyFacts {
    /** Original host checkpoint identity. / 原宿主检查点身份。 */
    public static final String KEY = "deployment-publication-policy";

    /** Strict original JSON framing. / 严格原 JSON 信封。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact persisted fields. / 精确持久字段。 */
    private static final Set<String> FIELDS = Set.of("formatVersion", "task", "owner", "ownerVersion", "server", "executionMode",
        "approvalMode", "rememberAuthorization", "modelRevision", "host", "port", "username", "initialFingerprint", "actualFingerprint");

    /** One separately bound installer inside the retained policy checkpoint. / 既有策略检查点内一项独立绑定安装。 */
    private static final String REPAIR = "helperRepair";

    /** Authenticated original checkpoint. / 已鉴权原检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Original owner capability. / 原所有者能力。 */
    private final ModContext context;

    /** Authentic original task. / 真实原任务。 */
    private final TaskContext task;

    /** Immutable non-secret original policy and connection facts. / 不可变非秘密原策略及连接事实。
     * @param owner original owner / 原所有者
     * @param ownerVersion original owner generation / 原所有者代次
     * @param executionMode original deterministic or assisted route / 原确定性或辅助路线
     * @param approvalMode original review policy / 原审批策略
     * @param rememberAuthorization original question repetition / 原问题重复规则
     * @param modelRevision original complete model route, empty for static / 原完整模型路由，静态为空
     * @param target original configured endpoint and initial trust / 原配置端点及初始信任
     * @param actualFingerprint first authenticated wire, absent before connection / 首次认证 wire，连接前为空 */
    public record State(String owner, String ownerVersion, String executionMode, String approvalMode,
                        boolean rememberAuthorization, String modelRevision, RemoteTarget target,
                        Optional<String> actualFingerprint) {
        /** Rejects invalid original policy and conflicting initial or actual trust. / 拒绝无效原策略及冲突初始或实际信任。
         * @param owner original owner / 原所有者
         * @param ownerVersion original owner generation / 原所有者代次
         * @param executionMode original route / 原路线
         * @param approvalMode original review policy / 原审批策略
         * @param rememberAuthorization original question repetition / 原问题重复规则
         * @param modelRevision original complete model route / 原完整模型路由
         * @param target original endpoint / 原端点
         * @param actualFingerprint first authenticated wire / 首次认证 wire */
        public State {
            Objects.requireNonNull(target);
            Objects.requireNonNull(actualFingerprint);
            DeploymentAuthorizationPolicy.requireAllowed(approvalMode, rememberAuthorization);
            if (owner == null || owner.isBlank() || owner.length() > 512 || ownerVersion == null || ownerVersion.isBlank()
                || ownerVersion.length() > 512 || !Set.of("STATIC", "ASSISTED").contains(executionMode)
                || modelRevision == null || modelRevision.length() > 512
                || executionMode.equals("STATIC") != modelRevision.isEmpty()
                || executionMode.equals("ASSISTED") && !modelRevision.matches("[0-9a-f]{64}")
                || actualFingerprint.filter(value -> !value.matches("SHA256:[A-Za-z0-9+/]{43}=?")).isPresent()
                || target.fingerprint().isPresent() && actualFingerprint.isPresent() && !target.fingerprint().equals(actualFingerprint))
                throw new IllegalArgumentException("Invalid original publication policy");
        }

        /** Pins every later connection to the first authenticated wire. / 将每次后续连接固定到首次认证 wire。
         * @return original exact connection expectation / 原精确连接预期 */
        public RemoteTarget fixedTarget() {
            return new RemoteTarget(target.server(), target.taskId(), target.host(), target.port(), target.username(),
                actualFingerprint.isPresent() ? actualFingerprint : target.fingerprint());
        }
    }

    /** Binds only a host-authenticated original checkpoint. / 仅绑定宿主鉴权原检查点。
     * @param checkpoint original checkpoint / 原检查点
     * @param context original owner / 原所有者
     * @param task original task / 原任务 */
    private DeploymentPublishPolicyFacts(TaskCheckpoint checkpoint, ModContext context, TaskContext task) {
        this.checkpoint = checkpoint;
        this.context = context;
        this.task = task;
    }

    /** Persists original choices before preparation or any remote connection. / 准备或任一远端连接前持久原选择。
     * @param context original owner / 原所有者
     * @param task authentic original task / 真实原任务
     * @param server original admitted server / 原准入服务器
     * @param mode original route / 原路线
     * @param approval original approval policy / 原审批策略
     * @param rememberAuthorization original repetition / 原重复规则
     * @param revision frozen complete model revision / 冻结完整模型修订
     * @return retained original facts / 留存原事实
     * @throws ApiException if original lookup or persistence fails / 原查询或持久化失败时 */
    public static DeploymentPublishPolicyFacts begin(ModContext context, TaskContext task, ServerId server,
                                                     String mode, String approval, boolean rememberAuthorization, String revision) throws ApiException {
        var info = context.servers().get(server, task);
        if (!info.ref().equals(server))
            throw new IllegalArgumentException("Original publication server lookup differs");
        var target = new RemoteTarget(server, task.id(), info.host(), info.port(), info.username(), info.trustedFingerprint());
        var facts = new State(context.identity().id(), context.identity().version(), mode, approval, rememberAuthorization, revision, target, Optional.empty());
        return new DeploymentPublishPolicyFacts(context.tasks().beginCheckpoint(task, KEY, encode(facts)), context, task);
    }

    /** Reads original facts only; missing evidence never selects the static route. / 仅读取原事实；缺失依据绝不选择静态路线。
     * @param context original owner / 原所有者
     * @param task authentic recovery task / 真实恢复任务
     * @return original checkpoint or absence / 原检查点或不存在
     * @throws ApiException if original checkpoint cannot be read / 原检查点无法读取时 */
    public static Optional<DeploymentPublishPolicyFacts> find(ModContext context, TaskContext task) throws ApiException {
        var original = context.tasks().findCheckpoint(task, KEY);
        if (original.isEmpty()) return Optional.empty();
        var journal = new DeploymentPublishPolicyFacts(original.orElseThrow(), context, task);
        journal.facts();
        return Optional.of(journal);
    }

    /** Checks the retained task and owner generation on each read. / 每次读取核对留存任务及所有者代次。
     * @return original validated values / 原已校验值
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public State facts() throws ApiException {
        var state = decode(checkpoint.snapshot().payload(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
        if (!task.id().equals(state.target().taskId()) || !context.identity().id().equals(state.owner())
            || !context.identity().version().equals(state.ownerVersion()))
            throw new IllegalArgumentException("Original publication owner or task differs");
        return state;
    }

    /** Records the first actual authenticated wire once and rejects every later change. / 一次记录首次实际认证 wire，拒绝每次后续变化。
     * @param fingerprint actual authenticated session key / 实际认证会话密钥
     * @throws ApiException if exact original persistence fails / 精确原持久化失败时 */
    public synchronized void authenticated(String fingerprint) throws ApiException {
        var before = checkpoint.snapshot();
        var state = facts();
        if (state.actualFingerprint().isPresent()) {
            if (!state.actualFingerprint().orElseThrow().equals(fingerprint))
                throw new IllegalArgumentException("Original publication wire changed");
            return;
        }
        var next = new State(state.owner(), state.ownerVersion(), state.executionMode(), state.approvalMode(), state.rememberAuthorization(),
            state.modelRevision(), state.target(), Optional.of(fingerprint));
        var payload = object(before.payload());
        var encoded = object(encode(next));
        if (payload.has(REPAIR)) encoded.set(REPAIR, payload.get(REPAIR));
        checkpoint.advance(before, encoded.toString());
    }

    /** Reads one existing repair without replacing its unknown effects. / 读取既有修复而不覆盖其未知效果。
     * @return original installer or absence / 原安装或不存在
     * @throws ApiException if retained facts are unavailable / 保留依据不可用时 */
    public Optional<DeploymentProtocolBootstrap.Journal> repair() throws ApiException {
        facts();
        var payload = object(checkpoint.snapshot().payload());
        return payload.has(REPAIR) ? Optional.of(repairJournal()) : Optional.empty();
    }

    /** Appends one exact helper repair to the existing unreleased task policy before dispatch. / 发送前向既有未释放任务策略追加一项精确helper修复。
     * @param initial complete new installer intent / 完整新安装意图
     * @return original repair journal / 原修复账本
     * @throws ApiException if original CAS fails / 原比较更新失败时 */
    public DeploymentProtocolBootstrap.Journal beginRepair(DeploymentProtocolBootstrapActionLog.Facts initial) throws ApiException {
        var before = checkpoint.snapshot();
        var state = facts();
        if (!initial.phase().equals("INTENT") || !initial.serverId().equals(state.target().server().id())
            || state.actualFingerprint().isEmpty())
            throw new IllegalArgumentException("Helper repair lacks the original authenticated target");
        var payload = object(before.payload());
        if (payload.has(REPAIR)) throw new IllegalArgumentException("Original helper repair already exists");
        payload.set(REPAIR, object(DeploymentProtocolBootstrapActionLog.encode(task.id(), initial)));
        checkpoint.advance(before, payload.toString());
        return repairJournal();
    }

    /** Narrows changes to the repair field while preserving all original policy facts. / 将变更限定在修复字段，保留全部原策略依据。
     * @return exact original installer journal / 精确原安装账本 */
    private DeploymentProtocolBootstrap.Journal repairJournal() {
        return new DeploymentProtocolBootstrap.Journal() {
            /** Returns immutable evidence bound to the original executable or journal. / 返回绑定原可执行输入或日志的不可变依据。
             * @return immutable original executable or journal evidence / 不可变原可执行输入或日志依据
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            public DeploymentProtocolBootstrapActionLog.Facts facts() throws ApiException {
                DeploymentPublishPolicyFacts.this.facts();
                return DeploymentProtocolBootstrapActionLog.decode(object(checkpoint.snapshot().payload()).path(REPAIR).toString(), task.id(),
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
            }

            /** Records dispatch of the original captured remote operation. / 记录已捕获原远端操作的派发。
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            public void dispatched() throws ApiException {
                advance("INTENT", "DISPATCHED");
            }

            /** Records the known terminal classification of the original operation. / 记录原操作的已知终态分类。
             * @param success original success bound to this operation / 当前操作绑定的原success值
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            public void terminal(boolean success) throws ApiException {
                advance("DISPATCHED", success ? "SUCCEEDED" : "FAILED");
            }

            /** Closes the original authorization scope using its captured terminal facts. / 按已捕获终态事实关闭原授权作用域。
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            public void close() throws ApiException {
                if (facts().phase().equals("DISPATCHED"))
                    throw new IllegalArgumentException("Original helper repair remains uncertain");
            }

            /** Persists the next captured policy phase on the original journal. / 在原日志持久化下一已捕获策略阶段。
             * @param expected reviewed active release digest / 已审阅活动发布摘要
             * @param next next verified state of the original operation / 原操作下一已核实状态
             * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
            private void advance(String expected, String next) throws ApiException {
                var before = checkpoint.snapshot();
                var value = facts();
                if (!value.phase().equals(expected))
                    throw new IllegalArgumentException("Original helper repair phase differs");
                var payload = object(before.payload());
                var changed = new DeploymentProtocolBootstrapActionLog.Facts(value.serverId(), value.operationId(), value.serverDigest(),
                    value.bodyDigest(), value.requestDigest(), value.helperDigest(), next, value.layout());
                payload.set(REPAIR, object(DeploymentProtocolBootstrapActionLog.encode(task.id(), changed)));
                checkpoint.advance(before, payload.toString());
            }
        };
    }

    /** Releases only after original effects and physical resources are settled. / 仅在原效果及物理资源已结清后释放。
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public void close() throws ApiException {
        var repair = repair();
        if (repair.isPresent() && repair.orElseThrow().facts().phase().equals("DISPATCHED"))
            throw new IllegalArgumentException("Original helper repair is not settled");
        checkpoint.close();
    }

    /** Encodes bounded non-secret original values. / 编码有界非秘密原值。
     * @param state original values / 原值
     * @return strict JSON object / 严格 JSON 对象 */
    private static String encode(State state) {
        var target = state.target();
        return JSON.createObjectNode().put("formatVersion", 1).put("task", target.taskId().toString()).put("owner", state.owner())
            .put("ownerVersion", state.ownerVersion()).put("server", target.server().id()).put("executionMode", state.executionMode())
            .put("approvalMode", state.approvalMode()).put("rememberAuthorization", state.rememberAuthorization()).put("modelRevision", state.modelRevision())
            .put("host", target.host()).put("port", target.port()).put("username", target.username())
            .put("initialFingerprint", target.fingerprint().orElse(null)).put("actualFingerprint", state.actualFingerprint().orElse(null)).toString();
    }

    /** Rejects substitutions, duplicate keys, extensions and trailing bytes. / 拒绝替代、重复键、扩展及尾随字节。
     * @param payload retained original JSON / 留存原 JSON
     * @return exact original values / 精确原值 */
    private static State decode(String payload, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        try {
            var node = JSON.readTree(payload);
            var names = new HashSet<String>();
            if (node == null || !node.isObject())
                throw new IllegalArgumentException("Invalid publication policy object");
            node.fieldNames().forEachRemaining(names::add);
            var extended = new HashSet<>(FIELDS);
            extended.add(REPAIR);
            if (!(names.equals(FIELDS) || names.equals(extended)) || !node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1
                || !node.path("port").isInt() || !node.path("rememberAuthorization").isBoolean())
                throw new IllegalArgumentException("Invalid publication policy framing");
            if (node.has(REPAIR))
                DeploymentProtocolBootstrapActionLog.decode(node.path(REPAIR).toString(), UUID.fromString(text(node, "task")), layout);
            var target = new RemoteTarget(new ServerId(text(node, "server")), UUID.fromString(text(node, "task")), text(node, "host"),
                node.path("port").intValue(), text(node, "username"), optional(node, "initialFingerprint"));
            return new State(text(node, "owner"), text(node, "ownerVersion"), text(node, "executionMode"), text(node, "approvalMode"),
                node.path("rememberAuthorization").booleanValue(), text(node, "modelRevision"), target, optional(node, "actualFingerprint"));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid publication policy JSON", invalid);
        }
    }

    /** Reads only a strict original JSON object. / 仅读取严格原JSON对象。
     * @param payload bounded retained payload / 有界保留载荷
     * @return original object / 原对象 */
    private static com.fasterxml.jackson.databind.node.ObjectNode object(String payload) {
        try {
            var node = JSON.readTree(payload);
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode value))
                throw new IllegalArgumentException("Invalid original policy object");
            return value;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid original policy JSON", invalid);
        }
    }

    /** Reads strict bounded text without coercion. / 不转换类型地读取严格有界文本。
     * @param node original object / 原对象
     * @param key exact field / 精确字段
     * @return original text / 原文本 */
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).textValue().length() > 512)
            throw new IllegalArgumentException("Invalid publication policy text");
        return node.path(key).textValue();
    }

    /** Preserves explicit first-use absence without accepting missing fields. / 保留明确首次使用空值，不接受缺失字段。
     * @param node original object / 原对象
     * @param key exact field / 精确字段
     * @return original optional trust / 原可选信任 */
    private static Optional<String> optional(JsonNode node, String key) {
        return node.path(key).isNull() ? Optional.empty() : Optional.of(text(node, key));
    }
}
