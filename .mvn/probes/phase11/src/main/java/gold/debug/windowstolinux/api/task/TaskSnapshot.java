package gold.debug.windowstolinux.api.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record TaskSnapshot(UUID id, String ownerId, String ownerVersion, String type,
    TaskState state, Optional<Outcome> outcome, String progressText,
    Instant startedAt, Optional<Instant> endedAt, List<String> blockers) {}
