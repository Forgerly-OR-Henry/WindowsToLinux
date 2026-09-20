using Microsoft.Data.Sqlite;
using System.Text.Json;
namespace AssetLending;

public sealed partial class AssetStore
{
    readonly string connectionString;
    static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    public static readonly string[] Actors = ["林同学", "周同学", "陈老师"];
    public AssetStore()
    {
        var dir = Path.GetFullPath(Environment.GetEnvironmentVariable("DATA_DIR") ?? "data");
        Directory.CreateDirectory(dir);
        connectionString = new SqliteConnectionStringBuilder { DataSource = Path.Combine(dir, "assets.db"), ForeignKeys = true, DefaultTimeout = 10 }.ToString();
        using var c = Open();
        if (Scalar(c, null, "PRAGMA user_version") != 2 && Scalar(c, null, "SELECT count(*) FROM sqlite_master WHERE name='assets'") > 0)
            throw new InvalidOperationException("Sample schema v2 requires a fresh DATA_DIR; old schema migration is not supported");
        Exec(c, null, """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE);
            CREATE TABLE IF NOT EXISTS loans(id INTEGER PRIMARY KEY,borrower TEXT NOT NULL,due_date TEXT NOT NULL,purpose TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('draft','submitted','approved','rejected','checked_out','partially_returned','closed')),created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
            CREATE TABLE IF NOT EXISTS assets(id INTEGER PRIMARY KEY,name TEXT NOT NULL,category_id INTEGER NOT NULL REFERENCES categories(id),serial TEXT NOT NULL UNIQUE,status TEXT NOT NULL CHECK(status IN ('available','reserved','lent','maintenance')),loan_id INTEGER REFERENCES loans(id));
            CREATE TABLE IF NOT EXISTS loan_items(loan_id INTEGER NOT NULL REFERENCES loans(id),asset_id INTEGER NOT NULL REFERENCES assets(id),returned INTEGER NOT NULL DEFAULT 0 CHECK(returned IN (0,1)),condition TEXT NOT NULL DEFAULT '',returned_at TEXT,PRIMARY KEY(loan_id,asset_id));
            CREATE TABLE IF NOT EXISTS maintenance(id INTEGER PRIMARY KEY,asset_id INTEGER NOT NULL REFERENCES assets(id),loan_id INTEGER NOT NULL REFERENCES loans(id),status TEXT NOT NULL CHECK(status IN ('open','completed')),note TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,completed TEXT);
            CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,loan_id INTEGER REFERENCES loans(id),asset_id INTEGER REFERENCES assets(id),action TEXT NOT NULL,actor TEXT NOT NULL,detail TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
            CREATE TABLE IF NOT EXISTS requests(request_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,result TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS asset_search ON assets(category_id,status,id);
            CREATE INDEX IF NOT EXISTS loan_state ON loans(status,due_date,id);
            CREATE INDEX IF NOT EXISTS item_asset ON loan_items(asset_id,loan_id);
            CREATE INDEX IF NOT EXISTS history_asset ON events(asset_id,id);
            CREATE INDEX IF NOT EXISTS history_loan ON events(loan_id,id);
            CREATE UNIQUE INDEX IF NOT EXISTS one_repair ON maintenance(asset_id) WHERE status='open';
            INSERT OR IGNORE INTO categories VALUES(1,'计算设备'),(2,'影像设备');
            PRAGMA user_version=2;
            """);
        if (Scalar(c, null, "SELECT count(*) FROM assets") == 0)
        {
            using var tx = c.BeginTransaction(deferred: false);
            foreach (var a in new[] { new AssetInput("演示笔记本", 1, "DEMO-PC-01"), new AssetInput("演示平板", 1, "DEMO-PAD-01"), new AssetInput("演示相机", 2, "DEMO-CAM-01") })
                Exec(c, tx, "INSERT INTO assets(name,category_id,serial,status) VALUES(@p0,@p1,@p2,'available')", a.Name, a.CategoryId, a.Serial);
            tx.Commit();
        }
    }
    SqliteConnection Open() { var c = new SqliteConnection(connectionString); c.Open(); return c; }
    static SqliteCommand Command(SqliteConnection c, SqliteTransaction? tx, string sql, params object?[] values) { var cmd = c.CreateCommand(); cmd.Transaction = tx; cmd.CommandText = sql; for (int i = 0; i < values.Length; i++) cmd.Parameters.AddWithValue("@p" + i, values[i] ?? DBNull.Value); return cmd; }
    static int Exec(SqliteConnection c, SqliteTransaction? tx, string sql, params object?[] values) { using var cmd = Command(c, tx, sql, values); return cmd.ExecuteNonQuery(); }
    static long Scalar(SqliteConnection c, SqliteTransaction? tx, string sql, params object?[] values) { using var cmd = Command(c, tx, sql, values); return Convert.ToInt64(cmd.ExecuteScalar()); }
    static List<Dictionary<string, object?>> Rows(SqliteConnection c, SqliteTransaction? tx, string sql, params object?[] values) { using var cmd = Command(c, tx, sql, values); using var r = cmd.ExecuteReader(); var list = new List<Dictionary<string, object?>>(); while (r.Read()) { var item = new Dictionary<string, object?>(); for (int i = 0; i < r.FieldCount; i++) item[r.GetName(i)] = r.IsDBNull(i) ? null : r.GetValue(i); list.Add(item); } return list; }
    static Dictionary<string, object?> One(SqliteConnection c, SqliteTransaction? tx, string sql, params object?[] values) { return Rows(c, tx, sql, values).FirstOrDefault() ?? throw new BusinessError(404, "记录不存在"); }
    static void Text(string? v, int max, string label) { if (string.IsNullOrWhiteSpace(v) || v.Length > max) throw new BusinessError(400, label + "无效"); }
    static void Actor(string value) { if (!Actors.Contains(value)) throw new BusinessError(400, "请选择固定演示身份（不属于身份认证）"); }
    static void Event(SqliteConnection c, SqliteTransaction tx, long? loan, long? asset, string action, string actor, string detail) { Exec(c, tx, "INSERT INTO events(loan_id,asset_id,action,actor,detail) VALUES(@p0,@p1,@p2,@p3,@p4)", loan, asset, action, actor, detail); }
    JsonElement Request(string key, object fingerprint, Func<SqliteConnection, SqliteTransaction, object> perform)
    {
        Text(key, 100, "请求标识"); string encoded = JsonSerializer.Serialize(fingerprint, JsonOptions);
        using var c = Open(); using var tx = c.BeginTransaction(deferred: false);
        var existing = Rows(c, tx, "SELECT fingerprint,result FROM requests WHERE request_id=@p0", key);
        if (existing.Count > 0) { if ((string)existing[0]["fingerprint"]! != encoded) throw new BusinessError(409, "请求标识已用于不同内容"); return JsonSerializer.Deserialize<JsonElement>((string)existing[0]["result"]!); }
        var result = perform(c, tx); string json = JsonSerializer.Serialize(result, JsonOptions);
        Exec(c, tx, "INSERT INTO requests VALUES(@p0,@p1,@p2)", key, encoded, json); tx.Commit(); return JsonSerializer.Deserialize<JsonElement>(json);
    }
    public object Categories() { using var c = Open(); return Rows(c, null, "SELECT id,name FROM categories ORDER BY id"); }
    public object AddCategory(CategoryInput input) { Text(input.Name, 60, "分类名称"); using var c = Open(); Exec(c, null, "INSERT INTO categories(name) VALUES(@p0)", input.Name.Trim()); return new { id = Scalar(c, null, "SELECT last_insert_rowid()"), name = input.Name.Trim() }; }
    public object Create(AssetInput input) { Text(input.Name, 120, "名称"); Text(input.Serial, 80, "资产编号"); using var c = Open(); One(c, null, "SELECT id FROM categories WHERE id=@p0", input.CategoryId); Exec(c, null, "INSERT INTO assets(name,category_id,serial,status) VALUES(@p0,@p1,@p2,'available')", input.Name.Trim(), input.CategoryId, input.Serial.Trim()); return One(c, null, "SELECT id,name,category_id AS categoryId,serial,status FROM assets WHERE id=last_insert_rowid()"); }
    public object List(string q, string status, long category, int offset, int limit) { using var c = Open(); const string where = " FROM assets a JOIN categories c ON c.id=a.category_id WHERE (instr(a.name,@p0)>0 OR instr(a.serial,@p0)>0) AND (@p1='' OR a.status=@p1) AND (@p2=0 OR a.category_id=@p2)"; return new { items = Rows(c, null, "SELECT a.id,a.name,a.serial,a.status,a.category_id AS categoryId,c.name AS category,a.loan_id AS loanId" + where + " ORDER BY a.id DESC LIMIT @p3 OFFSET @p4", q, status, category, limit, offset), total = Scalar(c, null, "SELECT count(*)" + where, q, status, category), offset, limit }; }
    public object Stats() { using var c = Open(); return new { total = Scalar(c, null, "SELECT count(*) FROM assets"), states = Rows(c, null, "SELECT status,count(*) AS count FROM assets GROUP BY status"), loans = Rows(c, null, "SELECT status,count(*) AS count FROM loans GROUP BY status"), overdue = Scalar(c, null, "SELECT count(*) FROM loans WHERE status IN ('checked_out','partially_returned') AND due_date<date('now')"), repairs = Scalar(c, null, "SELECT count(*) FROM maintenance WHERE status='open'") }; }
    public object History(long id) { using var c = Open(); var asset = One(c, null, "SELECT a.*,c.name AS category FROM assets a JOIN categories c ON c.id=a.category_id WHERE a.id=@p0", id); return new { asset, events = Rows(c, null, "SELECT action,actor,detail,created,loan_id AS loanId FROM events WHERE asset_id=@p0 ORDER BY id", id) }; }
    public object Repairs() { using var c = Open(); return Rows(c, null, "SELECT m.id,m.asset_id AS assetId,a.name,m.loan_id AS loanId,m.status,m.note,m.created,m.completed FROM maintenance m JOIN assets a ON a.id=m.asset_id ORDER BY m.id DESC LIMIT 200"); }
    static void Fault(string point) { if (Environment.GetEnvironmentVariable("SAMPLE_FAULT_POINT") != point) return; var dir = Environment.GetEnvironmentVariable("SAMPLE_FAULT_DIR") ?? throw new InvalidOperationException("SAMPLE_FAULT_DIR required"); Directory.CreateDirectory(dir); File.WriteAllText(Path.Combine(dir, point + ".ready"), Environment.ProcessId.ToString()); var end = DateTime.UtcNow.AddSeconds(60); while (DateTime.UtcNow < end) { if (File.Exists(Path.Combine(dir, point + ".release"))) return; Thread.Sleep(20); } throw new TimeoutException("Fault gate expired: " + point); }
}
