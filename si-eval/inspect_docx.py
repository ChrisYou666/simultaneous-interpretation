"""查看模板 DOCX 的段落文本（用于确定占位符位置）"""
import sys
sys.stdout.reconfigure(encoding="utf-8")
from docx import Document

doc = Document(r"D:\同声传译系统测试方案.docx")
for i, para in enumerate(doc.paragraphs):
    if para.text.strip():
        print(f"[{i}] {para.text[:120]}")
