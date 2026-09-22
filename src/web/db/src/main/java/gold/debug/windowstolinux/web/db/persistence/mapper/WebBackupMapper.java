package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebBackupEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped backup records to explicit database statements.
 * <p>将限定作用域的备份记录映射到显式数据库语句。
 */
@Mapper
public interface WebBackupMapper extends ResourceRevisionMapper<WebBackupEntity> {
}
