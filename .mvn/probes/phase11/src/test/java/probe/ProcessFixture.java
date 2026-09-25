package probe;

import java.util.UUID;

public class ProcessFixture {
    public static void main(String[] args)throws Exception {
        var process=UUID.fromString(args[0]);var task=UUID.fromString(args[1]);
        var hello=FrameCodec.read(System.in,process,task);
        FrameCodec.write(System.out,FrameCodec.frame(process,task,UUID.fromString(hello.get("requestId").asText()),"READY","handshake",FrameCodec.JSON.createObjectNode()));
        com.fasterxml.jackson.databind.node.ObjectNode request;
        while((request=FrameCodec.read(System.in,process,task))!=null) {
            var id=UUID.fromString(request.get("requestId").asText());var type=request.get("type").asText();
            if(request.get("kind").asText().equals("STOP")) {
                FrameCodec.write(System.out,FrameCodec.frame(process,task,id,"STOPPED","stop",FrameCodec.JSON.createObjectNode()));
                if(args.length>2&&args[2].equals("hold"))while(System.in.read()!=-1){}
                return;
            }
            if(type.equals("eof"))return;
            if(type.equals("malformed")){System.out.println("not protocol");return;}
            if(type.equals("noisy")){for(int i=0;i<512;i++)System.err.println("diagnostic "+"x".repeat(4096));}
            if(type.equals("wait"))continue;
            var result=FrameCodec.JSON.createObjectNode();result.put("outcome","SUCCEEDED");result.set("value",FrameCodec.JSON.createObjectNode().put("requestType",type));result.putNull("problem");
            var frame=FrameCodec.frame(process,task,id,"RESULT",type,result);FrameCodec.write(System.out,frame);
            if(type.equals("duplicate"))FrameCodec.write(System.out,frame);
        }
    }
}
