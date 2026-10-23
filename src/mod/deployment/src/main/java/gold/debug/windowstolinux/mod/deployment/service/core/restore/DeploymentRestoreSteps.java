package gold.debug.windowstolinux.mod.deployment.service.core.restore;

import static gold.debug.windowstolinux.mod.deployment.service.core.restore.DeploymentRestoreEvidence.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentHandoffChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;

/** Original restore command identities and durable step receipts. / 原恢复命令身份及持久步骤回执。 */
final class DeploymentRestoreSteps {
    /** Prevents instances of the fixed original step functions. / 防止固定原步骤函数产生实例。 */
    private DeploymentRestoreSteps() {
    }

    /** Persists a step attempt before dispatch and never repeats an uncertain attempt. / 发送前持久保存步骤尝试，绝不重复未知尝试。
     * @param channel fixed original helper channel / 固定原helper频道
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param component original component ID / 原组件ID
     * @param key stable original step key / 稳定原步骤键
     * @param verb fixed original helper action / 固定原helper动作
     * @param args exact original arguments / 精确原参数
     * @param receipt exact complete expected receipt / 精确完整预期回执
     * @return whether the original step is confirmed / 是否原步骤已确证
     * @throws Exception if original identity or persistence is unavailable / 原身份或持久化不可用时 */
    static boolean step(DeploymentHandoffChannel channel, DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                        String component, String key, String verb, List<String> args, String receipt) throws Exception {
        var steps = cursor.unit(component).withObject("steps");
        if (steps.path(key).path("attempted").asBoolean())
            return stepObserved(channel, cursor, plan, component, key, verb, args);
        steps.putObject(key).put("attempted", true).put("done", false).put("receipt", receipt);
        cursor.save(cursor.entry.publicValue().state());
        var result = send(channel, "handoff-restore-step", stepArguments(plan, cursor, component, key, verb, args),
            Duration.ofMinutes(30));
        if (!DeploymentHandoffChannel.receipt(result, "RESTORE_STEP=DONE\n" + receipt))
            return false;
        steps.withObject(key).put("done", true);
        cursor.save(cursor.entry.publicValue().state());
        return true;
    }

    /** Reads the full original sealed receipt without dispatching the step again. / 读取完整原封印回执而不再次发送步骤。
     * @param channel fixed original helper channel / 固定原helper频道
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param component original component ID / 原组件ID
     * @param key stable original step key / 稳定原步骤键
     * @param verb fixed original helper action / 固定原helper动作
     * @param args exact original arguments / 精确原参数
     * @return whether the complete original receipt matches / 是否完整原回执匹配
     * @throws Exception if original identity or evidence is unavailable / 原身份或依据不可用时 */
    static boolean stepObserved(DeploymentHandoffChannel channel, DeploymentRestoreState cursor,
                                DeploymentRestorePlan plan, String component, String key, String verb, List<String> args) throws Exception {
        var saved = cursor.unit(component).path("steps").path(key);
        if (!saved.path("attempted").asBoolean() || saved.path("receipt").asText().isEmpty())
            return false;
        var result = send(channel, "handoff-restore-step-status",
            stepArguments(plan, cursor, component, key, verb, args), Duration.ofMinutes(2));
        if (!DeploymentHandoffChannel.receipt(result, "RESTORE_STEP=DONE\n" + saved.path("receipt").asText()))
            return false;
        cursor.unit(component).withObject("steps").withObject(key).put("done", true);
        cursor.save(cursor.entry.publicValue().state());
        return true;
    }

    /** Builds bounded native or image preparation arguments from the complete reviewed files. / 从完整已审阅文件构造有界原生或镜像准备参数。
     * @param plan complete immutable request / 完整不可变请求
     * @param cursor original authorized journal / 原已授权账本
     * @param component original component identity / 原组件身份
     * @return exact fixed preparation arguments / 精确固定准备参数 */
    static List<String> preparation(DeploymentRestorePlan plan, DeploymentRestoreState cursor,
                                    DeploymentApplicationGraph.Part component) {
        var inputs = plan.files.stream().filter(value -> value.artifact().componentId().equals(component.componentId()))
            .toList();
        var files = inputs.stream().filter(value -> value.artifact().kind().equals("file"))
            .map(DeploymentRestorePlan.FileInput::member).toList();
        var args = new ArrayList<>(List.of(
            cursor.unit(component.componentId()).path("candidate").asText(), token(cursor.task),
            component.componentId(), component.unit().id(), component.unit().ownershipManifestSha256(),
            plan.commit(component.componentId()).release().sha256(), inputs.stream()
                .filter(value -> value.artifact().kind().equals("release")).findFirst().orElseThrow().member(),
            Integer.toString(files.size())));
        args.addAll(files);
        args.add(inputs.stream().filter(value -> value.artifact().kind().equals("oci")).findFirst()
            .map(DeploymentRestorePlan.FileInput::member).orElse("-"));
        args.add("0");
        return List.copyOf(args);
    }

    /** Selects only this component's original database inputs. / 仅选择本组件原数据库输入。
     * @param plan complete immutable request / 完整不可变请求
     * @param component original component ID / 原组件ID
     * @return original database inputs / 原数据库输入 */
    static List<DeploymentRestorePlan.FileInput> databases(DeploymentRestorePlan plan, String component) {
        return plan.files.stream().filter(
                value -> value.artifact().componentId().equals(component) && value.artifact().kind().equals("database"))
            .toList();
    }

    /** Derives a distinct stable step key from the public full-graph binding identity. / 从公开全图绑定身份推导独立稳定步骤键。
     * @param input original database input / 原数据库输入
     * @return original database step key / 原数据库步骤键 */
    static String databaseKey(DeploymentRestorePlan.FileInput input) {
        return "db-" + UUID.nameUUIDFromBytes(input.file().bindingId().getBytes(StandardCharsets.UTF_8)).toString()
            .replace("-", "").substring(0, 16);
    }

    /** Derives one database candidate per original handoff and binding. / 每个原交接及绑定推导独立数据库候选。
     * @param cursor original authorized journal / 原已授权账本
     * @param component original component identity / 原组件身份
     * @param input original database input / 原数据库输入
     * @return stable original database candidate / 稳定原数据库候选 */
    static String databaseCandidate(DeploymentRestoreState cursor, DeploymentApplicationGraph.Part component,
                                    DeploymentRestorePlan.FileInput input) {
        return component.unit().id() + "-"
            + UUID.nameUUIDFromBytes(
                (cursor.entry.publicValue().id() + input.file().bindingId()).getBytes(StandardCharsets.UTF_8))
            .toString().replace("-", "").substring(0, 16);
    }

    /** Derives an isolated imported artifact identity for the original binding. / 为原绑定推导隔离导入产物身份。
     * @param cursor original authorized journal / 原已授权账本
     * @param input original database input / 原数据库输入
     * @return stable original imported artifact / 稳定原导入产物 */
    static String databaseArtifact(DeploymentRestoreState cursor, DeploymentRestorePlan.FileInput input) {
        return "db-"
            + UUID.nameUUIDFromBytes((cursor.entry.publicValue().id() + ":artifact:" + input.file().bindingId())
            .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    /** Reuses database recovery arguments with this binding's isolated candidate and artifact. / 以本绑定隔离候选与产物复用数据库恢复参数。
     * @param cursor original authorized journal / 原已授权账本
     * @param component original component identity / 原组件身份
     * @param input original database input / 原数据库输入
     * @return exact original database arguments / 精确原数据库参数 */
    static List<String> databaseArguments(DeploymentRestoreState cursor, DeploymentApplicationGraph.Part component,
                                          DeploymentRestorePlan.FileInput input) {
        var args = new ArrayList<>(List.of(component.unit().id(), component.unit().id(),
            databaseCandidate(cursor, component, input), databaseArtifact(cursor, input)));
        var collected = input.artifact().databaseArguments();
        args.addAll(collected.subList(3, collected.size()));
        return List.copyOf(args);
    }

    /** Binds each helper operation to the original task, target review and main candidate. / 将每项helper操作绑定原任务、目标审阅与主候选。
     * @param plan complete immutable request / 完整不可变请求
     * @param cursor original authorized journal / 原已授权账本
     * @param component original component ID / 原组件ID
     * @return full immutable original operation identity / 完整不可变原操作身份 */
    static List<String> identity(DeploymentRestorePlan plan, DeploymentRestoreState cursor, String component) {
        var unit = plan.proposal.graph().components().stream().filter(value -> value.componentId().equals(component))
            .findFirst().orElseThrow().unit();
        return List.of(unit.id(), cursor.unit(component).path("candidate").asText(), cursor.task.id().toString(),
            plan.request.reviewedTargetSha256());
    }

    /** Seals the complete original operation identity together with its exact request. / 将完整原操作身份与精确请求共同封印。
     * @param plan complete immutable request / 完整不可变请求
     * @param cursor original authorized journal / 原已授权账本
     * @param component original component ID / 原组件ID
     * @param key stable original step key / 稳定原步骤键
     * @param verb fixed original helper action / 固定原helper动作
     * @param args exact original arguments / 精确原参数
     * @return full immutable original step request / 完整不可变原步骤请求 */
    static List<String> stepArguments(DeploymentRestorePlan plan, DeploymentRestoreState cursor, String component,
                                      String key, String verb, List<String> args) {
        var all = new ArrayList<>(identity(plan, cursor, component));
        all.add(key);
        all.add(verb);
        all.addAll(args);
        return List.copyOf(all);
    }

    /** Appends fixed arguments without mutating the reviewed original list. / 不修改已审阅原列表地追加固定参数。
     * @param values original arguments / 原参数
     * @param extra fixed additional arguments / 固定附加参数
     * @return independent complete arguments / 独立完整参数 */
    static List<String> append(List<String> values, String... extra) {
        var result = new ArrayList<>(values);
        result.addAll(List.of(extra));
        return List.copyOf(result);
    }

    /** Resolves the fixed controlled root of the original candidate. / 解析原候选固定受控根路径。
     * @param candidate original candidate ID / 原候选ID
     * @return controlled original candidate path / 受控原候选路径 */
    static String root(String candidate) {
        return "/var/lib/windowstolinux/candidates/" + candidate;
    }

    /** Encodes the original task UUID for existing restore helpers. / 为既有恢复helper编码原任务UUID。
     * @param task authentic original task / 真实原任务
     * @return original restore token / 原恢复令牌 */
    static String frozenRoot(DeploymentRestoreState cursor, DeploymentRestorePlan plan, String component) {
        return candidateRoot(cursor.unit(component), plan.layout);
    }

    static String candidateRoot(com.fasterxml.jackson.databind.JsonNode unit,
                                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        String candidate = unit.path("candidate").asText();
        if (!unit.has("candidatePathVersion")) {
            if (!layout.equals(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()))
                throw new IllegalArgumentException("Legacy restore requires its original layout");
            return root(candidate);
        }
        if (!unit.path("candidatePathVersion").isInt() || unit.path("candidatePathVersion").intValue() != 2
            || !unit.path("candidateRoot").isTextual()
            || !layout.candidateRoot(candidate).equals(unit.path("candidateRoot").textValue()))
            throw new IllegalArgumentException("Original restore candidate root differs");
        return unit.path("candidateRoot").textValue();
    }

    static String token(TaskContext task) {
        return task.id().toString().replace("-", "");
    }

    /** Sends one bounded fixed command under exact provider-owned review. / 在精确提供方所属审核下发送有界固定命令。
     * @param channel original helper channel / 原helper频道
     * @param verb fixed original helper action / 固定原helper动作
     * @param args exact original arguments / 精确原参数
     * @param timeout bounded deadline / 有界时限
     * @return actual remote command conclusion / 实际远端命令结论
     * @throws ApiException if current provider authority is unavailable / 当前提供方权限不可用时 */
    static OperationResult<CommandResult> send(DeploymentHandoffChannel channel, String verb, List<String> args,
                                               Duration timeout) throws ApiException {
        return channel.execute(verb, args, new byte[0], timeout, 65536);
    }

    /** Uses the validated graph's dependency order for original effects and compensation. / 以已校验图的依赖顺序处理原副作用与补偿。
     * @param graph validated original graph / 已校验原图
     * @return dependency-ordered original components / 按依赖排序的原组件 */
    static List<DeploymentApplicationGraph.Part> order(DeploymentApplicationGraph graph) {
        var result = new ArrayList<DeploymentApplicationGraph.Part>();
        var ids = new HashSet<String>();
        while (result.size() < graph.components().size())
            for (var component : graph.components())
                if (!ids.contains(component.componentId()) && ids.containsAll(component.dependencies())) {
                    result.add(component);
                    ids.add(component.componentId());
                }
        return List.copyOf(result);
    }

    /** Dispatches prepared graph effects only through original sealed steps. / 仅经原封印步骤发送已准备应用图效果。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel original sealed helper channel / 原封印helper频道
     * @return verified activation steps or retained uncertainty / 已确证激活步骤或留存未知
     * @throws Exception if original identity or effect evidence is unavailable / 原身份或副作用依据不可用时 */
    static OperationResult<Void> activatePrepared(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                                                  DeploymentHandoffChannel channel) throws Exception {
        var before = before(cursor);
        for (var component : order(plan.proposal.graph()).reversed()) {
            if (!targets(plan.proposal.graph(), channel).equals(before))
                return cursor.uncertain("DEPLOYMENT_RESTORE_CURRENT_CHANGED");
            if (!step(channel, cursor, plan, component.componentId(), "snapshot", "restore-snapshot", append(
                    List.of(cursor.unit(component.componentId()).path("candidate").asText(), token(cursor.task),
                        component.componentId()),
                    before.get(component.componentId()).equals("-") ? "empty" : before.get(component.componentId())),
                "SNAPSHOT=1\nPREVIOUS=" + (before.get(component.componentId()).equals("-") ? "0" : "1") + "\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_SNAPSHOT_UNKNOWN");
        }
        for (var component : plan.proposal.graph().components()) {
            String candidate = cursor.unit(component.componentId()).path("candidate").asText();
            if (!step(channel, cursor, plan, component.componentId(), "quiesced", "restore-mark-quiesced",
                List.of(candidate, token(cursor.task), "1", component.componentId()), "QUIESCED=1\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_QUIESCENCE_UNKNOWN");
        }
        for (var component : order(plan.proposal.graph()))
            for (var input : databases(plan, component.componentId())) {
                if (!targets(plan.proposal.graph(), channel).equals(before))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_CURRENT_CHANGED");
                if (!step(channel, cursor, plan, component.componentId(), "commit-" + databaseKey(input),
                    "database-commit-candidate", databaseArguments(cursor, component, input), "CANDIDATE_ID="
                        + databaseCandidate(cursor, component, input) + "\nCOMMITTED=1\nPREVIOUS_RETAINED=1\n"))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_DATABASE_COMMIT_UNKNOWN");
            }
        for (var component : order(plan.proposal.graph())) {
            String candidate = cursor.unit(component.componentId()).path("candidate").asText();
            if (!step(channel, cursor, plan, component.componentId(), "formal", "restore-start-formal",
                List.of(candidate, token(cursor.task), component.componentId()),
                "FORMAL_STARTED=1\nAPP=" + component.unit().id() + "\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_FORMAL_START_UNKNOWN");
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }
}
