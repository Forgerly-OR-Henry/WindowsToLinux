package gold.debug.windowstolinux.web.db.entity;

/** Durable task identity and state; request/result JSON contains no credential values. */
public record StoredTask(String id, String kind, String state, String requestJson, String resultJson,
                         String errorCode, String createdAt, String updatedAt, String finishedAt) { }
