package gold.debug.samples.tasks;
import static gold.debug.samples.tasks.Database.*;
import static gold.debug.samples.tasks.Models.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class TaskStore {
    private final Database db = new Database();
    private final ObjectMapper json = new ObjectMapper();
    private static final String SELECT =
        "SELECT t.id,t.project_id AS projectId,t.title,t.description,t.owner_id AS ownerId,m.name AS " +
        "owner,t.priority,t.labels,t.status,t.due_date AS dueDate,t.version,t.created FROM tasks t JOIN " +
        "members m ON m.id=t.owner_id ";
    public TaskStore() throws Exception {
        try (var c = db.open()) {
            if (scalar(c, "PRAGMA user_version") != 2 &&
                scalar(c, "SELECT count(*) FROM sqlite_master WHERE name='tasks'") > 0)
                throw new IllegalStateException(
                    "Sample schema v2 requires a fresh DATA_DIR; old schema migration is not supported");
            exec(c, "PRAGMA journal_mode=WAL");
            for (String sql : List.of(
                     "CREATE TABLE IF NOT EXISTS projects(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE)",
                     "CREATE TABLE IF NOT EXISTS members(id INTEGER PRIMARY KEY,project_id INTEGER NOT " +
                     "NULL REFERENCES projects(id),name TEXT NOT NULL,UNIQUE(project_id,name))",
                     "CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY,project_id INTEGER NOT NULL " +
                     "REFERENCES projects(id),title TEXT NOT NULL,description TEXT NOT NULL,owner_id " +
                     "INTEGER NOT NULL REFERENCES members(id),priority INTEGER NOT NULL CHECK(priority " +
                     "BETWEEN 1 AND 3),labels TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN " +
                     "('todo','doing','review','done')),due_date TEXT NOT NULL,version INTEGER NOT NULL " +
                     "CHECK(version>0),created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                     "CREATE TABLE IF NOT EXISTS dependencies(task_id INTEGER NOT NULL REFERENCES " +
                     "tasks(id),depends_on INTEGER NOT NULL REFERENCES tasks(id),PRIMARY " +
                     "KEY(task_id,depends_on),CHECK(task_id<>depends_on))",
                     "CREATE TABLE IF NOT EXISTS comments(id INTEGER PRIMARY KEY,task_id INTEGER NOT NULL " +
                     "REFERENCES tasks(id),actor_id INTEGER NOT NULL REFERENCES members(id),text TEXT NOT " +
                     "NULL,request_id TEXT NOT NULL UNIQUE,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                     "CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,task_id INTEGER NOT NULL " +
                     "REFERENCES tasks(id),actor_id INTEGER NOT NULL REFERENCES members(id),action TEXT " +
                     "NOT NULL,detail TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                     "CREATE INDEX IF NOT EXISTS tasks_filter ON " +
                     "tasks(project_id,status,priority,owner_id,id)",
                     "CREATE INDEX IF NOT EXISTS dependency_reverse ON dependencies(depends_on)",
                     "CREATE INDEX IF NOT EXISTS task_events ON events(task_id,id)",
                     "CREATE INDEX IF NOT EXISTS task_comments ON comments(task_id,id)",
                     "INSERT OR IGNORE INTO projects VALUES(1,'校园活动'),(2,'实验室建设')",
                     "INSERT OR IGNORE INTO members " +
                     "VALUES(1,1,'林同学'),(2,1,'周同学'),(3,2,'陈老师'),(4,2,'李同学')",
                     "PRAGMA user_version=2"))
                exec(c, sql);
            if (scalar(c, "SELECT count(*) FROM tasks") == 0)
                db.write(tx -> {
                    exec(
                        tx,
                        "INSERT INTO " +
                        "tasks(id,project_id,title,description,owner_id,priority,labels,status,due_date," +
                        "version) " +
                        "VALUES(1,1,'确认活动场地','先完成场地确认，再发布活动公告',1,2,'[\"演示\"]','todo'" +
                        ",'2030-10-01',1),(2,1,'发布活动公告','依赖场地确认',2,3,'[\"演示\"]','todo','2030-" +
                        "10-02',1),(3,2,'实验室设备盘点','独立项目数据',3,1,'[]','todo','2030-10-03',1)");
                    exec(tx, "INSERT INTO dependencies VALUES(2,1)");
                    for (long id : new long[] {1, 2, 3})
                        event(tx, id, id == 3 ? 3 : 1, "created", "初始化演示任务");
                    return null;
                });
        }
    }
    public Object projects() throws Exception {
        try (var c = db.open()) {
            return rows(c, "SELECT p.id,p.name,(SELECT count(*) FROM tasks t WHERE t.project_id=p.id) AS " +
                           "taskCount FROM projects p ORDER BY p.id");
        }
    }
    public Object createProject(Project input) throws Exception {
        text(input.name(), 80, "项目名");
        return db.write(c -> {
            if (scalar(c, "SELECT count(*) FROM projects WHERE name=?", input.name().trim()) > 0)
                throw new BusinessError(409, "项目名称已存在");
            exec(c, "INSERT INTO projects(name) VALUES(?)", input.name().trim());
            long id = scalar(c, "SELECT last_insert_rowid()");
            for (String name : List.of("林同学", "周同学", "陈老师"))
                exec(c, "INSERT INTO members(project_id,name) VALUES(?,?)", id, name);
            return one(c, "SELECT id,name FROM projects WHERE id=?", id);
        });
    }
    public Object members(long project) throws Exception {
        try (var c = db.open()) {
            project(c, project);
            return rows(c,
                        "SELECT id,name,project_id AS projectId FROM members WHERE project_id=? ORDER BY id",
                        project);
        }
    }
    private static void project(Connection c, long id) throws Exception {
        one(c, "SELECT id FROM projects WHERE id=?", id);
    }
    private static void member(Connection c, long project, long id) throws Exception {
        if (scalar(c, "SELECT count(*) FROM members WHERE id=? AND project_id=?", id, project) != 1)
            throw new BusinessError(400, "成员不属于所选项目");
    }
    private static void text(String v, int max, String label) {
        if (v == null || v.isBlank() || v.length() > max)
            throw new BusinessError(400, label + "无效");
    }
    private Map<String, Object> decode(Map<String, Object> item) throws Exception {
        item.put("labels", json.readValue((String)item.get("labels"), String[].class));
        return item;
    }
    private Map<String, Object> detail(Connection c, long id, long project) throws Exception {
        var task = decode(one(c, SELECT + "WHERE t.id=? AND t.project_id=?", id, project));
        task.put("dependencies", rows(c,
                                      "SELECT t.id,t.title,t.status FROM dependencies d JOIN tasks t ON " +
                                      "t.id=d.depends_on WHERE d.task_id=? ORDER BY t.id",
                                      id));
        task.put("comments", rows(c,
                                  "SELECT x.id,x.text,x.created,m.name AS actor FROM comments x JOIN " +
                                  "members m ON m.id=x.actor_id WHERE task_id=? ORDER BY x.id",
                                  id));
        task.put("events", rows(c,
                                "SELECT e.action,e.detail,e.created,m.name AS actor FROM events e JOIN " +
                                "members m ON m.id=e.actor_id WHERE task_id=? ORDER BY e.id",
                                id));
        return task;
    }
    public Object detail(long id, long project) throws Exception {
        try (var c = db.open()) {
            return detail(c, id, project);
        }
    }
    public Object list(long project, String q, String label, String status, int priority, long owner,
                       boolean overdue, int page, int size) throws Exception {
        if (page < 1 || size < 1 || size > 100 || priority < 0 || priority > 3 || owner < 0 ||
            (!status.isEmpty() && !Set.of("todo", "doing", "review", "done").contains(status)))
            throw new BusinessError(400, "筛选或分页参数无效");
        String where = " WHERE t.project_id=? AND instr(t.title,?)>0 AND (?='' OR EXISTS(SELECT 1 FROM " +
                       "json_each(t.labels) WHERE value=?)) AND (?='' OR t.status=?) AND (?=0 OR " +
                       "t.priority=?) AND (?=0 OR t.owner_id=?) AND (?=0 OR (t.status<>'done' AND " +
                       "t.due_date<>'' AND t.due_date<date('now')))";
        var values = new ArrayList<Object>(List.of(project, q, label, label, status, status, priority,
                                                   priority, owner, owner, overdue ? 1 : 0));
        try (var c = db.open()) {
            project(c, project);
            long total = scalar(c, "SELECT count(*) FROM tasks t" + where, values.toArray());
            values.add(size);
            values.add((long)(page - 1) * size);
            var items = rows(c, SELECT + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?", values.toArray());
            for (var item : items)
                decode(item);
            return Map.of("items", items, "total", total, "page", page, "size", size);
        }
    }
    public Object stats(long project) throws Exception {
        try (var c = db.open()) {
            project(c, project);
            return Map.of(
                "total", scalar(c, "SELECT count(*) FROM tasks WHERE project_id=?", project), "statuses",
                rows(c, "SELECT status,count(*) AS count FROM tasks WHERE project_id=? GROUP BY status",
                     project),
                "owners",
                rows(c,
                     "SELECT m.id,m.name,count(t.id) AS count FROM members m LEFT JOIN tasks t ON " +
                     "t.owner_id=m.id WHERE m.project_id=? GROUP BY m.id ORDER BY m.id",
                     project),
                "overdue",
                scalar(c,
                       "SELECT count(*) FROM tasks WHERE project_id=? AND status<>'done' AND due_date<>'' " +
                       "AND due_date<date('now')",
                       project));
        }
    }
    private static void event(Connection c, long task, long actor, String action, String detail)
        throws Exception {
        exec(c, "INSERT INTO events(task_id,actor_id,action,detail) VALUES(?,?,?,?)", task, actor, action,
             detail);
    }
    public Object save(Long id, TaskInput v) throws Exception {
        text(v.title(), 120, "标题");
        if (v.description() == null || v.description().length() > 2000 || v.priority() < 1 ||
            v.priority() > 3)
            throw new BusinessError(400, "描述或优先级无效");
        if (v.dueDate() == null)
            throw new BusinessError(400, "截止日期无效");
        try {
            if (!v.dueDate().isEmpty() && !LocalDate.parse(v.dueDate()).toString().equals(v.dueDate()))
                throw new Exception();
        } catch (Exception e) {
            throw new BusinessError(400, "截止日期须为 YYYY-MM-DD");
        }
        if (v.labels() == null || v.labels().size() > 8 ||
            v.labels().stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 24) ||
            v.labels().stream().distinct().count() != v.labels().size())
            throw new BusinessError(400, "标签须为最多8个不同非空短名称");
        if (v.dependsOn() == null || v.dependsOn().size() > 20 ||
            v.dependsOn().stream().anyMatch(Objects::isNull) ||
            v.dependsOn().stream().distinct().count() != v.dependsOn().size())
            throw new BusinessError(400, "依赖须为最多20个不同任务");
        return db.write(c -> {
            project(c, v.projectId());
            member(c, v.projectId(), v.ownerId());
            member(c, v.projectId(), v.actorId());
            if (id != null) {
                var old =
                    one(c, "SELECT version,status FROM tasks WHERE id=? AND project_id=?", id, v.projectId());
                if (((Number)old.get("version")).longValue() != v.version())
                    throw new BusinessError(409, "任务已被其他成员修改，请刷新后重试");
                if (old.get("status").equals("done"))
                    throw new BusinessError(409, "已完成任务不可编辑");
            }
            for (long dependency : v.dependsOn()) {
                var dep =
                    one(c, "SELECT status FROM tasks WHERE id=? AND project_id=?", dependency, v.projectId());
                if (id != null &&
                    (dependency == id ||
                     scalar(c,
                            "WITH RECURSIVE reachable(id) AS (SELECT depends_on FROM dependencies WHERE " +
                            "task_id=? UNION SELECT d.depends_on FROM dependencies d JOIN reachable r ON " +
                            "d.task_id=r.id) SELECT count(*) FROM reachable WHERE id=?",
                            dependency, id) > 0))
                    throw new BusinessError(409, "任务依赖不能形成环");
            }
            long key;
            if (id == null) {
                exec(c,
                     "INSERT INTO " +
                     "tasks(project_id,title,description,owner_id,priority,labels,status,due_date,version) " +
                     "VALUES(?,?,?,?,?,?,'todo',?,1)",
                     v.projectId(), v.title().trim(), v.description(), v.ownerId(), v.priority(),
                     json.writeValueAsString(v.labels()), v.dueDate());
                key = scalar(c, "SELECT last_insert_rowid()");
            } else {
                key = id;
                exec(c,
                     "UPDATE tasks SET " +
                     "title=?,description=?,owner_id=?,priority=?,labels=?,due_date=?,version=version+1 " +
                     "WHERE id=?",
                     v.title().trim(), v.description(), v.ownerId(), v.priority(),
                     json.writeValueAsString(v.labels()), v.dueDate(), key);
                exec(c, "DELETE FROM dependencies WHERE task_id=?", key);
            }
            for (long dependency : v.dependsOn())
                exec(c, "INSERT INTO dependencies VALUES(?,?)", key, dependency);
            fault("task-written");
            event(c, key, v.actorId(), id == null ? "created" : "updated", v.title());
            return detail(c, key, v.projectId());
        });
    }
    public Object transition(long id, long project, Transition v) throws Exception {
        return db.write(c -> {
            member(c, project, v.actorId());
            var old = one(c, "SELECT status,version FROM tasks WHERE id=? AND project_id=?", id, project);
            if (((Number)old.get("version")).longValue() != v.version())
                throw new BusinessError(409, "任务版本冲突，请刷新后重试");
            String status = (String)old.get("status");
            var allowed = Map.of("todo", Set.of("doing"), "doing", Set.of("review"), "review",
                                 Set.of("doing", "done"), "done", Set.<String>of());
            if (v.status() == null || !allowed.get(status).contains(v.status()))
                throw new BusinessError(409, "非法状态转换");
            if (v.status().equals("done") &&
                scalar(c,
                       "SELECT count(*) FROM dependencies d JOIN tasks t ON t.id=d.depends_on WHERE " +
                       "d.task_id=? AND t.status<>'done'",
                       id) > 0)
                throw new BusinessError(409, "仍有未完成的依赖任务");
            exec(c, "UPDATE tasks SET status=?,version=version+1 WHERE id=?", v.status(), id);
            fault("task-written");
            event(c, id, v.actorId(), "transition", status + " → " + v.status());
            return detail(c, id, project);
        });
    }
    public Object comment(long id, long project, Comment v) throws Exception {
        text(v.text(), 1000, "评论");
        text(v.requestId(), 100, "请求标识");
        return db.write(c -> {
            member(c, project, v.actorId());
            one(c, "SELECT id FROM tasks WHERE id=? AND project_id=?", id, project);
            var old = rows(c, "SELECT task_id,actor_id,text FROM comments WHERE request_id=?", v.requestId());
            if (!old.isEmpty()) {
                var saved = old.getFirst();
                if (((Number)saved.get("task_id")).longValue() != id ||
                    ((Number)saved.get("actor_id")).longValue() != v.actorId() ||
                    !saved.get("text").equals(v.text()))
                    throw new BusinessError(409, "请求标识已用于其他评论");
            } else {
                exec(c, "INSERT INTO comments(task_id,actor_id,text,request_id) VALUES(?,?,?,?)", id,
                     v.actorId(), v.text(), v.requestId());
                event(c, id, v.actorId(), "comment", v.text());
            }
            return detail(c, id, project);
        });
    }
}
