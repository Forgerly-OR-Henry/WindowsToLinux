package files

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

func reply(w http.ResponseWriter, status int, v any) {
	replyObserved(w, status, v, nil)
}
func replyObserved(w http.ResponseWriter, status int, v any, o *chunkObservation) {
	if o != nil {
		o.status = status
	}
	o.emit("HTTP_RESPONSE_BEGIN", nil)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	err := json.NewEncoder(w).Encode(v)
	o.emit("HTTP_RESPONSE_END", err)
}
func route(fn func(*http.Request) (any, error)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var observation *chunkObservation
		if r.Method == http.MethodPut && r.Pattern == "PUT /api/uploads/{id}/chunks/{number}" {
			if number, err := strconv.Atoi(r.PathValue("number")); err == nil && strconv.Itoa(number) == r.PathValue("number") {
				observation = newChunkObservation(r.Context(), r.PathValue("id"), number)
				if observation != nil {
					r = r.WithContext(context.WithValue(r.Context(), chunkObservationKey{}, observation))
				}
			}
		}
		v, e := fn(r)
		if e != nil {
			var b businessError
			if errors.As(e, &b) {
				replyObserved(w, b.status, map[string]string{"error": b.message}, observation)
				return
			}
			if observation != nil {
				observation.emit("HTTP_HANDLER_ERROR", e)
			} else {
				log.Printf("request %s %s: %v", r.Method, r.URL.Path, e)
			}
			replyObserved(w, 500, map[string]string{"error": "存储操作失败，请检查服务日志"}, observation)
			return
		}
		replyObserved(w, 200, v, observation)
	}
}
func body(r *http.Request, v any) error {
	d := json.NewDecoder(io.LimitReader(r.Body, 65537))
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		return reject(400, "JSON 请求无效")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return reject(400, "请求必须是单个 JSON 对象")
	}
	return nil
}
func folder(r *http.Request) (int64, error) {
	v, e := strconv.ParseInt(r.URL.Query().Get("folderId"), 10, 64)
	if e != nil || v < 1 {
		return 0, reject(400, "folderId 必须是正整数")
	}
	return v, nil
}
func paging(r *http.Request) (int, int, error) {
	offset, limit := 0, 25
	var e error
	if v := r.URL.Query().Get("offset"); v != "" {
		offset, e = strconv.Atoi(v)
		if e != nil || offset < 0 {
			return 0, 0, reject(400, "offset 无效")
		}
	}
	if v := r.URL.Query().Get("limit"); v != "" {
		limit, e = strconv.Atoi(v)
		if e != nil || limit < 1 || limit > 100 {
			return 0, 0, reject(400, "limit 必须为1..100")
		}
	}
	return offset, limit, nil
}
func Routes(s *Store) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", route(func(r *http.Request) (any, error) {
		return map[string]any{"status": "ok", "component": "go-files", "version": 2}, s.db.Ping()
	}))
	mux.HandleFunc("GET /api/folders", route(func(r *http.Request) (any, error) { return s.Folders() }))
	mux.HandleFunc("POST /api/folders", route(func(r *http.Request) (any, error) {
		var v struct {
			Name string `json:"name"`
		}
		if e := body(r, &v); e != nil {
			return nil, e
		}
		return s.AddFolder(v.Name)
	}))
	mux.HandleFunc("GET /api/files", route(func(r *http.Request) (any, error) {
		f, e := folder(r)
		if e != nil {
			return nil, e
		}
		o, l, e := paging(r)
		if e != nil {
			return nil, e
		}
		return s.List(f, r.URL.Query().Get("q"), o, l)
	}))
	mux.HandleFunc("GET /api/files/{id}/versions", route(func(r *http.Request) (any, error) { return s.Versions(r.PathValue("id")) }))
	mux.HandleFunc("GET /api/uploads", route(func(r *http.Request) (any, error) {
		f, e := folder(r)
		if e != nil {
			return nil, e
		}
		return s.History(f)
	}))
	mux.HandleFunc("POST /api/uploads", route(func(r *http.Request) (any, error) {
		var v UploadInput
		if e := body(r, &v); e != nil {
			return nil, e
		}
		return s.Create(v)
	}))
	mux.HandleFunc("GET /api/uploads/{id}", route(func(r *http.Request) (any, error) { return s.Get(r.PathValue("id")) }))
	mux.HandleFunc("PUT /api/uploads/{id}/chunks/{number}", route(func(r *http.Request) (any, error) {
		n, e := strconv.Atoi(r.PathValue("number"))
		if e != nil {
			return nil, reject(400, "分块编号无效")
		}
		return s.Chunk(r.Context(), r.PathValue("id"), n, r.Body)
	}))
	mux.HandleFunc("POST /api/uploads/{id}/complete", route(func(r *http.Request) (any, error) { return s.Complete(r.Context(), r.PathValue("id")) }))
	mux.HandleFunc("POST /api/uploads/{id}/cancel", route(func(r *http.Request) (any, error) { return s.Cancel(r.PathValue("id")) }))
	mux.HandleFunc("GET /api/versions/{id}/download", func(w http.ResponseWriter, r *http.Request) {
		record, e := one(s.db, "SELECT v.id,f.name,v.sha256 FROM versions v JOIN files f ON f.id=v.file_id WHERE v.id=?", r.PathValue("id"))
		if e != nil {
			reply(w, 404, map[string]string{"error": "已发布版本不存在"})
			return
		}
		f, e := os.Open(filepath.Join(s.dir, "versions", record["id"].(string)))
		if e != nil {
			reply(w, 500, map[string]string{"error": "版本内容缺失"})
			return
		}
		defer f.Close()
		serveDownload(w, r, f, record["name"].(string), record["sha256"].(string), record["id"].(string))
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Sample-Protocol", "2")
		mux.ServeHTTP(w, r)
	})
}

type downloadWriter struct {
	http.ResponseWriter
	controller *http.ResponseController
	written    int64
	err        error
}

func (w *downloadWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *downloadWriter) Write(p []byte) (int, error) {
	if w.err != nil {
		return 0, w.err
	}
	n, err := w.ResponseWriter.Write(p)
	w.written += int64(n)
	if err == nil && n != len(p) {
		err = io.ErrShortWrite
	}
	w.err = err
	return n, err
}

func serveDownload(w http.ResponseWriter, r *http.Request, f *os.File, name, digest, version string) {
	stat, err := f.Stat()
	if err != nil {
		reply(w, 500, map[string]string{"error": "读取版本失败"})
		return
	}
	stream := &downloadWriter{ResponseWriter: w, controller: http.NewResponseController(w)}
	// The final client measures body progress and cancels stalled downloads.
	// A blocked gateway write does not mean the final client stopped receiving.
	if stream.err = stream.controller.SetWriteDeadline(time.Time{}); stream.err != nil {
		reply(w, 500, map[string]string{"error": "无法设置下载期限"})
	} else {
		w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
		w.Header().Set("X-Content-SHA256", digest)
		http.ServeContent(stream, r, name, stat.ModTime(), f)
		if stream.err == nil && stream.written > 0 {
			stream.err = stream.controller.Flush()
		}
	}
	if stream.err != nil {
		declared, err := strconv.ParseInt(w.Header().Get("Content-Length"), 10, 64)
		if err != nil {
			declared = -1
		}
		var errno syscall.Errno
		hasErrno := errors.As(stream.err, &errno)
		log.Printf("FILE_TRANSFER_DOWNLOAD_WRITE_FAILED version=%s objectBytes=%d declaredBodyBytes=%d acceptedBodyBytes=%d errorType=%T errnoPresent=%t errno=%d timeout=%t",
			version, stat.Size(), declared, stream.written, stream.err, hasErrno, uint64(errno), os.IsTimeout(stream.err))
	}
}
