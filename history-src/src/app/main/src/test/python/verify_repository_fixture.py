"""Run one built repository fixture and check its HTTP contract; always stop it.

Example: python verify_repository_fixture.py --cwd <copied-fixture> --scenario
success-json-api -- node server.js. A command argument may contain {port}.
Build/install separately with the fixture's declared package manager first.
"""

import argparse
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import time


def request(port, path, host):
    connection = http.client.HTTPConnection(host, port, timeout=3)
    try:
        connection.request('GET', path)
        response = connection.getresponse()
        body = response.read()
        length = response.getheader('Content-Length')
        if length is not None:
            assert int(length) == len(body), (path, 'incorrect UTF-8 content length')
        return response.status, response.getheader('Content-Type', ''), body.decode('utf-8')
    except (OSError, http.client.HTTPException) as error:
        raise ConnectionError(f'HTTP request failed on port {port}, path {path}: {error}') from error
    finally:
        connection.close()


def summary(port, query, expected, host):
    status, content_type, body = request(port, '/api/summary' + query, host)
    assert status == 200, (query, status, body)
    assert content_type.startswith('application/json'), content_type
    payload = json.loads(body)
    assert payload['status'] == 'ok', payload
    assert payload['items'] == expected, payload
    assert payload['total'] == sum(expected), payload


def verify(cwd, scenario, command, label, host="127.0.0.1"):
    import ipaddress

    if not ipaddress.ip_address(host).is_loopback:
        raise ValueError("verification must use a loopback address")
    with socket.socket() as listener:
        listener.bind((host, 0))
        port = listener.getsockname()[1]
    environment = dict(os.environ, PORT=str(port))
    if label is None:
        environment.pop('FIXTURE_LABEL', None)
    else:
        environment['FIXTURE_LABEL'] = label
    command = [argument.replace('{port}', str(port)).replace('{host}', host) for argument in command]
    log_path = Path(cwd) / 'fixture-probe.log'
    checks = 0
    with log_path.open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=cwd, env=environment, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None:
                    raise AssertionError(
                        'fixture exited: ' + log_path.read_text(encoding='utf-8', errors='replace')[-4000:]
                    )
                try:
                    root = request(port, '/', host)
                    break
                except (OSError, http.client.HTTPException):
                    if time.monotonic() >= deadline:
                        raise AssertionError('fixture did not become available')
                    time.sleep(0.1)
            expected_status = 503 if scenario == 'failure-health-rollback' else 200
            assert root[0] == expected_status, root
            if scenario == 'success-json-api':
                assert root[1].startswith('application/json'), root
                payload = json.loads(root[2])
                assert payload['items'] == [1, 2, 3] and payload['total'] == 6, payload
            else:
                expected = (
                    label
                    if scenario == 'success-runtime-config' and label is not None
                    else ('runtime-config-default' if scenario == 'success-runtime-config' else 'deployment-smoke-ok')
                )
                assert root[2] == expected, root
            checks += 1
            if expected_status == 503:
                assert request(port, '/api/summary?values=2,3,5', host)[0] == 503
                assert request(port, '/', host)[0] == 503
                checks += 2
            else:
                for query, items in [
                    ('', [1, 2, 3]),
                    ('?values=2,3,5', [2, 3, 5]),
                    ('?values=0,10000', [0, 10000]),
                    ('?values=2%2C3%2C5', [2, 3, 5]),
                    ('?values=' + ','.join(['10000'] * 20), [10000] * 20),
                ]:
                    summary(port, query, items, host)
                    checks += 1
                for invalid in ['', '-1', '1.5', 'abc', '10001', '1,,2', '1,', '9999999999', ','.join(['1'] * 21)]:
                    response = request(port, '/api/summary?values=' + invalid, host)
                    assert response[0] == 400, (invalid, response)
                    checks += 1
                summary(port, '?values=4,6', [4, 6], host)
                checks += 1
            assert process.poll() is None, 'service stopped after requests'
            return {'scenario': scenario, 'label': label, 'checks': checks, 'status': 'passed'}
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            assert process.poll() is not None, 'fixture process did not exit'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cwd', type=Path, required=True)
    parser.add_argument(
        '--scenario',
        choices=['success-deployment-smoke', 'success-json-api', 'success-runtime-config', 'failure-health-rollback'],
        required=True,
    )
    parser.add_argument('--host', default='127.0.0.1', help='loopback address for this machine')
    parser.add_argument('--default-label', action='store_true')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command and args.command[0] == '--' else args.command
    if not command:
        parser.error('provide a direct service executable after --')
    result = verify(
        args.cwd.resolve(), args.scenario, command, None if args.default_label else '多文件服务-中文', args.host
    )
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
