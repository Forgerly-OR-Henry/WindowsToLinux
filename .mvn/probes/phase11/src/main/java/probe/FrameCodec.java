package probe;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

public final class FrameCodec {
    public static final int LIMIT=1024*1024;
    public static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> FIELDS=Set.of("protocolVersion","processId","taskId","requestId","kind","type","payload");
    private static final Set<String> KINDS=Set.of("HELLO","READY","REQUEST","RESULT","EVENT","CANCEL","STOP","STOPPED","ERROR");
    public static ObjectNode frame(UUID process,UUID task,UUID request,String kind,String type,ObjectNode payload) {
        var n=JSON.createObjectNode();n.put("protocolVersion",1);n.put("processId",process.toString());n.put("taskId",task.toString());n.put("requestId",request.toString());n.put("kind",kind);n.put("type",type);n.set("payload",payload);return n;
    }
    public static ObjectNode read(InputStream input,UUID process,UUID task)throws IOException {
        var bytes=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1) {
            if(bytes.size()+1>LIMIT)throw new IOException("Frame exceeds limit");
            if(b=='\n')break;bytes.write(b);
        }
        if(b==-1) { if(bytes.size()==0)return null;throw new IOException("Truncated frame"); }
        var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        if(text.startsWith("\ufeff"))throw new IOException("BOM is not allowed");
        var n=JSON.readTree(text);if(!(n instanceof ObjectNode value))throw new IOException("Expected object");
        var fields=new HashSet<String>();n.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(FIELDS)||!n.get("protocolVersion").isInt()||n.get("protocolVersion").intValue()!=1||!n.get("payload").isObject())throw new IOException("Invalid envelope");
        for(var key:List.of("processId","taskId","requestId","kind","type"))if(!n.get(key).isTextual())throw new IOException("Invalid field type");
        try { for(var key:List.of("processId","taskId","requestId"))if(!UUID.fromString(n.get(key).textValue()).toString().equals(n.get(key).textValue()))throw new IllegalArgumentException(); }
        catch(IllegalArgumentException failure){throw new IOException("Invalid identity",failure);}
        if(!n.get("processId").textValue().equals(process.toString())||!n.get("taskId").textValue().equals(task.toString())||!KINDS.contains(n.get("kind").textValue()))throw new IOException("Identity or protocol mismatch");
        return value;
    }
    public static void write(OutputStream output,ObjectNode frame)throws IOException {
        var data=JSON.writeValueAsBytes(frame);if(data.length+1>LIMIT)throw new IOException("Frame exceeds limit");output.write(data);output.write('\n');output.flush();
    }
}
