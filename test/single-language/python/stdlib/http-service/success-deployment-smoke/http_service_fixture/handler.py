from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlsplit
from .service import summarize

def handler_for(configuration):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            status = configuration.status
            content_type = 'text/plain; charset=utf-8'
            body = configuration.label.encode('utf-8')
            url = urlsplit(self.path)
            if status != 503 and (url.path == '/api/summary' or configuration.mode == 'json'):
                try:
                    raw = parse_qs(url.query, keep_blank_values=True).get('values', [None])[0] if url.path == '/api/summary' else None
                    body = summarize(raw).to_json()
                    content_type = 'application/json; charset=utf-8'
                except ValueError:
                    status, body = 400, b'invalid-values'
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass
    return Handler
