package gold.debug.windowstolinux.mod.deployment.linux.command;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;

/** Original task-bound model command runner with durable receipt reconciliation. / 带持久回执核对的原任务绑定模型命令执行器。 */
public final class DeploymentModelCommandRunner {
    /** Original task checkpoint key for model command receipts. / 模型命令回执的原任务检查点键。 */
    public static final String KEY = "deployment-model-commands";

    /** Strict duplicate and trailing-token rejecting JSON codec. / 拒绝重复字段及尾随内容的严格 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentModelCommandRunner() {
    }

    /** Executes one independently reviewed command under the original task authority. / 在原任务授权下执行一项独立审核命令。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @param server exact original target server / 精确原目标服务器
     * @param task original admitted task / 原已准入任务
     * @param revision captured original model revision / 已捕获原模型修订
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @param seconds bounded command timeout in seconds / 有界命令超时秒数
     * @param reason bounded evidence-supported review explanation / 有界依据支持的审核说明
     * @return classified original command outcome / 分类原命令结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static String execute(ModContext context, RemoteSession session, ServerId server, TaskContext task,
                                 String revision, String script, int seconds, String reason) throws Exception {
        return execute(context, session, server, task, revision, script, seconds, reason, null);
    }

    /** Executes the same canonical envelope identity already selected by the original caller. / 执行原调用者已经选定的同一规范封套身份。
     * @param context original Mod capabilities / 原Mod能力
     * @param session original task-bound session / 原任务绑定会话
     * @param server original server / 原服务器
     * @param task original task / 原任务
     * @param revision original model revision / 原模型修订
     * @param script original executable body / 原可执行正文
     * @param seconds original command timeout / 原命令超时
     * @param reason original rationale / 原说明
     * @param operation selected never-used operation / 已选择且从未使用的操作
     * @param layoutSha256 original captured layout digest / 原捕获目录摘要
     * @param envelopeSha256 complete actual envelope digest / 完整实际封套摘要
     * @return original reviewed command feedback / 原已审命令反馈
     * @throws Exception if binding, admission or receipt cannot be confirmed / 无法确认绑定、准入或回执时 */
    public static String executeBound(ModContext context, RemoteSession session, ServerId server, TaskContext task,
                                      String revision, String script, int seconds, String reason, UUID operation,
                                      String layoutSha256, String envelopeSha256) throws Exception {
        if (operation == null || layoutSha256 == null || !layoutSha256.matches("[0-9a-f]{64}")
            || envelopeSha256 == null || !envelopeSha256.matches("[0-9a-f]{64}"))
            throw new SecurityException("Original model command envelope identity is invalid");
        return execute(context, session, server, task, revision, script, seconds, reason,
            new BoundIdentity(operation, layoutSha256, envelopeSha256));
    }

    private record BoundIdentity(UUID operation, String layoutSha256, String envelopeSha256) {
    }

    private static String execute(ModContext context, RemoteSession session, ServerId server, TaskContext task,
                                  String revision, String script, int seconds, String reason, BoundIdentity bound) throws Exception {
        DeploymentCommandStaticGate.requireFixedAllowed(script);
        if (script.length() > 24000 || seconds < 1 || seconds > 7200 || reason.isBlank() || reason.length() > 2000)
            throw new IllegalArgumentException("Invalid model command limits or rationale");
        var operation = bound == null ? UUID.randomUUID() : bound.operation();
        String body = DeploymentModelCommandIdentity.digest(script);
        String boundExecutable = null;
        if (bound != null) {
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            if (!layout.digest().equals(bound.layoutSha256()))
                throw new SecurityException("Original model command layout changed");
            boundExecutable = DeploymentModelCommandIdentity.envelope(task.id(), operation, body, script, reason, layout);
            if (!DeploymentModelCommandIdentity.digest(boundExecutable).equals(bound.envelopeSha256()))
                throw new SecurityException("Original model command envelope changed");
        }
        var retained = context.tasks().findCheckpoint(task, KEY);
        TaskCheckpoint checkpoint;
        com.fasterxml.jackson.databind.node.ObjectNode facts;
        if (retained.isEmpty()) {
            facts = JSON.createObjectNode().put("version", 1).put("task", task.id().toString())
                .put("server", server.id()).put("fingerprint", session.facts().fingerprint()).put("modelRevision", revision);
            facts.putArray("commands");
            checkpoint = context.tasks().beginCheckpoint(task, KEY, JSON.writeValueAsString(facts));
        } else {
            checkpoint = retained.orElseThrow();
            facts = read(checkpoint, task);
            if (checkpoint.snapshot().released() || !facts.path("server").asText().equals(server.id())
                || !facts.path("fingerprint").asText().equals(session.facts().fingerprint())
                || !facts.path("modelRevision").asText().equals(revision))
                throw new SecurityException("Original model command target changed");
        }
        var commands = (com.fasterxml.jackson.databind.node.ArrayNode) facts.get("commands");
        if (bound != null && commands.findValuesAsText("operation").contains(operation.toString()))
            throw new SecurityException("Original model command operation was already used");
        if (commands.size() >= 16 || commands.findValuesAsText("phase").stream().anyMatch("DISPATCHED"::equals))
            throw new IllegalStateException("Original model command remains unsettled or budget exhausted");
        int totalSeconds = seconds;
        for (var previous : commands) totalSeconds = Math.addExact(totalSeconds, previous.path("seconds").asInt());
        if (totalSeconds > 7200) throw new IllegalArgumentException("Model command total time budget exhausted");
        var entry = commands.addObject().put("operation", operation.toString()).put("body", body).put("seconds", seconds).put("phase", "INTENT");
        checkpoint.advance(checkpoint.snapshot(), JSON.writeValueAsString(facts));
        byte[] envelope = (boundExecutable == null ? DeploymentModelCommandIdentity.envelope(task.id(), operation, body, script, reason,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task)) : boundExecutable).getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest(DeploymentModelCommandIdentity.LAUNCHER, envelope,
            Duration.ofSeconds(seconds), 65536)) {
            var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(
                "deployment.model.command.v1", Set.of(new ActionTarget(server.id(), "task", task.id().toString()),
                new ActionTarget(server.id(), "deployment-recovery-service",
                    gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.UNIT_PATH))));
            task.progress("AI_COMMAND_PROPOSED operation=" + operation + " sha256=" + body + " rationale=" + reason, Optional.empty());
            var grant = DeploymentCommandReviewRouter.approve(context.interaction(), intent, request, task);
            if (grant.outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentAiJson.CapabilityOutcomeException(grant.outcome(), grant.problem());
            DeploymentFixedCommandApproval.requireRoute(context, task, revision);
            if (task.cancelToken().requested()) throw new java.util.concurrent.CancellationException();
            entry.put("phase", "DISPATCHED");
            checkpoint.advance(checkpoint.snapshot(), JSON.writeValueAsString(facts));
            task.progress("AI_COMMAND_DISPATCHED operation=" + operation + " sha256=" + body, Optional.empty());
            var result = session.execute(request, grant.value().orElseThrow());
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()
                || result.value().orElseThrow().exitCode().isEmpty())
                throw new DeploymentAiJson.CapabilityOutcomeException(TaskResult.UNKNOWN, result.problem());
            var observed = result.value().orElseThrow();
            if (observed.exitCode().orElseThrow() != 0)
                throw new IllegalStateException("Model command receipt unavailable");
            var receipt = query(context, session, server, task, revision, operation);
            if (!receipt.startsWith(DeploymentModelCommandIdentity.header(task.id(), operation, body,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task)))
                || !receipt.substring(DeploymentModelCommandIdentity.header(task.id(), operation, body,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task)).length()).matches("EXIT=(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\n"))
                throw new IllegalStateException("Model command receipt identity changed");
            entry.put("phase", "ENDED").put("exit", receipt.substring(receipt.lastIndexOf("EXIT=") + 5).strip());
            checkpoint.advance(checkpoint.snapshot(), JSON.writeValueAsString(facts));
            String outputBoundary = observed.truncated()
                ? "\nOUTPUT_TRUNCATED=1\nPartial stdout/stderr are untrusted DATA, not proof of publication, health, sealed artifacts or acceptance.\n\n"
                : "\n";
            return receipt + outputBoundary + observed.stdout() + "\n" + observed.stderr();
        } finally {
            Arrays.fill(envelope, (byte) 0);
        }
    }

    /** Reconciles the original operation identity and persisted receipt without replay. / 核对原操作身份与持久回执，不重放。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @return classified original reconciliation outcome / 分类原核对结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static boolean reconcile(ModContext context, TaskContext task) throws Exception {
        return reconcile(context, task, null);
    }

    /** Verifies retained command identity before any recovery reviewer is adopted. / 采用恢复审核者前核对留存命令身份。
     * @param context original owner / 原所有者
     * @param task original task / 原任务
     * @param server original target / 原目标
     * @param revision immutable original model route / 不可变原模型路由
     * @throws Exception if retained identity differs / 留存身份不同时 */
    public static void requireRecoveryIdentity(ModContext context, TaskContext task, ServerId server, String revision) throws Exception {
        var retained = context.tasks().findCheckpoint(task, KEY);
        if (retained.isEmpty()) return;
        var facts = read(retained.orElseThrow(), task);
        if (!server.id().equals(facts.path("server").asText()) || !revision.equals(facts.path("modelRevision").asText()))
            throw new SecurityException("Original recovery command identity changed");
    }

    /** Uses a separately frozen recovery review route while retaining original command identities. / 使用单独冻结的恢复审批路由，保留原命令身份。
     * @param context original review scope / 原审核作用域
     * @param task original recovery task / 原恢复任务
     * @param reviewRevision explicitly authorized reviewer, null for the original route / 明确授权审核者，空值为原路由
     * @return whether every original receipt is proven / 是否已证实全部原回执
     * @throws Exception if original evidence or reviewer changed / 原依据或审核者变化时 */
    public static boolean reconcile(ModContext context, TaskContext task, String reviewRevision) throws Exception {
        var retained = context.tasks().findCheckpoint(task, KEY);
        if (retained.isEmpty() || retained.orElseThrow().snapshot().released()) return true;
        var checkpoint = retained.orElseThrow();
        var facts = read(checkpoint, task);
        var server = new ServerId(facts.path("server").asText());
        if (facts.path("commands").findValuesAsText("phase").contains("DISPATCHED")) {
            var revision = facts.path("modelRevision").asText();
            var reviewer = reviewRevision == null ? revision : reviewRevision;
            DeploymentFixedCommandApproval.requireRoute(context, task, reviewer);
            try (var session = context.linux().connect(server, task)) {
                if (!session.facts().fingerprint().equals(facts.path("fingerprint").asText())) return false;
                DeploymentFixedCommandApproval.requireRoute(context, task, reviewer);
                for (var item : facts.path("commands")) {
                    if (!item.path("phase").asText().equals("DISPATCHED")) continue;
                    UUID operation = UUID.fromString(item.path("operation").asText());
                    String prefix = DeploymentModelCommandIdentity.header(task.id(), operation, item.path("body").asText(),
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
                    String receipt = query(context, session, server, task, revision, operation);
                    if (!receipt.startsWith(prefix) || !receipt.substring(prefix.length()).matches("EXIT=(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\n"))
                        return false;
                    ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("phase", "ENDED")
                        .put("exit", receipt.substring(receipt.lastIndexOf("EXIT=") + 5).strip());
                    checkpoint.advance(checkpoint.snapshot(), JSON.writeValueAsString(facts));
                }
            }
        }
        checkpoint.close();
        return true;
    }

    /** Releases the original session only after its outcome is known. / 仅在结果已知后释放原会话。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static void releaseKnown(ModContext context, TaskContext task) throws Exception {
        var retained = context.tasks().findCheckpoint(task, KEY);
        if (retained.isEmpty() || retained.orElseThrow().snapshot().released()) return;
        var checkpoint = retained.orElseThrow();
        var facts = read(checkpoint, task);
        if (facts.path("commands").findValuesAsText("phase").contains("DISPATCHED"))
            throw new IllegalStateException("Model command remains unknown");
        checkpoint.close();
    }

    /** Classifies an operation whose original remote outcome remains unconfirmed. / 分类原远端结果仍未核实的操作。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @return original UNKNOWN result / 原 UNKNOWN 结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static boolean unconfirmed(ModContext context, TaskContext task) throws Exception {
        var retained = context.tasks().findCheckpoint(task, KEY);
        return retained.isPresent() && !retained.orElseThrow().snapshot().released()
            && read(retained.orElseThrow(), task).path("commands").findValuesAsText("phase").contains("DISPATCHED");
    }

    /** Reviews and applies an exact configuration replacement in the original task. / 在原任务中审阅并应用精确配置替换。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param task original admitted task / 原已准入任务
     * @param previous original previous bound to this operation / 当前操作绑定的原previous值
     * @param selected retained release selected by the original review / 原审阅选择的留存发布
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static void replaceConfiguration(ModContext context, TaskContext task, String previous, String selected) throws Exception {
        if (!previous.matches("[0-9a-f]{64}") || !selected.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid model command route replacement");
        var retained = context.tasks().findCheckpoint(task, KEY);
        if (retained.isEmpty()) return;
        var checkpoint = retained.orElseThrow();
        var facts = read(checkpoint, task);
        if (checkpoint.snapshot().released() || !facts.path("modelRevision").asText().equals(previous)
            || facts.path("commands").findValuesAsText("phase").contains("DISPATCHED"))
            throw new IllegalStateException("Original model command route cannot be replaced");
        DeploymentFixedCommandApproval.requireRoute(context, task, selected);
        facts.put("modelRevision", selected);
        checkpoint.advance(checkpoint.snapshot(), JSON.writeValueAsString(facts));
    }

    /** Reads and validates the original recovery evidence without changing its identity. / 读取并校验原恢复依据，不改变身份。
     * @param checkpoint original checkpoint bound to this operation / 当前操作绑定的原checkpoint值
     * @param task original admitted task / 原已准入任务
     * @return validated original recovery evidence / 已校验原恢复依据
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static com.fasterxml.jackson.databind.node.ObjectNode read(TaskCheckpoint checkpoint, TaskContext task) throws Exception {
        var facts = JSON.readTree(checkpoint.snapshot().payload());
        DeploymentJsonFields.exact(facts, Set.of("version", "task", "server", "fingerprint", "modelRevision", "commands"));
        if (facts.path("version").asInt() != 1 || !facts.path("task").asText().equals(task.id().toString())
            || !facts.path("commands").isArray() || facts.path("commands").size() > 16
            || !facts.path("modelRevision").asText().matches("[0-9a-f]{64}")
            || facts.path("server").asText().isBlank() || facts.path("fingerprint").asText().isBlank())
            throw new SecurityException("Original model command journal invalid");
        int totalSeconds = 0;
        var operations = new HashSet<UUID>();
        for (var item : facts.path("commands")) {
            DeploymentJsonFields.exact(item, item.has("exit")
                ? Set.of("operation", "body", "seconds", "phase", "exit") : Set.of("operation", "body", "seconds", "phase"));
            if (!operations.add(UUID.fromString(item.path("operation").asText())))
                throw new SecurityException("Duplicate model command operation");
            if (!item.path("body").asText().matches("[0-9a-f]{64}")
                || !Set.of("INTENT", "DISPATCHED", "ENDED").contains(item.path("phase").asText())
                || !item.path("seconds").isInt() || item.path("seconds").intValue() < 1 || item.path("seconds").intValue() > 7200
                || item.has("exit") != item.path("phase").asText().equals("ENDED")
                || item.has("exit") && !item.path("exit").asText().matches("(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])"))
                throw new SecurityException("Original model command facts invalid");
            totalSeconds = Math.addExact(totalSeconds, item.path("seconds").asInt());
        }
        if (totalSeconds > 7200) throw new SecurityException("Original model command budget invalid");
        return (com.fasterxml.jackson.databind.node.ObjectNode) facts;
    }

    /** Derives finite receipt-read evidence from the original dispatched checkpoint and exact request. / 从原已发送检查点和精确请求派生有限回执读取依据。
     * @param context original owner / 原所有者
     * @param task original task / 原任务
     * @param intent exact status intent / 精确状态意图
     * @param request complete status request / 完整状态请求
     * @param layout captured original layout / 捕获的原目录
     * @return original journal-bound read scope, without the author body / 原账本绑定的读取范围，不含作者正文
     * @throws ApiException if original task evidence is unavailable / 原任务依据不可用时
     * @throws java.io.IOException if any status binding is invalid / 任一状态绑定无效时 */
    public static Map<String, Object> receiptStatusEvidence(ModContext context, TaskContext task, ActionIntent intent,
                                                            CommandRequest request,
                                                            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout)
        throws ApiException, java.io.IOException {
        try {
            var captured = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            var checkpoint = context.tasks().findCheckpoint(task, KEY).orElseThrow();
            var snapshot = checkpoint.snapshot();
            var facts = read(checkpoint, task);
            var server = new ServerId(facts.path("server").asText());
            var actual = RemoteActions.command(server, facts.path("modelRevision").asText(), request);
            var semantics = intent.semantics().orElseThrow();
            byte[] input = request.stdin();
            try {
                if (snapshot.released() || !snapshot.equals(checkpoint.snapshot()) || !captured.equals(layout)
                    || !intent.type().equals(actual.type()) || !intent.serverIds().equals(actual.serverIds())
                    || !intent.target().equals(actual.target()) || !intent.revision().equals(actual.revision())
                    || !intent.exactAction().equals(actual.exactAction()) || !intent.effectSummary().equals(actual.effectSummary())
                    || !semantics.operation().equals("deployment.model.command.status.v1")
                    || !semantics.targets().equals(Set.of(new ActionTarget(server.id(), "task", task.id().toString())))
                    || input.length != 0 || !request.timeout().equals(Duration.ofSeconds(30)) || request.outputLimitBytes() != 1024)
                    throw new IllegalArgumentException();
            } finally {
                Arrays.fill(input, (byte) 0);
            }
            com.fasterxml.jackson.databind.JsonNode matched = null;
            for (var entry : facts.path("commands")) {
                if (!entry.path("phase").asText().equals("DISPATCHED")) continue;
                UUID operation = UUID.fromString(entry.path("operation").asText());
                if (!operation.toString().equals(entry.path("operation").asText()))
                    throw new IllegalArgumentException();
                if (request.script().equals(DeploymentModelCommandIdentity.receiptStatus(task.id(), operation, entry.path("body").asText(), layout))) {
                    if (matched != null) throw new IllegalArgumentException();
                    matched = entry;
                }
            }
            if (matched == null) throw new IllegalArgumentException();
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("protocol", "MODEL_COMMAND_FILTERED_STATUS_1");
            evidence.put("task", task.id().toString());
            evidence.put("server", server.id());
            evidence.put("fingerprint", facts.path("fingerprint").asText());
            evidence.put("businessRevision", facts.path("modelRevision").asText());
            evidence.put("operation", matched.path("operation").asText());
            evidence.put("bodySha256", matched.path("body").asText());
            evidence.put("layoutSha256", layout.digest());
            evidence.put("receiptPath", layout.modelCommandOperationsRoot() + "/" + matched.path("operation").asText() + ".receipt");
            evidence.put("checkpointRevision", snapshot.revision());
            evidence.put("checkpointSha256", DeploymentModelCommandIdentity.digest(snapshot.payload()));
            evidence.put("localPhase", "DISPATCHED");
            evidence.put("remoteObserved", false);
            evidence.put("authority", "NONE");
            evidence.put("outputContract", Map.of(
                "read", "The exact reader compares bounded bytes with this original task/operation/body/layout header and only STARTED=1 or canonical EXIT=0..255 followed by one LF.",
                "output", "It reconstructs only those known ASCII fields; arbitrary receipt bytes and hex are never output.",
                "identityLimits", "Root ownership, single-link and before/after same-FD/path identity checks bind the observation, not absolute protection against concurrent trusted root changes.",
                "settlement", "Missing, malformed, changed or STARTED receipts do not settle the author operation.",
                "meaning", "This describes the implementation, not a remote observation or approval."));
            return Collections.unmodifiableMap(evidence);
        } catch (ApiException unavailable) {
            throw unavailable;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Original model command receipt binding is invalid");
        } catch (Exception invalid) {
            // Never retain parser messages, receipt bytes or input fragments. / 绝不保留解析消息、回执字节或输入片段。
            throw new java.io.IOException("Original model command receipt binding is invalid");
        }
    }

    /** Reads bounded protocol evidence for the original operation. / 读取原操作的有界协议依据。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @param server exact original target server / 精确原目标服务器
     * @param task original admitted task / 原已准入任务
     * @param revision captured original model revision / 已捕获原模型修订
     * @param operation original operation receipt identity / 原操作回执身份
     * @return bounded original operation protocol response / 有界原操作协议响应
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static String query(ModContext context, RemoteSession session, ServerId server, TaskContext task,
                                String revision, UUID operation) throws Exception {
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout;
        String script;
        try {
            layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            var checkpoint = context.tasks().findCheckpoint(task, KEY).orElseThrow();
            var original = read(checkpoint, task);
            var entry = original.path("commands").findParents("operation").stream()
                .filter(item -> item.path("operation").asText().equals(operation.toString())).findFirst().orElseThrow();
            script = DeploymentModelCommandIdentity.receiptStatus(task.id(), operation, entry.path("body").asText(), layout);
        } catch (ApiException unavailable) {
            throw unavailable;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Original model command receipt binding is invalid");
        } catch (Exception invalid) {
            throw new java.io.IOException("Original model command receipt binding is invalid");
        }
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 1024)) {
            var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(
                "deployment.model.command.status.v1", Set.of(new ActionTarget(server.id(), "task", task.id().toString()))));
            var binding = receiptStatusEvidence(context, task, intent, request, layout);
            if (!session.facts().fingerprint().equals(binding.get("fingerprint")))
                throw new SecurityException("Original receipt session changed");
            var grant = DeploymentCommandReviewRouter.approve(context.interaction(), intent, request, task);
            if (grant.outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentAiJson.CapabilityOutcomeException(grant.outcome(), grant.problem());
            if (!binding.equals(receiptStatusEvidence(context, task, intent, request, layout))
                || !session.facts().fingerprint().equals(binding.get("fingerprint")))
                throw new SecurityException("Original receipt evidence changed");
            if (task.cancelToken().requested()) throw new java.util.concurrent.CancellationException();
            var result = session.execute(request, grant.value().orElseThrow());
            if (!binding.equals(receiptStatusEvidence(context, task, intent, request, layout)))
                throw new SecurityException("Original receipt evidence changed");
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()
                || result.value().orElseThrow().truncated() || result.value().orElseThrow().exitCode().orElse(-1) != 0
                || !result.value().orElseThrow().stderr().isEmpty())
                throw new IllegalStateException("Original model command receipt unknown");
            return result.value().orElseThrow().stdout();
        }
    }

}
