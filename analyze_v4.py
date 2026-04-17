#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
from datetime import datetime, timezone, timedelta
from collections import defaultdict, Counter

LOG_FILE = r'd:\data\simultaneous-interpretation\backend-java\logs\si-backend.log'
TZ8 = timezone(timedelta(hours=8))
TS_RE = re.compile(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})\+08:00')

def parse_ts(s):
    return int(datetime.strptime(s, '%Y-%m-%dT%H:%M:%S.%f').replace(tzinfo=TZ8).timestamp() * 1000)

def fmt_ts(ms):
    return datetime.fromtimestamp(ms/1000, tz=TZ8).strftime('%H:%M:%S.%f')[:-3]

sessions = {}
pcm_backlog = []
tts_429 = defaultdict(list)
tts_first_chunk = {}
tts_done = {}
tts_submits = {}
trans_done = {}
asr_finals = {}
batch_events = []
enq_times = []

with open(LOG_FILE, 'r', encoding='utf-8', errors='replace') as f:
    for line in f:
        m_ts = TS_RE.search(line)
        if not m_ts:
            continue
        ts_ms = parse_ts(m_ts.group(1))

        rm = re.search(r'roomId=(room-\d+)', line)
        if rm:
            room = rm.group(1)
            if room not in sessions:
                sessions[room] = {'first': ts_ms, 'last': ts_ms}
            else:
                sessions[room]['last'] = ts_ms

        if '[PCM-BACKLOG]' in line:
            dm = re.search(r'(\d+)\s*批', line)
            pcm_backlog.append((ts_ms, int(dm.group(1)) if dm else 1))

        elif '[LAT] upstream_batch_send' in line or '[LAT] upstream_send_binary' in line:
            sm = re.search(r'sendMs=(\d+)', line)
            fm = re.search(r'frames=(\d+)', line)
            dur = int(sm.group(1)) if sm else 0
            frames = int(fm.group(1)) if fm else 1
            batch_events.append((ts_ms, dur, frames))

        elif '[LAT] pcm_enqueue' in line:
            wm = re.search(r'wallMs=(\d+)', line)
            if wm:
                enq_times.append(int(wm.group(1)))

        elif '[SEG-CUT]' in line or '[SEG-FLUSH]' in line or '[SEG-FINAL]' in line or '[SEG-EMIT]' in line:
            sm = re.search(r'segIdx=(\d+)', line)
            if sm:
                seg = int(sm.group(1))
                if seg not in asr_finals:
                    asr_finals[seg] = ts_ms

        elif '[TRANS-DONE]' in line or '[TRANSLATE-DONE]' in line:
            sm = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            if sm and lm:
                key = (int(sm.group(1)), lm.group(1))
                if key not in trans_done:
                    trans_done[key] = ts_ms

        elif '[TTS-SUBMIT]' in line:
            sm = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            am = re.search(r'active=(\d+)/(\d+)', line)
            pm = re.search(r'pending=(\d+)', line)
            if sm and lm:
                key = (int(sm.group(1)), lm.group(1))
                tts_submits[key] = {
                    'ts': ts_ms,
                    'active': int(am.group(1)) if am else 0,
                    'pending': int(pm.group(1)) if pm else 0
                }

        elif '[TTS-FIRST-CHUNK]' in line:
            sm = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            dm = re.search(r'ttsDelay=(\d+)ms', line)
            if sm and lm:
                key = (int(sm.group(1)), lm.group(1))
                tts_first_chunk[key] = {'ts': ts_ms, 'delay_ms': int(dm.group(1)) if dm else 0}

        elif '[TTS-DONE]' in line:
            sm = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            dm = re.search(r'total=(\d+)ms', line)
            if sm and lm:
                key = (int(sm.group(1)), lm.group(1))
                tts_done[key] = {'ts': ts_ms, 'total_ms': int(dm.group(1)) if dm else 0}

        elif '[TTS-429]' in line:
            sm = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            am = re.search(r'attempt=(\d+)', line)
            if sm and lm:
                key = (int(sm.group(1)), lm.group(1))
                tts_429[key].append(int(am.group(1)) if am else 1)

LANGS = ['zh', 'en', 'id']

# ===== 总体 =====
print("=" * 68)
print("日志总体概况")
print("=" * 68)
if sessions:
    first_ts = min(s['first'] for s in sessions.values())
    last_ts2 = max(s['last'] for s in sessions.values())
    print("  时间范围: %s ~ %s" % (fmt_ts(first_ts), fmt_ts(last_ts2)))
    for room, s in sorted(sessions.items()):
        dur = (s['last'] - s['first']) // 1000
        print("  %s: %s ~ %s  时长 %ds" % (room, fmt_ts(s['first']), fmt_ts(s['last']), dur))
print("  ASR 切段总数: %d" % len(asr_finals))
print("  翻译完成条目: %d" % len(trans_done))
print("  TTS提交/首包/完成: %d / %d / %d" % (len(tts_submits), len(tts_first_chunk), len(tts_done)))

# ===== 1. PCM =====
print()
print("=" * 68)
print("1. PCM 积压")
print("=" * 68)
print("  入队总帧数: %d" % len(enq_times))
total_sent = sum(e[2] for e in batch_events)
print("  发送总帧数: %d  (批次 %d)" % (total_sent, len(batch_events)))
print("  未发帧数: %d" % (len(enq_times) - total_sent))
print("  PCM-BACKLOG 告警: %d 次" % len(pcm_backlog))

if batch_events:
    durs = sorted([e[1] for e in batch_events])
    N = len(durs)
    print("  sendBinary: avg=%.1fms  p50=%dms  p90=%dms  p95=%dms  max=%dms" % (
        sum(durs)/N, durs[N//2], durs[int(N*0.9)], durs[int(N*0.95)], max(durs)))
    slow = sum(1 for d in durs if d >= 500)
    print("  >=500ms: %d次 (%d%%)" % (slow, slow*100//N if N else 0))

if enq_times and batch_events:
    enq_per_sec = Counter(t // 1000 for t in enq_times)
    dep_per_sec = Counter(e[0] // 1000 for e in batch_events for _ in range(e[2]))
    all_secs = sorted(set(enq_per_sec) | set(dep_per_sec))
    cumul = 0
    max_cumul = 0
    notable = []
    for s2 in all_secs:
        enq = enq_per_sec.get(s2, 0)
        dep = dep_per_sec.get(s2, 0)
        cumul += enq - dep
        max_cumul = max(max_cumul, cumul)
        if abs(enq - dep) > 3 or abs(cumul) > 5:
            notable.append((s2, enq, dep, enq-dep, cumul))
    print("  最大累计积压: %d 帧" % max_cumul)
    if notable:
        print("  积压时间线（有波动的时段）:")
        print("    %12s  %5s  %5s  %5s  %5s" % ("时间", "入队", "出队", "净差", "累计"))
        for s2, enq, dep, delta, cumul in notable[:15]:
            dt_s = datetime.fromtimestamp(s2, tz=TZ8).strftime('%H:%M:%S')
            mark = " << 严重" if cumul > 50 else (" < 积压" if cumul > 20 else "")
            print("    %12s  %5d  %5d  %+5d  %5d%s" % (dt_s, enq, dep, delta, cumul, mark))
        if len(notable) > 15:
            print("    ... 共%d个波动时段" % len(notable))
    else:
        print("  积压时间线: 全程无明显积压")

if pcm_backlog:
    depths = [d for _, d in pcm_backlog]
    print("  积压批深度: max=%d  avg=%.1f" % (max(depths), sum(depths)/len(depths)))

# ===== 2. 翻译 =====
print()
print("=" * 68)
print("2. 翻译积压")
print("=" * 68)
segs = sorted(asr_finals.keys())
if segs:
    print("  已切段范围: seg %d ~ %d  共 %d 段" % (min(segs), max(segs), len(segs)))
    trans_delays = defaultdict(list)
    missing = defaultdict(list)
    for seg in segs:
        cut_ts = asr_finals[seg]
        for lang in LANGS:
            t = trans_done.get((seg, lang))
            if t:
                trans_delays[lang].append(t - cut_ts)
            else:
                missing[lang].append(seg)
    for lang in LANGS:
        delays = sorted(trans_delays[lang])
        N = len(delays)
        if N == 0:
            print("  [%s] 无翻译记录" % lang)
            continue
        slow = sum(1 for d in delays if d > 5000)
        print("  [%s] 完成 %d 条  p50=%dms  p90=%dms  max=%dms  >5s:%d(%d%%)" % (
            lang, N, delays[N//2], delays[int(N*0.9)], max(delays),
            slow, slow*100//N if N else 0))
    for lang in LANGS:
        if missing[lang]:
            print("  [%s] 缺失翻译: %d 段  例如 %s" % (lang, len(missing[lang]), missing[lang][:5]))

# ===== 3. TTS =====
print()
print("=" * 68)
print("3. TTS 积压与限流")
print("=" * 68)
total_429_cnt = sum(len(v) for v in tts_429.values())
print("  429 总次数: %d" % total_429_cnt)
if total_429_cnt > 0:
    lang_429 = Counter(k[1] for k in tts_429.keys())
    for lang, cnt in sorted(lang_429.items()):
        print("  [%s]: %d 个 segment 被限流" % (lang, cnt))
    times_429 = sorted([tts_submits[k]['ts'] for k in tts_429 if k in tts_submits])
    if times_429:
        print("  集中时段: %s ~ %s" % (fmt_ts(times_429[0]), fmt_ts(times_429[-1])))
else:
    print("  429 情况: 无")

max_pending = max((v.get('pending', 0) for v in tts_submits.values()), default=0)
print("  最大 pending 队列: %d" % max_pending)
if max_pending > 5:
    top = sorted(tts_submits.items(), key=lambda x: -x[1].get('pending', 0))[:5]
    for (seg, lang), v in top:
        print("    seg=%d lang=%s pending=%d active=%d" % (seg, lang, v.get('pending',0), v.get('active',0)))

print()
for lang in LANGS:
    delays = sorted([v['delay_ms'] for k, v in tts_first_chunk.items()
                     if k[1] == lang and v['delay_ms'] > 0])
    if not delays:
        print("  [%s] TTS首包: 无数据" % lang)
        continue
    N = len(delays)
    slow = sum(1 for d in delays if d >= 3000)
    print("  [%s] TTS首包 n=%d  p50=%dms  p90=%dms  p95=%dms  max=%dms  >=3s:%d(%d%%)" % (
        lang, N, delays[N//2], delays[int(N*0.9)], delays[int(N*0.95)], max(delays),
        slow, slow*100//N if N else 0))

print()
print("=" * 68)
print("4. 最高延迟段明细（TTS首包 >= 5000ms）")
print("=" * 68)
slow_segs = [(k, v) for k, v in tts_first_chunk.items() if v['delay_ms'] >= 5000]
slow_segs.sort(key=lambda x: -x[1]['delay_ms'])
if slow_segs:
    print("  %4s  %4s  %8s  %8s  %8s  备注" % ("seg", "lang", "TTS延迟", "submit_t", "429"))
    for (seg, lang), v in slow_segs[:20]:
        sub = tts_submits.get((seg, lang), {})
        r429 = tts_429.get((seg, lang), [])
        cut = asr_finals.get(seg, 0)
        trans_t = trans_done.get((seg, lang))
        trans_delay = "%dms" % (trans_t - cut) if trans_t and cut else "  ?"
        print("  %4d  %4s  %6dms  trans=%s  429x%d" % (
            seg, lang, v['delay_ms'], trans_delay, len(r429)))
else:
    print("  无 TTS 首包 >= 5s 的段")
