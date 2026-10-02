package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;

/** Recognizes supported Windows symlinks and directory junctions without following them. / 不跟随地识别 Windows 符号链接及目录联接点。 */
public final class WindowsLinks {
    private WindowsLinks() { }
    /** Reads a supported reparse target; other tags fail explicitly. / 读取支持的重解析目标，其他标记明确失败。 */
    public static Optional<String> target(Path path) throws IOException {
        if(!Platform.isWindows())return Files.isSymbolicLink(path)?Optional.of(Files.readSymbolicLink(path).toString()):Optional.empty();
        int attributes=Kernel32.INSTANCE.GetFileAttributes(path.toAbsolutePath().toString());
        if(attributes==WinBase.INVALID_FILE_ATTRIBUTES)throw new IOException("File attributes are unavailable");
        if((attributes&WinNT.FILE_ATTRIBUTE_REPARSE_POINT)==0)return Optional.empty();
        var handle=Kernel32.INSTANCE.CreateFile(path.toAbsolutePath().toString(),0,WinNT.FILE_SHARE_READ|WinNT.FILE_SHARE_WRITE|WinNT.FILE_SHARE_DELETE,null,WinNT.OPEN_EXISTING,WinNT.FILE_FLAG_BACKUP_SEMANTICS|WinNT.FILE_FLAG_OPEN_REPARSE_POINT,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(handle))throw new IOException("Reparse handle unavailable");
        try(var data=new Memory(16384)){
            var count=new IntByReference();
            if(!Kernel32.INSTANCE.DeviceIoControl(handle,0x000900A8,null,0,data,(int)data.size(),count,null))throw new IOException("Reparse target unavailable");
            int tag=data.getInt(0),base=tag==0xA0000003?16:tag==0xA000000C?20:-1;
            if(base<0)throw new IOException("Unsupported Windows reparse point");
            int offset=Short.toUnsignedInt(data.getShort(8)),length=Short.toUnsignedInt(data.getShort(10));
            if(length%2!=0||base+offset+length>count.getValue())throw new IOException("Invalid reparse target");
            String value=new String(data.getByteArray(base+offset,length),java.nio.charset.StandardCharsets.UTF_16LE);
            if(value.startsWith("\\??\\UNC\\"))value="\\\\"+value.substring(8);
            else if(value.startsWith("\\??\\"))value=value.substring(4);
            return Optional.of(value);
        }finally{if(!Kernel32.INSTANCE.CloseHandle(handle))throw new IOException("Reparse handle release failed");}
    }
    /** Creates a local directory junction when appropriate, otherwise a symbolic link. / 合适时创建本机目录联接点，否则创建符号链接。 */
    public static void create(Path path,Path target)throws IOException{
        Path absolute=target.isAbsolute()?target:path.toAbsolutePath().getParent().resolve(target).normalize();
        if(!Platform.isWindows()||!Files.isDirectory(absolute)||absolute.toString().startsWith("\\\\")){Files.createSymbolicLink(path,target);return;}
        junction(path, absolute, true);
    }
    /** Updates an existing directory junction in place without deleting the old link. / 原位更新既有目录联接点而不删除旧链接。
     * @param path existing junction / 既有联接点
     * @param target existing absolute directory target / 既有绝对目录目标
     * @throws IOException when the object is not a junction or the native update fails / 对象不是联接点或原生更新失败时
     */
    public static void retargetDirectoryJunction(Path path, Path target) throws IOException {
        var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!Platform.isWindows()||!attributes.isDirectory()||attributes.isSymbolicLink()||target(path).isEmpty())throw new IOException("Not a directory junction");
        if(!target.isAbsolute()||!Files.isDirectory(target)||target.toString().startsWith("\\\\"))throw new IOException("A local directory junction target is required");
        junction(path,target,false);
    }
    /** Writes one mount-point reparse record while preserving an existing link when requested. / 写入目录联接点记录，按请求保留既有链接。
     * @param path link position / 链接位置
     * @param absolute absolute directory target / 绝对目录目标
     * @param create whether to create the initial directory / 是否创建初始目录
     * @throws IOException when native reparse publication fails / 原生重解析发布失败时
     */
    private static void junction(Path path, Path absolute, boolean create) throws IOException {
        String substitute="\\??\\"+absolute.toAbsolutePath(),print=absolute.toAbsolutePath().toString();
        byte[] first=substitute.getBytes(java.nio.charset.StandardCharsets.UTF_16LE),second=print.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        int total=16+first.length+2+second.length+2;
        if(total>16384)throw new IOException("Junction target exceeds the native limit");
        if(create)Files.createDirectory(path);
        var handle=Kernel32.INSTANCE.CreateFile(path.toAbsolutePath().toString(),WinNT.GENERIC_WRITE,0,null,WinNT.OPEN_EXISTING,WinNT.FILE_FLAG_BACKUP_SEMANTICS|WinNT.FILE_FLAG_OPEN_REPARSE_POINT,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(handle)){if(create)Files.delete(path);throw new IOException("Junction handle unavailable");}
        boolean set=false;
        try(var data=new Memory(total)){
            data.clear();data.setInt(0,0xA0000003);data.setShort(4,(short)(total-8));data.setShort(8,(short)0);data.setShort(10,(short)first.length);data.setShort(12,(short)(first.length+2));data.setShort(14,(short)second.length);data.write(16,first,0,first.length);data.write(18+first.length,second,0,second.length);
            set=Kernel32.INSTANCE.DeviceIoControl(handle,0x000900A4,data,total,null,0,new IntByReference(),null);
            if(!set)throw new IOException("Junction creation failed");
        }finally{if(!Kernel32.INSTANCE.CloseHandle(handle))throw new IOException("Junction handle release failed");if(create&&!set)Files.delete(path);}
    }
}
