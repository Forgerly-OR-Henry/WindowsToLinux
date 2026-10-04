package gold.debug.windowstolinux.appapi.task.query;

import java.util.List;
import java.util.Optional;

/**
 * Copied query results and an optional continuation cursor. / 独立查询结果集合及可选的后续游标。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 * @param items copied results for this page / 本页结果的副本
 * @param nextCursor opaque continuation cursor, absent on the final page / 不透明的后续游标，末页为空
 */
public record PageSlice<T>(List<T> items, Optional<String> nextCursor) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param items copied results for this page / 本页结果的副本
     * @param nextCursor opaque continuation cursor, absent on the final page / 不透明的后续游标，末页为空
     */
    public PageSlice {
        items = List.copyOf(items);
        java.util.Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
