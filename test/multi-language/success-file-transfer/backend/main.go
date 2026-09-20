package main

import (
	"gold.debug/samples/filetransfer/internal/files"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

func main() {
	dir := os.Getenv("DATA_DIR")
	if dir == "" {
		dir = "data"
	}
	host := os.Getenv("HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "18111"
	}
	store, err := files.Open(dir)
	if err != nil {
		log.Fatal(err)
	}
	defer store.Close()
	timeout := 30
	if value := os.Getenv("HTTP_TIMEOUT_SECONDS"); value != "" {
		timeout, err = strconv.Atoi(value)
		if err != nil || timeout < 1 {
			log.Fatal("HTTP_TIMEOUT_SECONDS must be positive")
		}
	}
	server := &http.Server{Addr: host + ":" + port, Handler: files.Routes(store), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: time.Duration(timeout) * time.Second, WriteTimeout: time.Duration(timeout) * time.Second}
	log.Fatal(server.ListenAndServe())
}
