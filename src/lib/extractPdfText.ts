import * as pdfjsLib from "pdfjs-dist";
// Vite：worker 独立打包，避免主线程解析失败
import pdfWorkerSrc from "pdfjs-dist/build/pdf.worker.min.mjs?url";

let workerConfigured = false;

function ensureWorker(): void {
  if (workerConfigured) return;
  pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerSrc;
  workerConfigured = true;
}

export type ExtractPdfOptions = {
  /** 最多解析页数，防止超大 PDF 卡死 */
  maxPages?: number;
  /** 单份 PDF 提取正文上限（字符） */
  maxChars?: number;
};

const DEFAULT_MAX_PAGES = 120;
const DEFAULT_MAX_CHARS = 200_000;

/**
 * 在浏览器内从 PDF 抽取纯文本，供会议材料作为模型上下文。
 * 扫描版 PDF 无文本层时结果可能接近空，需后续 OCR/服务端处理。
 */
export async function extractTextFromPdfBlob(blob: Blob, options?: ExtractPdfOptions): Promise<string> {
  ensureWorker();
  const maxPages = options?.maxPages ?? DEFAULT_MAX_PAGES;
  const maxChars = options?.maxChars ?? DEFAULT_MAX_CHARS;

  const data = await blob.arrayBuffer();
  const pdf = await pdfjsLib.getDocument({ data }).promise;
  const n = Math.min(pdf.numPages, maxPages);
  const chunks: string[] = [];

  for (let i = 1; i <= n; i++) {
    const page = await pdf.getPage(i);
    const tc = await page.getTextContent();
    const line = tc.items
      .map((item) => (item && typeof item === "object" && "str" in item ? String((item as { str: string }).str) : ""))
      .join(" ");
    chunks.push(line);
    const joined = chunks.join("\n\n");
    if (joined.length >= maxChars) {
      return joined.slice(0, maxChars);
    }
  }

  const text = chunks.join("\n\n").replace(/\u00a0/g, " ").trim();
  return text.length > maxChars ? text.slice(0, maxChars) : text;
}
