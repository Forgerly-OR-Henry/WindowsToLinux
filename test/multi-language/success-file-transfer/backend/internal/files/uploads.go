package files

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode/utf8"
)

type UploadInput struct {
	FolderID  int64  `json:"folderId"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	ChunkSize int64  `json:"chunkSize"`
}
type Upload struct {
	ID string `json:"id"`
	UploadInput
	State     string           `json:"state"`
	VersionID string           `json:"versionId"`
	Error     string           `json:"error"`
	Chunks    []map[string]any `json:"chunks"`
	Events    []map[string]any `json:"events"`
}

func validName(v string) bool {
	return strings.TrimSpace(v) != "" && utf8.ValidString(v) && len(v) <= 240 && !strings.ContainsAny(v, "/\\\x00\r\n") && v != "." && v != ".."
}
func newID() (string, error) {
	var b [16]byte
	_, e := rand.Read(b[:])
	return hex.EncodeToString(b[:]), e
}
func (s *Store) load(id string) (Upload, error) {
	var u Upload
	var version *string
	e := s.db.QueryRow("SELECT id,folder_id,name,size,sha256,chunk_size,state,version_id,error FROM uploads WHERE id=?", id).Scan(&u.ID, &u.FolderID, &u.Name, &u.Size, &u.SHA256, &u.ChunkSize, &u.State, &version, &u.Error)
	if errors.Is(e, sql.ErrNoRows) {
		return u, reject(404, "上传会话不存在")
	}
	if version != nil {
		u.VersionID = *version
	}
	return u, e
}
func (s *Store) Get(id string) (any, error) {
	u, e := s.load(id)
	if e != nil {
		return nil, e
	}
	u.Chunks, e = rows(s.db, "SELECT number,size,sha256 FROM chunks WHERE upload_id=? ORDER BY number", id)
	if e != nil {
		return nil, e
	}
	u.Events, e = rows(s.db, "SELECT action,detail,created FROM upload_events WHERE upload_id=? ORDER BY id", id)
	return u, e
}
func (s *Store) Create(v UploadInput) (any, error) {
	hash, e := hex.DecodeString(v.SHA256)
	if !validName(v.Name) || e != nil || len(hash) != 32 || v.Size < 0 || v.Size > s.maxSize || v.ChunkSize < 65536 || v.ChunkSize > 4*1024*1024 {
		return nil, reject(400, "名称、SHA-256、文件大小或分块大小无效")
	}
	v.SHA256 = strings.ToLower(v.SHA256)
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, e = one(s.db, "SELECT id FROM folders WHERE id=?", v.FolderID); e != nil {
		return nil, e
	}
	id, e := newID()
	if e != nil {
		return nil, e
	}
	if e = os.Mkdir(filepath.Join(s.dir, "chunks", id), 0700); e != nil {
		return nil, e
	}
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	_, e = tx.Exec("INSERT INTO uploads(id,folder_id,name,size,sha256,chunk_size,state) VALUES(?,?,?,?,?,?,'receiving')", id, v.FolderID, v.Name, v.Size, v.SHA256, v.ChunkSize)
	if e != nil {
		return nil, e
	}
	if e = s.event(tx, id, "created", v.Name); e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	return s.Get(id)
}
func (s *Store) Chunk(ctx context.Context, id string, number int, source io.Reader) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, e := s.load(id)
	if e != nil {
		return nil, e
	}
	if u.State != "receiving" {
		return nil, reject(409, "会话已结束或正在校验")
	}
	count := (u.Size + u.ChunkSize - 1) / u.ChunkSize
	if number < 0 || int64(number) >= count {
		return nil, reject(400, "分块编号超出范围")
	}
	expected := u.ChunkSize
	if int64(number) == count-1 {
		expected = u.Size - int64(number)*u.ChunkSize
	}
	tmp, e := os.CreateTemp(filepath.Join(s.dir, "staging"), "chunk-")
	if e != nil {
		return nil, e
	}
	defer os.Remove(tmp.Name())
	digest := sha256.New()
	n, e := io.Copy(io.MultiWriter(tmp, digest), io.LimitReader(source, expected+1))
	syncErr := tmp.Sync()
	closeErr := tmp.Close()
	if e != nil || n != expected || ctx.Err() != nil {
		return nil, reject(400, "分块中断或长度不匹配")
	}
	if syncErr != nil {
		return nil, syncErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	hash := hex.EncodeToString(digest.Sum(nil))
	var previous string
	e = s.db.QueryRow("SELECT sha256 FROM chunks WHERE upload_id=? AND number=?", id, number).Scan(&previous)
	if e == nil {
		if previous != hash {
			return nil, reject(409, "相同分块编号的内容冲突")
		}
		return map[string]any{"number": number, "sha256": hash, "duplicate": true}, nil
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return nil, e
	}
	dest := filepath.Join(s.dir, "chunks", id, strconv.Itoa(number))
	if _, e = os.Stat(dest); e == nil {
		if e = os.Remove(dest); e != nil {
			return nil, e
		}
	}
	if e = os.Rename(tmp.Name(), dest); e != nil {
		return nil, e
	}
	if e = fault("chunk-written"); e != nil {
		return nil, e
	}
	_, e = s.db.Exec("INSERT INTO chunks(upload_id,number,size,sha256) VALUES(?,?,?,?)", id, number, n, hash)
	if e != nil {
		return nil, e
	}
	return map[string]any{"number": number, "sha256": hash, "duplicate": false}, nil
}
func (s *Store) Complete(ctx context.Context, id string) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, e := s.load(id)
	if e != nil {
		return nil, e
	}
	if u.State == "completed" {
		return one(s.db, "SELECT id,file_id AS fileId,number,size,sha256 FROM versions WHERE id=?", u.VersionID)
	}
	if u.State != "receiving" {
		return nil, reject(409, "会话不可发布")
	}
	var count int64
	if e = s.db.QueryRow("SELECT count(*) FROM chunks WHERE upload_id=?", id).Scan(&count); e != nil {
		return nil, e
	}
	if count != (u.Size+u.ChunkSize-1)/u.ChunkSize {
		return nil, reject(409, "仍有分块未确认")
	}
	if _, e = s.db.Exec("UPDATE uploads SET state='verifying' WHERE id=?", id); e != nil {
		return nil, e
	}
	published := false
	defer func() {
		if !published {
			s.db.Exec("UPDATE uploads SET state='receiving' WHERE id=? AND state='verifying'", id)
		}
	}()
	tmp, e := os.CreateTemp(filepath.Join(s.dir, "staging"), "publish-")
	if e != nil {
		return nil, e
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	digest := sha256.New()
	var total int64
	for i := int64(0); i < count; i++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		part, e := os.Open(filepath.Join(s.dir, "chunks", id, strconv.FormatInt(i, 10)))
		if e != nil {
			return nil, e
		}
		n, e := io.Copy(io.MultiWriter(tmp, digest), part)
		part.Close()
		if e != nil {
			return nil, e
		}
		total += n
	}
	if total != u.Size || hex.EncodeToString(digest.Sum(nil)) != u.SHA256 {
		_, e = s.db.Exec("UPDATE uploads SET state='failed',error='整文件 SHA-256 校验失败' WHERE id=?", id)
		if e != nil {
			return nil, e
		}
		return nil, reject(422, "整文件 SHA-256 校验失败")
	}
	if e = tmp.Sync(); e != nil {
		return nil, e
	}
	if e = tmp.Close(); e != nil {
		return nil, e
	}
	versionID, e := newID()
	if e != nil {
		return nil, e
	}
	dest := filepath.Join(s.dir, "versions", versionID)
	if e = os.Rename(tmp.Name(), dest); e != nil {
		return nil, e
	}
	defer func() {
		if !published {
			os.Remove(dest)
		}
	}()
	if e = fault("before-publish"); e != nil {
		return nil, e
	}
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	fileID, e := newID()
	if e != nil {
		return nil, e
	}
	if _, e = tx.Exec("INSERT OR IGNORE INTO files(id,folder_id,name) VALUES(?,?,?)", fileID, u.FolderID, u.Name); e != nil {
		return nil, e
	}
	if e = tx.QueryRow("SELECT id FROM files WHERE folder_id=? AND name=?", u.FolderID, u.Name).Scan(&fileID); e != nil {
		return nil, e
	}
	var number int
	if e = tx.QueryRow("SELECT coalesce(max(number),0)+1 FROM versions WHERE file_id=?", fileID).Scan(&number); e != nil {
		return nil, e
	}
	if _, e = tx.Exec("INSERT INTO versions(id,file_id,upload_id,number,size,sha256) VALUES(?,?,?,?,?,?)", versionID, fileID, id, number, u.Size, u.SHA256); e != nil {
		return nil, e
	}
	if _, e = tx.Exec("UPDATE uploads SET state='completed',version_id=? WHERE id=?", versionID, id); e != nil {
		return nil, e
	}
	if e = s.event(tx, id, "published", versionID); e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	published = true
	if e = os.RemoveAll(filepath.Join(s.dir, "chunks", id)); e != nil {
		return nil, e
	}
	return map[string]any{"id": versionID, "fileId": fileID, "number": number, "size": u.Size, "sha256": u.SHA256}, nil
}
func (s *Store) Cancel(id string) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, e := s.load(id)
	if e != nil {
		return nil, e
	}
	if u.State == "completed" {
		return nil, reject(409, "已发布版本不能取消")
	}
	if u.State != "cancelled" {
		tx, e := s.db.Begin()
		if e != nil {
			return nil, e
		}
		defer tx.Rollback()
		if _, e = tx.Exec("UPDATE uploads SET state='cancelled' WHERE id=?", id); e != nil {
			return nil, e
		}
		if e = s.event(tx, id, "cancelled", ""); e != nil {
			return nil, e
		}
		if e = tx.Commit(); e != nil {
			return nil, e
		}
	}
	if e = os.RemoveAll(filepath.Join(s.dir, "chunks", u.ID)); e != nil {
		return nil, e
	}
	return s.Get(id)
}
