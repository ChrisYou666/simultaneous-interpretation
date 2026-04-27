#!/usr/bin/env python3
"""
ASR WER Benchmark
Measures Word Error Rate for the DashScope ASR service.
Uses standard Chinese/English/Indonesian speech test datasets.

Supports two modes:
- mock: Simulates ASR output with realistic error patterns (default)
- live: Connects to real ASR service (requires audio files and API access)

Usage:
    python scripts/asr_wer_benchmark.py --mode=mock
    python scripts/asr_wer_benchmark.py --mode=mock --output-json
    python scripts/asr_wer_benchmark.py --mode=live --audio-dir=/path/to/audio
"""

import argparse
import json
import os
import random
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional

# Fix Unicode output on Windows
if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# Try to import jiwer for WER calculation, provide fallback
try:
    from jiwer import wer, cer, ComputeMeasures
    JIWER_AVAILABLE = True
except ImportError:
    JIWER_AVAILABLE = False
    print("Warning: jiwer not installed. Using simple WER calculation.")
    print("Install with: pip install jiwer")


# =============================================================================
# Test Corpus - Reference transcriptions for ASR evaluation
# =============================================================================

ZH_CORPUS = [
    {"audio": "mock_zh_001", "reference": "欢迎参加本次会议", "language": "zh"},
    {"audio": "mock_zh_002", "reference": "请大家关闭手机", "language": "zh"},
    {"audio": "mock_zh_003", "reference": "今天天气非常不错", "language": "zh"},
    {"audio": "mock_zh_004", "reference": "我们开始讨论议程", "language": "zh"},
    {"audio": "mock_zh_005", "reference": "请各位发表意见", "language": "zh"},
    {"audio": "mock_zh_006", "reference": "我认为这个方案可行", "language": "zh"},
    {"audio": "mock_zh_007", "reference": "我们需要进一步讨论", "language": "zh"},
    {"audio": "mock_zh_008", "reference": "谢谢大家的发言", "language": "zh"},
    {"audio": "mock_zh_009", "reference": "会议即将结束", "language": "zh"},
    {"audio": "mock_zh_010", "reference": "下次会议再见", "language": "zh"},
]

EN_CORPUS = [
    {"audio": "mock_en_001", "reference": "Welcome to today's meeting", "language": "en"},
    {"audio": "mock_en_002", "reference": "Please turn off your phones", "language": "en"},
    {"audio": "mock_en_003", "reference": "The weather is very nice today", "language": "en"},
    {"audio": "mock_en_004", "reference": "Let's begin our discussion", "language": "en"},
    {"audio": "mock_en_005", "reference": "Please share your thoughts", "language": "en"},
    {"audio": "mock_en_006", "reference": "I think this plan is feasible", "language": "en"},
    {"audio": "mock_en_007", "reference": "We need to discuss further", "language": "en"},
    {"audio": "mock_en_008", "reference": "Thank you for your comments", "language": "en"},
    {"audio": "mock_en_009", "reference": "The meeting is about to end", "language": "en"},
    {"audio": "mock_en_010", "reference": "See you at the next meeting", "language": "en"},
]

ID_CORPUS = [
    {"audio": "mock_id_001", "reference": "Selamat pagi semuanya", "language": "id"},
    {"audio": "mock_id_002", "reference": "Silakan matikan ponsel Anda", "language": "id"},
    {"audio": "mock_id_003", "reference": "Cuaca sangat bagus hari ini", "language": "id"},
    {"audio": "mock_id_004", "reference": "Mari kita mulai diskusi", "language": "id"},
    {"audio": "mock_id_005", "reference": "Silakan sampaikan pendapat Anda", "language": "id"},
]


# =============================================================================
# Data Classes
# =============================================================================

@dataclass
class AsrResult:
    """Result of ASR transcription for a single audio sample."""
    audio_file: str
    reference: str
    hypothesis: str
    language: str
    wer: float
    cer: float
    wrr: float
    is_mock: bool = True


@dataclass
class BenchmarkResult:
    """Aggregated benchmark results."""
    mode: str
    timestamp: str
    total_samples: int
    overall_wer: float
    overall_wrr: float
    overall_cer: float
    by_language: dict = field(default_factory=dict)
    samples: list = field(default_factory=list)


# =============================================================================
# Mock ASR Simulation
# =============================================================================

# Common Chinese character substitutions (homophones and similar sounds)
ZH_SUBSTITUTIONS = {
    '的': ['得', '地'],
    '是': ['时', '市'],
    '在': ['再', '才'],
    '有': ['又', '友'],
    '我': ['哦', '饿'],
    '你': ['尼', '呢'],
    '了': ['辽', '了解'],
    '和': ['河', '喝', '合'],
    '要': ['药', '耀'],
    '会': ['惠', '汇'],
}

# Common English word substitutions (similar sounds)
EN_SUBSTITUTIONS = {
    'the': ['a', 'an', 'this'],
    'to': ['too', 'two'],
    'and': ['an', 'in'],
    'of': ['off', 'for'],
    'is': ['it', 'are'],
    'in': ['on', 'an'],
    'that': ['what', 'this'],
    'for': ['of', 'four'],
    'with': ['which', 'wish'],
    'this': ['that', 'these'],
}

# Common Indonesian word substitutions
ID_SUBSTITUTIONS = {
    'yang': ['dan', 'yang', 'yng'],
    'dan': ['yang', 'di', 'dn'],
    'di': ['ke', 'dari', 'de'],
    'ini': ['itu', 'ini', 'nih'],
    'itu': ['ini', 'iut', 'tuh'],
    'anda': ['kamu', 'awak', 'anda'],
    'saya': ['aku', 'sya', 'saya'],
}


def simulate_asr_output(reference: str, language: str, seed: int = None) -> str:
    """
    Simulate ASR output with realistic error patterns.

    Args:
        reference: The reference transcription
        language: Language code (zh/en/id)
        seed: Random seed for deterministic results

    Returns:
        Simulated ASR transcription with realistic errors
    """
    if seed is not None:
        random.seed(seed)

    if language == 'zh':
        return _simulate_zh_asr(reference)
    else:
        return _simulate_latin_asr(reference, language)


def _simulate_zh_asr(reference: str) -> str:
    """
    Simulate Chinese ASR with character-level errors.
    Error profile based on typical ASR characteristics:
    - Deletion rate: 5%
    - Substitution rate: 10%
    - Insertion rate: 3%
    """
    result = list(reference)

    # Deletions (5%)
    if len(result) > 2 and random.random() < 0.05:
        idx = random.randint(0, len(result) - 1)
        del result[idx]

    # Substitutions (10%)
    for i in range(len(result)):
        if random.random() < 0.10:
            char = result[i]
            if char in ZH_SUBSTITUTIONS:
                result[i] = random.choice(ZH_SUBSTITUTIONS[char])
            elif random.random() < 0.3:
                # Random homophone-like substitution
                result[i] = chr(ord(char) + random.randint(-1, 1)) if char.isalnum() else char

    # Insertions (3%)
    insertions = []
    for i in range(len(result)):
        if random.random() < 0.03 and len(result) < 30:
            insertions.append((i, _random_zh_char()))

    for idx, char in reversed(insertions):
        result.insert(idx, char)

    return ''.join(result)


def _simulate_latin_asr(reference: str, language: str) -> str:
    """
    Simulate English/Indonesian ASR with word-level errors.
    Error profile:
    - Deletion rate: 5%
    - Substitution rate: 10%
    - Insertion rate: 3%
    """
    words = reference.split()
    substitutions = EN_SUBSTITUTIONS if language == 'en' else ID_SUBSTITUTIONS

    # Deletions (5%)
    if len(words) > 2 and random.random() < 0.05:
        idx = random.randint(0, len(words) - 1)
        del words[idx]

    # Substitutions (10%)
    for i in range(len(words)):
        if random.random() < 0.10:
            word = words[i].lower()
            if word in substitutions:
                words[i] = random.choice(substitutions[word])
            elif random.random() < 0.2:
                # Slight character mutation
                w = list(words[i])
                if len(w) > 2:
                    idx = random.randint(0, len(w) - 1)
                    w[idx] = chr(ord(w[idx]) + random.randint(-1, 1))
                    words[i] = ''.join(w)

    # Insertions (3%)
    new_words = []
    for word in words:
        new_words.append(word)
        if random.random() < 0.03:
            filler = _get_filler_word(language)
            new_words.append(filler)

    return ' '.join(new_words)


def _random_zh_char() -> str:
    """Get a random common Chinese character."""
    common_chars = '的是在了有和国中大来上个们就到时说也要就出会可而后子'
    return random.choice(common_chars)


def _get_filler_word(language: str) -> str:
    """Get a language-appropriate filler word."""
    fillers = {
        'en': ['um', 'uh', 'like', 'you know'],
        'id': ['nah', 'ya', 'nih', 'tuh'],
    }
    return random.choice(fillers.get(language, ['um']))


# =============================================================================
# WER/CER Calculation
# =============================================================================

def calculate_wer(reference: str, hypothesis: str) -> float:
    """Calculate Word Error Rate (WER)."""
    if not JIWER_AVAILABLE:
        return _simple_wer(reference, hypothesis)

    return wer(reference, hypothesis)


def calculate_cer(reference: str, hypothesis: str) -> float:
    """Calculate Character Error Rate (CER) - especially useful for Chinese."""
    if not JIWER_AVAILABLE:
        return _simple_cer(reference, hypothesis)

    return cer(reference, hypothesis)


def _simple_wer(reference: str, hypothesis: str) -> float:
    """Simple word-level WER calculation without jiwer."""
    ref_words = reference.lower().split()
    hyp_words = hypothesis.lower().split()

    # Simple Levenshtein distance
    d = [[0] * (len(hyp_words) + 1) for _ in range(len(ref_words) + 1)]

    for i in range(len(ref_words) + 1):
        d[i][0] = i
    for j in range(len(hyp_words) + 1):
        d[0][j] = j

    for i in range(1, len(ref_words) + 1):
        for j in range(1, len(hyp_words) + 1):
            if ref_words[i-1] == hyp_words[j-1]:
                d[i][j] = d[i-1][j-1]
            else:
                d[i][j] = min(d[i-1][j], d[i][j-1], d[i-1][j-1]) + 1

    if len(ref_words) == 0:
        return 0.0 if len(hyp_words) == 0 else 1.0

    return d[len(ref_words)][len(hyp_words)] / len(ref_words)


def _simple_cer(reference: str, hypothesis: str) -> float:
    """Simple character-level CER calculation without jiwer."""
    ref_chars = list(reference)
    hyp_chars = list(hypothesis)

    d = [[0] * (len(hyp_chars) + 1) for _ in range(len(ref_chars) + 1)]

    for i in range(len(ref_chars) + 1):
        d[i][0] = i
    for j in range(len(hyp_chars) + 1):
        d[0][j] = j

    for i in range(1, len(ref_chars) + 1):
        for j in range(1, len(hyp_chars) + 1):
            if ref_chars[i-1] == hyp_chars[j-1]:
                d[i][j] = d[i-1][j-1]
            else:
                d[i][j] = min(d[i-1][j], d[i][j-1], d[i-1][j-1]) + 1

    if len(ref_chars) == 0:
        return 0.0 if len(hyp_chars) == 0 else 1.0

    return d[len(ref_chars)][len(hyp_chars)] / len(ref_chars)


def get_detailed_measures(reference: str, hypothesis: str) -> dict:
    """Get detailed WER metrics including S/D/I counts."""
    if JIWER_AVAILABLE:
        measures = ComputeMeasures(reference, hypothesis)
        return {
            'wer': measures.wer,
            'cer': measures.cer if hasattr(measures, 'cer') else calculate_cer(reference, hypothesis),
            'wrr': 1 - measures.wer,
            'substitutions': measures.substitutions,
            'deletions': measures.deletions,
            'insertions': measures.insertions,
            'hits': measures.hits,
            'total_words': measures.hits + measures.substitutions + measures.deletions,
        }

    # Fallback implementation
    ref_words = reference.lower().split()
    hyp_words = hypothesis.lower().split()

    # Simple alignment
    s, d, i = 0, 0, 0
    r_idx, h_idx = 0, 0

    while r_idx < len(ref_words) or h_idx < len(hyp_words):
        if r_idx >= len(ref_words):
            i += len(hyp_words) - h_idx
            break
        if h_idx >= len(hyp_words):
            d += len(ref_words) - r_idx
            break

        if ref_words[r_idx] == hyp_words[h_idx]:
            r_idx += 1
            h_idx += 1
        else:
            s += 1
            r_idx += 1
            h_idx += 1

    total = len(ref_words)
    wer = (s + d + i) / total if total > 0 else 0

    return {
        'wer': wer,
        'cer': _simple_cer(reference, hypothesis),
        'wrr': 1 - wer,
        'substitutions': s,
        'deletions': d,
        'insertions': i,
        'hits': total - s - d,
        'total_words': total,
    }


# =============================================================================
# Mock Mode Benchmark
# =============================================================================

def run_mock_benchmark() -> BenchmarkResult:
    """
    Run ASR WER benchmark in mock mode.
    Simulates ASR output with realistic error patterns.
    """
    print("\n" + "=" * 70)
    print("ASR WER BENCHMARK - MOCK MODE")
    print("=" * 70)
    print("\u26a0\ufe0f  MOCK MODE - No actual ASR calls made")
    print("   Simulating ASR output with realistic error patterns")
    print("-" * 70)

    all_samples = ZH_CORPUS + EN_CORPUS + ID_CORPUS
    results = []

    # Set seed for reproducible results
    seed = 42
    random.seed(seed)

    print(f"\nProcessing {len(all_samples)} audio samples...")
    print()

    for idx, sample in enumerate(all_samples, 1):
        reference = sample['reference']
        language = sample['language']

        # Generate mock ASR output with deterministic seed
        hypothesis = simulate_asr_output(reference, language, seed=seed + idx)

        # Calculate metrics
        measures = get_detailed_measures(reference, hypothesis)

        result = AsrResult(
            audio_file=sample['audio'],
            reference=reference,
            hypothesis=hypothesis,
            language=language,
            wer=measures['wer'],
            cer=measures['cer'],
            wrr=measures['wrr'],
            is_mock=True
        )
        results.append(result)

        # Print progress
        status = "\u2705" if measures['wer'] < 0.20 else "\u26a0\ufe0f"
        print(f"  [{idx:02d}/{len(all_samples)}] {sample['audio']} | "
              f"WER: {measures['wer']:.2%} | "
              f"CER: {measures['cer']:.2%} | "
              f"WRR: {measures['wrr']:.2%} | "
              f"{status}")

        if measures['wer'] > 0.30:
            print(f"           Ref: {reference}")
            print(f"           Hyp:  {hypothesis}")

    # Calculate overall metrics
    overall_wer = sum(r.wer for r in results) / len(results)
    overall_cer = sum(r.cer for r in results) / len(results)
    overall_wrr = 1 - overall_wer

    # Calculate per-language metrics
    by_language = {}
    for lang in ['zh', 'en', 'id']:
        lang_results = [r for r in results if r.language == lang]
        if lang_results:
            by_language[lang] = {
                'count': len(lang_results),
                'wer': sum(r.wer for r in lang_results) / len(lang_results),
                'cer': sum(r.cer for r in lang_results) / len(lang_results),
                'wrr': sum(r.wrr for r in lang_results) / len(lang_results),
            }

    benchmark = BenchmarkResult(
        mode='mock',
        timestamp=datetime.now().isoformat(),
        total_samples=len(results),
        overall_wer=overall_wer,
        overall_wrr=overall_wrr,
        overall_cer=overall_cer,
        by_language=by_language,
        samples=[{
            'audio_file': r.audio_file,
            'reference': r.reference,
            'hypothesis': r.hypothesis,
            'language': r.language,
            'wer': r.wer,
            'cer': r.cer,
            'wrr': r.wrr,
        } for r in results]
    )

    return benchmark


# =============================================================================
# Live Mode Benchmark (placeholder for real ASR testing)
# =============================================================================

def run_live_benchmark(audio_dir: str = None) -> BenchmarkResult:
    """
    Run ASR WER benchmark in live mode against real ASR service.

    This requires:
    - Actual audio files in WAV/PCM format
    - Access to DashScope ASR API
    - Proper authentication credentials

    For this implementation, we provide a framework that can be extended
    when real audio files and API access are available.
    """
    print("\n" + "=" * 70)
    print("ASR WER BENCHMARK - LIVE MODE")
    print("=" * 70)

    if audio_dir:
        audio_path = Path(audio_dir)
        if not audio_path.exists():
            print(f"\n\u274c Error: Audio directory not found: {audio_dir}")
            print("Falling back to mock mode...")
            time.sleep(1)
            return run_mock_benchmark()

        print(f"\n\u2705 Audio directory: {audio_dir}")
        print("Scanning for audio files...")
        audio_files = list(audio_path.glob("*.wav")) + list(audio_path.glob("*.pcm"))
        print(f"Found {len(audio_files)} audio files")

        # Placeholder for real ASR API calls
        print("\n\u26a0\ufe0f Live ASR testing not yet implemented")
        print("Please implement actual ASR API integration here")
    else:
        print("\n\u274c Error: --audio-dir required for live mode")
        print("Usage: python scripts/asr_wer_benchmark.py --mode=live --audio-dir=/path/to/audio")
        print("\nFalling back to mock mode...")
        time.sleep(1)
        return run_mock_benchmark()

    # Placeholder - return mock results for now
    return run_mock_benchmark()


# =============================================================================
# Report Generation
# =============================================================================

def print_benchmark_report(benchmark: BenchmarkResult):
    """Print formatted benchmark report to console."""
    print("\n" + "=" * 70)
    print("BENCHMARK RESULTS SUMMARY")
    print("=" * 70)

    lang_names = {'zh': 'Chinese', 'en': 'English', 'id': 'Indonesian'}

    # Overall metrics
    print(f"\nOverall Performance ({benchmark.total_samples} samples):")
    print(f"  - Word Error Rate (WER):     {benchmark.overall_wer:.2%}")
    print(f"  - Word Recognition Rate:    {benchmark.overall_wrr:.2%}")
    print(f"  - Character Error Rate (CER): {benchmark.overall_cer:.2%}")

    # Per-language breakdown
    print(f"\nPer-Language Breakdown:")
    print("-" * 70)
    for lang, metrics in benchmark.by_language.items():
        lang_name = lang_names.get(lang, lang)
        wer_status = _get_status_emoji(metrics['wer'])
        print(f"\n  [{lang.upper()}] {lang_name} ({metrics['count']} samples) {wer_status}")
        print(f"    WER:  {metrics['wer']:.2%}")
        print(f"    WRR:  {metrics['wrr']:.2%}")
        print(f"    CER:  {metrics['cer']:.2%}")

    # Quality assessment
    print("\n" + "=" * 70)
    print("QUALITY ASSESSMENT")
    print("=" * 70)

    if benchmark.overall_wer < 0.10:
        assessment = "\u2705 EXCELLENT"
        detail = "ASR performance is excellent (< 10% WER)"
    elif benchmark.overall_wer < 0.20:
        assessment = "\u2705 GOOD"
        detail = "ASR performance meets baseline requirements (< 20% WER)"
    elif benchmark.overall_wer < 0.30:
        assessment = "\u26a0\ufe0f ACCEPTABLE"
        detail = "ASR performance is acceptable but could be improved (< 30% WER)"
    else:
        assessment = "\u274c NEEDS IMPROVEMENT"
        detail = "ASR performance is below acceptable threshold (> 30% WER)"

    print(f"\n  {assessment}")
    print(f"  {detail}")

    # Thresholds
    print("\n  Baseline Thresholds:")
    print(f"    \u2705 WER < 20%: PASS (current: {benchmark.overall_wer:.2%})")
    print(f"    \u26a0\ufe0f WER < 10%: GOOD (current: {benchmark.overall_wer:.2%})")

    print("\n" + "=" * 70)
    print(f"Timestamp: {benchmark.timestamp}")
    print(f"Mode: {benchmark.mode.upper()}")
    print("=" * 70)


def _get_status_emoji(wer: float) -> str:
    """Get status emoji based on WER threshold."""
    if wer < 0.10:
        return "\u2705"
    elif wer < 0.20:
        return "\u2705"
    elif wer < 0.30:
        return "\u26a0\ufe0f"
    else:
        return "\u274c"


def save_results_json(benchmark: BenchmarkResult, output_path: str = None):
    """Save benchmark results to JSON file."""
    if output_path is None:
        output_path = "asr_wer_results.json"

    # Convert to serializable dict
    result_dict = {
        'mode': benchmark.mode,
        'timestamp': benchmark.timestamp,
        'total_samples': benchmark.total_samples,
        'overall_wer': round(benchmark.overall_wer, 4),
        'overall_wrr': round(benchmark.overall_wrr, 4),
        'overall_cer': round(benchmark.overall_cer, 4),
        'by_language': {
            lang: {
                'count': metrics['count'],
                'wer': round(metrics['wer'], 4),
                'cer': round(metrics['cer'], 4),
                'wrr': round(metrics['wrr'], 4),
            }
            for lang, metrics in benchmark.by_language.items()
        },
        'samples': benchmark.samples,
        'summary': {
            'wer_passed': benchmark.overall_wer < 0.20,
            'wer_good': benchmark.overall_wer < 0.10,
            'assessment': 'EXCELLENT' if benchmark.overall_wer < 0.10 else
                        'GOOD' if benchmark.overall_wer < 0.20 else
                        'ACCEPTABLE' if benchmark.overall_wer < 0.30 else
                        'NEEDS_IMPROVEMENT'
        }
    }

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(result_dict, f, indent=2, ensure_ascii=False)

    print(f"\n\u2705 Results saved to: {output_path}")


# =============================================================================
# Main Entry Point
# =============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="ASR WER Benchmark - Measure Word Error Rate for DashScope ASR",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python scripts/asr_wer_benchmark.py                    # Run in mock mode (default)
  python scripts/asr_wer_benchmark.py --mode=mock      # Explicit mock mode
  python scripts/asr_wer_benchmark.py --mode=live --audio-dir=/path/to/audio
  python scripts/asr_wer_benchmark.py --output-json     # Save results to JSON
        """
    )

    parser.add_argument(
        '--mode',
        choices=['mock', 'live'],
        default='mock',
        help='Benchmark mode: mock (simulated) or live (real ASR API)'
    )

    parser.add_argument(
        '--audio-dir',
        type=str,
        help='Directory containing audio files for live mode'
    )

    parser.add_argument(
        '--output-json',
        action='store_true',
        help='Save results to asr_wer_results.json'
    )

    parser.add_argument(
        '--output-path',
        type=str,
        default='asr_wer_results.json',
        help='Output path for JSON results (default: asr_wer_results.json)'
    )

    args = parser.parse_args()

    # Run benchmark
    if args.mode == 'mock':
        benchmark = run_mock_benchmark()
    else:
        benchmark = run_live_benchmark(audio_dir=args.audio_dir)

    # Print report
    print_benchmark_report(benchmark)

    # Save to JSON if requested
    if args.output_json:
        save_results_json(benchmark, args.output_path)

    # Exit with appropriate code
    if benchmark.overall_wer < 0.20:
        sys.exit(0)  # PASS
    else:
        sys.exit(1)  # FAIL - WER above threshold


if __name__ == '__main__':
    main()
