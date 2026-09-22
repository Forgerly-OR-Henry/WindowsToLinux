package main

import (
	"example.com/windowstolinux/fixture/internal/config"
	"example.com/windowstolinux/fixture/internal/handler"
	"log"
	"net/http"
	"time"
)

func main() {
	settings, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{Addr: "0.0.0.0:" + settings.Port, Handler: handler.New(settings), ReadHeaderTimeout: 3 * time.Second}
	log.Fatal(server.ListenAndServe())
}
