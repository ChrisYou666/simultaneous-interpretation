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

/** 同一 batch（segmentTs）内按 index 升序取 key 列表 */
function sortedIndexKeys<T>(m: Map<number, T>): number[] {
  return [...m.keys()].sort((a, b) => a - b);
}

/** 卡片源语展示语种：batch 内任一段为 en/id 则优先（减轻单段误标 zh 时「同源无需翻译」） */
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

// ── 数据结构：一个 segmentTs = 一个 ASR final；index = 段号，与后端 segment/translation/TTS 一致 ──
type SegmentCard = {
  cardKey: number;
  segmentTs: number;
  /** ASR 段：index → 文本与归一语种（仅来自 isSourceText=true） */
  sourceByIndex: Map<number, { text: string; lang: LangCode }>;
  /** 目标语 → index → 译文（仅来自 isSourceText=false） */
  transByLang: Map<string, Map<number, string>>;
};

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
  const [playbackRate, setPlaybackRate] = useState(1.0);

  // ── 音频统计追踪 ─────────────────────────────────────────────────────
  /** 已收到音频的段（记录每个 segIdx 收到 WAV 的次数） */
  const receivedWavCountRef = useRef<Map<number, number>>(new Map());
  /** 已播放的段 */
  const playedSegIdxsRef = useRef<Set<number>>(new Set());
  /** 跳过的段（无音频被跳过） */
  const skippedSegIdxsRef = useRef<Set<number>>(new Set());
  /** 音频统计日志定时器 */
  const audioStatsIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /** 打印音频统计摘要 */
  const printAudioStats = useCallback(() => {
    const received = receivedWavCountRef.current;
    const played = playedSegIdxsRef.current;
    const skipped = skippedSegIdxsRef.current;
    const cacheKeys = [...audioCacheRef.current.keys()];
    const pendingKeys = [...pendingSegIdxRef.current];
    const endedKeys = [...audioEndedRef.current];

    console.info("[AudioStats] ★★★ 统计摘要 ★★★ received=%d played=%d skipped=%d cache=%O pending=%O ended=%O",
      received.size, played.size, skipped.size, cacheKeys, pendingKeys, endedKeys);

    // 检查异常：cache里有但ended里没有的段
    for (const [idx] of audioCacheRef.current) {
      if (!audioEndedRef.current.has(idx)) {
        console.warn("[AudioStats-WARN] segIdx=%d 在cache里但ended里没有，可能导致内存泄漏！", idx);
      }
    }
  }, []);

  // ── 播放相关 ref ────────────────────────────────────────────────────────
  const audioCtxRef = useRef<AudioContext | null>(null);
  const playbackCtxRef = useRef<AudioContext | null>(null);
  const currentSourceRef = useRef<AudioBufferSourceNode | null>(null);
  const nextSegIdxRef = useRef(0);
  const audioEndedRef = useRef<Set<number>>(new Set());
  const isPlayingRef = useRef(false);
  const listenLangRef = useRef<LangCode>(listenLang);
  /**
   * 每段合并后的整段 WAV（与 RealtimeTtsService 一致：CosyVoice 流在 threshold>0 时按字节切块，
   * 各块是同一 WAV 的连续片段，须在 END 帧后拼成一条再 decode；不可逐块 decode）。
   */
  const audioCacheRef = useRef<Map<number, ArrayBuffer>>(new Map());
  const wavChunksBySegRef = useRef<Map<number, Uint8Array[]>>(new Map());
  const audioMutedRef = useRef(false);
  const playbackMasterGainRef = useRef<GainNode | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  /** 正在传输中（收到音频帧但尚未收到 END）的段号 */
  const pendingSegIdxRef = useRef<Set<number>>(new Set());
  /**
   * 加入房间时从 fetchHistoryAudio 响应中确定的最小 segIdx。
   * canJump 跳段时，k < joinSegIdxRef 的段视为"加入前已完成"，可安全跳过；
   * k >= joinSegIdxRef 且未结束的段视为"TTS 仍在合成中"，禁止跳过。
   * 初始值 0：未加入/新会话起点，不允许随意跳段。
   */
  const joinSegIdxRef = useRef<number>(0);
  /**
   * 记录每个 segment 首次进入 WAIT-PENDING 状态的时刻（ms）。
   * 若等待超过 PENDING_TIMEOUT_MS 且后续有就绪段，视为 TTS 永久失败，允许跳过。
   */
  const segPendingStartRef = useRef<Map<number, number>>(new Map());
  /** TTS 永久失败超时阈值：15 秒内仍未收到音频则跳过 */
  const PENDING_TIMEOUT_MS = 15_000;

  // ── 轮询相关 ref ───────────────────────────────────────────────────────
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastPollSeqRef = useRef<number>(0);
  const pollRoomIdRef = useRef<string>("");
  const pollLangRef = useRef<string>("");
  const fetchAbortRef = useRef<AbortController | null>(null);

  const handleEventRef = useRef<(ev: AsrServerMessage) => void>(() => {});

  // ─── 播放主逻辑 ────────────────────────────────────────────────────────
  const ensureMasterGain = useCallback((ctx: AudioContext) => {
    if (playbackMasterGainRef.current && playbackMasterGainRef.current.context === ctx) {
      return playbackMasterGainRef.current;
    }
    const g = ctx.createGain();
    g.gain.value = audioMutedRef.current ? 0 : 1;
    g.connect(ctx.destination);
    playbackMasterGainRef.current = g;
    return g;
  }, []);

  /** 该段仍有 0x01 块未收到 END（不能跳到更后一段） */
  const segmentHasInFlight = useCallback((seg: number) => wavChunksBySegRef.current.has(seg), []);

  /**
   * 根据积压计算播放速率：
   * backlog = audioCache 中等待播放的段数 + pendingSegIdxRef 中正在传输的段数
   * 积压 ≤ 4 段 → 1.0x（不加速）
   * 积压 > 4 段 → 每多1段加速 0.05，上限 2.0x，下限 1.0x
   * 例如：积压5段→1.05x，6段→1.10x，... 24段→2.0x
   */
  const adjustPlaybackRate = useCallback(() => {
    const pendingCount = pendingSegIdxRef.current.size;
    const cachedCount = audioCacheRef.current.size;
    const backlog = pendingCount + cachedCount;

    let rate: number;
    if (backlog <= 4) {
      rate = 1.0;
    } else {
      rate = Math.min(2.0, 1.0 + (backlog - 4) * 0.05);
    }

    setPlaybackRate((prev) => {
      if (Math.abs(rate - prev) > 0.01) {
        currentPlaybackRate.current = rate;
        return rate;
      }
      return prev;
    });
  }, []);

  const playNext = useCallback(() => {
    const callTs = Date.now();
    const isPlaying = isPlayingRef.current;
    const nextSeg = nextSegIdxRef.current;
    const cacheSize = audioCacheRef.current.size;
    const endedSize = audioEndedRef.current.size;
    const pendingSize = pendingSegIdxRef.current.size;
    
    console.info("[ListenerPlay-ENTER] ★ ts=%d isPlaying=%s nextSeg=%d cacheSize=%d endedSize=%d pending=%s",
      callTs, isPlaying, nextSeg, cacheSize, endedSize, pendingSize);
    console.debug("[ListenerPlay-DEBUG] pendingSegIdxs=%o endedSet=%o",
      [...pendingSegIdxRef.current], [...audioEndedRef.current]);
    console.debug("[ListenerPlay-DEBUG] audioCache keys=%o",
      [...audioCacheRef.current.entries()].map(([k,v]) => `${k}:${v.byteLength}B`));
      
    if (isPlaying) {
      console.debug("[ListenerPlay] 已在播放，跳过");
      return;
    }

    // 跳过「已结束且无音频」的段（TTS skip 等）
    while (audioEndedRef.current.has(nextSegIdxRef.current)) {
      const buf = audioCacheRef.current.get(nextSegIdxRef.current);
      if (buf && buf.byteLength > 0) break;
      console.info("[ListenerPlay-SKIP] segIdx=%d 已结束但无音频（skip/空），跳过", nextSegIdxRef.current);
      skippedSegIdxsRef.current.add(nextSegIdxRef.current);
      console.info("[ListenerPlay-SKIPPED] ★ segIdx=%d 已标记为跳过 totalSkipped=%d", nextSegIdxRef.current, skippedSegIdxsRef.current.size);
      nextSegIdxRef.current++;
    }

    const readySorted: number[] = [];
    for (const [idx, buf] of audioCacheRef.current) {
      if (audioEndedRef.current.has(idx) && buf.byteLength > 0) readySorted.push(idx);
    }
    readySorted.sort((a, b) => a - b);

    if (readySorted.length === 0) {
      console.info("[ListenerPlay-NO-READY] 无就绪段 cache=%d ended=%d pending=%d", cacheSize, endedSize, pendingSize);
      setPlayingSegIdx(-1);
      return;
    }

    const expect = nextSegIdxRef.current;
    const minReady = readySorted[0]!;
    
    // 晚进房/段号不从 0 起：缓存里最小可播段 > expect，且 expect..minReady-1 均可安全跳过 → 跳到 minReady
    // 判断规则（优先级从高到低）：
    //   1. segmentHasInFlight(k)  → 正在接收 WAV 块，禁止跳过
    //   2. audioEndedRef.has(k)   → END 已到且无音频（TTS-skip），可跳过
    //   3. k < joinSegIdxRef      → 加入房间前已完成的段，可跳过（晚进房）
    //   4. 其余                   → TTS 仍在合成中尚未下发，禁止跳过
    if (minReady > expect) {
      const joinSeg = joinSegIdxRef.current;
      let canJump = true;
      for (let k = expect; k < minReady; k++) {
        if (segmentHasInFlight(k)) {
          canJump = false;
          console.info("[ListenerPlay-JUMP-BLOCKED] segIdx=%d 仍有在途数据，禁止跳跃到 %d", k, minReady);
          break;
        }
        if (audioEndedRef.current.has(k)) continue; // TTS-skip / 0-WAV 结束，可跳过
        if (k < joinSeg) continue; // 加入前已完成的段，晚进房可跳过
        // 既未结束也不在我们加入前，TTS 可能还在合成
        canJump = false;
        console.info("[ListenerPlay-JUMP-BLOCKED] segIdx=%d TTS尚未下发（joinSeg=%d），禁止跳跃到 %d", k, joinSeg, minReady);
        break;
      }
      if (canJump) {
        console.info("[ListenerPlay-JUMP] 从 segIdx=%d 跳跃到 segIdx=%d（晚进房或积压清理，joinSeg=%d）", expect, minReady, joinSeg);
        nextSegIdxRef.current = minReady;
      }
    }

    if (segmentHasInFlight(nextSegIdxRef.current)) {
      console.info("[ListenerPlay-WAIT] segIdx=%d 仍在传输中（pending=%s），等待", 
        nextSegIdxRef.current, pendingSegIdxRef.current);
      setPlayingSegIdx(-1);
      return;
    }

    let segIdx = nextSegIdxRef.current;
    let wav = audioCacheRef.current.get(segIdx);
    let ended = audioEndedRef.current.has(segIdx);

    if (!wav?.byteLength || !ended) {
      const joinSeg = joinSegIdxRef.current;
      if (segIdx >= joinSeg) {
        // 当前段在加入范围内，尚未就绪 → TTS 可能仍在合成中
        const now = Date.now();
        if (!segPendingStartRef.current.has(segIdx)) {
          segPendingStartRef.current.set(segIdx, now);
        }
        const waitMs = now - segPendingStartRef.current.get(segIdx)!;
        const nextReady = readySorted.find((k) => k > segIdx);
        if (waitMs < PENDING_TIMEOUT_MS || nextReady === undefined) {
          // 未超时，或超时但无更新的就绪段，继续等待
          console.info("[ListenerPlay-WAIT-PENDING] segIdx=%d 未就绪（joinSeg=%d），已等待 %dms，TTS合成中，等待",
            segIdx, joinSeg, waitMs);
          setPlayingSegIdx(-1);
          return;
        }
        // 超时：TTS 永久失败，跳到下一个就绪段
        console.warn("[ListenerPlay-TIMEOUT-SKIP] segIdx=%d 等待超过 %dms（joinSeg=%d），TTS永久失败，跳到 %d",
          segIdx, waitMs, joinSeg, nextReady);
        segPendingStartRef.current.delete(segIdx);
        nextSegIdxRef.current = nextReady;
        segIdx = nextReady;
        wav = audioCacheRef.current.get(segIdx);
        ended = audioEndedRef.current.has(segIdx);
      } else {
        // segIdx < joinSeg：加入前的历史段，可安全跳到下一就绪段
        const pick = readySorted.find((k) => k >= segIdx);
        if (pick !== undefined) {
          console.info("[ListenerPlay-PICK] segIdx=%d 在加入点前（joinSeg=%d），跳到就绪段 %d", segIdx, joinSeg, pick);
          segIdx = pick;
          nextSegIdxRef.current = segIdx;
          wav = audioCacheRef.current.get(segIdx);
          ended = audioEndedRef.current.has(segIdx);
        }
      }
    }

    if (!wav?.byteLength || !ended) {
      console.warn("[ListenerPlay-WAIT-2] segIdx=%d wav=%s ended=%s →等待修复", segIdx,
        wav ? `${wav.byteLength}B` : "null", ended);
      setPlayingSegIdx(-1);
      return;
    }

    console.info("[ListenerPlay-START] ▶▶▶ 开始播放 segIdx=%d wavBytes=%d cacheSize=%d callTs=%d",
      segIdx, wav.byteLength, audioCacheRef.current.size, callTs);
    isPlayingRef.current = true;
    setPlayingSegIdx(segIdx);

    // 统计：记录开始播放
    playedSegIdxsRef.current.add(segIdx);
    console.info("[ListenerPlay-PLAYING] ★ segIdx=%d 已标记为播放中 totalPlayed=%d", segIdx, playedSegIdxsRef.current.size);

    const ctx = audioCtxRef.current ?? playbackCtxRef.current ?? new AudioContext();
    playbackCtxRef.current = ctx;
    void ctx.resume();
    const out = ensureMasterGain(ctx);

    ctx.decodeAudioData(wav.slice(0)).then((decoded) => {
      const decodeTs = Date.now();
      const totalMs = decodeTs - callTs;
      console.info("[ListenerPlay-DECODE-OK] segIdx=%d duration=%.3fs sampleRate=%d channels=%d totalMs=%d",
        segIdx, decoded.duration, decoded.sampleRate, decoded.numberOfChannels, totalMs);
      if (ctx.state === "suspended") void ctx.resume();
      const src = ctx.createBufferSource();
      src.buffer = decoded;
      src.playbackRate.value = currentPlaybackRate.current;
      src.connect(out);
      currentSourceRef.current = src;

      src.onended = () => {
        const endedTs = Date.now();
        console.info("[ListenerPlay-ENDED] segIdx=%d →nextSeg=%d cacheLeft=%d endedTs=%d",
          segIdx, segIdx + 1, audioCacheRef.current.size - 1, endedTs);
        currentSourceRef.current = null;
        isPlayingRef.current = false;
        audioCacheRef.current.delete(segIdx);
        nextSegIdxRef.current = segIdx + 1;
        adjustPlaybackRate();
        playNext();
      };
      const startTs = Date.now();
      console.info("[ListenerPlay-SRC-START] segIdx=%d src.start() startTs=%d", segIdx, startTs);
      src.start();
    }).catch((err) => {
      console.error("[ListenerPlay-DECODE-FAIL] segIdx=%d wavBytes=%d →跳过此段:", segIdx, wav.byteLength, err);
      isPlayingRef.current = false;
      audioCacheRef.current.delete(segIdx);
      nextSegIdxRef.current = segIdx + 1;
      playNext();
    });
  }, [ensureMasterGain, segmentHasInFlight]);

  const playNextRef = useRef(playNext);
  playNextRef.current = playNext;

  // 同步 playbackRate 状态到 ref，避免 playNext 闭包捕获旧值
  const currentPlaybackRate = useRef(1.0);
  useEffect(() => { currentPlaybackRate.current = playbackRate; }, [playbackRate]);

  // ─── 解析二进制音频帧 ───────────────────────────────────────────────────
  const parsePollResponse = useCallback((buffer: ArrayBuffer) => {
    const dv = new DataView(buffer);
    const results: Array<{ segIdx: number; type: number; lang: string; payload: ArrayBuffer }> = [];
    let offset = 0;

    console.debug("[ListenerParse] 开始解析 buffer totalBytes=%d", buffer.byteLength);
    while (offset + 4 <= buffer.byteLength) {
      const frameLen = dv.getInt32(offset, false);
      offset += 4;
      if (frameLen === 0) { console.debug("[ListenerParse] frameLen=0 终止，offset=%d", offset); break; }
      if (offset + frameLen > buffer.byteLength) {
        console.warn("[ListenerParse] ★截断★ frameLen=%d 超出 buffer 边界 offset=%d total=%d", frameLen, offset, buffer.byteLength);
        break;
      }

      const payloadBuf = buffer.slice(offset, offset + frameLen);
      offset += frameLen;

      const payloadDv = new DataView(payloadBuf);
      const segIdx = payloadDv.getInt32(0, false);
      const type = payloadDv.getUint8(4);
      const langLen = payloadDv.getUint8(5);
      const langBytes = new Uint8Array(payloadBuf, 6, langLen);
      const lang = new TextDecoder().decode(langBytes);
      const typeStr = type === 0x01 ? "WAV" : type === 0x03 ? "END" : `0x${type.toString(16)}`;
      console.info("[ListenerParse] ★帧★ segIdx=%d type=%s lang=%s payloadBytes=%d", segIdx, typeStr, lang, payloadBuf.byteLength - 6 - langLen);
      results.push({ segIdx, type, lang, payload: payloadBuf.slice(6 + langLen) });
    }
    console.debug("[ListenerParse] 解析完成 frameCount=%d", results.length);
    return results;
  }, []);

  const processFrame = useCallback((segIdx: number, type: number, lang: string, payload: ArrayBuffer) => {
    const myLang = listenLangRef.current;
    const typeStr = type === 0x01 ? "WAV" : type === 0x03 ? "END" : `0x${type.toString(16)}`;
    const procTs = Date.now();
    console.info("[ListenerFrame-IN] ★ procTs=%d type=%s segIdx=%d lang=%s payloadBytes=%d myLang=%s",
      procTs, typeStr, segIdx, lang, payload.byteLength, myLang);

    if (type === 0x03) {
      // ── Bug fix：非当前收听语言的 END 帧必须完全忽略。
      // 后端同时为 zh/en/id 合成 TTS，三路 END 帧会先后到达。
      // 若允许非目标语言的 END 帧执行 delete(wavChunksBySegRef) / add(audioEndedRef)，
      // 会在目标语言 WAV 还未收齐时把已积累的 WAV 块清空、把段标记为"已结束无音频"，
      // 导致 playNext 跳过这一段，出现无声。
      if (lang !== myLang) {
        console.debug("[ListenerFrame-END-SKIP-LANG] segIdx=%d lang=%s != myLang=%s，忽略非目标语言END帧", segIdx, lang, myLang);
        return;
      }

      const parts = wavChunksBySegRef.current.get(segIdx);
      const chunkCount = parts?.length ?? 0;
      const langMatch = true; // lang === myLang 已在上方过滤，此处一定匹配
      const wavReceived = receivedWavCountRef.current.get(segIdx) ?? 0;
      console.info("[ListenerFrame-END] ★★ procTs=%d segIdx=%d lang=%s myLang=%s chunkCount=%d wavReceived=%d %s",
        procTs, segIdx, lang, myLang,
        chunkCount, wavReceived,
        chunkCount === 0 ? "★★ 警告：END到达但0个WAV块已收到，音频将为空！" : "→合并中");
      pendingSegIdxRef.current.delete(segIdx);
      wavChunksBySegRef.current.delete(segIdx);
      // 仅当前监听语言有效时才合并音频
      if (langMatch && parts && parts.length > 0) {
        const total = parts.reduce((a, b) => a + b.length, 0);
        console.info("[ListenerFrame-MERGE-START] segIdx=%d chunkCount=%d totalBytes=%d procTs=%d",
          segIdx, parts.length, total, procTs);
        const merged = new Uint8Array(total);
        let off = 0;
        for (const p of parts) {
          merged.set(p, off);
          off += p.length;
        }
        const buf = merged.buffer.slice(merged.byteOffset, merged.byteOffset + merged.byteLength);
        const beforeCacheSize = audioCacheRef.current.size;
        audioCacheRef.current.set(segIdx, buf);
        const afterCacheSize = audioCacheRef.current.size;
        console.info("[ListenerFrame-MERGED] ★★★ segIdx=%d mergedBytes=%d cacheSize=%d→%d endedAdded=%s procTs=%d",
          segIdx, total, beforeCacheSize, afterCacheSize, !audioEndedRef.current.has(segIdx), procTs);
      } else if (langMatch && chunkCount === 0) {
        console.error("[ListenerFrame-EMPTY-MERGE] ★★★ ★★★ segIdx=%d lang=%s END到达时0个WAV块→此段将永远无声！procTs=%d",
          segIdx, lang, procTs);
      }
      audioEndedRef.current.add(segIdx);
      console.info("[ListenerFrame-AFTER-ADD] endedSet added segIdx=%d totalEnded=%d pendingNow=%s",
        segIdx, audioEndedRef.current.size, [...pendingSegIdxRef.current]);
      adjustPlaybackRate();
      playNextRef.current();
      return;
    }

    if (type !== 0x01) {
      console.debug("[ListenerFrame-SKIP-TYPE] 未知type=0x%s segIdx=%d，忽略", type.toString(16), segIdx);
      return;
    }
    if (lang !== myLang) {
      console.debug("[ListenerFrame-SKIP-LANG] WAV segIdx=%d lang=%s != myLang=%s，忽略", segIdx, lang, myLang);
      return;
    }

    const wavData = new Uint8Array(payload);
    if (wavData.length <= 44) {
      console.warn("[ListenerFrame-WAV-TINY] segIdx=%d wavBytes=%d <= 44（仅含RIFF头或更小），跳过 procTs=%d",
        segIdx, wavData.length, procTs);
      return;
    }

    const wasPending = pendingSegIdxRef.current.has(segIdx);
    pendingSegIdxRef.current.add(segIdx);
    const list = wavChunksBySegRef.current.get(segIdx) ?? [];
    list.push(wavData);
    wavChunksBySegRef.current.set(segIdx, list);

    // 统计：记录收到的 WAV
    const prevCount = receivedWavCountRef.current.get(segIdx) ?? 0;
    receivedWavCountRef.current.set(segIdx, prevCount + 1);

    console.info("[ListenerFrame-WAV-ACC] ★ segIdx=%d chunk#=%d wavBytes=%d ★累计WAV#=%d pending=%s→%s cacheSize=%d procTs=%d",
      segIdx, list.length, wavData.length,
      prevCount + 1,
      wasPending, pendingSegIdxRef.current.has(segIdx),
      audioCacheRef.current.size, procTs);
    adjustPlaybackRate();
    playNextRef.current();
  }, [adjustPlaybackRate]);

  // ─── HTTP 轮询音频 ──────────────────────────────────────────────────────
  const fetchHistoryAudio = useCallback(async (rid: string, lang: string) => {
    try {
      // 加入房间时只拿最新的1-2段音频，避免初始积压过多
      const resp = await fetch(`/api/room/${rid}/audio/history?listenLang=${lang}&limit=2`, {
        cache: "no-store",
      });
      if (!resp.ok) return;
      const buffer = await resp.arrayBuffer();
      if (buffer.byteLength <= 4) return;
      const frames = parsePollResponse(buffer);
      if (frames.length > 0) {
        // 从历史响应中确定加入点：历史帧中最小的 segIdx 即为本次加入时的起始段
        // 低于此值的段是加入前已完成的，canJump 可以安全跳过
        const minHistorySeg = frames.reduce((m, f) => Math.min(m, f.segIdx), frames[0]!.segIdx);
        joinSegIdxRef.current = minHistorySeg;
        console.info("[ListenerHistory] joinSegIdx=%d (from %d history frames)", minHistorySeg, frames.length);
      }
      for (const f of frames) {
        processFrame(f.segIdx, f.type, f.lang, f.payload);
      }
    } catch { /* ok */ }
  }, [parsePollResponse, processFrame]);

  /**
   * 接收 poll 音频帧时的详细日志（用于分析为什么第一句话没播放）
   */
  const pollAudio = useCallback(async () => {
    const rid = pollRoomIdRef.current;
    const lang = pollLangRef.current;
    if (!rid || !lang) return;

    const pollStartTs = performance.now();
    try {
      if (fetchAbortRef.current) fetchAbortRef.current.abort();
      fetchAbortRef.current = new AbortController();
      const resp = await fetch(
        `/api/room/${rid}/audio/poll?listenLang=${lang}&lastPollSeq=${lastPollSeqRef.current}`,
        { cache: "no-store", signal: fetchAbortRef.current.signal }
      );
      if (!resp.ok) {
        console.warn("[ListenerAudio-POLL] HTTP %d room=%s lang=%s", resp.status, rid, lang);
        return;
      }
      const hdr = resp.headers.get("X-Audio-Last-Seq");
      if (hdr != null && hdr !== "") {
        const n = parseInt(hdr, 10);
        if (!Number.isNaN(n)) lastPollSeqRef.current = n;
      }
      const buffer = await resp.arrayBuffer();
      const pollElapsed = performance.now() - pollStartTs;
      const isEmptyPoll =
        buffer.byteLength === 0 ||
        (buffer.byteLength === 4 && new DataView(buffer).getInt32(0, false) === 0);
      if (isEmptyPoll) {
        console.debug("[ListenerAudio-POLL-EMPTY] room=%s lang=%s pollElapsed=%.1fms lastPollSeq=%d",
          rid, lang, pollElapsed, lastPollSeqRef.current);
        return;
      }
      const frames = parsePollResponse(buffer);
      const clientTs = Date.now();
      console.info("[ListenerAudio-POLL] ★★★ room=%s lang=%s bytes=%d frameCount=%d serverSeq=%s lastPollSeq=%d clientTs=%d pollElapsed=%.1fms",
        rid, lang, buffer.byteLength, frames.length, hdr ?? "?", lastPollSeqRef.current, clientTs, pollElapsed);
      
      // 逐帧详细日志
      for (const f of frames) {
        const typeStr = f.type === 0x01 ? "WAV" : f.type === 0x03 ? "END" : `0x${f.type.toString(16)}`;
        console.info("[ListenerAudio-FRAME] ★ segIdx=%d type=%s lang=%s payloadBytes=%d frameTs=%d",
          f.segIdx, typeStr, f.lang, f.payload.byteLength, clientTs);
      }
      
      for (const f of frames) {
        processFrame(f.segIdx, f.type, f.lang, f.payload);
      }
    } catch (e: unknown) {
      if (!(e instanceof Error && e.name === "AbortError")) {
        console.error("[ListenerAudio-POLL-ERROR] room=%s lang=%s: %o", rid, lang, e);
      }
    }
  }, [parsePollResponse, processFrame]);

  // ─── 切换语言 ───────────────────────────────────────────────────────────
  const setListenLangHandler = useCallback((lang: LangCode) => {
    setListenLang(lang);
    listenLangRef.current = lang;
    if (currentSourceRef.current) {
      currentSourceRef.current.onended = null;
      try { currentSourceRef.current.stop(); } catch { /* ok */ }
      currentSourceRef.current = null;
    }
    isPlayingRef.current = false;
    audioCacheRef.current.clear();
    wavChunksBySegRef.current.clear();
    audioEndedRef.current.clear();
    pendingSegIdxRef.current.clear();
    nextSegIdxRef.current = 0;
    joinSegIdxRef.current = 0;
    setPlaybackRate(1.0);
    currentPlaybackRate.current = 1.0;
    if (pollRoomIdRef.current) {
      pollLangRef.current = lang;
      lastPollSeqRef.current = 0;
    }
  }, []);

  // ─── JSON 事件处理 ─────────────────────────────────────────────────────
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

    // ── transcript：ASR 实时流文本，与主持端一致，顶部横幅展示
    if (ev.event === "transcript") {
      const t = ev.text ?? "";
      if ((ev as any).partial) {
        setLiveTranscript(t);
      } else {
        // final transcript 到达后，清空实时横幅（segment 会随后到达替代）
        setLiveTranscript("");
      }
      return;
    }

    // ── segment：ASR 原文，isSourceText 必须为 true；按 segmentTs=batchId、index=段号 写入
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

    // ── translation：译文 isSourceText 必须为 false；与 ASR 同 segmentTs、同 index
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

    if (ev.event === "playback_sync") {
      const idx = (ev as { segIdx?: number }).segIdx;
      if (typeof idx === "number" && idx >= 0) {
        if (currentSourceRef.current) {
          currentSourceRef.current.onended = null;
          try { currentSourceRef.current.stop(); } catch { /* ok */ }
          currentSourceRef.current = null;
        }
        isPlayingRef.current = false;
        nextSegIdxRef.current = idx;
        playNextRef.current();
      }
      return;
    }

    if (ev.event === "tts_skip" || ev.event === "audio_end") return;
  }, []);

  handleEventRef.current = handleEvent;

  // ─── 当前播放段始终滚入可视区（按 batch 内任意 index 匹配，不限于最大段号） ───
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

  // ─── 连接房间 ───────────────────────────────────────────────────────────
  const connectToRoom = useCallback((rid: string, lang: string) => {
    // 新房间/重连：清空所有状态
    setCards([]);
    setPlayingSegIdx(-1);
    setLiveTranscript("");
    setConnected(false);
    setErr(null);

    audioCacheRef.current.clear();
    wavChunksBySegRef.current.clear();
    audioEndedRef.current.clear();
    pendingSegIdxRef.current.clear();
    nextSegIdxRef.current = 0;
    joinSegIdxRef.current = 0;
    isPlayingRef.current = false;
    lastPollSeqRef.current = 0;
    // 清理音频统计
    receivedWavCountRef.current.clear();
    playedSegIdxsRef.current.clear();
    skippedSegIdxsRef.current.clear();
    console.info("[ListenerConnect] 音频统计已重置");
    pollRoomIdRef.current = rid;
    pollLangRef.current = lang;

    // 「加入房间」来自用户点击，在同一次手势里创建/恢复 AudioContext，减轻自动播放策略导致的有数据无声
    try {
      const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (AC) {
        const ctx = playbackCtxRef.current ?? new AC();
        playbackCtxRef.current = ctx;
        ensureMasterGain(ctx);
        void ctx.resume();
      }
    } catch {
      /* ok */
    }

    if (currentSourceRef.current) {
      currentSourceRef.current.onended = null;
      try { currentSourceRef.current.stop(); } catch { /* ok */ }
      currentSourceRef.current = null;
    }

    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
    }

    pollAudio();
    pollIntervalRef.current = setInterval(pollAudio, 300);

    // 启动音频统计定时器（每 10 秒打印一次统计摘要）
    if (audioStatsIntervalRef.current) {
      clearInterval(audioStatsIntervalRef.current);
    }
    audioStatsIntervalRef.current = setInterval(printAudioStats, 10000);

    const wsRoot = resolveBrowserWsRoot();
    const wsUrl = `${wsRoot}/ws/room/${rid}?role=listener&listenLang=${lang}`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      setConnected(true);
      setRoomId(rid);
      fetchHistoryAudio(rid, lang);
    };

    ws.onmessage = (m) => {
      if (typeof m.data === "string") {
        try {
          handleEventRef.current(JSON.parse(m.data) as AsrServerMessage);
        } catch { /* ok */ }
      }
    };

    ws.onerror = () => setErr("连接失败，请检查房间号");
    ws.onclose = () => {
      setConnected(false);
      setLiveTranscript("");
    };
  }, [pollAudio, fetchHistoryAudio, ensureMasterGain]);

  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => () => {
    wsRef.current?.close();
    if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    if (fetchAbortRef.current) fetchAbortRef.current.abort();
    playbackCtxRef.current?.close().catch(() => {});
  }, []);

  // ─── 段落卡片渲染 ──────────────────────────────────────────────────────
  const segmentItem = (card: SegmentCard) => {
    const order = sortedIndexKeys(card.sourceByIndex);
    const sourceLang = batchDisplaySourceLang(card.sourceByIndex);
    const srcLabel = LANG_LABELS[sourceLang] ?? sourceLang;
    const sourceText = joinSourceText(card.sourceByIndex);
    const isSameLang = sourceLang === listenLang;

    // 翻译行：听众语言（与源语言不同时取翻译，相同则显示「同源，不需翻译」）
    const listenLabel = LANG_LABELS[listenLang];
    const listenBySeg = card.transByLang.get(listenLang);
    const listenText = isSameLang
      ? undefined
      : joinTransForListen(card.sourceByIndex, listenBySeg);
    const listenPending = !isSameLang && listenBySeg != null
      ? order.some((idx) => !listenBySeg?.get(idx))
      : false;

    const rowActive = order.includes(playingSegIdx);

    return (
      <div
        key={card.cardKey}
        data-seg={order[0]}
        data-seg-ids={order.join(",")}
        className={`listener-segment ${rowActive ? "listener-segment--playing" : ""}`}
      >
        {/* 源语言行 */}
        <div className="listener-seg-source">
          <span className="listener-seg-lang-badge listener-seg-lang-badge--src">{srcLabel}</span>
          <span className="listener-seg-source-text">{sourceText}</span>
        </div>
        {/* 听众语言行（始终显示） */}
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
        .listener-seg-trans-row-list {
          display: flex;
          flex-direction: column;
          gap: 8px;
          border-top: 1px dashed #e2e8f0;
          padding-top: 12px;
        }
        .listener-seg-trans-row { display: flex; align-items: flex-start; gap: 10px; flex-wrap: wrap; }
        .listener-seg-trans-row--listen { background: #eff6ff; border-radius: 8px; padding: 10px 12px; }
        .listener-seg-trans-row--same-lang { background: #f9fafb; border-radius: 8px; padding: 10px 12px; }
        .listener-seg-trans-body {
          flex: 1;
          min-width: 0;
          font-size: 0.98rem;
          line-height: 1.58;
          word-break: break-word;
          color: #1c1c1e;
        }
        .listener-seg-same-lang-note { font-size: 14px; color: #9ca3af; font-style: italic; }
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
        .listener-speed-badge { padding: 4px 10px; border-radius: 6px; background: #fef3c7; color: #92400e; font-size: 13px; }
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

      {/* ── 顶栏 ─────────────────────────────────────────────── */}
      <div className="listener-header">
        <div className="listener-title">同声传译 - 听众模式</div>
        <div className="listener-status">
          <div className={`status-dot ${connected ? "connected" : ""}`} />
          <span>{connected ? "已连接" : "未连接"}</span>
          {connected && roomId && <span style={{ marginLeft: 8, fontSize: 12, opacity: 0.9 }}>{roomId}</span>}
        </div>
        {connected && (
          <button className={`listener-mute-btn ${audioMuted ? "listener-mute-btn--muted" : ""}`}
            onClick={() => {
              const next = !audioMuted;
              setAudioMuted(next);
              audioMutedRef.current = next;
              const g = playbackMasterGainRef.current;
              if (g) g.gain.value = next ? 0 : 1;
            }}>
            {audioMuted ? "已静音" : "开声音"}
          </button>
        )}
      </div>

      {/* ── 实时流文本横幅（transcript 事件，与主持端一致） ─── */}
      {connected && liveTranscript && (
        <div className="listener-live-transcript">
          <span className="listener-live-label">实时</span>
          <span className="listener-live-text">{liveTranscript}</span>
        </div>
      )}

      {/* ── 控制条 ───────────────────────────────────────────── */}
      <div className="listener-controls">
        <div className="listener-input-group">
          <input className="listener-input"
            type="text"
            placeholder="输入房间号 (如 room-1001)"
            value={inputRoomId}
            onChange={(e) => setInputRoomId(e.target.value)}
            disabled={connected} />
          {connected ? (
            <button className="listener-btn listener-btn-secondary"
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
            <button className="listener-btn listener-btn-primary"
              onClick={() => inputRoomId && connectToRoom(inputRoomId, listenLang)}
              disabled={!inputRoomId.trim()}>
              加入房间
            </button>
          )}
        </div>
        <div className="lang-selector">
          <label>收听语言:</label>
          <select value={listenLang}
            onChange={(e) => setListenLangHandler(e.target.value as LangCode)}
            disabled={connected}>
            {ALL_LANGS.map((l) => <option key={l} value={l}>{LANG_LABELS[l]}</option>)}
          </select>
        </div>
        {playbackRate !== 1.0 && (
          <span className="listener-speed-badge" title="积压超过4段时自动加速，最高2.0x">语速 {playbackRate.toFixed(2)}x（自动加速中）</span>
        )}
      </div>

      {err && <div className="listener-err">{err}</div>}
      {!hostPresent && connected && <div className="no-host-banner">等待主持人开始讲话...</div>}

      {/* ── 内容区 ────────────────────────────────────────────── */}
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

  // 组件卸载时清理定时器
  useEffect(() => {
    return () => {
      if (audioStatsIntervalRef.current) {
        clearInterval(audioStatsIntervalRef.current);
        audioStatsIntervalRef.current = null;
      }
    };
  }, []);
}
