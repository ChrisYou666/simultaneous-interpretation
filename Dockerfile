# ============================================
# 同声传译系统 - 前端 Docker 构建
# ============================================
# 使用Node Alpine作为构建阶段
FROM node:20-alpine AS builder

WORKDIR /app

# 复制package文件
COPY package.json package-lock.json* ./

# 安装依赖（利用缓存层）
RUN npm ci

# 复制源代码
COPY . .

# 构建生产版本
RUN npm run build

# ============================================
# 阶段2：Nginx运行时
# ============================================
FROM nginx:alpine

# 复制构建产物到Nginx目录
COPY --from=builder /app/dist /usr/share/nginx/html

# 复制Nginx配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 暴露端口
EXPOSE 80

# 启动Nginx
CMD ["nginx", "-g", "daemon off;"]
