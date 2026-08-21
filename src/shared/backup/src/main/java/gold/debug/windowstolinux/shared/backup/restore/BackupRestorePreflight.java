package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Performs all compatibility and local-boundary checks before restore mutation. / 在恢复修改前执行全部兼容性与本地边界检查。 */
public final class BackupRestorePreflight {
    /** Returns explicit compatibility evidence or stops before mutation. / 返回显式兼容性证据，否则在修改前停止。 */
    public List<String> verify(BackupRestorePlan plan) throws BackupException {
        List<String> evidence = new ArrayList<>();
        if (!Files.isDirectory(plan.candidate().root(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(plan.candidate().root())) {
            throw failed("local restore candidate is not an existing non-link directory");
        }
        evidence.add("archive integrity and isolated local extraction are bound to the restore plan");
        RestoreTargetProfile target = plan.target();
        if (!target.managedRootWritable() || !target.requiredPortsAvailable() || target.foreignApplicationConflict()) {
            throw failed("target managed root, port availability or ownership conflict preflight failed");
        }
        long requiredBytes;
        try {
            requiredBytes = Math.multiplyExact(plan.validation().verifiedBytes(), 2L);
        } catch (ArithmeticException exception) {
            throw failed("restore space requirement overflowed its supported boundary", exception);
        }
        if (target.availableBytes() < requiredBytes) {
            throw failed("target free space is below the two-copy restore safety boundary");
        }
        evidence.add("managed root, ownership, required ports and two-copy free space were verified");

        BackupRuntime source = plan.validation().manifest().inventory().runtime();
        if (!source.runtimeKind().equals(target.runtimeKind())) {
            throw failed("target runtime kind differs from the backup");
        }
        if (plan.materialKind() == RestoreMaterialKind.SOURCE_REBUILD) {
            if (!target.sourceRebuildSupported()) {
                throw failed("target cannot rebuild the source-backed restore candidate");
            }
            evidence.add("source-backed restore will rebuild for the verified target architecture");
        } else {
            if (!source.architecture().equals(target.architecture())) {
                throw failed("binary restore architecture is incompatible with the target");
            }
            boolean exactPlatform = source.distroId().equals(target.distroId())
                    && source.distroVersion().equals(target.distroVersion())
                    && source.runtimeVersion().equals(target.runtimeVersion());
            if (!exactPlatform && !target.binaryExperimentApproved()) {
                throw failed("cross-distribution or runtime binary restore requires explicit experimental approval");
            }
            evidence.add(exactPlatform
                    ? "binary distribution, runtime and architecture match exactly"
                    : "binary platform difference was explicitly approved as an experimental candidate");
        }

        BackupDatabaseType sourceDatabase = plan.validation().manifest().inventory().database().type();
        if (sourceDatabase != target.databaseType()) {
            throw failed("target database family differs from the backup");
        }
        if (sourceDatabase != BackupDatabaseType.NONE) {
            if (!target.databaseCompatibilityVerified()
                    || !versionFamily(plan.validation().manifest().inventory().database().engineVersion())
                    .equals(versionFamily(target.databaseEngineVersion()))) {
                throw failed("target database compatibility or major version family was not verified");
            }
            evidence.add("database family and major version compatibility were verified before restore");
        } else {
            evidence.add("backup and target both declare no database");
        }
        evidence.addAll(target.evidence());
        return List.copyOf(evidence);
    }

    private static String versionFamily(String version) throws BackupException {
        var matcher = Pattern.compile("(?:^|[^0-9])([0-9]+)(?:[^0-9]|$)").matcher(version);
        if (!matcher.find()) throw failed("database version evidence has no major family");
        return matcher.group(1);
    }

    private static BackupException failed(String diagnostic) {
        return BackupException.create(BackupFailureType.RESTORE_PREFLIGHT_FAILED, diagnostic);
    }

    private static BackupException failed(String diagnostic, Throwable cause) {
        return BackupException.create(BackupFailureType.RESTORE_PREFLIGHT_FAILED, diagnostic, cause);
    }
}
