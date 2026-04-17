$ErrorActionPreference = 'Stop'
$java = (Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $java) {
  Write-Error 'java not found in PATH'
  exit 1
}
if ($java -match '\\System32\\') {
  Write-Error 'Refusing to use java.exe from System32 (stub). Install JDK 17+ and add its bin to PATH.'
  exit 1
}
$javaHome = Split-Path (Split-Path $java)
if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
  Write-Error "Cannot derive JAVA_HOME from: $java"
  exit 1
}
Write-Output $javaHome
