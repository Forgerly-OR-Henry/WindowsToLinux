package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.WebResourceEntity;
import org.apache.ibatis.annotations.Param;

/**
 * Supplements scoped MyBatis-Plus CRUD with explicit compare-and-set statements.
 * <p>使用显式比较并设置语句补充作用域内 MyBatis-Plus CRUD。
 *
 * @param <T> type of the contract payload / 契约载荷的类型
 */
public interface ResourceRevisionMapper<T extends WebResourceEntity> extends BaseMapper<T> {
    /**
     * Replaces resource revision mapper.
     * <p>替换资源修订映射器。
     *
     * @param row row / 数据行
     * @param expectedVersion expected version / 预期版本
     * @return replace as a numeric result / 替换的数值结果
     */
    int replace(@Param("row") T row, @Param("expectedVersion") long expectedVersion);
}
