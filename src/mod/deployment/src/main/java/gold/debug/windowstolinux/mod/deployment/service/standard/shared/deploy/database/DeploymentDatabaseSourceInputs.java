package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentSqliteRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DatabaseRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation.StorageLocationType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation.StorageResourceType;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;

/** Completes source-derived DB declarations without connecting to a server or granting schema approval. / 补全源码派生的数据库声明，不连接服务器也不授予结构批准。 */
public final class DeploymentDatabaseSourceInputs {
    /** Prevents construction of the source input helper. / 禁止构造源码输入辅助类。 */
    private DeploymentDatabaseSourceInputs() {
    }

    /** Returns the next bounded non-secret questions from the current source facts. / 根据当前源码事实返回下一组有界非秘密问题。
     * @param assessment original source assessment / 原源码评估
     * @param applicationId reviewed component identity / 经审阅组件身份
     * @param supplied previously supplied non-secret answers / 已提供的非秘密答案
     * @return fields to display before any database effect / 任何数据库效果前应显示的字段 */
    public static List<DeploymentReviewField> questions(DatabaseProjectChecker.Report assessment,
                                                        String applicationId, Map<String, String> supplied) {
        return questions(assessment, applicationId, supplied, !supplied.isEmpty());
    }

    /** Reopens editable source values after an invalid declaration even when no first answer existed. / 即使初次无答案，也在声明无效后重新开放源码值供更正。
     * @param assessment source facts / 源码事实
     * @param applicationId managed identity / 受管身份
     * @param supplied previous answers / 先前答案
     * @param correction whether every declaration field must be editable / 是否允许更正全部声明字段
     * @return bounded non-secret questions / 有界非秘密问题 */
    public static List<DeploymentReviewField> questions(DatabaseProjectChecker.Report assessment,
                                                        String applicationId, Map<String, String> supplied, boolean correction) {
        return gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabaseDeclarations
            .questions(assessment, applicationId, supplied, correction).stream().map(v -> {
                String field = v.key().substring(v.key().lastIndexOf('.') + 1);
                return new DeploymentReviewField(v.key(), "database." + field, "database.help." + field, v.value(), v.choices());
            }).toList();
    }

    /** Completes and validates reviewed declarations against the same frozen component root. / 对同一冻结组件根补齐并校验经审阅声明。
     * @param root frozen component root / 冻结组件根
     * @param applicationId reviewed component identity / 经审阅组件身份
     * @param assessment original source assessment / 原源码评估
     * @param supplied exact non-secret answers / 精确非秘密答案
     * @return resolved assessment without schema-effect approval / 不含结构效果批准的已解析评估
     * @throws IOException if a reviewed source file cannot be checked / 无法核验经审阅源码文件时 */
    public static DatabaseProjectChecker.Report complete(Path root, String applicationId,
                                                         DatabaseProjectChecker.Report assessment, Map<String, String> supplied) throws IOException {
        return complete(root, applicationId, assessment, supplied,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static DatabaseProjectChecker.Report complete(Path root, String applicationId,
            DatabaseProjectChecker.Report assessment, Map<String, String> supplied,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws IOException {
        return gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabaseDeclarations
            .complete(root, applicationId, assessment, supplied, layout);
    }
}
