import { useState } from "react";
import { translateWithFullContext } from "../lib/translateApi";

export function AiTryTranslate() {
  const [segment, setSegment] = useState("");
  const [sourceLang, setSourceLang] = useState("en");
  const [targetLang, setTargetLang] = useState("zh");
  const [result, setResult] = useState<string | null>(null);
  const [meta, setMeta] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const run = async () => {
    const t = segment.trim();
    if (!t) {
      setErr("请输入要试译的句子");
      return;
    }
    setErr(null);
    setResult(null);
    setMeta(null);
    setLoading(true);
    try {
      const r = await translateWithFullContext({ segment: t, sourceLang, targetLang });
      setResult(r.translation);
      setMeta(
        `模型：${r.model} · ${r.usedMeetingText ? "已带会议材料文本" : "未带会议材料文本"} · ${r.usedImages ? "已带图片" : "未带图片"}`,
      );
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="si-ai-section si-ai-try" aria-labelledby="si-try-heading">
      <h3 id="si-try-heading" className="si-ai-section-title">
        模型试译
      </h3>
      <p className="si-ai-hint">
        将当前「关键词 / 上下文 / 会议材料（PDF 正文）」与「参会图片」一并提交到后端，由多模态模型翻译下面片段。需配置{" "}
        <code className="si-ai-code">OPENAI_API_KEY</code>（或兼容接口）并重启 Java 服务。
      </p>
      <div className="si-try-row">
        <label className="si-try-field">
          <span>源语言</span>
          <input
            className="si-try-input"
            value={sourceLang}
            onChange={(e) => setSourceLang(e.target.value)}
            placeholder="en"
          />
        </label>
        <label className="si-try-field">
          <span>目标语言</span>
          <input
            className="si-try-input"
            value={targetLang}
            onChange={(e) => setTargetLang(e.target.value)}
            placeholder="zh"
          />
        </label>
      </div>
      <textarea
        className="si-ai-textarea si-try-segment"
        rows={3}
        placeholder="输入一句待译原文…"
        value={segment}
        onChange={(e) => setSegment(e.target.value)}
      />
      <div className="si-try-actions">
        <button type="button" className="si-try-btn" disabled={loading} onClick={() => void run()}>
          {loading ? "请求中…" : "调用模型翻译"}
        </button>
      </div>
      {err ? (
        <p className="si-mm-error si-error-pre" role="alert">
          {err}
        </p>
      ) : null}
      {result ? (
        <div className="si-try-out">
          <p className="si-try-out-label">译文</p>
          <p className="si-try-out-text">{result}</p>
          {meta ? <p className="si-try-meta">{meta}</p> : null}
        </div>
      ) : null}
    </section>
  );
}
