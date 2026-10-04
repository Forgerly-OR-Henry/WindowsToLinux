package gold.debug.windowstolinux.app.service.setting;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.swing.SwingUtilities;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import gold.debug.windowstolinux.app.db.PlatformStorage;

/** Stores only non-secret page identities for the next desktop startup. / 仅保存下次桌面启动所用的非秘密页面身份。 */
public final class StartupPageService {
    /** Stable preference key and strict page decoder. / 稳定偏好键及严格页面解码器。 */
    private static final String KEY = "ui_last_page";

    /** Strict decoder for bounded navigation metadata. / 有界导航元数据的严格解码器。 */
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Original preference storage owner. / 原偏好存储所有者。 */
    private final PlatformStorage persistence;

    /** Binds original desktop preference storage. / 绑定原桌面偏好存储。
     * @param persistence original storage owner / 原存储所有者 */
    public StartupPageService(PlatformStorage persistence) {
        this.persistence = Objects.requireNonNull(persistence);
    }

    /** Reads non-secret UI location before unlock without starting business work. / 解锁前读取非秘密 UI 位置，不启动业务任务。
     * @return retained location or absence / 保留位置或缺失
     * @throws SQLException if retained data cannot be verified / 保留数据无法核实时 */
    public Optional<StartupPageLocation> read() throws SQLException {
        worker();
        var saved = persistence.preferences().find(KEY);
        if (saved.isEmpty()) return Optional.empty();
        try {
            if (saved.orElseThrow().length() > 8192) throw new IllegalArgumentException("Oversized page preference");
            var node = JSON.readTree(saved.orElseThrow());
            if (!node.isObject() || node.size() != 5 || !node.path("formatVersion").isInt() || node.path("formatVersion").asInt() != 1)
                throw new IllegalArgumentException("Invalid page preference format");
            for (String key : new String[]{"platformId", "ownerId", "pageId", "detailId"})
                if (!node.path(key).isTextual()) throw new IllegalArgumentException("Invalid page preference field");
            return Optional.of(new StartupPageLocation(node.path("platformId").textValue(), node.path("ownerId").textValue(),
                node.path("pageId").textValue(), node.path("detailId").textValue()));
        } catch (Exception failure) {
            throw new SQLException("Invalid last-page preference; original value retained", failure);
        }
    }

    /** Remembers the EDT-captured page before normal runtime shutdown, including while locked. / 正常运行时关闭前记录 EDT 捕获页面，包括锁定状态。
     * @param location non-secret stable page identity / 非秘密稳定页面身份
     * @throws SQLException if the original preference write fails / 原偏好写入失败时 */
    public void remember(StartupPageLocation location) throws SQLException {
        worker();
        var node = JSON.createObjectNode().put("formatVersion", 1).put("platformId", location.platformId())
            .put("ownerId", location.ownerId()).put("pageId", location.pageId()).put("detailId", location.detailId());
        persistence.preferences().save(KEY, node.toString());
    }

    /** Keeps preference I/O off the event thread. / 偏好 I/O 离开事件线程执行。 */
    private static void worker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Startup preference I/O requires a worker");
    }
}
