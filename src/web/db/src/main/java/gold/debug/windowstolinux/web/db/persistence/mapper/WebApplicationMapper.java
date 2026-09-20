package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.WebApplicationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WebApplicationMapper extends ResourceRevisionMapper<WebApplicationEntity> { }
