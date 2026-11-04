import http.server
import json
import os
import pathlib
import socket
import sys
import threading
import time
import types
import urllib.parse

directory = pathlib.Path(sys.argv[1])
runtime = types.ModuleType('mirror_ipv6_runtime')
exec(compile((directory / 'runtime.py').read_text(), 'runtime.py', 'exec'), runtime.__dict__)
requests = []


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def finish(self):
        super().finish()
        if os.name == 'nt':
            time.sleep(.05)

    def do_GET(self):
        requests.append(self.path)
        body = b'ipv6 mirror object\n'
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Server(http.server.ThreadingHTTPServer):
    address_family = socket.AF_INET6
    daemon_threads = False


server = thread = None
try:
    try:
        server = Server(('::1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        connection = {'available': True, 'port': server.server_port}
    except OSError as error:
        connection = {'available': False, 'reason': type(error).__name__}
    temporary = directory / 'connection.next'
    temporary.write_text(json.dumps(connection))
    temporary.replace(directory / 'connection.json')
    until = time.monotonic() + 10
    while not (directory / 'policy.json').is_file() and time.monotonic() < until:
        time.sleep(.01)
    document = json.loads((directory / 'policy.json').read_text())
    policy = runtime.MirrorPolicy(document, deadline=time.monotonic() + 10)
    for point, endpoints in document['points'].items():
        for endpoint in endpoints:
            for host in endpoint['allowedHosts']:
                assert '[' not in host and ']' not in host and host.partition('%')[0] == host.partition('%')[0].lower()
            assert urllib.parse.urlsplit(endpoint['baseUrl']).hostname in endpoint['allowedHosts']
            policy.validate_url(endpoint, endpoint['baseUrl'])
            for route in endpoint['routes']:
                policy.validate_url(endpoint, route['prefix'])
                if route.get('pattern'):
                    policy.validate_url(endpoint, policy.maps(endpoint, route['origin'] + 'payload'))
            for template in endpoint.get('repoTemplates', {}).values():
                policy.validate_url(endpoint, template.replace('$releasever', '45').replace('$basearch', 'x86_64'))
    print('PARSER_VECTORS_OK')
    if connection['available']:
        endpoint = policy.endpoints('MAVEN_REPOSITORY')[0]
        original = endpoint['routes'][0]['origin'] + 'object.txt'
        body, receipt = runtime.mirror_bytes(policy, 'MAVEN_REPOSITORY', original)
        assert body == b'ipv6 mirror object\n'
        assert requests == ['/repo/object.txt']
        print('IPV6_LOOPBACK_OK')
    else:
        print('IPV6_LOOPBACK_UNAVAILABLE ' + connection['reason'])
finally:
    if server is not None:
        server.shutdown()
        server.server_close()
    if thread is not None:
        thread.join()
