package gold.debug.samples.survey

import com.google.gson.*
import gold.debug.samples.survey.Database.Companion.exec
import gold.debug.samples.survey.Database.Companion.fault
import gold.debug.samples.survey.Database.Companion.one
import gold.debug.samples.survey.Database.Companion.rows
import gold.debug.samples.survey.Database.Companion.scalar
import java.sql.Connection

class SurveyStore {
    private val db = Database()
    private val gson = Gson()

    private fun demo(): JsonObject =
        Contract.content(
            JsonParser.parseString(
                javaClass
                    .getResourceAsStream("/demo-survey.json")!!
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            )
        )

    init {
        db.read { c ->
            if (
                scalar(c, "PRAGMA user_version") != 2L &&
                    scalar(c, "SELECT count(*) FROM sqlite_master WHERE name='submissions'") > 0
            )
                error(
                    "Sample schema v2 requires a fresh DATA_DIR; old schema migration is not supported"
                )
            exec(c, "PRAGMA journal_mode=WAL")
            listOf(
                    "CREATE TABLE IF NOT EXISTS surveys(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS revisions(id INTEGER PRIMARY KEY,survey_id INTEGER NOT NULL REFERENCES surveys(id),number INTEGER NOT NULL,status TEXT NOT NULL CHECK(status IN ('draft','published','closed')),content TEXT NOT NULL,edit_version INTEGER NOT NULL DEFAULT 1,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(survey_id,number))",
                    "CREATE UNIQUE INDEX IF NOT EXISTS one_draft ON revisions(survey_id) WHERE status='draft'",
                    "CREATE TABLE IF NOT EXISTS submissions(id INTEGER PRIMARY KEY,revision_id INTEGER NOT NULL REFERENCES revisions(id),respondent TEXT NOT NULL,request_id TEXT NOT NULL UNIQUE,fingerprint TEXT NOT NULL,answers TEXT NOT NULL,result TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE INDEX IF NOT EXISTS submission_revision ON submissions(revision_id,id)",
                    "CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,survey_id INTEGER NOT NULL REFERENCES surveys(id),revision_id INTEGER NOT NULL REFERENCES revisions(id),action TEXT NOT NULL,actor TEXT NOT NULL,detail TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE INDEX IF NOT EXISTS survey_events ON events(survey_id,id)",
                    "PRAGMA user_version=2",
                )
                .forEach { exec(c, it) }
        }
        db.write { c ->
            if (scalar(c, "SELECT count(*) FROM surveys") == 0L) {
                exec(c, "INSERT INTO surveys VALUES(1,'校园协作体验',CURRENT_TIMESTAMP)")
                exec(
                    c,
                    "INSERT INTO revisions(id,survey_id,number,status,content) VALUES(1,1,1,'published',?)",
                    demo().toString(),
                )
                event(c, 1, 1, "published", "陈老师", "初始演示版本")
            }
        }
    }

    private fun event(
        c: Connection,
        survey: Long,
        revision: Long,
        action: String,
        actor: String,
        detail: String = "",
    ) {
        exec(
            c,
            "INSERT INTO events(survey_id,revision_id,action,actor,detail) VALUES(?,?,?,?,?)",
            survey,
            revision,
            action,
            actor,
            detail,
        )
    }

    private fun revision(c: Connection, id: Long): MutableMap<String, Any?> =
        one(
                c,
                "SELECT id,survey_id AS surveyId,number,status,content,edit_version AS editVersion,created FROM revisions WHERE id=?",
                id,
            )
            .also { it["content"] = JsonParser.parseString(it["content"] as String) }

    fun revision(id: Long): Any = db.read { revision(it, id) }

    fun surveys(): Any =
        db.read { c ->
            rows(
                c,
                "SELECT s.id,s.name,(SELECT count(*) FROM revisions r WHERE r.survey_id=s.id) AS versions,(SELECT count(*) FROM submissions x JOIN revisions r ON r.id=x.revision_id WHERE r.survey_id=s.id) AS submissions FROM surveys s ORDER BY s.id",
            )
        }

    fun detail(id: Long): Any =
        db.read { c ->
            one(c, "SELECT id,name,created FROM surveys WHERE id=?", id).also {
                it["revisions"] =
                    rows(
                        c,
                        "SELECT id,number,status,edit_version AS editVersion,created FROM revisions WHERE survey_id=? ORDER BY number DESC",
                        id,
                    )
                it["events"] =
                    rows(
                        c,
                        "SELECT action,actor,detail,revision_id AS revisionId,created FROM events WHERE survey_id=? ORDER BY id",
                        id,
                    )
            }
        }

    fun create(input: JsonObject): Any {
        val name = string(input["name"], "问卷名称", 120)
        val actor = Contract.actor(input["actor"])
        return db.write { c ->
            if (scalar(c, "SELECT count(*) FROM surveys WHERE name=?", name) > 0)
                throw BusinessError(409, "问卷名称已存在")
            exec(c, "INSERT INTO surveys(name) VALUES(?)", name)
            val id = scalar(c, "SELECT last_insert_rowid()")
            val content = demo().also { it.addProperty("title", name) }
            exec(
                c,
                "INSERT INTO revisions(survey_id,number,status,content) VALUES(?,1,'draft',?)",
                id,
                content.toString(),
            )
            val revision = scalar(c, "SELECT last_insert_rowid()")
            event(c, id, revision, "created", actor)
            revision(c, revision)
        }
    }

    fun clone(id: Long, input: JsonObject): Any {
        val actor = Contract.actor(input["actor"])
        return db.write { c ->
            one(c, "SELECT id FROM surveys WHERE id=?", id)
            if (
                scalar(
                    c,
                    "SELECT count(*) FROM revisions WHERE survey_id=? AND status='draft'",
                    id,
                ) > 0
            )
                throw BusinessError(409, "已有草稿，请继续编辑")
            val previous =
                one(
                    c,
                    "SELECT number,content FROM revisions WHERE survey_id=? ORDER BY number DESC LIMIT 1",
                    id,
                )
            exec(
                c,
                "INSERT INTO revisions(survey_id,number,status,content) VALUES(?,?,'draft',?)",
                id,
                (previous["number"] as Number).toInt() + 1,
                previous["content"],
            )
            val revision = scalar(c, "SELECT last_insert_rowid()")
            event(c, id, revision, "cloned", actor)
            revision(c, revision)
        }
    }

    fun edit(id: Long, input: JsonObject): Any {
        val actor = Contract.actor(input["actor"])
        val version = integer(input["editVersion"], "编辑版本", 1)
        val content = Contract.content(input["content"])
        return db.write { c ->
            val old = one(c, "SELECT survey_id,status,edit_version FROM revisions WHERE id=?", id)
            if (old["status"] != "draft") throw BusinessError(409, "已发布版本不可原地修改，请创建新草稿")
            if ((old["edit_version"] as Number).toInt() != version)
                throw BusinessError(409, "草稿版本冲突，请重新加载")
            exec(
                c,
                "UPDATE revisions SET content=?,edit_version=edit_version+1 WHERE id=?",
                content.toString(),
                id,
            )
            event(c, (old["survey_id"] as Number).toLong(), id, "edited", actor)
            revision(c, id)
        }
    }

    fun transition(id: Long, action: String, input: JsonObject): Any {
        val actor = Contract.actor(input["actor"])
        return db.write { c ->
            val old = revision(c, id)
            val state = if (action == "publish") "published" else "closed"
            val expected = if (action == "publish") "draft" else "published"
            if (old["status"] != expected) throw BusinessError(409, "问卷版本状态不允许此操作")
            Contract.content(old["content"] as JsonElement)
            exec(c, "UPDATE revisions SET status=? WHERE id=?", state, id)
            event(c, (old["surveyId"] as Number).toLong(), id, action, actor)
            revision(c, id)
        }
    }

    private fun submission(c: Connection, id: Long): MutableMap<String, Any?> =
        one(
                c,
                "SELECT id,revision_id AS revisionId,respondent,answers,result,created FROM submissions WHERE id=?",
                id,
            )
            .also {
                it["answers"] = JsonParser.parseString(it["answers"] as String)
                it["result"] = JsonParser.parseString(it["result"] as String)
            }

    fun result(id: Long): Any =
        db.read { c ->
            submission(c, id).also {
                it["revision"] = revision(c, (it["revisionId"] as Number).toLong())
            }
        }

    fun submissions(revision: Long, offset: Int, limit: Int): Any =
        db.read { c ->
            one(c, "SELECT id FROM revisions WHERE id=?", revision)
            mapOf(
                "items" to
                    rows(
                        c,
                        "SELECT id,revision_id AS revisionId,respondent,json_extract(result,'$.score') AS score,created FROM submissions WHERE revision_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                        revision,
                        limit,
                        offset,
                    ),
                "total" to
                    scalar(c, "SELECT count(*) FROM submissions WHERE revision_id=?", revision),
                "offset" to offset,
                "limit" to limit,
            )
        }

    fun stats(id: Long): Any =
        db.read { c ->
            one(c, "SELECT id FROM surveys WHERE id=?", id)
            mapOf(
                "total" to
                    scalar(
                        c,
                        "SELECT count(*) FROM submissions x JOIN revisions r ON r.id=x.revision_id WHERE r.survey_id=?",
                        id,
                    ),
                "revisions" to
                    rows(
                        c,
                        "SELECT r.id,r.number,r.status,count(x.id) AS count,coalesce(avg(json_extract(x.result,'$.score')),0) AS average FROM revisions r LEFT JOIN submissions x ON x.revision_id=r.id WHERE r.survey_id=? GROUP BY r.id ORDER BY r.number",
                        id,
                    ),
            )
        }

    private fun existing(c: Connection, key: String, fingerprint: String): Any? {
        val previous =
            rows(c, "SELECT id,fingerprint FROM submissions WHERE request_id=?", key).firstOrNull()
                ?: return null
        if (previous["fingerprint"] != fingerprint) throw BusinessError(409, "提交标识已用于其他问卷、身份或答案")
        return submission(c, (previous["id"] as Number).toLong())
    }

    fun submit(input: JsonObject, scorer: ScoringClient): Any {
        val id = integer(input["revisionId"], "问卷版本", 1).toLong()
        val key = string(input["requestId"], "提交标识", 100)
        val respondent = Contract.actor(input["respondent"])
        val version = db.read { revision(it, id) }
        val content = (version["content"] as JsonElement).asJsonObject
        val answers = Contract.answers(content, input["answers"])
        val fingerprint = gson.toJson(listOf(id, respondent, answers))
        db.read { existing(it, key, fingerprint) }
            ?.let {
                return it
            }
        if (version["status"] != "published") throw BusinessError(409, "此问卷版本未开放提交")
        val scored = scorer.score(id, content, answers)
        fault("score-returned")
        return db.write { c ->
            existing(c, key, fingerprint)
                ?: run {
                    val latest = revision(c, id)
                    if (latest["status"] != "published") throw BusinessError(409, "评分期间问卷已关闭，未保存结果")
                    exec(
                        c,
                        "INSERT INTO submissions(revision_id,respondent,request_id,fingerprint,answers,result) VALUES(?,?,?,?,?,?)",
                        id,
                        respondent,
                        key,
                        fingerprint,
                        answers.toString(),
                        scored.toString(),
                    )
                    val resultId = scalar(c, "SELECT last_insert_rowid()")
                    fault("submission-written")
                    submission(c, resultId)
                }
        }
    }
}
