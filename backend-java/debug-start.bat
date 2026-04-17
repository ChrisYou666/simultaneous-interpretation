@echo off
REM =============================================================================
REM 同声传译后端调试启动脚本
REM 在 Cursor 终端中运行此脚本，即可进行单步调试
REM =============================================================================

cd /d "%~dp0"

echo =====================================================
echo   同声传译后端 - 调试模式启动
echo =====================================================
echo.
echo   调试端口: 5005
echo   请在 IDE 中配置 Remote Debug 连接到 localhost:5005
echo.

REM 清理之前可能存在的编译文件
echo [1/3] 清理并编译项目...
call mvn clean compile -q

REM 启动 Spring Boot，开启 JDWP 调试监听 5005 端口
echo.
echo [2/3] 启动应用并等待调试器连接...
echo    提示: 在 IntelliJ/VS Code 中配置 Remote JVM Debug，端口 5005
echo    或者使用: jdb -attach 5005
echo.

REM 使用 spring-boot:run 并开启调试参数
REM suspend=y 会等待调试器连接后再启动
call mvn spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"

echo.
echo [3/3] 应用已退出
pause