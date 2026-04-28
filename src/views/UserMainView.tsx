import { useEffect, useMemo, useState } from "react";
import { AiTryTranslate } from "../components/AiTryTranslate";
import { MeetingMaterialsSection } from "../components/MeetingMaterialsSection";
import { StreamingAsrPanel } from "../components/StreamingAsrPanel";
import {
  AI_ASSISTANT_LIMITS,
  buildPromptInjectionPayload,
  loadAiAssistant,
  saveAiAssistant,
  type AiAssistantSettings,
} from "../lib/aiAssistantStore";
import { formatApiError } from "../lib/apiError";
import { resolveApiBaseAndAuth } from "../lib/apiConfig";
import type { AuthSession } from "../lib/authSession";

function IconLightbulb() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M9 21h6v-1H9v1zm3-19a6 6 0 00-3 11.17V17h6v-3.83A6 6 0 0012 2zm0 10a4 4 0 110-8 4 4 0 010 8z"
        fill="currentColor"
      />
    </svg>
  );
}

function IconClose() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

function IconBrain() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 4c-1.2 0-2.3.4-3.2 1.1A3.5 3.5 0 005 8.5c0 .8.2 1.5.6 2.1A4 4 0 004 14c0 1.8 1.2 3.3 2.9 3.8.3 1.5 1.6 2.7 3.1 2.7h6c1.5 0 2.8-1.2 3.1-2.7 1.7-.5 2.9-2 2.9-3.8a4 4 0 00-1.6-3.4c.4-.6.6-1.3.6-2.1a3.5 3.5 0 00-3.8-3.4A4.2 4.2 0 0012 4z"
        stroke="currentColor"
        strokeWidth="1.4"
        fill="none"
        strokeLinejoin="round"
      />
      <path d="M9 14h6M10 11h4" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  );
}

type Props = {
  session: AuthSession;
  onLogout: () => void;
};

export function UserMainView({ session, onLogout }: Props) {
  const [healthDetail, setHealthDetail] = useState<string>("");
  const [apiErr, setApiErr] = useState(false);
  const [keywordsOpen, setKeywordsOpen] = useState(false);
  const [ai, setAi] = useState<AiAssistantSettings>(() => loadAiAssistant());

  useEffect(() => { saveAiAssistant(ai); }, [ai]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { baseUrl, authHeaders } = resolveApiBaseAndAuth();
        const res = await fetch(`${baseUrl}/api/health`, { headers: { ...authHeaders } });
        const text = await res.text();
        if (!cancelled) {
          if (res.ok) { setApiErr(false); setHealthDetail(""); }
          else { setApiErr(true); setHealthDetail(formatApiError(res, text)); }
        }
      } catch (e) {
        if (!cancelled) { setApiErr(true); setHealthDetail(e instanceof Error ? e.message : "无法连接后端"); }
      }
    })();
    return () => { cancelled = true; };
  }, [session.token]);

  const patchAi = (p: Partial<AiAssistantSettings>) => setAi((prev) => ({ ...prev, ...p }));
  const promptPayload = useMemo(() => buildPromptInjectionPayload(ai), [ai]);

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">聚龙同传</h1>
          <span className="si-brand-sub">中文 / Bahasa Indonesia</span>
        </div>
        <div className="si-topbar-right">
          {apiErr && (
            <span className="si-api-pill--err" title={healthDetail || "后端不可用"}>API 异常</span>
          )}
          <button type="button" className="si-pill-btn" onClick={() => setKeywordsOpen(true)}>
            <IconLightbulb /> 术语表
          </button>
          <button type="button" className="si-logout-btn" onClick={onLogout}>退出</button>
        </div>
      </header>

      <main className="si-main-trilingual">
        <div className="si-host-asr-wrap">
          <StreamingAsrPanel
            floor={1}
            glossary={promptPayload.glossaryBlock}
            context={promptPayload.contextBlock}
          />
        </div>
      </main>

      {keywordsOpen && (
        <div
          className="si-modal-backdrop"
          role="presentation"
          onMouseDown={(e) => { if (e.target === e.currentTarget) setKeywordsOpen(false); }}
        >
          <div
            className={`si-modal si-modal--wide ${ai.kbEnabled ? "" : "si-modal--kb-off"}`}
            role="dialog"
            aria-modal="true"
            aria-labelledby="si-ai-title"
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="si-modal-head">
              <div className="si-modal-title-row">
                <span className="si-modal-title-icon" aria-hidden><IconBrain /></span>
                <h2 id="si-ai-title" className="si-modal-title">AI 助手</h2>
              </div>
              <button type="button" className="si-modal-close" aria-label="关闭" onClick={() => setKeywordsOpen(false)}>
                <IconClose />
              </button>
            </div>

            <div className="si-ai-kb-row">
              <span className="si-ai-label">知识库：</span>
              <button
                type="button"
                className={`si-toggle ${ai.kbEnabled ? "si-toggle--on" : ""}`}
                role="switch"
                aria-checked={ai.kbEnabled}
                onClick={() => patchAi({ kbEnabled: !ai.kbEnabled })}
              >
                <span className="si-toggle-knob" />
              </button>
              <span className="si-toggle-caption">{ai.kbEnabled ? "已启用" : "已关闭"}</span>
            </div>

            <section className="si-ai-section" aria-labelledby="si-kw-heading">
              <h3 id="si-kw-heading" className="si-ai-section-title">关键词</h3>
              <p className="si-ai-hint">
                翻译过程中可能出现的词汇，可用逗号或换行分隔；使用等号连接原文与译文，例如：
              </p>
              <div className="si-ai-tags" aria-hidden>
                <span className="si-ai-tag">Telemedicine</span>
                <span className="si-ai-tag">Biopsy = 活检</span>
              </div>
              <div className="si-ai-field-wrap">
                <textarea
                  id="si-kw-textarea"
                  className="si-ai-textarea"
                  rows={5}
                  maxLength={AI_ASSISTANT_LIMITS.keywords}
                  placeholder="Telemedicine, electronic health records, precision medicine"
                  value={ai.keywordsText}
                  disabled={!ai.kbEnabled}
                  onChange={(e) => patchAi({ keywordsText: e.target.value.slice(0, AI_ASSISTANT_LIMITS.keywords) })}
                />
                <div className="si-ai-counter" aria-live="polite">
                  {ai.keywordsText.length}/{AI_ASSISTANT_LIMITS.keywords}
                </div>
              </div>
            </section>

            <section className="si-ai-section" aria-labelledby="si-ctx-heading">
              <h3 id="si-ctx-heading" className="si-ai-section-title">上下文</h3>
              <p className="si-ai-context-question" id="si-ctx-desc">
                同声传译助手应了解您的哪些信息？（场景、身份、领域等）
              </p>
              <div className="si-ai-field-wrap">
                <textarea
                  className="si-ai-textarea"
                  rows={4}
                  maxLength={AI_ASSISTANT_LIMITS.context}
                  placeholder="I am a foreign trade sales representative based in California, specializing in medical equipment sales."
                  value={ai.contextText}
                  disabled={!ai.kbEnabled}
                  onChange={(e) => patchAi({ contextText: e.target.value.slice(0, AI_ASSISTANT_LIMITS.context) })}
                  aria-describedby="si-ctx-desc"
                />
                <div className="si-ai-counter" aria-live="polite">
                  {ai.contextText.length}/{AI_ASSISTANT_LIMITS.context}
                </div>
              </div>
            </section>

            <MeetingMaterialsSection disabled={!ai.kbEnabled} />
            <AiTryTranslate />

            <p className="si-ai-footnote">
              知识库与术语表会注入翻译模型的 system prompt，帮助提升专业术语准确度。
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
