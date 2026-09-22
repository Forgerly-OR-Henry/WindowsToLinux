package gold.debug.samples.survey

import com.google.gson.*
import com.sun.net.httpserver.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

private fun input(exchange: HttpExchange): JsonObject {
    val bytes = exchange.requestBody.use { it.readNBytes(65537) }
    if (bytes.size > 65536) invalid("请求超过 64 KiB")
    return try {
        obj(JsonParser.parseString(bytes.toString(Charsets.UTF_8)), "请求")
    } catch (e: JsonParseException) {
        invalid("无效 JSON")
    }
}

fun main() {
    val gson = Gson()
    val store = SurveyStore()
    val scorer = ScoringClient()
    val server =
        HttpServer.create(
            InetSocketAddress(
                System.getenv("HOST") ?: "127.0.0.1",
                (System.getenv("PORT") ?: "18141").toInt(),
            ),
            0,
        )
    server.executor =
        Executors.newFixedThreadPool(
            (System.getenv("WORKERS") ?: "16").toInt().also { require(it in 1..64) }
        )
    server.createContext("/") { exchange ->
        var status = 200
        exchange.responseHeaders.apply {
            set(
                "Access-Control-Allow-Origin",
                System.getenv("WEB_ORIGIN") ?: "http://127.0.0.1:18140",
            )
            set("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS")
            set("Access-Control-Allow-Headers", "Content-Type")
            set("Access-Control-Expose-Headers", "X-Sample-Protocol")
            set("X-Sample-Protocol", "2")
        }
        val body: Any =
            try {
                val path = exchange.requestURI.path
                val method = exchange.requestMethod
                val query =
                    exchange.requestURI.rawQuery
                        .orEmpty()
                        .split('&')
                        .filter { it.isNotEmpty() }
                        .associate {
                            val parts = it.split('=', limit = 2)
                            URLDecoder.decode(parts[0], "UTF-8") to
                                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                        }
                fun number(
                    name: String,
                    default: Int? = null,
                    min: Int = 1,
                    max: Int = Int.MAX_VALUE,
                ): Int {
                    val text = query[name] ?: return default ?: invalid("缺少 $name")
                    return text.toIntOrNull()?.takeIf { it in min..max } ?: invalid("$name 无效")
                }
                val pieces = path.trim('/').split('/')
                val id = pieces.getOrNull(2)?.toLongOrNull()
                if (method != "OPTIONS" && path != "/healthz" && pieces.firstOrNull() != "api")
                    throw BusinessError(404, "接口不存在")
                when {
                    method == "OPTIONS" -> mapOf("ok" to true)
                    path == "/healthz" ->
                        mapOf("status" to "ok", "component" to "kotlin-survey", "version" to 2)
                    path == "/api/actors" && method == "GET" -> Contract.actors
                    path == "/api/surveys" && method == "GET" -> store.surveys()
                    path == "/api/surveys" && method == "POST" -> store.create(input(exchange))
                    pieces.getOrNull(1) == "surveys" &&
                        id != null &&
                        pieces.size == 3 &&
                        method == "GET" -> store.detail(id)
                    pieces.getOrNull(1) == "surveys" &&
                        id != null &&
                        pieces.getOrNull(3) == "revisions" &&
                        method == "POST" -> store.clone(id, input(exchange))
                    pieces.getOrNull(1) == "surveys" &&
                        id != null &&
                        pieces.getOrNull(3) == "stats" &&
                        method == "GET" -> store.stats(id)
                    pieces.getOrNull(1) == "revisions" &&
                        id != null &&
                        pieces.size == 3 &&
                        method == "GET" -> store.revision(id)
                    pieces.getOrNull(1) == "revisions" &&
                        id != null &&
                        pieces.size == 3 &&
                        method == "PUT" -> store.edit(id, input(exchange))
                    pieces.getOrNull(1) == "revisions" &&
                        id != null &&
                        pieces.getOrNull(3) in listOf("publish", "close") &&
                        method == "POST" -> store.transition(id, pieces[3], input(exchange))
                    path == "/api/submissions" && method == "GET" ->
                        store.submissions(
                            number("revisionId").toLong(),
                            number("offset", 0, 0),
                            number("limit", 25, 1, 100),
                        )
                    path == "/api/submissions" && method == "POST" ->
                        store.submit(input(exchange), scorer)
                    pieces.getOrNull(1) == "submissions" &&
                        id != null &&
                        pieces.size == 3 &&
                        method == "GET" -> store.result(id)
                    pieces.getOrNull(1) == "submissions" &&
                        id != null &&
                        pieces.getOrNull(3) == "export" &&
                        method == "GET" -> {
                        exchange.responseHeaders.set(
                            "Content-Disposition",
                            "attachment; filename=result-$id.json",
                        )
                        store.result(id)
                    }
                    else -> throw BusinessError(404, "接口不存在")
                }
            } catch (e: BusinessError) {
                status = e.status
                mapOf("error" to (e.message ?: "业务错误"))
            } catch (e: Exception) {
                System.err.println("${e.javaClass.name}: ${e.message}")
                status = 500
                mapOf("error" to "存储或内部处理失败")
            }
        val bytes = gson.toJson(body).toByteArray(Charsets.UTF_8)
        try {
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            exchange.close()
        }
    }
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
                server.stop(0)
                (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
            }
        )
    server.start()
}
