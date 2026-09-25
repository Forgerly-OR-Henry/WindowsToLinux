package gold.debug.windowstolinux.api.task;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record LogCategory(String category, String displayName, Optional<String> businessTable) {}
