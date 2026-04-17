@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ============================================
:: 同声传译系统 - 编译并启动前后端
:: ============================================

echo.
echo ================================================
echo   同声传译系统 - 编译并启动
echo ================================================
echo.

:: 获取项目根目录
set "PROJECT_ROOT=%~dp0"
set "PROJECT_ROOT=!PROJECT_ROOT:~0,-1!"

:: 获取脚本所在目录
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=!SCRIPT_DIR:~0,-1!"

:: 分离文件名和目录
for %%i in ("!SCRIPT_DIR!") do set "SCRIPT_NAME=%%~nxi"

:: 判断脚本位置
if "!SCRIPT_NAME!" == "dev.bat" (
    set "BACKEND_DIR=!PROJECT_ROOT!\backend-java"
    set "FRONTEND_DIR=!PROJECT_ROOT!"
) else (
    set "BACKEND_DIR=!SCRIPT_DIR!"
    set "FRONTEND_DIR=!PROJECT_ROOT!"
)

echo [1/4] 后端目录: !BACKEND_DIR!
echo [2/4] 前端目录: !FRONTEND_DIR!
echo.

:: ============================================
:: 步骤 1：编译后端
:: ============================================
echo.
echo ================================================
echo   [1/4] 编译后端 Java 项目...
echo ================================================
echo.

cd /d "!BACKEND_DIR!"
call mvn clean compile -DskipTests

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [错误] 后端编译失败！
    pause
    exit /b 1
)

echo.
echo [OK] 后端编译成功！
echo.

:: ============================================
:: 步骤 2：启动后端
:: ============================================
echo.
echo ================================================
echo   [2/4] 启动后端 Spring Boot 服务...
echo ================================================
echo.

:: 设置环境变量
set "DASHSCOPE_API_KEY=sk-44dcc39298eb4c2c89554a2f9796bb1f"
set "TUNING_TTS_API_KEY=sk-f2259f641b804b7981106869b41f1239"
set "SPRING_PROFILES_ACTIVE=local"

:: 启动后端（在后台运行）
start "SI-Backend" cmd /c "cd /d ^"!BACKEND_DIR!^" ^&^& mvn spring-boot:run"

:: 等待后端启动（检查健康检查接口）
echo 等待后端启动（端口 8100）...
set BACKEND_READY=0
for /L %%i in (1,1,30) do (
    curl -s http://localhost:8100/api/health >nul 2>&1
    if !ERRORLEVEL! EQU 0 (
        set BACKEND_READY=1
        goto :backend_ready
    )
    timeout /t 2 >nul
)
:backend_ready

if !BACKEND_READY! EQU 1 (
    echo.
    echo [OK] 后端启动成功！
) else (
    echo.
    echo [警告] 后端可能尚未完全启动，请检查日志
)

:: ============================================
:: 步骤 3：编译前端
:: ============================================
echo.
echo ================================================
echo   [3/4] 编译前端...
echo ================================================
echo.

cd /d "!FRONTEND_DIR!"

:: 检查 node_modules
if not exist "node_modules" (
    echo [提示] 首次运行或缺少依赖，执行 npm install...
    call npm install
)

call npm run build

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [错误] 前端编译失败！
    pause
    exit /b 1
)

echo.
echo [OK] 前端编译成功！
echo.

:: ============================================
:: 步骤 4：启动前端开发服务器
:: ============================================
echo.
echo ================================================
echo   [4/4] 启动前端开发服务器...
echo ================================================
echo.

:: 启动前端（在后台运行）
start "SI-Frontend" cmd /c "cd /d ^"!FRONTEND_DIR!^" ^&^& npm run dev"

echo.
echo ================================================
echo   启动完成！
echo ================================================
echo.
echo   后端: http://localhost:8100
echo   前端: http://localhost:5174
echo.
echo   注意：窗口会自动关闭，如需停止服务请关闭对应窗口
echo.

:: 延迟后关闭此窗口
timeout /t 5 >nul