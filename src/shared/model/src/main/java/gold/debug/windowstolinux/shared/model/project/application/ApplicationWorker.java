package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/**
 * A foreground worker sharing its daemon's runtime, identity and storage. / 共享主服务运行时、身份与存储的前台工作进程。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
 */
public record ApplicationWorker(String id, ApplicationCommand command) {
    /**
     * Validates and binds the inputs required by application worker.
     * <p>校验并绑定应用工作线程所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationWorker {
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid worker identifier");
        if (Objects.requireNonNull(command).entrypoint().isEmpty())
            throw new IllegalArgumentException("worker requires an explicit source entrypoint");
    }
}
