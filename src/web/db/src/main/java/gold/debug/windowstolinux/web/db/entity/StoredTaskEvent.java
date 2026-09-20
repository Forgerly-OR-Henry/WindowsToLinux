package gold.debug.windowstolinux.web.db.entity;

/** Stable sequence supports reconnecting event consumers. */
public record StoredTaskEvent(long sequence, String kind, String message, String detailJson, String createdAt) { }
