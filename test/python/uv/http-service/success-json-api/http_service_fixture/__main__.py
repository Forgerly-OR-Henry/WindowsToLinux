from http.server import ThreadingHTTPServer
from .configuration import Configuration
from .handler import handler_for

def main():
    configuration = Configuration.load()
    with ThreadingHTTPServer(('0.0.0.0', configuration.port), handler_for(configuration)) as server:
        server.serve_forever()

if __name__ == '__main__':
    main()
