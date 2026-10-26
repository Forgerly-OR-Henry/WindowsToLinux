package files

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type uploadLogCapture struct {
	mu    sync.Mutex
	lines []map[string]any
	wake  chan struct{}
	raw   bytes.Buffer
}

func (c *uploadLogCapture) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.raw.Write(p)
	if start := bytes.Index(p, []byte("FILE_TRANSFER_UPLOAD_DIAGNOSTIC ")); start >= 0 {
		var line map[string]any
		if json.Unmarshal(p[start+len("FILE_TRANSFER_UPLOAD_DIAGNOSTIC "):], &line) == nil {
			c.lines = append(c.lines, line)
		}
	}
	select {
	case c.wake <- struct{}{}:
	default:
	}
	return len(p), nil
}

func (c *uploadLogCapture) snapshot() []map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]map[string]any(nil), c.lines...)
}

func uploadCapture(t *testing.T) *uploadLogCapture {
	t.Helper()
	c := &uploadLogCapture{wake: make(chan struct{}, 1)}
	previous := log.Writer()
	log.SetOutput(c)
	t.Cleanup(func() { log.SetOutput(previous) })
	return c
}

func uploadWait(t *testing.T, c *uploadLogCapture, stage string) map[string]any {
	t.Helper()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	for {
		for _, line := range c.snapshot() {
			if line["stage"] == stage {
				return line
			}
		}
		select {
		case <-c.wake:
		case <-deadline.C:
			t.Fatalf("upload did not reach %s", stage)
		}
	}
}

func uploadFixture(t *testing.T) (*Store, string, []byte) {
	t.Helper()
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	body := bytes.Repeat([]byte{0x5a}, 1<<20)
	sum := sha256.Sum256(body)
	value, err := s.Create(UploadInput{FolderID: 1, Name: "safe.bin", Size: int64(len(body)), SHA256: hex.EncodeToString(sum[:]), ChunkSize: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	return s, value.(Upload).ID, body
}

func uploadHTTP(t *testing.T, client *http.Client, url string, body io.Reader) (int, map[string]any) {
	t.Helper()
	r, err := http.NewRequest(http.MethodPut, url, body)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var result map[string]any
	if err = json.NewDecoder(response.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	return response.StatusCode, result
}

func TestUploadObservationBodyAndCommitPhases(t *testing.T) {
	t.Run("slow real request body", func(t *testing.T) {
		capture := uploadCapture(t)
		store, id, body := uploadFixture(t)
		server := httptest.NewServer(Routes(store))
		t.Cleanup(server.Close)
		input, output := io.Pipe()
		release := make(chan struct{})
		var once sync.Once
		unblock := func() { once.Do(func() { close(release) }) }
		t.Cleanup(func() { unblock(); input.Close(); output.Close() })
		type answer struct {
			response *http.Response
			err      error
		}
		done := make(chan answer, 1)
		r, _ := http.NewRequest(http.MethodPut, server.URL+"/api/uploads/"+id+"/chunks/0", input)
		r.ContentLength = int64(len(body))
		go func() { response, err := server.Client().Do(r); done <- answer{response, err} }()
		written := make(chan struct{})
		go func() {
			defer close(written)
			output.Write(body[:65536])
			<-release
			output.Write(body[65536:])
			output.Close()
		}()
		uploadWait(t, capture, "STAGING_FIRST_WRITE_END")
		for _, line := range capture.snapshot() {
			if line["stage"] == "BODY_COPY_END" {
				t.Fatal("copy finished while real body was withheld")
			}
		}
		time.Sleep(120 * time.Millisecond)
		unblock()
		select {
		case answer := <-done:
			if answer.err != nil {
				t.Fatal(answer.err)
			}
			data, err := io.ReadAll(answer.response.Body)
			answer.response.Body.Close()
			if err != nil || answer.response.StatusCode != 200 {
				t.Fatalf("normal upload changed: status=%d err=%v", answer.response.StatusCode, err)
			}
			var result map[string]any
			json.Unmarshal(data, &result)
			if result["duplicate"] != false {
				t.Fatal(result)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("slow upload did not complete")
		}
		<-written
		copyEnd := uploadWait(t, capture, "BODY_COPY_END")
		if copyEnd["copiedBytes"] != float64(len(body)) || copyEnd["sourceReadCallMillis"].(float64) < 100 || copyEnd["stagingWrittenBytes"] != float64(len(body)) {
			t.Fatal(copyEnd)
		}
		uploadWait(t, capture, "HTTP_RESPONSE_END")
		t.Logf("LOCAL_UPLOAD_REAL_BODY_STALL_VERIFIED sourceReadCallMillis=%v stagingWriteCallMillis=%v copiedBytes=%v", copyEnd["sourceReadCallMillis"], copyEnd["stagingWriteCallMillis"], copyEnd["copiedBytes"])
	})
	t.Run("body complete before existing processing fault gate", func(t *testing.T) {
		capture := uploadCapture(t)
		store, id, body := uploadFixture(t)
		faultDir := t.TempDir()
		t.Setenv("SAMPLE_FAULT_POINT", "chunk-written")
		t.Setenv("SAMPLE_FAULT_DIR", faultDir)
		server := httptest.NewServer(Routes(store))
		t.Cleanup(server.Close)
		release := func() { os.WriteFile(filepath.Join(faultDir, "chunk-written.release"), nil, 0600) }
		t.Cleanup(release)
		done := make(chan int, 1)
		go func() {
			r, _ := http.NewRequest(http.MethodPut, server.URL+"/api/uploads/"+id+"/chunks/0", bytes.NewReader(body))
			response, err := server.Client().Do(r)
			if err != nil {
				done <- 0
				return
			}
			io.Copy(io.Discard, response.Body)
			response.Body.Close()
			done <- response.StatusCode
		}()
		uploadWait(t, capture, "FAULT_GATE_BEGIN")
		copyEnd := uploadWait(t, capture, "BODY_COPY_END")
		uploadWait(t, capture, "SYNC_END")
		if copyEnd["copiedBytes"] != float64(len(body)) {
			t.Fatal(copyEnd)
		}
		for _, line := range capture.snapshot() {
			if line["stage"] == "METADATA_COMMIT_BEGIN" {
				t.Fatal("metadata committed before existing fault gate release")
			}
		}
		time.Sleep(120 * time.Millisecond)
		release()
		select {
		case status := <-done:
			if status != 200 {
				t.Fatal(status)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("processing gate did not finish")
		}
		uploadWait(t, capture, "METADATA_COMMIT_END")
		t.Logf("LOCAL_UPLOAD_POST_BODY_PROCESSING_STALL_VERIFIED copiedBytes=%v sourceReadCallMillis=%v stagingWriteCallMillis=%v", copyEnd["copiedBytes"], copyEnd["sourceReadCallMillis"], copyEnd["stagingWriteCallMillis"])
	})
}

type uploadBrokenWriter struct {
	header http.Header
	status int
}

func (w *uploadBrokenWriter) Header() http.Header    { return w.header }
func (w *uploadBrokenWriter) WriteHeader(status int) { w.status = status }
func (w *uploadBrokenWriter) Write([]byte) (int, error) {
	return 0, errors.New("SECRET_ERROR_MESSAGE_MUST_NOT_APPEAR")
}

func TestUploadObservationIdentityDuplicateAndSafeErrors(t *testing.T) {
	capture := uploadCapture(t)
	store, id, body := uploadFixture(t)
	server := httptest.NewServer(Routes(store))
	t.Cleanup(server.Close)
	url := server.URL + "/api/uploads/" + id + "/chunks/0"
	status, result := uploadHTTP(t, server.Client(), url, bytes.NewReader(body))
	if status != 200 || result["duplicate"] != false {
		t.Fatal(status, result)
	}
	status, duplicate := uploadHTTP(t, server.Client(), url, bytes.NewReader(body))
	if status != 200 || duplicate["duplicate"] != true || duplicate["sha256"] != result["sha256"] {
		t.Fatal(status, duplicate)
	}
	status, _ = uploadHTTP(t, server.Client(), url, bytes.NewReader(bytes.Repeat([]byte{1}, len(body))))
	if status != 409 {
		t.Fatal(status)
	}
	before := len(capture.snapshot())
	status, _ = uploadHTTP(t, server.Client(), server.URL+"/api/uploads/invalid_identity/chunks/0", bytes.NewReader(body))
	if status != 404 || len(capture.snapshot()) != before {
		t.Fatal("invalid identity acquired diagnostic identity", status)
	}
	var count int
	if err := store.db.QueryRow("SELECT count(*) FROM chunks WHERE upload_id=?", id).Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
	actual, err := os.ReadFile(filepath.Join(store.dir, "chunks", id, "0"))
	if err != nil || !bytes.Equal(actual, body) {
		t.Fatal("committed bytes changed", err)
	}
	w := &uploadBrokenWriter{header: make(http.Header)}
	replyObserved(w, 200, map[string]bool{"ok": true}, newChunkObservation(context.Background(), id, 0))
	if w.status != 200 {
		t.Fatal(w.status)
	}
	groups := map[string]int{}
	foundResponseError := false
	allowed := strings.Fields("uploadId chunkIndex requestId sequence stage startedAtUtc elapsedMillis expectedBodyBytes copiedBytes sourceReadBytes stagingWrittenBytes sourceReadCalls stagingWriteCalls sourceReadCallMillis stagingWriteCallMillis firstReadElapsedMillis lastReadElapsedMillis contextCancelled errorType timeout responseStatus timingMeaning")
	for _, line := range capture.snapshot() {
		key := line["requestId"].(string)
		groups[key]++
		if groups[key] > 32 {
			t.Fatal("unbounded phase logs")
		}
		for key := range line {
			present := false
			for _, name := range allowed {
				present = present || name == key
			}
			if !present {
				t.Fatal("unapproved field", key)
			}
		}
		if line["stage"] == "HTTP_RESPONSE_END" && line["errorType"] != "<nil>" {
			foundResponseError = true
		}
	}
	if !foundResponseError || strings.Contains(capture.raw.String(), "SECRET_ERROR_MESSAGE_MUST_NOT_APPEAR") {
		t.Fatal("unsafe or absent response error observation")
	}
	if _, ok := io.LimitReader(bytes.NewReader(body), int64(len(body))).(io.WriterTo); ok {
		t.Fatal("original copy fast path assumption changed")
	}
	t.Logf("LOCAL_UPLOAD_NORMAL_DUPLICATE_CONFLICT_IDENTITY_SAFE_ERRORS_VERIFIED requests=%d committedChunks=%d", len(groups), count)
}

func TestUploadObservationCancellationThroughNode(t *testing.T) {
	capture := uploadCapture(t)
	store, id, _ := uploadFixture(t)
	server := httptest.NewServer(Routes(store))
	t.Cleanup(server.Close)
	node := os.Getenv("FILE_TRANSFER_NODE_TEST_BINARY")
	if node == "" {
		var err error
		node, err = exec.LookPath("node")
		if err != nil {
			t.Fatal(err)
		}
	}
	script, err := filepath.Abs("../../../web/test/upload-proxy.mjs")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, node, "--test", "--test-isolation=none", "--test-name-pattern=upload cancellation reaches actual Go handler", script)
	cmd.Env = append(os.Environ(), "UPLOAD_TEST_GO_ORIGIN="+server.URL, "UPLOAD_TEST_GO_ID="+id)
	input, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	cmd.Stdout, cmd.Stderr = &output, &output
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	waited := false
	t.Cleanup(func() {
		cancel()
		input.Close()
		if !waited {
			cmd.Wait()
		}
	})
	uploadWait(t, capture, "STAGING_FIRST_WRITE_END")
	if _, err = input.Write([]byte("cancel\n")); err != nil {
		t.Fatal(err)
	}
	input.Close()
	err = cmd.Wait()
	waited = true
	t.Logf("NODE_GO_CANCEL_CHILD_OUTPUT\n%s", output.Bytes())
	if err != nil {
		t.Fatal("Node cancellation child failed", err)
	}
	copyEnd := uploadWait(t, capture, "BODY_COPY_END")
	returned := uploadWait(t, capture, "CHUNK_RETURN")
	uploadWait(t, capture, "HTTP_RESPONSE_END")
	if copyEnd["copiedBytes"].(float64) <= 0 || copyEnd["copiedBytes"].(float64) >= 1<<20 || returned["contextCancelled"] != true {
		t.Fatal("cancellation did not reach actual Go read/context", copyEnd, returned)
	}
	var count int
	if err = store.db.QueryRow("SELECT count(*) FROM chunks WHERE upload_id=?", id).Scan(&count); err != nil || count != 0 {
		t.Fatal(count, err)
	}
	if _, err = os.Stat(filepath.Join(store.dir, "chunks", id, "0")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("cancelled upload created a committed chunk", err)
	}
	t.Logf("LOCAL_UPLOAD_NODE_TO_GO_REAL_CANCEL_VERIFIED copiedBytes=%v contextCancelled=%v committedChunks=%d", copyEnd["copiedBytes"], returned["contextCancelled"], count)
}
