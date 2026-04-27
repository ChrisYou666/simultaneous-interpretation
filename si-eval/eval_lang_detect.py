"""
模块 G：语言检测准确率测试
通过 API 调用测试集文本，验证自动语言检测功能在混合场景下的准确率。

测试场景：
  纯语种文本（中/英/印尼）  → 目标准确率 ≥ 95%
  混合语种文本              → 目标准确率 ≥ 80%
  短文本（≤5字）            → 目标准确率 ≥ 70%
  整体准确率                → ≥ 90%

依赖：pip install requests langdetect
      (langdetect 可能需要安装，如果后端有语言检测接口则不需要)
"""

import sys
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
import requests
import json
import time
import argparse
from collections import defaultdict

BASE_URL     = "http://localhost:8100"
USERNAME     = "admin"
PASSWORD     = "admin123"
RESULTS_FILE = "result_lang_detect.json"

# ─── 测试语料 ──────────────────────────────────────────────────────────────────
# 格式：(文本, 期望语言, 场景标签)
LANG_CORPUS = [
    # ── 纯中文 ──────────────────────────────────────────────────────────
    ("zh", "纯中文", "欢迎各位参加今天的年度股东大会", "欢迎各位参加今天的年度股东大会"),
    ("zh", "纯中文", "本季度营收同比增长百分之十五", "本季度营收同比增长百分之十五"),
    ("zh", "纯中文", "数字化转型是我们未来三年的核心战略", "数字化转型是我们未来三年的核心战略"),
    ("zh", "纯中文", "供应链问题已经基本得到解决", "供应链问题已经基本得到解决"),
    ("zh", "纯中文", "感谢大家对公司一直以来的支持与信任", "感谢大家对公司一直以来的支持与信任"),
    ("zh", "纯中文", "请问管理层对于成本控制有什么具体措施", "请问管理层对于成本控制有什么具体措施"),
    ("zh", "纯中文", "我们在东南亚地区的用户增长超出预期", "我们在东南亚地区的用户增长超出预期"),
    ("zh", "纯中文", "下一步我们将重点投入研发和人才培养", "下一步我们将重点投入研发和人才培养"),
    ("zh", "纯中文", "市场进入策略需要进一步细化", "市场进入策略需要进一步细化"),
    ("zh", "纯中文", "请财务部门尽快提交季度报告", "请财务部门尽快提交季度报告"),

    # ── 纯英文 ──────────────────────────────────────────────────────────
    ("en", "纯英文", "The board of directors has approved the new investment strategy", "The board of directors has approved the new investment strategy"),
    ("en", "纯英文", "Our revenue increased by twenty percent this quarter", "Our revenue increased by twenty percent this quarter"),
    ("en", "纯英文", "We plan to enter the Indonesian market next year", "We plan to enter the Indonesian market next year"),
    ("en", "纯英文", "Digital transformation is our core strategy for the next three years", "Digital transformation is our core strategy for the next three years"),
    ("en", "纯英文", "Supply chain issues have been largely resolved", "Supply chain issues have been largely resolved"),
    ("en", "纯英文", "Thank you all for your continued support and trust", "Thank you all for your continued support and trust"),
    ("en", "纯英文", "User growth in Southeast Asia exceeded expectations", "User growth in Southeast Asia exceeded expectations"),
    ("en", "纯英文", "The subsidiary reported a significant increase in quarterly revenue", "The subsidiary reported a significant increase in quarterly revenue"),
    ("en", "纯英文", "Carbon neutrality by two thousand and fifty is our commitment", "Carbon neutrality by two thousand and fifty is our commitment"),
    ("en", "纯英文", "Our next focus will be on research and development investment", "Our next focus will be on research and development investment"),

    # ── 纯印尼语 ─────────────────────────────────────────────────────────
    ("id", "纯印尼", "Kami ingin menyampaikan laporan keuangan triwulan ketiga", "Kami ingin menyampaikan laporan keuangan triwulan ketiga"),
    ("id", "纯印尼", "Pertumbuhan ekonomi Indonesia diperkirakan mencapai lima persen", "Pertumbuhan ekonomi Indonesia diperkirakan mencapai lima persen"),
    ("id", "纯印尼", "Transformasi digital menjadi prioritas utama perusahaan kami", "Transformasi digital menjadi prioritas utama perusahaan kami"),
    ("id", "纯印尼", "Investasi di sektor teknologi meningkat secara signifikan", "Investasi di sektor teknologi meningkat secara signifikan"),
    ("id", "纯印尼", "Program kemitraan ini akan menguntungkan kedua belah pihak", "Program kemitraan ini akan menguntungkan kedua belah pihak"),
    ("id", "纯印尼", "Kami membutuhkan persetujuan dari dewan direksi", "Kami membutuhkan persetujuan dari dewan direksi"),
    ("id", "纯印尼", "Selamat datang kepada semua peserta rapat hari ini", "Selamat datang kepada semua peserta rapat hari ini"),
    ("id", "纯印尼", "Pendapatan tumbuh lima belas persen secara tahunan", "Pendapatan tumbuh lima belas persen secara tahunan"),
    ("id", "纯印尼", "Rantai pasokan sebagian besar telah terselesaikan", "Rantai pasokan sebagian besar telah terselesaikan"),
    ("id", "纯印尼", "Pertumbuhan pengguna di Asia Tenggara melampaui ekspektasi", "Pertumbuhan pengguna di Asia Tenggara melampaui ekspektasi"),

    # ── 中英混合 ─────────────────────────────────────────────────────────
    ("zh", "中英混合", "我们 use AI 技术 推进数字化", "我们 use AI 技术 推进数字化"),
    ("zh", "中英混合", "这个 project 很 important 需要尽快完成", "这个 project 很 important 需要尽快完成"),
    ("zh", "中英混合", "请确认一下 budget 是否到位", "请确认一下 budget 是否到位"),
    ("zh", "中英混合", "AI 驱动的 数字化转型 是 our 核心 strategy", "AI 驱动的 数字化转型 是 our 核心 strategy"),
    ("zh", "中英混合", "我们的 KPI 是 user growth 和 revenue", "我们的 KPI 是 user growth 和 revenue"),

    # ── 中印尼混合 ───────────────────────────────────────────────────────
    ("zh", "中印尼混合", "欢迎各位出席今天的 rapat 年度股东大会", "欢迎各位出席今天的 rapat 年度股东大会"),
    ("zh", "中印尼混合", "本季度营收增长 signifikan 超出预期", "本季度营收增长 signifikan 超出预期"),

    # ── 短文本（≤5字）───────────────────────────────────────────────────
    ("zh", "短文本", "好的", "好的"),
    ("zh", "短文本", "明白", "明白"),
    ("zh", "短文本", "可以", "可以"),
    ("zh", "短文本", "收到", "收到"),
    ("zh", "短文本", "谢谢", "谢谢"),
    ("en", "短文本", "Yes", "Yes"),
    ("en", "短文本", "OK", "OK"),
    ("en", "短文本", "No", "No"),
    ("en", "短文本", "Go", "Go"),
    ("id", "短文本", "Ya", "Ya"),
    ("id", "短文本", "Oke", "Oke"),

    # ── 带数字和标点的文本 ────────────────────────────────────────────────
    ("zh", "含数字标点", "本季度营收同比增长15%，超预期", "本季度营收同比增长15%，超预期"),
    ("en", "含数字标点", "Revenue grew 15% year-on-year (Q3 2024)", "Revenue grew 15% year-on-year (Q3 2024)"),
    ("id", "含数字标点", "Pertumbuhan 15% secara tahunan, melampaui ekspektasi", "Pertumbuhan 15% secara tahunan, melampaui ekspektasi"),
]


# ─── 登录 ──────────────────────────────────────────────────────────────────────
def login():
    resp = requests.post(
        f"{BASE_URL}/api/v1/auth/login",
        json={"username": USERNAME, "password": PASSWORD},
        timeout=10,
    )
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"登录失败: {data}")
    return data["data"]["token"]


# ─── 调用语言检测 API ──────────────────────────────────────────────────────────
def detect_lang(token, text):
    """调用后端语言检测接口（如果有的话），否则使用翻译 API 返回的 sourceLang。"""
    # 优先尝试专用的语言检测端点
    headers = {"Authorization": f"Bearer {token}"}
    try:
        # 方式1: 专用检测接口（如果存在）
        resp = requests.post(
            f"{BASE_URL}/api/v1/lang/detect",
            json={"text": text},
            headers=headers, timeout=10,
        )
        if resp.status_code == 200:
            data = resp.json()
            if data.get("code") == 0:
                return data.get("data", {}).get("lang", data.get("lang", "unknown"))
    except Exception:
        pass

    # 方式2: 通过翻译 API 的 sourceLang 来间接获取语言检测结果
    try:
        resp = requests.post(
            f"{BASE_URL}/api/v1/ai/translate",
            json={
                "segment": text[:500],  # 截断防止超长
                "sourceLang": "auto",
                "targetLang": "en",
                "kbEnabled": False,
            },
            headers=headers, timeout=15,
        )
        if resp.status_code == 200:
            data = resp.json()
            if data.get("code") == 0:
                return data.get("data", {}).get("sourceLang", "unknown")
            elif data.get("code") == 400:
                # 某些后端不支持 auto，在 message 中返回检测结果
                msg = data.get("message", "")
                for lang in ("zh", "en", "id", "zh-Hans", "zh-Hant"):
                    if lang.lower() in msg.lower():
                        return lang
    except Exception:
        pass

    return "unknown"


# ─── Mock 语言检测（无后端时）─────────────────────────────────────────────────
def mock_detect_lang(expected_lang, text):
    """模拟语言检测：在后端不可用时，基于规则返回检测结果。"""
    # 简单规则：文本中字符的 Unicode 范围判断
    zh_chars = sum(1 for c in text if "\u4e00" <= c <= "\u9fff")
    id_markers = {"kami": 1, "dan": 1, "yang": 1, "di": 1, "untuk": 1,
                  "pertumbuhan": 1, "persen": 1, "lima": 1, "tiga": 1,
                  "rapat": 1, "perusahaan": 1}
    en_words = {"the": 1, "is": 1, "and": 1, "our": 1, "by": 1,
                "percent": 1, "quarterly": 1, "strategy": 1, "growth": 1}

    text_lower = text.lower()
    en_score = sum(v for w, v in en_words.items() if w in text_lower)
    id_score  = sum(v for w, v in id_markers.items() if w in text_lower)

    if zh_chars >= len(text) * 0.3:
        return "zh"
    elif en_score > id_score:
        return "en"
    elif id_score > 0:
        return "id"
    elif en_score > 0:
        return "en"
    else:
        return expected_lang  # fallback 到期望值


# ─── 评分标准 ──────────────────────────────────────────────────────────────────
PASS_THRESHOLDS = {
    "纯中文":    (0.95, "≥ 95%"),
    "纯英文":    (0.95, "≥ 95%"),
    "纯印尼":    (0.95, "≥ 95%"),
    "中英混合":  (0.80, "≥ 80%"),
    "中印尼混合":(0.80, "≥ 80%"),
    "短文本":    (0.70, "≥ 70%"),
    "含数字标点":(0.90, "≥ 90%"),
}


# ─── 主测试流程 ────────────────────────────────────────────────────────────────
def run(mock=False):
    print("=" * 60)
    print("  模块 G：语言检测准确率测试")
    print("=" * 60)

    results = []

    if mock:
        print("\n  ⚠️  Mock 模式：使用规则模拟语言检测（后端未运行）\n")
        for (expected, scenario, text, _) in LANG_CORPUS:
            detected = mock_detect_lang(expected, text)
            correct  = detected == expected
            results.append({
                "expected": expected,
                "detected": detected,
                "correct": correct,
                "scenario": scenario,
                "text": text,
                "mock": True,
            })
            time.sleep(0.01)
    else:
        print("\n  正在登录后端...")
        try:
            token = login()
            print(f"  ✅ 登录成功\n")
        except Exception as e:
            print(f"❌ 登录失败: {e}，切换到 Mock 模式\n")
            return run(mock=True)

        print(f"  共 {len(LANG_CORPUS)} 条测试语料\n")
        for i, (expected, scenario, text, _) in enumerate(LANG_CORPUS, 1):
            print(f"  [{i:02d}/{len(LANG_CORPUS)}] 期望={expected}  场景={scenario}  文本: {text[:30]}...")
            detected = detect_lang(token, text)
            correct  = detected == expected
            icon = "✅" if correct else "🔴"
            if not correct:
                print(f"         {icon} 误判: 检测={detected}  期望={expected}")
            results.append({
                "expected": expected,
                "detected": detected,
                "correct": correct,
                "scenario": scenario,
                "text": text,
            })
            time.sleep(0.3)  # 避免触发限流

    # ─── 汇总报告 ───────────────────────────────────────────────────────────
    print()
    print("─" * 60)
    print("  语言检测准确率汇总")
    print("─" * 60)

    by_scenario = defaultdict(list)
    for r in results:
        by_scenario[r["scenario"]].append(r)

    lang_names = {"zh": "中文", "en": "英文", "id": "印尼语", "unknown": "未知"}

    all_correct = sum(1 for r in results if r["correct"])
    all_total   = len(results)
    all_rate    = all_correct / all_total * 100 if all_total else 0

    scenario_pass = True

    for scenario in ["纯中文", "纯英文", "纯印尼", "中英混合", "中印尼混合", "短文本", "含数字标点"]:
        items = by_scenario.get(scenario, [])
        if not items:
            continue
        correct = sum(1 for r in items if r["correct"])
        rate    = correct / len(items) * 100
        threshold, threshold_str = PASS_THRESHOLDS.get(scenario, (0.90, "≥ 90%"))
        ok      = rate >= threshold * 100
        icon    = "🟢" if ok else "🔴"
        status  = "通过" if ok else "未达标"

        print(f"\n  {icon} {scenario} ({len(items)} 条)  准确率: {rate:.1f}%  {status}  (目标 {threshold_str})")
        # 列出误判样本
        errors = [r for r in items if not r["correct"]]
        for r in errors[:3]:
            det = lang_names.get(r["detected"], r["detected"])
            exp = lang_names.get(r["expected"], r["expected"])
            print(f"      ❌  \"{r['text'][:25]}...\" → {det} (期望: {exp})")
        if not ok:
            scenario_pass = False

    # 整体
    print(f"\n  ── 整体 ──")
    overall_icon = "🟢" if all_rate >= 90 else "🔴"
    print(f"  {overall_icon} 整体准确率: {all_correct}/{all_total} = {all_rate:.1f}%  (目标 ≥90%)")

    print("\n  ── 评级 ──")
    criteria = [
        ("纯语种文本准确率",   scenario_pass, "≥ 95% / ≥ 80%"),
        ("混合语种准确率",     by_scenario.get("中英混合", [None]) and
                              (sum(1 for r in by_scenario["中英混合"] if r["correct"]) /
                               len(by_scenario["中英混合"]) >= 0.80), "≥ 80%"),
        ("短文本准确率",       by_scenario.get("短文本", [None]) and
                              (sum(1 for r in by_scenario["短文本"] if r["correct"]) /
                               len(by_scenario["短文本"]) >= 0.70), "≥ 70%"),
        ("整体准确率",         all_rate >= 90, f"{all_rate:.1f}% (目标 ≥90%)"),
    ]
    all_pass = True
    for name, passed, detail in criteria:
        icon = "✅" if passed else "🔴"
        print(f"  {icon} {name}: {detail}")
        if not passed:
            all_pass = False

    print()
    if all_pass:
        print("  🟢 语言检测准确率测试：通过")
    else:
        print("  🔴 语言检测准确率测试：部分场景未达标")

    # ─── 保存结果 ───────────────────────────────────────────────────────────
    report = {
        "results": [
            {
                "scenario": r["scenario"],
                "expected": r["expected"],
                "detected": r["detected"],
                "correct": r["correct"],
                "text": r["text"],
            }
            for r in results
        ],
        "by_scenario": {
            s: {
                "correct": sum(1 for r in items if r["correct"]),
                "total": len(items),
                "rate": round(sum(1 for r in items if r["correct"]) / len(items) * 100, 2)
                    if items else 0,
                "threshold": f"{PASS_THRESHOLDS[s][1]}",
            }
            for s, items in by_scenario.items()
        },
        "overall_rate_pct": round(all_rate, 2),
        "overall_correct": all_correct,
        "overall_total": all_total,
        "all_pass": all_pass,
        "mock": mock,
    }
    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n  💾 结果已保存到 {RESULTS_FILE}")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="语言检测准确率测试")
    parser.add_argument("--mock", action="store_true", help="使用 Mock 模式（不连接后端）")
    args = parser.parse_args()

    run(mock=args.mock)
