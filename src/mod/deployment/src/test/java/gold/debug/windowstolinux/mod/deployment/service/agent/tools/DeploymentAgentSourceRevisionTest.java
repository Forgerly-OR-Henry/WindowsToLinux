package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentSourceRevisionTest {
    @TempDir
    Path directory;

    @Test
    void sealClassifiesOnlyFrozenOutputRejectionAndNeverWritesItsArtifactRecord() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        String resource;
        try (var input = getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/27-agent-workspace.sh")) {
            resource = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        for (String boundary : List.of("output", "source", "stop", "freeze")) {
            Path candidate = Files.createDirectory(directory.resolve(boundary));
            Files.writeString(candidate.resolve(".agent-engine"), "ordinary\n");
            Files.writeString(candidate.resolve(".agent-success"), "a".repeat(64) + "\n");
            String path = candidate.toString().replace('\\', '/');
            if (path.matches("^[A-Za-z]:/.*")) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
            // Ownership, mount and service checks are controlled; the actual seal function ordering and file effects run.
            String script = "set -eu\nexport PATH=/usr/bin:/bin\n" + resource + "\nagent_candidate_root="
                + gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope.shellQuote(path) + "\n"
                + "boundary=" + boundary + "\n" + """
                reject() { printf 'MANAGED_HELPER_REJECT=%s\\n' "$1" >&2; exit 64; }
                agent_candidate() { :; }
                flock() { :; }
                stop_candidate_build() { printf 'stop\\n' >> "$agent_candidate_root/trace"; [ "$boundary" != stop ] || reject stop-failed; }
                agent_assert_revision() { printf 'source\\n' >> "$agent_candidate_root/trace"; [ "$boundary" != source ] || { printf 'SOURCE_REJECT=invalid-or-conflicting-source\\n' >&2; exit 64; }; }
                assert_sealed_build() { printf 'freeze\\n' >> "$agent_candidate_root/trace"; [ "$boundary" != freeze ] || reject freeze-failed; }
                assert_root_owned_regular() { test -f "$1"; }
                agent_source_operation() {
                    [ "$1" = artifact-digest ]; [ "$3" = "$revision" ]; [ "$4" = "$task" ]; [ "$5" = "$candidate" ];
                    printf 'ARTIFACT_REJECT={"task":"%s","candidate":"%s","sourceRevision":"%s","code":"HARD_LINK","path":"app"}\\n' "$task" "$candidate" "$revision"
                    return 65
                }
                task=00000000-0000-0000-0000-000000000001
                """ + "candidate=demo-api-" + "c".repeat(16) + "\nrevision=" + "a".repeat(64)
                + "\nagent_seal app \"$candidate\" \"$task\" \"$revision\"\n";
            var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
            try {
                try (var stream = process.getOutputStream()) {
                    stream.write(script.getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                String observed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(boundary.equals("output") ? 65 : 64, process.exitValue(), observed);
                assertEquals(boundary.equals("output"), observed.startsWith("ARTIFACT_REJECT="), observed);
                assertFalse(Files.exists(candidate.resolve(".agent-artifact")));
                assertEquals(switch (boundary) {
                    case "stop" -> List.of("stop");
                    case "source" -> List.of("stop", "source");
                    default -> List.of("stop", "source", "freeze");
                }, Files.readAllLines(candidate.resolve("trace")));
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void artifactFailuresAreTypedWhileSourceAndIoFailuresKeepTheirOriginalBoundary() throws Exception {
        Path module = directory.resolve("operations.py"), output = Files.createDirectory(directory.resolve("artifact"));
        try (var input = getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/source-operations.py")) {
            Files.copy(Objects.requireNonNull(input), module);
        }
        String program = """
            import importlib.util,io,json,os,sys
            from pathlib import Path
            from contextlib import redirect_stdout
            from unittest.mock import patch
            spec=importlib.util.spec_from_file_location('operations',sys.argv[1])
            m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
            root=Path(sys.argv[2]);task='00000000-0000-0000-0000-000000000001';candidate='demo-api-'+'c'*16;revision='a'*64
            identity_root=Path('/var/lib/windowstolinux/work')/candidate/'mutable/source'
            m.validate_artifact_identity(identity_root,revision,[task,candidate])
            for invalid in ([task,'other-api-'+'c'*16],[task,'c'*64],['not-a-task',candidate],[task,candidate,'extra']):
                try: m.validate_artifact_identity(identity_root,revision,invalid)
                except ValueError: pass
                else: raise AssertionError('Wrong task candidate identity accepted')
            try: m.validate_artifact_identity(identity_root.parent/'input-source',revision,[task,candidate])
            except ValueError: pass
            else: raise AssertionError('Source tree classified as output')
            def rejected(code):
                captured=io.StringIO()
                with redirect_stdout(captured): status=m.artifact_digest(root,task,candidate,revision)
                assert status==65
                body=json.loads(captured.getvalue().removeprefix('ARTIFACT_REJECT='))
                assert body['code']==code and body['task']==task and body['candidate']==candidate and body['sourceRevision']==revision
                assert 'DIGEST=' not in captured.getvalue()
                print(code)
            rejected('EMPTY_TREE')
            (root/'app').write_bytes(b'portable artifact')
            expected=m.tree_digest(root)
            captured=io.StringIO()
            with redirect_stdout(captured): status=m.artifact_digest(root,task,candidate,revision)
            assert status==0 and captured.getvalue()=='DIGEST='+expected+'\\n'
            os.link(root/'app',root/'linked')
            rejected('HARD_LINK')
            try: m.tree_digest(root)
            except m.PortableArtifactRejected: raise AssertionError('Source operation changed to artifact feedback')
            except ValueError: pass
            else: raise AssertionError('Source accepted hard links')
            (root/'linked').unlink()
            with patch.object(Path,'is_symlink',return_value=True): rejected('SYMBOLIC_LINK')
            with patch.object(m.stat,'S_ISREG',return_value=False): rejected('SPECIAL_FILE')
            m.MAX_MEMBERS=0;rejected('MEMBER_LIMIT')
            m.MAX_MEMBERS=1;(root/'second').write_bytes(b'other');rejected('FILE_LIMIT')
            (root/'second').unlink();m.MAX_MEMBERS=20000
            try: m.safe_member(root,'../outside',True)
            except m.PortableArtifactRejected as error: assert error.code=='INVALID_PATH' and error.path==''
            else: raise AssertionError('Invalid path accepted')
            with patch.object(Path,'open',side_effect=OSError('controlled I/O failure')):
                try: m.artifact_digest(root,task,candidate,revision)
                except OSError: print('IO_HARDSTOP')
                else: raise AssertionError('I/O failure was downgraded')
            nested=root/'nested';nested.mkdir();(nested/'data').write_bytes(b'complete subtree')
            actual_scandir=os.scandir
            for target in (root,nested):
                def failing_scandir(path):
                    if Path(path)==target: raise OSError('controlled directory enumeration failure')
                    return actual_scandir(path)
                captured=io.StringIO()
                with patch.object(m.os,'scandir',side_effect=failing_scandir),redirect_stdout(captured):
                    try: m.artifact_digest(root,task,candidate,revision)
                    except OSError: pass
                    else: raise AssertionError('Enumeration failed but an artifact result was returned')
                assert captured.getvalue()==''
                print('SCAN_HARDSTOP')
            """;
        var process = new ProcessBuilder(System.getProperty("managed.test.python", "python"), "-I", "-c", program, module.toString(), output.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String observed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), observed);
            assertEquals(List.of("EMPTY_TREE", "HARD_LINK", "SYMBOLIC_LINK", "SPECIAL_FILE", "MEMBER_LIMIT", "FILE_LIMIT", "IO_HARDSTOP", "SCAN_HARDSTOP", "SCAN_HARDSTOP"), observed.lines().toList());
            assertEquals("portable artifact", Files.readString(output.resolve("app")));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alpha\nbeta\n", "alpha\r\nbeta\r\n", "alpha\nbeta", "alpha\rbeta", "alpha\u0085beta", "alpha\u2028beta", ""})
    void predictsExactlyTheHelperBytesAndUnicodeTreeOrder(String text) throws Exception {
        Path javaRoot = Files.createDirectory(directory.resolve("java")), pythonRoot = Files.createDirectory(directory.resolve("python"));
        for (Path root : List.of(javaRoot, pythonRoot)) {
            Files.writeString(root.resolve("main.txt"), text);
            Files.writeString(root.resolve("中.txt"), "evidence\n");
            Files.writeString(root.resolve("𐀀.txt"), "astral\n");
            Files.writeString(root.resolve("\ue000.txt"), "bmp\n");
        }
        String original = DeploymentAgentSourceRevision.digest(javaRoot);
        String before = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        var patch = new DeploymentAgentPatch("main.txt", before, original, List.of(new DeploymentAgentPatch.Edit(1, text.isEmpty() ? List.of() : List.of("alpha"), List.of("revised"))));
        Path module = directory.resolve("operations.py");
        try (var input = getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/source-operations.py")) {
            Files.copy(Objects.requireNonNull(input), module);
        }
        Path payload = directory.resolve("patch.json");
        Files.writeString(payload, new ObjectMapper().writeValueAsString(patch));
        // Windows cannot fsync a directory; only that durability syscall is stubbed in this byte-parity fixture.
        String program = "import importlib.util,json,sys,os;from pathlib import Path;s=importlib.util.spec_from_file_location('operations',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.sync_directory=m.sync_directory if hasattr(os,'O_DIRECTORY') else lambda p:None;r=Path(sys.argv[2]);print(m.tree_digest(r));p=json.loads(Path(sys.argv[3]).read_text(encoding='utf-8'));print(m.apply_patch(r,p,p['sourceRevision']))";
        var process = new ProcessBuilder(System.getProperty("managed.test.python", "python"), "-c", program, module.toString(), pythonRoot.toString(), payload.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            var lines = output.lines().toList();
            assertEquals(original, lines.getFirst());
            assertEquals(lines.getLast(), DeploymentAgentSourceRevision.patch(javaRoot, patch));
            assertArrayEquals(Files.readAllBytes(pythonRoot.resolve("main.txt")), Files.readAllBytes(javaRoot.resolve("main.txt")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    @Test
    void refusesWrongRevisionsContentProtectedPathsAndBinaryInput() throws Exception {
        Files.writeString(directory.resolve("main.txt"), "original\n");
        String revision = DeploymentAgentSourceRevision.digest(directory);
        var wrong = new DeploymentAgentPatch("main.txt", "a".repeat(64), revision, List.of(new DeploymentAgentPatch.Edit(1, List.of("original"), List.of("new"))));
        assertThrows(java.io.IOException.class, () -> DeploymentAgentSourceRevision.patch(directory, wrong));
        assertEquals("original\n", Files.readString(directory.resolve("main.txt")));
        assertThrows(java.io.IOException.class, () -> DeploymentAgentSourceRevision.safe(directory, "../main.txt"));
        Files.write(directory.resolve("main.txt"), new byte[]{(byte) 0xff});
        String binary = DeploymentAgentSourceRevision.digest(directory);
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{(byte) 0xff}));
        assertThrows(java.io.IOException.class, () -> DeploymentAgentSourceRevision.patch(directory, new DeploymentAgentPatch("main.txt", digest, binary, wrong.edits())));
    }
}
