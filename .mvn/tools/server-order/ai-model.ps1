[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('apply','restore','test')][string] $Mode,
    [string] $DataDirectory,
    [string] $BackupDirectory,
    [string] $Case,
    [string] $JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string] $MavenRepository,
    [string] $CompiledRoot
)
& (Join-Path $PSScriptRoot 'server-order.ps1') @PSBoundParameters -Operation 'ai-model'
exit $LASTEXITCODE
