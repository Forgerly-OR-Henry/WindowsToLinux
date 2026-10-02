package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord.DeploymentReleaseState;

/** SELECT-only checks across immutable release history and formal business results. / 不可变发布历史与正式业务结果间的仅SELECT核验。 */
final class DeploymentReleaseValidation {
    /** Prevents construction of fixed validation. / 防止构造固定核验器。 */
    private DeploymentReleaseValidation() {
    }

    /** Validates retained history and supported execution links without rewriting unknown versions. / 核验保留历史和受支持执行关联，不改写未知版本。
     * @param connection original SELECT-only connection / 原仅SELECT连接
     * @throws SQLException if retained evidence conflicts / 保留证据冲突时 */
    static void validate(Connection connection) throws SQLException {
        try (var query = connection.createStatement();
             var rows = query.executeQuery(
                 "SELECT application_id FROM deployment_release GROUP BY application_id HAVING SUM(CASE WHEN state='ACTIVE' THEN 1 ELSE 0 END)>1 OR (SUM(CASE WHEN state='ACTIVE' THEN 1 ELSE 0 END)=0 AND SUM(CASE WHEN state='RETIRED' THEN 1 ELSE 0 END)>0)")) {
            if (rows.next())
                throw new SQLException("Release history has no unique active version");
        }
        try (var query = connection.createStatement();
             var rows = query.executeQuery("SELECT * FROM deployment_release")) {
            while (rows.next()) {
                var record = DeploymentReleaseRepository.row(rows);
                if (DeploymentReleaseCodec.supported(record))
                    validateRecord(connection, record);
            }
        }
        try (var query = connection.createStatement();
             var rows = query.executeQuery("SELECT * FROM deployment_execution WHERE business_committed=TRUE")) {
            while (rows.next()) {
                var execution = DeploymentExecutionRepository.row(rows);
                if (DeploymentExecutionCodec.supported(execution))
                    linked(connection, execution);
            }
        }
    }

    /** Checks original creating execution and currently active publication separately. / 分别检查原创建执行和当前活动发布。
     * @param connection original connection / 原连接
     * @param record original history / 原历史
     * @throws SQLException if origin or publication facts conflict / 来源或发布事实冲突时 */
    private static void validateRecord(Connection connection, DeploymentReleaseRecord record) throws SQLException {
        var snapshot = DeploymentReleaseCodec.snapshot(record);
        var origin = DeploymentExecutionRepository.read(connection, record.executionId(), false)
            .orElseThrow(() -> new SQLException("Release origin is absent"));
        if (!origin.identity().applicationId().equals(record.applicationId()) || snapshot.graph().components().stream()
            .anyMatch(c -> !c.unit().server().equals(origin.identity().server())))
            throw new SQLException("Release origin belongs to another application or server");
        if (record.state() == DeploymentReleaseState.ACTIVE || record.state() == DeploymentReleaseState.RETIRED) {
            bindings(connection, snapshot);
            if (!origin.businessCommitted())
                throw new SQLException("Verified release lacks a committed origin");
            if (DeploymentExecutionCodec.supported(origin)
                && !DeploymentExecutionCodec.release(origin).orElseThrow().equals(record.identity()))
                throw new SQLException("Release origin links another history");
        }
        if (record.state() == DeploymentReleaseState.ACTIVE) {
            if (!DeploymentGraphRepository.read(connection, record.applicationId())
                .orElseThrow(() -> new SQLException("Active application is absent")).equals(snapshot.graph()))
                throw new SQLException("Active history differs from the current graph");
            for (var binding : snapshot.bindings()) {
                try (var query = connection.prepareStatement(
                    "SELECT current_release_sha256 FROM deployment_unit WHERE deployment_unit_id=?")) {
                    query.setString(1, binding.unitId());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !binding.releaseSha256().equals(rows.getString(1)))
                            throw new SQLException("Active component publication differs");
                    }
                }
            }
        }
    }

    /** Checks retained exact revision bindings rather than today's configuration. / 检查保留的精确修订绑定，不使用当前配置。
     * @param connection original connection / 原连接
     * @param snapshot original manifest / 原清单
     * @throws SQLException if original bindings are missing or changed / 原绑定缺失或变化时 */
    static void bindings(Connection connection, DeploymentReleaseSnapshot snapshot) throws SQLException {
        for (var binding : snapshot.bindings()) {
            try (var query = connection.prepareStatement(
                "SELECT configuration_revision,configuration_sha256,is_secret_binding_complete FROM deployment_unit_release_binding WHERE deployment_unit_id=? AND release_sha256=?")) {
                query.setString(1, binding.unitId());
                query.setString(2, binding.releaseSha256());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != binding.configurationRevision()
                        || !rows.getString(2).equals(binding.configurationSha256()) || !rows.getBoolean(3))
                        throw new SQLException("Release configuration binding differs from original history");
                }
            }
            if (!Set.copyOf(
                    DeploymentSecretRepository.findRelease(connection, binding.unitId(), binding.releaseSha256())
                        .orElseThrow(() -> new SQLException("Release secret set is absent")))
                .equals(Set.copyOf(binding.secrets())))
                throw new SQLException("Release secrets differ from original history");
        }
    }

    /** Verifies formal success points to the same full application and component evidence. / 核验正式成功指向同一完整应用及组件证据。
     * @param connection original connection / 原连接
     * @param execution supported committed execution / 受支持已提交执行
     * @throws SQLException if its linked history is absent or conflicts / 关联历史缺失或冲突时 */
    private static void linked(Connection connection, DeploymentExecutionRecord execution) throws SQLException {
        var identity = DeploymentExecutionCodec.release(execution).orElseThrow();
        var release = DeploymentReleaseRepository.read(connection, execution.identity().applicationId(), identity.id())
            .orElseThrow(() -> new SQLException("Committed execution lacks original release history"));
        if (!identity.equals(release.identity()))
            throw new SQLException("Execution release digest differs");
        if (!DeploymentReleaseCodec.supported(release))
            return;
        if (release.state() != DeploymentReleaseState.ACTIVE && release.state() != DeploymentReleaseState.RETIRED)
            throw new SQLException("Committed execution links an unverified release");
        var snapshot = DeploymentReleaseCodec.snapshot(release);
        if (snapshot.graph().components().stream()
            .anyMatch(c -> !c.unit().server().equals(execution.identity().server())))
            throw new SQLException("Execution release server differs");
        var result = DeploymentJsonDocument.parse(execution.resultJson().orElseThrow());
        var entries = DeploymentJsonDocument.array(result, "releases", 64);
        if (entries.size() != snapshot.bindings().size())
            throw new SQLException("Execution release units differ");
        for (var entry : entries) {
            var binding = snapshot.bindings().stream().filter(b -> b.unitId().equals(entry.path("unitId").textValue()))
                .findFirst().orElseThrow(() -> new SQLException("Execution release unit is absent"));
            if (!binding.releaseSha256().equals(entry.path("sha256").textValue())
                || !binding.configurationSha256().equals(entry.path("configurationSha256").textValue()))
                throw new SQLException("Execution component content differs");
        }
    }
}
