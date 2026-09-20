package gold.debug.windowstolinux.web.main.config;

import gold.debug.windowstolinux.web.api.config.WebHttpPolicy;
import gold.debug.windowstolinux.web.file.quota.UploadQuota;
import gold.debug.windowstolinux.web.task.model.WebTaskPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "w2l", ignoreUnknownFields = false)
public record WebRuntimeProperties(String mode, Storage storage, Secrets secrets, Database database,
        WebHttpPolicy http, WebTaskPolicy tasks, UploadQuota files, UploadQuota backups, Maintenance maintenance) {
    public WebRuntimeProperties {
        if (!"internal-test".equals(mode)) throw new IllegalArgumentException("w2l.mode must be internal-test in Phase 5");
        Objects.requireNonNull(storage); Objects.requireNonNull(secrets); Objects.requireNonNull(database);
        Objects.requireNonNull(maintenance); Objects.requireNonNull(http); Objects.requireNonNull(tasks); Objects.requireNonNull(files); Objects.requireNonNull(backups);
    }
    public record Maintenance(Duration sourceRetention, Duration scanValidity) {
        public Maintenance {
            if (sourceRetention == null || sourceRetention.isNegative() || sourceRetention.isZero()
                    || scanValidity == null || scanValidity.isNegative() || scanValidity.isZero())
                throw new IllegalArgumentException("Invalid w2l.maintenance durations");
        }
    }
    public record Storage(String root, long minimumFreeBytes) {
        public Storage { if (root == null || root.isBlank() || minimumFreeBytes < 0) throw new IllegalArgumentException("Invalid w2l.storage"); }
    }
    public record Secrets(Path directory) {
        public Secrets { if (directory == null || !directory.isAbsolute()) throw new IllegalArgumentException("w2l.secrets.directory must be absolute"); }
    }
    public record Database(Duration busyTimeout, Duration connectionTimeout) {
        public Database {
            if (busyTimeout == null || busyTimeout.isNegative() || busyTimeout.toMillis() > Integer.MAX_VALUE
                    || connectionTimeout == null || connectionTimeout.toMillis() < 250)
                throw new IllegalArgumentException("Invalid w2l.database timeouts");
        }
    }
}
