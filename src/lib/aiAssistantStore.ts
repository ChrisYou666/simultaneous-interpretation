const STORAGE_KEY = "si_ai_assistant_v2";
const LEGACY_KEY = "si_ai_keyword_kb";

export type AiAssistantSettings = {
  /** 是否将下方内容作为知识库注入后续模型请求 */
  kbEnabled: boolean;
  /** 关键词与术语（逗号、换行分隔；可用 = 连接源文与译文） */
  keywordsText: string;
  /** 用户/场景上下文，供模型理解角色与领域 */
  contextText: string;
};

const LIMIT_KW = 800;
const LIMIT_CTX = 400;

function migrateLegacy(): Partial<AiAssistantSettings> | null {
  try {
    const raw = localStorage.getItem(LEGACY_KEY);
    if (!raw) return null;
    const p = JSON.parse(raw) as unknown;
    if (Array.isArray(p) && p.length > 0) {
      const joined = p.filter((x): x is string => typeof x === "string").join(", ");
      return { kbEnabled: true, keywordsText: joined.slice(0, LIMIT_KW), contextText: "" };
    }
  } catch {
    /* ignore */
  }
  return null;
}

export function loadAiAssistant(): AiAssistantSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const p = JSON.parse(raw) as Record<string, unknown>;
      if (p && typeof p === "object") {
        return {
          kbEnabled: typeof p.kbEnabled === "boolean" ? p.kbEnabled : true,
          keywordsText:
            typeof p.keywordsText === "string" ? p.keywordsText.slice(0, LIMIT_KW) : "",
          contextText: typeof p.contextText === "string" ? p.contextText.slice(0, LIMIT_CTX) : "",
        };
      }
    }
    const m = migrateLegacy();
    if (m) {
      const full: AiAssistantSettings = {
        kbEnabled: m.kbEnabled ?? true,
        keywordsText: m.keywordsText ?? "",
        contextText: m.contextText ?? "",
      };
      saveAiAssistant(full);
      return full;
    }
  } catch {
    /* ignore */
  }
  return { kbEnabled: true, keywordsText: "", contextText: "" };
}

export function saveAiAssistant(s: AiAssistantSettings): void {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      kbEnabled: s.kbEnabled,
      keywordsText: s.keywordsText.slice(0, LIMIT_KW),
      contextText: s.contextText.slice(0, LIMIT_CTX),
    }),
  );
}

export const AI_ASSISTANT_LIMITS = { keywords: LIMIT_KW, context: LIMIT_CTX } as const;

/**
 * 将关键词文本拆成条目（供后续拼系统提示或调用后端）。
 * 逗号、分号、换行均可分隔；含 = 时解析为源/目标对。
 */
export function parseKeywordEntries(text: string): { line: string; source?: string; target?: string }[] {
  const parts = text
    .split(/[,，;；\n\r]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  return parts.map((line) => {
    const m = line.match(/^(.+?)\s*=\s*(.+)$/);
    if (m) {
      return { line, source: m[1].trim(), target: m[2].trim() };
    }
    return { line };
  });
}

/**
 * 供后续接入翻译链路：是否在请求中带知识库、手写上下文与会议材料（PDF 正文 + 图片说明）。
 * meetingMaterialsBlock 由 `buildMeetingMaterialsPromptBlock` 生成，可很长，仅在上限内截断。
 */
export function buildPromptInjectionPayload(
  s: AiAssistantSettings,
  meetingMaterialsBlock?: string,
): {
  useKb: boolean;
  glossaryBlock: string;
  contextBlock: string;
  meetingMaterialsBlock: string;
} {
  const useKb = s.kbEnabled;
  const entries = parseKeywordEntries(s.keywordsText);
  const glossaryBlock = entries
    .map((e) => (e.source != null && e.target != null ? `${e.source} → ${e.target}` : e.line))
    .join("\n");
  const meeting =
    useKb && meetingMaterialsBlock?.trim() ? meetingMaterialsBlock.trim() : "";
  return {
    useKb,
    glossaryBlock: useKb ? glossaryBlock : "",
    contextBlock: useKb ? s.contextText.trim() : "",
    meetingMaterialsBlock: meeting,
  };
}
