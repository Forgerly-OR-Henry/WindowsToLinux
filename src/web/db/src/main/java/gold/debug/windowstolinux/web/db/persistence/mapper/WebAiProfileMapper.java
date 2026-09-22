package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebAiProfileEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped ai profile records to explicit database statements.
 * <p>将限定作用域的AI配置资料记录映射到显式数据库语句。
 */
@Mapper
public interface WebAiProfileMapper extends ResourceRevisionMapper<WebAiProfileEntity> {
}
