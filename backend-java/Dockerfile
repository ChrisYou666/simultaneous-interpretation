# ============================================
# 同声传译系统 - 后端 Docker 多阶段构建
# ============================================
# 阶段1：Maven构建
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# 复制pom.xml先下载依赖（利用缓存）
COPY backend-java/pom.xml ./
RUN mvn dependency:go-offline -B

# 复制源代码并构建
COPY backend-java/src ./src
RUN mvn clean package -DskipTests

# ============================================
# 阶段2：运行时镜像
# ============================================
# 使用 ubuntu-jre-21 作为基础镜像
FROM ubuntu:noble AS runtime

# 安装JRE
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jre-headless \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 创建非root用户（安全加固）
RUN groupadd -g 1001 appgroup && \
    useradd -r -u 1001 -g appgroup appuser

WORKDIR /app

# 从构建阶段复制jar包
COPY --from=builder /app/target/si-backend-*.jar app.jar

# 创建logs目录并授权
RUN mkdir -p logs && chown -R appuser:appgroup /app

# 切换到非root用户
USER appuser

# 暴露端口
EXPOSE 8100

# JVM参数优化
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseZGC -XX:+ZGenerational"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
