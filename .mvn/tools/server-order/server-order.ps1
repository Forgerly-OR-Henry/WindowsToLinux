[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('apply', 'restore', 'test')]
    [string] $Mode,
    [string] $DataDirectory,
    [string] $BackupDirectory,
    [string] $Case,
    [string] $JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)]
    [string] $MavenRepository,
    [string] $CompiledRoot
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if ([string]::IsNullOrWhiteSpace($CompiledRoot)) { $CompiledRoot = $repositoryRoot }
$compiledPath = [IO.Path]::GetFullPath($CompiledRoot)
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javacCommand = Get-Command javac.exe -ErrorAction Stop
    $JavaHome = Split-Path (Split-Path $javacCommand.Source -Parent) -Parent
}
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
foreach ($executable in @($java, $javac)) {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'A complete JDK 21 or newer is required.' }
}

if ($Mode -ne 'test') {
    if (-not [IO.Path]::IsPathFullyQualified($DataDirectory) -or
        -not [IO.Path]::IsPathFullyQualified($BackupDirectory)) {
        throw 'DataDirectory and BackupDirectory must be explicit absolute paths.'
    }
    $privateBackup = [IO.Path]::GetFullPath($BackupDirectory)
    if ($privateBackup.Equals($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $privateBackup.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The sensitive paired backup must be outside the source checkout and evidence directories.'
    }
}

[xml] $projectPom = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'pom.xml')
$h2Version = [string] $projectPom.project.properties.'h2.version'
$jacksonVersion = [string] $projectPom.project.properties.'jackson.version'
$jnaVersion = [string] $projectPom.project.properties.'jna.version'
$classPathParts = @(
    (Join-Path $repositoryRoot 'src/app/db/src/main/resources'),
    (Join-Path $compiledPath 'src/app/db/target/classes'),
    (Join-Path $compiledPath 'src/app/windows/target/classes'),
    (Join-Path $compiledPath 'src/appapi/target/classes'),
    (Join-Path $MavenRepository "com/h2database/h2/$h2Version/h2-$h2Version.jar"),
    (Join-Path $MavenRepository "com/fasterxml/jackson/core/jackson-databind/$jacksonVersion/jackson-databind-$jacksonVersion.jar"),
    (Join-Path $MavenRepository "com/fasterxml/jackson/core/jackson-core/$jacksonVersion/jackson-core-$jacksonVersion.jar"),
    (Join-Path $MavenRepository "com/fasterxml/jackson/core/jackson-annotations/$jacksonVersion/jackson-annotations-$jacksonVersion.jar"),
    (Join-Path $MavenRepository "net/java/dev/jna/jna/$jnaVersion/jna-$jnaVersion.jar"),
    (Join-Path $MavenRepository "net/java/dev/jna/jna-platform/$jnaVersion/jna-platform-$jnaVersion.jar")
)
foreach ($part in $classPathParts) {
    if (-not (Test-Path -LiteralPath $part)) { throw "Required compiled artifact or pinned dependency is absent: $part" }
}
foreach ($class in @(
    'src/app/db/target/classes/gold/debug/windowstolinux/app/db/init/PlatformSchemaList.class',
    'src/app/windows/target/classes/gold/debug/windowstolinux/app/windows/security/CurrentUserProtection.class',
    'src/appapi/target/classes/gold/debug/windowstolinux/appapi/task/result/Problem.class'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $compiledPath $class) -PathType Leaf)) {
        throw 'Compile the directly required platform modules before running this development tool.'
    }
}

$buildDirectory = Join-Path ([IO.Path]::GetTempPath()) ('server-order-tool-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($buildDirectory) | Out-Null
$exitCode = 1
try {
    $classPath = $classPathParts -join [IO.Path]::PathSeparator
    $sources = @('ServerOrderFiles.java', 'ServerOrderData.java', 'ServerOrderMaintenance.java', 'ServerOrderAtomicMove.java') |
        ForEach-Object { Join-Path $PSScriptRoot $_ }
    if ($Mode -eq 'test') { $sources += Join-Path $PSScriptRoot 'ServerOrderMaintenanceTest.java' }
    & $javac '--release' '21' '-encoding' 'UTF-8' '-cp' $classPath '-d' $buildDirectory @sources
    if ($LASTEXITCODE -ne 0) { throw 'The one-time maintenance tool did not compile.' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'platform-v1-before-server-order.sql') `
        -Destination (Join-Path $buildDirectory 'server-order-before.sql')
    $runtimeClassPath = $buildDirectory + [IO.Path]::PathSeparator + $classPath
    if ($Mode -eq 'test') {
        $testArguments = @('-cp', $runtimeClassPath, 'gold.debug.windowstolinux.app.db.init.ServerOrderMaintenanceTest')
        if (-not [string]::IsNullOrWhiteSpace($Case)) { $testArguments += $Case }
        & $java @testArguments
    } else {
        & $java '-cp' $runtimeClassPath 'gold.debug.windowstolinux.app.db.init.ServerOrderMaintenance' `
            $Mode $DataDirectory $BackupDirectory
    }
    $exitCode = $LASTEXITCODE
} finally {
    # This exact freshly created directory only contains compiled classes and the non-secret old DDL.
    $resolvedBuild = [IO.Path]::GetFullPath($buildDirectory)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolvedBuild.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($resolvedBuild)).StartsWith('server-order-tool-')) {
        throw 'Refuse cleanup outside the owned temporary build directory.'
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}
exit $exitCode
