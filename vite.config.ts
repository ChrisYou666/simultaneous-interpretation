import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";

export default defineConfig({
  plugins: [react()],
  /** 与会议助手同机并行开发时避免与 5173 冲突 */
  server: {
    port: 5174,
    /** Windows 上仅绑 [::1] 时，用 127.0.0.1 打开会连不上；监听所有地址更稳 */
    host: true,
    /** 与页面同源，避免 localhost / 127.0.0.1 混用导致 WebSocket 握手失败 */
    proxy: {
      "/api": { target: "http://localhost:8100", changeOrigin: true },
      "/ws": {
        target: "http://localhost:8100",
        ws: true,
        changeOrigin: true,
        /** http-proxy 默认对 WebSocket 消息有 1MB 限制；
         *  53KB 的二进制 WAV 帧需要放大此限制 */
        configure: (proxy) => {
          proxy.on("proxyReqWs", (proxyReq, req, socket) => {
            // 移除 http-proxy 默认的消息大小限制
            socket.setTimeout(0);
          });
        },
      },
    },
  },
});
