@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo [后端] 启动 Spring Boot 服务...
echo.

:: ASR/翻译 使用原来的 API Key
set "DASHSCOPE_API_KEY=sk-44dcc39298eb4c2c89554a2f9796bb1f"
:: TTS 使用专用的 API Key
set "TUNING_TTS_API_KEY=sk-f2259f641b804b7981106869b41f1239"

:: 激活本地配置
set "SPRING_PROFILES_ACTIVE=local"

:: 启动服务
mvn spring-boot:run

pause