package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;

/** Non-secret original model/revision ownership retained by the central task. / 中央任务保留的非秘密模型及修订归属。 */
record ModelProbeIdentity(String profileId, long revision, AiCapabilityType capability) {
    static final String KEY = "model-probe.v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    String payload() {
        return "{\"formatVersion\":1,\"profileId\":\"" + profileId + "\",\"configurationRevision\":" + revision
            + ",\"capability\":\"" + capability.name() + "\"}";
    }

    static java.util.Optional<ModelProbeIdentity> read(PlatformDatabase database, TaskSnapshot task) throws SQLException {
        var row = new TaskAuthorCheckpoint(database).find(task.id(), task.ownerId(), task.ownerVersion(), KEY);
        if (row.isEmpty()) return java.util.Optional.empty();
        try {
            var node = JSON.readTree(row.orElseThrow().snapshot().payload());
            if (!node.isObject() || node.size() != 4 || node.path("formatVersion").asInt() != 1
                || !node.path("profileId").asText().matches("[a-z][a-z0-9-]{0,63}")
                || !node.path("configurationRevision").canConvertToLong() || node.path("configurationRevision").asLong() < 1)
                throw new IllegalArgumentException("Invalid model probe binding");
            return java.util.Optional.of(new ModelProbeIdentity(node.path("profileId").asText(),
                node.path("configurationRevision").asLong(), AiCapabilityType.valueOf(node.path("capability").asText())));
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new SQLException("Model probe identity is invalid", invalid);
        }
    }
}
