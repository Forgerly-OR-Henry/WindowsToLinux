package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Optional;

/**
 * Last explicit connection check, with no credentials. / 最近一次明确连接检查，不含凭据。
 *
 * @param checkedAt checked at / 已检查时刻
 * @param connected connected / 已连接
 * @param operatingSystem operating system / 操作系统
 */
public record StoredServerObservation(Optional<Instant> checkedAt, boolean connected, String operatingSystem) {
}
