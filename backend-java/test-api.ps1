# 本地后端 HTTP 自检（默认 http://127.0.0.1:8100）
param(
  [string] $BaseUrl = "http://127.0.0.1:8100"
)

$ErrorActionPreference = "Stop"

Write-Host "=== GET /api/health ===" -ForegroundColor Cyan
$h = Invoke-WebRequest -Uri "$BaseUrl/api/health" -UseBasicParsing
Write-Host "Status:" $h.StatusCode $h.Content

Write-Host "`n=== POST /api/auth/login ===" -ForegroundColor Cyan
$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method POST `
  -Body '{"username":"user","password":"user123"}' `
  -ContentType "application/json; charset=utf-8"
$login | Format-List

Write-Host "=== POST /api/ai/translate (default: Qwen compatible; 503 if no key) ===" -ForegroundColor Cyan
$token = $login.token
$headers = @{
  Authorization = "Bearer $token"
  "Content-Type" = "application/json"
}
try {
  $tr = Invoke-RestMethod -Uri "$BaseUrl/api/ai/translate" -Method POST -Headers $headers `
    -Body '{"segment":"Hello","sourceLang":"en","targetLang":"zh"}'
  $tr | ConvertTo-Json -Depth 5
} catch {
  $resp = $_.Exception.Response
  if ($resp) {
    $r = New-Object System.IO.StreamReader($resp.GetResponseStream())
    Write-Host "Status:" ([int]$resp.StatusCode) $r.ReadToEnd()
  } else {
    throw
  }
}

Write-Host "`nASR: use the web UI (Floor + Start); WebSocket not covered by this script." -ForegroundColor DarkGray
