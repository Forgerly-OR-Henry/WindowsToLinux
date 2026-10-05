package gold.debug.windowstolinux.appapi.task.log;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/** Bounded redacted diagnostics from one original owned target. / 一个原所属目标的有界脱敏诊断。
 * @param records newest-first records from the retained hundred-record window / 保留百条窗口内按新旧排序的记录
 * @param problem any storage location not successfully observed / 任一未成功观测的存储位置 */
public record LogReadResult(List<Record> records, Optional<Problem> problem) {
    /** Copies observations without treating an unavailable location as empty. / 复制观测，不将不可用位置当作空集合。
     * @param records bounded original observations / 有界原观测
     * @param problem unresolved storage observation / 未解决的存储观测 */
    public LogReadResult {
        records = List.copyOf(records);
        Objects.requireNonNull(problem);
        if (records.size() > 100) throw new IllegalArgumentException("Diagnostic window exceeds 100 records");
    }

    /** Immutable redacted diagnostic retaining its original identity. / 保留原身份的不可变脱敏诊断。
     * @param id original diagnostic identity / 原诊断身份
     * @param recordedAt actual recording time / 实际记录时间
     * @param event redacted original event / 脱敏原事件
     * @param truncated actual upstream or storage truncation / 实际上游或存储截断 */
    public record Record(UUID id, Instant recordedAt, LogEvent event, boolean truncated) {
        /** Requires complete evidence without reconstructing an operation. / 要求完整依据，不重建操作。
         * @param id original identity / 原身份
         * @param recordedAt original time / 原时间
         * @param event original redacted event / 原脱敏事件
         * @param truncated actual truncation / 实际截断 */
        public Record {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recordedAt);
            Objects.requireNonNull(event);
        }
    }
}
