# Issue24 固定制品与校验接口核验

> 核验日期：2026-10-25，Asia/Shanghai。服务于 [#24 固定后端依赖与双入口 Schema 准入](https://github.com/BeyondzychenProton/agentscope-brain/issues/24)。
> 本文只记录官方制品及固定源码的实施输入；不重新选型，不代表目标 JDK 安装、依赖求解、编译、Boot 装配或两个校验入口已运行通过。实际运行结论由本工单验收证据另行记录。
>
> 后续更正：用户确认本次采用现有 Oracle JDK 17。Temurin 是原设计部署候选，本节 Windows 资产缺失事实不再构成 #24 的硬阻塞；Oracle 官方 ZIP 校验及本机 416 文件逐项一致的证据见[验收记录](../../../archive/stage0-backend/evidence/issue24/验收记录.md)。原静态调查事实保留，以下涉及阻塞的旧判定由该更正取代。

## 1. Temurin 目标 Windows 制品缺失

本次通过 GitHub 官方 API 实时读取 `jdk-17.0.21+9`，发布编号 `421004792`，发布时间 `2026-10-22T17:28:09Z`。另对 assets API 使用 `per_page=100` 和分页读取，共 **58 个资产，名称包含 `windows` 的资产为 0**。因此本次不能从该固定发布取得 Windows x64 HotSpot JDK ZIP 或它的官方 SHA-256，不把已存在的 GA 标签等同于 Windows 构建输入齐备。[固定发布 API](https://api.github.com/repos/adoptium/temurin17-binaries/releases/tags/jdk-17.0.21%2B9)、[完整资产 API](https://api.github.com/repos/adoptium/temurin17-binaries/releases/421004792/assets?per_page=100)

官方 API 当前列出的 Linux x64 JDK 为 `OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz`，其 `digest` 为 `sha256:8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711`；该事实不能替代 Windows 目标证明。本核验没有下载或运行 Linux 包。[Linux 制品](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz)

该查询只说明 Temurin 候选的 Windows 制品当时缺失。本次用户已接受现有 Oracle JDK 17，并补齐其官方制品校验；不宣称 Temurin 本身已经验收，也不再要求等待该候选才能完成 #24。

复现只读查询：

```powershell
gh api repos/adoptium/temurin17-binaries/releases/tags/jdk-17.0.21+9 --jq '{id, tag_name, published_at, count:(.assets|length), windows:[.assets[]|select(.name|contains("windows"))|.name]}'
gh api --paginate 'repos/adoptium/temurin17-binaries/releases/421004792/assets?per_page=100' --jq '{count:length,windows:[.[]|select(.name|contains("windows"))|.name]}'
```

## 2. Maven 与 Wrapper 校验接缝

### 2.1 Maven 3.9.16

官方 ZIP URL 的 HEAD 请求返回 HTTP 200；官方 `.sha512` GET 返回 HTTP 200，正文为以下 128 位十六进制 SHA-512。实现时需对真实下载字节核验该值，再计算 Wrapper 使用的 SHA-256，不能把 SHA-512 放进 `distributionSha256Sum`。[官方 ZIP](https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip)、[官方 SHA-512](https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip.sha512)

```text
ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3
```

Central 同名 `apache-maven-3.9.16-bin.zip.sha256` 请求本次返回 **404**。这只说明该摘要 URL 不存在；不说明 ZIP 不存在，不虚构官方 SHA-256。[所查 URL](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip.sha256)

### 2.2 Wrapper 3.3.4

Apache 官方 `maven-wrapper-3.3.4` 注解 tag 对象 `c82bcee2275b9ee2afd0fab17fdec05e1c6f67aa` 解引用为 commit **`524486aff97d0748926a977665d5befb3251ff17`**。本次读取该固定提交的 `only-mvnw` 与 `only-mvnw.cmd`；这是发布脚本的源码模板，版本位置仍为构建占位符，正式 Wrapper 文件应取发布分发包或正式生成结果。[tag API](https://api.github.com/repos/apache/maven-wrapper/git/ref/tags/maven-wrapper-3.3.4)、[tag 对象](https://api.github.com/repos/apache/maven-wrapper/git/tags/c82bcee2275b9ee2afd0fab17fdec05e1c6f67aa)

Windows `only-mvnw.cmd` 的 PowerShell 段设置 `$ErrorActionPreference = "Stop"`，下载 ZIP 后读取 `distributionSha256Sum`，使用 `Get-FileHash -Algorithm SHA256`；摘要不一致通过 `Write-Error` 中止。它不依赖 Wrapper JAR，所以 `only-script` 类型无需以 `wrapperSha256Sum` 冒充 Maven 分发包校验。[固定 Windows 脚本](https://github.com/apache/maven-wrapper/blob/524486aff97d0748926a977665d5befb3251ff17/maven-wrapper-distribution/src/resources/only-mvnw.cmd)

需要在构建证据中区分以下边界：

- 两平台脚本命中已解压 Maven 缓存时，都会在下载／校验分支前直接执行；属性存在不等于每次运行都重新核验缓存。干净 Wrapper 缓存的首次下载和错误摘要拒绝应单独验证。
- Unix 脚本在没有 `unzip` 时把 ZIP URL 改为 `.tar.gz`，仍使用同一个摘要属性。锁定 ZIP 摘要的执行环境应具备 `unzip`；不能把 Windows 通过扩展为所有 Unix 前提通过。[固定 Unix 脚本](https://github.com/apache/maven-wrapper/blob/524486aff97d0748926a977665d5befb3251ff17/maven-wrapper-distribution/src/resources/only-mvnw)

## 3. SDK 原生 ToolValidator 接口

以下均来自已定 AgentScope Java commit **`0b6743b263ba8e39eb417e35048026f1e0196548`**，本核验没有替换或修改 SDK。[固定源码](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/ToolValidator.java)

```java
public final class ToolValidator {
    private ToolValidator();
    public static String validateInput(String input, Map<String, Object> schema);
}
```

该入口无声明 `throws`；空／null schema 直接返回 null，因此不能用空 schema 构造“兼容成功”的样本。非空 schema 的真实路径是：

1. 静态 Registry 使用 `SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)`。
2. SDK `JsonUtils.getJsonCodec().toJson(schema)` 把 Jackson 2 侧结构序列化为 String。
3. `SCHEMA_REGISTRY.getSchema(schemaJson)` 编译 Schema。
4. SDK 自身 Jackson 2 `ObjectMapper` 处理可选 null 属性，然后调用 `jsonSchema.validate(normalizedInput, InputFormat.JSON)`。
5. 无错误返回 null；错误通过 `getInstanceLocation()` 与 `getMessage()` 拼成 String。内部 `catch (Exception)` 返回 `Schema validation error: ...`；`LinkageError` 不属于该 catch。

探针需要直接调用已发布 SDK JAR 的这个原生入口，覆盖合法非空 schema、结构／类型非法输入以及默认 2020-12 方言；不能重写一个“等价校验器”冒充 SDK 准入。SDK 对可选 null 的裁剪行为属于该原生入口，不应默认为应用 form-v1 也应裁剪用户输入。

## 4. networknt 3.0.6 固定接口

以下对应 commit **`12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1`**。固定源码仍提供 SDK 依赖的 `SchemaRegistry.withDefaultDialect(SpecificationVersion)`、`getSchema(String)`、`Schema.validate(String, InputFormat)` 和 `InputFormat.JSON`。String 参数隔开 Jackson 2／3 的节点类型，但这些源码方法存在本身不能证明发布 JAR 的所有二进制交点均兼容；仍需 #24 同一实际 classpath 运行。[Registry](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/SchemaRegistry.java)、[Schema](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/Schema.java)、[InputFormat](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/InputFormat.java)

应用侧可使用 `SchemaRegistry.withDialects(List<Dialect>, Consumer<SchemaRegistry.Builder>)` 只注册 `Draft7.getInstance()`，通过 `SchemaRegistryConfig.builder()` 显式保持 `formatAssertionsEnabled(true)`、`failFast(false)`、`pathType(PathType.JSON_POINTER)`、`typeLoose(false)`。配置只是探针输入；必须由非法 format 被拒绝、全部已知字段错误可见等实测确认。[Registry](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/SchemaRegistry.java)、[配置](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/SchemaRegistryConfig.java)

`SchemaLoader.Builder.allow(Predicate<AbsoluteIri>)`／`block(Predicate<AbsoluteIri>)` 是封闭加载接缝；`getSchemaResource` 先执行 allow／block，再处理内建 meta-schema、classpath 和其他 ResourceLoader。默认 `fetchRemoteResources=false`，显式开启才追加远程加载器；仍须在结构接纳层拒绝任意 `$ref`／`$id`／替换 `$schema`，并测试 HTTP、本地、classpath 和内部引用拒绝，不能只依赖默认不联网。[Loader](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/resource/SchemaLoader.java)

### 4.1 字段错误路径

固定 `Error` 的公共接口为 `getKeyword(): String`、`getInstanceLocation(): NodePath`、`getDetails(): Map<String,Object>`（可为 null）、`getProperty(): String`（null-safe）、`getIndex(): Integer`。`required`／`additionalProperties` 将父对象位置放进 instanceLocation，并通过 `property(fieldName)` 记录缺失／多余字段。因此字段路径归一化时应取 property、按 JSON Pointer 规则转义后追加到父路径；数组项和普通字段错误沿用其 instanceLocation。不能直接把父路径当作完整字段路径，也不应比较库的原始英文报错顺序。[Error](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/Error.java)、[required](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/keyword/RequiredValidator.java)、[additionalProperties](https://github.com/networknt/json-schema-validator/blob/12b85e4f1a07c18a2efc0ae661bb2bf609f7fec1/src/main/java/com/networknt/schema/keyword/AdditionalPropertiesValidator.java)

## 5. 本核验的证据边界

| 项目 | 本文结论 |
| --- | --- |
| Temurin 候选 Windows 制品与官方摘要 | 当时缺失；本次用户已接受 Oracle JDK 17 且官方制品补证通过，不再阻塞 #24 |
| Maven ZIP／SHA-512 来源 | 官方 URL 可读，期望摘要已取得；真实包核验由运行证据证明 |
| Wrapper 校验行为 | 固定源码已读；干净下载、错误摘要拒绝与缓存行为仍需实测 |
| SDK／networknt 接缝 | 固定源码接口已读；发布 JAR 的同 classpath 兼容性仍需实测 |
| Boot／MyBatis-Plus 自动配置、双 Jackson 等 | 不在本次静态研究中宣称通过，见 #24 实际装配证据 |
| 模型／MCP／MinIO 网络／正式业务库／前端 | 未访问，超出本工单范围 |

本文只进行官方 API／固定源码／小型校验文本读取，无业务前置数据，无模型／SDK 执行／会议工具调用；不写入 GitHub，不提交、推送或部署。
