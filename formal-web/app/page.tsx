"use client";

import { FormEvent, useEffect, useState } from "react";
import { ApiFailure, Identity, mutate, read } from "./api-client";
import { Workbench } from "./workbench";

function navigate(path: string) { window.history.replaceState(null, "", path); }

export default function Page() {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirmation, setConfirmation] = useState("");

  async function restore() {
    try {
      const me = await read<Identity>("/api/me");
      setIdentity(me);
      if (me.authStage === "PASSWORD_CHANGE_REQUIRED") navigate("/password/initial");
      else if (!window.location.pathname.startsWith("/workbench")) navigate("/workbench");
    } catch (error) {
      setIdentity(null);
      navigate("/login");
      if (!(error instanceof ApiFailure && error.status === 401)) setNotice("登录服务暂时不可用，请稍后重试。");
    } finally { setLoading(false); }
  }
  useEffect(() => { void restore(); }, []);

  async function login(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setNotice("");
    try {
      await mutate<Identity>("POST", "/api/auth/login", { phone: phone.trim(), password });
      await restore();
    } catch (error) {
      setNotice(error instanceof ApiFailure && error.code === "AUTHENTICATION_FAILED"
        ? "手机号或密码不正确，请重新输入。" : "登录服务暂时不可用，请稍后重试。");
    } finally { setPassword(""); setBusy(false); }
  }

  async function changePassword(event: FormEvent) {
    event.preventDefault();
    if (busy || notice) return;
    if ([...next].length < 12 || new TextEncoder().encode(next).length > 72) {
      setNotice("新密码至少 12 个字符，UTF-8 编码不超过 72 字节。"); return;
    }
    if (next !== confirmation) { setNotice("两次新密码不一致。"); return; }
    setBusy(true);
    try {
      await mutate<Identity>("POST", "/api/auth/password/initial", { currentPassword: current, newPassword: next });
      await restore();
    } catch (error) {
      if (error instanceof ApiFailure && error.code === "PASSWORD_CHANGED_RELOGIN_REQUIRED") {
        setNotice("密码已修改，但登录状态未能保存。请使用新密码重新登录，无需再次修改。"); setIdentity(null); navigate("/login");
      } else if (!(error instanceof ApiFailure) || error.status === 0 || error.code === "AUTH_OUTCOME_UNCERTAIN") {
        setNotice("本次改密结果尚未确认，请先尝试使用新密码登录；若新密码无效，再尝试原密码。请勿直接重复提交改密。");
        setIdentity(null); navigate("/login");
      } else if (error.status === 401) {
        setNotice("登录状态已失效，请重新登录后确认当前账号状态。"); setIdentity(null); navigate("/login");
      } else {
        setNotice(error.code === "CURRENT_PASSWORD_INVALID" ? "当前密码不正确，请核对后重新填写。"
          : error.code === "CSRF_REJECTED" ? "页面安全校验已失效，请刷新后重新填写。"
          : "改密服务暂时不可用，请稍后确认登录状态。");
      }
    } finally { setCurrent(""); setNext(""); setConfirmation(""); setBusy(false); }
  }

  async function logout() {
    if (busy) return;
    setBusy(true);
    try { await mutate<void>("POST", "/api/auth/logout"); setIdentity(null); navigate("/login"); setNotice(""); }
    catch { setNotice("退出尚未确认，请稍后重试。"); }
    finally { setBusy(false); }
  }
  if (loading) return <p className="loading">正在读取登录状态…</p>;
  if (!identity) return <main className="auth-layout"><section className="intro">
    <div className="brand"><span className="brand-mark">H</span>海卓智慧大脑</div>
    <h1>让复杂工作，<br />从容交给 AI。</h1><p>统一连接 Agent、知识库与业务系统</p>
  </section><section className="auth-panel"><div className="auth-card"><p className="eyebrow">WELCOME BACK</p>
    <h2>登录工作台</h2><p>使用管理员为你开通的手机号和密码。</p>
    {notice && <p className="alert" role="alert">{notice}</p>}
    <form onSubmit={login}><label htmlFor="phone">登录手机号</label><input id="phone" autoComplete="username" value={phone}
      onChange={event => setPhone(event.target.value)} maxLength={32} required disabled={busy} />
    <label htmlFor="password">密码</label><input id="password" type="password" autoComplete="current-password" value={password}
      onChange={event => setPassword(event.target.value)} required disabled={busy} />
    <button className="primary" disabled={busy} type="submit">{busy ? "正在登录…" : "登录"}</button></form>
    <p className="footnote">首次登录需要先修改初始密码。<br />如需更正登录手机号，请联系管理员。</p>
  </div></section></main>;
  if (identity.authStage === "PASSWORD_CHANGE_REQUIRED") return <main className="restricted-layout">
    <header><div className="brand"><span className="brand-mark">H</span>海卓智慧大脑</div>
      <button disabled={busy} onClick={() => void logout()}>退出登录</button></header>
    <section className="password-card"><p className="eyebrow">ACCOUNT SECURITY</p><h1>先保护好你的账号</h1>
      <p>{identity.displayName}，欢迎加入。修改初始密码后，进入数字员工工作台。</p>
      <p className="stage-note">当前仅可访问账号安全页面</p>
      {notice && <p className="alert" role="alert">{notice}</p>}
      <form onSubmit={changePassword} onChange={() => setNotice("")}>
        <label htmlFor="current-password">当前密码</label><input id="current-password" type="password" autoComplete="current-password" value={current} onChange={event => setCurrent(event.target.value)} required disabled={busy} />
        <label htmlFor="new-password">新密码</label><input id="new-password" type="password" autoComplete="new-password" value={next} onChange={event => setNext(event.target.value)} required disabled={busy} />
        <p className="footnote">至少 12 个字符，UTF-8 编码不超过 72 字节。</p>
        <label htmlFor="confirm-password">确认新密码</label><input id="confirm-password" type="password" autoComplete="new-password" value={confirmation} onChange={event => setConfirmation(event.target.value)} required disabled={busy} />
        <button className="primary" type="submit" disabled={busy}>修改密码并进入工作台</button>
      </form><p className="footnote">当前登录 {identity.maskedPhone}</p></section></main>;
  return <Workbench identity={identity} busy={busy} onLogout={() => void logout()} onAuthLost={() => {
    setIdentity(null); navigate("/login"); setNotice("登录状态已失效，请重新登录。");
  }} />;
}
