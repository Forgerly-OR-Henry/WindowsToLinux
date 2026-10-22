package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DeploymentAgentToolchainProgramStoreTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();

    static final String ACTION = "a".repeat(32);

    @Test
    void explicitManifestPinsCompleteSourceLoaderInstallerAndContractBeforeLayoutDerivation() throws Exception {
        var manifest = JSON.readTree(DeploymentAgentToolchainProgramStore.resource("agent-controller-program-manifest.json"));
        String controller = DeploymentAgentToolchainPrograms.controller();
        String loader = DeploymentAgentToolchainProgramStore.resource("agent-controller-loader.py");
        String installer = DeploymentAgentToolchainProgramStore.resource("agent-controller-install.py");
        String contract = DeploymentAgentToolchainProgramStore.contract();
        DeploymentAgentToolchainProgramStore.requireManifest(manifest, controller, loader, installer, contract);
        assertEquals("9cd73cbb2a80d77638cf60da0246b391bf4f59935b7982c6662a02f6da3f9e9e", manifest.path("defaultControllerSha256").asText());
        for (int position = 0; position < 4; position++) {
            int changed = position;
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainProgramStore.requireManifest(manifest,
                controller + (changed == 0 ? "# changed" : ""), loader + (changed == 1 ? "# changed" : ""),
                installer + (changed == 2 ? "# changed" : ""), contract + (changed == 3 ? "changed" : "")));
        }
        var custom = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/a/lib", "/srv/a/apps", "/srv/a/state", "/srv/a/config", "/srv/a/data"));
        assertNotEquals(DeploymentAgentToolchainProgramStore.version(LAYOUT), DeploymentAgentToolchainProgramStore.version(custom));
        assertNotEquals(DeploymentAgentToolchainProgramStore.sourceSha256(LAYOUT), DeploymentAgentToolchainProgramStore.sourceSha256(custom));
        assertTrue(DeploymentAgentToolchainProgramStore.path(custom).startsWith(custom.toolchains()));
        assertFalse(DeploymentAgentToolchainProgramStore.loader(custom).contains("managed-helper"));
        assertTrue(DeploymentAgentToolchainProgramStore.installer(custom).contains("PROGRAM_SOURCE"));
    }

    @Test
    void installedCreationRejectsInlineRecipeWhileRecoveryRetainsOriginalInlineBytes() {
        var base = DeploymentAgentToolchainTrackedTest.recipe("PREPARE", UUID.randomUUID().toString());
        var inline = DeploymentAgentToolchainTracked.create(base.taskId(), base.serverId(), DeploymentAgentToolchainTrackedTest.FINGERPRINT,
            base.layout(), base.phase(), base.operationId(), base.originalArgv(), true);
        byte[] original = inline.bytes();
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainProgramStore.trackedCommand("prepare-tracked", inline));
        assertDoesNotThrow(() -> DeploymentAgentToolchainPrograms.trackedCommand(inline));
        for (String verb : List.of("observe-tracked", "cleanup-tracked")) {
            String script = DeploymentAgentToolchainProgramStore.trackedCommand(verb, inline);
            assertEquals(inline.frame(verb), DeploymentAgentToolchainProgramStore.arguments(script, inline.layout()));
            assertArrayEquals(original, inline.bytes());
        }
        assertDoesNotThrow(() -> DeploymentAgentToolchainProgramStore.trackedCommand("prepare-tracked", base));
    }

    @Test
    void completeStoreReceiptsPreserveOriginalInstallationAndRejectPartialOrFalseReady() throws Exception {
        String task = UUID.randomUUID().toString();
        var ready = observation(task, ACTION, true);
        var parsed = DeploymentAgentToolchainProgramStore.receipt(DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + ready + "\n"), task, ACTION, LAYOUT, false);
        assertEquals(ready, parsed);
        assertNotEquals(task, parsed.path("installTask").asText());
        var missing = observation(task, ACTION, false);
        assertEquals("MISSING", DeploymentAgentToolchainProgramStore.receipt(DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + missing + "\n"), task, ACTION, LAYOUT, false).path("status").asText());
        for (String field : List.of("format", "contract", "version", "sourceSha256", "path", "layoutSha256", "task", "action", "status", "installTask", "installOperation")) {
            var changed = ready.deepCopy().put(field, "invalid");
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainProgramStore.receipt(DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + changed + "\n"), task, ACTION, LAYOUT, false), field);
        }
        for (String bad : List.of("{}", ready.toString() + "{}", ready.toString().replace("\"status\":\"READY\"", "\"status\":\"READY\",\"status\":\"READY\""), ready.deepCopy().put("sourceBytes", 1).toString()))
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainProgramStore.receipt(DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + bad + "\n"), task, ACTION, LAYOUT, false));
        var truncated = new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), "AGENT_CONTROLLER_STORE=" + ready + "\n", "", true)), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainProgramStore.receipt(truncated, task, ACTION, LAYOUT, false));
    }

    @Test
    void controlledFdFixturesRetainLocksOriginsPartialStagesAndSameFdBytesWithoutNativeLinuxClaims() throws Exception {
        String fixture = "LOADER = " + JSON.writeValueAsString(DeploymentAgentToolchainProgramStore.loader(LAYOUT)) + "\nINSTALLER = "
            + JSON.writeValueAsString(DeploymentAgentToolchainProgramStore.installer(LAYOUT)) + "\n" + """
            import sys, types, stat, json, hashlib, io, contextlib, builtins
            class Node:
                serial=0
                def __init__(self, mode, data=b''):
                    Node.serial+=1
                    self.mode,self.data,self.uid,self.gid,self.nlink,self.ino,self.clock=mode,data,0,0,1,Node.serial,1
                def info(self):
                    return types.SimpleNamespace(st_dev=1,st_ino=self.ino,st_mode=self.mode,st_uid=self.uid,st_gid=self.gid,st_nlink=self.nlink,st_size=len(self.data),st_mtime_ns=self.clock,st_ctime_ns=self.clock)
            class FS(types.ModuleType):
                def __init__(self):
                    super().__init__('os')
                    for i,k in enumerate(['O_RDONLY','O_WRONLY','O_RDWR','O_CREAT','O_EXCL','O_DIRECTORY','O_NOFOLLOW','O_CLOEXEC','O_NONBLOCK']): setattr(self,k,1<<i)
                    self.nodes={'/':Node(stat.S_IFDIR|0o755)};self.fds={};self.locks={};self.serial=10;self.busy=False;self.failrename=False;self.readhook=None;self.listChecks=0
                def path(self,name,fd=None): return name if name.startswith('/') else self.fds[fd][0].rstrip('/')+'/'+name
                def dirs(self,path):
                    cursor=''
                    for p in path.split('/')[1:]:
                        cursor+='/'+p
                        self.nodes.setdefault(cursor,Node(stat.S_IFDIR|0o755))
                def open(self,name,flags,mode=0o777,dir_fd=None):
                    p=self.path(name,dir_fd)
                    if p not in self.nodes:
                        if not flags & self.O_CREAT: raise FileNotFoundError(p)
                        self.nodes[p]=Node(stat.S_IFREG|mode)
                    elif flags & self.O_CREAT and flags & self.O_EXCL: raise FileExistsError(p)
                    n=self.nodes[p]
                    if stat.S_ISLNK(n.mode) and flags & self.O_NOFOLLOW: raise OSError('controlled nofollow')
                    if flags & self.O_DIRECTORY and not stat.S_ISDIR(n.mode): raise OSError('controlled not-directory')
                    if not stat.S_ISDIR(n.mode) and not flags & (self.O_WRONLY|self.O_CREAT): assert flags & self.O_NONBLOCK, 'special-file open may block before fstat'
                    self.serial+=1;self.fds[self.serial]=[p,n,0];return self.serial
                def close(self,fd): self.fds.pop(fd);self.locks.pop(fd,None)
                def fstat(self,fd): return self.fds[fd][1].info()
                def read(self,fd,count):
                    p,n,offset=self.fds[fd]
                    if self.readhook is not None:
                        hook,self.readhook=self.readhook,None;hook(p,n)
                    body=n.data[offset:offset+count];self.fds[fd][2]+=len(body);return body
                def write(self,fd,data):
                    n=self.fds[fd][1];body=bytes(data);n.data+=body;n.clock+=1;return len(body)
                def fchmod(self,fd,mode):
                    n=self.fds[fd][1];n.mode=stat.S_IFMT(n.mode)|mode;n.clock+=1
                def fsync(self,fd): assert fd in self.fds
                def geteuid(self): return 0
                def mkdir(self,name,mode=0o777,dir_fd=None):
                    p=self.path(name,dir_fd)
                    if p in self.nodes: raise FileExistsError(p)
                    self.nodes[p]=Node(stat.S_IFDIR|mode)
                def listdir(self,fd):
                    p=self.fds[fd][0]
                    if p+'/.install.lock' in self.nodes:
                        assert any(self.fds[k][0]==p+'/.install.lock' for k in self.locks), 'lock closed before observation'
                        self.listChecks+=1
                    return [q[len(p)+1:] for q in self.nodes if q.startswith(p+'/') and '/' not in q[len(p)+1:]]
                def fsencode(self,text): return text.encode()
            class Rename:
                def __init__(self,fs): self.fs=fs
                def __call__(self,oldfd,old,newfd,new,flags):
                    fs=self.fs;a=fs.path(old.decode(),oldfd);b=fs.path(new.decode(),newfd)
                    assert flags==1
                    if fs.failrename or b in fs.nodes: return -1
                    for p in list(fs.nodes):
                        if p==a or p.startswith(a+'/'): fs.nodes[b+p[len(a):]]=fs.nodes.pop(p)
                    return 0
            def environment():
                fs=FS();fcntl=types.ModuleType('fcntl');fcntl.LOCK_SH=1;fcntl.LOCK_EX=2;fcntl.LOCK_NB=4
                def flock(fd,flags):
                    assert fd in fs.fds
                    if fs.busy: raise BlockingIOError('controlled lock contention')
                    fs.locks[fd]=flags
                fcntl.flock=flock
                ctypes=types.ModuleType('ctypes');ctypes.c_int=int;ctypes.c_char_p=bytes;ctypes.c_uint=int;ctypes.get_errno=lambda:17
                ctypes.CDLL=lambda *a,**k:types.SimpleNamespace(renameat2=Rename(fs))
                sys.modules.update(os=fs,fcntl=fcntl,ctypes=ctypes)
                ns={'__name__':'controlled_test'};exec(compile(LOADER,'<controlled-loader>','exec'),ns)
                fs.dirs(ns['STORE_ROOT'])
                return fs,ns
            task='10000000-0000-0000-0000-000000000001';other='20000000-0000-0000-0000-000000000002';operation='a'*32
            def fail(call):
                try: call()
                except Exception: return
                raise AssertionError('unsafe fixture was accepted')
            def installed():
                fs,ns=environment();install={'__name__':'controlled_installer'}
                exec(compile(INSTALLER,'<controlled-installer>','exec'),install)
                with contextlib.redirect_stdout(io.StringIO()): install['install_main']([task,operation])
                assert not fs.fds
                return fs,ns,install
            fs,ns=environment();assert ns['store_ready']() is None;assert not fs.fds
            helper=ns['STORE_ROOT'].rsplit('/',2)[0]
            fs.nodes={p:n for p,n in fs.nodes.items() if not (p==helper or p.startswith(helper+'/'))}
            install={'__name__':'controlled_installer'};exec(compile(INSTALLER,'<controlled-installer>','exec'),install)
            original_paths=set(fs.nodes);fail(lambda:install['install_main']([task,operation]));assert set(fs.nodes)==original_paths and not fs.fds
            fs,ns,install=installed();ready=ns['store_ready']();assert ready[0]['installTask']==task;assert fs.listChecks>0;assert not fs.fds
            out=io.StringIO()
            with contextlib.redirect_stdout(out): install['install_main']([other,'b'*32])
            value=json.loads(out.getvalue().split('=',1)[1]);assert value['task']==other and value['installTask']==task and value['installOperation']==operation
            assert value['requestedInstallOperation']=='b'*32 and not fs.fds
            original=ready[1];seen=[]
            def checked_compile(source,*args):
                seen.append(source);return builtins.compile('assert PROGRAM_SOURCE == expected_source','<controlled-execution>','exec')
            ns['compile']=checked_compile
            ns['exec']=lambda code,scope:builtins.exec(code,dict(scope,expected_source=original))
            ns['store_main'](['invoke',other,operation,'bind','fixedbinding','30',operation]);assert seen==[original]
            fail(lambda:ns['store_main'](['invoke',other,'b'*32,'bind','fixedbinding','30',operation]));assert seen==[original]
            path=ns['STORE_ROOT']+'/'+ns['VERSION_SHA256']
            for mutation in ['symlink','hardlink','writable','owner','fifo','changedbytes','missingreceipt','badreceipt','directorywrite','lockfifo','partial']:
                fs,ns,install=installed();path=ns['STORE_ROOT']+'/'+ns['VERSION_SHA256'];source=fs.nodes[path+'/controller.py']
                if mutation=='symlink': source.mode=stat.S_IFLNK|0o444
                if mutation=='hardlink': source.nlink=2
                if mutation=='writable': source.mode=stat.S_IFREG|0o644
                if mutation=='owner': source.uid=1000
                if mutation=='fifo': source.mode=stat.S_IFIFO|0o444
                if mutation=='changedbytes': source.data+=b'changed'
                if mutation=='missingreceipt': del fs.nodes[path+'/install-receipt.json']
                if mutation=='badreceipt': fs.nodes[path+'/install-receipt.json'].data=b'{}'
                if mutation=='directorywrite': fs.nodes[ns['STORE_ROOT']].mode=stat.S_IFDIR|0o777
                if mutation=='lockfifo': fs.nodes[ns['STORE_ROOT']+'/.install.lock'].mode=stat.S_IFIFO|0o600
                if mutation=='partial': fs.nodes[ns['STORE_ROOT']+'/.install-'+ns['VERSION_SHA256']+'-'+'f'*32]=Node(stat.S_IFDIR|0o700)
                fail(lambda:ns['store_ready']());assert not fs.fds,mutation
            fs,ns,install=installed();fs.busy=True;fail(lambda:ns['store_ready']());assert not fs.fds
            fs,ns,install=installed();path=ns['STORE_ROOT']+'/'+ns['VERSION_SHA256']+'/controller.py'
            old=fs.nodes[path];fs.readhook=lambda p,n:fs.nodes.__setitem__(p,Node(stat.S_IFREG|0o444,b'path replacement')) if p==path else None
            # The first receipt read consumes this hook: constrain the source-read hook directly.
            parent=ns['store_walk'](ns['STORE_ROOT']+'/'+ns['VERSION_SHA256'])
            fs.readhook=lambda p,n:fs.nodes.__setitem__(p,Node(stat.S_IFREG|0o444,b'path replacement'))
            assert ns['store_read'](parent,'controller.py',1048576,ns['SOURCE_SHA256'])==old.data
            fs.close(parent);assert not fs.fds
            fs,ns,install=installed();parent=ns['store_walk'](ns['STORE_ROOT']+'/'+ns['VERSION_SHA256'])
            fs.readhook=lambda p,n:setattr(n,'clock',n.clock+1)
            fail(lambda:ns['store_read'](parent,'controller.py',1048576,ns['SOURCE_SHA256']));fs.close(parent);assert not fs.fds
            fs,ns=environment();install={'__name__':'controlled_installer'};exec(compile(INSTALLER,'<controlled-installer>','exec'),install)
            fs.failrename=True;fail(lambda:install['install_main']([task,operation]));assert not fs.fds
            retained=set(fs.nodes);assert any('/.install-'+ns['VERSION_SHA256']+'-' in p for p in retained)
            fail(lambda:ns['store_ready']());assert retained==set(fs.nodes) and not fs.fds
            fs,ns,install=installed();old_version=ns['VERSION_SHA256'];new_version='f'*64
            upgraded=INSTALLER.replace(old_version,new_version);new={'__name__':'controlled_upgrade'};exec(compile(upgraded,'<controlled-upgrade>','exec'),new)
            with contextlib.redirect_stdout(io.StringIO()): new['install_main']([other,'c'*32])
            assert ns['STORE_ROOT']+'/'+old_version in fs.nodes and ns['STORE_ROOT']+'/'+new_version in fs.nodes
            assert not fs.fds
            print('CONTROLLED_FD_FIXTURE_PASS no_native_linux_claim=1 retained_versions=2')
            """;
        var process = new ProcessBuilder(System.getProperty("managed.test.python", "python"), "-I", "-S", "-").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(fixture.getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException broken) {
                if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Controlled Python exited before receiving its fixture: "
                        + new String(process.getInputStream().readNBytes(65536), StandardCharsets.UTF_8), broken);
                throw broken;
            }
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "controlled Python fixture did not exit");
            String output = new String(process.getInputStream().readNBytes(65536), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("CONTROLLED_FD_FIXTURE_PASS"), output);
            System.out.print(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertFalse(process.isAlive());
        }
    }

    public static ObjectNode observation(String task, String action, boolean ready) {
        return observation(task, action, ready, LAYOUT);
    }

    public static String binding() {
        return DeploymentAgentToolchainCommandsTest.binding();
    }

    static ObjectNode observation(String task, String action, boolean ready, DeploymentRemoteLayout layout) {
        return JSON.createObjectNode().put("format", DeploymentAgentToolchainProgramStore.REVISION).put("contract", DeploymentAgentToolchainProgramStore.REVISION)
            .put("version", DeploymentAgentToolchainProgramStore.version(layout)).put("sourceSha256", DeploymentAgentToolchainProgramStore.sourceSha256(layout))
            .put("sourceBytes", DeploymentAgentToolchainPrograms.controller(layout).getBytes(StandardCharsets.UTF_8).length)
            .put("path", DeploymentAgentToolchainProgramStore.path(layout)).put("layoutSha256", layout.digest())
            .put("installOperation", ready ? "b".repeat(32) : "-").put("installTask", ready ? "10000000-0000-0000-0000-000000000001" : "-")
            .put("status", ready ? "READY" : "MISSING").put("task", task).put("action", action);
    }

    public static ObjectNode current(String phase, String task, String action, List<String> args, DeploymentRemoteLayout layout) {
        return current(phase, task, action, args, layout, DeploymentAgentToolchainProgramStateTest.SERVER);
    }

    public static ObjectNode current(String phase, String task, String action, List<String> args, DeploymentRemoteLayout layout, gold.debug.windowstolinux.appapi.server.model.ServerId server) {
        String body = phase.equals("install") ? DeploymentAgentToolchainProgramStore.installer(layout) : "";
        String script = phase.equals("install") ? DeploymentAgentToolchainPrograms.command(List.of(task, action))
            : DeploymentAgentToolchainProgramStore.command(phase, task, action, args, layout);
        try (var request = new CommandRequest(script, body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 65536)) {
            var current = JSON.createObjectNode().put("operation", DeploymentAgentToolchainProgramStore.PREFIX + phase + ".v1")
                .put("businessRevision", "c".repeat(64)).put("inputKind", phase.equals("install") ? "EXECUTABLE_SCRIPT" : "EMPTY")
                .put("exactAction", RemoteActions.command(server, "c".repeat(64), request).exactAction());
            if (phase.equals("install")) current.put("completeExecutableInput", body);
            current.set("serverTargets", JSON.valueToTree(List.of(server.id())));
            current.set("resourceTargets", JSON.valueToTree(DeploymentAgentToolchainProgramStore.targets(server, phase, action, action, layout)));
            return current;
        }
    }
}
