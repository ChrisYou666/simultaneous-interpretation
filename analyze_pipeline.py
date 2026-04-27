#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
from collections import defaultdict, Counter
from datetime import datetime, timezone, timedelta

TZ8 = timezone(timedelta(hours=8))
LOG = 'd:/data/simultaneous-interpretation/backend-java/logs/si-backend.log'
TS_RE = re.compile(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})\+08:00')

def parse_ts(s):
    return datetime.strptime(s, '%Y-%m-%dT%H:%M:%S.%f').replace(tzinfo=TZ8).timestamp() * 1000

def fmt(ms):
    return datetime.fromtimestamp(ms/1000, tz=TZ8).strftime('%H:%M:%S.%f')[:-3]

pcm_enq = []
batch_send = []
backlog = []
trans_done = {}
tts_submit = {}
tts_first = {}
tts_done = {}
tts_429 = defaultdict(list)
enq_audio = []
poll_success = []
asr_markers = Counter()
seg_markers = Counter()

with open(LOG, encoding='utf-8', errors='replace') as f:
    for line in f:
        m = TS_RE.search(line)
        if not m:
            continue
        ts = parse_ts(m.group(1))

        if 'pcm_enqueue' in line:
            pcm_enq.append(ts)

        elif 'upstream_batch_send' in line:
            fm = re.search(r'frames=(\d+)', line)
            sm = re.search(r'sendMs=(\d+)', line)
            if fm:
                batch_send.append((ts, int(fm.group(1)), int(sm.group(1)) if sm else 0))

        elif 'PCM-BACKLOG' in line:
            dm = re.search(r'(\d+)', line[line.find('PCM-BACKLOG'):])
            backlog.append((ts, int(dm.group(1)) if dm else 1))

        if 'AsrWebSocketHandler' in line:
            mk = re.search(r'\[([A-Z][A-Z0-9\-]+)\]', line)
            if mk:
                asr_markers[mk.group(1)] += 1

        mk2 = re.search(r'\[(SEG[\-A-Z]+|TRANS[\-A-Z]+)\]', line)
        if mk2:
            seg_markers[mk2.group(1)] += 1

        if 'TRANS-DONE' in line:
            sm2 = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            if sm2 and lm:
                key = (int(sm2.group(1)), lm.group(1))
                if key not in trans_done:
                    trans_done[key] = ts

        if 'TTS-SUBMIT' in line:
            sm2 = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            pm = re.search(r'pending=(\d+)', line)
            am = re.search(r'active=(\d+)', line)
            if sm2 and lm:
                key = (int(sm2.group(1)), lm.group(1))
                tts_submit[key] = {
                    'ts': ts,
                    'pending': int(pm.group(1)) if pm else 0,
                    'active': int(am.group(1)) if am else 0
                }

        if 'TTS-FIRST-CHUNK' in line:
            sm2 = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            dm = re.search(r'ttsDelay=(\d+)', line)
            if sm2 and lm:
                key = (int(sm2.group(1)), lm.group(1))
                tts_first[key] = {'ts': ts, 'delay': int(dm.group(1)) if dm else 0}

        if 'TTS-DONE' in line:
            sm2 = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            if sm2 and lm:
                key = (int(sm2.group(1)), lm.group(1))
                tts_done[key] = ts

        if 'TTS-429' in line:
            sm2 = re.search(r'segIdx=(\d+)', line)
            lm = re.search(r'tgtLang=(\w+)', line)
            if sm2 and lm:
                key = (int(sm2.group(1)), lm.group(1))
                tts_429[key].append(ts)

        if 'AudioController-ENQ' in line:
            range_m = re.search(r'segIdx=-?\d+->(\d+)', line)
            lm = re.search(r'listenLang=(\w+)', line)
            tm = re.search(r'frameType=(\w+)', line)
            if range_m and lm and tm:
                enq_audio.append((ts, int(range_m.group(1)), lm.group(1), tm.group(1)))

        if 'AudioPoll-SUCCESS' in line:
            fm = re.search(r'(\d+)\u5e27', line)
            if not fm:
                fm = re.search(r'^\S+.*?INFO.*?(\d+)\w* roomId', line)
            sb = re.search(r'size=(\d+)B', line)
            frames_m = re.search(r'(\d+)\D{1,6}roomId', line)
            poll_success.append((ts, int(frames_m.group(1)) if frames_m else 0,
                                 int(sb.group(1)) if sb else 0))

# ======================== REPORT ========================
print('=' * 60)
print('1. PCM BACKLOG')
print('=' * 60)
total_enq = len(pcm_enq)
total_sent = sum(f for _, f, _ in batch_send)
print(f'  Enqueued: {total_enq} frames')
print(f'  Sent:     {total_sent} frames in {len(batch_send)} batches')
print(f'  Delta:    {total_enq - total_sent} frames')
print(f'  BACKLOG alerts: {len(backlog)}')

enq_per_sec = Counter(int(t / 1000) for t in pcm_enq)
dep_per_sec = Counter()
for ts, frames, _ in batch_send:
    dep_per_sec[int(ts / 1000)] += frames

all_secs = sorted(set(enq_per_sec) | set(dep_per_sec))
cumul = 0
peak = 0
for s in all_secs:
    cumul += enq_per_sec.get(s, 0) - dep_per_sec.get(s, 0)
    peak = max(peak, cumul)
print(f'  Peak cumulative backlog: {peak} frames')

send_delays = [d for _, _, d in batch_send]
if send_delays:
    sd = sorted(send_delays)
    N = len(sd)
    slow = sum(1 for d in sd if d >= 500)
    print(f'  sendBinary: p50={sd[N//2]}ms  p90={sd[int(N*0.9)]}ms  max={max(sd)}ms  >=500ms:{slow}({slow*100//N}%)')
if backlog:
    bvals = [v for _, v in backlog]
    print(f'  Backlog depth: max={max(bvals)} avg={sum(bvals)/len(bvals):.1f} batches')

print()
print('=' * 60)
print('2. ASR / SEGMENT')
print('=' * 60)
print(f'  ASR log markers: {dict(asr_markers.most_common(15))}')
print(f'  Seg/Trans markers: {dict(seg_markers.most_common(20))}')
trans_segs = sorted(set(seg for seg, _ in trans_done))
langs_used = sorted(set(lang for _, lang in trans_done))
print(f'  Translation done: {len(trans_done)} (seg,lang) pairs  |  {len(trans_segs)} segs  |  range: {min(trans_segs) if trans_segs else "?"}-{max(trans_segs) if trans_segs else "?"}')
for lang in langs_used:
    cnt = sum(1 for _, l in trans_done if l == lang)
    print(f'    [{lang}] {cnt} segments translated')

print()
print('=' * 60)
print('3. TTS PIPELINE')
print('=' * 60)
submit_segs = sorted(set(seg for seg, _ in tts_submit))
print(f'  TTS Submit:      {len(tts_submit)} (seg,lang)  segs {min(submit_segs) if submit_segs else "?"}-{max(submit_segs) if submit_segs else "?"}')
print(f'  TTS First-chunk: {len(tts_first)}')
print(f'  TTS Done:        {len(tts_done)}')
print(f'  TTS 429:         {sum(len(v) for v in tts_429.values())} hits on {len(tts_429)} (seg,lang)')
by_lang_429 = Counter(l for _, l in tts_429)
print(f'    By lang: {dict(by_lang_429)}')
max_pend = max((v['pending'] for v in tts_submit.values()), default=0)
print(f'  Max pending queue: {max_pend}')
if max_pend > 5:
    top5 = sorted(tts_submit.items(), key=lambda x: -x[1]['pending'])[:5]
    for (seg, lang), v in top5:
        print(f'    seg={seg} lang={lang} pending={v["pending"]} active={v["active"]}')
print()
for lang in ['zh', 'en', 'id']:
    delays = sorted(v['delay'] for k, v in tts_first.items() if k[1] == lang and v['delay'] > 0)
    if delays:
        N = len(delays)
        slow3 = sum(1 for d in delays if d >= 3000)
        print(f'  [{lang}] first-chunk: n={N}  p50={delays[N//2]}ms  p90={delays[int(N*0.9)]}ms  max={max(delays)}ms  >=3s:{slow3}({slow3*100//N}%)')

print()
print('=' * 60)
print('4. AUDIO DELIVERY TO FRONTEND')
print('=' * 60)
enq_langs = Counter(lang for _, _, lang, _ in enq_audio)
enq_types = Counter(t for _, _, _, t in enq_audio)
print(f'  AudioController-ENQ total: {len(enq_audio)}')
print(f'    By lang: {dict(enq_langs)}')
print(f'    By type: {dict(enq_types)}')
enq_segs = sorted(set(seg for _, seg, _, _ in enq_audio))
if enq_segs:
    print(f'    Seg range: {min(enq_segs)}-{max(enq_segs)}')

wav_set = set((seg, lang) for _, seg, lang, t in enq_audio if t == 'WAV')
end_set = set((seg, lang) for _, seg, lang, t in enq_audio if t == 'END')
wav_no_end = wav_set - end_set
end_no_wav = end_set - wav_set
print(f'  WAV frames stored: {len(wav_set)}')
print(f'  END frames stored: {len(end_set)}')
if wav_no_end:
    print(f'  WAV without END (in-flight/incomplete): {sorted(wav_no_end)[:15]}')
if end_no_wav:
    print(f'  END without WAV (anomaly): {sorted(end_no_wav)[:15]}')

print()
print(f'  AudioPoll-SUCCESS responses: {len(poll_success)}')
if poll_success:
    ts_list = sorted(t for t, _, _ in poll_success)
    total_frames_polled = sum(f for _, f, _ in poll_success)
    total_bytes_polled = sum(b for _, _, b in poll_success)
    print(f'    Time range: {fmt(ts_list[0])} ~ {fmt(ts_list[-1])}')
    print(f'    Total frames polled: {total_frames_polled}')
    print(f'    Total bytes polled:  {total_bytes_polled}')

    # Check: how many unique (seg, lang) pairs were polled vs stored
    polled_segs = set()
    with open(LOG, encoding='utf-8', errors='replace') as f:
        for line in f:
            if 'AudioPoll-FRAME' in line:
                sm2 = re.search(r'segIdx=(\d+)', line)
                rm = re.search(r'roomId=(\S+)', line)
                if sm2:
                    polled_segs.add(int(sm2.group(1)))
    print(f'    Unique segIdxs delivered via poll: {sorted(polled_segs)}')
else:
    print('  *** NO AudioPoll-SUCCESS - frontend may not be polling ***')

print()
print('=' * 60)
print('5. END-TO-END: TTS->AudioController->Poll coverage')
print('=' * 60)
tts_done_set = set(tts_done.keys())
enq_wav_set = set((seg, lang) for _, seg, lang, t in enq_audio if t == 'WAV')
enq_end_set = set((seg, lang) for _, seg, lang, t in enq_audio if t == 'END')
tts_not_enqueued = tts_done_set - enq_wav_set
enq_not_done = enq_wav_set - tts_done_set
print(f'  TTS done:         {len(tts_done_set)} (seg,lang)')
print(f'  ENQ (WAV):        {len(enq_wav_set)} (seg,lang)')
print(f'  TTS done but NOT in AudioController: {len(tts_not_enqueued)}')
if tts_not_enqueued:
    print(f'    Examples: {sorted(tts_not_enqueued)[:10]}')
print(f'  ENQ without TTS-DONE: {len(enq_not_done)}')
if enq_not_done:
    print(f'    Examples: {sorted(enq_not_done)[:10]}')
