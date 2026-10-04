# 类名调整清单（2026-10-03）

采用“对象＋职责／用途”，包名、字段、协议与存储格式保持现状。公开类型改名后，旧编译 Mod 需要重新编译。

本次调整 252 个生产 Java 文件名；86 个测试／夹具文件名及调用引用同时同步。

## 生产文件

| 原类型 | 新类型 | 当前源码路径 |
| --- | --- | --- |
| `HttpEndpoint` | `HttpUrlChecker` | `src/app/ai/src/main/java/gold/debug/windowstolinux/app/ai/http/HttpUrlChecker.java` |
| `AiCapabilityStateType` | `AiCapabilityStatus` | `src/app/ai/src/main/java/gold/debug/windowstolinux/app/ai/model/AiCapabilityStatus.java` |
| `VoiceTranscript` | `VoiceTranscriptBuilder` | `src/app/ai/src/main/java/gold/debug/windowstolinux/app/ai/voice/VoiceTranscriptBuilder.java` |
| `PlatformPersistence` | `PlatformStorage` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/PlatformStorage.java` |
| `AiProfileRepository` | `AiModelStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/ai/AiModelStore.java` |
| `AiPurposeRepository` | `AiPurposeStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/ai/AiPurposeStore.java` |
| `StoredAiProviderConfiguration` | `StoredAiModelInfo` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/ai/StoredAiModelInfo.java` |
| `StoredAiProviderProfile` | `StoredAiModelProfile` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/ai/StoredAiModelProfile.java` |
| `PlatformSchemaInventory` | `PlatformSchemaList` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/connect/PlatformSchemaList.java` |
| `RepositoryConnections` | `StoreConnections` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/connect/StoreConnections.java` |
| `RepositoryTransactionExecutor` | `StoreTransactionRunner` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/connect/StoreTransactionRunner.java` |
| `DesktopPersistenceException` | `DesktopStorageException` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/error/DesktopStorageException.java` |
| `DesktopPersistenceFailureType` | `DesktopStorageFailureType` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/error/DesktopStorageFailureType.java` |
| `FileRepository` | `FileStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/file/FileStore.java` |
| `LogRepository` | `LogStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/log/LogStore.java` |
| `SchemaValidation` | `SchemaChecker` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/schema/SchemaChecker.java` |
| `CredentialReferenceRepository` | `SecretRefStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/security/SecretRefStore.java` |
| `SecurityRepository` | `SecurityStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/security/SecurityStore.java` |
| `StoredCredential` | `StoredSecret` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/security/StoredSecret.java` |
| `ServerProfileRepository` | `ServerProfileStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/server/ServerProfileStore.java` |
| `ServerReferenceRepository` | `ServerRefStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/server/ServerRefStore.java` |
| `DesktopPreferenceRepository` | `DesktopPreferenceStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/setting/DesktopPreferenceStore.java` |
| `BrowserRecoveryRepository` | `BrowserRecoveryStore` | `src/app/db/src/main/java/gold/debug/windowstolinux/app/db/sshrecover/BrowserRecoveryStore.java` |
| `FileJournal` | `FileActionLog` | `src/app/file/src/main/java/gold/debug/windowstolinux/app/file/model/FileActionLog.java` |
| `FileContentPublication` | `FileContentPublish` | `src/app/file/src/main/java/gold/debug/windowstolinux/app/file/FileContentPublish.java` |
| `TransferJournal` | `TransferActionLog` | `src/app/linux/src/main/java/gold/debug/windowstolinux/app/linux/sftp/TransferActionLog.java` |
| `ModSchemaInspection` | `ModSchemaChecker` | `src/app/main/src/main/java/gold/debug/windowstolinux/app/main/mod/ModSchemaChecker.java` |
| `ModSchemaPreparation` | `ModSchemaSetup` | `src/app/main/src/main/java/gold/debug/windowstolinux/app/main/mod/ModSchemaSetup.java` |
| `PlatformModCoordinator` | `PlatformModFlow` | `src/app/main/src/main/java/gold/debug/windowstolinux/app/main/mod/PlatformModFlow.java` |
| `DesktopMain` | `ApplicationLauncher` | `src/app/main/src/main/java/gold/debug/windowstolinux/app/main/start/ApplicationLauncher.java` |
| `DevelopmentDescriptor` | `DevelopmentInfo` | `src/app/mod/src/main/java/gold/debug/windowstolinux/app/mod/dev/DevelopmentInfo.java` |
| `DevelopmentModDiscovery` | `DevelopmentModFinder` | `src/app/mod/src/main/java/gold/debug/windowstolinux/app/mod/dev/DevelopmentModFinder.java` |
| `ModCodeInspection` | `ModCodeChecker` | `src/app/mod/src/main/java/gold/debug/windowstolinux/app/mod/load/ModCodeChecker.java` |
| `ServiceSnapshot` | `ServiceValueCopier` | `src/app/mod/src/main/java/gold/debug/windowstolinux/app/mod/service/ServiceValueCopier.java` |
| `CredentialVault` | `SecretVault` | `src/app/secret/src/main/java/gold/debug/windowstolinux/app/secret/SecretVault.java` |
| `AiProviderSummary` | `AiModelInfo` | `src/app/service/src/main/java/gold/debug/windowstolinux/app/service/ai/AiModelInfo.java` |
| `AiInventoryAccess` | `AiModelListAccess` | `src/app/service/src/main/java/gold/debug/windowstolinux/app/service/ai/AiModelListAccess.java` |
| `AiProviderProfile` | `AiModelProfile` | `src/app/service/src/main/java/gold/debug/windowstolinux/app/service/ai/AiModelProfile.java` |
| `PlatformFileCoordinator` | `PlatformFileFlow` | `src/app/service/src/main/java/gold/debug/windowstolinux/app/service/file/PlatformFileFlow.java` |
| `TransferConfiguration` | `TransferConfig` | `src/app/service/src/main/java/gold/debug/windowstolinux/app/service/setting/TransferConfig.java` |
| `SecurityApplicationFacade` | `SecurityAccess` | `src/app/service/src/main/java/gold/debug/windowstolinux/app/service/security/SecurityAccess.java` |
| `AiProviderDragTransfer` | `AiModelDragTransfer` | `src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/ai/AiModelDragTransfer.java` |
| `AiModelInventoryPane` | `AiModelListPane` | `src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/ai/AiModelListPane.java` |
| `AiInventoryState` | `AiModelListState` | `src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/ai/AiModelListState.java` |
| `DesktopComponentFactory` | `DesktopPartFactory` | `src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/control/DesktopPartFactory.java` |
| `DesktopDisplayConfiguration` | `DesktopDisplayConfig` | `src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/display/DesktopDisplayConfig.java` |
| `DesktopTaskExecutor` | `DesktopTaskRunner` | `src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/task/DesktopTaskRunner.java` |
| `WindowsFilePublication` | `WindowsFilePublish` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/file/WindowsFilePublish.java` |
| `DesktopUninstallCoordinator` | `DesktopUninstallFlow` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/uninstall/DesktopUninstallFlow.java` |
| `DesktopUninstallPreparationResult` | `DesktopUninstallSetupResult` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/uninstall/DesktopUninstallSetupResult.java` |
| `DesktopUninstallPreparationStatus` | `DesktopUninstallSetupStatus` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/uninstall/model/DesktopUninstallSetupStatus.java` |
| `DesktopUpdateCoordinator` | `DesktopUpdateFlow` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/update/DesktopUpdateFlow.java` |
| `DesktopUpdatePreparationResult` | `DesktopUpdateSetupResult` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/update/DesktopUpdateSetupResult.java` |
| `DesktopUpdatePreparationStatus` | `DesktopUpdateSetupStatus` | `src/app/windows/src/main/java/gold/debug/windowstolinux/app/windows/update/model/DesktopUpdateSetupStatus.java` |
| `SecretRef` | `SecretId` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/secret/model/SecretId.java` |
| `SecretReference` | `SecretRef` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/secret/ref/SecretRef.java` |
| `ReferenceResolution` | `ResourceRefResult` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/task/ref/ResourceRefResult.java` |
| `ReferenceState` | `ResourceRefState` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/task/ref/ResourceRefState.java` |
| `ServerRef` | `ServerId` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/server/model/ServerId.java` |
| `ServerReference` | `ServerRef` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/server/ref/ServerRef.java` |
| `Outcome` | `TaskResult` | `src/appapi/src/main/java/gold/debug/windowstolinux/appapi/task/result/TaskResult.java` |
| `DeploymentApplicationRef` | `DeploymentApplicationId` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/api/DeploymentApplicationId.java` |
| `DeploymentConfigurationRepository` | `DeploymentConfigStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentConfigStore.java` |
| `DeploymentExecutionRepository` | `DeploymentExecutionStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentExecutionStore.java` |
| `DeploymentGraphRepository` | `DeploymentGraphStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentGraphStore.java` |
| `DeploymentPayloadValidation` | `DeploymentPayloadChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPayloadChecker.java` |
| `DeploymentProjectRepository` | `DeploymentProjectStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentProjectStore.java` |
| `DeploymentPublicationAttemptCodec` | `DeploymentPublishAttemptCodec` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishAttemptCodec.java` |
| `DeploymentPublicationAttemptIntent` | `DeploymentPublishAttemptIntent` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishAttemptIntent.java` |
| `DeploymentPublicationAttemptProof` | `DeploymentPublishAttemptProof` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishAttemptProof.java` |
| `DeploymentPublicationAttemptReview` | `DeploymentPublishAttemptReview` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishAttemptReview.java` |
| `DeploymentPublicationAttemptRepository` | `DeploymentPublishAttemptStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishAttemptStore.java` |
| `DeploymentPublicationProposal` | `DeploymentPublishProposal` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishProposal.java` |
| `DeploymentPublicationRepository` | `DeploymentPublishStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishStore.java` |
| `DeploymentPublicationTemplateCodec` | `DeploymentPublishTemplateCodec` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishTemplateCodec.java` |
| `DeploymentReferenceAssociations` | `DeploymentRefAssociations` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentRefAssociations.java` |
| `DeploymentReferenceIntent` | `DeploymentRefIntent` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentRefIntent.java` |
| `DeploymentReferenceKey` | `DeploymentRefKey` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentRefKey.java` |
| `DeploymentReleaseValidation` | `DeploymentReleaseChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentReleaseChecker.java` |
| `DeploymentReleaseRepository` | `DeploymentReleaseStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentReleaseStore.java` |
| `DeploymentSecretRepository` | `DeploymentSecretStore` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentSecretStore.java` |
| `DeploymentSourcePreparer` | `DeploymentSourceSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/file/DeploymentSourceSetup.java` |
| `DeploymentGitReference` | `DeploymentGitRef` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/file/git/DeploymentGitRef.java` |
| `DeploymentGitPreparer` | `DeploymentGitSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/file/git/DeploymentGitSetup.java` |
| `DeploymentOriginalPublicationStatus` | `DeploymentOriginalPublishStatus` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentOriginalPublishStatus.java` |
| `DeploymentPublicationArguments` | `DeploymentPublishArguments` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentPublishArguments.java` |
| `DeploymentPublicationChannel` | `DeploymentPublishChannel` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentPublishChannel.java` |
| `DeploymentPublicationEvidenceChannel` | `DeploymentPublishEvidenceChannel` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentPublishEvidenceChannel.java` |
| `DeploymentPublicationReceipt` | `DeploymentPublishReceipt` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentPublishReceipt.java` |
| `DeploymentSecretProvisionJournal` | `DeploymentSecretProvisionActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/secret/DeploymentSecretProvisionActionLog.java` |
| `DeploymentAgentJournal` | `DeploymentAgentActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/agent/DeploymentAgentActionLog.java` |
| `DeploymentGitSourceJournal` | `DeploymentGitSourceActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/DeploymentGitSourceActionLog.java` |
| `DeploymentPublication` | `DeploymentPublish` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/DeploymentPublish.java` |
| `DeploymentPublicationPlan` | `DeploymentPublishPlan` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/DeploymentPublishPlan.java` |
| `DeploymentReferenceFlow` | `DeploymentRefFlow` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/DeploymentRefFlow.java` |
| `DeploymentConfigurationEntry` | `DeploymentConfigEntry` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/config/DeploymentConfigEntry.java` |
| `DeploymentConfigurationInput` | `DeploymentConfigInput` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/config/DeploymentConfigInput.java` |
| `DeploymentConfigurationScopeType` | `DeploymentConfigScopeType` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/config/DeploymentConfigScopeType.java` |
| `DeploymentConfigurationSnapshot` | `DeploymentConfigSnapshot` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/config/DeploymentConfigSnapshot.java` |
| `DeploymentConfigurationValue` | `DeploymentConfigValue` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/config/DeploymentConfigValue.java` |
| `DeploymentOriginalPublicationCommit` | `DeploymentOriginalPublishCommit` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/delivery/DeploymentOriginalPublishCommit.java` |
| `DeploymentApplicationInventory` | `DeploymentApplicationList` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/execution/DeploymentApplicationList.java` |
| `DeploymentApplicationExecutor` | `DeploymentApplicationRunner` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/execution/DeploymentApplicationRunner.java` |
| `DeploymentCollectionCoordinator` | `DeploymentCollectionFlow` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/execution/DeploymentCollectionFlow.java` |
| `DeploymentManagementJournal` | `DeploymentManagementActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/execution/DeploymentManagementActionLog.java` |
| `DeploymentRestoreCoordinator` | `DeploymentRestoreFlow` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/execution/DeploymentRestoreFlow.java` |
| `DeploymentRestorePreparation` | `DeploymentRestoreSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/execution/DeploymentRestoreSetup.java` |
| `DeploymentConfigurationException` | `DeploymentConfigException` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/DeploymentConfigException.java` |
| `DeploymentSecretReference` | `DeploymentSecretRef` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/DeploymentSecretRef.java` |
| `DeploymentUnitRuntimeConfiguration` | `DeploymentUnitRuntimeConfig` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/DeploymentUnitRuntimeConfig.java` |
| `DeploymentProjectAssessment` | `DeploymentProjectReport` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/assessment/DeploymentProjectReport.java` |
| `MultiComponentProjectAssessment` | `MultiPartProjectReport` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/assessment/MultiPartProjectReport.java` |
| `ComponentIssue` | `PartIssue` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/assessment/PartIssue.java` |
| `DeploymentRuntimeAssessment` | `DeploymentRuntimeReport` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/project/DeploymentRuntimeReport.java` |
| `DeploymentComponent` | `DeploymentPart` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/project/component/DeploymentPart.java` |
| `ComponentIsolationSpecification` | `PartIsolationSpec` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/core/model/project/component/PartIsolationSpec.java` |
| `DeploymentDatabasePlanJournal` | `DeploymentDatabasePlanActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentDatabasePlanActionLog.java` |
| `DeploymentNativeDatabaseJournal` | `DeploymentNativeDatabaseActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentNativeDatabaseActionLog.java` |
| `DeploymentNativeDatabasePreparation` | `DeploymentNativeDatabaseSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentNativeDatabaseSetup.java` |
| `DeploymentProtocolBootstrapJournal` | `DeploymentProtocolBootstrapActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentProtocolBootstrapActionLog.java` |
| `DeploymentStaticPreparationJournal` | `DeploymentStaticSetupActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentStaticSetupActionLog.java` |
| `DeploymentStaticSourcePreparation` | `DeploymentStaticSourceSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentStaticSourceSetup.java` |
| `DiscoveredProjectComponent` | `DiscoveredProjectPart` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/DiscoveredProjectPart.java` |
| `MixedProjectInspector` | `MixedProjectChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/MixedProjectChecker.java` |
| `ComponentAnalysisRequest` | `PartAnalysisRequest` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/PartAnalysisRequest.java` |
| `ComponentGraphValidator` | `PartGraphChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/PartGraphChecker.java` |
| `ProjectComponentDiscovery` | `ProjectPartFinder` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/ProjectPartFinder.java` |
| `DeploymentTypeInspector` | `DeploymentTypeChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/contract/spi/DeploymentTypeChecker.java` |
| `DeploymentTypeAssessment` | `DeploymentTypeReport` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/contract/spi/DeploymentTypeReport.java` |
| `DeploymentAnalysisCoordinator` | `DeploymentAnalysisFlow` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/core/DeploymentAnalysisFlow.java` |
| `ProjectLanguageInspector` | `ProjectLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/core/ProjectLanguageChecker.java` |
| `CLanguageInspector` | `CLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/c/CLanguageChecker.java` |
| `CmakeDeploymentInspector` | `CmakeDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/c/cmake/CmakeDeploymentChecker.java` |
| `DatabaseProjectInspector` | `DatabaseProjectChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/db/DatabaseProjectChecker.java` |
| `DotNetLanguageInspector` | `DotNetLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/dotnet/DotNetLanguageChecker.java` |
| `DotNetSdkDeploymentInspector` | `DotNetSdkDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/dotnet/dotnetsdk/DotNetSdkDeploymentChecker.java` |
| `GoLanguageInspector` | `GoLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/go/GoLanguageChecker.java` |
| `GoModuleDeploymentInspector` | `GoModuleDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/go/gomodule/GoModuleDeploymentChecker.java` |
| `JavaLanguageInspector` | `JavaLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/JavaLanguageChecker.java` |
| `SpringBootDeploymentInspector` | `SpringBootDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/SpringBootDeploymentChecker.java` |
| `GradleBuildInspector` | `GradleBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/gradle/GradleBuildChecker.java` |
| `JavaJarDeploymentInspector` | `JavaJarDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/jar/JavaJarDeploymentChecker.java` |
| `JavaJarManifestInspector` | `JavaJarManifestChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/jar/JavaJarManifestChecker.java` |
| `JavaJdkDeploymentInspector` | `JavaJdkDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/jdk/JavaJdkDeploymentChecker.java` |
| `MavenBuildInspector` | `MavenBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/maven/MavenBuildChecker.java` |
| `KotlinLanguageInspector` | `KotlinLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/kotlin/KotlinLanguageChecker.java` |
| `KotlinServiceDeploymentInspector` | `KotlinServiceDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/kotlin/KotlinServiceDeploymentChecker.java` |
| `KotlinGradleDeploymentInspector` | `KotlinGradleDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/kotlin/gradle/KotlinGradleDeploymentChecker.java` |
| `KotlinCompilerDeploymentInspector` | `KotlinCompilerDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/kotlin/kotlinc/KotlinCompilerDeploymentChecker.java` |
| `NodeBuildInspector` | `NodeBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/NodeBuildChecker.java` |
| `NodeLanguageInspector` | `NodeLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/NodeLanguageChecker.java` |
| `NodeServiceDeploymentInspector` | `NodeServiceDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/NodeServiceDeploymentChecker.java` |
| `NpmBuildInspector` | `NpmBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/npm/NpmBuildChecker.java` |
| `PnpmBuildInspector` | `PnpmBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/pnpm/PnpmBuildChecker.java` |
| `YarnBuildInspector` | `YarnBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/yarn/YarnBuildChecker.java` |
| `PhpLanguageInspector` | `PhpLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/php/PhpLanguageChecker.java` |
| `PhpServiceDeploymentInspector` | `PhpServiceDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/php/PhpServiceDeploymentChecker.java` |
| `PhpComposerDeploymentInspector` | `PhpComposerDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/php/composer/PhpComposerDeploymentChecker.java` |
| `PhpCliDeploymentInspector` | `PhpCliDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/php/phpcli/PhpCliDeploymentChecker.java` |
| `PythonBuildInspector` | `PythonBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/PythonBuildChecker.java` |
| `PythonLanguageInspector` | `PythonLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/PythonLanguageChecker.java` |
| `PythonServiceDeploymentInspector` | `PythonServiceDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/PythonServiceDeploymentChecker.java` |
| `PipBuildInspector` | `PipBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/pip/PipBuildChecker.java` |
| `PipenvBuildInspector` | `PipenvBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/pipenv/PipenvBuildChecker.java` |
| `PoetryBuildInspector` | `PoetryBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/poetry/PoetryBuildChecker.java` |
| `UvBuildInspector` | `UvBuildChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/uv/UvBuildChecker.java` |
| `RubyLanguageInspector` | `RubyLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/ruby/RubyLanguageChecker.java` |
| `RubyServiceDeploymentInspector` | `RubyServiceDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/ruby/RubyServiceDeploymentChecker.java` |
| `RubyBundlerDeploymentInspector` | `RubyBundlerDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/ruby/bundler/RubyBundlerDeploymentChecker.java` |
| `RubyCliDeploymentInspector` | `RubyCliDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/ruby/rubycli/RubyCliDeploymentChecker.java` |
| `RustLanguageInspector` | `RustLanguageChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/rust/RustLanguageChecker.java` |
| `RustCargoDeploymentInspector` | `RustCargoDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/rust/cargo/RustCargoDeploymentChecker.java` |
| `DeploymentTypeInspectorRegistry` | `DeploymentTypeCheckerRegistry` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/extension/registry/DeploymentTypeCheckerRegistry.java` |
| `PreviewInspector` | `PreviewChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/preview/PreviewChecker.java` |
| `ServiceMetadataInspector` | `ServiceMetadataChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/service/ServiceMetadataChecker.java` |
| `ServiceInspectionAssembler` | `ServiceReportBuilder` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/service/ServiceReportBuilder.java` |
| `ApplicationBundleInspector` | `ApplicationBundleChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/source/ApplicationBundleChecker.java` |
| `BoundedMetadataInspector` | `BoundedMetadataChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/source/BoundedMetadataChecker.java` |
| `BoundedSourceInspector` | `BoundedSourceChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/source/BoundedSourceChecker.java` |
| `SourceInspectionFacts` | `SourceCheckFacts` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/source/SourceCheckFacts.java` |
| `ToolchainDeclarationInspector` | `ToolchainSpecChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/toolchain/ToolchainSpecChecker.java` |
| `ContainerDeploymentInspector` | `ContainerDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/workload/ContainerDeploymentChecker.java` |
| `StaticWebDeploymentInspector` | `StaticWebDeploymentChecker` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/workload/StaticWebDeploymentChecker.java` |
| `DeploymentStaticReviewAssembler` | `DeploymentStaticReviewBuilder` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/DeploymentStaticReviewBuilder.java` |
| `BuildCompatibilityPolicy` | `BuildMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/BuildMatchPolicy.java` |
| `DeploymentStandardBuildExecutor` | `DeploymentStandardBuildRunner` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/DeploymentStandardBuildRunner.java` |
| `DeploymentStandardCandidateExecutor` | `DeploymentStandardCandidateRunner` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/DeploymentStandardCandidateRunner.java` |
| `DeploymentToolchainPreparer` | `DeploymentToolchainSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/DeploymentToolchainSetup.java` |
| `DeploymentBuildRenderer` | `DeploymentBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/contract/spi/DeploymentBuildWriter.java` |
| `CargoBuildRenderer` | `CargoBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/CargoBuildWriter.java` |
| `CmakeBuildRenderer` | `CmakeBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/CmakeBuildWriter.java` |
| `DotNetSdkBuildRenderer` | `DotNetSdkBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/DotNetSdkBuildWriter.java` |
| `GoBuildRenderer` | `GoBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/GoBuildWriter.java` |
| `GradleBuildRenderer` | `GradleBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/java/GradleBuildWriter.java` |
| `JavaJarBuildRenderer` | `JavaJarBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/java/JavaJarBuildWriter.java` |
| `JdkBuildRenderer` | `JdkBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/java/JdkBuildWriter.java` |
| `MavenBuildRenderer` | `MavenBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/java/MavenBuildWriter.java` |
| `KotlinCompilerBuildRenderer` | `KotlinCompilerBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/kotlin/KotlinCompilerBuildWriter.java` |
| `KotlinGradleBuildRenderer` | `KotlinGradleBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/kotlin/KotlinGradleBuildWriter.java` |
| `NodeArchitectureBuildRenderer` | `NodeArchitectureBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/node/NodeArchitectureBuildWriter.java` |
| `NpmBuildRenderer` | `NpmBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/node/NpmBuildWriter.java` |
| `PnpmBuildRenderer` | `PnpmBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/node/PnpmBuildWriter.java` |
| `YarnBuildRenderer` | `YarnBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/node/YarnBuildWriter.java` |
| `ComposerBuildRenderer` | `ComposerBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/php/ComposerBuildWriter.java` |
| `PhpCliBuildRenderer` | `PhpCliBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/php/PhpCliBuildWriter.java` |
| `PipBuildRenderer` | `PipBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/python/PipBuildWriter.java` |
| `PipenvBuildRenderer` | `PipenvBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/python/PipenvBuildWriter.java` |
| `PoetryBuildRenderer` | `PoetryBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/python/PoetryBuildWriter.java` |
| `PythonArchitectureBuildRenderer` | `PythonArchitectureBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/python/PythonArchitectureBuildWriter.java` |
| `UvBuildRenderer` | `UvBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/python/UvBuildWriter.java` |
| `BundlerBuildRenderer` | `BundlerBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/ruby/BundlerBuildWriter.java` |
| `RubyCliBuildRenderer` | `RubyCliBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/ecosystem/ruby/RubyCliBuildWriter.java` |
| `DeploymentBuildRendererRegistry` | `DeploymentBuildWriterRegistry` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/extension/registry/DeploymentBuildWriterRegistry.java` |
| `DeploymentBuildEnvironmentRenderer` | `DeploymentBuildEnvironmentWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/generation/script/DeploymentBuildEnvironmentWriter.java` |
| `NodeDependencyToolPreparation` | `NodeDependencyToolSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/generation/script/NodeDependencyToolSetup.java` |
| `ContainerBuildRenderer` | `ContainerBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/workload/ContainerBuildWriter.java` |
| `StaticSiteBuildRenderer` | `StaticSiteBuildWriter` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/workload/StaticSiteBuildWriter.java` |
| `MultiComponentDeploymentPlan` | `MultiPartDeploymentPlan` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/contract/MultiPartDeploymentPlan.java` |
| `DeploymentSqlitePreparation` | `DeploymentSqliteSetup` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/database/DeploymentSqliteSetup.java` |
| `ApplicationDeclaration` | `ApplicationSpec` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/input/ApplicationSpec.java` |
| `MultiComponentDeploymentPlanner` | `MultiPartDeploymentPlanner` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/plan/MultiPartDeploymentPlanner.java` |
| `DeploymentAssistedPublicationAttempts` | `DeploymentAssistedPublishAttempts` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentAssistedPublishAttempts.java` |
| `DeploymentAssistedPublicationBuild` | `DeploymentAssistedPublishBuild` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentAssistedPublishBuild.java` |
| `DeploymentGraphPublicationJournal` | `DeploymentGraphPublishActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentGraphPublishActionLog.java` |
| `DeploymentMultiComponentPublicationRecovery` | `DeploymentMultiPartPublishRecovery` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentMultiPartPublishRecovery.java` |
| `DeploymentMultiComponentPublicationExecutor` | `DeploymentMultiPartPublishRunner` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentMultiPartPublishRunner.java` |
| `DeploymentMultiComponentPublicationTransaction` | `DeploymentMultiPartPublishTransaction` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentMultiPartPublishTransaction.java` |
| `DeploymentPublicationAttemptPointers` | `DeploymentPublishAttemptPointers` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishAttemptPointers.java` |
| `DeploymentPublicationAttemptRecovery` | `DeploymentPublishAttemptRecovery` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishAttemptRecovery.java` |
| `DeploymentPublicationAuthorSession` | `DeploymentPublishAuthorSession` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishAuthorSession.java` |
| `DeploymentPublicationCleanupJournal` | `DeploymentPublishCleanupActionLog` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishCleanupActionLog.java` |
| `DeploymentPublicationCleanupAttempt` | `DeploymentPublishCleanupAttempt` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishCleanupAttempt.java` |
| `DeploymentPublicationOperationBinding` | `DeploymentPublishOperationBinding` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishOperationBinding.java` |
| `DeploymentPublicationRecoveryFacts` | `DeploymentPublishRecoveryFacts` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishRecoveryFacts.java` |
| `DeploymentPublicationRestorationProof` | `DeploymentPublishRestorationProof` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishRestorationProof.java` |
| `DeploymentPublicationStaging` | `DeploymentPublishStaging` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishStaging.java` |
| `DeploymentStandardPublicationRecovery` | `DeploymentStandardPublishRecovery` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentStandardPublishRecovery.java` |
| `DeploymentStandardPublicationExecutor` | `DeploymentStandardPublishRunner` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentStandardPublishRunner.java` |
| `DeploymentStandardPublicationTransaction` | `DeploymentStandardPublishTransaction` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentStandardPublishTransaction.java` |
| `DeploymentHostCompatibilityDecision` | `DeploymentHostMatchDecision` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/DeploymentHostMatchDecision.java` |
| `DeploymentHostCompatibilityEvaluator` | `DeploymentHostMatcher` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/DeploymentHostMatcher.java` |
| `DeploymentAlmaLinuxCompatibilityPolicy` | `DeploymentAlmaLinuxMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentAlmaLinuxMatchPolicy.java` |
| `DeploymentCentosStreamCompatibilityPolicy` | `DeploymentCentosStreamMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentCentosStreamMatchPolicy.java` |
| `DeploymentDebianCompatibilityPolicy` | `DeploymentDebianMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentDebianMatchPolicy.java` |
| `DeploymentDistributionCompatibilityPolicy` | `DeploymentDistributionMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentDistributionMatchPolicy.java` |
| `DeploymentDistributionCompatibilityRules` | `DeploymentDistributionMatchRules` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentDistributionMatchRules.java` |
| `DeploymentDistributionCompatibilityEvaluator` | `DeploymentDistributionMatcher` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentDistributionMatcher.java` |
| `DeploymentOracleLinuxCompatibilityPolicy` | `DeploymentOracleLinuxMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentOracleLinuxMatchPolicy.java` |
| `DeploymentRockyLinuxCompatibilityPolicy` | `DeploymentRockyLinuxMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentRockyLinuxMatchPolicy.java` |
| `DeploymentUbuntuCompatibilityPolicy` | `DeploymentUbuntuMatchPolicy` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentUbuntuMatchPolicy.java` |
| `DeploymentRuntimeCapabilityEvaluator` | `DeploymentRuntimeCapabilityMatcher` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/runtime/DeploymentRuntimeCapabilityMatcher.java` |
| `DeploymentSecretReferenceField` | `DeploymentSecretRefField` | `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/ui/DeploymentSecretRefField.java` |

## 嵌套类型与命名边界

`java.awt.Component`、`java.util.List` 等第三方及 JDK 类型不改。自有嵌套 Component 使用 Part；嵌套 Inventory 根据对象使用 LogList、SourceList、InstanceList，避免与 Java List 重名。

ServerId / SecretId 表示标识，ServerRef / SecretRef 表示业务引用，Identity 表示完整身份信息。Store 是具体数据存取，Storage 是存储能力组合入口。TaskResult 与资源清理状态继续独立。

Registry、Parser、Deployment、Capability、Application、Profile、Snapshot、Identity、Transcript 保留。ServiceSnapshot 执行复制，因此按实际行为调整为 ServiceValueCopier；状态数据的 Snapshot 保留。

以下列出生产文件内发生改名的嵌套声明，包含改名前后的外部类型名称：

| 原嵌套类型 | 新嵌套类型 |
| --- | --- |
| `AiModelInventoryPane.InventorySnapshot` | `AiModelListPane.ListSnapshot` |
| `DatabaseProjectInspector.Assessment` | `DatabaseProjectChecker.Report` |
| `DeploymentAgentPlan.Component` | `DeploymentAgentPlan.Part` |
| `DeploymentApplicationGraph.Component` | `DeploymentApplicationGraph.Part` |
| `DeploymentGitSourceFlow.PublicationSource` | `DeploymentGitSourceFlow.PublishSource` |
| `DeploymentGraphPublicationJournal.ComponentIndex` | `DeploymentGraphPublishActionLog.PartIndex` |
| `DeploymentMultiComponentPublicationRecovery.Component` | `DeploymentMultiPartPublishRecovery.Part` |
| `DeploymentNativeDatabaseFacts.Inventory` | `DeploymentNativeDatabaseFacts.InstanceList` |
| `DeploymentNativeDatabasePreparation.NativeDatabasePreparationException` | `DeploymentNativeDatabaseSetup.NativeDatabaseSetupException` |
| `DeploymentReferenceFlow.PreparedPublication` | `DeploymentRefFlow.PreparedPublish` |
| `DeploymentReferenceIntent.DeploymentReferenceKind` | `DeploymentRefIntent.DeploymentRefKind` |
| `DeploymentStandardAnalyzer.SnapshotAssessment` | `DeploymentStandardAnalyzer.SnapshotReport` |
| `DeploymentStandardPlanner.ComponentPlans` | `DeploymentStandardPlanner.PartPlans` |
| `DeploymentStaticPageReviewer.ComponentForm` | `DeploymentStaticPageReviewer.PartForm` |
| `DeploymentStaticSourcePreparation.ArchivedComponents` | `DeploymentStaticSourceSetup.ArchivedParts` |
| `ModRestartLedger.Preparation` | `ModRestartLedger.Setup` |
| `ModRestartLedger.PreparationLedger` | `ModRestartLedger.SetupLedger` |
| `ModRuntimeSession.Journal` | `ModRuntimeSession.ActionLog` |
| `PlatformLogService.Inventory` | `PlatformLogService.LogList` |
| `PlatformModContext.Publication` | `PlatformModContext.Publish` |
| `PlatformModCoordinator.Assessment` | `PlatformModFlow.Report` |
| `PlatformModInstallation.InstallationInventory` | `PlatformModInstallation.InstallationList` |
| `PlatformModInstallation.Inventory` | `PlatformModInstallation.SourceList` |
| `CredentialVault.CredentialWriteException` | `SecretVault.SecretWriteException` |
| `SftpTransfer.SftpSourcePreparation` | `SftpTransfer.SftpSourceSetup` |

## 测试与夹具文件

| 原类型 | 新类型 | 当前源码路径 |
| --- | --- | --- |
| `DesktopPersistenceIntegrationTest` | `DesktopStorageIntegrationTest` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/DesktopStorageIntegrationTest.java` |
| `PlatformPersistenceTest` | `PlatformStorageTest` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/PlatformStorageTest.java` |
| `AiPriorityRepositoryTest` | `AiPriorityStoreTest` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/ai/AiPriorityStoreTest.java` |
| `PlatformRepositoryTestFixture` | `PlatformStoreTestFixture` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/connect/PlatformStoreTestFixture.java` |
| `RepositoryTransactionExecutorTest` | `StoreTransactionRunnerTest` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/connect/StoreTransactionRunnerTest.java` |
| `ServerProfileRepositoryTest` | `ServerProfileStoreTest` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/server/ServerProfileStoreTest.java` |
| `BrowserRecoveryRepositoryTest` | `BrowserRecoveryStoreTest` | `src/app/db/src/test/java/gold/debug/windowstolinux/app/db/sshrecover/BrowserRecoveryStoreTest.java` |
| `SftpJournalTest` | `SftpActionLogTest` | `src/app/linux/src/test/java/gold/debug/windowstolinux/app/linux/sftp/SftpActionLogTest.java` |
| `SftpPublicationTest` | `SftpPublishTest` | `src/app/linux/src/test/java/gold/debug/windowstolinux/app/linux/sftp/SftpPublishTest.java` |
| `ArchitectureSourceInspector` | `ArchitectureSourceChecker` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/architecture/ArchitectureSourceChecker.java` |
| `JavadocCoverageInspector` | `JavadocCoverageChecker` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/architecture/JavadocCoverageChecker.java` |
| `PlatformModCoordinatorTest` | `PlatformModFlowTest` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/start/PlatformModFlowTest.java` |
| `PlatformReferenceLookupTest` | `PlatformRefLookupTest` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/start/PlatformRefLookupTest.java` |
| `PlatformServerReferenceTest` | `PlatformServerRefTest` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/start/PlatformServerRefTest.java` |
| `RepositoryHttpFixtureTest` | `StoreHttpFixtureTest` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/start/StoreHttpFixtureTest.java` |
| `RepositoryServiceFixture` | `StoreServiceFixture` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/start/StoreServiceFixture.java` |
| `VoiceConfigurationTest` | `VoiceConfigTest` | `src/app/main/src/test/java/gold/debug/windowstolinux/app/main/start/VoiceConfigTest.java` |
| `DevelopmentModDiscoveryTest` | `DevelopmentModFinderTest` | `src/app/mod/src/test/java/gold/debug/windowstolinux/app/mod/dev/DevelopmentModFinderTest.java` |
| `ModCodeInspectionTest` | `ModCodeCheckerTest` | `src/app/mod/src/test/java/gold/debug/windowstolinux/app/mod/load/ModCodeCheckerTest.java` |
| `ServiceSnapshotTest` | `ServiceValueCopierTest` | `src/app/mod/src/test/java/gold/debug/windowstolinux/app/mod/service/ServiceValueCopierTest.java` |
| `CredentialVaultH2Test` | `SecretVaultH2Test` | `src/app/secret/src/test/java/gold/debug/windowstolinux/app/secret/SecretVaultH2Test.java` |
| `CredentialVaultTest` | `SecretVaultTest` | `src/app/secret/src/test/java/gold/debug/windowstolinux/app/secret/SecretVaultTest.java` |
| `AiModelInventoryTest` | `AiModelListTest` | `src/app/ui/src/test/java/gold/debug/windowstolinux/app/ui/ai/AiModelListTest.java` |
| `PlatformComponentInteractionMigrationTest` | `PlatformPartInteractionMigrationTest` | `src/app/ui/src/test/java/gold/debug/windowstolinux/app/ui/control/PlatformPartInteractionMigrationTest.java` |
| `DesktopDisplayConfigurationTest` | `DesktopDisplayConfigTest` | `src/app/ui/src/test/java/gold/debug/windowstolinux/app/ui/display/DesktopDisplayConfigTest.java` |
| `DesktopUninstallCoordinatorTest` | `DesktopUninstallFlowTest` | `src/app/windows/src/test/java/gold/debug/windowstolinux/app/windows/uninstall/DesktopUninstallFlowTest.java` |
| `DesktopUpdateCoordinatorTest` | `DesktopUpdateFlowTest` | `src/app/windows/src/test/java/gold/debug/windowstolinux/app/windows/update/DesktopUpdateFlowTest.java` |
| `DeploymentApplicationInventoryTest` | `DeploymentApplicationListTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentApplicationListTest.java` |
| `DeploymentCodecCompatibility` | `DeploymentCodecMatch` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentCodecMatch.java` |
| `DeploymentConfigurationRepositoryTest` | `DeploymentConfigStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentConfigStoreTest.java` |
| `DeploymentExecutionRepositoryTest` | `DeploymentExecutionStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentExecutionStoreTest.java` |
| `DeploymentGraphRepositoryTest` | `DeploymentGraphStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentGraphStoreTest.java` |
| `DeploymentPayloadValidationTest` | `DeploymentPayloadCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPayloadCheckerTest.java` |
| `DeploymentProjectRepositoryTest` | `DeploymentProjectStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentProjectStoreTest.java` |
| `DeploymentPublicationAttemptReviewTest` | `DeploymentPublishAttemptReviewTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishAttemptReviewTest.java` |
| `DeploymentPublicationRepositoryTest` | `DeploymentPublishStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishStoreTest.java` |
| `DeploymentPublicationTemplateCodecTest` | `DeploymentPublishTemplateCodecTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentPublishTemplateCodecTest.java` |
| `DeploymentReferenceAssociationsTest` | `DeploymentRefAssociationsTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentRefAssociationsTest.java` |
| `DeploymentReferenceFlowTest` | `DeploymentRefFlowTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentRefFlowTest.java` |
| `DeploymentReleaseRepositoryTest` | `DeploymentReleaseStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentReleaseStoreTest.java` |
| `DeploymentRetainedReleaseRepositoryTest` | `DeploymentRetainedReleaseStoreTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/db/DeploymentRetainedReleaseStoreTest.java` |
| `DeploymentSourcePreparationMigrationTest` | `DeploymentSourceSetupMigrationTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/file/DeploymentSourceSetupMigrationTest.java` |
| `DeploymentSourcePreparerTest` | `DeploymentSourceSetupTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/file/DeploymentSourceSetupTest.java` |
| `DeploymentRepositoryServiceFixture` | `DeploymentStoreServiceFixture` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/file/DeploymentStoreServiceFixture.java` |
| `DeploymentGitPreparerTest` | `DeploymentGitSetupTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/file/git/DeploymentGitSetupTest.java` |
| `DeploymentPublicationChannelTest` | `DeploymentPublishChannelTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/linux/DeploymentPublishChannelTest.java` |
| `DeploymentConfigurationSnapshotTest` | `DeploymentConfigSnapshotTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/core/config/DeploymentConfigSnapshotTest.java` |
| `DeploymentConflictingPortPublicationTest` | `DeploymentConflictingPortPublishTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentConflictingPortPublishTest.java` |
| `DeploymentPendingPublicationAttemptTest` | `DeploymentPendingPublishAttemptTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentPendingPublishAttemptTest.java` |
| `DeploymentPreviousRunningPublicationTest` | `DeploymentPreviousRunningPublishTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentPreviousRunningPublishTest.java` |
| `DeploymentPublicationAcceptanceFixture` | `DeploymentPublishAcceptanceFixture` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentPublishAcceptanceFixture.java` |
| `DeploymentPublicationStatePeer` | `DeploymentPublishStatePeer` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentPublishStatePeer.java` |
| `DeploymentUnknownHealthPublicationTest` | `DeploymentUnknownHealthPublishTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/DeploymentUnknownHealthPublishTest.java` |
| `MixedProjectInspectorTest` | `MixedProjectCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/MixedProjectCheckerTest.java` |
| `ProjectComponentDiscoveryTest` | `ProjectPartFinderTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/component/ProjectPartFinderTest.java` |
| `DeploymentAnalysisCoordinatorTest` | `DeploymentAnalysisFlowTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/core/DeploymentAnalysisFlowTest.java` |
| `ProjectLanguageInspectorTest` | `ProjectLanguageCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/core/ProjectLanguageCheckerTest.java` |
| `CLanguageInspectorTest` | `CLanguageCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/c/CLanguageCheckerTest.java` |
| `DatabaseProjectInspectorTest` | `DatabaseProjectCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/db/DatabaseProjectCheckerTest.java` |
| `JavaLanguageInspectorTest` | `JavaLanguageCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/JavaLanguageCheckerTest.java` |
| `SpringBootDeploymentInspectorTest` | `SpringBootDeploymentCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/SpringBootDeploymentCheckerTest.java` |
| `JavaJarManifestInspectorTest` | `JavaJarManifestCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/java/jar/JavaJarManifestCheckerTest.java` |
| `NodeBuildInspectorTest` | `NodeBuildCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/NodeBuildCheckerTest.java` |
| `NodeLanguageInspectorTest` | `NodeLanguageCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/node/NodeLanguageCheckerTest.java` |
| `PythonBuildInspectorTest` | `PythonBuildCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/PythonBuildCheckerTest.java` |
| `PythonLanguageInspectorTest` | `PythonLanguageCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/ecosystem/python/PythonLanguageCheckerTest.java` |
| `ServiceMetadataInspectorTest` | `ServiceMetadataCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/service/ServiceMetadataCheckerTest.java` |
| `ToolchainDeclarationInspectorTest` | `ToolchainSpecCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/toolchain/ToolchainSpecCheckerTest.java` |
| `ContainerDeploymentInspectorTest` | `ContainerDeploymentCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/workload/ContainerDeploymentCheckerTest.java` |
| `StaticWebDeploymentInspectorTest` | `StaticWebDeploymentCheckerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/analyze/workload/StaticWebDeploymentCheckerTest.java` |
| `DeploymentStaticReviewAssemblerTest` | `DeploymentStaticReviewBuilderTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/DeploymentStaticReviewBuilderTest.java` |
| `BuildCompatibilityPolicyTest` | `BuildMatchPolicyTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/BuildMatchPolicyTest.java` |
| `DeploymentBuildRendererTest` | `DeploymentBuildWriterTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/DeploymentBuildWriterTest.java` |
| `DeploymentStandardBuildExecutorTest` | `DeploymentStandardBuildRunnerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/DeploymentStandardBuildRunnerTest.java` |
| `DeploymentStandardCandidateExecutorTest` | `DeploymentStandardCandidateRunnerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/DeploymentStandardCandidateRunnerTest.java` |
| `DeploymentBuildEnvironmentRendererTest` | `DeploymentBuildEnvironmentWriterTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/build/generation/script/DeploymentBuildEnvironmentWriterTest.java` |
| `DeploymentSqlitePreparationTest` | `DeploymentSqliteSetupTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/database/DeploymentSqliteSetupTest.java` |
| `MultiComponentDeploymentPlannerTest` | `MultiPartDeploymentPlannerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/plan/MultiPartDeploymentPlannerTest.java` |
| `DeploymentMultiComponentPublicationRecoveryRestartTest` | `DeploymentMultiPartPublishRecoveryRestartTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentMultiPartPublishRecoveryRestartTest.java` |
| `DeploymentMultiComponentPublicationExecutorTest` | `DeploymentMultiPartPublishRunnerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentMultiPartPublishRunnerTest.java` |
| `DeploymentPublicationRecoveryRestartTest` | `DeploymentPublishRecoveryRestartTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentPublishRecoveryRestartTest.java` |
| `DeploymentStandardPublicationRecoveryTest` | `DeploymentStandardPublishRecoveryTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentStandardPublishRecoveryTest.java` |
| `DeploymentStandardPublicationExecutorTest` | `DeploymentStandardPublishRunnerTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/publication/DeploymentStandardPublishRunnerTest.java` |
| `DeploymentHostCompatibilityEvaluatorTest` | `DeploymentHostMatcherTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/DeploymentHostMatcherTest.java` |
| `DeploymentDistributionCompatibilityEvaluatorTest` | `DeploymentDistributionMatcherTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/distro/DeploymentDistributionMatcherTest.java` |
| `DeploymentRuntimeCapabilityEvaluatorTest` | `DeploymentRuntimeCapabilityMatcherTest` | `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/service/standard/deploy/support/runtime/DeploymentRuntimeCapabilityMatcherTest.java` |
