import { getSession } from "./authSession";

export function httpToWsRoot(httpBase: string): string {
  if (httpBase.startsWith("https://")) {
    return "wss://" + httpBase.slice("https://".length);
  }
  if (httpBase.startsWith("http://")) {
    return "ws://" + httpBase.slice("http://".length);
  }
  return httpBase;
}

/**
 * 浏览器侧 WebSocket 根（无路径）。开发默认与页面同源，走 Vite `vite.config.ts` 的 `/ws` 代理，避免硬编码后端端口与 Spring 实际端口不一致。
 */
export function resolveBrowserWsRoot(): string {
  const backend = import.meta.env.VITE_API_BASE_URL?.trim();
  if (backend) {
    return httpToWsRoot(backend.replace(/\/$/, ""));
  }
  if (import.meta.env.DEV) {
    const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${window.location.host}`;
  }
  return httpToWsRoot(window.location.origin);
}

/**
 * 同传 WebSocket URL（自动检测语言，翻译到另外一种）。
 */
export function buildAsrWebSocketUrl(floor = 1): string {
  const wsRoot = resolveBrowserWsRoot();
  const session = getSession();
  const q = new URLSearchParams({ floor: String(floor) });
  if (session?.token) {
    q.set("access_token", session.token);
  }
  return `${wsRoot}/ws/asr?${q.toString()}`;
}

export const ALL_LANGS = ["zh", "id"] as const;
export type LangCode = (typeof ALL_LANGS)[number];
export const LANG_LABELS: Record<LangCode, string> = {
  zh: "中文",
  id: "🇮🇩 印尼语",
};

/**
 * 将 ASR/上游可能返回的语种字符串规范为 zh/en/id，供 UI 与翻译列对齐。
 * 若无法识别则返回 null（调用方仍可回退显示原始字符串）。
 */
/**
 * 统一 segment / translation 的序号类型（JSON 在部分环境下可能是字符串）。
 */
export function coerceSegIndex(ev: { index?: unknown }): number | null {
  const v = ev.index;
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const n = parseInt(v, 10);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

export function normalizeLangCode(raw: string | undefined | null): LangCode | null {
  if (raw == null || String(raw).trim() === "") return null;
  const r = String(raw).trim().toLowerCase();
  if (r.startsWith("zh") || r === "cn" || r === "chinese" || r === "mandarin") return "zh";
  if (r.startsWith("id") || r.includes("indonesia") || r === "in" || r.startsWith("ms") || r.includes("malay"))
    return "id";
  return null;
}

/** 切段/翻译事件上的归一源语种（优先服务端 sourceLang / lang，其次 detectedLang） */
export function canonicalSourceLangFromSegment(ev: {
  sourceLang?: string;
  lang?: string;
  detectedLang?: string;
}): LangCode {
  return normalizeLangCode(ev.sourceLang ?? ev.lang ?? ev.detectedLang) ?? "zh";
}

/** 翻译行文本对应的语种（与 translatedText 一致） */
export function langFromTranslationEvent(ev: {
  lang?: string;
  targetLang?: string;
}): LangCode | null {
  return normalizeLangCode(ev.lang ?? ev.targetLang);
}

export type AsrServerEvent =
  | {
      event: "ready";
      floor: number;
      sampleRate?: number;
      provider?: string;
      /** 当前会议已推送完所有语言音频的 seg 序号，新加入的听众应从该序号开始收听 */
      currentSeq?: number;
    }
  | {
      event: "room_joined";
      roomId: string;
      role?: string;
      hostPresent?: boolean;
    }
  | {
      event: "host_left";
      reason?: string;
    }
  | {
      event: "host_status";
      status: string;
    }
  | {
      event: "transcript";
      partial: boolean;
      text: string;
      language: string;
      confidence: number;
      floor: number;
    }
  | {
      event: "segment";
      index: number;
      /** 与 index 相同，显式序列号（便于与 translation 对齐） */
      sequence?: number;
      text: string;
      detectedLang: string;
      /** 归一三语 zh|en|id，与 text 对应，即本段 ASR 源语 */
      sourceLang?: string;
      /** 与 sourceLang 相同：本包文本的语种 */
      lang?: string;
      /** source 表示原文来自 ASR */
      textRole?: "source";
      /** ASR 识别得到的原文，固定为 true */
      isSourceText: boolean;
      language: string;
      confidence: number;
      floor: number;
      serverTs?: number;
      /** 切段时刻（毫秒时间戳），用于将同批次切出的多句合并为一段展示 */
      segmentTs?: number;
      /** 与上行 PCM 采样率一致，用于将采样索引映射到本地时间 */
      inputSampleRate?: number;
      /** 当前切段参数快照（供前端调试显示） */
      tuning?: {
        segMaxChars: number;
        segEnMaxCharsMultiplier?: number;
        segEnMaxCharsMin?: number;
        segEnMaxCharsMax?: number;
        segSoftBreakChars: number;
        segFlushTimeoutMs: number;
      };
    }
  | {
      event: "translation";
      index: number;
      sequence?: number;
      sourceText: string;
      translatedText: string;
      sourceLang: string;
      targetLang: string;
      /** 与 targetLang 一致：translatedText 的语种 */
      lang?: string;
      textRole?: "translation";
      /** LLM 翻译得到的译文，固定为 false */
      isSourceText: boolean;
      /** 目标语言，用于前端按语言合并翻译文本 */
      detectedLang: string;
      floor: number;
      serverTs?: number;
      segmentTs?: number;
    }
  | {
      event: "audio";
      index: number;
      targetLang: string;
      sourceLang: string;
      format: string;
      data: string;
      floor: number;
      serverTs?: number;
      segmentTs?: number;
    }
  | {
      event: "tts_skip";
      index: number;
      targetLang: string;
      sourceLang: string;
      floor: number;
      reason?: string;
    }
  | {
      event: "audio_end";
      index: number;
      targetLang: string;
      sourceLang: string;
      floor: number;
      segmentTs?: number;
      serverTs?: number;
    }
  | {
      /** 主持人播放进度同步（从 AsrWebSocketHandler broadcastPlayback 广播而来） */
      event: "playback_sync";
      segIdx: number;
      lang: string;
      floor?: number;
    }
  | {
      event: "pcm_end";
      index: number;
      serverTs?: number;
    }
  | {
      /** 某个 seq 的三种语言（zh/en/id）TTS 全部完成推送，前端可安全跳过缺失的 seq */
      event: "seq_completed";
      seq: number;
      serverTs?: number;
    }
  | {
      /** 服务器确认听众切换收听语言 */
      event: "listen_lang_changed";
      listenLang: string;
      serverTs?: number;
    }
  | { event: "error"; message?: string; code?: string; detail?: string };

export type AsrServerMessage = AsrServerEvent;

export function parseAsrServerEvent(raw: string): AsrServerMessage | null {
  try {
    return JSON.parse(raw) as AsrServerMessage;
  } catch {
    return null;
  }
}
