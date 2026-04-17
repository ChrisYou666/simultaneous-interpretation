@echo off
chcp 65001 >nul
set "JAVA_HOME=D:\app\jdk-21\jdk-21.0.9.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "d:\data\simultaneous-interpretation\backend-java"

echo ============================================
echo 开始编译后端 Java 代码...
echo JAVA_HOME=%JAVA_HOME%
echo ============================================

call tools\apache-maven-3.9.6\bin\mvn.cmd clean compile -DskipTests

echo ============================================
echo 编译完成
echo ============================================
pause
