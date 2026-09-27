package gold.debug.windowstolinux.appapi.task;

import java.util.Objects;

/** Original-task bounded author evidence, retained until platform task archival. / 保留至平台任务归档的原任务有界作者依据。 */
public interface TaskCheckpoint extends AutoCloseable {
    /** Immutable exact value for compare-and-set. / 用于比较后更新的不可变精确值。
     * @param revision monotonically increasing original revision / 严格递增的原修订
     * @param payload bounded non-secret author JSON object / 有界且不含秘密的作者 JSON 对象
     * @param released whether the explicit occupancy was released / 显式占用是否已释放 */
    record Snapshot(long revision, String payload, boolean released) {
        /** Requires a valid immutable observation. / 要求有效的不可变观察。
         * @param revision monotonically increasing original revision / 严格递增的原修订
         * @param payload bounded non-secret author JSON object / 有界且不含秘密的作者 JSON 对象
         * @param released whether the explicit occupancy was released / 显式占用是否已释放 */
        public Snapshot {
            if (revision < 0)
                throw new IllegalArgumentException("Negative checkpoint revision");
            Objects.requireNonNull(payload);
        }
    }

    /** Reads the exact original row, including one already released before an uncertain archive. / 读取精确原行，包括归档未确认前已释放的行。
     * @return current original revision and payload / 当前原修订及载荷
     * @throws ApiException if ownership or durable evidence cannot be confirmed / 无法确认归属或持久依据时 */
    Snapshot snapshot() throws ApiException;

    /** Advances only the expected unreleased revision without replacing unknown effects. / 仅推进预期的未释放修订，不覆盖未知效果。
     * @param expected exact previously read revision and payload / 精确的先前修订及载荷
     * @param next bounded non-secret author JSON object / 下一个有界且不含秘密的作者 JSON 对象
     * @return committed next revision / 已提交的下一修订
     * @throws ApiException if validation, compare-and-set or persistence fails / 校验、比较更新或持久化失败时 */
    Snapshot advance(Snapshot expected, String next) throws ApiException;

    /** Releases only this checkpoint's occupancy after all required evidence is retained. / 必要依据保全后仅释放此检查点占用。
     * @throws ApiException if original release cannot be confirmed / 无法确认原释放时 */
    @Override
    void close() throws ApiException;
}
