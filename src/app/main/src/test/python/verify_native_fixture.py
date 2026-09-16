"""Compile C/C++ fixture business units and test routing without the POSIX transport.

This is a portable component check, not a CMake/Linux deployment verification.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

C_MAIN = '''#include "include/router.h"
#include <stdio.h>
int main(int argc, char **argv) {
    Configuration configuration; Response response;
    if (!configuration_load(&configuration) || argc != 2) return 2;
    route_request(&configuration, argv[1], &response);
    printf("%d\\n%s", response.status, response.body);
    return 0;
}
'''
CPP_MAIN = '''#include "include/router.hpp"
#include <iostream>
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    auto response = route_request(Configuration::load(), argv[1]);
    std::cout << response.status << "\\n" << response.body;
}
'''


def verify(source, work_parent, compiler, cpp):
    with tempfile.TemporaryDirectory(prefix='native-fixture-', dir=work_parent) as temporary:
        work = Path(temporary) / 'fixture'
        shutil.copytree(source, work)
        suffix = '.cpp' if cpp else '.c'
        (work / ('probe' + suffix)).write_text(CPP_MAIN if cpp else C_MAIN, encoding='utf-8')
        executable = work / ('probe.exe' if os.name == 'nt' else 'probe')
        units = ['probe' + suffix, *['src/' + name + suffix for name in ['config', 'summary', 'router']]]
        command = [compiler, '-std=c++20' if cpp else '-std=c11', '-Wall', '-Wextra', '-Werror',
                   *units, '-o', str(executable)]
        subprocess.run(command, cwd=work, check=True, capture_output=True, timeout=60)
        environment = dict(os.environ, PORT='18765', FIXTURE_LABEL='多文件服务-中文')

        def request(path):
            result = subprocess.run([str(executable), path], cwd=work, env=environment,
                                    capture_output=True, check=True, timeout=5)
            status, body = result.stdout.decode('utf-8').split('\n', 1)
            return int(status), body

        scenario = Path(source).name
        root_status, body = request('/')
        if scenario == 'failure-health-rollback':
            assert root_status == 503 and request('/api/summary?values=2,3,5')[0] == 503
        else:
            assert root_status == 200
            if scenario == 'success-runtime-config':
                assert body == environment['FIXTURE_LABEL']
                environment.pop('FIXTURE_LABEL')
                assert request('/')[1] == 'runtime-config-default'
            for query, values in [('', [1, 2, 3]), ('?values=2%2C3%2C5', [2, 3, 5]),
                                  ('?values=0,10000', [0, 10000]),
                                  ('?values=' + ','.join(['10000'] * 20), [10000] * 20)]:
                status, body = request('/api/summary' + query)
                assert status == 200 and json.loads(body) == {'status': 'ok', 'items': values, 'total': sum(values)}
            for invalid in ['', '-1', '1.5', 'abc', '10001', '1,,2', '1,', '9999999999', ','.join(['1'] * 21)]:
                assert request('/api/summary?values=' + invalid)[0] == 400, invalid
        header = work / 'include' / ('summary.hpp' if cpp else 'summary.h')
        header.unlink()
        broken = subprocess.run(command, cwd=work, capture_output=True, timeout=60)
        assert broken.returncode != 0 and header.name.encode() in broken.stderr
        shutil.copyfile(Path(source) / 'include' / header.name, header)
        broken = subprocess.run([part for part in command if part != 'src/summary' + suffix],
                                cwd=work, capture_output=True, timeout=60)
        assert broken.returncode != 0 and b'undefined reference' in broken.stderr.lower()
        return {'scenario': scenario, 'status': 'passed', 'scope': 'business units, missing header and link unit'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--work-parent', type=Path, required=True)
    parser.add_argument('--compiler', required=True)
    parser.add_argument('--cpp', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.source.resolve(), args.work_parent.resolve(), args.compiler, args.cpp)))


if __name__ == '__main__':
    main()
