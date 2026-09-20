package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.WebResourceEntity;
import org.apache.ibatis.annotations.Param;

/** Explicit compare-and-set statements supplement MyBatis-Plus scoped CRUD. */
public interface ResourceRevisionMapper<T extends WebResourceEntity> extends BaseMapper<T> {
    int replace(@Param("row") T row, @Param("expectedVersion") long expectedVersion);
}
