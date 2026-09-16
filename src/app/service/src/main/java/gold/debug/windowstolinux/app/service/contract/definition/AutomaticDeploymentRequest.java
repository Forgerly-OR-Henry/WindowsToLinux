package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable click-time source/server selection and non-secret advanced overrides. / 点击时固定的源码和服务器选择，以及非秘密高级选项。 */
public record AutomaticDeploymentRequest(Optional<Path> directory, Optional<GitSourceRequest> git,
                                         ServerProfile server, Map<String, String> overrides) {
    /** Requires exactly one source and freezes the input before background work. / 要求恰好一个源码来源，并在后台任务开始前冻结输入。 */
    public AutomaticDeploymentRequest {
        Objects.requireNonNull(directory); Objects.requireNonNull(git); Objects.requireNonNull(server);
        if (directory.isPresent() == git.isPresent()) throw new IllegalArgumentException("select exactly one source");
        overrides = Map.copyOf(overrides);
    }
}
