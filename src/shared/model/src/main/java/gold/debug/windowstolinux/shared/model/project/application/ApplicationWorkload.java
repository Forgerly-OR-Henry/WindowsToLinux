package gold.debug.windowstolinux.shared.model.project.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Shared reviewed execution, exposure and delivery contract. / 共享的经审阅执行、对外服务与交付契约。 */
public record ApplicationWorkload(ExecutionMode mode, boolean reviewed, ApplicationCommand command,
                                  String workingDirectory, List<ApplicationEndpoint> endpoints,
                                  Optional<ApplicationCommand> verification, String expectedOutput,
                                  Optional<ApplicationCommand> client, List<ApplicationInput> inputs,
                                  List<ApplicationCompanion> companions, String buildDirectory, List<ApplicationWorker> workers) {
    public ApplicationWorkload(ExecutionMode mode, boolean reviewed, ApplicationCommand command, String workingDirectory,
            List<ApplicationEndpoint> endpoints, Optional<ApplicationCommand> verification, String expectedOutput,
            Optional<ApplicationCommand> client, List<ApplicationInput> inputs, List<ApplicationCompanion> companions,
            String buildDirectory) {
        this(mode, reviewed, command, workingDirectory, endpoints, verification, expectedOutput, client, inputs,
                companions, buildDirectory, List.of());
    }
    public ApplicationWorkload(ExecutionMode mode, boolean reviewed, ApplicationCommand command, String workingDirectory,
            List<ApplicationEndpoint> endpoints, Optional<ApplicationCommand> verification, String expectedOutput,
            Optional<ApplicationCommand> client, List<ApplicationInput> inputs, List<ApplicationCompanion> companions) {
        this(mode, reviewed, command, workingDirectory, endpoints, verification, expectedOutput, client, inputs, companions, "");
    }
    public enum ExecutionMode { DAEMON, ON_DEMAND }
    public enum CategoryType { WEBSITE, APP }

    public ApplicationWorkload {
        Objects.requireNonNull(mode); Objects.requireNonNull(command);
        buildDirectory = ApplicationCommand.relative(buildDirectory, true);
        workingDirectory = ApplicationCommand.relative(workingDirectory, true);
        endpoints = List.copyOf(endpoints); inputs = List.copyOf(inputs); companions = List.copyOf(companions);
        workers = List.copyOf(workers);
        if (workers.size() > 8 || workers.stream().map(ApplicationWorker::id).distinct().count() != workers.size()
                || !workers.isEmpty() && mode != ExecutionMode.DAEMON)
            throw new IllegalArgumentException("workers require a daemon and at most eight unique identities");
        Objects.requireNonNull(verification); Objects.requireNonNull(client); Objects.requireNonNull(expectedOutput);
        if (endpoints.size() > 32 || inputs.size() > 32 || companions.size() > 32 || expectedOutput.length() > 4096
                || expectedOutput.indexOf('\0') >= 0 || expectedOutput.indexOf('\n') >= 0 || expectedOutput.indexOf('\r') >= 0)
            throw new IllegalArgumentException("application declaration exceeds its bounds");
        if (mode == ExecutionMode.ON_DEMAND && verification.isEmpty())
            throw new IllegalArgumentException("on-demand applications require installation verification");
        if (mode == ExecutionMode.ON_DEMAND && !endpoints.isEmpty())
            throw new IllegalArgumentException("on-demand applications cannot publish persistent service endpoints");
        if (endpoints.stream().map(ApplicationEndpoint::id).distinct().count() != endpoints.size()
                || endpoints.stream().map(ApplicationEndpoint::portKey).distinct().count() != endpoints.size()
                || inputs.stream().map(ApplicationInput::id).distinct().count() != inputs.size()
                || companions.stream().map(ApplicationCompanion::id).distinct().count() != companions.size()
                || companions.stream().map(ApplicationCompanion::environment).distinct().count() != companions.size())
            throw new IllegalArgumentException("application resource identifiers or port bindings conflict");
        for (var input : inputs) for (var other : inputs) {
            if (input != other && (input.accessPath().equals(other.accessPath())
                    || input.accessPath().startsWith(other.accessPath() + "/")))
                throw new IllegalArgumentException("external input mappings overlap");
        }
    }

    public CategoryType category() {
        return endpoints.stream().anyMatch(value -> value.exposure() == ApplicationEndpoint.ExposureType.EXTERNAL)
                ? CategoryType.WEBSITE : CategoryType.APP;
    }
    public boolean supportsLifecycle() { return reviewed && mode == ExecutionMode.DAEMON; }
    public static ApplicationWorkload unspecified() {
        return new ApplicationWorkload(ExecutionMode.DAEMON, false, ApplicationCommand.primary(), "",
                List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
    }
}
