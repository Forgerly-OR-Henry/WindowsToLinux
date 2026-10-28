package files

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"testing"
	"time"
)

// Started only by the named loopback Java integration fixture, never by a regular test run.
func TestUploadDeadlineLocalPeer(t *testing.T) {
	if os.Getenv("UPLOAD_DEADLINE_LOCAL_PEER") != "1" {
		t.Skip("explicit loopback peer only")
	}
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: Routes(store), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second}
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	data, _ := json.Marshal(map[string]any{"origin": "http://" + listener.Addr().String()})
	fmt.Printf("UPLOAD_DEADLINE_GO_READY %s\n", data)
	bufio.NewScanner(os.Stdin).Scan()
	if err := server.Close(); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != http.ErrServerClosed {
		t.Fatal(err)
	}
	fmt.Println("UPLOAD_DEADLINE_GO_STOPPED")
}
