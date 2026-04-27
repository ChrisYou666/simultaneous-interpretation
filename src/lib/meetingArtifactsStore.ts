const DB_NAME = "si_meeting_artifacts";
const DB_VERSION = 1;
const STORE = "artifacts";

export type MeetingArtifactKind = "pdf" | "image";

export type PdfExtractStatus = "pending" | "extracting" | "done" | "error";

export interface MeetingArtifactRecord {
  id: string;
  kind: MeetingArtifactKind;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  blob: Blob;
  createdAt: number;
  /** PDF 抽取的正文（可能截断） */
  extractedText?: string;
  pdfStatus?: PdfExtractStatus;
  pdfError?: string;
}

const MAX_PDF_BYTES = 40 * 1024 * 1024;
const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const MAX_PDF_FILES = 20;
const MAX_IMAGE_FILES = 40;
/** 注入提示词时，会议材料总字数软上限（超出截断） */
export const MEETING_MATERIALS_PROMPT_MAX_CHARS = 500_000;

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onerror = () => reject(req.error ?? new Error("indexedDB open failed"));
    req.onsuccess = () => resolve(req.result);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: "id" });
      }
    };
  });
}

function idbReqToPromise<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("indexedDB request failed"));
  });
}

export async function listMeetingArtifacts(): Promise<MeetingArtifactRecord[]> {
  const db = await openDb();
  const tx = db.transaction(STORE, "readonly");
  const store = tx.objectStore(STORE);
  const all = await idbReqToPromise(store.getAll() as IDBRequest<MeetingArtifactRecord[]>);
  db.close();
  return all.sort((a, b) => b.createdAt - a.createdAt);
}

export async function getMeetingArtifact(id: string): Promise<MeetingArtifactRecord | undefined> {
  const db = await openDb();
  const tx = db.transaction(STORE, "readonly");
  const store = tx.objectStore(STORE);
  const row = await idbReqToPromise(store.get(id) as IDBRequest<MeetingArtifactRecord | undefined>);
  db.close();
  return row;
}

async function putArtifact(rec: MeetingArtifactRecord): Promise<void> {
  const db = await openDb();
  const tx = db.transaction(STORE, "readwrite");
  const store = tx.objectStore(STORE);
  await idbReqToPromise(store.put(rec) as IDBRequest<IDBValidKey>);
  db.close();
}

async function deleteArtifact(id: string): Promise<void> {
  const db = await openDb();
  const tx = db.transaction(STORE, "readwrite");
  const store = tx.objectStore(STORE);
  await idbReqToPromise(store.delete(id) as IDBRequest<void>);
  db.close();
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function validatePdfFile(file: File): string | null {
  if (file.size > MAX_PDF_BYTES) {
    return `单份 PDF 不超过 ${formatBytes(MAX_PDF_BYTES)}（当前 ${formatBytes(file.size)}）`;
  }
  if (file.type !== "application/pdf" && !file.name.toLowerCase().endsWith(".pdf")) {
    return "请选择 PDF 文件";
  }
  return null;
}

export function validateImageFile(file: File): string | null {
  if (file.size > MAX_IMAGE_BYTES) {
    return `单张图片不超过 ${formatBytes(MAX_IMAGE_BYTES)}`;
  }
  if (!file.type.startsWith("image/")) {
    return "请选择图片文件";
  }
  return null;
}

export async function countByKind(kind: MeetingArtifactKind): Promise<number> {
  const all = await listMeetingArtifacts();
  return all.filter((a) => a.kind === kind).length;
}

/**
 * 添加 PDF：先入库，再异步抽取文本并回写。
 */
export async function addMeetingPdf(
  file: File,
  onUpdate: (rec: MeetingArtifactRecord) => void,
): Promise<void> {
  const err = validatePdfFile(file);
  if (err) throw new Error(err);
  if ((await countByKind("pdf")) >= MAX_PDF_FILES) {
    throw new Error(`最多保留 ${MAX_PDF_FILES} 份会议 PDF，请先删除旧文件`);
  }

  const id = crypto.randomUUID();
  const blob = new Blob([await file.arrayBuffer()], { type: file.type || "application/pdf" });
  let rec: MeetingArtifactRecord = {
    id,
    kind: "pdf",
    fileName: file.name,
    mimeType: blob.type,
    sizeBytes: blob.size,
    blob,
    createdAt: Date.now(),
    pdfStatus: "pending",
  };
  await putArtifact(rec);
  onUpdate(rec);

  rec = { ...rec, pdfStatus: "extracting" };
  await putArtifact(rec);
  onUpdate(rec);

  try {
    const { extractTextFromPdfBlob } = await import("./extractPdfText");
    const extractedText = await extractTextFromPdfBlob(blob);
    rec = {
      ...rec,
      pdfStatus: "done",
      extractedText: extractedText.length > 0 ? extractedText : undefined,
      pdfError: extractedText.length === 0 ? "未识别到文本层（可能为扫描件，需 OCR）" : undefined,
    };
  } catch (e) {
    rec = {
      ...rec,
      pdfStatus: "error",
      pdfError: e instanceof Error ? e.message : String(e),
    };
  }
  await putArtifact(rec);
  onUpdate(rec);
}

export async function addMeetingImage(file: File): Promise<MeetingArtifactRecord> {
  const err = validateImageFile(file);
  if (err) throw new Error(err);
  if ((await countByKind("image")) >= MAX_IMAGE_FILES) {
    throw new Error(`最多保留 ${MAX_IMAGE_FILES} 张图片，请先删除旧文件`);
  }

  const id = crypto.randomUUID();
  const blob = new Blob([await file.arrayBuffer()], { type: file.type });
  const rec: MeetingArtifactRecord = {
    id,
    kind: "image",
    fileName: file.name,
    mimeType: blob.type,
    sizeBytes: blob.size,
    blob,
    createdAt: Date.now(),
  };
  await putArtifact(rec);
  return rec;
}

export async function removeMeetingArtifact(id: string): Promise<void> {
  await deleteArtifact(id);
}

/**
 * 拼成可拼进系统提示的大段「会议材料」正文（含截断）。
 */
export function buildMeetingMaterialsPromptBlock(artifacts: MeetingArtifactRecord[]): string {
  const pdfParts: string[] = [];
  const imgParts: string[] = [];

  for (const a of artifacts) {
    if (a.kind === "pdf") {
      const header = `\n--- 会议报告《${a.fileName}》---\n`;
      if (a.pdfStatus === "done" && a.extractedText?.trim()) {
        pdfParts.push(header + a.extractedText.trim());
      } else if (a.pdfStatus === "error" || a.pdfError) {
        pdfParts.push(header + `（解析未完全成功：${a.pdfError ?? "未知错误"}）\n`);
      } else if (a.pdfStatus === "extracting" || a.pdfStatus === "pending") {
        pdfParts.push(header + "（正文提取中或未完成）\n");
      }
    } else {
      imgParts.push(
        `[参会人员/身份相关图片：${a.fileName}，${formatBytes(a.sizeBytes)}；图像已存本地 IndexedDB，多模态或 OCR 接入后可随请求上传处理]`,
      );
    }
  }

  let out = [...pdfParts, imgParts.length ? `\n${imgParts.join("\n")}` : ""].join("\n").trim();
  if (out.length > MEETING_MATERIALS_PROMPT_MAX_CHARS) {
    out =
      out.slice(0, MEETING_MATERIALS_PROMPT_MAX_CHARS) +
      `\n\n…（会议材料已截断至 ${MEETING_MATERIALS_PROMPT_MAX_CHARS} 字）`;
  }
  return out;
}

/** 从 IndexedDB 读取全部材料并生成可注入提示词的正文（供后端/WebSocket 调用前组装）。 */
export async function getMeetingMaterialsPromptText(): Promise<string> {
  const list = await listMeetingArtifacts();
  return buildMeetingMaterialsPromptBlock(list);
}
