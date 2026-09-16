        release = dict(line.split('=', 1) for line in pathlib.Path('/etc/os-release').read_text().splitlines()
                       if '=' in line and not line.lstrip().startswith('#'))
        centos = release.get('ID', '').strip().strip('\"\'') == 'centos'
        version = release.get('VERSION_ID', '').strip().strip('\"\'')
        repositories = ['--enablerepo=crb'] if centos and version in ('9', '10') else []
