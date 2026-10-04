package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Explicit original-upload staging cleanup under the provider's own authority. / 在提供方自身权限下明确清理原上传暂存。 */
final class DeploymentRestoreTransfers {
    /** Actual provider capability binding, never supplied by the caller. / 实际提供方能力绑定，绝不由调用方提供。 */
    private final ModContext context;

    /** Binds the actual provider's Linux and interaction capabilities. / 绑定实际提供方的Linux与交互能力。
     * @param context active authenticated provider context / 活动已认证提供方上下文 */
    DeploymentRestoreTransfers(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Discards only fully matched original uploads; never resumes or changes their final targets. / 仅丢弃完整匹配的原上传，绝不续传或改变最终目标。
     * @param cursor original authorized handoff and task / 原已授权交接与任务
     * @param plan complete immutable original request / 完整不可变原请求
     * @param session authenticated provider session / 已认证提供方会话
     * @return whether every original pending transfer has confirmed cleanup / 是否全部原待处理传输已确证清理
     * @throws Exception if identity, approval, transport or persistence is unavailable / 身份、审批、传输或持久化不可用时 */
    boolean discard(DeploymentRestoreState cursor, DeploymentRestorePlan plan, RemoteSession session) throws Exception {
        var pending = context.linux().pendingTransfers(cursor.task);
        var owned = identify(cursor, plan, pending);
        for (var transfer : owned) {
            var file = cursor.unit(transfer.input().artifact().componentId()).withObject("files")
                .withObject(transfer.input().file().bindingId());
            file.put("transferId", transfer.checkpoint().id().toString())
                .put("transferOverwrite", transfer.checkpoint().overwrite()).put("transferDiscardAttempted", true);
            cursor.save(cursor.entry.publicValue().state());
            var approved = context.interaction().approve(
                RemoteActions.recoverTransfer(transfer.checkpoint(), plan.request.reviewedTargetSha256(), false),
                cursor.task);
            if (approved.outcome() != TaskResult.SUCCEEDED)
                return false;
            var discarded = session.discardTransfer(transfer.checkpoint().id(), approved.value().orElseThrow());
            if (discarded.outcome() != TaskResult.SUCCEEDED)
                return false;
            file.put("transferDiscarded", true);
            cursor.save(cursor.entry.publicValue().state());
        }
        return context.linux().pendingTransfers(cursor.task).isEmpty();
    }

    /** Validates every pending host record before permitting the first cleanup effect. / 允许首项清理副作用前校验全部待处理宿主记录。
     * @param cursor original authorized handoff and task / 原已授权交接与任务
     * @param plan complete immutable original request / 完整不可变原请求
     * @param pending host-certified original transfer descriptions / 宿主认证的原传输描述
     * @return exact original uploads admitted for cleanup / 获准清理的精确原上传
     * @throws IllegalArgumentException if any transfer or saved identity differs / 任一传输或保存身份不符时 */
    private static List<OriginalUpload> identify(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                                                 List<TransferCheckpoint> pending) {
        var owned = new ArrayList<OriginalUpload>();
        var bindings = new HashSet<String>();
        for (var checkpoint : pending) {
            var matches = plan.files.stream().filter(input -> matches(cursor, plan, input, checkpoint)).toList();
            if (matches.size() != 1)
                throw new IllegalArgumentException("Original transfer does not match one attempted restore file");
            var input = matches.getFirst();
            if (!bindings.add(input.file().bindingId()))
                throw new IllegalArgumentException("Repeated original restore transfer binding");
            var facts = cursor.unit(input.artifact().componentId()).path("files").path(input.file().bindingId());
            if (facts.has("transferId") && (!facts.path("transferId").isTextual()
                || !facts.path("transferId").textValue().equals(checkpoint.id().toString())))
                throw new IllegalArgumentException("Original restore transfer identity differs");
            if (facts.has("transferOverwrite") && (!facts.path("transferOverwrite").isBoolean()
                || facts.path("transferOverwrite").booleanValue() != checkpoint.overwrite()))
                throw new IllegalArgumentException("Original restore transfer choice differs");
            owned.add(new OriginalUpload(input, checkpoint));
        }
        return List.copyOf(owned);
    }

    /** Matches immutable handoff endpoints, budget, digest and the persisted upload attempt. / 匹配不可变交接端点、容量、摘要与持久上传尝试。
     * @param cursor original authorized handoff and task / 原已授权交接与任务
     * @param plan complete immutable original request / 完整不可变原请求
     * @param input reviewed original file binding / 已审阅原文件绑定
     * @param checkpoint host-certified original transfer / 宿主认证的原传输
     * @return whether every immutable field and the original attempt match / 是否全部不可变字段及原尝试均匹配 */
    private static boolean matches(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                                   DeploymentRestorePlan.FileInput input, TransferCheckpoint checkpoint) {
        var unit = cursor.unit(input.artifact().componentId());
        String candidate = unit.path("candidate").asText();
        var request = new TransferRequest(input.file().verifiedLocalFile(),
            DeploymentRestoreSteps.root(candidate) + "/mutable/restore/" + input.member(),
            input.file().byteCount());
        return unit.path("created").asBoolean()
            && unit.path("files").path(input.file().bindingId()).path("uploadAttempted").asBoolean()
            && checkpoint.server().equals(plan.request.target()) && checkpoint.upload()
            && checkpoint.request().equals(request) && checkpoint.size() == input.file().byteCount()
            && checkpoint.sha256().equals(input.file().sha256());
    }

    /** One host-certified original upload and its exact reviewed file. / 一项宿主认证的原上传及其精确已审阅文件。
     * @param input reviewed original file binding / 已审阅原文件绑定
     * @param checkpoint host-certified original transfer / 宿主认证的原传输 */
    private record OriginalUpload(DeploymentRestorePlan.FileInput input, TransferCheckpoint checkpoint) {
    }
}
