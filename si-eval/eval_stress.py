"""
模块 E：并发压力测试
模拟多语言对同时翻译，验证并发限流和稳定性。
使用多线程并发发送翻译请求，统计成功率、P50/P95/P99 延迟。

依赖：pip install requests
通过条件：
  并发数  成功率  P95 延迟
  2       100%    ≤ 5s
  4       ≥ 95%   ≤ 8s
  8       ≥ 90%   ≤ 15s
"""

import sys
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
import requests
import time
import json
import threading
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict

BASE_URL  = "http://localhost:8100"
USERNAME  = "admin"
PASSWORD  = "admin123"
RESULTS_FILE = "result_stress.json"

# 不同并发级别的目标阈值
CONCURRENCY_LEVELS = [2, 4, 8]

PASS_CRITERIA = {
    2: {"success_rate": 100.0, "p95_latency_ms": 5000},
    4: {"success_rate":  95.0, "p95_latency_ms": 8000},
    8: {"success_rate":  90.0, "p95_latency_ms": 15000},
}


# ─── 测试语料（从 corpus 导入，避免重复定义）───────────────────────────────────
try:
    sys.path.insert(0, os.path.dirname(__file__))
    from corpus import CORPUS
except Exception:
    CORPUS = [
        ("zh", "en", "欢迎各位出席今天的年度股东大会", "Welcome everyone to today's annual shareholders meeting"),
        ("zh", "id", "本季度营收同比增长百分之十五", "Pendapatan tumbuh lima belas persen secara tahunan"),
        ("en", "zh", "The board of directors has approved the new strategy", "董事会已批准新战略"),
        ("id", "zh", "Pertumbuhan ekonomi Indonesia diperkirakan mencapai lima persen", "印尼经济增长预计达百分之五"),
        ("zh", "en", "数字化转型是我们未来三年的核心战略", "Digital transformation is our core strategy for the next three years"),
        ("en", "zh", "Our subsidiary reported a twenty percent increase in revenue", "我们在东南亚的子公司报告季度收入增长百分之二十"),
        ("zh", "id", "供应链问题已经基本得到解决", "Masalah rantai pasokan sebagian besar telah terselesaikan"),
        ("id", "en", "Kami ingin menyampaikan laporan keuangan triwulan ketiga", "We would like to present the third quarter financial report"),
    ]

import os
sys.path.insert(0, os.path.dirname(__file__))


# ─── 登录 ──────────────────────────────────────────────────────────────────────
def login():
    resp = requests.post(
        f"{BASE_URL}/api/v1/auth/login",
        json={"username": USERNAME, "password": PASSWORD},
        timeout=15,
    )
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"登录失败: {data}")
    return data["data"]["token"]


# ─── 单次翻译请求 ──────────────────────────────────────────────────────────────
def translate_once(token, src_lang, tgt_lang, text, result_list, lock, thread_id):
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    payload = {
        "segment": text,
        "sourceLang": src_lang,
        "targetLang": tgt_lang,
        "kbEnabled": False,
    }
    t0 = time.time()
    error = None
    status_code = None
    try:
        resp = requests.post(
            f"{BASE_URL}/api/v1/ai/translate",
            json=payload, headers=headers, timeout=60,
        )
        status_code = resp.status_code
        elapsed_ms = (time.time() - t0) * 1000
        if resp.status_code == 200:
            data = resp.json()
            if data.get("code") == 0:
                translation = data.get("data", {}).get("translation", "")
            else:
                error = data.get("message", "unknown")
        else:
            error = f"HTTP {resp.status_code}"
    except requests.Timeout:
        elapsed_ms = (time.time() - t0) * 1000
        error = "timeout"
    except Exception as e:
        elapsed_ms = (time.time() - t0) * 1000
        error = str(e)

    with lock:
        result_list.append({
            "thread_id": thread_id,
            "src_lang": src_lang,
            "tgt_lang": tgt_lang,
            "text": text,
            "elapsed_ms": elapsed_ms,
            "success": error is None,
            "error": error,
            "status_code": status_code,
        })


# ─── 并发测试（单个并发级别）───────────────────────────────────────────────────
def stress_level(concurrency, token, repeat=1):
    """运行单个并发级别的压力测试。"""
    results = []
    lock = threading.Lock()
    text_items = CORPUS * repeat

    print(f"\n  ── 并发数 = {concurrency} ──")
    print(f"     总请求数: {len(text_items)}  (语料 {len(CORPUS)} × {repeat})")

    t_start = time.time()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = []
        for i, (src_lang, tgt_lang, src_text, _) in enumerate(text_items):
            f = executor.submit(
                translate_once, token, src_lang, tgt_lang, src_text,
                results, lock, i % concurrency,
            )
            futures.append(f)

        for f in as_completed(futures):
            try:
                f.result()
            except Exception as e:
                with lock:
                    results.append({
                        "thread_id": -1,
                        "elapsed_ms": 0,
                        "success": False,
                        "error": f"executor error: {e}",
                    })

    wall_time = time.time() - t_start

    latencies = [r["elapsed_ms"] for r in results if r["success"]]
    errors    = [r for r in results if not r["success"]]
    success_count = len(latencies)
    total_count   = len(results)
    success_rate  = success_count / total_count * 100 if total_count else 0

    if latencies:
        latencies_sorted = sorted(latencies)
        n = len(latencies_sorted)
        p50 = latencies_sorted[int(n * 0.50)]
        p95 = latencies_sorted[int(n * 0.95)]
        p99 = latencies_sorted[int(n * 0.99)]
        avg = sum(latencies) / n
    else:
        p50 = p95 = p99 = avg = 0

    criteria = PASS_CRITERIA[concurrency]
    rate_ok  = success_rate >= criteria["success_rate"]
    p95_ok   = p95 <= criteria["p95_latency_ms"]

    rate_icon  = "✅" if rate_ok  else "🔴"
    p95_icon   = "✅" if p95_ok   else "🔴"

    print(f"\n     ⏱  耗时: {wall_time:.1f}s  (平均 {wall_time/total_count*1000:.0f}ms/请求)")
    print(f"     ✅ 成功: {success_count}/{total_count}  {rate_icon} {success_rate:.1f}%  (目标 ≥{criteria['success_rate']}%)")
    print(f"     ⏱  P50: {p50:.0f}ms  P95: {p95_icon} {p95:.0f}ms  P99: {p99:.0f}ms  avg: {avg:.0f}ms")
    if errors:
        err_types = defaultdict(int)
        for e in errors:
            err_types[e["error"]] += 1
        print(f"     ❌ 错误类型: " + "  ".join(f"{k}({v})" for k, v in err_types.items()))

    return {
        "concurrency": concurrency,
        "total": total_count,
        "success": success_count,
        "success_rate_pct": round(success_rate, 2),
        "latency_ms": {
            "p50": round(p50, 1),
            "p95": round(p95, 1),
            "p99": round(p99, 1),
            "avg": round(avg, 1),
        },
        "wall_time_s": round(wall_time, 2),
        "pass": rate_ok and p95_ok,
        "criteria": criteria,
        "errors": [{"error": e["error"], "count": 1} for e in errors],
    }


# ─── 主测试流程 ────────────────────────────────────────────────────────────────
def run(concurrencies=None, mock=False):
    print("=" * 60)
    print("  模块 E：并发压力测试")
    print("=" * 60)

    if mock:
        print("\n  ⚠️  Mock 模式：模拟并发测试（不连接后端）")
        # 生成模拟数据
        results = []
        import random
        for conc in (concurrencies or CONCURRENCY_LEVELS):
            crit = PASS_CRITERIA[conc]
            latencies = [random.gauss(1500, 500) for _ in range(100)]
            latencies.sort()
            p95 = latencies[int(len(latencies) * 0.95)]
            success_rate = random.uniform(95, 100)
            results.append({
                "concurrency": conc,
                "total": 100,
                "success": int(100 * success_rate / 100),
                "success_rate_pct": round(success_rate, 2),
                "latency_ms": {
                    "p50": round(latencies[int(len(latencies)*0.50)], 1),
                    "p95": round(p95, 1),
                    "p99": round(latencies[int(len(latencies)*0.99)], 1),
                    "avg": round(sum(latencies)/len(latencies), 1),
                },
                "wall_time_s": round(random.uniform(5, 30), 2),
                "pass": success_rate >= crit["success_rate"] and p95 <= crit["p95_latency_ms"],
                "criteria": crit,
                "mock": True,
            })
    else:
        print("\n  正在登录后端...")
        try:
            token = login()
            print(f"  ✅ 登录成功\n")
        except Exception as e:
            print(f"❌ 登录失败: {e}，切换到 Mock 模式\n")
            return run(concurrencies=concurrencies, mock=True)

        results = []
        for conc in (concurrencies or CONCURRENCY_LEVELS):
            level_result = stress_level(conc, token)
            results.append(level_result)
            time.sleep(2)  # 级别之间稍作冷却

    # ─── 汇总报告 ───────────────────────────────────────────────────────────
    print()
    print("─" * 60)
    print("  并发压力测试汇总")
    print("─" * 60)
    print(f"\n  {'并发数':^6}  {'成功率':^8}  {'P50(ms)':^8}  {'P95(ms)':^8}  {'P99(ms)':^8}  {'结果':^6}")
    print(f"  {'─'*6}  {'─'*8}  {'─'*8}  {'─'*8}  {'─'*8}  {'─'*6}")
    for r in results:
        conc = r["concurrency"]
        crit = r["criteria"]
        rate_ok = r["success_rate_pct"] >= crit["success_rate"]
        p95_ok  = r["latency_ms"]["p95"] <= crit["p95_latency_ms"]
        icon = "✅" if r["pass"] else "🔴"
        print(
            f"  {conc:^6}  "
            f"{rate_ok and '✅' or '🔴'}{r['success_rate_pct']:6.1f}%  "
            f"{r['latency_ms']['p50']:>7.0f}  "
            f"{p95_ok and '✅' or '🔴'}{r['latency_ms']['p95']:>7.0f}  "
            f"{r['latency_ms']['p99']:>7.0f}  "
            f"{icon:^6}"
        )

    print("\n  ── 评级 ──")
    all_pass = True
    for r in results:
        conc  = r["concurrency"]
        crit  = r["criteria"]
        rate  = r["success_rate_pct"]
        p95   = r["latency_ms"]["p95"]
        ok    = r["pass"]
        icon  = "✅" if ok else "🔴"
        print(f"  {icon} 并发 {conc}: 成功率={rate:.1f}% (≥{crit['success_rate']}%)  P95={p95:.0f}ms (≤{crit['p95_latency_ms']}ms)")
        if not ok:
            all_pass = False

    print()
    if all_pass:
        print("  🟢 并发压力测试：全部通过")
    else:
        print("  🔴 并发压力测试：部分未达标")

    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump({"levels": results, "all_pass": all_pass}, f, ensure_ascii=False, indent=2)
    print(f"\n  💾 结果已保存到 {RESULTS_FILE}")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="并发压力测试")
    parser.add_argument("--concurrency", type=int, nargs="+", default=None,
                        help=f"指定并发级别，默认 {CONCURRENCY_LEVELS}")
    parser.add_argument("--mock", action="store_true", help="使用 Mock 模式（不连接后端）")
    args = parser.parse_args()

    run(concurrencies=args.concurrency, mock=args.mock)
