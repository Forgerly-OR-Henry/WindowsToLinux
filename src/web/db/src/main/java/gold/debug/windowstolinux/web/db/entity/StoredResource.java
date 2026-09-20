package gold.debug.windowstolinux.web.db.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable revision of one owned resource; JSON contains no secret values. */
public record StoredResource(String id, String name, Map<String, Object> attributes, String document,
                             long version, String createdAt, String updatedAt) {
    public StoredResource { attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes)); }
}
