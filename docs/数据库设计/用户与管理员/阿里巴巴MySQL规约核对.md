# 阿里巴巴 MySQL 规约核对：一期身份数据

## 依据与适用边界

以阿里巴巴官方仓库公开的[建表规约](https://github.com/alibaba/p3c/blob/master/p3c-gitbook/MySQL%E6%95%B0%E6%8D%AE%E5%BA%93/%E5%BB%BA%E8%A1%A8%E8%A7%84%E7%BA%A6.md)、[索引规约](https://github.com/alibaba/p3c/blob/master/p3c-gitbook/MySQL%E6%95%B0%E6%8D%AE%E5%BA%93/%E7%B4%A2%E5%BC%95%E8%A7%84%E7%BA%A6.md)和[SQL 规约](https://github.com/alibaba/p3c/blob/master/p3c-gitbook/MySQL%E6%95%B0%E6%8D%AE%E5%BA%93/SQL%E8%AF%AD%E5%8F%A5.md)核对。该 GitBook 自称与更新版本不完全同步，因此这里明确采用**上述公开文本**作为本轮可复核的规则来源；若企业内部另有更新版，应再逐条对照。

本轮只覆盖一期用户与管理员身份表，不把规则机械套到 AgentScope、Spring Session 或会议系统拥有的外部表结构上。

## 逐项核对

| 规约点 | 本轮设计 |
| --- | --- |
| 表名、列名小写且表名用单数 | 七张表均使用小写英文单数实体名；列名只用小写、数字、下划线。 |
| 每表 `id / gmt_create / gmt_modified` | 每表 `id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY`，两个时间列为 `DATETIME(6) NOT NULL`，由应用按 UTC 写入并在修改时同步更新。`platform_user.id` 即稳定 UserId。 |
| 是/否列 `is_xxx TINYINT UNSIGNED` | `is_password_change_required` 采用 0/1 值，并有 CHECK 限定；Java 属性不要直接带 `is` 前缀，ORM 显式映射。 |
| 非负整数无符号、定长/变长字段选型 | 业务 ID、版本和 HTTP 状态用无符号整数；长度固定或接近的账号状态、审计结果用 `CHAR`，可变长度标识和姓名用 `VARCHAR`，没有浮点列。 |
| 业务唯一性、索引命名与长度 | 手机号、单例键、用户一对一凭据和会议状态、每用户当前号码均有 `uk_` 唯一索引；普通索引用 `idx_`，VARCHAR 索引显式声明索引长度。手机号索引覆盖字段全长，以保留完整唯一性。 |
| 外键、级联与存储过程 | 建表 SQL 不创建外键或级联；验收不创建存储过程。业务关联由服务层事务校验，定期巡检核对。 |
| 表与列注释 | 所有已建表和字段写入中文 `COMMENT`，状态取值变化时同步维护。 |

阿里规约文字要求主键索引名为 `pk_字段名`，而 [MySQL 8.0 将主键索引名固定为 `PRIMARY`](https://dev.mysql.com/doc/refman/8.0/en/create-table.html)。因此物理库只能使用 `PRIMARY`，其余唯一和普通索引仍按 `uk_`、`idx_` 命名。这是数据库语法限制，不以伪造的二级唯一索引代替主键。

## 无外键时的责任归属

唯一索引只能保证“一对一”和“最多一个”；它不能证明 `user_id`、`phone_id` 指向有效且归属正确的记录。服务层必须在一个事务中完成以下检查和写入：

1. 开户先核对手机号归属与全局占用，再新增用户、凭据、当前号码及审计；提交前确认三者的同一 `UserId`。
2. 改号时锁定用户与当前号码，核对新号、将旧号转 `HELD`、登记新 `CURRENT`、提升 `phone_revision/security_version`、作废会议探测并写审计；并发冲突以数据库唯一索引为最后防线。
3. 固定管理员初始化和运维改号须核对目标用户存在且有效；启动时校验恰好一名有效管理员。管理接口不允许增删管理员绑定。
4. 会议检查状态写入时同时核对用户、当前号码归属和 `phone_revision`；会议工具入口再次检查这些条件。HTTP 401 只表示签名或身份校验失败，不直接判定号码不存在。
5. 审计和探测尝试使用只追加的写入权限；账号、密码摘要和历史手机号不硬删除。每天或在启动时执行[身份关联巡检 SQL](../../../db/tests/02_身份关联巡检.sql)，有异常即告警并阻断受影响身份的敏感工具。

本轮空表验收只能验证数据库可表达的约束和巡检查询可执行；服务层事务、并发控制及真实会议 MCP 行为必须在应用实现后另行验收。
