#36 开发、隔离与真实验收已完成。

## 结论
固定 24h 绝对期限、活动不续期、Cursor 内嵌页与系统 Chrome 并行登录、仅退出当前认证会话均有证据。受控时钟只用于自建回环实例。`business_run` 与外部探测为 0。未部署、未改来源地图。

## 证据
- 隔离 20 项：`verification/issue-36/isolated-deadline.json`
- 真实并行／退出：`verification/issue-36/real-parallel.json`
- 浏览器活动不续期／同标签共享／退出回登录：`browser-activity.json`、`browser-same-tab-share.json`、截图
- 正式 JAR SHA-256 `1fc609dd9c8df0ef895198825903911bb24477a22a45022e92c28663b20486b9`

## 验收对照
- [x] 持久 authenticatedAt／absoluteExpiresAt；受控时钟覆盖到期前／恰到期／到期后
- [x] 刷新／受保护读取／CSRF 持续读取不延长绝对期限
- [x] 两独立浏览器有效；同浏览器标签共享；退出只撤销当前会话
- [x] 退出后受保护动作拒绝并回登录；同浏览器标签共享失效边界成立
- [x] 登录／退出 CSRF；prod／dev Cookie 分离与 HttpOnly／Secure／SameSite／作用域
- [x] 退出／到期路径不生成 Run 或会议取消意图
