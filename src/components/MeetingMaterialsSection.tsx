import { useCallback, useEffect, useState } from "react";
import {
  addMeetingImage,
  addMeetingPdf,
  listMeetingArtifacts,
  removeMeetingArtifact,
  type MeetingArtifactRecord,
} from "../lib/meetingArtifactsStore";

type Props = {
  disabled: boolean;
};

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function ImageThumb({ artifact }: { artifact: MeetingArtifactRecord }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const u = URL.createObjectURL(artifact.blob);
    setUrl(u);
    return () => {
      URL.revokeObjectURL(u);
    };
  }, [artifact.blob, artifact.id]);

  if (!url) return <div className="si-mm-thumb si-mm-thumb--empty" aria-hidden />;
  return <img className="si-mm-thumb" src={url} alt="" />;
}

export function MeetingMaterialsSection({ disabled }: Props) {
  const [rows, setRows] = useState<MeetingArtifactRecord[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [busyPdf, setBusyPdf] = useState(false);

  const refresh = useCallback(async () => {
    setRows(await listMeetingArtifacts());
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const mergeUpdate = useCallback((rec: MeetingArtifactRecord) => {
    setRows((prev) => {
      const i = prev.findIndex((x) => x.id === rec.id);
      if (i === -1) return [rec, ...prev];
      const next = [...prev];
      next[i] = rec;
      return next;
    });
  }, []);

  const onPickPdf = async (files: FileList | null) => {
    if (!files?.length || disabled) return;
    setErr(null);
    setBusyPdf(true);
    try {
      for (const file of Array.from(files)) {
        await addMeetingPdf(file, mergeUpdate);
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyPdf(false);
    }
  };

  const onPickImages = async (files: FileList | null) => {
    if (!files?.length || disabled) return;
    setErr(null);
    try {
      for (const file of Array.from(files)) {
        const rec = await addMeetingImage(file);
        mergeUpdate(rec);
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const onRemove = async (id: string) => {
    setErr(null);
    try {
      await removeMeetingArtifact(id);
      setRows((prev) => prev.filter((x) => x.id !== id));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const onDropPdf = (e: React.DragEvent) => {
    e.preventDefault();
    if (disabled) return;
    void onPickPdf(e.dataTransfer.files);
  };

  const onDropImg = (e: React.DragEvent) => {
    e.preventDefault();
    if (disabled) return;
    const list = Array.from(e.dataTransfer.files).filter((f) => f.type.startsWith("image/"));
    const dt = new DataTransfer();
    list.forEach((f) => dt.items.add(f));
    void onPickImages(dt.files);
  };

  return (
    <section className="si-ai-section si-ai-section--materials" aria-labelledby="si-mm-heading">
      <h3 id="si-mm-heading" className="si-ai-section-title">
        会议材料
      </h3>
      <p className="si-ai-hint">
        上传会议报告 PDF（浏览器内抽取正文，体积可较大）与参会人员身份/名片等图片。文件保存在本机 IndexedDB，不会自动上传服务器；接入同传 API
        时可一并作为上下文注入。
      </p>

      {err ? (
        <p className="si-mm-error" role="alert">
          {err}
        </p>
      ) : null}

      <div className="si-mm-grid">
        <div className="si-mm-col">
          <p className="si-mm-label">会议报告（PDF）</p>
          <label
            className={`si-mm-drop ${disabled ? "si-mm-drop--disabled" : ""}`}
            onDragOver={(e) => e.preventDefault()}
            onDrop={onDropPdf}
          >
            <input
              type="file"
              accept="application/pdf,.pdf"
              multiple
              disabled={disabled || busyPdf}
              className="si-mm-file"
              onChange={(e) => void onPickPdf(e.target.files)}
            />
            <span className="si-mm-drop-text">{busyPdf ? "正在处理 PDF…" : "点击或拖入 PDF，可多选"}</span>
            <span className="si-mm-drop-sub">单文件约 40MB 内；文本层过长会截断</span>
          </label>
          <ul className="si-mm-list">
            {rows
              .filter((a) => a.kind === "pdf")
              .map((a) => (
                <li key={a.id} className="si-mm-row">
                  <div className="si-mm-meta">
                    <span className="si-mm-name" title={a.fileName}>
                      {a.fileName}
                    </span>
                    <span className="si-mm-size">{formatBytes(a.sizeBytes)}</span>
                    {a.pdfStatus === "extracting" ? <span className="si-mm-badge">提取中</span> : null}
                    {a.pdfStatus === "done" ? <span className="si-mm-badge si-mm-badge--ok">已解析</span> : null}
                    {a.pdfStatus === "error" ? <span className="si-mm-badge si-mm-badge--err">失败</span> : null}
                  </div>
                  {a.pdfError ? <p className="si-mm-warn">{a.pdfError}</p> : null}
                  <button
                    type="button"
                    className="si-mm-remove"
                    disabled={disabled}
                    onClick={() => void onRemove(a.id)}
                  >
                    移除
                  </button>
                </li>
              ))}
          </ul>
        </div>

        <div className="si-mm-col">
          <p className="si-mm-label">参会人员 / 身份图片</p>
          <label
            className={`si-mm-drop ${disabled ? "si-mm-drop--disabled" : ""}`}
            onDragOver={(e) => e.preventDefault()}
            onDrop={onDropImg}
          >
            <input
              type="file"
              accept="image/*"
              multiple
              disabled={disabled}
              className="si-mm-file"
              onChange={(e) => void onPickImages(e.target.files)}
            />
            <span className="si-mm-drop-text">点击或拖入图片，可多选</span>
            <span className="si-mm-drop-sub">单张约 20MB 内；像素内容需后续多模态/OCR</span>
          </label>
          <ul className="si-mm-list si-mm-list--img">
            {rows
              .filter((a) => a.kind === "image")
              .map((a) => (
                <li key={a.id} className="si-mm-row si-mm-row--img">
                  <ImageThumb artifact={a} />
                  <div className="si-mm-meta">
                    <span className="si-mm-name" title={a.fileName}>
                      {a.fileName}
                    </span>
                    <span className="si-mm-size">{formatBytes(a.sizeBytes)}</span>
                  </div>
                  <button
                    type="button"
                    className="si-mm-remove"
                    disabled={disabled}
                    onClick={() => void onRemove(a.id)}
                  >
                    移除
                  </button>
                </li>
              ))}
          </ul>
        </div>
      </div>
    </section>
  );
}
