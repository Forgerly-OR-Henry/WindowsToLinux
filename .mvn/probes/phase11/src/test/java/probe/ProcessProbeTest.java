package probe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessProbeTest {
    static final class Session implements AutoCloseable {
        final UUID processId=UUID.randomUUID(), taskId=UUID.randomUUID();
        final Process process;
        final AtomicLong stderrBytes=new AtomicLong();
        final Thread stderr;
        final Set<UUID> pending=new HashSet<>();
        final Set<UUID> cancelled=new HashSet<>();
        final ExecutorService reader=Executors.newVirtualThreadPerTaskExecutor();
        Session(String... extra)throws Exception {
            var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),ProcessFixture.class.getName(),processId.toString(),taskId.toString()));args.addAll(List.of(extra));
            process=new ProcessBuilder(args).start();
            stderr=Thread.ofVirtual().start(()-> {try(var stream=process.getErrorStream()){var buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1)stderrBytes.addAndGet(n);}catch(IOException failure){throw new UncheckedIOException(failure);}});
            var id=UUID.randomUUID();send(id,"HELLO","handshake");var ready=read();assertNotNull(ready);assertEquals("READY",ready.get("kind").asText());assertEquals(id.toString(),ready.get("requestId").asText());
        }
        void send(UUID id,String kind,String type)throws IOException { FrameCodec.write(process.getOutputStream(),FrameCodec.frame(processId,taskId,id,kind,type,FrameCodec.JSON.createObjectNode())); }
        UUID request(String type)throws Exception {
            if(pending.size()==32)throw new IllegalStateException("BUSY");
            var id=UUID.randomUUID();pending.add(id);send(id,"REQUEST",type);return id;
        }
        ObjectNode read()throws Exception {return reader.submit(()->FrameCodec.read(process.getInputStream(),processId,taskId)).get(10,TimeUnit.SECONDS);}
        ObjectNode result()throws Exception {
            var n=read();if(n==null)throw new EOFException("Pending requests remain UNKNOWN");
            var id=UUID.fromString(n.get("requestId").asText());if(!pending.remove(id))throw new IOException("Unknown or duplicate response");return n;
        }
        boolean cancel(UUID id)throws IOException {if(!pending.contains(id))throw new IllegalArgumentException();if(!cancelled.add(id))return false;send(id,"CANCEL","cancel");return true;}
        void stop()throws Exception {send(UUID.randomUUID(),"STOP","stop");assertEquals("STOPPED",read().get("kind").asText());}
        @Override public void close()throws Exception {
            process.getOutputStream().close();
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Probe process did not release");
            process.getInputStream().close();stderr.join(10000);assertFalse(stderr.isAlive());reader.close();
        }
    }
    @Test void roundtripDrainsLargeStderrAndReleasesProcess()throws Exception {
        try(var s=new Session()) {s.request("noisy");assertEquals("SUCCEEDED",s.result().get("payload").get("outcome").asText());s.stop();assertTrue(s.process.waitFor(5,TimeUnit.SECONDS));s.stderr.join(5000);assertTrue(s.stderrBytes.get()>1024*1024);}
    }
    @Test void protocolRejectsCorruptionUnexpectedIdsAndDuplicates()throws Exception {
        try(var s=new Session()) {s.request("malformed");assertThrows(ExecutionException.class,s::result);assertEquals(1,s.pending.size());}
        try(var s=new Session()) {s.request("duplicate");s.result();assertThrows(IOException.class,s::result);}
    }
    @Test void eofLeavesPendingWorkUnknown()throws Exception {
        try(var s=new Session()) {s.request("eof");assertThrows(EOFException.class,s::result);assertEquals(1,s.pending.size());}
    }
    @Test void stoppedFrameDoesNotProveActualExitAndRequestsAreBounded()throws Exception {
        try(var s=new Session("hold")) {
            for(int i=0;i<32;i++)s.request("wait");assertThrows(IllegalStateException.class,()->s.request("wait"));
            var first=s.pending.iterator().next();assertTrue(s.cancel(first));assertFalse(s.cancel(first));
            // Consume the fixture's cancellation response before checking STOPPED. / 核对停止前先读取夹具的取消响应。
            s.result();s.stop();assertTrue(s.process.isAlive());assertFalse(s.process.waitFor(100,TimeUnit.MILLISECONDS));
        }
    }
    @Test void frameLimitsIdentityAndStrictJsonAreEnforced()throws Exception {
        var p=UUID.randomUUID();var t=UUID.randomUUID();var frame=FrameCodec.frame(p,t,UUID.randomUUID(),"READY","handshake",FrameCodec.JSON.createObjectNode());
        var valid=FrameCodec.JSON.writeValueAsString(frame)+"\n";
        for(var invalid:List.of(valid.replace("\"protocolVersion\":1","\"protocolVersion\":1,\"protocolVersion\":1"),valid.replace("\"protocolVersion\":1","\"protocolVersion\":2"),valid.replace("\"payload\":{}","\"payload\":null"),valid.replace("\"payload\":{}","\"payload\":{},\"extra\":1"),"\ufeff"+valid,valid.replace(t.toString(),UUID.randomUUID().toString()),valid.stripTrailing(),"x".repeat(FrameCodec.LIMIT)+"\n")) {
            assertThrows(IOException.class,()->FrameCodec.read(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)),p,t));
        }
    }
}
