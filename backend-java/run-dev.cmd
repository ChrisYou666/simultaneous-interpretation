@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ensure-maven.ps1"
if errorlevel 1 exit /b 1

set "MAVEN_HOME=%~dp0tools\apache-maven-3.9.6"
set "PATH=%MAVEN_HOME%\bin;%PATH%"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :jdbc
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0set-java-home.ps1"') do set "JAVA_HOME=%%i"
if errorlevel 1 exit /b 1
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: JAVA_HOME invalid: %JAVA_HOME%
  exit /b 1
)
echo JAVA_HOME=%JAVA_HOME%

:jdbc
set "MYSQL_USER=root"
REM 未设置 MYSQL_PASSWORD 时 JDBC 会以空密码连接 root，多数本机会报：
REM   Access denied for user 'root'@'localhost' (using password: NO)
REM 任选其一：1) 下一行取消注释并填写密码  2) 提前 set MYSQL_PASSWORD=xxx
REM 3) 复制 src\main\resources\application-local.example.yml 为 application-local.yml 并填写密码
if not defined MYSQL_PASSWORD set "MYSQL_PASSWORD="
REM set "MYSQL_PASSWORD=在此填写本机MySQL密码"

if "%MYSQL_PASSWORD%"=="" (
  echo.
  echo NOTE: 未设置环境变量 MYSQL_PASSWORD。本脚本已启用 profile=local：
  echo   - 若已在 src\main\resources\application-local.yml 中填写 spring.datasource.password，可忽略本提示；
  echo   - 否则请在 yml 中配置密码，或执行 set MYSQL_PASSWORD=你的root密码 后再运行。
  echo   参考 application-local.example.yml
  echo.
)

if not defined SERVER_PORT set "SERVER_PORT=8100"
echo SERVER_PORT=%SERVER_PORT%
if not defined SERVER_ADDRESS set "SERVER_ADDRESS=0.0.0.0"
echo SERVER_ADDRESS=%SERVER_ADDRESS%

REM 百炼密钥：环境变量优先；若 application-local.yml 含 api-key: sk- 行则视为已配置，不再刷屏提示
REM 注意：if ^( ... ^) 块内 echo 禁止使用 ASCII 右括号 ^)，否则块会提前结束并报「is not recognized」
set "SHOW_DS_NOTE=1"
if defined DASHSCOPE_API_KEY set "SHOW_DS_NOTE=0"
if "%SHOW_DS_NOTE%"=="1" if exist "%~dp0src\main\resources\application-local.yml" (
  findstr /L /C:"api-key: sk-" "%~dp0src\main\resources\application-local.yml" >nul 2>&1 && set "SHOW_DS_NOTE=0"
)
if "%SHOW_DS_NOTE%"=="1" (
  echo.
  echo NOTE: 未检测到 DASHSCOPE_API_KEY 环境变量，且未在 application-local.yml 中发现「api-key: sk-」行。
  echo   百炼密钥用于 Fun-ASR、LiveTranslate、CosyVoice、通义试译等；请二选一：
  echo   - 在 src\main\resources\application-local.yml 配置 app.asr.dashscope.api-key 与 app.openai.api-key
  echo   - 或执行 set DASHSCOPE_API_KEY=你的密钥
  echo   模板见 application-local.example.yml
  echo.
)

call "%MAVEN_HOME%\bin\mvn.cmd" -q spring-boot:run -Dspring-boot.run.profiles=local
