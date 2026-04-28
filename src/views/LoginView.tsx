import { useState } from "react";
import { authApi, authSession, ApiException } from "../api";

type Props = {
  onLoggedIn: (s: { token: string; username: string; role: string }) => void;
};

export function LoginView({ onLoggedIn }: Props) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    setLoading(true);
    try {
      const result = await authApi.login({
        username: username.trim(),
        password: password,
      });

      if (result.code !== 0) {
        throw new Error(result.message || "登录失败");
      }

      const data = result.data;
      const role = data.role === "ADMIN" || data.role === "USER" ? data.role : "USER";

      // 保存会话
      authSession.save(data.token, data.username, role, data.userId);

      // 回调通知登录成功
      onLoggedIn({ token: data.token, username: data.username, role });
    } catch (e) {
      if (e instanceof ApiException) {
        setErr(e.message);
      } else if (e instanceof Error) {
        setErr(e.message);
      } else {
        setErr(String(e));
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="si-login">
      <div className="si-login-card">
          <h1 className="si-login-title">聚龙同传</h1>
        <p className="si-login-sub">请登录以继续使用</p>
        <form className="si-login-form" onSubmit={(e) => void submit(e)}>
          <label className="si-login-label">
            用户名
            <input
              className="si-login-input"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label className="si-login-label">
            密码
            <input
              className="si-login-input"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          {err ? (
            <p className="si-login-err si-error-pre" role="alert">
              {err}
            </p>
          ) : null}
          <button type="submit" className="si-login-btn" disabled={loading}>
            {loading ? "登录中..." : "登录"}
          </button>
        </form>
        <p className="si-login-hint">默认：admin/admin123（管理员）、user/user123（用户），首次部署后请修改密码。</p>
      </div>
    </div>
  );
}
