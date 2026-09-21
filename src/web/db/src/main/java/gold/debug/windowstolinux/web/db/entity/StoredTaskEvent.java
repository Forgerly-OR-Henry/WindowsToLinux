package gold.debug.windowstolinux.web.db.entity;

/**
 * Carries a stable event sequence for reconnecting consumers.
 * <p>携带供重新连接消费者使用的稳定事件序列。
 *
 * @param sequence sequence / 序列
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param message localized explanation / 本地化说明
 * @param detailJson detail json / 详情JSON
 * @param createdAt instant at which this record was created / 当前记录创建时刻
 */
public record StoredTaskEvent(long sequence, String kind, String message, String detailJson, String createdAt) { }
