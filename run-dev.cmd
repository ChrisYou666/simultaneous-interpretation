@echo off
cd /d "%~dp0backend-java"
if not exist "run-dev.cmd" (
  echo ERROR: 未找到 backend-java\run-dev.cmd
  exit /b 1
)
call run-dev.cmd %*
