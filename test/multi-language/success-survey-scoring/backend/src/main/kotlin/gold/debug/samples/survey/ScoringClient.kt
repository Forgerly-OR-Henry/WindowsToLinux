package gold.debug.samples.survey

import java.net.URI
import java.net.http.*
import java.time.Duration
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.*
import com.google.gson.*

private class LimitedBody(private val maximum:Int):HttpResponse.BodySubscriber<ByteArray> {
    private val future=CompletableFuture<ByteArray>()
    private val output=ByteArrayOutputStream()
    private lateinit var subscription:Flow.Subscription
    override fun getBody():CompletionStage<ByteArray> = future
    override fun onSubscribe(s:Flow.Subscription){subscription=s;s.request(1)}
    override fun onNext(buffers:List<ByteBuffer>){
        for(buffer in buffers){if(output.size()+buffer.remaining()>maximum){subscription.cancel();future.completeExceptionally(IllegalStateException("Ruby response exceeds size limit"));return};val bytes=ByteArray(buffer.remaining());buffer.get(bytes);output.write(bytes)}
        subscription.request(1)
    }
    override fun onError(error:Throwable){future.completeExceptionally(error)}
    override fun onComplete(){future.complete(output.toByteArray())}
}
class ScoringClient {
    private val timeout=(System.getenv("SCORER_TIMEOUT_MS")?:"5000").toLong().also{require(it>0)}
    private val maximum=(System.getenv("MAX_SCORE_BYTES")?:"262144").toInt().also{require(it>0)}
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).build()
    fun score(revision:Long,content:JsonObject,answers:JsonObject):JsonObject {
        val body=JsonObject().also{it.addProperty("protocolVersion",2);it.addProperty("revisionId",revision);it.add("content",content);it.add("answers",answers)}
        val request=HttpRequest.newBuilder(URI.create((System.getenv("SCORER_URL")?:"http://127.0.0.1:18142")+"/score")).timeout(Duration.ofMillis(timeout)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val future=client.sendAsync(request,HttpResponse.BodyHandler{LimitedBody(maximum)})
        try {
            val response=future.get(timeout,TimeUnit.MILLISECONDS)
            check(response.statusCode()==200){"Ruby rejected scoring"}
            val result=JsonParser.parseString(response.body().toString(Charsets.UTF_8)).asJsonObject
            check(integer(result["protocolVersion"],"protocolVersion")==2 && result["component"]?.asString=="ruby-scoring" && integer(result["revisionId"],"revisionId").toLong()==revision)
            val parts=array(result["parts"],"parts");val hidden=array(result["hidden"],"hidden");val dimensions=obj(result["dimensions"],"dimensions")
            val questions=content["questions"].asJsonArray.map{it.asJsonObject}
            val expectedHidden=questions.filter { val condition=it.getAsJsonObject("visibleWhen");condition!=null&&answers[condition["question"].asString]!=condition["equals"] }.map{it["id"].asString}.toSet()
            val ids=parts.map{string(it.asJsonObject["id"],"part id")}
            check(ids.size==ids.toSet().size && ids.toSet()==questions.map{it["id"].asString}.toSet()-expectedHidden && hidden.map{it.asString}.toSet()==expectedHidden && hidden.size()==expectedHidden.size)
            var total=0;var maximum=0
            for(part in parts){val p=obj(part,"part");val ceiling=integer(p["maximum"],"maximum",1,30000);val weighted=integer(p["weighted"],"weighted",0,ceiling);integer(p["raw"],"raw",0,1000);integer(p["weight"],"weight",1,10);string(p["explanation"],"explanation",2000);val dimension=string(p["dimension"],"dimension",40);check(dimensions.has(dimension));total+=weighted;maximum+=ceiling}
            check(integer(result["weightedTotal"],"weightedTotal")==total && integer(result["maximum"],"maximum")==maximum)
            check(result["score"]?.asJsonPrimitive?.isNumber==true)
            val score=result["score"].asDouble;check(score.isFinite()&&score in 0.0..100.0 && kotlin.math.abs(score-(if(maximum==0)0.0 else total*100.0/maximum))<0.011)
            for((_,raw) in dimensions.entrySet()){val d=obj(raw,"dimension");integer(d["weighted"],"dimension weighted");integer(d["maximum"],"dimension maximum",1);check(d["score"]?.asJsonPrimitive?.isNumber==true&&d["score"].asDouble.isFinite())}
            return result
        }catch(e:Exception){future.cancel(true);throw BusinessError(502,"Ruby 评分服务不可用、超时或协议无效")}
    }
}
