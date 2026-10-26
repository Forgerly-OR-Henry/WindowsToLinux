package files

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

type chunkObservationKey struct{}

type chunkObservation struct {
	ctx                      context.Context
	id                       string
	number, sequence, status int
	started                  time.Time
	expected, copied         int64
	readBytes, writeBytes    int64
	readCalls, writeCalls    int
	readTime, writeTime      time.Duration
	firstRead, lastRead      time.Duration
}

func newChunkObservation(ctx context.Context, id string, number int) *chunkObservation {
	raw, err := hex.DecodeString(id)
	if err != nil || len(raw) != 16 || id != strings.ToLower(id) || number < 0 || int64(number) > 9007199254740991 {
		return nil
	}
	o := &chunkObservation{ctx: ctx, id: id, number: number, started: time.Now(), expected: -1, firstRead: -time.Millisecond, lastRead: -time.Millisecond}
	o.emit("ACCEPTED", nil)
	return o
}

func (o *chunkObservation) emit(stage string, err error) {
	if o == nil || o.sequence >= 32 {
		return
	}
	// Observation failures must not replace the upload's original result.
	defer func() { _ = recover() }()
	o.sequence++
	line, marshalErr := json.Marshal(map[string]any{
		"uploadId": o.id, "chunkIndex": o.number, "requestId": strconv.FormatInt(o.started.UnixNano(), 10),
		"sequence": o.sequence, "stage": stage, "startedAtUtc": o.started.UTC().Format(time.RFC3339Nano),
		"elapsedMillis":     float64(time.Since(o.started)) / float64(time.Millisecond),
		"expectedBodyBytes": o.expected, "copiedBytes": o.copied, "sourceReadBytes": o.readBytes,
		"stagingWrittenBytes": o.writeBytes, "sourceReadCalls": o.readCalls, "stagingWriteCalls": o.writeCalls,
		"sourceReadCallMillis":   float64(o.readTime) / float64(time.Millisecond),
		"stagingWriteCallMillis": float64(o.writeTime) / float64(time.Millisecond),
		"firstReadElapsedMillis": float64(o.firstRead) / float64(time.Millisecond),
		"lastReadElapsedMillis":  float64(o.lastRead) / float64(time.Millisecond),
		"contextCancelled":       o.ctx.Err() != nil, "errorType": fmt.Sprintf("%T", err), "timeout": os.IsTimeout(err),
		"responseStatus": o.status, "timingMeaning": "CALL_DURATION_NOT_PURE_NETWORK_TIME_OR_PEER_RECEIPT",
	})
	if marshalErr == nil {
		log.Printf("FILE_TRANSFER_UPLOAD_DIAGNOSTIC %s", line)
	}
}

type chunkObservedReader struct {
	io.Reader
	o *chunkObservation
}

func (r chunkObservedReader) Read(p []byte) (int, error) {
	if r.o.readCalls == 0 {
		r.o.emit("BODY_FIRST_READ_BEGIN", nil)
	}
	started := time.Now()
	n, err := r.Reader.Read(p)
	r.o.readTime += time.Since(started)
	r.o.readCalls++
	r.o.readBytes += int64(n)
	if n > 0 {
		r.o.lastRead = time.Since(r.o.started)
		if r.o.firstRead < 0 {
			r.o.firstRead = r.o.lastRead
		}
	}
	if r.o.readCalls == 1 {
		r.o.emit("BODY_FIRST_READ_END", err)
	}
	return n, err
}

type chunkObservedWriter struct {
	io.Writer
	o *chunkObservation
}

func (w chunkObservedWriter) Write(p []byte) (int, error) {
	if w.o.writeCalls == 0 {
		w.o.emit("STAGING_FIRST_WRITE_BEGIN", nil)
	}
	started := time.Now()
	n, err := w.Writer.Write(p)
	w.o.writeTime += time.Since(started)
	w.o.writeCalls++
	w.o.writeBytes += int64(n)
	if w.o.writeCalls == 1 {
		w.o.emit("STAGING_FIRST_WRITE_END", err)
	}
	return n, err
}

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
func (s *Store) Chunk(ctx context.Context, id string, number int, source io.Reader) (result any, returnedErr error) {
	o, _ := ctx.Value(chunkObservationKey{}).(*chunkObservation)
	if o == nil {
		o = newChunkObservation(ctx, id, number)
	}
	defer func() { o.emit("CHUNK_RETURN", returnedErr) }()
	o.emit("LOCK_WAIT_BEGIN", nil)
	s.mu.Lock()
	defer s.mu.Unlock()
	o.emit("LOCK_ACQUIRED", nil)
	o.emit("SESSION_LOOKUP_BEGIN", nil)
	u, e := s.load(id)
	o.emit("SESSION_LOOKUP_END", e)
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
	if o != nil {
		o.expected = expected
	}
	o.emit("STAGING_CREATE_BEGIN", nil)
	tmp, e := os.CreateTemp(filepath.Join(s.dir, "staging"), "chunk-")
	o.emit("STAGING_CREATE_END", e)
	if e != nil {
		return nil, e
	}
	defer os.Remove(tmp.Name())
	digest := sha256.New()
	var destination io.Writer = tmp
	limited := io.LimitReader(source, expected+1)
	var input io.Reader = limited
	if o != nil {
		input, destination = chunkObservedReader{limited, o}, chunkObservedWriter{tmp, o}
	}
	o.emit("BODY_COPY_BEGIN", nil)
	n, e := io.Copy(io.MultiWriter(destination, digest), input)
	if o != nil {
		o.copied = n
	}
	o.emit("BODY_COPY_END", e)
	o.emit("SYNC_BEGIN", nil)
	syncErr := tmp.Sync()
	o.emit("SYNC_END", syncErr)
	o.emit("STAGING_CLOSE_BEGIN", nil)
	closeErr := tmp.Close()
	o.emit("STAGING_CLOSE_END", closeErr)
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
	o.emit("METADATA_LOOKUP_BEGIN", nil)
	e = s.db.QueryRow("SELECT sha256 FROM chunks WHERE upload_id=? AND number=?", id, number).Scan(&previous)
	o.emit("METADATA_LOOKUP_END", e)
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
	o.emit("CHUNK_RENAME_BEGIN", nil)
	if _, e = os.Stat(dest); e == nil {
		if e = os.Remove(dest); e != nil {
			return nil, e
		}
	}
	if e = os.Rename(tmp.Name(), dest); e != nil {
		return nil, e
	}
	o.emit("CHUNK_RENAME_END", nil)
	o.emit("FAULT_GATE_BEGIN", nil)
	if e = fault("chunk-written"); e != nil {
		o.emit("FAULT_GATE_END", e)
		return nil, e
	}
	o.emit("FAULT_GATE_END", nil)
	o.emit("METADATA_COMMIT_BEGIN", nil)
	_, e = s.db.Exec("INSERT INTO chunks(upload_id,number,size,sha256) VALUES(?,?,?,?)", id, number, n, hash)
	o.emit("METADATA_COMMIT_END", e)
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
