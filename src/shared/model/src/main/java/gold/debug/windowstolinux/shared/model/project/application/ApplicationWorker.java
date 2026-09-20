package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/** A foreground worker sharing its daemon's runtime, identity and storage. / 共享主服务运行时、身份与存储的前台工作进程。 */
public record ApplicationWorker(String id, ApplicationCommand command) {
    public ApplicationWorker {
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid worker identifier");
        if (Objects.requireNonNull(command).entrypoint().isEmpty())
            throw new IllegalArgumentException("worker requires an explicit source entrypoint");
    }
}
