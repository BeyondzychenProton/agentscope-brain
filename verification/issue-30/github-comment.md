## 隔离验证结果（2026-10-26）

阶段 1 / 07 数据库契约已在一次性本机 MySQL 8.0.45 隔离实例完成。只证明 `session_intent`／`media_upload`／`ordinary_submission` 的结构、约束、巡检与合成竞争；不表示 HTTP 准备／上传／接纳／close、MinIO 或真实业务 Run 通过。

### 命令

```powershell
python "db\tests\执行新建意图暂存与普通提交隔离验收.py" `
  --mysql-bin "D:\code environment\mysql\mysql-8.0.45-winx64\bin" `
  --report "verification\issue-30\probe-summary.json"
```

exit 0。2026-10-26T11:16:16+08:00。全库 32 表；约束拒绝 23、正向 10、跨行巡检 8、定向异常 8、合法场景 2、竞争探针 8。

### 验收对照

| 验收要点 | 结论 |
| --- | --- |
| 新建意图冻结目标／能力，同 owner＋clientIntentId 唯一，至多绑定一个业务会话；错配拒绝或检出 | 通过 |
| 暂存票据与正式消息素材分开；配对、单次 BOUND、TTL 不作正式删除规则 | 通过 |
| 普通提交永久身份；纯文字空清单与未登记原键墓碑可区分；成功结果约束成立 | 通过 |
| 封闭先提交迟到拒绝；接纳先提交封闭读回原结果；首次插入、同键异参、意图绑定与条件更新竞争可复现 | 通过 |
| 绑定／禁绑清理竞争、ACCEPTED 缺结果、BOUND 无正式引用、owner／目标错配；保留原消息与会话规则 | 通过 |
| 平台 SQL 提交后按原控制键读回；不宣称 HTTP／对象写入／真实 Run 通过 | 通过 |

### 证据

- `verification/issue-30/验收记录.md`
- `verification/issue-30/probe-summary.json`
- `db/schema/07_新建意图暂存与普通提交建表.sql`
- `db/tests/16_新建意图暂存与普通提交建表验收.sql`
- `db/tests/17_新建意图暂存与普通提交关联巡检.sql`
- `db/tests/18_新建意图暂存与普通提交巡检种子.sql`
- `db/tests/19_新建意图暂存与普通提交定向异常验收.sql`
- `db/tests/执行新建意图暂存与普通提交隔离验收.py`

### 分层结论

| 分层 | 结论 |
| --- | --- |
| 开发完成 | 三表 DDL 与隔离探针已落地 |
| 隔离验证 | 一次性隔离实例通过 |
| 真实验收 | 未做；阶段 2／3 |
| 阻塞项 | 无；缺口只阻断正式上传／接纳／close 流程验收 |

### 未纳入

正式 HTTP／Web、MinIO、真实 Run、向共用开发库执行 DDL、提交／推送／部署。

来源 Map #14／路线 #23 已关闭；开放数据库地图 #1 另记本条完成摘要。
