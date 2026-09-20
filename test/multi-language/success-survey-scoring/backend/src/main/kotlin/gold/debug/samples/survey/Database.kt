package gold.debug.samples.survey

import java.nio.file.*
import java.sql.*

class Database {
    private val url: String
    init {
        val dir=Path.of(System.getenv("DATA_DIR")?:"data").toAbsolutePath()
        Files.createDirectories(dir);url="jdbc:sqlite:"+dir.resolve("survey.db")
    }
    fun open():Connection=DriverManager.getConnection(url).also { c->
        c.createStatement().use { it.execute("PRAGMA foreign_keys=ON");it.execute("PRAGMA busy_timeout=10000") }
    }
    fun <T> read(work:(Connection)->T):T=open().use(work)
    @Synchronized fun <T> write(work:(Connection)->T):T=open().use { c->
        exec(c,"BEGIN IMMEDIATE")
        try { val result=work(c);exec(c,"COMMIT");result }
        catch(e:Exception){exec(c,"ROLLBACK");throw e}
    }
    companion object {
        fun exec(c:Connection,sql:String,vararg args:Any?):Int=c.prepareStatement(sql).use { s->
            args.forEachIndexed { i,v->s.setObject(i+1,v) };s.execute();s.updateCount
        }
        fun rows(c:Connection,sql:String,vararg args:Any?):List<MutableMap<String,Any?>> = c.prepareStatement(sql).use { s->
            args.forEachIndexed { i,v->s.setObject(i+1,v) }
            s.executeQuery().use { r->buildList { while(r.next()){val item=linkedMapOf<String,Any?>();for(i in 1..r.metaData.columnCount)item[r.metaData.getColumnLabel(i)]=r.getObject(i);add(item)} } }
        }
        fun one(c:Connection,sql:String,vararg args:Any?):MutableMap<String,Any?> = rows(c,sql,*args).firstOrNull()?:throw BusinessError(404,"记录不存在")
        fun scalar(c:Connection,sql:String,vararg args:Any?):Long=(one(c,sql,*args).values.first() as Number).toLong()
        fun fault(point:String) {
            if(System.getenv("SAMPLE_FAULT_POINT")!=point)return
            val dir=Path.of(System.getenv("SAMPLE_FAULT_DIR")?:error("SAMPLE_FAULT_DIR required"));Files.createDirectories(dir)
            Files.writeString(dir.resolve("$point.ready"),ProcessHandle.current().pid().toString())
            val deadline=System.nanoTime()+60_000_000_000L
            while(System.nanoTime()<deadline){if(Files.exists(dir.resolve("$point.release")))return;Thread.sleep(20)}
            error("Fault gate expired: $point")
        }
    }
}
