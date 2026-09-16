package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Optional;

/** Last explicit connection check, with no credentials. / 最近一次明确连接检查，不含凭据。 */
public record StoredServerObservation(Optional<Instant> checkedAt, boolean connected, String operatingSystem) { }
