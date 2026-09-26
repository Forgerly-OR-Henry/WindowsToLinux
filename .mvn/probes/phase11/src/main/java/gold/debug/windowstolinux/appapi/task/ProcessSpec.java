package gold.debug.windowstolinux.appapi.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record ProcessSpec(Path executable, List<String> arguments, Map<String, String> environment,
    Path workingDirectory, Duration handshakeTimeout) {}
