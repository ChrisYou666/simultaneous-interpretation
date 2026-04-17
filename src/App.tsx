import { useState } from "react";
import { HashRouter, Routes, Route, Navigate } from "react-router-dom";
import { authSession } from "./api";
import { LoginView } from "./views/LoginView";
import { UserMainView } from "./views/UserMainView";
import { ListenerView } from "./views/ListenerView";

/**
 * 用户会话类型
 */
type Session = {
  token: string;
  username: string;
  role: "ADMIN" | "USER";
};

/**
 * 主持人路由（需要登录）
 */
function HostRoutes({ session, onLogout }: { session: Session; onLogout: () => void }) {
  return (
    <Routes>
      <Route path="/" element={<UserMainView session={session} onLogout={onLogout} />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  // 获取初始会话
  const getInitialSession = (): Session | null => {
    const session = authSession.get();
    if (session) {
      // 验证 role 类型
      const role = session.role === "ADMIN" || session.role === "USER"
        ? session.role
        : "USER";
      return {
        token: session.token,
        username: session.username,
        role,
      };
    }
    return null;
  };

  const [session, setSession] = useState<Session | null>(getInitialSession);

  const handleLogout = () => {
    authSession.clear();
    setSession(null);
  };

  const handleLogin = (s: { token: string; username: string; role: string }) => {
    const validRole = s.role === "ADMIN" || s.role === "USER" ? s.role : "USER";
    setSession({
      token: s.token,
      username: s.username,
      role: validRole,
    });
  };

  return (
    <HashRouter>
      <Routes>
        {/* 听众界面：无需登录 */}
        <Route path="/listener" element={<ListenerView />} />
        {/* 主持人界面：需要登录 */}
        {session ? (
          <Route path="/*" element={<HostRoutes session={session} onLogout={handleLogout} />} />
        ) : (
          <Route path="/*" element={<LoginView onLoggedIn={handleLogin} />} />
        )}
      </Routes>
    </HashRouter>
  );
}
