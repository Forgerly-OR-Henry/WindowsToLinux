package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebApplicationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped application records to explicit database statements.
 * <p>将限定作用域的应用记录映射到显式数据库语句。
 */
@Mapper
public interface WebApplicationMapper extends ResourceRevisionMapper<WebApplicationEntity> { }
