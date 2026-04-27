#!/usr/bin/env python3
"""
Metrics Reporting Script for CI/CD Integration
Parses benchmark results and reports to dashboard/comment.

Usage:
    python scripts/report_metrics.py

Author: System
Version: 1.0.0
"""

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False


def load_benchmark_results(filepath: str) -> dict:
    """Load benchmark results from JSON file."""
    with open(filepath, "r", encoding="utf-8") as f:
        return json.load(f)


def generate_markdown_report(results: dict) -> str:
    """Generate markdown report from benchmark results."""
    md = []
    md.append("# Translation Quality Benchmark Report")
    md.append("")
    md.append(f"**Generated:** {results.get('timestamp', 'N/A')}")
    md.append(f"**Mode:** {'Mock' if results.get('is_mock') else 'Live API'}")
    if results.get('api_url'):
        md.append(f"**API URL:** {results['api_url']}")
    md.append("")

    # Overall status
    all_passed = results.get('all_passed', False)
    status = "✅ ALL PASSED" if all_passed else "❌ SOME FAILED"
    md.append(f"## Overall Status: {status}")
    md.append("")

    # Thresholds
    thresholds = results.get('thresholds', {})
    md.append("### Thresholds")
    md.append(f"- BLEU Score: >= {thresholds.get('bleu_min', 15.0)}")
    md.append(f"- BERTScore F1: >= {thresholds.get('bert_f1_min', 0.85)}")
    md.append(f"- Term Consistency: >= {thresholds.get('term_consistency_min', 98.0)}%")
    md.append("")

    # Overall metrics
    overall = results.get('overall_metrics', {})
    md.append("### Overall Metrics")
    md.append(f"- **BLEU Score:** {overall.get('bleu_average', 0):.2f}")
    md.append(f"- **BERTScore F1:** {overall.get('bert_f1_average', 0):.4f}")
    md.append(f"- **Term Consistency:** {overall.get('term_consistency_rate', 0):.2f}%")
    md.append("")

    # Direction results
    md.append("### Per-Direction Results")
    md.append("")
    md.append("| Direction | BLEU | BERTScore F1 | Pairs | Status |")
    md.append("|-----------|------|--------------|-------|--------|")

    for dr in results.get('direction_results', []):
        status_icon = "✅" if dr.get('passed') else "❌"
        md.append(f"| {dr.get('direction', 'N/A'):15s} | {dr.get('bleu_score', 0):.2f} | "
                 f"{dr.get('bert_score_f1', 0):.4f} | {dr.get('pair_count', 0)} | {status_icon} |")

    md.append("")

    # Sample translations
    pairs = results.get('pairs', [])
    if pairs:
        md.append("### Sample Translations")
        md.append("")

        # Show a few samples per direction
        shown = set()
        for pair in pairs[:15]:
            direction = pair.get('direction', 'unknown')
            if direction in shown:
                continue
            shown.add(direction)

            md.append(f"#### {direction.upper()}")
            md.append("")
            md.append(f"**Source:** {pair.get('source', '')}")
            md.append(f"**Reference:** {pair.get('reference', '')}")
            md.append(f"**Hypothesis:** {pair.get('hypothesis', '')}")
            md.append("")

    return "\n".join(md)


def create_metrics_chart(results: dict, output_path: str):
    """Create a bar chart comparing metrics across directions."""
    if not HAS_MATPLOTLIB:
        print("[WARN] matplotlib not available, skipping chart generation")
        return

    directions = []
    bleu_scores = []
    bert_f1_scores = []

    for dr in results.get('direction_results', []):
        directions.append(dr.get('direction', 'Unknown'))
        bleu_scores.append(dr.get('bleu_score', 0))
        bert_f1_scores.append(dr.get('bert_score_f1', 0) * 100)  # Scale to percentage

    fig, ax = plt.subplots(figsize=(12, 6))

    x = range(len(directions))
    width = 0.35

    bars1 = ax.bar([i - width/2 for i in x], bleu_scores, width, label='BLEU', color='#3498db')
    bars2 = ax.bar([i + width/2 for i in x], bert_f1_scores, width, label='BERTScore F1 (%)', color='#2ecc71')

    ax.set_xlabel('Direction')
    ax.set_ylabel('Score')
    ax.set_title('Translation Quality by Direction')
    ax.set_xticks(x)
    ax.set_xticklabels(directions, rotation=45, ha='right')
    ax.legend()
    ax.grid(axis='y', alpha=0.3)

    # Add threshold lines
    thresholds = results.get('thresholds', {})
    ax.axhline(y=thresholds.get('bleu_min', 15), color='#3498db', linestyle='--', alpha=0.5, label='BLEU threshold')
    ax.axhline(y=thresholds.get('bert_f1_min', 0.85) * 100, color='#2ecc71', linestyle='--', alpha=0.5, label='BERT threshold')

    plt.tight_layout()
    plt.savefig(output_path, dpi=150)
    print(f"[INFO] Chart saved to: {output_path}")


def print_summary_report(results: dict):
    """Print a summary report to console."""
    print("=" * 60)
    print("TRANSLATION BENCHMARK SUMMARY")
    print("=" * 60)

    overall = results.get('overall_metrics', {})
    thresholds = results.get('thresholds', {})

    print(f"\nTimestamp: {results.get('timestamp', 'N/A')}")
    print(f"Mode: {'Mock' if results.get('is_mock') else 'Live API'}")

    print(f"\n--- Overall Metrics ---")
    print(f"  BLEU Score:        {overall.get('bleu_average', 0):.2f} "
          f"(threshold: >= {thresholds.get('bleu_min', 15.0)})")
    print(f"  BERTScore F1:      {overall.get('bert_f1_average', 0):.4f} "
          f"(threshold: >= {thresholds.get('bert_f1_min', 0.85)})")
    print(f"  Term Consistency:  {overall.get('term_consistency_rate', 0):.2f}% "
          f"(threshold: >= {thresholds.get('term_consistency_min', 98.0)}%)")

    print(f"\n--- Per-Direction Results ---")
    for dr in results.get('direction_results', []):
        status = "✅" if dr.get('passed') else "❌"
        print(f"  {status} {dr.get('direction', 'N/A'):20s} | BLEU: {dr.get('bleu_score', 0):5.2f} | "
              f"F1: {dr.get('bert_score_f1', 0):.4f}")

    print(f"\n--- Overall Result ---")
    all_passed = results.get('all_passed', False)
    print(f"  {'✅ ALL PASSED' if all_passed else '❌ SOME FAILED'}")
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(description="Report translation benchmark metrics")
    parser.add_argument(
        "--input",
        type=str,
        default="translation_benchmark_results.json",
        help="Input JSON file from benchmark"
    )
    parser.add_argument(
        "--output-md",
        type=str,
        default="benchmark-report.md",
        help="Output markdown report"
    )
    parser.add_argument(
        "--output-chart",
        type=str,
        default="benchmark-chart.png",
        help="Output chart image"
    )
    parser.add_argument(
        "--no-chart",
        action="store_true",
        help="Skip chart generation"
    )

    args = parser.parse_args()

    # Try multiple possible locations for the input file
    input_paths = [
        args.input,
        "target/" + args.input,
        "../" + args.input,
        "../../" + args.input,
    ]

    results = None
    for path in input_paths:
        try:
            results = load_benchmark_results(path)
            print(f"[INFO] Loaded results from: {path}")
            break
        except FileNotFoundError:
            continue

    if results is None:
        print(f"[ERROR] Could not find benchmark results file")
        print(f"[INFO] Tried: {', '.join(input_paths)}")
        sys.exit(1)

    # Print summary
    print_summary_report(results)

    # Generate markdown report
    md_content = generate_markdown_report(results)
    with open(args.output_md, "w", encoding="utf-8") as f:
        f.write(md_content)
    print(f"\n[INFO] Markdown report saved to: {args.output_md}")

    # Generate chart
    if not args.no_chart and HAS_MATPLOTLIB:
        try:
            create_metrics_chart(results, args.output_chart)
        except Exception as e:
            print(f"[WARN] Chart generation failed: {e}")

    # Output for CI (GitHub Actions)
    if "GITHUB_OUTPUT" in __import__('os').environ:
        with open(__import__('os').environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"bleu_score={overall.get('bleu_average', 0):.2f}\n")
            f.write(f"bert_f1_score={overall.get('bert_f1_average', 0):.4f}\n")
            f.write(f"term_consistency={overall.get('term_consistency_rate', 0):.2f}\n")
            f.write(f"all_passed={str(results.get('all_passed', False)).lower()}\n")


if __name__ == "__main__":
    main()
