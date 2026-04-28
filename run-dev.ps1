#requires -Version 5.1
<#
.SYNOPSIS
  同声传译系统 - 一键启动开发环境（前后端）。
  双击此脚本即可运行，无需手动编译。

  前置条件：
    1. JDK 21+ 在 PATH
    2. Maven 3.8+ 在 PATH
    3. Node.js 18+ 在 PATH
    4. 项目根目录有 .env 文件包含 DASHSCOPE_API_KEY
       （内容格式：DASHSCOPE_API_KEY=你的密钥）

.DESCRIPTION
  启动流程：
    1. 检查环境（Java / Maven / Node.js / API Key）
    2. 在新窗口启动后端（Spring Boot，profile=local）
    3. 等待后端就绪（最多 60 秒轮询 /api/health）
    4. 在新窗口启动前端（Vite dev server）
    5. 打开浏览器访问 http://localhost:5174

  停止：直接关闭 si-backend 和 si-frontend 两个窗口

.EXAMPLE
  .\run-dev.ps1
#>
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$BackendPort = 8100
$FrontendPort = 5174

function Get-CdpErrors($port, $sec = 5) {
    $err = @()
    try {
        $ws = New-Object System.Net.WebSockets.ClientWebSocket
        $ct = [Threading.CancellationToken]::None
        $ws.ConnectAsync((Invoke-RestMethod "http://localhost:$port/json" -TimeoutSec 3)[0].webSocketDebuggerUrl, $ct).Wait()
        '{"id":1,"method":"Runtime.enable"}','{"id":2,"method":"Log.enable"}' | % { $ws.SendAsync([ArraySegment[byte]][Text.Encoding]::UTF8.GetBytes($_), 'Text', $true, $ct).Wait() }
        $buf = [byte[]]::new(32768); $end = (Get-Date).AddSeconds($sec)
        while ((Get-Date) -lt $end -and $ws.State -eq 'Open') {
            $r = $ws.ReceiveAsync([ArraySegment[byte]]$buf, $ct)
            if ($r.Wait(500) -and $r.Result.Count -gt 0) {
                $j = [Text.Encoding]::UTF8.GetString($buf,0,$r.Result.Count) | ConvertFrom-Json -EA SilentlyContinue
                if ($j.method -match "exceptionThrown|consoleAPICalled|entryAdded" -and ($j.method -eq "Runtime.exceptionThrown" -or $j.params.type -eq "error" -or $j.params.entry.level -eq "error")) { $err += $j }
            }
        }
        $ws.CloseAsync('NormalClosure', "", $ct).Wait()
    } catch {}
    $err
}

Write-Host ""
Write-Host "╔══════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║     同声传译系统 - 开发环境一键启动          ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# ── 1. 加载 .env ─────────────────────────────────────────────────────────────
$apiKey = $null
$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*DASHSCOPE_API_KEY\s*=\s*(.+)') {
            $apiKey = $matches[1].Trim().Trim('"').Trim("'")
        }
    }
}
if (-not $apiKey) {
    Write-Host "[ERROR] 未找到 DASHSCOPE_API_KEY" -ForegroundColor Red
    Write-Host ""
    Write-Host "请在项目根目录创建 .env 文件，内容如下：" -ForegroundColor Yellow
    Write-Host "  DASHSCOPE_API_KEY=你的百炼密钥" -ForegroundColor White
    Write-Host ""
    Write-Host "（application-local.yml 中有默认值，若要使用请直接启动。）" -ForegroundColor Gray
    Read-Host "按回车退出"
    exit 1
}
Write-Host "[OK]  DASHSCOPE_API_KEY 已加载" -ForegroundColor Green

# ── 2. 检查依赖 ─────────────────────────────────────────────────────────────
$issues = 0
$javaVer = & java -version 2>&1 | Select-Object -First 1
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] 未找到 Java，请安装 JDK 21+" -ForegroundColor Red; $issues++ }
else { Write-Host "[OK]  Java: $javaVer" -ForegroundColor Green }

try { $mvnVer = & mvn -version 2>&1 | Select-Object -First 1; Write-Host "[OK]  Maven: $mvnVer" -ForegroundColor Green } catch { Write-Host "[ERROR] 未找到 Maven" -ForegroundColor Red; $issues++ }
try { $nodeVer = & node --version; Write-Host "[OK]  Node.js: v$nodeVer" -ForegroundColor Green } catch { Write-Host "[ERROR] 未找到 Node.js" -ForegroundColor Red; $issues++ }

if ($issues -gt 0) {
    Read-Host "缺少依赖，按回车退出"
    exit 1
}

Write-Host ""

# ── 3. 杀掉旧进程 ────────────────────────────────────────────────────────────
$existingBackend = Get-NetTCPConnection -LocalPort $BackendPort -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess
$existingFrontend = Get-NetTCPConnection -LocalPort $FrontendPort -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess

if ($existingBackend) {
    Write-Host "[INFO] 正在停止旧后端 (PID $existingBackend)..." -ForegroundColor Yellow
    Stop-Process -Id $existingBackend -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}
if ($existingFrontend) {
    Write-Host "[INFO] 正在停止旧前端 (PID $existingFrontend)..." -ForegroundColor Yellow
    Stop-Process -Id $existingFrontend -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

# ── 4. 启动后端 ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  [1/2] 启动后端 Spring Boot (profile=local) ..." -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan

$backendBat = @"
@echo off
chcp 65001 >nul
cd /d "$root\backend-java"
title si-backend
set "DASHSCOPE_API_KEY=$apiKey"
mvn spring-boot:run -Plocal -DskipTests
"@

$tmpBackendBat = Join-Path $env:TEMP "si-backend-$(Get-Random).bat"
Set-Content -Path $tmpBackendBat -Value $backendBat -Encoding ASCII
Start-Process cmd -ArgumentList "/k `"$tmpBackendBat`"" -WindowStyle Normal

Write-Host "[INFO] 后端窗口已启动，等待就绪（最多 60 秒）..." -ForegroundColor Yellow

$waited = 0
$backendReady = $false
while ($waited -lt 60) {
    Start-Sleep -Seconds 3
    $waited += 3
    try {
        $status = (Invoke-WebRequest -Uri "http://localhost:$BackendPort/api/health" -TimeoutSec 2 -UseBasicParsing).StatusCode
        if ($status -eq 200) {
            $backendReady = $true
            Write-Host "[OK]  后端已就绪 (http://localhost:$BackendPort)" -ForegroundColor Green
            break
        }
    } catch {}
    Write-Host "      等待中... ${waited}s" -ForegroundColor Gray
}

if (-not $backendReady) {
    Write-Host "[WARN] 后端可能仍在启动，请在 si-backend 窗口查看状态" -ForegroundColor Yellow
}

# ── 5. 启动前端 ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  [2/2] 启动前端 Vite ..." -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan

$frontendBat = @"
@echo off
chcp 65001 >nul
cd /d "$root"
title si-frontend
npm run dev
"@

$tmpFrontendBat = Join-Path $env:TEMP "si-frontend-$(Get-Random).bat"
Set-Content -Path $tmpFrontendBat -Value $frontendBat -Encoding ASCII
Start-Process cmd -ArgumentList "/k `"$tmpFrontendBat`"" -WindowStyle Normal

Write-Host "[INFO] 前端窗口已启动" -ForegroundColor Green

# ── 6. 完成 ─────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Green
Write-Host "  启动完成！" -ForegroundColor Green
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Green
Write-Host ""
Write-Host "  前端:  http://localhost:$FrontendPort" -ForegroundColor White
Write-Host "  后端:  http://localhost:$BackendPort" -ForegroundColor White
Write-Host ""
Write-Host "  停止服务: 关闭 si-backend 和 si-frontend 窗口" -ForegroundColor Gray
Write-Host ""

# ── 7. 自动打开浏览器 ───────────────────────────────────────────────────────
Start-Sleep -Seconds 3
try {
    Start-Process "http://localhost:$FrontendPort"
} catch {}

# 清理临时 bat 文件（1分钟后）
Start-Sleep -Seconds 60
Remove-Item $tmpBackendBat -ErrorAction SilentlyContinue
Remove-Item $tmpFrontendBat -ErrorAction SilentlyContinue
