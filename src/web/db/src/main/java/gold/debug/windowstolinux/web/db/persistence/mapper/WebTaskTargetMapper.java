package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.WebTaskTargetEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped task target records to explicit database statements.
 * <p>将限定作用域的任务目标记录映射到显式数据库语句。
 */
@Mapper
public interface WebTaskTargetMapper extends BaseMapper<WebTaskTargetEntity> { }
