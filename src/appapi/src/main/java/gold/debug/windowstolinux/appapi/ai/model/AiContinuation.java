package gold.debug.windowstolinux.appapi.ai.model;

import java.util.UUID;

/** Opaque host continuation bound to the original task and model revision. / 绑定原任务和模型修订的不透明宿主续接标识。
 * @param id host-issued identity without provider payload / 不含提供方载荷的宿主签发标识
 * @param payloadBytes actual UTF-8 provider-state size, verified by the host on reuse / 复用时由宿主核验的提供方状态实际 UTF-8 字节数 */
public record AiContinuation(UUID id, int payloadBytes) {
    public AiContinuation {
        java.util.Objects.requireNonNull(id);
        if (payloadBytes < 1 || payloadBytes > 1024 * 1024)
            throw new IllegalArgumentException("Native provider state size is invalid");
    }
}
