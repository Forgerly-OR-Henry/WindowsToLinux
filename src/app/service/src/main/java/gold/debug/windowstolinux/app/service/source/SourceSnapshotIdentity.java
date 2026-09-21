package gold.debug.windowstolinux.app.service.source;
import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Revalidates private snapshot members without exposing source contents. / 重新验证私有快照成员，不泄露源码内容。 */
public final class SourceSnapshotIdentity {
    /** Prevents construction. / 禁止实例化。 */
    private SourceSnapshotIdentity(){}
    /** Hashes safe regular files and their normalized member identities. / 对安全普通文件及其规范成员身份计算摘要。
     * @param directory private frozen snapshot / 私有冻结快照
     * @return content and member digest / 内容及成员摘要
     * @throws Exception when boundaries or content cannot be verified / 无法验证边界或内容时
     */
    public static String digest(Path directory)throws Exception{
        var validator=new SourceBoundaryValidator();Path root=validator.validateSourceDirectory(directory);
        var manifest=validator.collect(root);var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
        for(var entry:manifest.entries()){
            validator.verifyUnchangedRegularFile(root,entry);
            digest.update((entry.relativePath().length()+":"+entry.relativePath()+":"+entry.byteCount()+":").getBytes(StandardCharsets.UTF_8));
            try(var stream=Files.newInputStream(entry.path(),LinkOption.NOFOLLOW_LINKS)){int n;while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);}
            validator.verifyUnchangedRegularFile(root,entry);
        }return HexFormat.of().formatHex(digest.digest());
    }
}
