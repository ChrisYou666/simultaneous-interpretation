@echo off
chcp 65001 >nul
cd /d "%~dp0"
title SI-Interpreter - Starting

echo.
echo  ============================================
echo    SI-Interpreter - Dev Environment
echo  ============================================
echo.

:: Read DASHSCOPE_API_KEY from .env
set "DASHSCOPE_API_KEY="
if not exist ".env" (
    echo [ERROR] .env not found
    echo Please create .env with:
    echo   DASHSCOPE_API_KEY=your-key-here
    echo.
    pause
    exit /b 1
)
for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    if /i "%%A"=="DASHSCOPE_API_KEY" set "DASHSCOPE_API_KEY=%%B"
)
if "%DASHSCOPE_API_KEY%"=="" (
    echo [ERROR] DASHSCOPE_API_KEY not found in .env
    echo.
    pause
    exit /b 1
)
echo [OK] DASHSCOPE_API_KEY loaded

:: Kill old processes on ports 8100 and 5174
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8100 " ^| findstr "LISTENING"') do (
    echo [INFO] Stopping old backend PID=%%p
    taskkill /F /PID %%p >nul 2>&1
    timeout /t 2 /nobreak >nul
)
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":5174 " ^| findstr "LISTENING"') do (
    echo [INFO] Stopping old frontend PID=%%p
    taskkill /F /PID %%p >nul 2>&1
)

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

:: Start backend
echo.
echo [1/2] Starting backend (compile + Spring Boot dev)...
start "si-backend" cmd /k "chcp 65001 >nul && cd /d %ROOT%\backend-java && set DASHSCOPE_API_KEY=%DASHSCOPE_API_KEY% && mvn spring-boot:run -Pdev -DskipTests"

:: Wait for backend health check (up to 90s)
echo [INFO] Waiting for backend (up to 90s)...
powershell -NoProfile -Command "$ok=$false; for($i=1;$i -le 30;$i++){ Start-Sleep 3; try{ if((iwr 'http://localhost:8100/api/health' -TimeoutSec 2 -UseBasicParsing).StatusCode -eq 200){ Write-Host '[OK] Backend ready'; $ok=$true; break } }catch{}; if(!$ok){ Write-Host ('      waiting... '+($i*3)+'s') } }; if(!$ok){ Write-Host '[WARN] Backend not ready in 90s, check si-backend window' }"

:: Start frontend
echo.
echo [2/2] Starting frontend (Vite dev)...
start "si-frontend" cmd /k "chcp 65001 >nul && cd /d %ROOT% && npm run dev"

timeout /t 5 /nobreak >nul
start http://localhost:5174

echo.
echo  ============================================
echo    Started!
echo    Frontend : http://localhost:5174
echo    Backend  : http://localhost:8100
echo    To stop  : close si-backend / si-frontend
echo  ============================================
echo.