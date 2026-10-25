# MyBatis-Plus 接入与事务接缝补充核验

> 日期：2026-10-24，Asia/Shanghai。议题：[后端工程与用户链路 #22](https://github.com/BeyondzychenProton/agentscope-brain/issues/22)。  
> 状态：官方文档及固定源码的静态核验。用户已确认 Spring MVC，并选择 MyBatis-Plus 操作应用数据库，替代上一份研究中的 Spring JDBC 应用数据访问候选；本页不重新决议该选择。未安装依赖、编译、执行 SQL／DDL 或连接真实模型／会议接口。

## 1. 选择与研究边界

沿用[后端工程与用户链路设计草稿](../../正式应用设计/后端工程与用户链路设计草稿.md)中的单 Spring Boot 应用、单 Maven 模块及普通用户优先范围。本页只核验 Boot 3 接入、SQL 表达与事务、可选插件边界；不增加管理员功能，不决定全局启用插件，不修改已确认数据库结构。

MyBatis-Plus／MyBatis 官方网页是可更新的第一方资料，本次用于核验能力，不据页面示例版本宣称已经锁定项目依赖；精确 MyBatis-Plus、Boot BOM、MyBatis-Spring、JSQLParser 与 JDK 组合留后续核对。AgentScope 固定源码仍为既有研究的 `v2.0.4` 提交 `0b6743b263ba8e39eb417e35048026f1e0196548`。

## 2. 五组官方证据与接入限制

### S1：使用 Boot 3 专用 starter，避免重复引入底层组件

**官方事实。** Boot 3 对应 `com.baomidou:mybatis-plus-spring-boot3-starter`；它与 Boot 2、Boot 4 的 starter 分开。官方提醒，引入 MyBatis-Plus 后不要再次单独引入 MyBatis、`mybatis-spring-boot-starter` 或 MyBatis-Spring，以免版本差异。官方另提供 `mybatis-plus-bom`；从 3.5.9 起部分插件依赖改为可选模块，需按实际能力选择。[MyBatis-Plus：安装、Boot 3 与 BOM](https://baomidou.com/getting-started/install/)

**项目限制。** 后续应核对 Maven 有效依赖树与 Boot BOM 交集，不能把所有示例依赖叠加或任意覆盖传递版本。引入 starter 不等于决定分页／乐观锁等插件，也不允许因此开启自动维护 DDL、生成或改造既有业务表。

### S2：通用 CRUD 与自定义 Mapper／XML SQL 可以并存

**官方事实。** MyBatis-Plus 的 `BaseMapper` 提供通用查询、插入、更新和删除接口；MyBatis 原生 Mapper XML 仍能定义 `select`／`insert`／`update`／`delete` 及结果映射。`#{...}` 形成预编译参数；`${...}` 是直接字符串替换，不能视作值参数绑定。[MyBatis-Plus：持久层 Mapper 接口](https://baomidou.com/guides/data-interface/)、[MyBatis 3：Mapper XML 与参数](https://mybatis.org/mybatis-3/sqlmap-xml.html)

**项目候选。** 普通 CRUD 可复用通用 Mapper；锁定读取、固定锁序、多列条件状态迁移、计数分配、幂等回查及投影批次等关键 SQL，可通过明确命名的自定义 Mapper／XML 表达。SQL 是否满足业务协议仍须逐句核验，并检查条件更新影响行数。不能只因继承 `BaseMapper` 就将任意修改／删除能力直接暴露成业务动作；排序列和其他 SQL 结构必须由服务端限定。

### S3：MyBatis-Spring 参与 Spring 事务，但必须使用同一 DataSource

**官方事实。** MyBatis-Spring 复用 Spring 的事务管理器，在事务期间使用受管理的 SqlSession；`SqlSessionFactoryBean` 与事务管理器必须使用同一个 DataSource。受管理 SqlSession 不能自行调用 commit／rollback／close；在 Spring 事务外调用 Mapper，操作会自动提交。[MyBatis-Spring：事务](https://mybatis.org/spring/transactions.html)

**项目限制。** 涉及消息／Run／交互／操作账本／投影等多表业务事实，仍需在明确的服务事务中提交，不能靠 Mapper 单次自动提交拼成原子流程。平台锁序、CAS、成功唯一性、审计与投影同事务，以及提交结果不明后的原键回查，均不是 ORM／Mapper 自动提供的保证。异步执行线程仍需自行建立事务；Spring 本地事务不会自动传播到新线程。[Spring Framework 6.2：Transactional 的线程边界](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/annotation/Transactional.html)

### S4：乐观锁、逻辑删除、分页各有局部职责，不替代业务协议

| 能力 | 官方行为 | 本项目边界 |
| --- | --- | --- |
| 乐观锁 | `OptimisticLockerInnerInterceptor` 配合 `@Version` 处理支持的更新方式；整数版本递增并回写实体。自定义更新还需自行实现匹配的更新 SQL；`update(entity, wrapper)` 的 wrapper 不能复用。 | 不能替代多表锁序、授权复核、复合状态条件、发送领取 fence、幂等键或同事务投影；不能仅给实体加注解就宣布全部自定义 SQL 具备 CAS。 |
| 逻辑删除 | 相关生成操作会过滤已删除记录、阻止其更新，并把删除转换为标记更新；可全局配置，也可用 `@TableLogic` 限定实体。 | 逻辑删除不是保留策略、归档或审计模型。不能强制给历史业务表增加删除列，也不能把归档／停用／撤权当作删除而隐藏应可读取的历史。 |
| 分页 | `PaginationInnerInterceptor` 支持 MySQL、IPage 及自定义 Mapper；可设置单页上限。3.5.9 起须另行考虑 JSQLParser 模块；多插件时官方要求分页放最后，并注意 COUNT 的 join 优化。 | 不能自动提供本人资源过滤、全范围标题搜索、固定排序、游标／固定水位快照或投影字节预算；复杂查询须验证实际分页及 COUNT 语义。 |

来源：[乐观锁插件](https://baomidou.com/plugins/optimistic-locker/)、[逻辑删除](https://baomidou.com/guides/logic-delete/)、[分页插件](https://baomidou.com/plugins/pagination/)。本表仅列能力及限制，**没有决定任何插件全局启用**；逐表／逐方法的适用性与实际 SQL 留实施设计核验。

### S5：应用采用 MyBatis-Plus，不要求改写框架原生 JDBC 存储

**官方事实。** 固定 AgentScope 源码中的 `JdbcAgentStateStore` 使用 DataSource、JDBC Connection／PreparedStatement 及其方言实现；Spring Session JDBC 也有自己的会话表与持久化实现，默认会话 JDBC 操作使用 `REQUIRES_NEW`。[固定 AgentScope JDBC 状态源码](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-jdbc/src/main/java/io/agentscope/extensions/jdbc/state/JdbcAgentStateStore.java)、[Spring Session 3.5：JDBC 与事务](https://docs.spring.io/spring-session/reference/3.5/configuration/jdbc.html)

**项目推论。** MyBatis-Plus 的应用持久化选择不要求把 SDK 状态、Workspace KV 或 Spring Session JDBC 一并改写成 Mapper；其原生后端可保留，分别验证隔离、连接和事务。它们与平台共享 MySQL／DataSource，也不能据此推定同一次提交；既有框架状态 checkpoint、平台 SQL、对象原件错开后的待核对与恢复协议继续适用。是否采用某个具体 SDK JDBC／MySQL实现仍按既有存储设计及后续精确接入验证确定。

## 3. 后续最小验证

1. Boot 3 starter 与有效依赖树；插件若需启用，再核对对应解析器模块及版本，不提前引入整套可选能力。
2. 既有主键／字段／JSON／时间／版本的真实映射；通用 CRUD 与自定义 SQL 的实际输出、参数绑定及影响行数。
3. 同一事务管理器／DataSource 下的回滚、锁序／CAS、多表投影原子提交；异步线程和事务外调用不得被误认为同一业务事务。
4. 若选用插件，逐条验证乐观锁更新、历史可见性、COUNT／分页稳定性及插件叠加结果；业务事实和历史不能被插件静默改义。
5. SDK 原生状态存储及候选 Session JDBC 的独立往返、权限与恢复；本页未运行这些验证。

既定业务依据：[交互生命周期规格](../../正式应用设计/动态表单与交互请求生命周期设计规格.md)、[消息／事件／恢复投影规格](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)、[全库锁序与事务责任](../../数据库设计/一期全库实体关系与约束评审.md#5-跨模块锁顺序提案)。上一份[后端 Web 与登录持久化核验](后端Web与登录持久化接缝补充核验.md)中的原生 JDBC／事务事实仍可参考，其应用数据访问候选已由本轮 MyBatis-Plus 决定替代。
