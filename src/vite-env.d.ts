/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /** 开发环境设为 true 时直连 VITE_API_BASE_URL，不经过 Vite 代理 */
  readonly VITE_API_DIRECT?: string;
  readonly VITE_API_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
