@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo [后端] 编译 Java 项目...
call mvn clean compile -DskipTests

if %ERRORLEVEL% NEQ 0 (
    echo [后端] 编译失败！
    pause
    exit /b 1
)

echo [后端] 编译成功！