package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.WebSecretEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maps scoped secret records to explicit database statements.
 * <p>将限定作用域的秘密记录映射到显式数据库语句。
 */
@Mapper
public interface WebSecretMapper extends BaseMapper<WebSecretEntity> { }
