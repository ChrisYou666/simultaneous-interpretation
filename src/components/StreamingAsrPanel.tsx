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

/** 批内子句 */
type SubSeg = {
  index: number;
  text: string;
  lang: LangCode;
};

/** 一个 final batch（segmentTs 相同）：可能含多个子句，各自独立 index */
type BatchEntry = {
  segmentTs: number;
  /** 批内子句，按 index 升序排列 */
  subSegs: SubSeg[];
  /** index → lang → 译文 */
  transByIndex: Map<number, Map<string, string>>;
};

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
  /**
   * ASR final 句子批次：每个 segmentTs 一个卡片
   * 一个 batch 可能含多个子句（各自 index），前端拼接展示
   */
  const [batches, setBatches] = useState<BatchEntry[]>([]);
  /** 全局翻译文本：index → lang → text */
  const [translations, setTranslations] = useState<Map<number, Map<string, string>>>(new Map());
  const [listenLang, _setListenLang] = useState<LangCode>("zh");
  const [playingSegIdx, setPlayingSegIdx] = useState(-1);

  /** 调参面板展开状态 */
  const [showTuning, setShowTuning] = useState(false);
  /** 调参参数值 */
  const [tuningParams, setTuningParams] = useState<Record<string, number | string>>({});
  /** 调参加载中 */
  const [tuningLoading, setTuningLoading] = useState(false);

  /** 历史记录（按 zh→en→id 顺序） */
  const [history, setHistory] = useState<HistoryEntry[]>([]);

  const wsRef = useRef<WebSocket | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<AudioNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

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

  /* ── Language switch ── */
  const setListenLang = useCallback((lang: LangCode) => {
    _setListenLang(lang);
    listenLangRef.current = lang;
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ action: "setListenLang", lang }));
    }
  }, []);

  /* ── Cleanup ── */
  const stopInternal = useCallback(() => {
    const ws = wsRef.current; wsRef.current = null;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) ws.close();
    processorRef.current?.disconnect(); processorRef.current = null;
    streamRef.current?.getTracks().forEach((t) => t.stop()); streamRef.current = null;
    const shared = audioCtxRef.current;
    audioCtxRef.current = null;
    shared?.close().catch(() => {});
    capturePipelineStartedAtRef.current = 0;
    lastLoudAudioAtRef.current = 0;
    setCaptureSilentWarn(false);
    setRunning(false); setPlayingSegIdx(-1);
  }, []);

  useEffect(() => () => stopInternal(), [stopInternal]);

  /* ── WebSocket event handler ── */
  const handleEvent = useCallback((ev: AsrServerMessage) => {
    if (ev.event === "ready") {
      setErr(null);
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
      const text = ev.text ?? "";
      console.info("[Panel-SEG] ★ segIdx=%d batchTs=%d srcLang=%s text=\"%s\"",
        segIdx, batchTs, srcCode, text.slice(0, 60));

      setBatches((prev) => {
        const at = prev.findIndex((b) => b.segmentTs === batchTs);
        const sub: SubSeg = { index: segIdx, text, lang: srcCode };

        if (at >= 0) {
          // 该 batch 已存在：跳过（防重）
          const batch = prev[at]!;
          if (batch.subSegs.some((s) => s.index === segIdx)) return prev;
          const subSegs = [...batch.subSegs, sub].sort((a, b2) => a.index - b2.index);
          const next = [...prev];
          next[at] = { ...batch, subSegs };
          return next;
        }
        // 新 batch：追加
        return [...prev, { segmentTs: batchTs, subSegs: [sub], transByIndex: new Map() }];
      });

      setHistory((prev) => {
        const entry: HistoryEntry = {
          segIndex: segIdx,
          sourceByLang: { [srcCode]: text },
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
      console.info("[Panel-TRANS] ★ segIdx=%d tgtLang=%s text=\"%s\"",
        segIdx, tgt, (ev.translatedText ?? "").slice(0, 60));

      // 更新全局 translations map
      setTranslations((p) => {
        const n = new Map(p);
        const lm = new Map(n.get(segIdx) ?? []);
        lm.set(tgt, ev.translatedText);
        n.set(segIdx, lm);
        return n;
      });

      // 更新 batches 中对应 batch 的 transByIndex
      setBatches((prev) => {
        let changed = false;
        const n = prev.map((batch) => {
          if (!batch.subSegs.some((s) => s.index === segIdx)) return batch;
          changed = true;
          const transByIndex = new Map(batch.transByIndex);
          const lm = new Map(transByIndex.get(segIdx) ?? []);
          lm.set(tgt, ev.translatedText);
          transByIndex.set(segIdx, lm);
          return { ...batch, transByIndex };
        });
        return changed ? n : prev;
      });

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
  }, []);

  /* ── Auto-scroll ── */
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
        if (ids.includes(playingSegIdx)) { el = node; break; }
      }
    }
    if (el) el.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [playingSegIdx]);

  useEffect(() => {
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [batches, partial]);

  useEffect(() => {
    if (partial || batches.length > 0) setCaptureSilentWarn(false);
  }, [partial, batches.length]);

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
    setErr(null); setPartial(""); partialRef.current = ""; setLiveTranscript(""); setBatches([]); setTranslations(new Map()); setDetectedLang("");
    setPlayingSegIdx(-1);
    micCumSentRef.current = 0;

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

  /**
   * 获取 batch 中指定语种的展示文本（跨子句按 index 升序拼接）
   */
  const getBatchLine = (
    batch: BatchEntry,
    lang: LangCode
  ): { text: string; pending: boolean } => {
    const sorted = [...batch.subSegs].sort((a, b) => a.index - b.index);
    // 判断是否源语
    const isSrc = sorted.some((s) => s.lang === lang);
    if (isSrc) {
      return { text: sorted.map((s) => s.text).join(""), pending: false };
    }
    // 译语列：按 index 顺序拼接翻译
    const parts: string[] = [];
    for (const seg of sorted) {
      const t = batch.transByIndex.get(seg.index)?.get(lang)
             ?? translations.get(seg.index)?.get(lang);
      if (t !== undefined) parts.push(t);
      else return { text: parts.join(""), pending: true };
    }
    return { text: parts.join(""), pending: false };
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
            {batches.length === 0 && !running && (
              <div className="si-tri-empty">
                点击「开始收音」，在弹窗中选择要识别的标签页或窗口，并勾选分享音频（源语言可为中文、英语或印尼语）
              </div>
            )}
            {batches.length === 0 && running && !partial && (
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
            {batches.map((batch) => {
              const sorted = [...batch.subSegs].sort((a, b) => a.index - b.index);
              const headIndex = sorted[0]?.index ?? 0;
              const mergedIndices = sorted.map((s) => s.index).join(",");
              const rowActive = sorted.some((s) => s.index === playingSegIdx);
              return (
                <div
                  key={batch.segmentTs}
                  data-seg={headIndex}
                  data-merged-indices={mergedIndices}
                  className={`si-tri-block ${rowActive ? "si-tri-block--playing" : ""}`}
                >
                  {(["zh", "en", "id"] as LangCode[]).map((lang) => {
                    const { text, pending } = getBatchLine(batch, lang);
                    return (
                      <div key={lang} className="si-tri-block-line">
                        <span className={`si-tri-line-lang-badge si-tri-line-lang-badge--${lang}`} title="本行语种">
                          {LANG_LABELS[lang]}
                        </span>
                        <div className="si-tri-block-line-body">
                          {pending ? (
                            <span className="si-tri-seg-pending">翻译中…</span>
                          ) : (
                            <SegmentPlayingText text={text} active={rowActive} />
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
