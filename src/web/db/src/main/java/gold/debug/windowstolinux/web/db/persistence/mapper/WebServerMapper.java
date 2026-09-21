package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebServerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped server records to explicit database statements.
 * <p>将限定作用域的服务器记录映射到显式数据库语句。
 */
@Mapper
public interface WebServerMapper extends ResourceRevisionMapper<WebServerEntity> { }
