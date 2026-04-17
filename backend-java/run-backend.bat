@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "DASHSCOPE_API_KEY=sk-44dcc39298eb4c2c89554a2f9796bb1f"
set "TUNING_TTS_API_KEY=sk-f2259f641b804b7981106869b41f1239"
set "SPRING_PROFILES_ACTIVE=local"

mvn spring-boot:run
