#!/usr/bin/env python3
"""
Translation Quality Benchmark
Measures BLEU and BERTScore for the simultaneous interpretation system.
Supports: zh↔en, id→zh translation directions.

Usage:
    python scripts/translation_benchmark.py                    # Mock mode
    python scripts/translation_benchmark.py --api-url http://localhost:8080/api/v1/ai/translate

Dependencies:
    pip install sacrebleu bert-score transformers torch

Author: System
Version: 1.0.0
"""

import argparse
import json
import sys
import time
import warnings
from dataclasses import dataclass, asdict
from datetime import datetime
from typing import Optional

warnings.filterwarnings("ignore")

# =============================================================================
# Inline Translation Corpus
# =============================================================================

ZH_TO_EN = [
    {"source": "欢迎各位参加今天的会议", "reference": "Welcome to today's meeting everyone"},
    {"source": "请大家保持安静", "reference": "Please keep quiet"},
    {"source": "会议即将开始", "reference": "The meeting is about to begin"},
    {"source": "感谢大家的参与", "reference": "Thank you all for your participation"},
    {"source": "我们开始今天的议题", "reference": "Let's start today's agenda"},
    {"source": "首先，请允许我介绍今天的演讲嘉宾", "reference": "First, please allow me to introduce today's speakers"},
    {"source": "下面进入问答环节", "reference": "Now let's move to the Q&A session"},
    {"source": "感谢各位的精彩演讲", "reference": "Thank you all for your excellent presentations"},
    {"source": "会议到此结束", "reference": "This concludes today's meeting"},
    {"source": "请各位会后交流", "reference": "Please feel free to network after the meeting"},
    {"source": "我同意这个提议", "reference": "I agree with this proposal"},
    {"source": "请看下一页PPT", "reference": "Please look at the next slide"},
    {"source": "这个问题问得很好", "reference": "That's an excellent question"},
    {"source": "我们有十分钟的休息时间", "reference": "We have a ten minute break"},
    {"source": "让我们继续讨论", "reference": "Let's continue the discussion"},
]

EN_TO_ZH = [
    {"source": "Good morning everyone, let's begin the meeting", "reference": "大家早上好，让我们开始会议"},
    {"source": "I'd like to thank everyone for coming today", "reference": "感谢各位今天的到来"},
    {"source": "Let's move on to the next item on the agenda", "reference": "让我们进入下一个议程"},
    {"source": "Could you please elaborate on that point", "reference": "请您详细说明一下那个观点"},
    {"source": "I have a question regarding the budget", "reference": "我有一个关于预算的问题"},
    {"source": "Let's table this discussion for now", "reference": "让我们暂时搁置这个讨论"},
    {"source": "We need to reschedule this meeting", "reference": "我们需要重新安排这次会议"},
    {"source": "The presentation was very informative", "reference": "这次演讲非常有帮助"},
    {"source": "Please sign the attendance sheet", "reference": "请签到"},
    {"source": "The next conference will be held in Shanghai", "reference": "下次会议将在上海举行"},
    {"source": "I appreciate your feedback", "reference": "感谢您的反馈"},
    {"source": "Let's vote on this proposal", "reference": "让我们对这个提案进行投票"},
    {"source": "We have run out of time", "reference": "我们的时间用完了"},
    {"source": "Please mute your microphone", "reference": "请静音"},
    {"source": "The meeting is adjourned", "reference": "会议结束"},
]

ID_TO_ZH = [
    {"source": "Selamat pagi, mari kita mulai diskusi", "reference": "早上好，让我们开始讨论"},
    {"source": "Terima kasih atas partisipasi Anda", "reference": "感谢您的参与"},
    {"source": "SilakanAjukan pertanyaan", "reference": "请提问"},
    {"source": "Kita akan istirahat selama sepuluh menit", "reference": "我们将休息十分钟"},
    {"source": "Presentasi tersebut sangat bermanfaat", "reference": "这次演讲非常有价值"},
    {"source": "Silakan lanjutkan dengan topik berikutnya", "reference": "请继续下一个主题"},
    {"source": "Saya ingin menambahkan beberapa poin", "reference": "我想补充几点"},
    {"source": "Bisakah Anda ulangi pertanyaannya", "reference": "请您重复一下问题"},
    {"source": "Rapat akan berakhir dalam lima menit", "reference": "会议将在五分钟后结束"},
    {"source": "Sampai jumpa di pertemuan berikutnya", "reference": "下次会议见"},
]

TECHNICAL_TERMINOLOGY = [
    {"source": "人工智能技术正在快速发展", "reference": "AI technology is developing rapidly"},
    {"source": "机器学习是人工智能的核心", "reference": "Machine learning is the core of AI"},
    {"source": "深度学习在图像识别中应用广泛", "reference": "Deep learning is widely used in image recognition"},
    {"source": "神经网络可以处理复杂的模式", "reference": "Neural networks can handle complex patterns"},
    {"source": "自然语言处理使机器理解人类语言", "reference": "NLP enables machines to understand human language"},
    {"source": "The neural network architecture uses transformer models", "reference": "该神经网络架构使用Transformer模型"},
    {"source": "Machine learning algorithms require large datasets", "reference": "机器学习算法需要大量数据集"},
    {"source": "Deep learning has revolutionized computer vision", "reference": "深度学习革新了计算机视觉领域"},
    {"source": "Natural language processing applications include translation", "reference": "自然语言处理的应用包括翻译"},
    {"source": "AI and machine learning are transforming industries", "reference": "人工智能和机器学习正在改变各行业"},
]

# Glossary terms for consistency testing
GLOSSARY = [
    ("AI", "人工智能"),
    ("machine learning", "机器学习"),
    ("deep learning", "深度学习"),
    ("neural network", "神经网络"),
    ("natural language processing", "自然语言处理"),
    ("NLP", "自然语言处理"),
    ("transformer", "Transformer"),
    ("computer vision", "计算机视觉"),
]

# Mock translations for demonstration (when no API is available)
MOCK_TRANSLATIONS = {
    "zh-en": {
        "欢迎各位参加今天的会议": "Welcome everyone to attend today's conference",
        "请大家保持安静": "Please keep quiet everyone",
        "会议即将开始": "The conference is about to begin",
        "感谢大家的参与": "Thanks for everyone's participation",
        "我们开始今天的议题": "Let's start today's topic",
        "人工智能技术正在快速发展": "AI technology is developing rapidly",
        "机器学习是人工智能的核心": "Machine learning is the core of artificial intelligence",
        "深度学习在图像识别中应用广泛": "Deep learning is widely used in image recognition",
        "神经网络可以处理复杂的模式": "Neural networks can handle complex patterns",
        "自然语言处理使机器理解人类语言": "Natural language processing enables machines to understand human language",
    },
    "en-zh": {
        "Good morning everyone, let's begin the meeting": "大家早上好，让我们开始会议吧",
        "The neural network architecture uses transformer models": "该神经网络架构使用Transformer模型",
        "Machine learning algorithms require large datasets": "机器学习算法需要大量数据集",
    },
    "id-zh": {
        "Selamat pagi, mari kita mulai diskusi": "早上好，让我们开始讨论吧",
        "Terima kasih atas partisipasi Anda": "感谢您的参与",
    }
}


# =============================================================================
# Data Classes
# =============================================================================

@dataclass
class TranslationPair:
    source: str
    reference: str
    hypothesis: str
    direction: str
    domain: str = "general"


@dataclass
class DirectionMetrics:
    direction: str
    bleu_score: float
    bert_score_precision: float
    bert_score_recall: float
    bert_score_f1: float
    pair_count: int
    passed: bool


@dataclass
class BenchmarkResult:
    timestamp: str
    api_url: Optional[str]
    is_mock: bool
    direction_results: list
    overall_bleu_avg: float
    overall_bert_f1_avg: float
    term_consistency_rate: float
    all_passed: bool


# =============================================================================
# Metrics Calculation
# =============================================================================

def calculate_bleu(reference: str, hypothesis: str) -> float:
    """
    Calculate BLEU score for a single sentence pair.
    Uses sacrebleu for standard BLEU calculation.
    """
    try:
        import sacrebleu
        result = sacrebleu.sentence_bleu(hypothesis, [reference])
        return result.score
    except ImportError:
        # Fallback: simple word overlap ratio
        ref_words = set(reference.lower().split())
        hyp_words = set(hypothesis.lower().split())
        if not ref_words:
            return 0.0
        overlap = len(ref_words & hyp_words)
        return (overlap / len(ref_words)) * 100


def calculate_bert_score(reference: str, hypothesis: str, lang: str = "en") -> tuple:
    """
    Calculate BERTScore for a single sentence pair.
    Returns (precision, recall, f1).
    """
    try:
        from bert_score import score as bert_score
        import torch

        refs = [reference]
        hyps = [hypothesis]

        # Use multilingual model for non-English
        if lang in ("zh", "chinese"):
            model_type = "bert-base-multilingual-cased"
        elif lang == "id":
            model_type = "bert-base-multilingual-cased"
        else:
            model_type = "bert-base-uncased"

        P, R, F1 = bert_score(
            hyps, refs,
            model_type=model_type,
            lang=lang if lang != "zh" else "zh",
            device=torch.device("cpu"),
            verbose=False
        )

        return (
            P.item(),
            R.item(),
            F1.item()
        )
    except ImportError:
        # Fallback: cosine similarity based on character n-grams
        def get_ngrams(s, n=3):
            return set(s[i:i+n] for i in range(len(s)-n+1))

        ref_ngrams = get_ngrams(reference.lower())
        hyp_ngrams = get_ngrams(hypothesis.lower())

        if not ref_ngrams:
            return (0.0, 0.0, 0.0)

        precision = len(ref_ngrams & hyp_ngrams) / len(hyp_ngrams) if hyp_ngrams else 0
        recall = len(ref_ngrams & hyp_ngrams) / len(ref_ngrams)
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0
        return (precision, recall, f1)


def calculate_bleu_for_corpus(references: list, hypotheses: list) -> float:
    """Calculate average BLEU score for a corpus."""
    if not references or not hypotheses:
        return 0.0

    scores = []
    for ref, hyp in zip(references, hypotheses):
        scores.append(calculate_bleu(ref, hyp))
    return sum(scores) / len(scores)


def calculate_bert_for_corpus(references: list, hypotheses: list, lang: str = "en") -> tuple:
    """Calculate average BERTScore for a corpus."""
    if not references or not hypotheses:
        return (0.0, 0.0, 0.0)

    total_p, total_r, total_f1 = 0.0, 0.0, 0.0
    for ref, hyp in zip(references, hypotheses):
        p, r, f1 = calculate_bert_score(ref, hyp, lang)
        total_p += p
        total_r += r
        total_f1 += f1

    count = len(references)
    return (total_p / count, total_r / count, total_f1 / count)


# =============================================================================
# API Integration
# =============================================================================

def call_translation_api(api_url: str, source_text: str, source_lang: str, target_lang: str) -> Optional[str]:
    """Call the translation API and return the translated text."""
    import urllib.request
    import urllib.error

    payload = {
        "segment": source_text,
        "sourceLang": source_lang,
        "targetLang": target_lang,
        "kbEnabled": False
    }

    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            api_url,
            data=data,
            headers={"Content-Type": "application/json"}
        )

        with urllib.request.urlopen(req, timeout=60) as response:
            result = json.loads(response.read().decode("utf-8"))

            if result.get("code") == 200 and result.get("data"):
                return result["data"]["translation"]
            else:
                print(f"    [WARN] API returned error: {result.get('message', 'Unknown error')}")
                return None

    except urllib.error.HTTPError as e:
        print(f"    [WARN] HTTP error {e.code}: {e.reason}")
        return None
    except urllib.error.URLError as e:
        print(f"    [WARN] URL error: {e.reason}")
        return None
    except json.JSONDecodeError:
        print(f"    [WARN] Invalid JSON response from API")
        return None
    except Exception as e:
        print(f"    [WARN] API call failed: {e}")
        return None


def translate_source(source: str, direction: str, api_url: Optional[str], is_mock: bool) -> str:
    """Translate a source text, using mock or API."""
    if is_mock or not api_url:
        # Return a mock translation
        if direction in MOCK_TRANSLATIONS and source in MOCK_TRANSLATIONS[direction]:
            return MOCK_TRANSLATIONS[direction][source]
        # For unmocked texts, create a simple transformation
        if direction == "zh-en":
            # Simple mock: add prefix
            return f"[EN] {source[:20]}... (mock translation)"
        elif direction == "en-zh":
            return f"[中文] {source[:10]}... (模拟翻译)"
        elif direction == "id-zh":
            return f"[中文] {source[:15]}... (模拟翻译)"
        return f"[translated] {source}"

    # Map direction to API language codes
    lang_map = {
        "zh-en": ("zh", "en"),
        "en-zh": ("en", "zh"),
        "id-zh": ("id", "zh"),
    }

    if direction not in lang_map:
        return source

    src_lang, tgt_lang = lang_map[direction]
    return call_translation_api(api_url, source, src_lang, tgt_lang) or source


# =============================================================================
# Term Consistency Testing
# =============================================================================

def check_term_consistency(translated_text: str, source_text: str, glossary: list) -> dict:
    """
    Check if glossary terms in source are consistently translated in output.
    Returns dict with term checks.
    """
    results = {
        "total_terms_found": 0,
        "correctly_translated": 0,
        "checks": []
    }

    translated_lower = translated_text.lower()

    for eng_term, zh_term in glossary:
        # Check if English term appears in source
        if eng_term.lower() in source_text.lower():
            results["total_terms_found"] += 1

            # Check if the Chinese translation appears in output
            if zh_term in translated_text or zh_term.lower() in translated_lower:
                results["correctly_translated"] += 1
                results["checks"].append({
                    "term": eng_term,
                    "expected": zh_term,
                    "found": True
                })
            else:
                results["checks"].append({
                    "term": eng_term,
                    "expected": zh_term,
                    "found": False
                })

    return results


def calculate_term_consistency_rate(pairs: list, glossary: list) -> float:
    """Calculate overall term consistency rate across all pairs."""
    total_found = 0
    total_correct = 0

    for pair in pairs:
        result = check_term_consistency(pair.hypothesis, pair.source, glossary)
        total_found += result["total_terms_found"]
        total_correct += result["correctly_translated"]

    if total_found == 0:
        return 100.0  # No terms found, assume pass

    return (total_correct / total_found) * 100


# =============================================================================
# Benchmark Execution
# =============================================================================

def run_benchmark_for_direction(
    direction: str,
    corpus: list,
    api_url: Optional[str],
    is_mock: bool,
    lang_code: str = "en"
) -> tuple:
    """Run benchmark for a specific translation direction."""

    print(f"\n{'='*60}")
    print(f"Direction: {direction.upper()}")
    print(f"{'='*60}")

    pairs = []
    references = []
    hypotheses = []

    for idx, item in enumerate(corpus):
        source = item["source"]
        reference = item["reference"]

        print(f"\n[{idx+1}/{len(corpus)}] Source: {source[:50]}{'...' if len(source) > 50 else ''}")

        hypothesis = translate_source(source, direction, api_url, is_mock)
        print(f"    Reference: {reference[:50]}{'...' if len(reference) > 50 else ''}")
        print(f"    Hypothesis: {hypothesis[:50]}{'...' if len(hypothesis) > 50 else ''}")

        pairs.append(TranslationPair(
            source=source,
            reference=reference,
            hypothesis=hypothesis,
            direction=direction
        ))
        references.append(reference)
        hypotheses.append(hypothesis)

        # Rate limiting for API calls
        if not is_mock:
            time.sleep(0.5)

    # Calculate metrics
    bleu = calculate_bleu_for_corpus(references, hypotheses)
    p, r, f1 = calculate_bert_for_corpus(references, hypotheses, lang_code)

    print(f"\n[Metrics for {direction}]")
    print(f"    BLEU Score: {bleu:.2f}")
    print(f"    BERTScore - P: {p:.4f}, R: {r:.4f}, F1: {f1:.4f}")

    # Check thresholds
    bleu_pass = bleu >= 15.0
    bert_pass = f1 >= 0.85

    print(f"    BLEU >= 15: {'PASS' if bleu_pass else 'FAIL'}")
    print(f"    BERTScore F1 >= 0.85: {'PASS' if bert_pass else 'FAIL'}")

    return (
        DirectionMetrics(
            direction=direction,
            bleu_score=bleu,
            bert_score_precision=p,
            bert_score_recall=r,
            bert_score_f1=f1,
            pair_count=len(pairs),
            passed=bleu_pass and bert_pass
        ),
        pairs
    )


def run_terminology_benchmark(api_url: Optional[str], is_mock: bool) -> tuple:
    """Run benchmark specifically for technical terminology."""
    direction = "zh-en"
    lang_code = "en"

    print(f"\n{'='*60}")
    print(f"Domain: TECHNICAL TERMINOLOGY (zh-en)")
    print(f"{'='*60}")

    pairs = []
    references = []
    hypotheses = []

    for idx, item in enumerate(TECHNICAL_TERMINOLOGY):
        source = item["source"]
        reference = item["reference"]

        print(f"\n[{idx+1}/{len(TECHNICAL_TERMINOLOGY)}] Source: {source[:50]}{'...' if len(source) > 50 else ''}")

        hypothesis = translate_source(source, direction, api_url, is_mock)
        print(f"    Reference: {reference[:50]}{'...' if len(reference) > 50 else ''}")
        print(f"    Hypothesis: {hypothesis[:50]}{'...' if len(hypothesis) > 50 else ''}")

        pairs.append(TranslationPair(
            source=source,
            reference=reference,
            hypothesis=hypothesis,
            direction=direction,
            domain="technical"
        ))
        references.append(reference)
        hypotheses.append(hypothesis)

        if not is_mock:
            time.sleep(0.5)

    # Calculate metrics
    bleu = calculate_bleu_for_corpus(references, hypotheses)
    p, r, f1 = calculate_bert_for_corpus(references, hypotheses, lang_code)

    print(f"\n[Metrics for Technical Terminology]")
    print(f"    BLEU Score: {bleu:.2f}")
    print(f"    BERTScore - P: {p:.4f}, R: {r:.4f}, F1: {f1:.4f}")

    return (
        DirectionMetrics(
            direction="zh-en-technical",
            bleu_score=bleu,
            bert_score_precision=p,
            bert_score_recall=r,
            bert_score_f1=f1,
            pair_count=len(pairs),
            passed=bleu >= 15.0 and f1 >= 0.85
        ),
        pairs
    )


def print_summary(results: list, all_pairs: list, api_url: Optional[str], is_mock: bool):
    """Print benchmark summary."""

    print(f"\n{'='*60}")
    print("BENCHMARK SUMMARY")
    print(f"{'='*60}")

    total_bleu = sum(r.bleu_score for r in results) / len(results) if results else 0
    total_bert_f1 = sum(r.bert_score_f1 for r in results) / len(results) if results else 0

    # Term consistency
    term_consistency = calculate_term_consistency_rate(all_pairs, GLOSSARY)

    print(f"\nTimestamp: {datetime.now().isoformat()}")
    print(f"API URL: {api_url or 'Mock Mode'}")
    print(f"Mode: {'MOCK (no API call)' if is_mock else 'LIVE API'}")

    print(f"\n--- Per-Direction Results ---")
    for r in results:
        status = "PASS" if r.passed else "FAIL"
        print(f"  {r.direction:25s} | BLEU: {r.bleu_score:5.2f} | BERT-F1: {r.bert_score_f1:.4f} | {status}")

    print(f"\n--- Overall Metrics ---")
    print(f"  Average BLEU: {total_bleu:.2f}")
    print(f"  Average BERTScore F1: {total_bert_f1:.4f}")
    print(f"  Term Consistency: {term_consistency:.2f}%")

    # Threshold checks
    print(f"\n--- Threshold Checks ---")
    print(f"  BLEU >= 15:         {'PASS' if total_bleu >= 15 else 'FAIL'}")
    print(f"  BERTScore F1 >= 0.85: {'PASS' if total_bert_f1 >= 0.85 else 'FAIL'}")
    print(f"  Term Consistency >= 98%: {'PASS' if term_consistency >= 98 else 'FAIL'}")

    all_passed = all(r.passed for r in results) and total_bleu >= 15 and total_bert_f1 >= 0.85 and term_consistency >= 98

    print(f"\n{'='*60}")
    print(f"Overall Result: {'ALL PASSED' if all_passed else 'SOME FAILED'}")
    print(f"{'='*60}")

    return all_passed


def save_json_results(
    filepath: str,
    results: list,
    all_pairs: list,
    api_url: Optional[str],
    is_mock: bool,
    all_passed: bool
):
    """Save results to JSON file."""

    total_bleu = sum(r.bleu_score for r in results) / len(results) if results else 0
    total_bert_f1 = sum(r.bert_score_f1 for r in results) / len(results) if results else 0
    term_consistency = calculate_term_consistency_rate(all_pairs, GLOSSARY)

    output = {
        "timestamp": datetime.now().isoformat(),
        "api_url": api_url,
        "is_mock": is_mock,
        "thresholds": {
            "bleu_min": 15.0,
            "bert_f1_min": 0.85,
            "term_consistency_min": 98.0
        },
        "direction_results": [
            {
                "direction": r.direction,
                "bleu_score": round(r.bleu_score, 4),
                "bert_score_precision": round(r.bert_score_precision, 4),
                "bert_score_recall": round(r.bert_score_recall, 4),
                "bert_score_f1": round(r.bert_score_f1, 4),
                "pair_count": r.pair_count,
                "passed": r.passed
            }
            for r in results
        ],
        "overall_metrics": {
            "bleu_average": round(total_bleu, 4),
            "bert_f1_average": round(total_bert_f1, 4),
            "term_consistency_rate": round(term_consistency, 2)
        },
        "all_passed": all_passed,
        "pairs": [
            {
                "source": p.source,
                "reference": p.reference,
                "hypothesis": p.hypothesis,
                "direction": p.direction,
                "domain": p.domain
            }
            for p in all_pairs
        ]
    }

    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)

    print(f"\nResults saved to: {filepath}")


# =============================================================================
# Main Entry Point
# =============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Translation Quality Benchmark - BLEU and BERTScore evaluation"
    )
    parser.add_argument(
        "--api-url",
        type=str,
        default=None,
        help="Translation API URL (e.g., http://localhost:8080/api/v1/ai/translate)"
    )
    parser.add_argument(
        "--output",
        type=str,
        default="translation_benchmark_results.json",
        help="Output JSON file path"
    )
    parser.add_argument(
        "--skip-mock-warning",
        action="store_true",
        help="Skip mock mode warning"
    )

    args = parser.parse_args()

    is_mock = not args.api_url

    if is_mock:
        print("=" * 60)
        print("WARNING: Running in MOCK MODE")
        print("No API URL provided. Using simulated translations.")
        print("For real benchmarking, provide --api-url")
        print("=" * 60)
        if not args.skip_mock_warning:
            print()

    # Track all results and pairs
    all_results = []
    all_pairs = []

    # Run benchmarks for each direction
    corpora = [
        ("zh-en", ZH_TO_EN, "en"),
        ("en-zh", EN_TO_ZH, "zh"),
        ("id-zh", ID_TO_ZH, "zh"),
    ]

    for direction, corpus, lang_code in corpora:
        result, pairs = run_benchmark_for_direction(
            direction, corpus, args.api_url, is_mock, lang_code
        )
        all_results.append(result)
        all_pairs.extend(pairs)

    # Run technical terminology benchmark
    tech_result, tech_pairs = run_terminology_benchmark(args.api_url, is_mock)
    all_results.append(tech_result)
    all_pairs.extend(tech_pairs)

    # Print and get summary
    all_passed = print_summary(all_results, all_pairs, args.api_url, is_mock)

    # Save JSON results
    save_json_results(args.output, all_results, all_pairs, args.api_url, is_mock, all_passed)

    # Exit with appropriate code
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
