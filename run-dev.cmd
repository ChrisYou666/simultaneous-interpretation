@echo off
chcp 65001 >nul
setlocal

:: ================================================
:: 同声传译系统 - 一键启动脚本
:: ================================================
:: 前置条件：
::   1. JDK 21+ (需在 PATH)
::   2. Maven 3.8+ (需在 PATH)
::   3. Node.js 18+ (需在 PATH)
::   4. .env 文件（自动读取 DASHSCOPE_API_KEY）
:: ================================================

echo [INFO] 同声传译系统启动脚本
echo.

:: 尝试从 .env 加载环境变量
set "FOUND_ENV="
if exist "%~dp0.env" (
    echo [INFO] 从 .env 加载环境变量...
    for /f "usebackq tokens=1,* delims==" %%a in ("%~dp0.env") do (
        set "key=%%a"
        set "val=%%b"
        :: 跳过注释和空行
        echo !key! | findstr /b ";#" >nul
        if errorlevel 1 (
            if not "!key!"=="" (
                endlocal
                set "%%a=%%b"
                setlocal
                set "FOUND_ENV=1"
            )
        )
    )
)

:: 检查 DASHSCOPE_API_KEY
if "%DASHSCOPE_API_KEY%"=="" (
    echo [ERROR] DASHSCOPE_API_KEY 未配置
    echo.
    echo 请创建 .env 文件，内容如下：
    echo   DASHSCOPE_API_KEY=你的百炼密钥
    echo.
    pause
    exit /b 1
)

echo [OK] DASHSCOPE_API_KEY 已配置
echo.

:: 检查 Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未找到 Java，请安装 JDK 21+ 并加入 PATH
    pause
    exit /b 1
)
echo [OK] JDK 已就绪

:: 检查 Maven
mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未找到 Maven，请安装 Maven 3.8+ 并加入 PATH
    pause
    exit /b 1
)
echo [OK] Maven 已就绪

:: 检查 Node
node --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未找到 Node.js，请安装 Node.js 18+ 并加入 PATH
    pause
    exit /b 1
)
for /f "delims=" %%v in ('node --version') do set NODE_VER=%%v
echo [OK] Node.js !NODE_VER! 已就绪

echo.
echo ================================================
echo   正在启动后端 Spring Boot ...
echo ================================================

:: 将环境变量写入临时文件，供新窗口加载
set "TMP_BAT=%TEMP%\si-startup-%RANDOM%.bat"
(
    echo @echo off
    echo set "DASHSCOPE_API_KEY=%DASHSCOPE_API_KEY%"
    echo cd /d "%~dp0backend-java"
    echo mvn spring-boot:run -Plocal
) > "%TMP_BAT%"

:: 启动后端（在新窗口，窗口标题固定用于关闭）
start "si-backend" cmd /k ""%TMP_BAT%""

echo [1/2] 后端窗口已打开，等待启动（最多 60 秒）...

:: 等待后端就绪
set /a waited=0
:wait_backend
ping -n 6 127.0.0.1 >nul 2>&1
set /a waited+=5
curl -s -o nul -w "%%{http_code}" http://localhost:8100/api/health --max-time 2 >nul 2>&1
if errorlevel 1 (
    if %waited% LSS 60 goto wait_backend
    echo [WARN] 后端可能仍在启动，请查看 si-backend 窗口
) else (
    echo [OK] 后端已就绪: http://localhost:8100
)

:: 清理临时文件
del "%TMP_BAT%" >nul 2>&1

echo.
echo ================================================
echo   正在启动前端 Vite ...
echo ================================================

:: 启动前端（在新窗口）
start "si-frontend" cmd /k "cd /d "%~dp0" && npm run dev"

echo [2/2] 前端窗口已打开

echo.
echo ================================================
echo   启动完成！
echo ================================================
echo   前端: http://localhost:5174
echo   后端: http://localhost:8100
echo.
echo   停止服务: 关闭 si-backend 和 si-frontend 窗口
echo.
