package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebSourceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped source records to explicit database statements.
 * <p>将限定作用域的源码记录映射到显式数据库语句。
 */
@Mapper
public interface WebSourceMapper extends ResourceRevisionMapper<WebSourceEntity> {
}
