$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$MavenHome = Join-Path $Root 'tools\apache-maven-3.9.6'
$MvnCmd = Join-Path $MavenHome 'bin\mvn.cmd'
if (Test-Path $MvnCmd) { exit 0 }

Write-Host '[first run] Downloading Apache Maven 3.9.6 to tools\ ...'
$Tools = Join-Path $Root 'tools'
New-Item -ItemType Directory -Force -Path $Tools | Out-Null
$Url = 'https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip'
$Zip = Join-Path $env:TEMP 'maven-3.9.6-bin.zip'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -Uri $Url -OutFile $Zip
Expand-Archive -Path $Zip -DestinationPath $Tools -Force
Remove-Item $Zip -Force

if (-not (Test-Path $MvnCmd)) {
  Write-Error 'Maven download or extract failed.'
  exit 1
}
Write-Host "OK: $MavenHome"
exit 0
