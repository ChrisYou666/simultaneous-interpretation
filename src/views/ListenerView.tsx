import { useCallback, useEffect, useRef, useState } from "react";
import {
  ALL_LANGS,
  LANG_LABELS,
  coerceSegIndex,
  normalizeLangCode,
  resolveBrowserWsRoot,
  type AsrServerMessage,
  type LangCode,
} from "../lib/asrWs";

/** 同一 segmentTs 内按 index 升序取 key 列表 */
function sortedIndexKeys<T>(m: Map<number, T>): number[] {
  return [...m.keys()].sort((a, b) => a - b);
}

/** 卡片源语展示语种：优先 en/id（减少单段误判 zh 时「同源无需翻译」出现） */
function batchDisplaySourceLang(sourceByIndex: Map<number, { lang: LangCode }>): LangCode {
  const order = sortedIndexKeys(sourceByIndex);
  for (const i of order) {
    if (sourceByIndex.get(i)!.lang === "en") return "en";
  }
  for (const i of order) {
    if (sourceByIndex.get(i)!.lang === "id") return "id";
  }
  return order.length > 0 ? sourceByIndex.get(order[0]!)!.lang : "zh";
}

function joinSourceText(sourceByIndex: Map<number, { text: string }>): string {
  return sortedIndexKeys(sourceByIndex)
    .map((i) => sourceByIndex.get(i)!.text)
    .join(" ");
}

function joinTransForListen(
  sourceByIndex: Map<number, unknown>,
  bySeg: Map<number, string> | undefined,
): string | undefined {
  if (!bySeg || bySeg.size === 0) return undefined;
  const parts: string[] = [];
  for (const i of sortedIndexKeys(sourceByIndex)) {
    const t = bySeg.get(i);
    if (t) parts.push(t);
  }
  return parts.length > 0 ? parts.join(" ") : undefined;
}

// ── 数据结构：一个 segmentTs = 一个 ASR final；index = 段号 ──────────────────
type SegmentCard = {
  cardKey: number;
  segmentTs: number;
  /** ASR 段：index → 文本与归一语种 */
  sourceByIndex: Map<number, { text: string; lang: LangCode }>;
  /** 目标语 → index → 译文 */
  transByLang: Map<string, Map<number, string>>;
};

// ── 二进制帧格式：[4B segIdx][4B sentenceIdx][1B type][1B langLen][N lang][audio bytes] ──
function parseAudioFrame(buffer: ArrayBuffer): {
  segIdx: number;
  sentenceIdx: number;
  type: number;
  lang: string;
  data: Uint8Array;
} | null {
  if (buffer.byteLength < 10) return null;
  const dv = new DataView(buffer);
  const segIdx = dv.getInt32(0, false);
  const sentenceIdx = dv.getInt32(4, false);
  const type = dv.getUint8(8);
  const langLen = dv.getUint8(9);
  if (buffer.byteLength < 10 + langLen) return null;
  const lang = new TextDecoder().decode(new Uint8Array(buffer, 10, langLen));
  const data = new Uint8Array(buffer, 10 + langLen);
  return { segIdx, sentenceIdx, type, lang, data };
}

export function ListenerView() {
  // ── 状态 ────────────────────────────────────────────────────────────────
  const [liveTranscript, setLiveTranscript] = useState("");
  const [cards, setCards] = useState<SegmentCard[]>([]);
  const [listenLang, setListenLang] = useState<LangCode>("zh");
  const [playingSegIdx, setPlayingSegIdx] = useState(-1);
  const [connected, setConnected] = useState(false);
  const [roomId, setRoomId] = useState<string>("");
  const [hostPresent, setHostPresent] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [audioMuted, setAudioMuted] = useState(false);
  const [displayRate, setDisplayRate] = useState(1.0); // 用于 UI 显示

  // ── Refs ────────────────────────────────────────────────────────────────
  const wsRef = useRef<WebSocket | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const masterGainRef = useRef<GainNode | null>(null);
  const audioMutedRef = useRef(false);
  const listenLangRef = useRef<LangCode>(listenLang);
  const handleEventRef = useRef<(ev: AsrServerMessage) => void>(() => {});
  const bodyRef = useRef<HTMLDivElement>(null);

  /** AudioContext 全局播放时间轴（下一个 chunk 的调度时刻） */
  const nextScheduleRef = useRef<number>(0);
  /** 已调度过首帧的 segIdx 集合（用于 playingSegIdx 时序更新） */
  const segFirstChunkRef = useRef<Set<number>>(new Set());

  /** 动态语速控制 */
  const playbackRateRef = useRef<number>(1.0);
  /** 上次调整语速的时刻（毫秒），避免频繁调整 */
  const lastRateAdjustRef = useRef<number>(0);

  // 语速调整阈值配置
  const SPEED_UP_THRESHOLD_S = 3;   // 积压超过 3 秒才加速（收紧条件）
  const SPEED_DOWN_THRESHOLD_S = 1.5; // 积压低于 1.5 秒开始减速（更敏感）
  const MIN_RATE = 1.0;
  const MAX_RATE = 1.5;            // 限制最大倍速，避免过快（从2.0降到1.5）
  const RATE_STEP_UP = 0.008;      // 加速步长稍小
  const RATE_STEP_DOWN = 0.02;     // 减速步长更大，快速恢复
  const RATE_ADJUST_INTERVAL_MS = 500; // 每 500ms 检查一次

  // ── AudioContext 懒初始化（用户手势后创建） ──────────────────────────────
  const getAudioCtx = useCallback((): AudioContext => {
    if (audioCtxRef.current) return audioCtxRef.current;
    const AC = window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    const ctx = new AC!();
    audioCtxRef.current = ctx;
    const gain = ctx.createGain();
    gain.gain.value = audioMutedRef.current ? 0 : 1;
    gain.connect(ctx.destination);
    masterGainRef.current = gain;
    return ctx;
  }, []);

  // ── 动态调整播放速率 ──────────────────────────────────────────────────────
  const adjustPlaybackRate = useCallback(() => {
    const ctx = audioCtxRef.current;
    if (!ctx) return;

    const now = performance.now();
    // 避免过于频繁调整
    if (now - lastRateAdjustRef.current < RATE_ADJUST_INTERVAL_MS) return;
    lastRateAdjustRef.current = now;

    const currentRate = playbackRateRef.current;

    // 计算待播放时长：基于时间轴计算
    // nextScheduleRef.current 是下一个音频帧应该开始播放的时间点
    // 如果这个时间 > ctx.currentTime，说明有待播放内容
    const scheduleAhead = Math.max(0, nextScheduleRef.current - ctx.currentTime);

    // 将时间轴待播放时长转换为"原始音频时长"（考虑当前播放速率）
    // 如果加速播放，实际播放更快，所以需要更多原始内容来填满等待时间
    const rawPending = scheduleAhead * currentRate;

    // 滞后程度 = 原始待播放时长 - 当前速率下应有的时长
    // 例如：2x 速率时，scheduleAhead=10s，但实际只需要 5s 的原始内容
    const lagSeconds = rawPending - scheduleAhead;

    // 加速条件：时间轴积压 > 3 秒
    if (scheduleAhead > SPEED_UP_THRESHOLD_S && currentRate < MAX_RATE) {
      const newRate = Math.min(MAX_RATE, currentRate + RATE_STEP_UP);
      playbackRateRef.current = newRate;
      setDisplayRate(newRate);
      console.info(`[Speed] ↑ 加速: ${newRate.toFixed(2)}x (ahead=${scheduleAhead.toFixed(1)}s lag=${lagSeconds.toFixed(1)}s)`);
    }
    // 减速条件：时间轴积压 < 1.5 秒
    else if (scheduleAhead < SPEED_DOWN_THRESHOLD_S && currentRate > MIN_RATE) {
      const newRate = Math.max(MIN_RATE, currentRate - RATE_STEP_DOWN); // 更大步长快速恢复
      playbackRateRef.current = newRate;
      setDisplayRate(newRate);
      console.info(`[Speed] ↓ 减速: ${newRate.toFixed(2)}x (ahead=${scheduleAhead.toFixed(1)}s)`);
    }
  }, []);

  // 定时检查语速（每 500ms）
  useEffect(() => {
    const interval = setInterval(adjustPlaybackRate, RATE_ADJUST_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [adjustPlaybackRate]);

  // ── PCM 流式调度 ─────────────────────────────────────────────────────────
  const handleBinaryFrame = useCallback((buffer: ArrayBuffer) => {
    const frame = parseAudioFrame(buffer);
    if (!frame || frame.lang !== listenLangRef.current) return;

    if (frame.type === 0x01 && frame.data.byteLength > 0) {
      // PCM 16-bit LE → Float32 → AudioBuffer → 立即调度
      const ctx = getAudioCtx();
      void ctx.resume();

      const samples = frame.data.byteLength >> 1;
      const pcm = new DataView(frame.data.buffer, frame.data.byteOffset, frame.data.byteLength);
      const float = new Float32Array(samples);
      for (let i = 0; i < samples; i++) float[i] = pcm.getInt16(i * 2, true) / 32768;

      const audioBuf = ctx.createBuffer(1, samples, 24000);
      audioBuf.copyToChannel(float, 0);

      // 计算原始音频时长（秒），用于语速控制
      const rawDuration = samples / 24000;

      // 追加到全局时间轴末尾；若时间轴落后于当前时刻，从 currentTime + 50ms 重新开始
      const startTime = Math.max(nextScheduleRef.current, ctx.currentTime + 0.05);
      // 调整后的时长（加速播放时实际播放时间更短）
      const adjustedDuration = rawDuration / playbackRateRef.current;
      // 更新时间轴
      nextScheduleRef.current = startTime + adjustedDuration;

      const src = ctx.createBufferSource();
      src.buffer = audioBuf;
      src.playbackRate.value = playbackRateRef.current;
      src.connect(masterGainRef.current ?? ctx.destination);
      src.start(startTime);

      // 首帧：用 setTimeout 在音频实际播放时更新 playingSegIdx
      const segIdx = frame.segIdx;
      if (!segFirstChunkRef.current.has(segIdx)) {
        segFirstChunkRef.current.add(segIdx);
        const delayMs = Math.max(0, (startTime - ctx.currentTime) * 1000);
        setTimeout(() => setPlayingSegIdx(segIdx), delayMs);
      }

    } else if (frame.type === 0x03) {
      // END：在该段最后一帧播完时清除 playingSegIdx
      const ctx = audioCtxRef.current;
      if (!ctx) return;
      const segIdx = frame.segIdx;
      const endTime = nextScheduleRef.current;
      const delayMs = Math.max(0, (endTime - ctx.currentTime) * 1000);
      setTimeout(() => {
        segFirstChunkRef.current.delete(segIdx);
        setPlayingSegIdx((prev) => (prev === segIdx ? -1 : prev));
      }, delayMs);
    }
  }, [getAudioCtx]);

  // ── JSON 事件处理 ─────────────────────────────────────────────────────────
  const handleEvent = useCallback((ev: AsrServerMessage) => {
    if (ev.event === "ready") {
      setConnected(true);
      setHostPresent(true);
      setErr(null);
      return;
    }
    if (ev.event === "room_joined") {
      setConnected(true);
      setRoomId((ev as any).roomId || "");
      setHostPresent((ev as any).hostPresent || false);
      return;
    }
    if (ev.event === "host_left") {
      setHostPresent(false);
      setErr("主持人已离开会议");
      return;
    }
    if (ev.event === "host_status") {
      if ((ev as any).status === "started") setHostPresent(true);
      if ((ev as any).status === "stopped") setHostPresent(false);
      return;
    }
    if (ev.event === "error") {
      setErr((ev as any).message || "错误");
      return;
    }

    // ── transcript：ASR 实时流文本，顶部横幅展示 ──────────────────────────
    if (ev.event === "transcript") {
      const t = ev.text ?? "";
      if ((ev as any).partial) {
        setLiveTranscript(t);
      } else {
        setLiveTranscript("");
      }
      return;
    }

    // ── segment：ASR 原文，isSourceText=true ─────────────────────────────
    if (ev.event === "segment" && (ev as any).isSourceText === true) {
      const segIdx = coerceSegIndex(ev);
      if (segIdx === null) return;
      const batchTs = (ev as any).segmentTs ?? 0;
      if (batchTs === 0) return;
      const lang = normalizeLangCode((ev as any).detectedLang ?? "zh") as LangCode;
      const segText = ev.text ?? "";

      setCards((prev) => {
        const at = prev.findIndex((c) => c.segmentTs === batchTs);
        if (at >= 0) {
          const card = prev[at]!;
          const sourceByIndex = new Map(card.sourceByIndex);
          sourceByIndex.set(segIdx, { text: segText, lang });
          const next = [...prev];
          next[at] = { ...card, sourceByIndex };
          return next;
        }
        const sourceByIndex = new Map<number, { text: string; lang: LangCode }>();
        sourceByIndex.set(segIdx, { text: segText, lang });
        return [
          ...prev,
          {
            cardKey: prev.length,
            segmentTs: batchTs,
            sourceByIndex,
            transByLang: new Map(),
          },
        ];
      });
      setLiveTranscript("");
      setHostPresent(true);
      return;
    }

    // ── translation：译文，isSourceText=false ─────────────────────────────
    if (ev.event === "translation" && (ev as any).isSourceText === false) {
      const batchTs = (ev as any).segmentTs ?? 0;
      if (batchTs === 0) return;
      const transText = (ev as any).translatedText ?? "";
      if (!transText) return;
      const transSegIdx = coerceSegIndex(ev as any);
      if (transSegIdx === null) return;
      const tgtLang = normalizeLangCode((ev as any).targetLang ?? "zh") ?? "zh";

      setCards((prev) => {
        const at = prev.findIndex((c) => c.segmentTs === batchTs);
        if (at < 0) return prev;
        const card = prev[at]!;
        const transByLang = new Map(card.transByLang);
        const bySeg = new Map(transByLang.get(tgtLang) ?? []);
        bySeg.set(transSegIdx, transText);
        transByLang.set(tgtLang, bySeg);
        const next = [...prev];
        next[at] = { ...card, transByLang };
        return next;
      });
      return;
    }
  }, []);

  handleEventRef.current = handleEvent;

  // ── 当前播放段滚入可视区 ──────────────────────────────────────────────────
  useEffect(() => {
    if (playingSegIdx < 0) return;
    const root = bodyRef.current;
    if (!root) return;
    const blocks = root.querySelectorAll(".listener-segment[data-seg-ids]");
    for (const node of blocks) {
      const raw = node.getAttribute("data-seg-ids") ?? "";
      const ids = raw.split(",").map((s) => parseInt(s, 10)).filter((n) => Number.isFinite(n));
      if (ids.includes(playingSegIdx)) {
        node.scrollIntoView({ behavior: "smooth", block: "center", inline: "nearest" });
        break;
      }
    }
  }, [playingSegIdx, cards.length]);

  // ── 连接房间 ─────────────────────────────────────────────────────────────
  const connectToRoom = useCallback((rid: string, lang: LangCode) => {
    setCards([]);
    setPlayingSegIdx(-1);
    setLiveTranscript("");
    setConnected(false);
    setErr(null);

    nextScheduleRef.current = 0;
    segFirstChunkRef.current.clear();
    setPlayingSegIdx(-1);

    // 用户手势里创建 AudioContext，解除浏览器自动播放限制
    try {
      const ctx = getAudioCtx();
      void ctx.resume();
    } catch { /* ok */ }

    wsRef.current?.close();

    const wsRoot = resolveBrowserWsRoot();
    const wsUrl = `${wsRoot}/ws/room/${rid}?role=listener&listenLang=${lang}`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      setConnected(true);
      setRoomId(rid);
    };

    ws.onmessage = (m) => {
      const receiveTs = performance.now();
      if (typeof m.data === "string") {
        try {
          const parsed = JSON.parse(m.data) as AsrServerMessage;
          if (parsed.event === "transcript") {
            const isPartial = (parsed as any).partial;
            const text = (parsed as any).text ?? "";
            console.info("[Listener-WS-MSG-TRANSCRIPT] ★ receiveTs=%.2f event=transcript partial=%s textLen=%d text=\"%s\"",
              receiveTs, isPartial, text.length, text.slice(0, 50));
          }
          handleEventRef.current(parsed);
        } catch { /* ok */ }
      } else {
        // 二进制音频帧
        const blob = m.data as Blob;
        console.info("[Listener-WS-BINARY] ★ receiveTs=%.2f type=%s size=%d", 
          receiveTs, blob.type, blob.size);
        blob.arrayBuffer().then((buf) => {
          const frame = parseAudioFrame(buf);
          if (frame) {
            console.info("[Listener-BINARY-PARSE] ★ lang=%s type=%d segIdx=%d dataLen=%d currentListenLang=%s",
              frame.lang, frame.type, frame.segIdx, frame.data.byteLength, listenLangRef.current);
          } else {
            console.warn("[Listener-BINARY-PARSE] ★ 解析失败 bufLen=%d", buf.byteLength);
          }
          handleBinaryFrame(buf);
        }).catch((e) => {
          console.error("[Listener-BINARY-ERROR] ★ %s", e.message);
        });
      }
    };

    ws.onerror = () => setErr("连接失败，请检查房间号");
    ws.onclose = () => {
      setConnected(false);
      setLiveTranscript("");
    };
  }, [getAudioCtx, handleBinaryFrame]);

  // ── 切换语言 ─────────────────────────────────────────────────────────────
  const setListenLangHandler = useCallback((lang: LangCode) => {
    setListenLang(lang);
    listenLangRef.current = lang;
    // 重置 PCM 时间轴，旧语言的已调度 chunk 最多还有 ~0.25s 会自然结束
    nextScheduleRef.current = 0;
    segFirstChunkRef.current.clear();
    setPlayingSegIdx(-1);
  }, []);

  // ── 卸载时关闭 WS ────────────────────────────────────────────────────────
  useEffect(() => () => {
    wsRef.current?.close();
    audioCtxRef.current?.close().catch(() => {});
  }, []);

  // ── 段落卡片渲染 ──────────────────────────────────────────────────────────
  const segmentItem = (card: SegmentCard) => {
    const order = sortedIndexKeys(card.sourceByIndex);
    const sourceLang = batchDisplaySourceLang(card.sourceByIndex);
    const srcLabel = LANG_LABELS[sourceLang] ?? sourceLang;
    const sourceText = joinSourceText(card.sourceByIndex);
    const isSameLang = sourceLang === listenLang;

    const listenLabel = LANG_LABELS[listenLang];
    const listenBySeg = card.transByLang.get(listenLang);
    const listenText = isSameLang
      ? undefined
      : joinTransForListen(card.sourceByIndex, listenBySeg);
    const listenPending = !isSameLang && !listenText;

    const rowActive = order.includes(playingSegIdx);

    return (
      <div
        key={card.cardKey}
        data-seg={order[0]}
        data-seg-ids={order.join(",")}
        className={`listener-segment ${rowActive ? "listener-segment--playing" : ""}`}
      >
        <div className="listener-seg-source">
          <span className="listener-seg-lang-badge listener-seg-lang-badge--src">{srcLabel}</span>
          <span className="listener-seg-source-text">{sourceText}</span>
        </div>
        <div className="listener-seg-source">
          <span className="listener-seg-lang-badge listener-seg-lang-badge--listen">{listenLabel}</span>
          {isSameLang ? (
            <span className="listener-seg-source-text" style={{ color: "#9ca3af" }}>同源，不需翻译</span>
          ) : listenPending ? (
            <span className="listener-seg-pending">翻译中…</span>
          ) : (
            <span className="listener-seg-source-text">{listenText}</span>
          )}
        </div>
      </div>
    );
  };

  const [inputRoomId, setInputRoomId] = useState("");

  return (
    <div className="listener-view">
      <style>{`
        .listener-view {
          box-sizing: border-box;
          width: 100%;
          height: 100vh;
          height: 100dvh;
          margin: 0;
          padding: 8px 10px 10px;
          font-family: system-ui, -apple-system, sans-serif;
          display: flex;
          flex-direction: column;
          overflow: hidden;
          background: #e8e8ea;
        }
        .listener-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 10px;
          flex-wrap: wrap;
          padding: 10px 14px;
          background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
          color: white;
          border-radius: 10px;
          margin-bottom: 8px;
          flex-shrink: 0;
        }
        .listener-title { font-size: 18px; font-weight: 600; }
        .listener-status { display: flex; align-items: center; gap: 8px; font-size: 14px; }
        .status-dot { width: 10px; height: 10px; border-radius: 50%; background: #888; }
        .status-dot.connected { background: #4ade80; }
        .listener-controls {
          display: flex;
          gap: 10px;
          margin-bottom: 8px;
          flex-wrap: wrap;
          align-items: center;
          flex-shrink: 0;
        }
        .listener-input-group { display: flex; gap: 8px; flex: 1; min-width: 200px; }
        .listener-input {
          flex: 1;
          padding: 10px 14px;
          border: 1px solid #ddd;
          border-radius: 8px;
          font-size: 14px;
          background: #fff;
        }
        .listener-btn {
          padding: 10px 20px;
          border: none;
          border-radius: 8px;
          font-size: 14px;
          font-weight: 500;
          cursor: pointer;
          transition: all 0.2s;
        }
        .listener-btn-primary { background: #667eea; color: white; }
        .listener-btn-primary:hover { background: #5a6fd6; }
        .listener-btn-secondary { background: #f3f4f6; color: #374151; }
        .lang-selector { display: flex; align-items: center; gap: 8px; }
        .lang-selector label { font-size: 14px; color: #444; }
        .lang-selector select { padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; background: #fff; }
        .listener-err {
          padding: 8px 12px;
          background: #fee2e2;
          color: #dc2626;
          border-radius: 8px;
          margin-bottom: 8px;
          flex-shrink: 0;
        }
        .listener-main {
          flex: 1 1 0;
          min-height: 0;
          display: flex;
          flex-direction: column;
          overflow: hidden;
        }
        .listener-empty {
          flex: 1;
          min-height: 0;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          text-align: center;
          padding: 24px;
          color: #888;
          background: #f5f5f7;
          border: 1px solid #d8d8dc;
          border-radius: 12px;
        }
        .listener-empty-icon { font-size: 48px; margin-bottom: 16px; }
        .listener-segment-scroll {
          flex: 1 1 0;
          min-height: 0;
          overflow-y: auto;
          overflow-x: hidden;
          -webkit-overflow-scrolling: touch;
          padding: 10px 12px 14px;
          background: #f0f0f2;
          border: 1px solid #d8d8dc;
          border-radius: 10px;
        }
        .listener-segment-list { display: flex; flex-direction: column; gap: 14px; }
        .listener-segment {
          scroll-margin-block: 12px;
          padding: 16px 18px;
          background: #fff;
          border: 1px solid #e5e7eb;
          border-radius: 10px;
          transition: border-color 0.2s, box-shadow 0.2s;
        }
        .listener-segment--playing { border-color: #667eea; box-shadow: 0 0 0 3px rgba(102,126,234,0.12); }
        .listener-seg-source {
          display: flex;
          align-items: flex-start;
          gap: 10px;
          flex-wrap: wrap;
          margin-bottom: 10px;
        }
        .listener-seg-source-text {
          flex: 1;
          min-width: 0;
          font-size: 1rem;
          line-height: 1.6;
          word-break: break-word;
          color: #1c1c1e;
        }
        .listener-seg-lang-badge {
          display: inline-block;
          font-size: 10px;
          font-weight: 700;
          padding: 3px 7px;
          border-radius: 4px;
          text-transform: uppercase;
          letter-spacing: 0.3px;
          flex-shrink: 0;
          margin-top: 0.2em;
        }
        .listener-seg-lang-badge--src { background: #fce7f3; color: #9d174d; }
        .listener-seg-lang-badge--listen { background: #e0e7ff; color: #4338ca; }
        .listener-seg-pending { color: #9ca3af; font-size: 14px; font-style: italic; }
        .no-host-banner {
          padding: 8px 12px;
          background: #fef3c7;
          color: #92400e;
          border-radius: 8px;
          text-align: center;
          margin-bottom: 8px;
          flex-shrink: 0;
        }
        .listener-mute-btn {
          margin-left: 12px;
          padding: 4px 12px;
          border: 1px solid rgba(255,255,255,0.4);
          border-radius: 8px;
          background: rgba(255,255,255,0.15);
          color: white;
          font-size: 14px;
          cursor: pointer;
        }
        .listener-mute-btn:hover { background: rgba(255,255,255,0.25); }
        .listener-mute-btn--muted { background: rgba(220,38,38,0.3); border-color: rgba(220,38,38,0.5); }
        .listener-speed-indicator {
          margin-left: 12px;
          padding: 4px 12px;
          border: 1px solid rgba(255,255,255,0.4);
          border-radius: 8px;
          background: rgba(255,255,255,0.15);
          color: white;
          font-size: 14px;
          min-width: 60px;
          text-align: center;
        }
        .listener-speed-indicator.fast { background: rgba(74,222,128,0.3); border-color: rgba(74,222,128,0.5); }
        .listener-live-transcript {
          display: flex;
          align-items: flex-start;
          gap: 8px;
          padding: 10px 14px;
          background: linear-gradient(90deg, #fffbeb 0%, #fef9c3 100%);
          border: 1px solid #fde68a;
          border-radius: 8px;
          margin-bottom: 8px;
          flex-shrink: 0;
        }
        .listener-live-label {
          font-size: 11px;
          font-weight: 700;
          color: #d97706;
          background: #fef3c7;
          border: 1px solid #fcd34d;
          border-radius: 4px;
          padding: 1px 6px;
          flex-shrink: 0;
          margin-top: 0.2em;
        }
        .listener-live-text {
          flex: 1;
          font-size: 0.95rem;
          line-height: 1.5;
          color: #78350f;
          word-break: break-word;
          animation: listener-pulse 1.2s ease-in-out infinite;
        }
        @keyframes listener-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.75; }
        }
      `}</style>

      {/* ── 顶栏 ─────────────────────────────────────────────────────── */}
      <div className="listener-header">
        <div className="listener-title">同声传译 - 听众模式</div>
        <div className="listener-status">
          <div className={`status-dot ${connected ? "connected" : ""}`} />
          <span>{connected ? "已连接" : "未连接"}</span>
          {connected && roomId && <span style={{ marginLeft: 8, fontSize: 12, opacity: 0.9 }}>{roomId}</span>}
        </div>
        {connected && (
          <>
            <div className={`listener-speed-indicator ${displayRate > 1.0 ? 'fast' : ''}`}>
              {displayRate.toFixed(2)}x
            </div>
            <button
              className={`listener-mute-btn ${audioMuted ? "listener-mute-btn--muted" : ""}`}
              onClick={() => {
                const next = !audioMuted;
                setAudioMuted(next);
                audioMutedRef.current = next;
                if (masterGainRef.current) masterGainRef.current.gain.value = next ? 0 : 1;
              }}>
              {audioMuted ? "已静音" : "开声音"}
            </button>
          </>
        )}
      </div>

      {/* ── 实时流文本横幅 ───────────────────────────────────────────── */}
      {connected && liveTranscript && (
        <div className="listener-live-transcript">
          <span className="listener-live-label">实时</span>
          <span className="listener-live-text">{liveTranscript}</span>
        </div>
      )}

      {/* ── 控制条 ───────────────────────────────────────────────────── */}
      <div className="listener-controls">
        <div className="listener-input-group">
          <input
            className="listener-input"
            type="text"
            placeholder="输入房间号 (如 room-1001)"
            value={inputRoomId}
            onChange={(e) => setInputRoomId(e.target.value)}
            disabled={connected}
          />
          {connected ? (
            <button
              className="listener-btn listener-btn-secondary"
              onClick={() => {
                wsRef.current?.close();
                setConnected(false);
                setCards([]);
                setPlayingSegIdx(-1);
                setLiveTranscript("");
              }}>
              断开
            </button>
          ) : (
            <button
              className="listener-btn listener-btn-primary"
              onClick={() => inputRoomId && connectToRoom(inputRoomId, listenLang)}
              disabled={!inputRoomId.trim()}>
              加入房间
            </button>
          )}
        </div>
        <div className="lang-selector">
          <label>收听语言:</label>
          <select
            value={listenLang}
            onChange={(e) => setListenLangHandler(e.target.value as LangCode)}
            disabled={connected}>
            {ALL_LANGS.map((l) => <option key={l} value={l}>{LANG_LABELS[l]}</option>)}
          </select>
        </div>
      </div>

      {err && <div className="listener-err">{err}</div>}
      {!hostPresent && connected && <div className="no-host-banner">等待主持人开始讲话...</div>}

      {/* ── 内容区 ───────────────────────────────────────────────────── */}
      <div className="listener-main">
        {(!connected || cards.length === 0) ? (
          <div className="listener-empty">
            <div className="listener-empty-icon">🎙️</div>
            <div>输入房间号加入会议</div>
            <div style={{ marginTop: 8, fontSize: 14, color: "#aaa" }}>加入后即可收听实时翻译</div>
          </div>
        ) : (
          <div className="listener-segment-scroll" ref={bodyRef}>
            <div className="listener-segment-list">
              {cards.map((card) => segmentItem(card))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
