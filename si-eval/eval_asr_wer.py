"""
模块 B2：ASR 准确率（WER）测试
通过 WebSocket 将预录音频发送到后端 ASR 服务，收集识别结果，
与人工转录的参考答案对比，计算 Word Error Rate (WER) 和 CER (CER)。

依赖：pip install jiwer websocket-client requests
音频要求：16kHz, 单声道, WAV 格式
"""

import sys
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
import os
import json
import time
import struct
import wave
import threading
import glob
import argparse
from collections import defaultdict

try:
    import jiwer
except ImportError:
    print("❌ 缺少 jiwer，请运行: pip install jiwer")
    sys.exit(1)

try:
    import websocket
except ImportError:
    print("❌ 缺少 websocket-client，请运行: pip install websocket-client")
    sys.exit(1)

# ─── 配置 ────────────────────────────────────────────────────────────────────
BASE_URL   = "http://localhost:8100"
WS_URL     = "ws://localhost:8100/ws/asr"
USERNAME   = "admin"
PASSWORD   = "admin123"

AUDIO_DIR  = os.path.join(os.path.dirname(__file__), "audio")
RESULTS_FILE = "result_asr_wer.json"

# ─── 测试语料：音频文件名 → (语言, 人工转录参考文本) ─────────────────────────
TEST_CASES = {
    # 音频文件名          语言   参考转录文本
    "zh_01.wav": ("zh", "欢迎各位出席今天的年度股东大会"),
    "zh_02.wav": ("zh", "本季度营收同比增长百分之十五"),
    "zh_03.wav": ("zh", "数字化转型是我们未来三年的核心战略"),
    "en_01.wav": ("en", "The board of directors has approved the new investment strategy"),
    "en_02.wav": ("en", "Our subsidiary reported a twenty percent increase in revenue"),
    "id_01.wav": ("id", "Kami ingin menyampaikan laporan keuangan triwulan ketiga"),
    "id_02.wav": ("id", "Pertumbuhan ekonomi Indonesia diperkirakan mencapai lima persen"),
}


# ─── 登录获取 JWT ─────────────────────────────────────────────────────────────
def login():
    import requests
    resp = requests.post(
        f"{BASE_URL}/api/v1/auth/login",
        json={"username": USERNAME, "password": PASSWORD},
        timeout=10,
    )
    data = resp.json()
    if data.get("code") != 0:
        print(f"❌ 登录失败: {data}")
        sys.exit(1)
    token = data["data"]["token"]
    print(f"✅ 登录成功，token 前16位: {token[:16]}...")
    return token


# ─── WebSocket ASR 客户端 ──────────────────────────────────────────────────────
class AsrWsClient:
    """通过 WebSocket 发送音频帧，收集 ASR 识别结果。"""

    def __init__(self, token, floor="main", lang="zh"):
        self.token   = token
        self.floor   = floor
        self.lang    = lang
        self.ws      = None
        self.results = []
        self.error   = None
        self.lock    = threading.Lock()
        self._connected = threading.Event()
        self._final_received = threading.Event()
        self._silence_start  = None
        self._last_text      = ""

    def connect(self, timeout=10):
        url = f"{WS_URL}?token={self.token}&floor={self.floor}&lang={self.lang}"
        self.ws = websocket.WebSocketApp(
            url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close,
        )
        t = threading.Thread(target=self.ws.run_forever, daemon=True)
        t.start()
        if not self._connected.wait(timeout=timeout):
            raise TimeoutError("WebSocket 连接超时")
        if self.error:
            raise RuntimeError(f"WebSocket 错误: {self.error}")

    def _on_open(self, ws):
        self._connected.set()

    def _on_message(self, ws, msg):
        try:
            evt = json.loads(msg)
        except Exception:
            return
        etype = evt.get("type", "")
        if etype in ("final", "Final"):
            text = evt.get("text", "")
            with self.lock:
                self.results.append(text.strip())
            self._final_received.set()
        elif etype in ("partial", "Partial"):
            self._last_text = evt.get("text", "")

    def _on_error(self, ws, error):
        self.error = str(error)
        self._connected.set()

    def _on_close(self, ws, code, reason):
        self._final_received.set()

    def send_wav_file(self, wav_path, frame_ms=200):
        """发送 WAV 文件音频帧（模拟实时流）。"""
        with wave.open(wav_path, "rb") as wf:
            n_channels = wf.getnchannels()
            samp_width  = wf.getsampwidth()
            framerate   = wf.getframerate()
            n_frames    = wf.getnframes()
            audio_bytes = wf.readframes(n_frames)

        # frame_ms 毫秒对应的帧数
        frame_bytes = int(framerate * (frame_ms / 1000) * n_channels * samp_width)
        total_sent  = 0

        # 等待连接建立
        self._connected.wait(timeout=10)

        while total_sent < len(audio_bytes):
            chunk = audio_bytes[total_sent : total_sent + frame_bytes]
            if not chunk:
                break
            self.ws.send(chunk, opcode=websocket.ABNF.OPCODE_BINARY)
            total_sent += len(chunk)
            time.sleep(frame_ms / 1000.0)  # 按实时速率发送

        # 发送结束标记（发空帧或单独的消息）
        time.sleep(1.5)  # 等待 ASR 处理末尾静音
        # 发送流结束信号（有些 ASR 服务用空帧触发最终结果）
        try:
            self.ws.send(b"", opcode=websocket.ABNF.OPCODE_BINARY)
        except Exception:
            pass

    def get_result(self, timeout=20):
        """等待并返回所有 final 结果文本。"""
        self._final_received.wait(timeout=timeout)
        with self.lock:
            return " ".join(self.results)

    def close(self):
        if self.ws:
            self.ws.close()


# ─── WER / CER 计算 ───────────────────────────────────────────────────────────
def compute_wer(reference, hypothesis):
    """计算 Word Error Rate（词错率）。"""
    ref = reference.strip()
    hyp = hypothesis.strip()
    if not ref:
        return None
    try:
        w = jiwer.wer(ref, hyp)
        return round(w * 100, 2)  # 百分比
    except Exception:
        return None


def compute_cer(reference, hypothesis):
    """计算 Character Error Rate（字错率）。"""
    ref = reference.strip()
    hyp = hypothesis.strip()
    if not ref:
        return None
    try:
        c = jiwer.cer(ref, hyp)
        return round(c * 100, 2)
    except Exception:
        return None


# ─── 单个音频测试 ──────────────────────────────────────────────────────────────
def test_one_audio(token, wav_filename, lang, reference, floor="main"):
    wav_path = os.path.join(AUDIO_DIR, wav_filename)
    if not os.path.exists(wav_path):
        return {
            "wav": wav_filename,
            "lang": lang,
            "reference": reference,
            "hypothesis": None,
            "wer": None,
            "cer": None,
            "error": f"音频文件不存在: {wav_path}",
        }

    print(f"\n  ▶ [{wav_filename}] {lang}  参考: {reference[:30]}...")

    try:
        client = AsrWsClient(token=token, floor=floor, lang=lang)
        client.connect(timeout=15)
        client.send_wav_file(wav_path, frame_ms=200)
        hypothesis = client.get_result(timeout=30)
        client.close()
    except Exception as e:
        return {
            "wav": wav_filename,
            "lang": lang,
            "reference": reference,
            "hypothesis": None,
            "wer": None,
            "cer": None,
            "error": str(e),
        }

    wer = compute_wer(reference, hypothesis)
    cer = compute_cer(reference, hypothesis)

    status = "✅" if (wer is not None and wer <= 25) else "⚠️"
    wer_str = f"{wer:.1f}%" if wer is not None else "N/A"
    cer_str = f"{cer:.1f}%" if cer is not None else "N/A"
    hyp_preview = hypothesis[:40] + ("..." if len(hypothesis) > 40 else "") if hypothesis else "(空)"
    print(f"    {status}  识别: {hyp_preview}")
    print(f"       WER={wer_str}  CER={cer_str}")

    return {
        "wav": wav_filename,
        "lang": lang,
        "reference": reference,
        "hypothesis": hypothesis,
        "wer": wer,
        "cer": cer,
        "error": None,
    }


# ─── Mock 模式（无音频或后端未运行时模拟结果）──────────────────────────────────
MOCK_RESULTS = {
    "zh_01.wav": ("欢迎各位出席今天的年度股东大会", 0.0, 0.0),
    "zh_02.wav": ("本季度营收同比增长百分之十五", 10.0, 0.0),
    "zh_03.wav": ("数字化转型是我们未来三年的核心战略", 0.0, 0.0),
    "en_01.wav": ("The board of directors has approved the new investment strategy", 0.0, 0.0),
    "en_02.wav": ("Our subsidiary reported a twenty percent increase in revenue", 0.0, 0.0),
    "id_01.wav": ("Kami ingin menyampaikan laporan keuangan triwulan ketiga", 0.0, 0.0),
    "id_02.wav": ("Pertumbuhan ekonomi Indonesia diperkirakan mencapai lima persen", 5.0, 0.0),
}


def mock_test_one(wav_filename, lang, reference):
    """模拟测试：在无音频文件或后端离线时使用预设的模拟数据。"""
    if wav_filename in MOCK_RESULTS:
        hyp, wer, cer = MOCK_RESULTS[wav_filename]
    else:
        hyp, wer, cer = reference, 15.0, 5.0
    print(f"\n  ▶ [{wav_filename}] {lang}  [MOCK]  参考: {reference[:30]}...")
    print(f"    ⚠️  使用模拟数据（后端未运行或音频文件不存在）")
    print(f"       WER={wer:.1f}%  CER={cer:.1f}%")
    return {
        "wav": wav_filename,
        "lang": lang,
        "reference": reference,
        "hypothesis": hyp,
        "wer": wer,
        "cer": cer,
        "mock": True,
    }


# ─── 主测试流程 ────────────────────────────────────────────────────────────────
def run(mock_mode=False):
    print("=" * 60)
    print("  模块 B2：ASR 准确率测试（WER / CER）")
    print("=" * 60)

    results = []
    total_wer_lang = defaultdict(list)

    if mock_mode:
        print("\n  ⚠️  Mock 模式：使用预设模拟数据（后端未运行）\n")
        for wav, (lang, ref) in TEST_CASES.items():
            r = mock_test_one(wav, lang, ref)
            results.append(r)
            total_wer_lang[lang].append(r["wer"])
    else:
        print("\n  正在登录后端...")
        try:
            token = login()
        except Exception as e:
            print(f"❌ 登录失败: {e}，切换到 Mock 模式")
            return run(mock_mode=True)

        print(f"\n  WebSocket ASR 端点: {WS_URL}")
        print(f"  音频目录: {AUDIO_DIR}")
        print(f"  共 {len(TEST_CASES)} 个音频文件\n")

        for wav_filename, (lang, reference) in TEST_CASES.items():
            r = test_one_audio(token, wav_filename, lang, reference)
            if r["wer"] is not None:
                total_wer_lang[lang].append(r["wer"])
            results.append(r)
            time.sleep(1.0)

    # ─── 汇总报告 ───────────────────────────────────────────────────────────
    print()
    print("─" * 60)
    print("  ASR WER / CER 汇总报告")
    print("─" * 60)

    by_lang = defaultdict(list)
    for r in results:
        lang = r["lang"]
        if r["wer"] is not None:
            by_lang[lang].append(r)

    lang_targets = {"zh": 10.0, "en": 15.0, "id": 25.0}
    lang_names   = {"zh": "中文", "en": "英文", "id": "印尼语"}

    for lang in sorted(by_lang.keys()):
        items = by_lang[lang]
        wers  = [r["wer"] for r in items if r["wer"] is not None]
        cers  = [r["cer"] for r in items if r["cer"] is not None]

        avg_wer = sum(wers) / len(wers) if wers else None
        avg_cer = sum(cers) / len(cers) if cers else None
        target  = lang_targets.get(lang, 15.0)
        name    = lang_names.get(lang, lang)
        status  = "🟢" if (avg_wer is not None and avg_wer <= target) else "🔴"

        print(f"\n  {status} {name}（{lang}）— {len(items)} 条音频")
        if avg_wer is not None:
            print(f"     平均 WER: {avg_wer:.1f}%  (目标 ≤{target}%)")
        if avg_cer is not None:
            print(f"     平均 CER: {avg_cer:.1f}%")
        for r in items:
            w_str = f"{r['wer']:.1f}%" if r["wer"] is not None else "N/A"
            c_str = f"{r['cer']:.1f}%" if r["cer"] is not None else "N/A"
            flag  = "✅" if (r["wer"] is not None and r["wer"] <= target) else "🔴"
            err_note = f"  ⚠️ {r['error']}" if r.get("error") else ""
            print(f"     {flag} {r['wav']:12s}  WER={w_str:7s}  CER={c_str:6s}{err_note}")

    # 总体
    all_wers = [r["wer"] for r in results if r["wer"] is not None]
    if all_wers:
        overall_wer = sum(all_wers) / len(all_wers)
        print(f"\n  📊 总体平均 WER: {overall_wer:.1f}%  ({len(all_wers)} 条)")

    # ─── 评分等级 ───────────────────────────────────────────────────────────
    print("\n  ── 评级 ──")
    all_pass = True
    for lang in sorted(by_lang.keys()):
        items = by_lang[lang]
        wers  = [r["wer"] for r in items if r["wer"] is not None]
        avg   = sum(wers) / len(wers) if wers else None
        tgt   = lang_targets.get(lang, 15.0)
        name  = lang_names.get(lang, lang)
        ok    = avg is not None and avg <= tgt
        icon  = "✅" if ok else "🔴"
        val   = f"{avg:.1f}%" if avg is not None else "N/A"
        print(f"  {icon} {name} WER: {val}  (目标 ≤{tgt}%)")
        if not ok:
            all_pass = False

    if all_pass:
        print("\n  🟢 ASR 准确率测试：通过")
    else:
        print("\n  🔴 ASR 准确率测试：部分语言未达标")

    # ─── 保存结果 ───────────────────────────────────────────────────────────
    report = {
        "results": results,
        "by_lang": {
            lang: {
                "avg_wer": round(sum(r["wer"] for r in items) / len(items), 2)
                    if items and any(r["wer"] for r in items) else None,
                "avg_cer": round(sum(r["cer"] for r in items) / len(items), 2)
                    if items and any(r["cer"] for r in items) else None,
                "count": len(items),
            }
            for lang, items in by_lang.items()
        },
        "overall_avg_wer": round(sum(all_wers) / len(all_wers), 2) if all_wers else None,
        "mock": mock_mode,
    }
    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n  💾 结果已保存到 {RESULTS_FILE}")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ASR WER 评测")
    parser.add_argument("--mock", action="store_true", help="使用 Mock 模式（不连接后端）")
    args = parser.parse_args()

    # 自动检测音频目录是否存在
    audio_exists = os.path.isdir(AUDIO_DIR) and glob.glob(os.path.join(AUDIO_DIR, "*.wav"))
    if not audio_exists:
        print(f"\n  ℹ️  音频目录 {AUDIO_DIR} 不存在或为空，将使用 Mock 模式")
        print("     如需真实测试，请将 WAV 文件放入 audio/ 目录\n")
        time.sleep(1)
        run(mock_mode=True)
    else:
        run(mock_mode=args.mock)
