import { useCallback, useEffect, useRef, useState } from "react";
import {
  ALL_LANGS,
  LANG_LABELS,
  buildAsrWebSocketUrl,
  canonicalSourceLangFromSegment,
  coerceSegIndex,
  normalizeLangCode,
  parseAsrServerEvent,
  type AsrServerMessage,
  type LangCode,
} from "../lib/asrWs";

/** 历史记录条目：原文不变，按 zh→en→id 顺序记录翻译 */
export type HistoryEntry = {
  segIndex: number;
  /** 各语言原文（detectedLang 来自服务端） */
  sourceByLang: Record<string, string>;
  /** 各语言翻译文本（null=未翻译/同源） */
  transByLang: Record<string, string | null>;
  /** 段生成时间戳 */
  ts: number;
};

type Props = { floor?: number; glossary?: string; context?: string; roomId?: string };

type SourcePart = {
  text: string;
  detectedLang: string;
  sourceLangCode: LangCode;
};

/** 同一次 ASR final：segmentTs=batchId；parts 的 key = 与后端 segment/translation/TTS 一致的 index */
type SegmentEntry = {
  segmentBatchTs: number;
  parts: Map<number, SourcePart>;
};

function batchSortedIndices(parts: Map<number, SourcePart>): number[] {
  return [...parts.keys()].sort((a, b) => a - b);
}

/** 与 ListenerView：batch 内任一段为 en/id 则整行源语列按该语展示 */
function batchPrimarySourceLang(parts: Map<number, SourcePart>): LangCode {
  const order = batchSortedIndices(parts);
  for (const i of order) {
    if (parts.get(i)!.sourceLangCode === "en") return "en";
  }
  for (const i of order) {
    if (parts.get(i)!.sourceLangCode === "id") return "id";
  }
  return order.length ? parts.get(order[0]!)!.sourceLangCode : "zh";
}

function batchSourceConcat(parts: Map<number, SourcePart>): string {
  return batchSortedIndices(parts)
    .map((i) => parts.get(i)!.text)
    .join(" ");
}

/** 展示用最大 index（与 TTS、延迟统计对齐） */
function batchDisplayIndex(parts: Map<number, SourcePart>): number {
  const s = batchSortedIndices(parts);
  return s.length ? s[s.length - 1]! : 0;
}
type TranslationMap = Map<number, Map<string, string>>;
/** 播发延时（ms）；na 表示无收听语 TTS（同源/跳过/失败） */
type ListenLatencyEntry = number | "na";

function formatListenLatency(entry: ListenLatencyEntry | undefined): string | null {
  if (entry === undefined) return null;
  if (entry === "na") return "—";
  if (entry < 1000) return `${entry} ms`;
  return `${(entry / 1000).toFixed(1)} s`;
}

/** 当前段在播放时整行高亮（不按字跟读） */
function SegmentPlayingText({ text, active }: { text: string; active: boolean }) {
  if (!text) return null;
  return <span className={active ? "si-tri-line-playing" : "si-tri-line-plain"}>{text}</span>;
}

function IconLatencyHeadset() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 3a7 7 0 00-7 7v3a2 2 0 002 2h1v-6H6a5 5 0 0110 0v6h-2v2h3a2 2 0 002-2v-3a7 7 0 00-7-7z"
        fill="currentColor"
      />
    </svg>
  );
}

const TUNING_FIELDS: {
  group: string;
  key: string;
  label: string;
  desc: string;
  min: number;
  max: number;
  step?: number;
  defaultNum: number;
}[] = [
  {
    group: "切段",
    key: "segMaxChars",
    label: "maxChars",
    desc: "单段最大字符数（中文基准）；越小越早送出翻译/TTS，延迟更低，但句子更易被切碎。",
    min: 8,
    max: 512,
    defaultNum: 50,
  },
  {
    group: "切段",
    key: "segEnMaxCharsMultiplier",
    label: "enMultiplier",
    desc: "英语句子 maxChars 倍数（避免短句过度切分导致卡顿）；建议 1.5-2.5。",
    min: 1.0,
    max: 4.0,
    step: 0.1,
    defaultNum: 2.0,
  },
  {
    group: "切段",
    key: "segEnMaxCharsMin",
    label: "enMinChars",
    desc: "英语句子 maxChars 最小值；防止倍数为 1 时仍过短。",
    min: 10,
    max: 200,
    defaultNum: 10,
  },
  {
    group: "切段",
    key: "segEnMaxCharsMax",
    label: "enMaxChars",
    desc: "英语句子 maxChars 最大值（建议 60-80，英文问句较长需要更大缓冲区）。",
    min: 20,
    max: 200,
    defaultNum: 80,
  },
  {
    group: "切段",
    key: "segIdMaxCharsMultiplier",
    label: "idMultiplier",
    desc: "印尼语 maxChars 倍数（印尼语词汇更长，虚词较多）；建议 1.5-2.0。",
    min: 1.0,
    max: 4.0,
    step: 0.1,
    defaultNum: 1.6,
  },
  {
    group: "切段",
    key: "segIdMaxCharsMin",
    label: "idMinChars",
    desc: "印尼语 maxChars 最小值；防止倍数为 1 时仍过短。",
    min: 10,
    max: 200,
    defaultNum: 10,
  },
  {
    group: "切段",
    key: "segIdMaxCharsMax",
    label: "idMaxChars",
    desc: "印尼语 maxChars 最大值；印尼语独立阈值，防止超长句。",
    min: 20,
    max: 200,
    defaultNum: 60,
  },
  {
    group: "切段",
    key: "segSoftBreakChars",
    label: "softBreakChars",
    desc: "至少多长才开始在逗号、空格等「软标点」处切分；略大则更倾向整句再切。",
    min: 4,
    max: 256,
    defaultNum: 15,
  },
  {
    group: "切段",
    key: "segFlushTimeoutMs",
    label: "flushTimeoutMs",
    desc: "距上次切出超过该毫秒则强制刷出缓冲（建议 400-600ms，英文/印尼语一句话可能包含多个分句）。",
    min: 50,
    max: 10000,
    defaultNum: 500,
  },
  {
    group: "翻译",
    key: "translateMaxTokens",
    label: "maxTokens",
    desc: "单次翻译生成上限 token；同传短句可调小以缩短模型耗时。",
    min: 32,
    max: 2048,
    defaultNum: 320,
  },
  {
    group: "翻译",
    key: "translateTimeoutSec",
    label: "timeoutSec",
    desc: "调用翻译 HTTP 的超时秒数；过短易失败，过长则卡住久等。",
    min: 5,
    max: 120,
    defaultNum: 25,
  },
  {
    group: "TTS",
    key: "ttsRate",
    label: "ttsRate",
    desc: "TTS 基础语速（0.5–2.0）。",
    min: 0.5,
    max: 2,
    step: 0.1,
    defaultNum: 1.0,
  },
];

export function StreamingAsrPanel({ floor = 1, glossary = "", context = "", roomId }: Props) {
  const [running, setRunning] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [partial, setPartial] = useState("");
  /** 追踪当前 partial 文本（区分未切出内容 vs 已切段内容） */
  const partialRef = useRef("");
  const [detectedLang, setDetectedLang] = useState<string>("");
  /**
   * ASR transcript partial 实时文本：在 segment 到达时不清除，持续显示实时识别进度。
   * 与 segments 的区别：partial 是「仍在识别中」的实时文本，segments 是已定段确认的句子。
   * 仅在 stop / start 时清空。
   */
  const [liveTranscript, setLiveTranscript] = useState("");
  const [segments, setSegments] = useState<SegmentEntry[]>([]);
  const [translations, setTranslations] = useState<TranslationMap>(new Map());
  const [listenLang, _setListenLang] = useState<LangCode>("zh");
  const [playingSegIdx, setPlayingSegIdx] = useState(-1);
  const [listenLatenciesMs, setListenLatenciesMs] = useState<Map<number, ListenLatencyEntry>>(() => new Map());

  /** 调参面板展开状态 */
  const [showTuning, setShowTuning] = useState(false);
  /** 调参参数值 */
  const [tuningParams, setTuningParams] = useState<Record<string, number | string>>({});
  /** 调参加载中 */
  const [tuningLoading, setTuningLoading] = useState(false);

  /** 音频静音状态（仅当前页面生效） */
  const [audioMuted, setAudioMuted] = useState(false);
  /** 音频静音 ref（避免 AudioContext 在静音状态变化时重新创建） */
  const audioMutedRef = useRef(false);
  /** 播放总线 Gain：所有 TTS/回放经此节点，切换静音立即作用于当前正在播的 Buffer */
  const playbackOutGainRef = useRef<GainNode | null>(null);
  /** 发言端标识：发言端（自己）不需要听自己的 TTS，播放会导致声学回授。默认 true（发言端）。 */
  const isSpeakerRef = useRef(true);
  /** 历史记录（按 zh→en→id 顺序） */
  const [history, setHistory] = useState<HistoryEntry[]>([]);

  const wsRef = useRef<WebSocket | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<AudioNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  // 简化播放状态：缓存音频 + 按 seq 顺序播放
  const seqCompletedRef = useRef<Set<number>>(new Set());
  /** 下一个该播放的 seq 序号 */
  const nextSeqRef = useRef<number>(0);
  const isPlayingRef = useRef(false);
  const currentSourceRef = useRef<AudioBufferSourceNode | null>(null);
  /** seq → lang → ArrayBuffer[] */
  const audioCacheRef = useRef<Map<number, Map<string, ArrayBuffer[]>>>(new Map());
  /** seq → detectedLang（用于判断播放 PCM 还是 TTS） */
  const detectedLangMapRef = useRef<Map<number, string>>(new Map());
  const listenLangRef = useRef<LangCode>(listenLang);
  /** 上行 PCM 采样率（与 ready / segment.inputSampleRate 一致） */
  const sentSampleRateRef = useRef(16000);
  /** 已发往 WS 的累计 int16 采样数 */
  const micCumSentRef = useRef(0);
  /** 上行音频管线启动时间（用于检测「长时间几乎全零」） */
  const capturePipelineStartedAtRef = useRef(0);
  /** 最近一次输入块 RMS 超过阈值的时间 */
  const lastLoudAudioAtRef = useRef(0);

  const bodyRef = useRef<HTMLDivElement>(null);
  /** 捕获流疑似静音（标签页静音 / 选错源 / 未勾选分享音频等） */
  const [captureSilentWarn, setCaptureSilentWarn] = useState(false);

  /* ── 调参 API ── */
  const loadTuningParams = useCallback(async () => {
    setTuningLoading(true);
    try {
      const res = await fetch("/api/tuning/params");
      if (res.ok) {
        const data = await res.json();
        setTuningParams(data);
      }
    } catch {
      /* ignore */
    } finally {
      setTuningLoading(false);
    }
  }, []);

  const applyTuningParam = useCallback(async (key: string, value: number | string) => {
    try {
      const res = await fetch("/api/tuning/params", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ [key]: value }),
      });
      if (res.ok) {
        const data = await res.json();
        setTuningParams(data.current);
      }
    } catch {
      /* ignore */
    }
  }, []);

  /** 确保播放输出节点存在（挂到 ctx.destination 前级） */
  const connectPlaybackOut = useCallback((ctx: AudioContext) => {
    const existing = playbackOutGainRef.current;
    if (existing && existing.context === ctx) return existing;
    const g = ctx.createGain();
    g.gain.value = audioMutedRef.current ? 0 : 1;
    g.connect(ctx.destination);
    playbackOutGainRef.current = g;
    return g;
  }, []);

  /* ── Ordered playback: 从缓存最小 seq 开始，严格递增，seq_completed 驱动跳过 ── */
  const scheduleNextChunk = useCallback(() => {
    if (isPlayingRef.current) {
      console.debug("[AsrPanel-PLAY] isPlaying=true，跳过调度 nextSeq=%d", nextSeqRef.current);
      return;
    }

    // 发言端（主持界面）不播放 TTS 音频——只收听实时音频进行翻译，不听翻译结果
    if (isSpeakerRef.current) {
      const cachedSeqs = Array.from(audioCacheRef.current.keys()).sort((a, b) => a - b);
      if (cachedSeqs.length === 0) {
        console.debug("[AsrPanel-PLAY] isSpeaker=true，缓存为空，跳过");
        return;
      }
      const next = cachedSeqs[0];
      console.info("[AsrPanel-PLAY] isSpeaker=true，清除seq=%d缓存，继续等待", next);
      audioCacheRef.current.delete(next);
      nextSeqRef.current = next + 1;
      return;
    }

    const myLang = listenLangRef.current;

    // 找到下一个该播放的 seq
    let seq = nextSeqRef.current;
    const cachedSeqs = Array.from(audioCacheRef.current.keys()).sort((a, b) => a - b);

    // [PLAY_LOG] 每次调度入口
    console.info(
      "[AsrPanel-PLAY] scheduleNextChunk myLang=%c%s%c seq=%d cachedSeqs=%o completed=%o isPlaying=%s",
      "color:#f0f;font-weight:bold", myLang, "color:inherit", seq,
      cachedSeqs, [...seqCompletedRef.current], isPlayingRef.current
    );

    // 跳过已完成的 seq
    while (seqCompletedRef.current.has(seq) || !cachedSeqs.includes(seq)) {
      if (seqCompletedRef.current.has(seq)) {
        console.info("[AsrPanel-PLAY] seq=%d 命中 seq_completed，跳过", seq);
        seq++;
        continue;
      }
      if (!cachedSeqs.includes(seq)) {
        const hasLarger = cachedSeqs.some((s) => s > seq);
        if (hasLarger) {
          console.info("[AsrPanel-PLAY] seq=%d 未收到音频但有更大seq=%o，跳过（疑似丢包）", seq, cachedSeqs);
          seq++;
          continue;
        }
        // [PLAY_LOG] 该 seq 没收到音频，且缓存中最小的就是它，等
        nextSeqRef.current = seq;
        console.info("[AsrPanel-PLAY] seq=%d 未收到音频，缓存中最小，继续等待", seq);
        return;
      }
    }

    nextSeqRef.current = seq;

    const segC = audioCacheRef.current.get(seq);
    if (!segC) {
      console.info("[AsrPanel-PLAY] seq=%d 缓存为空", seq);
      return;
    }

    const langs = Array.from(segC.keys());
    if (langs.length === 0) {
      console.info("[AsrPanel-PLAY] seq=%d 缓存中无语言", seq);
      return;
    }

    // [PLAY_LOG] 语言选择
    const targetLang = langs.includes(myLang) ? myLang : langs[0];
    const isPreferred = targetLang === myLang;
    console.info(
      "[AsrPanel-PLAY] seq=%d 语言决策: cachedLangs=%o myLang=%c%s%c isPreferred=%s targetLang=%s",
      seq, langs,
      "color:#f0f;font-weight:bold", myLang, "color:inherit",
      isPreferred, targetLang
    );

    const bufs = segC.get(targetLang);
    if (!bufs || bufs.length === 0) {
      console.info("[AsrPanel-PLAY] seq=%d targetLang=%s 无音频缓冲区", seq, targetLang);
      return;
    }

    isPlayingRef.current = true;
    setPlayingSegIdx(seq);

    const ctx = audioCtxRef.current ?? new AudioContext();
    audioCtxRef.current = ctx;
    void ctx.resume().catch(() => {});
    const out = connectPlaybackOut(ctx);

    console.info("[AsrPanel-PLAY] seq=%d decodeAudioData 开始 lang=%s chunks=%d", seq, targetLang, bufs.length);
    ctx.decodeAudioData(bufs[0].slice(0))
      .then((decoded) => {
        console.info(
          "[AsrPanel-PLAY] ▶ seq=%d ▶ 播放 audio=▶ lang=%c%s%c chunks=%d dur=%.2fs",
          seq,
          isPreferred ? "color:#0f0;font-weight:bold" : "color:#f00;font-weight:bold",
          targetLang, "color:inherit",
          bufs.length, decoded.duration
        );
        const src = ctx.createBufferSource();
        src.buffer = decoded;
        src.connect(out);
        currentSourceRef.current = src;
        src.onended = () => {
          const remaining = segC.get(targetLang);
          if (remaining && remaining.length > 1) {
            segC.set(targetLang, remaining.slice(1));
            currentSourceRef.current = null;
            isPlayingRef.current = false;
            console.info("[AsrPanel-PLAY] seq=%d chunk播完，还有%d片余量，继续", seq, remaining.length - 1);
            if (out.context.state === "running") scheduleNextChunk();
          } else {
            segC.delete(targetLang);
            if (segC.size === 0) audioCacheRef.current.delete(seq);
            currentSourceRef.current = null;
            isPlayingRef.current = false;
            const finishedSeq = seq;
            nextSeqRef.current = seq + 1;
            console.info("[AsrPanel-PLAY] seq=%d ▶▶ 播完，清缓存，nextSeq→%d", finishedSeq, seq + 1);
            if (out.context.state === "running") scheduleNextChunk();
          }
        };
        src.start();
      })
      .catch((err) => {
        console.error("[AsrPanel-PLAY] seq=%d 解码失败:", seq, err);
        currentSourceRef.current = null;
        isPlayingRef.current = false;
        nextSeqRef.current = seq + 1;
        if (ctx.state === "running") scheduleNextChunk();
      });
  }, [connectPlaybackOut]);

  /* ── Language switch ── */
  const setListenLang = useCallback((lang: LangCode) => {
    const prev = listenLangRef.current;
    console.debug(
      "[LANG] 切换: %c%s%c → %c%s%c nextSeq=%d cacheSize=%d",
      "color:#f55", prev, "color:inherit",
      "color:#5f5;font-weight:bold", lang, "color:inherit",
      nextSeqRef.current, audioCacheRef.current.size
    );
    _setListenLang(lang);
    listenLangRef.current = lang;
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ action: "setListenLang", lang }));
    }

    // 停止当前播放，从当前已播放到的 seq 继续（不重置到 0，避免重复）
    if (currentSourceRef.current) {
      currentSourceRef.current.onended = null;
      try { currentSourceRef.current.stop(); } catch { /* ok */ }
      currentSourceRef.current = null;
    }
    isPlayingRef.current = false;
    setPlayingSegIdx(-1);
    // nextSeqRef 保持不变，从当前位置继续找新语言音频
    // 清除旧语言缓存，防止切语言后误播
    audioCacheRef.current.clear();
    scheduleNextChunk();
  }, [scheduleNextChunk]);

  /* ── Cache audio ── */
  const cacheAudio = useCallback((seq: number, targetLang: string, b64: string) => {
    try {
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);

      let segC = audioCacheRef.current.get(seq);
      if (!segC) { segC = new Map(); audioCacheRef.current.set(seq, segC); }
      let arr = segC.get(targetLang);
      if (!arr) { arr = []; segC.set(targetLang, arr); }
      arr.push(bytes.buffer);

      console.info(
        "[AsrPanel-CACHE] seq=%d lang=%c%s%c bytes=%d chunks=%d allLangs=%o nextSeq=%d isPlaying=%s isSpeaker=%s",
        seq,
        "color:#ff0;font-weight:bold", targetLang, "color:inherit",
        bin.length, arr.length, [...segC.keys()],
        nextSeqRef.current, isPlayingRef.current, isSpeakerRef.current
      );
      scheduleNextChunk();
    } catch (err) {
      console.error("[AsrPanel-CACHE-ERR] seq=%d lang=%s:", seq, targetLang, err);
    }
  }, [scheduleNextChunk]);

  /* ── Cleanup ── */
  const stopInternal = useCallback(() => {
    const ws = wsRef.current; wsRef.current = null;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) ws.close();
    processorRef.current?.disconnect(); processorRef.current = null;
    streamRef.current?.getTracks().forEach((t) => t.stop()); streamRef.current = null;
    const shared = audioCtxRef.current;
    audioCtxRef.current = null;
    shared?.close().catch(() => {});
    playbackOutGainRef.current = null;
    seqCompletedRef.current.clear();
    capturePipelineStartedAtRef.current = 0;
    lastLoudAudioAtRef.current = 0;
    setCaptureSilentWarn(false);
    isPlayingRef.current = false;
    if (currentSourceRef.current) { currentSourceRef.current.onended = null; try { currentSourceRef.current.stop(); } catch { /* ok */ } currentSourceRef.current = null; }
    setRunning(false); setPlayingSegIdx(-1);
  }, []);

  useEffect(() => () => stopInternal(), [stopInternal]);

  /* ── WebSocket event handler ── */
  const handleEvent = useCallback((ev: AsrServerMessage) => {
    if (ev.event === "ready") {
      setErr(null);
      // 从缓存最小 seq 开始播放（新加入听众从 currentSeq 继续）
      if (typeof ev.currentSeq === "number") {
        nextSeqRef.current = ev.currentSeq;
      }
      return;
    }
    if (ev.event === "error") {
      const parts = [ev.message, ev.detail].filter((x): x is string => Boolean(x && String(x).trim()));
      setErr(parts.length > 0 ? parts.join("\n\n") : "ASR 错误"); return;
    }
    if (ev.event === "transcript") {
      const handleStart = performance.now();
      if (ev.language) {
        const n = normalizeLangCode(ev.language);
        setDetectedLang(n ?? ev.language);
      }
      if (ev.partial) {
        const raw = ev.text ?? "";
        partialRef.current = raw;
        const shown = raw.length > 60 ? `…${raw.slice(-59)}` : raw;
        const handleMs = performance.now() - handleStart;
        console.info("[Panel-TRANSCRIPT] ★ partial=true textLen=%d shownLen=%d handleMs=%.2f", raw.length, shown.length, handleMs);
        setPartial(shown);
        setLiveTranscript(shown);
      }
    }
    if (ev.event === "segment") {
      if (ev.isSourceText !== true) return;
      const segIdx = coerceSegIndex(ev);
      if (segIdx === null) {
        console.warn("[StreamingAsrPanel] segment 缺少有效 index", ev);
        return;
      }
      const batchTs = ev.segmentTs ?? 0;
      if (batchTs === 0) {
        console.warn("[StreamingAsrPanel] segment 缺少 segmentTs", ev);
        return;
      }
      const srcCode = canonicalSourceLangFromSegment(ev);
      console.info("[Panel-SEG] ★ segIdx=%d batchTs=%d srcLang=%s detectedLang=%s text=\"%s\"",
        segIdx, batchTs, srcCode, ev.detectedLang ?? "?",
        (ev.text ?? "").slice(0, 60));

      detectedLangMapRef.current.set(segIdx, srcCode);
      // partial 文本不再清空，保持显示 ASR 实时识别进度直到 stop/start

      setSegments((p) => {
        const at = p.findIndex((row) => row.segmentBatchTs === batchTs);
        const part: SourcePart = {
          text: ev.text ?? "",
          detectedLang: ev.detectedLang,
          sourceLangCode: srcCode,
        };
        if (at >= 0) {
          const row = p[at]!;
          const parts = new Map(row.parts);
          parts.set(segIdx, part);
          const next = [...p];
          next[at] = { segmentBatchTs: batchTs, parts };
          return next;
        }
        const parts = new Map<number, SourcePart>();
        parts.set(segIdx, part);
        if (nextSeqRef.current === 0) {
          nextSeqRef.current = segIdx;
        }
        return [...p, { segmentBatchTs: batchTs, parts }];
      });
      if (ev.detectedLang) setDetectedLang(srcCode);
      scheduleNextChunk();

      // 追加历史记录（zh→en→id 顺序）
      setHistory((prev) => {
        const entry: HistoryEntry = {
          segIndex: segIdx,
          sourceByLang: { [srcCode]: ev.text },
          transByLang: {},
          ts: Date.now(),
        };
        return [...prev, entry];
      });
    }
    if (ev.event === "translation") {
      if (ev.isSourceText !== false) return;
      const segIdx = coerceSegIndex(ev);
      if (segIdx === null) {
        console.warn("[StreamingAsrPanel] translation 缺少有效 index", ev);
        return;
      }
      const tgt = normalizeLangCode(ev.targetLang) ?? ev.targetLang;
      console.info("[Panel-TRANS] ★ segIdx=%d tgtLang=%s srcLang=%s text=\"%s\"",
        segIdx, tgt, ev.sourceLang ?? "?",
        (ev.translatedText ?? "").slice(0, 60));
      setTranslations((p) => {
        const n = new Map(p);
        const lm = new Map(n.get(segIdx) ?? []);
        lm.set(tgt, ev.translatedText);
        n.set(segIdx, lm);
        return n;
      });
      // 更新历史记录的翻译文本
      setHistory((prev) => {
        const hi = prev.findIndex((e) => e.segIndex === segIdx);
        if (hi < 0) return prev;
        const next = [...prev];
        const entry = { ...next[hi] };
        entry.transByLang = { ...entry.transByLang, [tgt]: ev.translatedText };
        next[hi] = entry;
        return next;
      });
    }
    if (ev.event === "tts_skip") {
      const skipIdx = coerceSegIndex(ev);
      console.info("[Panel-TTS-SKIP] ★ segIdx=%s lang=%s reason=%s",
        skipIdx ?? "?", (ev as any).lang ?? (ev as any).targetLang ?? "?",
        (ev as any).reason ?? "?");
      // TTS 跳过时触发检查（该语言音频不会被收到，播放逻辑会等其他语言）
      scheduleNextChunk();
    }
    if (ev.event === "audio") {
      const segIdx = coerceSegIndex(ev);
      if (segIdx === null) {
        console.warn("[AsrPanel] audio 事件缺少有效 index", ev);
        return;
      }
      if (!ev.data || ev.data.trim() === "") {
        console.warn("[AsrPanel-AUDIO-EMPTY] ★ segIdx=%d targetLang=%s data为空，跳过", segIdx, ev.targetLang);
        return;
      }
      const approxBytes = Math.floor(ev.data.length * 0.75);
      console.info("[AsrPanel-AUDIO] ★ segIdx=%d targetLang=%s srcLang=%s b64Len=%d approxBytes=%d",
        segIdx, ev.targetLang, ev.sourceLang ?? "?", ev.data.length, approxBytes);
      cacheAudio(segIdx, ev.targetLang, ev.data);
    }
  }, [cacheAudio, scheduleNextChunk]);

  /* ── Auto-scroll（合并行用 data-merged-indices 命中子段号） ── */
  useEffect(() => {
    if (playingSegIdx < 0) return;
    const root = bodyRef.current;
    if (!root) return;
    let el: Element | null = root.querySelector(`[data-seg="${playingSegIdx}"]`);
    if (!el) {
      for (const node of root.querySelectorAll("[data-merged-indices]")) {
        const ids = (node.getAttribute("data-merged-indices") || "")
          .split(",")
          .map((s) => parseInt(s, 10))
          .filter((n) => !Number.isNaN(n));
        if (ids.includes(playingSegIdx)) {
          el = node;
          break;
        }
      }
    }
    if (el) el.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [playingSegIdx]);

  useEffect(() => {
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [segments, partial]);

  useEffect(() => {
    if (partial || segments.length > 0) setCaptureSilentWarn(false);
  }, [partial, segments.length]);

  /** 运行约 5s 后若捕获流仍几乎全静音，提示检查标签页静音 / 分享源 */
  useEffect(() => {
    if (!running) {
      setCaptureSilentWarn(false);
      return;
    }
    const iv = window.setInterval(() => {
      const start = capturePipelineStartedAtRef.current;
      const last = lastLoudAudioAtRef.current;
      const now = Date.now();
      if (start === 0 || now - start < 5000) return;
      if (last === 0 || now - last > 3500) setCaptureSilentWarn(true);
    }, 2000);
    return () => clearInterval(iv);
  }, [running]);

  /* ── Start recording ── */
  const start = async () => {
    setErr(null); setPartial(""); partialRef.current = ""; setLiveTranscript(""); setSegments([]); setTranslations(new Map()); setDetectedLang("");
    setPlayingSegIdx(-1);
    setListenLatenciesMs(new Map());
    detectedLangMapRef.current.clear();
    micCumSentRef.current = 0;
    audioCacheRef.current.clear();
    seqCompletedRef.current.clear();
    nextSeqRef.current = 0;

    /**
     * 收听端音频采集方案：通过 getDisplayMedia 让用户手动选择要捕获的标签页/窗口音频。
     *
     * 重要约束：
     * - 不选「整个屏幕」：整个屏幕会把麦克风也混进来，导致物理回授。
     * - 不选「浏览器标签页」：自己标签页里的 TTS 声音会被捕获，形成回授循环。
     * - 推荐选「单独窗口」（如会议软件窗口、播放器窗口），并且发言端 TTS 静音已由代码保证。
     *
     * 音频约束全部关闭（echoCancellation/noiseSuppression/autoGainControl），
     * 确保完整保留系统音频内容，不被浏览器处理链过滤。
     */
    let stream: MediaStream;
    try {
      const dispStream: MediaStream = await navigator.mediaDevices.getDisplayMedia({
        video: true,
        audio: {
          echoCancellation: false,
          noiseSuppression: false,
          autoGainControl: false,
        },
      });
      dispStream.getVideoTracks().forEach((t) => t.stop());

      const settings = dispStream.getAudioTracks()[0]?.getSettings();
      const displaySurface = (settings as Record<string, unknown>)?.displaySurface as string | undefined;

      if (displaySurface === "monitor") {
        dispStream.getTracks().forEach((t) => t.stop());
        setErr(
          "检测到你选择了「整个屏幕」，系统音频会包含麦克风输入，会造成回授循环。请重新选择：点击分享弹窗左下角「分享整个屏幕」旁的下拉箭头，改选「窗口」→ 选择会议软件或播放器的独立窗口。"
        );
        return;
      }

      if (displaySurface === "browser") {
        dispStream.getTracks().forEach((t) => t.stop());
        setErr(
          "检测到你选择了「浏览器标签页」，本标签页内的 TTS 音频会被捕获并送入 ASR，导致循环识别。请重新选择：点击分享弹窗左下角「分享标签页」旁的下拉箭头，改选「窗口」→ 选择会议软件或播放器的独立窗口。"
        );
        return;
      }

      const audioTracks = dispStream.getAudioTracks();
      if (audioTracks.length === 0) {
        dispStream.getTracks().forEach((t) => t.stop());
        setErr(
          "已选择分享源，但没有音频轨。请在分享弹窗里勾选「分享音频 / Share tab audio」选项，或换一个带音频的窗口。"
        );
        return;
      }
      stream = dispStream;
      console.info("[START] 系统音频捕获成功，displaySurface=%s audioTracks:", displaySurface, audioTracks.map((t) => t.label || t.id));
    } catch (dispErr) {
      console.warn("[START] getDisplayMedia 失败:", dispErr);
      setErr(
        "无法开始窗口分享（浏览器拒绝了请求或当前环境不支持）。请确保在弹窗中选择了要捕获音频的窗口，并勾选「分享音频」选项。"
      );
      return;
    }

    const url = buildAsrWebSocketUrl(floor, roomId);
    const ws = new WebSocket(url); ws.binaryType = "arraybuffer"; wsRef.current = ws;
    let targetSampleRate = 16000;

    try {
      await new Promise<void>((resolve, reject) => {
        let settled = false;
        const fail = (msg: string) => { if (settled) return; settled = true; ws.onerror = null; ws.onclose = null; reject(new Error(msg)); };
        ws.onerror = () => fail("WebSocket 连接被拒绝。请检查后端是否运行。");
        ws.onclose = (ev) => { if (settled) return; fail(`WebSocket 握手被拒绝（code ${ev.code}）。`); };
        ws.onopen = () => {
          console.info("[ASR-WS] WebSocket 连接建立 floor=%d roomId=%s", floor, roomId || "无");
          if (settled) return;
          settled = true;
          ws.onerror = null;
          ws.onclose = null;
          /* 在等 ready 之前下发 glossary / 收听语 */
          if (glossary || context) ws.send(JSON.stringify({ action: "setGlossary", glossary, context }));
          ws.send(JSON.stringify({ action: "setListenLang", lang: listenLangRef.current }));
          console.info("[ASR-WS] 发送 setListenLang=%s", listenLangRef.current);
          resolve();
        };
      });
      await new Promise<void>((resolve, reject) => {
        const t = window.setTimeout(() => reject(new Error("等待 ASR 就绪超时（15s）")), 15000);
        const onMsg = (m: MessageEvent) => {
          if (typeof m.data !== "string") return;
          try {
            const parsed = parseAsrServerEvent(m.data);
            if (!parsed) return;
            if (parsed.event === "ready") { window.clearTimeout(t); ws.removeEventListener("message", onMsg); if (typeof parsed.sampleRate === "number" && parsed.sampleRate > 0) targetSampleRate = parsed.sampleRate; handleEvent(parsed); resolve(); return; }
            if (parsed.event === "error") { window.clearTimeout(t); ws.removeEventListener("message", onMsg); const pe = parsed as { message?: string; detail?: string }; const parts = [pe.message, pe.detail].filter((x): x is string => Boolean(x && String(x).trim())); reject(new Error(parts.length > 0 ? parts.join("\n\n") : "ASR 错误")); }
          } catch (e) {
            console.error("[ASR] 就绪握手消息处理异常", e);
          }
        };
        ws.addEventListener("message", onMsg);
      });
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); ws.close(); stream.getTracks().forEach((x) => x.stop()); return; }

    sentSampleRateRef.current = targetSampleRate;

    const audioContext = new AudioContext({ sampleRate: targetSampleRate });
    const actualSr = audioContext.sampleRate;
    const needResample = Math.abs(actualSr - targetSampleRate) > 50;
    ws.onmessage = (m) => {
      const receiveTs = performance.now();
      const msgSize = m.data instanceof ArrayBuffer ? m.data.byteLength : (typeof m.data === "string" ? m.data.length : 0);
      console.info("[ASR-WS-MSG] ★ receiveTs=%.2f binaryType=%s size=%d bytes", receiveTs, typeof m.data === "string" ? "text" : "binary", msgSize);
      if (typeof m.data !== "string") return;
      try {
        const parsed = parseAsrServerEvent(m.data);
        if (parsed) {
          if (parsed.event === "transcript") {
            const isPartial = (parsed as any).partial;
            const text = (parsed as any).text ?? "";
            console.info("[ASR-WS-MSG-TRANSCRIPT] ★ event=transcript partial=%s textLen=%d text=\"%s\"",
              isPartial, text.length, text.slice(0, 50));
          }
          handleEvent(parsed);
        }
      } catch (e) {
        console.error("[ASR] 下行消息处理异常", e);
      }
    };
    ws.onerror = () => setErr("WebSocket 传输中断");
    ws.onclose = () => stopInternal();

    const source = audioContext.createMediaStreamSource(stream);
    stream.getAudioTracks().forEach((track) => {
      track.onended = () => {
        console.warn("[AUDIO] 系统音频轨道结束（用户停止共享或窗口关闭），自动停止");
        stopInternal();
      };
    });
    // AudioWorklet：在专用音频线程缓冲，每 100ms 发一次到主线程，不阻塞 UI/渲染
    // chunkSamples = 100ms 对应的原生采样数（浏览器通常 48kHz → 4800）
    const chunkSamples = Math.round(actualSr * 0.1);
    await audioContext.audioWorklet.addModule("/audio-capture-processor.js");
    const workletNode = new AudioWorkletNode(audioContext, "audio-capture-processor", {
      processorOptions: { chunkSamples },
    });

    workletNode.port.onmessage = (e: MessageEvent<Float32Array>) => {
      const w = wsRef.current;
      if (!w || w.readyState !== WebSocket.OPEN) return;
      const inp = e.data;
      const workletReceiveTs = performance.now(); // AudioWorklet 接收时刻

      // RMS 静音检测（与原逻辑一致）
      let sumSq = 0;
      for (let i = 0; i < inp.length; i++) sumSq += inp[i] * inp[i];
      const rms = Math.sqrt(sumSq / inp.length);
      if (rms > 0.0012) lastLoudAudioAtRef.current = Date.now();

      // 下采样（若 AudioContext 未能运行在目标采样率，则手动线性插值）
      let samples: Float32Array;
      const resampleStartTs = performance.now();
      if (needResample) {
        const ratio = targetSampleRate / actualSr;
        const newLen = Math.round(inp.length * ratio);
        samples = new Float32Array(newLen);
        for (let i = 0; i < newLen; i++) {
          const srcIdx = i / ratio;
          const lo = Math.floor(srcIdx);
          const hi = Math.min(lo + 1, inp.length - 1);
          const frac = srcIdx - lo;
          samples[i] = inp[lo] * (1 - frac) + inp[hi] * frac;
        }
      } else {
        samples = inp;
      }
      const resampleMs = performance.now() - resampleStartTs;

      // Float32 → Int16
      const i16StartTs = performance.now();
      const i16 = new Int16Array(samples.length);
      for (let i = 0; i < samples.length; i++) {
        const s = Math.max(-1, Math.min(1, samples[i]));
        i16[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
      }
      const i16Ms = performance.now() - i16StartTs;

      const prevCum = micCumSentRef.current;
      micCumSentRef.current += i16.length;
      sentSampleRateRef.current = targetSampleRate;

      // 方案B：在 PCM 数据前附加 8 字节起始采样位置（BigInt64 大端序），供后端精确索引
      const bufBuildStartTs = performance.now();
      const buf = new ArrayBuffer(8 + i16.byteLength);
      const startSamplePos = BigInt(prevCum);
      new DataView(buf).setBigInt64(0, startSamplePos, false);
      new Uint8Array(buf, 8).set(new Uint8Array(i16.buffer));
      const bufBuildMs = performance.now() - bufBuildStartTs;

      const wsSendStartTs = performance.now();
      w.send(buf);
      const wsSendMs = performance.now() - wsSendStartTs;
      const totalProcessingMs = performance.now() - workletReceiveTs;

      console.debug("[AUDIO-WORKLET] samples=%d bufBytes=%d startSample=%s rms=%.4f " +
        "resampleMs=%.2f i16Ms=%.2f bufBuildMs=%.2f wsSendMs=%.2f totalMs=%.2f " +
        "actualSr=%d targetSr=%d needResample=%s",
        samples.length, buf.byteLength, startSamplePos.toString(), rms,
        resampleMs, i16Ms, bufBuildMs, wsSendMs, totalProcessingMs,
        actualSr, targetSampleRate, needResample);
    };

    processorRef.current = workletNode; streamRef.current = stream; audioCtxRef.current = audioContext;
    capturePipelineStartedAtRef.current = Date.now();
    lastLoudAudioAtRef.current = 0;

    const mute = audioContext.createGain();
    mute.gain.value = 0;
    source.connect(workletNode);
    workletNode.connect(mute);
    mute.connect(audioContext.destination);
    setRunning(true);
  };

  const stop = () => {
    stopInternal();
    setPartial("");
  };

  /** 同 batch 内按 index 升序拼接；源语列对应该 batch 的主源语种 */
  const getSegTextByLang = (
    seg: SegmentEntry,
    lang: LangCode
  ): { text: string; allPending: boolean; tailPending: boolean } => {
    const merged = batchSortedIndices(seg.parts);
    const primary = batchPrimarySourceLang(seg.parts);
    if (primary === lang) {
      return { text: batchSourceConcat(seg.parts), allPending: false, tailPending: false };
    }
    const parts: string[] = [];
    for (const idx of merged) {
      const t = translations.get(idx)?.get(lang);
      if (t === undefined) {
        if (parts.length === 0) return { text: "", allPending: true, tailPending: false };
        const remainingSubSegs = merged.length - parts.length;
        return {
          text: parts.join(" "),
          allPending: false,
          tailPending: remainingSubSegs > 1,
        };
      }
      parts.push(t);
    }
    return { text: parts.join(" "), allPending: false, tailPending: false };
  };

  return (
    <div className="si-trilingual">
      <div className="si-tri-host-layout">
        <div className="si-tri-toolbar">
          <span className="si-tri-title">实时同传</span>
          {detectedLang ? (
            <span className="si-tri-detected-lang" title="流式语种提示">
              {LANG_LABELS[detectedLang as LangCode] ?? detectedLang}
            </span>
          ) : null}
          <button
            type="button"
            className="si-tri-tuning-btn"
            onClick={() => {
              if (!showTuning) loadTuningParams();
              setShowTuning((v) => !v);
            }}
            title="调参面板"
          >
            {showTuning ? "隐藏调参" : "调参"}
          </button>
        </div>

        {showTuning && (
          <div className="si-tri-tuning-panel">
            {tuningLoading && <p className="si-tri-tuning-loading">正在加载当前参数…</p>}
            {(["切段", "翻译", "TTS"] as const).map((groupName) => (
              <div key={groupName} className="si-tri-tuning-group">
                <div className="si-tri-tuning-group-title">{groupName}参数</div>
                {TUNING_FIELDS.filter((f) => f.group === groupName).map((field) => {
                  const raw = (tuningParams as Record<string, number | string>)[field.key];
                  const num =
                    typeof raw === "number"
                      ? raw
                      : typeof raw === "string"
                        ? parseFloat(raw)
                        : field.defaultNum;
                  const safe = Number.isFinite(num) ? num : field.defaultNum;
                  const isFloat = field.step !== undefined && !Number.isInteger(field.step);
                  return (
                    <div key={field.key} className="si-tri-tuning-field">
                      <div className="si-tri-tuning-row">
                        <label htmlFor={`tuning-${field.key}`} title={field.desc}>
                          {field.label}
                        </label>
                        <input
                          id={`tuning-${field.key}`}
                          type="number"
                          value={safe}
                          min={field.min}
                          max={field.max}
                          step={field.step}
                          onChange={(e) => {
                            const v = isFloat
                              ? parseFloat(e.target.value)
                              : parseInt(e.target.value, 10);
                            if (!Number.isFinite(v)) return;
                            void applyTuningParam(field.key, v);
                          }}
                        />
                      </div>
                      <p className="si-tri-tuning-desc">{field.desc}</p>
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        )}

        {err && <p className="si-tri-err" style={{ whiteSpace: "pre-wrap" }}>{err}</p>}

        <div className="si-tri-transcript-dock">
          <div className="si-tri-transcript-dock-inner" ref={bodyRef}>
            {segments.length === 0 && !running && (
              <div className="si-tri-empty">
                点击「开始收音」，在弹窗中选择要识别的标签页或窗口，并勾选分享音频（源语言可为中文、英语或印尼语）
              </div>
            )}
            {segments.length === 0 && running && !partial && (
              <div className="si-tri-empty si-tri-empty--capture-wait">
                <div>等待语音…</div>
                {captureSilentWarn && (
                  <p className="si-tri-capture-warn">
                    已连接但几乎无音频。请检查：① 被分享的窗口<strong>不要静音</strong>；② 分享弹窗里已勾选「分享音频」；③
                    确认选的是「窗口」而非「整个屏幕」（整个屏幕会混入麦克风造成回授）；④
                    分享弹窗中务必点开下拉箭头，选「窗口」标签，再选择会议软件或播放器窗口。
                  </p>
                )}
                <ul className="si-tri-capture-tips">
                  <li>务必选择「窗口」而非「整个屏幕」或「标签页」，避免麦克风混音进入捕获流。</li>
                  <li>标签页静音时，浏览器通常不会把声音送进分享流，识别会一直空白。</li>
                </ul>
              </div>
            )}
            {segments.map((seg) => {
              const merged = batchSortedIndices(seg.parts);
              const rowActive = merged.includes(playingSegIdx);
              const dispIdx = batchDisplayIndex(seg.parts);
              const listenEntry = listenLatenciesMs.get(dispIdx);
              const latencyLabel = listenEntry !== undefined
                ? (listenEntry === "na" ? "—" : formatListenLatency(listenEntry) ?? "—")
                : "延迟 …";
              const latencyTitle = "延迟：PCM采集 → 翻译 → TTS → 音频下发";
              return (
                <div
                  key={merged.join("-")}
                  data-seg={merged[0]}
                  data-merged-indices={merged.join(",")}
                  className={`si-tri-block ${rowActive ? "si-tri-block--playing" : ""}`}
                >
                  <div
                    className={`si-tri-block-latency ${listenEntry === undefined ? "si-tri-block-latency--wait" : ""}`}
                    title={latencyTitle}
                  >
                    <IconLatencyHeadset />
                    <span>{latencyLabel}</span>
                  </div>
                  {(["zh", "en", "id"] as LangCode[]).map((lang) => {
                    const { text, allPending, tailPending } = getSegTextByLang(seg, lang);
                    return (
                      <div key={lang} className="si-tri-block-line">
                        <span className={`si-tri-line-lang-badge si-tri-line-lang-badge--${lang}`} title="本行语种">
                          {LANG_LABELS[lang]}
                        </span>
                        <div className="si-tri-block-line-body">
                          {allPending ? (
                            <span className="si-tri-seg-pending">翻译中…</span>
                          ) : (
                            <>
                              <SegmentPlayingText text={text} active={rowActive} />
                              {tailPending ? (
                                <span className="si-tri-seg-pending si-tri-seg-pending--tail">（后续句翻译中…）</span>
                              ) : null}
                            </>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              );
            })}
            {running && partial ? (
              <div
                className="si-tri-block si-tri-block--partial"
                data-partial-live="1"
                aria-live="polite"
                title="流式识别中，定段后将移入上方并完成翻译"
              >
                <div
                  className="si-tri-block-latency si-tri-block-latency--streaming"
                  title="流式识别中，定段后将统计端到端延迟"
                >
                  <IconLatencyHeadset />
                  <span>实时</span>
                </div>
                {(["zh", "en", "id"] as LangCode[]).map((lang) => {
                  const srcNorm = normalizeLangCode(detectedLang);
                  const srcLang: LangCode =
                    srcNorm !== null
                      ? srcNorm
                      : ALL_LANGS.includes(detectedLang as LangCode)
                        ? (detectedLang as LangCode)
                        : "zh";
                  const isSource = lang === srcLang;
                  return (
                    <div key={`partial-${lang}`} className="si-tri-block-line">
                      <span
                        className={`si-tri-line-lang-badge si-tri-line-lang-badge--partial si-tri-line-lang-badge--${lang}`}
                        title="本行语种"
                      >
                        {LANG_LABELS[lang]}
                      </span>
                      <div className="si-tri-block-line-body">
                        {isSource ? (
                          // 源语：实时显示 transcript 文本，说话瞬间就有反馈
                          <span className="si-tri-partial-live-text">{liveTranscript}</span>
                        ) : (
                          // 译语：实时显示 ASR 原文预览，定段后替换为正式翻译
                          <span className="si-tri-seg-pending">实时: {partial.slice(-40)}</span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : null}
          </div>
        </div>
      </div>

      <div className="si-tri-footer">
        <div className="si-tri-listen-wrap">
          <span className="si-tri-select-label">收听</span>
          <svg className="si-tri-listen-icon-svg" width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden><path d="M11 5L6 9H3v6h3l5 4V5zm7.5 7a4.5 4.5 0 00-2.5-4v8a4.5 4.5 0 002.5-4z" fill="currentColor"/></svg>
          <select className="si-tri-listen-select" value={listenLang} disabled={running} onChange={(e) => setListenLang(e.target.value as LangCode)} aria-label="收听语言">
            {ALL_LANGS.map((l) => <option key={l} value={l}>{LANG_LABELS[l]}</option>)}
          </select>
        </div>
        {!running
          ? <button type="button" className="si-tri-btn" onClick={() => void start()}>开始收音</button>
          : <button type="button" className="si-tri-btn si-tri-btn--stop" onClick={stop}>停止</button>}
        <button
          type="button"
          className={`si-tri-audio-btn ${audioMuted ? "si-tri-audio-btn--muted" : ""}`}
          onClick={() => {
            const next = !audioMuted;
            setAudioMuted(next);
            audioMutedRef.current = next;
            const g = playbackOutGainRef.current;
            if (g) g.gain.value = next ? 0 : 1;
          }}
          title={audioMuted ? "取消静音" : "静音"}
        >
          {audioMuted ? (
            /* 静音图标 */
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="M11 5L6 9H3v6h3l5 4V5zm7.5 7a4.5 4.5 0 01-2.5-4v8a4.5 4.5 0 002.5-4z" fill="currentColor"/>
              <line x1="23" y1="9" x2="17" y2="15" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
          ) : (
            /* 有声音图标 */
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="M11 5L6 9H3v6h3l5 4V5zm7.5 7a4.5 4.5 0 00-2.5-4v8a4.5 4.5 0 002.5-4z" fill="currentColor"/>
              <path d="M15.5 10a3 3 0 010 4M19 7a7 7 0 010 10" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
          )}
        </button>
      </div>

      {/* 历史记录折叠区 */}
      {history.length > 0 && (
        <details className="si-history">
          <summary className="si-history-title">
            历史记录（共 {history.length} 段）
          </summary>
          <table className="si-history-table">
            <thead>
              <tr>
                <th>#</th>
                <th>原文（检测语种）</th>
                <th>中文翻译</th>
                <th>English</th>
                <th>Bahasa Indonesia</th>
              </tr>
            </thead>
            <tbody>
              {history.map((entry) => {
                const srcLang = Object.keys(entry.sourceByLang)[0];
                const srcText = entry.sourceByLang[srcLang] ?? "";
                const srcLabel = LANG_LABELS[srcLang as LangCode] ?? srcLang;
                return (
                  <tr key={entry.segIndex}>
                    <td className="si-history-col-idx">{entry.segIndex}</td>
                    <td className="si-history-col-src">
                      <span className="si-history-lang-tag">{srcLabel}</span>
                      {srcText}
                    </td>
                    <td>{entry.transByLang["zh"] ?? <span className="si-history-empty">—</span>}</td>
                    <td>{entry.transByLang["en"] ?? <span className="si-history-empty">—</span>}</td>
                    <td>{entry.transByLang["id"] ?? <span className="si-history-empty">—</span>}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </details>
      )}
    </div>
  );
}
