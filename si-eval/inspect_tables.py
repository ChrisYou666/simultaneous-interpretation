"""查看模板 DOCX 的表格内容"""
import sys
sys.stdout.reconfigure(encoding="utf-8")
from docx import Document

doc = Document(r"D:\同声传译系统测试方案.docx")
for i, table in enumerate(doc.tables):
    print(f"\n=== 表格 {i} (行数={len(table.rows)}, 列数={len(table.columns)}) ===")
    for ri, row in enumerate(table.rows[:5]):  # 前5行
        cells = [cell.text[:40].replace('\n',' ') for cell in row.cells]
        print(f"  [{ri}] {' | '.join(cells)}")
    if len(table.rows) > 5:
        print(f"  ... ({len(table.rows)} rows total)")
