# 后端 Web 与登录持久化接缝补充核验

> 日期：2026-10-24，Asia/Shanghai。议题：[正式后端与受控维护方案 #22](https://github.com/BeyondzychenProton/agentscope-brain/issues/22)。  
> 状态：第一方版本化文档及固定源码的静态研究；未安装依赖、创建正式工程、编译、执行 SQL／DDL、调用真实模型或会议接口。本页提供候选依据，不替用户确认 Web、数据访问或登录组件，也不宣称已完成依赖兼容验证。

> 后续选择更新：用户已在 #22 Q3 采用 Spring MVC，Q4 指定 MyBatis-Plus 访问平台业务数据库，并采用 Q5 Spring Security + Spring Session JDBC、Q6 同源 HTTPS 入口。本文 S2 的 Spring JDBC 能力作为事务事实与研究历史保留，不代表当前应用数据访问选型；最新决定见[后端工程与用户链路设计草稿](../../正式应用设计/后端工程与用户链路设计草稿.md)。精确依赖和真实接入仍待后续核对。

## 1. 研究范围与版本边界

本轮用户已确认单 Spring Boot 应用、单 Maven 模块、按业务能力分包，Web、Agent 运行和后台协调同进程。当前任务先交付普通用户功能，后续实施时预置一个普通用户账号及必要配置；管理员角色、后台、开户／重置／授权／发布维护功能延期另一任务，不采用本机维护命令作为本任务交付。真实登录、首次改密和可信身份继续保留；本页没有实际预置数据。决定记录见[后端工程与用户链路设计草稿](../../正式应用设计/后端工程与用户链路设计草稿.md)。

以下使用 Spring Framework **6.2 系列**、Spring Security **6.5 系列**、Spring Session **3.5 系列**的版本化文档；补充固定 `v6.2.12` Framework 源码与 `3.5.6` Session 源码以定位实现。它们是能力核验范围，不是已选择的一组项目依赖，也不代表已核对当前最新补丁。AgentScope 沿用既有研究的 `v2.0.4` 固定提交 `0b6743b263ba8e39eb417e35048026f1e0196548`；精确依赖及 BOM 交集留后续锁版核对。

## 2. 五组官方证据及限制

### S1：Servlet MVC 可以异步提供 SSE，也可以适配 reactive 返回值

**框架事实。** Servlet 异步处理允许 Filter／Servlet 链退出请求线程而保持响应开放；MVC 的 `SseEmitter` 支持由其他线程发送 SSE。MVC 还可通过 `ReactiveAdapterRegistry` 适配 `Mono`／`Flux`；流式媒体类型的多值流可以按 emitter 方式处理。因此，仅看到 SDK 返回 reactive 类型，不能推出应用必须使用 WebFlux。[Spring Framework 6.2：异步请求、SSE、Reactive Types](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)

**限制。** MVC 的响应写出仍可能阻塞；reactive 响应写出使用独立执行器，不等于全过程非阻塞。Servlet／Filter 要支持异步分派，超时依赖配置及容器；默认异步执行器不适合负载下的生产使用。Servlet 也不保证立即获知客户端消失，需周期写入及异常回调发现断开。[同一官方文档：比较、Disconnects、Configuration](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html#webmvc-ann-async-vs-webflux)

**项目候选。** MVC + `SseEmitter` 足以作为已确认单应用下的 Web 候选。执行器边界、发送队列／慢读者、心跳、超时及代理行为仍须设计和验证；emitter 内存对象本身不提供持久补读、游标、权限复核或进程重启恢复。

### S2：Spring JDBC 保留显式 SQL；事务绑定 DataSource 连接及执行线程

**框架事实。** `JdbcTemplate` 处理 JDBC 资源、参数化语句和异常转换，调用方提供 SQL；`NamedParameterJdbcTemplate` 增加具名参数并委托给 `JdbcTemplate`。`DataSourceTransactionManager` 面向一个 JDBC DataSource，将连接绑定当前线程；`JdbcTemplate` 经 `DataSourceUtils` 取得相应连接。[Spring Framework 6.2：JDBC 核心类](https://docs.spring.io/spring-framework/reference/6.2/data-access/jdbc/core.html)、[固定 v6.2.12 事务管理器源码](https://github.com/spring-projects/spring-framework/blob/v6.2.12/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#L38-L69)

`@Transactional` 配合普通 `PlatformTransactionManager` 的事务不会自动传播给方法内新建的线程；响应变成异步流也不改变这点。[Spring Framework 6.2：Transactional 事务管理语义](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/annotation/Transactional.html)

**项目候选。** 显式 SQL 便于逐句落实已有 MySQL 锁序、条件更新／CAS、影响行数及同事务投影；正确性仍由实际 SQL、同一事务管理器／DataSource、连接获取方式及错误处理证明。后台线程须自行建立短事务，不能把请求线程事务延伸到模型调用／SSE 生命周期；SDK 自行获取连接或对象存储调用也不会仅因共享数据库而自动加入平台事务。这里是接入推论，不是已通过的应用事务验收。

### S3：Spring Session JDBC 可以保存 HttpSession，但有独立事务及 Cookie 行为

**框架事实。** Spring Session JDBC 使用数据库保存会话，默认有 `SPRING_SESSION`／`SPRING_SESSION_ATTRIBUTES` 两表及对应数据库脚本；JDBC 操作默认使用 `REQUIRES_NEW` 事务。其事务配置可以定制，但不能因为与业务表使用相同 DataSource 就声称登录会话和业务提交天然原子。[Spring Session 3.5：JDBC 存储与事务](https://docs.spring.io/spring-session/reference/3.5/configuration/jdbc.html)

固定 `3.5.6` 的 `DefaultCookieSerializer` 默认启用 HttpOnly，SameSite 为 Lax，Secure 未显式配置时取 `request.isSecure()`，均有可配置接缝。[固定 Cookie 序列化源码](https://github.com/spring-projects/spring-session/blob/3.5.6/spring-session-core/src/main/java/org/springframework/session/web/http/DefaultCookieSerializer.java#L63-L86)、[Secure／HttpOnly 配置](https://github.com/spring-projects/spring-session/blob/3.5.6/spring-session-core/src/main/java/org/springframework/session/web/http/DefaultCookieSerializer.java#L254-L280)

**项目候选。** 用服务端会话 Cookie 配合 JDBC 保存登录状态，不把身份凭据放入聊天草稿。生产 HTTPS、反向代理下安全请求识别、Cookie 的 Secure／HttpOnly／SameSite／Path 及超时须按实际部署明确验证；HttpOnly 不等于 CSRF 防护。认证基础设施表应与平台 29 表及 AgentState 分开核对，不能直接复制文档中的其他数据库示例或据此执行初始化。

### S4：Spring Security 提供会话认证与请求授权；首次改密限制须由平台实现

**框架事实。** Spring Security 通过 `SecurityContextRepository` 保存／加载认证，可使用 HttpSession；对需要跨请求保留的自定义认证，不能只修改当前线程的 `SecurityContextHolder` 而忽略持久保存路径。请求级授权可以按匹配规则和 authority 控制。[Spring Security 6.5：认证持久化](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/persistence.html)、[请求授权](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html)

Spring Security 默认保护 POST 等不安全方法的 CSRF，默认 token repository 基于 HttpSession。SPA 还须处理 token 取得、请求头携带及登录／退出后 token 更新，不能因 JSON 接口或 Cookie 带 SameSite 就直接关闭保护。[Spring Security 6.5：CSRF 与 SPA 接入](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)

**项目责任。** 数据库初始凭据标识、仅可改密的受限身份／允许动作、改密后会话更新，以及每次读取／写入复核账号和安全版本，均属平台规则；框架不会自动理解 `is_password_change_required`。可用定制授权规则实施受限入口，但不能仅靠前端跳转阻止未改密用户读取业务数据、创建 Run 或调用工具。本任务不因此增加管理员功能。CSRF token 若需要供脚本读取，应与仍保持 HttpOnly 的登录会话 Cookie 区分。

### S5：AgentScope reactive 执行流需要平台适配，不能成为浏览器订阅的执行源

**固定源码事实。** AgentScope 的 `AgentRun` 持有 Reactor `Flux`，是惰性、单次订阅的执行句柄；再次订阅被拒绝，取消订阅会取消该句柄。[固定 AgentRun 源码](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/agent/AgentRun.java#L32-L141)

**项目推论。** 结合 S1，SDK 可以留在后端受控执行适配层，由平台消费其事件并按既有协议落库；MVC 的 SSE 读取持久投影。因此 SDK 的 reactive 返回值不强制 WebFlux，浏览器连接取消也不能直接沿用 SDK 执行源的取消传播。仅把 SDK Flux 返回 Controller 不能满足“关闭页面不取消 Run、刷新不重新执行”的既定规则。这里没有新建适配器或验证订阅生命周期。

## 3. 仍需后续证据

1. 精确 Spring Boot BOM、Security／Session／JDBC／Servlet 容器与 AgentScope 的依赖交集；本次不追最新补丁、不生成锁定依赖。
2. MVC 的有界执行／发送队列、长连接清理、代理缓冲与超时；与既定 SSE 补读和慢消费者规则联合验证。
3. MySQL 实际连接和事务边界、锁序／CAS、回滚与提交结果不明、异步线程独立事务及投影同事务提交。
4. JDBC 登录会话真实重启往返、HTTPS／Cookie、CSRF、首次改密前受限访问、安全版本失效及跨用户资源拒绝。
5. SDK 单次消费、后台执行归属、页面断开／刷新不取消或重复执行，以及已有 checkpoint／pending／UNKNOWN 恢复门槛。

既有业务依据：[交互生命周期规格](../../正式应用设计/动态表单与交互请求生命周期设计规格.md)、[消息／事件／投影规格](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)、[工作台接口交接第 8 节](../../正式应用设计/工作台交互与前端工程设计规格.md#8-向-22-的接口交接)。本页未覆盖管理员延期任务的维护实现，也未以研究结论替代真实集成验收。
