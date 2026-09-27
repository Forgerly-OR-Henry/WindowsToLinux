package gold.debug.windowstolinux.mod.deployment.service.core;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.source.SourceService;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;
import gold.debug.windowstolinux.mod.deployment.source.*;
import gold.debug.windowstolinux.mod.deployment.source.git.*;

/** Prepares an authorized source and saves its actual location and frozen digest with one project revision. / 准备已授权来源，并将实际位置及冻结摘要存入一个项目修订。 */
public final class DeploymentProjectSourceRecorder {
    /** Original source capability. / 原来源能力。 */
    private final SourceService sources;

    /** Original task-bound project database. / 原任务绑定项目数据库。 */
    private final DeploymentProjectRepository projects;

    /** Controlled Git preparer. / 受控Git准备器。 */
    private final DeploymentGitPreparer git;

    /** Binds the real Mod capabilities and explicit native Git program. / 绑定真实Mod能力及明确原生Git程序。
     * @param context active Mod context / 活动Mod上下文
     * @param gitExecutable absolute native Git program / 绝对原生Git程序 */
    public DeploymentProjectSourceRecorder(ModContext context, Path gitExecutable) {
        Objects.requireNonNull(context);
        sources = context.source();
        projects = new DeploymentProjectRepository(context.db());
        git = new DeploymentGitPreparer(context, gitExecutable);
    }

    /** Selects a local directory, freezes it and saves the complete observed project revision. / 选择本地目录、冻结后保存完整已观察项目修订。
     * @param task original active task / 原活动任务
     * @param title actual user-selection title / 实际用户选择标题
     * @param id new project identity; must match expected on update / 新项目身份；更新时须与预期一致
     * @param displayName selected project name / 选定项目名
     * @param expected original reviewed row or absence for creation / 原已审阅记录，创建时为空
     * @param observedAt actual millisecond observation / 实际毫秒观测
     * @param maximumBytes selected byte budget / 选定字节预算
     * @param maximumEntries discovery budget / 发现预算
     * @return saved revision and still-owned frozen source / 已存修订与仍持有的冻结来源
     * @throws SQLException if project persistence conflicts or fails / 项目持久化冲突或失败时
     * @throws ApiException if authorization, preparation or durability fails / 授权、准备或持久性失败时 */
    public PreparedLocal prepareLocal(TaskContext task, String title, UUID id, String displayName,
            Optional<DeploymentProjectRecord> expected, Instant observedAt, long maximumBytes, int maximumEntries)
            throws SQLException, ApiException {
        var selection = sources.selectDirectory(title, task);
        DeploymentSourceMaterial material = new DeploymentSourcePreparer(sources).prepare(selection, maximumBytes,
                maximumEntries, task);
        try {
            var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.LOCAL,
                    sources.location(selection, task), Optional.empty(), Optional.empty(),
                    material.snapshot().sha256());
            var project = record(id, displayName, source, expected, observedAt);
            save(task, expected, project);
            return new PreparedLocal(project, material);
        } catch (SQLException | ApiException | RuntimeException failure) {
            try {
                material.close();
            } catch (ApiException cleanup) {
                if (failure instanceof ApiException api && api.problem().code() == ProblemCode.UNKNOWN)
                    failure.addSuppressed(cleanup);
                else {
                    cleanup.addSuppressed(failure);
                    throw cleanup;
                }
            }
            throw failure;
        }
    }

    /** Pins and freezes Git before saving the actual commit and content digest. / 固定并冻结Git后保存实际提交及内容摘要。
     * @param task original active task / 原活动任务
     * @param request original reviewed Git request / 原已审阅Git请求
     * @param id new project identity; must match expected on update / 新项目身份；更新时须与预期一致
     * @param displayName selected project name / 选定项目名
     * @param expected original reviewed row or absence for creation / 原已审阅记录，创建时为空
     * @param observedAt actual millisecond observation / 实际毫秒观测
     * @param maximumBytes selected source budget / 选定源码预算
     * @param maximumEntries discovery budget / 发现预算
     * @return saved revision and still-owned Git material / 已存修订与仍持有的Git素材
     * @throws SQLException if project persistence conflicts or fails / 项目持久化冲突或失败时
     * @throws ApiException if preparation or durability fails / 准备或持久性失败时 */
    public PreparedGit prepareGit(TaskContext task, DeploymentGitRequest request, UUID id, String displayName,
            Optional<DeploymentProjectRecord> expected, Instant observedAt, long maximumBytes, int maximumEntries)
            throws SQLException, ApiException {
        DeploymentGitMaterial material = git.prepare(request, maximumBytes, maximumEntries, task);
        try {
            if (!request.remote().equals(material.remote()))
                throw new IllegalArgumentException("Git material belongs to another remote");
            var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT, material.remote().location(),
                    Optional.of(reference(request.reference())), Optional.of(material.commit()),
                    material.source().snapshot().sha256());
            var project = record(id, displayName, source, expected, observedAt);
            save(task, expected, project);
            return new PreparedGit(project, material);
        } catch (SQLException | ApiException | RuntimeException failure) {
            try {
                material.close();
            } catch (ApiException cleanup) {
                if (failure instanceof ApiException api && api.problem().code() == ProblemCode.UNKNOWN)
                    failure.addSuppressed(cleanup);
                else {
                    cleanup.addSuppressed(failure);
                    throw cleanup;
                }
            }
            throw failure;
        }
    }

    /** Builds a complete revision with caller-observed time, retaining stable identity on update. / 使用调用方观测时间构建完整修订，更新时保持稳定身份。
     * @param id selected project identity / 选定项目身份
     * @param displayName selected display name / 选定显示名
     * @param source actual frozen source / 实际冻结来源
     * @param expected original row or absence / 原记录或不存在
     * @param observedAt caller observation / 调用方观测
     * @return complete revision / 完整修订 */
    private static DeploymentProjectRecord record(UUID id, String displayName, DeploymentProjectSource source,
            Optional<DeploymentProjectRecord> expected, Instant observedAt) {
        Objects.requireNonNull(expected);
        return new DeploymentProjectRecord(id, displayName, source,
                expected.map(DeploymentProjectRecord::createdAt).orElse(observedAt), observedAt);
    }

    /** Uses exactly one managed transaction without retrying an unknown write. / 仅使用一个受管事务，不重放未知写入。
     * @param task original task / 原任务
     * @param expected old row or absence / 旧记录或不存在
     * @param project new complete project / 新完整项目
     * @throws SQLException if original row differs / 原记录不同时
     * @throws ApiException if transaction confirmation is unknown / 事务确认未知时 */
    private void save(TaskContext task, Optional<DeploymentProjectRecord> expected, DeploymentProjectRecord project)
            throws SQLException, ApiException {
        if (expected.isPresent())
            projects.update(task, expected.get(), project);
        else
            projects.create(task, project);
    }

    /** Encodes the selected reference kind without collisions between branch, tag and commit. / 无冲突地编码选定引用类别。
     * @param ref original selected reference / 原选定引用
     * @return exact persisted text / 精确持久文本 */
    private static String reference(DeploymentGitReference ref) {
        return switch (ref) {
            case DeploymentGitReference.DefaultBranch ignored -> "DEFAULT:HEAD";
            case DeploymentGitReference.Branch branch -> "BRANCH:" + branch.value();
            case DeploymentGitReference.Tag tag -> "TAG:" + tag.value();
            case DeploymentGitReference.Commit commit -> "COMMIT:" + commit.value();
        };
    }

    /** Local result retaining the original platform source lease. / 保留原平台来源租约的本地结果。
     * @param project saved project revision / 已存项目修订
     * @param material frozen source material / 冻结来源素材 */
    public record PreparedLocal(DeploymentProjectRecord project,
            DeploymentSourceMaterial material) implements AutoCloseable {
        /** Releases the actual frozen source. / 释放实际冻结来源。
         * @throws ApiException if cleanup is unknown / 清理未知时 */
        @Override
        public void close() throws ApiException {
            material.close();
        }
    }

    /** Git result retaining both source and archive leases. / 保留来源与归档双重租约的Git结果。
     * @param project saved project revision / 已存项目修订
     * @param material pinned Git material / 固定Git素材 */
    public record PreparedGit(DeploymentProjectRecord project,
            DeploymentGitMaterial material) implements AutoCloseable {
        /** Releases actual Git source and archive. / 释放实际Git来源及归档。
         * @throws ApiException if cleanup is unknown / 清理未知时 */
        @Override
        public void close() throws ApiException {
            material.close();
        }
    }
}
