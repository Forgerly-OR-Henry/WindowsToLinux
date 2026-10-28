package files

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type budgetWriter struct {
	*httptest.ResponseRecorder
	reads, writes []time.Time
}

func (w *budgetWriter) EnableFullDuplex() error           { return nil }
func (w *budgetWriter) SetReadDeadline(v time.Time) error { w.reads = append(w.reads, v); return nil }
func (w *budgetWriter) SetWriteDeadline(v time.Time) error {
	w.writes = append(w.writes, v)
	return nil
}
func testBudget(t *testing.T, p chunkPolicy) *chunkBudget {
	t.Helper()
	b, e := startChunkBudget(&budgetWriter{ResponseRecorder: httptest.NewRecorder()}, httptest.NewRequest("PUT", "/", nil), p)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(b.close)
	return b
}

type slowBudgetReader struct{ positive bool }

func (r slowBudgetReader) Read(p []byte) (int, error) {
	time.Sleep(10 * time.Millisecond)
	if r.positive {
		p[0] = 1
		return 1, nil
	}
	return 0, nil
}
func TestChunkBudgetPositiveReadsCannotRenewTotalOrZeroReadIdle(t *testing.T) {
	for _, positive := range []bool{false, true} {
		t.Run(map[bool]string{false: "zero reads cannot renew idle", true: "positive trickle cannot renew total"}[positive], func(t *testing.T) {
			b := testBudget(t, chunkPolicy{100 * time.Millisecond, 40 * time.Millisecond, time.Second})
			began := time.Now()
			n, err := io.Copy(io.Discard, chunkBudgetReader{slowBudgetReader{positive}, b})
			if !errors.Is(err, context.DeadlineExceeded) || time.Since(began) > 300*time.Millisecond {
				t.Fatal(n, err, time.Since(began))
			}
			if positive && (n == 0 || time.Since(began) < 90*time.Millisecond) {
				t.Fatal("body cap did not apply", n)
			}
			if !positive && (n != 0 || time.Since(began) > 90*time.Millisecond) {
				t.Fatal("zero read refreshed idle", n)
			}
		})
	}
	t.Run("unsupported controller refuses before effect", func(t *testing.T) {
		if _, err := startChunkBudget(httptest.NewRecorder(), httptest.NewRequest("PUT", "/", nil), uploadPolicy); err == nil {
			t.Fatal("missing deadline support accepted")
		}
	})
	t.Run("absolute deadline does not depend on cancellation scheduling", func(t *testing.T) {
		ctx := unscheduledDeadline{context.Background(), time.Now().Add(-time.Millisecond)}
		if !errors.Is(chunkContextError(ctx), context.DeadlineExceeded) {
			t.Fatal("elapsed deadline accepted before Done is scheduled")
		}
	})
}

type unscheduledDeadline struct {
	context.Context
	until time.Time
}

func (c unscheduledDeadline) Deadline() (time.Time, bool) { return c.until, true }

func TestChunkAckFailureRetainsCommittedFactWithoutReplay(t *testing.T) {
	capture := uploadCapture(t)
	s, id, body := uploadFixture(t)
	result, e := s.Chunk(context.Background(), id, 0, bytes.NewReader(body))
	if e != nil {
		t.Fatal(e)
	}
	w := &uploadBrokenWriter{header: make(http.Header)}
	replyChunk(w, 200, result, newChunkObservation(context.Background(), id, 0))
	if committed(t, s, id) != 1 {
		t.Fatal("ACK failure invented rollback")
	}
	found := false
	for _, line := range capture.snapshot() {
		if line["stage"] == "HTTP_RESPONSE_END" && line["errorType"] != "<nil>" {
			found = true
		}
	}
	if !found {
		t.Fatal("ACK write failure was not observed")
	}
	// No second Chunk/GET repair is used to turn the missing ACK into client success.
}
func committed(t *testing.T, s *Store, id string) int {
	t.Helper()
	var n int
	if e := s.db.QueryRow("SELECT count(*) FROM chunks WHERE upload_id=?", id).Scan(&n); e != nil {
		t.Fatal(e)
	}
	return n
}
func TestChunkDeadlineReturnsActualErrorBeforeBodyDrain(t *testing.T) {
	for _, mode := range []string{"idle", "trickle-total"} {
		t.Run(mode, func(t *testing.T) {
			capture := uploadCapture(t)
			s, id, _ := uploadFixture(t)
			p := chunkPolicy{180 * time.Millisecond, 70 * time.Millisecond, 300 * time.Millisecond}
			server := httptest.NewUnstartedServer(routesWithChunkPolicy(s, p))
			server.Config.ReadTimeout = 30 * time.Millisecond
			server.Config.WriteTimeout = 30 * time.Millisecond
			server.Start()
			defer server.Close()
			c, e := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
			if e != nil {
				t.Fatal(e)
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(2 * time.Second))
			io.WriteString(c, "PUT /api/uploads/"+id+"/chunks/0 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\n\r\n")
			c.Write([]byte{1})
			stop := make(chan struct{})
			written := make(chan struct{})
			go func() {
				defer close(written)
				if mode == "idle" {
					return
				}
				tick := time.NewTicker(20 * time.Millisecond)
				defer tick.Stop()
				for {
					select {
					case <-stop:
						return
					case <-tick.C:
						if _, err := c.Write([]byte{1}); err != nil {
							return
						}
					}
				}
			}()
			began := time.Now()
			response, e := http.ReadResponse(bufio.NewReader(c), &http.Request{Method: "PUT"})
			close(stop)
			<-written
			if e != nil {
				t.Fatal("error response was swallowed by stale write deadline/drain", e)
			}
			data, e := io.ReadAll(response.Body)
			response.Body.Close()
			if e != nil || response.StatusCode != 400 || !bytes.Contains(data, []byte("error")) || time.Since(began) > time.Second || committed(t, s, id) != 0 {
				t.Fatal(response.StatusCode, string(data), e)
			}
			for _, v := range capture.snapshot() {
				if v["stage"] == "SYNC_BEGIN" || v["stage"] == "METADATA_COMMIT_BEGIN" {
					t.Fatal("invalid body proceeded", v)
				}
			}
		})
	}
}
func createBudgetUpload(t *testing.T, s *Store, size, chunkSize int64, body []byte) string {
	t.Helper()
	sum := sha256.Sum256(body)
	v, e := s.Create(UploadInput{FolderID: 1, Name: "bounds.bin", Size: size, ChunkSize: chunkSize, SHA256: hex.EncodeToString(sum[:])})
	if e != nil {
		t.Fatal(e)
	}
	return v.(Upload).ID
}
func TestChunkDeadlinePreservesSizeBoundsExactBodyAndCancellation(t *testing.T) {
	for _, mode := range []string{"64KiB", "4MiB", "tail", "expected-plus-one", "short", "cancelled", "wrong-number"} {
		t.Run(mode, func(t *testing.T) {
			s, e := Open(t.TempDir())
			if e != nil {
				t.Fatal(e)
			}
			defer s.Close()
			length := 65536
			if mode == "4MiB" {
				length = 4 << 20
			}
			body := bytes.Repeat([]byte{0x5a}, length)
			size := int64(length)
			number := "0"
			if mode == "tail" {
				size += 7
				body = body[:7]
				number = "1"
			}
			id := createBudgetUpload(t, s, size, int64(length), body)
			if mode == "expected-plus-one" {
				body = append(body, 0)
			}
			if mode == "short" {
				body = body[:len(body)-1]
			}
			if mode == "cancelled" {
				if _, e = s.Cancel(id); e != nil {
					t.Fatal(e)
				}
			}
			if mode == "wrong-number" {
				number = "2"
			}
			server := httptest.NewServer(Routes(s))
			defer server.Close()
			status, ack := uploadHTTP(t, server.Client(), server.URL+"/api/uploads/"+id+"/chunks/"+number, bytes.NewReader(body))
			want := 200
			if mode == "expected-plus-one" || mode == "short" || mode == "wrong-number" {
				want = 400
			}
			if mode == "cancelled" {
				want = 409
			}
			if status != want {
				t.Fatal(status, ack)
			}
			if want == 200 {
				sum := sha256.Sum256(body)
				if ack["sha256"] != hex.EncodeToString(sum[:]) || ack["number"] != map[string]float64{"0": 0, "1": 1}[number] || committed(t, s, id) != 1 {
					t.Fatal(ack)
				}
			} else if committed(t, s, id) != 0 {
				t.Fatal("invalid body committed")
			}
		})
	}
}
func TestChunkPostDeadlineUsesOriginalParentAndCancelsGate(t *testing.T) {
	for _, mode := range []string{"fresh-post-after-body", "post-expired"} {
		t.Run(mode, func(t *testing.T) {
			s, id, body := uploadFixture(t)
			gate := t.TempDir()
			t.Setenv("SAMPLE_FAULT_POINT", "chunk-written")
			t.Setenv("SAMPLE_FAULT_DIR", gate)
			p := chunkPolicy{150 * time.Millisecond, 120 * time.Millisecond, 250 * time.Millisecond}
			if mode == "post-expired" {
				p.post = 70 * time.Millisecond
			}
			server := httptest.NewServer(routesWithChunkPolicy(s, p))
			defer server.Close()
			reader, writer := io.Pipe()
			defer reader.Close()
			defer writer.Close()
			written := make(chan struct{})
			go func() {
				defer close(written)
				writer.Write(body[:65536])
				time.Sleep(90 * time.Millisecond)
				writer.Write(body[65536:])
				writer.Close()
			}()
			released := make(chan struct{})
			go func() {
				defer close(released)
				until := time.Now().Add(time.Second)
				for time.Now().Before(until) {
					if _, e := os.Stat(filepath.Join(gate, "chunk-written.ready")); e == nil {
						if mode == "fresh-post-after-body" {
							time.Sleep(90 * time.Millisecond)
							os.WriteFile(filepath.Join(gate, "chunk-written.release"), nil, 0600)
						}
						return
					}
					time.Sleep(time.Millisecond)
				}
			}()
			status, ack := uploadHTTP(t, server.Client(), server.URL+"/api/uploads/"+id+"/chunks/0", reader)
			<-written
			<-released
			if mode == "fresh-post-after-body" {
				if status != 200 || committed(t, s, id) != 1 {
					t.Fatal("post inherited expired body", status, ack)
				}
			} else {
				os.WriteFile(filepath.Join(gate, "chunk-written.release"), nil, 0600)
				if status == 200 || committed(t, s, id) != 0 {
					t.Fatal("late gate committed", status, ack)
				}
			}
		})
	}
}
func TestChunkLateSyncAndQueuedLockCannotStartCommit(t *testing.T) {
	for _, mode := range []string{"noninterruptible-sync", "queued-lock", "cancelled-parent"} {
		t.Run(mode, func(t *testing.T) {
			s, id, body := uploadFixture(t)
			b := testBudget(t, chunkPolicy{80 * time.Millisecond, time.Second, 50 * time.Millisecond})
			ctx := context.WithValue(b.body, chunkBudgetKey{}, b)
			release := make(chan struct{})
			entered := make(chan struct{})
			done := make(chan error, 1)
			var calls atomic.Int32
			syncFile := func(f *os.File) error { calls.Add(1); close(entered); <-release; return f.Sync() }
			if mode == "queued-lock" {
				s.mu.Lock()
			}
			if mode == "cancelled-parent" {
				b.cancelBody()
			}
			go func() { _, e := s.chunk(ctx, id, 0, bytes.NewReader(body), syncFile); done <- e }()
			if mode == "noninterruptible-sync" {
				select {
				case <-entered:
				case <-time.After(time.Second):
					close(release)
					t.Fatal("sync not entered")
				}
				time.Sleep(90 * time.Millisecond)
				select {
				case e := <-done:
					close(release)
					t.Fatal("claimed hard cancellation", e)
				default:
				}
				close(release)
			} else if mode == "queued-lock" {
				time.Sleep(120 * time.Millisecond)
				s.mu.Unlock()
				close(release)
			} else {
				close(release)
			}
			select {
			case e := <-done:
				if !errors.Is(e, context.Canceled) && !errors.Is(e, context.DeadlineExceeded) {
					t.Fatal(e)
				}
			case <-time.After(time.Second):
				t.Fatal("owned worker did not exit")
			}
			if committed(t, s, id) != 0 || (mode != "noninterruptible-sync" && calls.Load() != 0) {
				t.Fatal("late/cancelled work proceeded")
			}
			entries, e := os.ReadDir(filepath.Join(s.dir, "staging"))
			if e != nil || len(entries) != 0 {
				t.Fatal("staging not cleaned", e, len(entries))
			}
		})
	}
}

func TestChunkUninterruptibleSyncHasFiniteNodeFailureAndNoLateCommit(t *testing.T) {
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
	s, _, _ := uploadFixture(t)
	id := createBudgetUpload(t, s, 65536, 65536, bytes.Repeat([]byte{0x5a}, 65536))
	entered, release, returned := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		r.SetPathValue("id", id)
		r.SetPathValue("number", "0")
		b, e := startChunkBudget(w, r, chunkPolicy{5 * time.Second, 2 * time.Second, 70 * time.Millisecond})
		if e != nil {
			t.Error(e)
			return
		}
		defer b.close()
		ctx := context.WithValue(b.body, chunkBudgetKey{}, b)
		_, e = s.chunk(ctx, id, 0, chunkBudgetReader{r.Body, b}, func(f *os.File) error { close(entered); <-release; return f.Sync() })
		returned <- e
	}))
	t.Cleanup(func() { unblock(); server.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, node, "--test", "--test-isolation=none", "--test-name-pattern=^upload deadline bounds blocked actual Go sync$", script)
	cmd.Env = append(os.Environ(), "UPLOAD_TEST_GO_ORIGIN="+server.URL, "UPLOAD_TEST_GO_ID="+id)
	output, err := cmd.CombinedOutput()
	select {
	case <-entered:
	default:
		t.Error("real Go sync not reached")
	}
	select {
	case e := <-returned:
		t.Error("uninterruptible sync claimed exited before release", e)
	default:
	}
	unblock()
	select {
	case e := <-returned:
		if !errors.Is(e, context.DeadlineExceeded) && !errors.Is(e, context.Canceled) {
			t.Error(e)
		}
	case <-time.After(time.Second):
		t.Error("released owned worker did not exit")
	}
	t.Logf("NODE_ACTUAL_SYNC_OUTPUT\n%s", output)
	if err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 1 || committed(t, s, id) != 0 {
		t.Fatal("late metadata commit or repeat", requests.Load())
	}
}
