import { loadAiAssistant } from "./aiAssistantStore";
import { resolveApiBaseAndAuth } from "./apiConfig";
import { formatApiError } from "./apiError";
import { getMeetingMaterialsPromptText, listMeetingArtifacts } from "./meetingArtifactsStore";

const MAX_IMAGES = 6;
const MAX_IMAGE_BYTES = 4 * 1024 * 1024;

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  const chunk = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

async function collectImagesForApi(): Promise<{ mimeType: string; base64: string }[]> {
  const all = await listMeetingArtifacts();
  const images = all.filter((a) => a.kind === "image");
  const out: { mimeType: string; base64: string }[] = [];
  for (const a of images) {
    if (out.length >= MAX_IMAGES) break;
    if (a.sizeBytes > MAX_IMAGE_BYTES) continue;
    const b64 = arrayBufferToBase64(await a.blob.arrayBuffer());
    out.push({ mimeType: a.mimeType || "image/jpeg", base64: b64 });
  }
  return out;
}

export type TranslateApiResult = {
  translation: string;
  model: string;
  usedImages: boolean;
  usedMeetingText: boolean;
};

/**
 * 调用后端 `/api/ai/translate`：系统提示词中注入术语、上下文、PDF 等会议材料正文；
 * 用户消息中带待译句 + 多模态图片（Base64）。
 */
export async function translateWithFullContext(input: {
  segment: string;
  sourceLang: string;
  targetLang: string;
}): Promise<TranslateApiResult> {
  const ai = loadAiAssistant();
  const meetingMaterialsText = ai.kbEnabled ? await getMeetingMaterialsPromptText() : "";
  const images = await collectImagesForApi();

  const { baseUrl, authHeaders } = resolveApiBaseAndAuth();
  const res = await fetch(`${baseUrl}/api/ai/translate`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders,
    },
    body: JSON.stringify({
      segment: input.segment,
      sourceLang: input.sourceLang,
      targetLang: input.targetLang,
      kbEnabled: ai.kbEnabled,
      keywordsText: ai.keywordsText,
      contextText: ai.contextText,
      meetingMaterialsText,
      images,
    }),
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(formatApiError(res, text));
  }
  const json = JSON.parse(text) as TranslateApiResult;
  return json;
}
