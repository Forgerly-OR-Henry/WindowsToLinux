def dependency_native_apt_scope(profile, ecosystem):
    if profile['manager'] != 'APT' or profile['id'] not in ('ubuntu', 'debian'):
        dependency_reject('UNSUPPORTED_OS_PROFILE')
    prefix = 'NATIVE_BUILD_APT_'
    if ecosystem != DEPENDENCY_SCOPED_PROFILE or not re.fullmatch(prefix + r'(?:[1-9]|1[0-5])', ecosystem):
        dependency_reject('DEPENDENCY_ARGUMENT_FRAME')
    roots = dependency_packages('APT', 'NATIVE_BUILD')
    if roots != ['cmake', 'ninja-build', 'gcc', 'g++']:
        dependency_reject('PACKAGED_DEPENDENCY_TABLE')
    mask = int(ecosystem[len(prefix):])
    return [package for index, package in enumerate(roots) if mask & (1 << index)]
