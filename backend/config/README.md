# 共用资源配置

实际配置集中在 [application.yaml](application.yaml)，已按 `../resource.txt` 直接填入 MySQL、MinIO、Redis 的已知地址和账号密码，并给每个配置项写明中文说明。没有资源环境变量占位符，也不需要复制 example 文件。

直接修改 YAML：

| 位置 | 内容 |
| --- | --- |
| `spring.profiles.active` | 开发写 `dev`，生产改为 `prod`；两者共用同一套服务连接 |
| `spring.datasource` | MySQL JDBC URL、驱动、账号与密码；数据库名原资料未提供，URL 中已明确标注待确认 |
| `brain.resources.minio` | 对象 API、管理控制台、访问账号密码；bucket 原资料未提供，保留空值并注明 |
| `brain.resources.redis` | Redis 地址、端口、密码；当前 `enabled: false`，既定认证仍使用 Session JDBC |
| `spring.sql.init` / `spring.session.jdbc` | 禁止自动初始化、建表和当前阶段的 Session 清理 |
| `server` / `mybatis-plus` | HTTP 端口、基础 Cookie、错误输出与 Mapper 位置 |

从 `backend/` 运行，Spring Boot 自动加载 `config/application.yaml`。开发／生产 HTTP 与 Cookie 差异分别来自 `src/main/resources/application-dev.yaml` 和 `application-prod.yaml`，不需要额外 import。

MySQL 库名、MinIO bucket 和对象前缀尚未由资源文件确定；接入时按真实资源补齐，不根据本机初始化脚本猜测共用服务器状态。MinIO API 原资料未注明协议，当前按同机控制台的 HTTP 协议填写，接入时核实；控制台地址不能用来替代对象 API。

该文件含实际凭据，已被 Git 忽略且位于 Maven resources 之外，不打入 JAR。`resource.txt` 保留为本地交接原件。自动化测试显式只装载 `application-test.yaml`，不读取这里的实际配置。

共用同一数据区时按生产数据处理；本次没有连接服务、建库、建 bucket 或运行清理。模型、MCP 与确认密钥在所属阶段另行填写，不补造尚未提供的值。
