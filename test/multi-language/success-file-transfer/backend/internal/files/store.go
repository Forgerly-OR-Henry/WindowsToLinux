package files

import (
	"database/sql"
	"errors"
	"fmt"
	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

type Store struct {
	db      *sql.DB
	dir     string
	mu      sync.Mutex
	maxSize int64
}
type businessError struct {
	status  int
	message string
}

func (e businessError) Error() string         { return e.message }
func reject(status int, message string) error { return businessError{status, message} }

type queryer interface {
	Query(string, ...any) (*sql.Rows, error)
	QueryRow(string, ...any) *sql.Row
	Exec(string, ...any) (sql.Result, error)
}

func rows(q queryer, query string, args ...any) ([]map[string]any, error) {
	r, err := q.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer r.Close()
	columns, err := r.Columns()
	if err != nil {
		return nil, err
	}
	out := []map[string]any{}
	for r.Next() {
		values := make([]any, len(columns))
		pointers := make([]any, len(columns))
		for i := range values {
			pointers[i] = &values[i]
		}
		if err = r.Scan(pointers...); err != nil {
			return nil, err
		}
		item := map[string]any{}
		for i, k := range columns {
			item[k] = values[i]
		}
		out = append(out, item)
	}
	return out, r.Err()
}
func one(q queryer, query string, args ...any) (map[string]any, error) {
	list, err := rows(q, query, args...)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, reject(404, "记录不存在")
	}
	return list[0], nil
}
func positiveEnv(key string, fallback int64) int64 {
	if v := os.Getenv(key); v != "" {
		n, e := strconv.ParseInt(v, 10, 64)
		if e != nil || n < 1 {
			panic(key + " must be positive")
		}
		return n
	}
	return fallback
}
func Open(dir string) (*Store, error) {
	for _, name := range []string{"chunks", "versions", "staging"} {
		if err := os.MkdirAll(filepath.Join(dir, name), 0700); err != nil {
			return nil, err
		}
	}
	db, err := sql.Open("sqlite", filepath.Join(dir, "files.db"))
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	fail := func(e error) (*Store, error) { db.Close(); return nil, e }
	var version, tables int
	if err = db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return fail(err)
	}
	if err = db.QueryRow("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='files'").Scan(&tables); err != nil {
		return fail(err)
	}
	if version != 2 && tables > 0 {
		return fail(errors.New("sample schema v2 requires a fresh DATA_DIR; old schema migration is not supported"))
	}
	_, err = db.Exec(`PRAGMA foreign_keys=ON; PRAGMA busy_timeout=10000; PRAGMA journal_mode=WAL;
 CREATE TABLE IF NOT EXISTS folders(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE);
 CREATE TABLE IF NOT EXISTS files(id TEXT PRIMARY KEY,folder_id INTEGER NOT NULL REFERENCES folders(id),name TEXT NOT NULL,UNIQUE(folder_id,name));
 CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,folder_id INTEGER NOT NULL REFERENCES folders(id),name TEXT NOT NULL,size INTEGER NOT NULL CHECK(size>=0),sha256 TEXT NOT NULL,chunk_size INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('receiving','verifying','completed','cancelled','failed')),version_id TEXT,error TEXT NOT NULL DEFAULT '',created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
 CREATE TABLE IF NOT EXISTS chunks(upload_id TEXT NOT NULL REFERENCES uploads(id),number INTEGER NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL,PRIMARY KEY(upload_id,number));
 CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY,file_id TEXT NOT NULL REFERENCES files(id),upload_id TEXT NOT NULL UNIQUE REFERENCES uploads(id),number INTEGER NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(file_id,number));
 CREATE TABLE IF NOT EXISTS upload_events(id INTEGER PRIMARY KEY,upload_id TEXT NOT NULL REFERENCES uploads(id),action TEXT NOT NULL,detail TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
 CREATE INDEX IF NOT EXISTS uploads_folder ON uploads(folder_id,created);
 CREATE INDEX IF NOT EXISTS versions_file ON versions(file_id,number);
 INSERT OR IGNORE INTO folders(id,name) VALUES(1,'演示文档'),(2,'数据归档'); PRAGMA user_version=2;`)
	if err != nil {
		return fail(err)
	}
	s := &Store{db: db, dir: dir, maxSize: positiveEnv("MAX_FILE_BYTES", 256*1024*1024)}
	if err = s.recover(); err != nil {
		return fail(err)
	}
	return s, nil
}
func (s *Store) Close() { s.db.Close() }
func (s *Store) recover() error {
	// A committed versions row is the publication boundary; uncommitted files are discarded.
	for _, sub := range []string{"staging", "versions"} {
		entries, e := os.ReadDir(filepath.Join(s.dir, sub))
		if e != nil {
			return e
		}
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			keep := 0
			if sub == "versions" {
				if e = s.db.QueryRow("SELECT count(*) FROM versions WHERE id=?", entry.Name()).Scan(&keep); e != nil {
					return e
				}
			}
			if keep == 0 {
				if e = os.Remove(filepath.Join(s.dir, sub, entry.Name())); e != nil {
					return e
				}
			}
		}
	}
	ended, e := rows(s.db, "SELECT id FROM uploads WHERE state IN ('completed','cancelled','failed')")
	if e != nil {
		return e
	}
	for _, u := range ended {
		if e = os.RemoveAll(filepath.Join(s.dir, "chunks", u["id"].(string))); e != nil {
			return e
		}
	}
	_, err := s.db.Exec(`INSERT INTO upload_events(upload_id,action,detail) SELECT id,'recovered','继续提交已确认的分块' FROM uploads WHERE state='verifying'; UPDATE uploads SET state='receiving' WHERE state='verifying'`)
	return err
}
func (s *Store) event(q queryer, id, action, detail string) error {
	_, err := q.Exec("INSERT INTO upload_events(upload_id,action,detail) VALUES(?,?,?)", id, action, detail)
	return err
}
func (s *Store) Folders() ([]map[string]any, error) {
	return rows(s.db, "SELECT id,name FROM folders ORDER BY id")
}
func (s *Store) AddFolder(name string) (any, error) {
	if !validName(name) {
		return nil, reject(400, "文件夹名称无效")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM folders WHERE name=?", name).Scan(&count); err != nil {
		return nil, err
	}
	if count > 0 {
		return nil, reject(409, "文件夹已存在")
	}
	r, e := s.db.Exec("INSERT INTO folders(name) VALUES(?)", name)
	if e != nil {
		return nil, e
	}
	id, e := r.LastInsertId()
	return map[string]any{"id": id, "name": name}, e
}
func (s *Store) List(folder int64, q string, offset, limit int) (any, error) {
	list, e := rows(s.db, `SELECT f.id,f.folder_id AS folderId,f.name,v.id AS versionId,v.number AS version,v.size,v.sha256,v.created FROM files f JOIN versions v ON v.file_id=f.id AND v.number=(SELECT max(number) FROM versions WHERE file_id=f.id) WHERE f.folder_id=? AND instr(f.name,?)>0 ORDER BY f.name LIMIT ? OFFSET ?`, folder, q, limit, offset)
	if e != nil {
		return nil, e
	}
	var total int
	e = s.db.QueryRow("SELECT count(*) FROM files WHERE folder_id=? AND instr(name,?)>0", folder, q).Scan(&total)
	return map[string]any{"items": list, "total": total, "offset": offset, "limit": limit}, e
}
func (s *Store) Versions(id string) (any, error) {
	if _, e := one(s.db, "SELECT id FROM files WHERE id=?", id); e != nil {
		return nil, e
	}
	return rows(s.db, "SELECT id,number,size,sha256,created FROM versions WHERE file_id=? ORDER BY number DESC", id)
}
func (s *Store) History(folder int64) (any, error) {
	return rows(s.db, "SELECT id,name,size,state,version_id AS versionId,error,created FROM uploads WHERE folder_id=? ORDER BY rowid DESC LIMIT 200", folder)
}
func fault(point string) error {
	if os.Getenv("SAMPLE_FAULT_POINT") != point {
		return nil
	}
	dir := os.Getenv("SAMPLE_FAULT_DIR")
	if dir == "" {
		return errors.New("SAMPLE_FAULT_DIR required")
	}
	if e := os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	if e := os.WriteFile(filepath.Join(dir, point+".ready"), []byte(strconv.Itoa(os.Getpid())), 0600); e != nil {
		return e
	}
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if _, e := os.Stat(filepath.Join(dir, point+".release")); e == nil {
			return nil
		}
		time.Sleep(20 * time.Millisecond)
	}
	return fmt.Errorf("fault gate %s timed out", point)
}
