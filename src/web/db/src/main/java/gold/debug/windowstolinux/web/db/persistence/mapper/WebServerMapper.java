package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebServerEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WebServerMapper extends ResourceRevisionMapper<WebServerEntity> { }
