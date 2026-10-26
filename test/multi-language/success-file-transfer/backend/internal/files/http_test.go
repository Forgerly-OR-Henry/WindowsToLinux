package files

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const downloadTestBytes = 64 << 20
const downloadTestVersion = "0123456789abcdef0123456789abcdef"

type downloadTestListener struct{ net.Listener }

func (l downloadTestListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err == nil {
		err = c.(*net.TCPConn).SetWriteBuffer(128 << 10)
		if err != nil {
			c.Close()
		}
	}
	return c, err
}

func downloadTestFile(t *testing.T) (string, string) {
	t.Helper()
	f, err := os.CreateTemp(t.TempDir(), "download-64m-*")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	block := make([]byte, 32<<10)
	for i := range block {
		block[i] = byte(i*31 + 17)
	}
	hash := sha256.New()
	for n := 0; n < downloadTestBytes; n += len(block) {
		if _, err = io.MultiWriter(f, hash).Write(block); err != nil {
			t.Fatal(err)
		}
	}
	return f.Name(), hex.EncodeToString(hash.Sum(nil))
}

func downloadTestServer(t *testing.T, path, digest string, window time.Duration) (string, *http.Client, <-chan struct{}, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	handled := make(chan struct{}, 8)
	var handlers sync.WaitGroup
	server := &http.Server{WriteTimeout: window, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlers.Add(1)
		defer handlers.Done()
		defer func() { handled <- struct{}{} }()
		f, err := os.Open(path)
		if err != nil {
			http.Error(w, "test object missing", 500)
			return
		}
		defer f.Close()
		w.Header().Set("X-Sample-Protocol", "2")
		observed := &downloadTestWriter{ResponseWriter: w}
		serveDownload(observed, r, f, "download.bin", digest, downloadTestVersion)
		if r.Method == http.MethodGet && r.Header.Get("Range") == "" && r.Header.Get("If-Modified-Since") == "" {
			t.Logf("LOCAL_DOWNLOAD_HANDLER_RETURNED acceptedBodyBytes=%d writeErrorType=%T", observed.written, observed.err)
		}
	})}
	done := make(chan error, 1)
	go func() { done <- server.Serve(downloadTestListener{ln}) }()
	transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		c, err := (&net.Dialer{}).DialContext(ctx, network, address)
		if err == nil {
			err = c.(*net.TCPConn).SetReadBuffer(128 << 10)
			if err != nil {
				c.Close()
			}
		}
		return c, err
	}}
	closeServer := func() {
		server.Close()
		transport.CloseIdleConnections()
		<-done
		handlers.Wait()
	}
	return "http://" + ln.Addr().String() + "/download", &http.Client{Transport: transport, Timeout: 20 * time.Second}, handled, closeServer
}

type downloadTestWriter struct {
	http.ResponseWriter
	written int64
	err     error
}

func (w *downloadTestWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *downloadTestWriter) Write(p []byte) (int, error) {
	n, err := w.ResponseWriter.Write(p)
	w.written += int64(n)
	if err != nil {
		w.err = err
	}
	return n, err
}

func TestDownloadProgressPreserves64MiBAndHTTPMetadata(t *testing.T) {
	path, digest := downloadTestFile(t)
	const window = time.Second
	url, client, _, closeServer := downloadTestServer(t, path, digest, window)
	defer closeServer()

	t.Run("progress-beyond-original-deadline", func(t *testing.T) {
		started := time.Now()
		response, err := client.Get(url)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != 200 || response.ContentLength != downloadTestBytes || response.Header.Get("X-Content-SHA256") != digest || response.Header.Get("X-Sample-Protocol") != "2" {
			t.Fatal("download status, length or digest headers changed")
		}
		hash := sha256.New()
		var received int64
		block := make([]byte, 32<<10)
		for {
			n, err := response.Body.Read(block)
			received += int64(n)
			hash.Write(block[:n])
			if n > 0 {
				time.Sleep(time.Millisecond)
			}
			if err != nil {
				if err != io.EOF {
					t.Fatalf("truncated download rejected: bytes=%d errorType=%T error=%v", received, err, err)
				}
				break
			}
		}
		elapsed := time.Since(started)
		if received != downloadTestBytes || hex.EncodeToString(hash.Sum(nil)) != digest || elapsed <= 2*window {
			t.Fatalf("full download failed: bytes=%d elapsed=%s", received, elapsed)
		}
		t.Logf("LOCAL_DOWNLOAD_PROGRESS_VERIFIED bytes=%d originalServerWriteWindowMillis=%d elapsedMillis=%d sha256=%s", received, window.Milliseconds(), elapsed.Milliseconds(), digest)
	})

	t.Run("head", func(t *testing.T) {
		request, _ := http.NewRequest(http.MethodHead, url, nil)
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || len(body) != 0 || response.StatusCode != 200 || response.ContentLength != downloadTestBytes || response.Header.Get("X-Content-SHA256") != digest || !strings.HasPrefix(response.Header.Get("Content-Disposition"), "attachment;") {
			t.Fatal("HEAD metadata or empty body changed")
		}
	})

	t.Run("range", func(t *testing.T) {
		request, _ := http.NewRequest(http.MethodGet, url, nil)
		request.Header.Set("Range", "bytes=17-65552")
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		f, openErr := os.Open(path)
		if openErr != nil {
			t.Fatal(openErr)
		}
		defer f.Close()
		want := make([]byte, 65536)
		_, readErr := f.ReadAt(want, 17)
		if err != nil || readErr != nil || response.StatusCode != 206 || response.ContentLength != int64(len(want)) || response.Header.Get("Content-Range") != fmt.Sprintf("bytes 17-65552/%d", downloadTestBytes) || !bytes.Equal(body, want) {
			t.Fatalf("Range mismatch: status=%d declaredBytes=%d actualBytes=%d contentRange=%q readErrorType=%T sourceReadErrorType=%T bytesMatch=%t", response.StatusCode, response.ContentLength, len(body), response.Header.Get("Content-Range"), err, readErr, bytes.Equal(body, want))
		}
	})

	t.Run("not-modified", func(t *testing.T) {
		stat, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		request, _ := http.NewRequest(http.MethodGet, url, nil)
		request.Header.Set("If-Modified-Since", stat.ModTime().UTC().Format(http.TimeFormat))
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || response.StatusCode != 304 || len(body) != 0 {
			t.Fatal("conditional request no longer returns 304 with no body")
		}
	})
}

type downloadTestLog struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (b *downloadTestLog) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.Write(p)
}

func (b *downloadTestLog) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.String()
}

func TestDownloadClientCancellationEndsOwnedStreamAndReportsWriteFailure(t *testing.T) {
	path, digest := downloadTestFile(t)
	var output downloadTestLog
	old := log.Writer()
	log.SetOutput(&output)
	defer log.SetOutput(old)
	const window = 200 * time.Millisecond
	url, client, handled, closeServer := downloadTestServer(t, path, digest, window)
	defer closeServer()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	time.Sleep(3 * window)
	select {
	case <-handled:
		t.Fatal("download ended before client cancellation")
	default:
	}
	cancel()
	response.Body.Close()
	select {
	case <-handled:
	case <-time.After(2 * time.Second):
		t.Fatal("download handler did not finish after client cancellation")
	}
	line := output.String()
	if !strings.Contains(line, "FILE_TRANSFER_DOWNLOAD_WRITE_FAILED version="+downloadTestVersion) || !strings.Contains(line, "objectBytes="+strconv.Itoa(downloadTestBytes)) || !strings.Contains(line, "declaredBodyBytes="+strconv.Itoa(downloadTestBytes)) || !strings.Contains(line, "acceptedBodyBytes=") || !strings.Contains(line, "timeout=false") {
		t.Fatalf("client cancellation did not retain safe evidence: %s", line)
	}
	t.Logf("LOCAL_DOWNLOAD_CLIENT_CANCEL_VERIFIED originalServerWriteWindowMillis=%d", window.Milliseconds())
}
