import http.server
import os

STATUS_CODE = 200
MARKER = b"phase3-live-ok"

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(STATUS_CODE)
        self.send_header("Content-Length", str(len(MARKER)))
        self.end_headers()
        self.wfile.write(MARKER)

    def log_message(self, format, *args):
        pass

http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ["PORT"])), Handler).serve_forever()
