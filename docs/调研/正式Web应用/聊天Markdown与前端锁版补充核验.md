# 聊天 Markdown 与前端锁版补充核验

> 日期：2026-10-24，Asia/Shanghai。  
> 议题：[工作台交互与前端工程 #21](https://github.com/BeyondzychenProton/agentscope-brain/issues/21)。  
> 状态：官方文档、固定源码及官方 npm 元数据的静态核验；仅新增本文，未安装依赖、生成正式工程或 lockfile，也未运行构建、浏览器或真实后端。

> 后续决定：用户已在 #21 的 Q19—Q21 采用本文 Markdown 组合、分层与精确初始版本，见[完整规格](../../正式应用设计/工作台交互与前端工程设计规格.md)。下文保留核验时的候选措辞；未安装或运行的证据边界不变。

## 1. 范围与结论层级

本文承接[已有工程补充核验](前端工程与受控表单渲染补充核验.md)，不重开已选 Vue／Element Plus／有限 form-v1 renderer，也不重定 #19／#20 产品契约。

- **官方事实：**下文引用维护方源码、文档或精确版本 registry 元数据，可支持 API／声明范围判断。
- **工程候选：**Markdown 组合、依赖版本与目录分层是待确认建议；范围相交不证明编译、浏览器或业务集成成功。
- **既定输入：**聊天 Markdown 禁止原始 HTML、脚本及执行组件；链接只允许 http／https，Markdown 图片不能绕过素材链路。来源：[投影规格第 2 节](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)。

## 2. Markdown 解析、净化与可信组件的职责

本次候选为 **markdown-it 15.0.3 + DOMPurify 3.4.17**。markdown-it 的 npm gitHead 对应固定提交 `a8c00d474ec9b304fad9b71c16d6fa8b4909279e`，其 [package.json](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/package.json)确认版本、ESM／CJS 入口和随包类型；下列源码事实均基于该提交，不混用旧版目录。

| 职责 | 已核验事实 | 本项目建议 |
| --- | --- | --- |
| Markdown 语法 | 默认 preset 的 html=false、linkify=false、highlight=null。[固定 preset](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/src/presets/default.ts) | 显式固定 html=false；插件及语法扩展按受控目录增加，不让消息选择 parser 配置。 |
| HTML 输出净化 | DOMPurify 提供 sanitize；默认支持 HTML／SVG／MathML，可限制标签、属性和 URI。[固定 README](https://github.com/cure53/DOMPurify/blob/3.4.17/README.md) | 对 parser 输出再做聊天所需标签／属性白名单净化，排除图片、表单、样式及执行型节点；具体配置由测试固定。 |
| 链接协议 | markdown-it 默认阻止 javascript／vbscript／file，仍允许部分 data:image；DOMPurify 默认 URI 也包含 mailto 等更宽协议。[固定 validateLink](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/src/markdownit.ts#L55-L58)、[DOMPurify URI 配置](https://github.com/cure53/DOMPurify/blob/3.4.17/README.md#control-permitted-attribute-values) | 默认配置不等于本项目 http／https 白名单；解析规范化后检查最终 URL 协议，并在 renderer／净化配置中保持一致。 |
| 图片 | Markdown 图片规则会根据文本中的目的地址生成 img token，而非平台素材引用。[固定 image 规则](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/src/rules_inline/image.ts) | 不让 Markdown 直接生成可加载图片；图片由独立 Vue 组件接收经服务端核验的素材引用，按既定鉴权访问方式渲染。 |
| Vue 挂载 | Vue 禁止把不可信内容作为组件模板，普通插值会转义；HTML 注入须自行保证安全。[Vue 安全说明](https://vuejs.org/guide/best-practices/security.html) | 仅在集中 Markdown 展示组件内使用净化后的 HTML；不编译消息模板，不按文本动态装载 Vue 组件。 |

DOMPurify 明确提示净化后再改写 HTML 可能破坏净化效果。因此 renderer 的结构调整、代码高亮等若引入，须在最终净化前完成；不能把净化结果再交给会重新解释不可信内容的插件。来源：[固定 README 的使用边界](https://github.com/cure53/DOMPurify/blob/3.4.17/README.md#is-there-any-foot-gun-potential)。这不要求开放原始 HTML，也不代替后端素材／权限校验。若净化抛错或 isSupported=false，建议回退纯文本，不能把未净化内容作为 HTML 插入；维护方明确不支持的旧环境可能直接原样返回输入。[支持边界](https://github.com/cure53/DOMPurify/blob/3.4.17/README.md#what-about-legacy-browsers-like-internet-explorer)。

### 代码块复制与流式累计

固定 renderer 对 inline code、普通代码块及未启用高亮的 fence 调用 escapeHtml；配置 highlight 时会使用其返回内容。来源：[固定 renderer](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/src/renderer.ts#L14-L85)。

建议一期代码块仅展示和复制原始纯文本，不提供执行／预览入口，不用 eval／Function／iframe 解释代码。复制由可信按钮调用 Clipboard.writeText；它只写字符串，需安全上下文且可能被拒绝，应显示真实成功或失败。来源：[Clipboard 官方浏览器文档](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard/writeText)。禁止执行是项目建议，不是 Clipboard 替应用提供的权限策略。

markdown-it 的 render(src) 每次 parse 整份字符串；parse 创建新的处理状态，不提供平台事件去重或业务 Run 恢复。来源：[固定 parse／render](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/src/markdownit.ts#L349-L380)。由此建议：

1. reducer 先按 #20 校验并整批应用，得到当前 contentId／contentVersion 的累计 Markdown；再解析、净化并替换该内容组件的展示。不能把每段网络文本分别 render 后拼接 HTML。
2. 未闭合 fence、链接或表格可能在后续文本到达时重新解释；未完成结构可以按 #20 降级文字。保留原始累计文本作为投影数据，净化 HTML 只是可重建展示缓存。
3. 可以合并短时间内的展示更新，但不跳业务批次、不提前推进应用游标；保留已定的上翻阅读位置、展开状态与焦点。长正文的重渲染成本和节流参数须实测，不能从同步 render API 推出不卡顿。

这三项是基于 parser 调用方式与[既定投影契约](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)的工程推论，不是库自带的流式 UI 保证。

## 3. 可明确固定的一组依赖候选

2026-10-24 实际读取了下列官方 registry 的精确版本 JSON，核对 version、engines、peerDependencies 与 peerDependenciesMeta；初次发现版本使用过 registry 标签，但结论与链接均固定到版本号。没有执行 npm install／npm ci。

| 对象 | 精确候选 | 实际声明或核验依据 |
| --- | --- | --- |
| Node.js | 24.22.1 | [官方 LTS 发布页](https://nodejs.org/en/blog/release/v24.22.1)、[官方发布索引](https://nodejs.org/dist/index.json)；后者记录该版配套 npm 11.19.1。 |
| npm | 11.19.1 | [精确元数据](https://registry.npmjs.org/npm/11.19.1)：Node `^20.17.0 或 >=22.9.0`。 |
| Vue | 3.5.44 | [精确元数据](https://registry.npmjs.org/vue/3.5.44)：TypeScript peer 为 `*` 且可选；内部 Vue 包使用相同精确版本。 |
| Vue Router | 4.6.3 | [精确元数据](https://registry.npmjs.org/vue-router/4.6.3)：Vue peer `^3.5.0`。 |
| Pinia | 3.0.4 | [精确元数据](https://registry.npmjs.org/pinia/3.0.4)：Vue `^3.5.11`；可选 TypeScript `>=4.5.0`。 |
| Element Plus | 2.14.7 | [精确元数据](https://registry.npmjs.org/element-plus/2.14.7)：Vue peer `^3.3.7`。 |
| Vite | 8.3.5 | [精确元数据](https://registry.npmjs.org/vite/8.3.5)：Node `^20.19.0 或 >=22.12.0`；可选 @types/node 同范围。 |
| @vitejs/plugin-vue | 6.0.10 | [精确元数据](https://registry.npmjs.org/@vitejs%2fplugin-vue/6.0.10)：Node 同 Vite；Vue `^3.2.25 或 ^3.6.0-rc.10`，Vite 接受 5／6／7／8 主版本。 |
| TypeScript | 6.0.3 | [精确元数据](https://registry.npmjs.org/typescript/6.0.3)：Node `>=14.17`。 |
| vue-tsc | 3.3.13 | [精确元数据](https://registry.npmjs.org/vue-tsc/3.3.13)：TypeScript `>=5.0.0`。 |
| @types/node | 24.10.1 | [精确元数据](https://registry.npmjs.org/@types%2fnode/24.10.1)：Node 24 系列类型；版本满足 Vite 的可选类型 peer 范围。 |
| Ajv | 8.20.0 | [精确元数据](https://registry.npmjs.org/ajv/8.20.0)；对应 [固定默认入口](https://github.com/ajv-validator/ajv/blob/0fba0b8e649909613cfce0999b149cd08f4a4987/lib/ajv.ts)装载 draft-07 词汇与 meta-schema。 |
| ajv-formats | 3.0.1 | [精确元数据](https://registry.npmjs.org/ajv-formats/3.0.1)：Ajv `^8.0.0`，同时有此范围的普通依赖。 |
| markdown-it | 15.0.3 | [精确元数据](https://registry.npmjs.org/markdown-it/15.0.3)及[固定包声明](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/package.json)：随包提供类型，未声明 engines／peer。 |
| DOMPurify | 3.4.17 | [精确元数据](https://registry.npmjs.org/dompurify/3.4.17)及[固定包声明](https://github.com/cure53/DOMPurify/blob/a77a3e78b59f72fa4d1c83f22e121ad9cc7d3575/package.json)：提供 ESM 与类型，未声明消费方 engines／peer；浏览器使用其 DOM 实现。 |

### 静态交集结论与尚未证明的部分

- Node 24.22.1 满足所列 npm、Vite、plugin-vue 和 TS 的 engines；Vue 3.5.44 满足 Router、Pinia、Element Plus 和 plugin-vue 的 peer；Vite 8.3.5 满足插件的 `^8.0.0`；TS 6.0.3 满足 Pinia 和 vue-tsc；Ajv 8.20.0 满足 formats 的 `^8.0.0`。这是声明范围的静态计算。
- 未声明 engines 不等于维护方证明支持所有 Node 版本。Vite 的 Sass／Less 等 peer 标为 optional，不因元数据列出就自动增加本项目依赖。
- markdown-it v15 已自带类型，维护方明确不再需要 @types/markdown-it；应从包根公开 API 使用，不依赖旧 lib 内部入口。[固定迁移说明](https://github.com/markdown-it/markdown-it/blob/a8c00d474ec9b304fad9b71c16d6fa8b4909279e/docs/migration/migration_v15.md)。
- formats 3.0.1 的 time／date-time 均要求时区；与 form-v1 上海业务时间的映射仍按[已有研究第 5 节](前端工程与受控表单渲染补充核验.md)验证，不因候选升级放宽格式断言或改业务时区。[固定 formats 说明](https://github.com/ajv-validator/ajv-formats/blob/v3.0.1/README.md)。
- 这不是完整传递依赖求解、审计或运行相容证明。实施时直接依赖应写精确版本并提交锁文件，核对实际完整依赖树及 integrity，再执行独立 vue-tsc、生产构建、组件和浏览器验证；本次未创建这些文件或宣称通过。

## 4. 工程分层建议

下表是围绕本项目契约的组织建议，**不是 Vue／Pinia／Vite 要求的目录标准**；目录名称可在实施时微调，责任边界应保留。

| 层次／建议目录 | 责任 | 验证责任 |
| --- | --- | --- |
| app／pages | 启动、路由、登录／受限改密／工作台页面组装，承接允许访问范围 | 路由跳转、登录失效及返回路径；不把客户端路由当服务端鉴权。 |
| components | 基础展示、受控字段、状态和可访问交互；以 props／事件通信 | 标签、字段错误、键盘和焦点、长表单滚动；不在基础组件内发业务请求。 |
| features | auth、chat、interaction、media 等用例协调；提交／保存／取消／查询状态 | 同键重试、结果未知、草稿冲突、图片状态及历史只读，不自动重发业务动作。 |
| stores | Pinia 保存已接纳的展示状态及按 Session 定位的 UI 状态 | 会话切换、用户隔离、草稿及读取恢复；不持久化凭据到展示状态，不由 store 猜测 Run 成功。 |
| projection／reducer | DTO／版本校验、整批 reducer、规范化对象、应用 cursor；纯函数转换为主 | 重复／缺口／乱序／坏引用／未来版本、整批原子性与正式回复替换；不含网络或工具调用。 |
| transport | HTTP／SSE 编解码、认证错误分层、只读补读／轮询和重连接缝 | 截断帧、断线、退避、401、已应用 cursor；不把传输恢复解释为重新执行任务。 |
| rendering | Markdown parser、链接规则、净化入口；可信图片、表单及回执分别渲染 | 恶意链接／HTML、跨分片代码 fence、图片旁路、复制失败与更新时阅读位置。 |

建议依赖方向为页面组合 feature；feature 协调 transport 与 store；传输数据先经过 projection 校验及 reducer，再交给组件渲染。reducer 和 Markdown 渲染不能回调模型或写工具；恢复读取只改变展示。依据：[既定 #19 交互契约](../../正式应用设计/动态表单与交互请求生命周期设计规格.md)、[既定 #20 投影契约](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)。

## 5. 实施与验收责任

1. **纯逻辑验证：**用固定 fixture 覆盖 reducer、链接白名单、Schema／格式映射、幂等与错误分类；测试可解释的行为，不只镜像实现分支。
2. **组件／浏览器验证：**检验 Markdown 净化后的实际 DOM、可信图片、复制 API 成败、长表单及焦点；浏览器依赖的净化不能只靠无 DOM 的单元测试。若使用 Node DOM 模拟器，应核验其安全及兼容范围，不能代替目标浏览器。[DOMPurify 官方服务端边界](https://github.com/cure53/DOMPurify/blob/3.4.17/README.md#running-dompurify-on-the-server)。
3. **工程检查：**独立类型检查与生产构建均通过；Vue 官方明确 Vite 转译不执行类型检查。[Vue TS 文档](https://vuejs.org/guide/typescript/overview.html)。
4. **分阶段闭环：**#22 落实真实 API、身份、素材、事务及恢复接缝；#23 分别证明隔离集成、Chrome／Edge PC 矩阵及真实 Web／框架／会议结果。局部渲染或 npm 元数据检查不能覆盖这些证据。[下游分工](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)。

本次产出支持 Markdown 与锁版方案评审；没有应用运行、供应链完整审计或真实集成验收结论。
