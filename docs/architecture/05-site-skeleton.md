## site 服务端渲染骨架与 P4 站点配置（MACC-9）

> **状态：骨架已实现。** 首页、分类列表、视频公开详情已由 MACC-14 实现，见 `08-site-pages.md`（本文只描述骨架部分）。
>
> **骨架原状态：未实现任何业务页面。** 依据：D2 = jte 3.2.4（用户已确认，`04-site-template-spike.md` 第 4 节）、P2（MACC-8，`content.application.view`）、P4（`04-site-template-spike.md` 第 6、9 节）。首页、分类列表、视频详情另行任务。

### 1. 结构

| 位置 | 内容 |
|---|---|
| `backend/src/main/java/com/maccms/backend/site/application/` | `SiteProperties`（`maccms.site.*` 原始字符串绑定）、`SiteSettings`（校验后的站点设置、canonical 生成）、`SafeUrl`（URL 白名单） |
| `backend/src/main/java/com/maccms/backend/site/config/` | `SiteConfiguration`（装配）、`@ConditionalOnSiteEnabled` / `SiteEnabledCondition`（启用开关） |
| `backend/src/main/java/com/maccms/backend/site/web/` | `SiteHtmlRenderer`（缓冲渲染）、`SiteExceptionHandler`（site 专用错误页）、`SiteStatusInterceptor`（维护关闭）、`SitePages`（视图模型组装）、`SitePlaceholderController`（骨架占位页）、`view/`（视图模型） |
| `backend/src/main/jte/site/` | `layout/page.jte`（外壳）、`pages/placeholder.jte`、`pages/error.jte`（404/500/503/维护共用） |
| `backend/src/main/resources/static/site/site.css` | 最小设计 token 与基础样式（不还原旧站视觉） |

- 依赖：`gg.jte:jte-runtime`、`gg.jte:jte-models` 3.2.4；构建插件 `jte-maven-plugin` 3.2.4（`contentType=Html`、`OwaspHtmlPolicy`、`ModelExtension`，生成到 `com.maccms.backend.site.web.jte`）。**未引入** `jte-spring-boot-starter-4`，运行时不编译模板、不需要 JDK 编译器。测试另用 `gg.jte:jte`（编译器）与 jsoup 1.23.2（`test` 作用域）。
- site 不调用 `ContentReadService`（骨架没有内容页面），不访问 Mapper、infrastructure、数据库或旧 PHP。site 的 Java 源码由 `ModuleBoundaryTest` 另禁止读文件/环境变量、启动进程、发起 HTTP 请求；模板由 `SiteGuardrailsTest` 禁止全限定名、静态 API、`new` 与反射（模板表达式只能读取视图模型）。

### 2. 配置（只读本应用的环境变量，不读 PHP 配置）

| 配置项 / 环境变量 | 默认 | 规则 |
|---|---|---|
| `maccms.site.enabled` / `MACCMS_SITE_ENABLED` | `false` | 只接受 `true`/`false`（不区分大小写）；其他值启动失败。关闭时不注册任何 site 组件与路由，也不校验下列配置 |
| `maccms.site.name` / `MACCMS_SITE_NAME` | 无，启用时必填 | 去首尾空白后 1～64 字符，无控制字符 |
| `maccms.site.base-url` / `MACCMS_SITE_BASE_URL` | 无，启用时必填 | 小写 `http`/`https`，有主机名，无用户信息、查询串、片段、`%`、`.`/`..` 段、`//`、空白，路径以 `/` 结尾（允许子目录），≤200 字符 |
| `maccms.site.keywords` / `description` / `icp` | 空 | ≤200 / ≤500 / ≤64 字符，无控制字符 |
| `maccms.site.logo-url` | 空 | 空，或 `SafeUrl`：`http`/`https` 绝对地址（无用户信息），或以单个 `/` 开头的站内路径（拒绝 `//`、`/\`） |
| `maccms.site.status` | `open` | `open` / `closed` |
| `maccms.site.closed-message` | `站点维护中，请稍后访问。` | ≤200 字符，无控制字符，按文本转义输出 |
| `maccms.site.canonical-redirect` | `false` | D4 未批准，只接受 `false` |
| `maccms.site.hit-counter-enabled` | `false` | D5 未决定，只接受 `false` |

- 启用后配置缺失或不合法：启动失败，一次列出所有出错的**配置项名称与规则**，不回显取值（所有项按字符串绑定，Spring 的绑定错误分析不会打印取值）。
- `base-url` 带子目录（如 `https://站点/sub/`）时：canonical、首页链接、样式表等页面内链接都按对外地址 `/sub/...` 生成（`SiteSettings.canonical` / `publicPath`），而本应用的路由仍挂在根路径，需要反向代理把 `/sub/` 前缀映射到本应用根路径。该映射未实现、未演练，随部署评审确定；本地只用根路径 `/`。
- 本地合成值见 `backend/config/maccms-backend.env.example`：`MACCMS_SITE_NAME=MacCMS 本地站（合成）`、`MACCMS_SITE_BASE_URL=http://maccms.local.test/`。
- 首批不支持：`site_tj` 统计代码、移动端独立模板、时区设置（暂无使用方）、生产缓存 TTL。

### 3. 行为

| 场景 | 状态码 | 头 | 页面 |
|---|---|---|---|
| `GET /_site/placeholder`（骨架占位页，不是首页） | 200 | `Cache-Control: no-cache`、`X-Robots-Tag: noindex`、`Content-Length` | 外壳 + 说明文字；`<link rel="canonical">` = `base-url` + `_site/placeholder` |
| site 页面抛 `VIDEO_NOT_FOUND`/`CATEGORY_NOT_FOUND`/`NOT_FOUND`/`INVALID_PARAMETER`/`METHOD_NOT_ALLOWED`，或路径参数类型不符 | 404 | `no-store`、noindex | 错误页 |
| `SERVICE_UNAVAILABLE` | 503 | `no-store`、noindex、`Retry-After: 120` | 错误页 |
| `VIDEO_PASSWORD_REQUIRED`/`VIDEO_COPYRIGHT_RESTRICTED` | 503 | 同上 | 错误页（不提示原因）。**占位处理**：按 03 方案第 4.2 节应交还 PHP，交还机制须与部署一起评审，实现详情页时替换 |
| 其他异常、渲染中途失败 | 500 | `no-store`、noindex | 完整错误页，不含异常信息 |
| `status=closed` | 503 | `no-store`、noindex、`Retry-After: 3600` | 维护页（转义后的 `closed-message`）；页面 Controller 不执行，因此不查库 |

- 所有 site 响应：`text/html;charset=UTF-8`、`X-Content-Type-Options: nosniff`、`Content-Security-Policy`（`script-src 'none'` 等；骨架没有脚本，后续页面需要脚本时随任务评审）。
- 缓冲：整页先渲染到字符串，成功后才构造响应；错误模板本身失败时输出固定的兜底 HTML。
- 日志只用 `SafeExceptionLog`（类型链与栈帧），不记录异常 message、cookie、请求头或参数。
- canonical 只用配置的 `base-url`，不读取 `Host`、`X-Forwarded-*`、`Forwarded`。canonical 301、计数、生产缓存 TTL 保持关闭/未定。
- 样式表 `/site/site.css` 位于 Boot 默认静态目录，`maccms.site.enabled=false` 时也能访问（静态资源，不是页面路由，不含数据）。
- **不在 site 处理范围内**：未匹配任何路由的请求、对 site 路由使用 POST 等方法，仍由全局处理返回 JSON 404/405（Spring 在找到 Controller 之前就失败，site 的异常处理不适用）。上线时这些请求按 03 方案第 2 节由 nginx 留给 PHP。

### 4. 模板规则与护栏（`./mvnw verify` 中执行）

| 检查 | 位置 |
|---|---|
| 模板引用的字段/方法、参数类型在 `compile` 阶段检查（包括测试走不到的分支） | jte 预编译（构建自带） |
| 未加引号的属性值、在标签名/属性名中输出 | 构建的 `OwaspHtmlPolicy` |
| 另禁止事件属性、单引号属性、`script`/`style`/`iframe`/`object`/`embed`/`base`/`svg` 等标签、`style`/`srcdoc`/`http-equiv`/`formaction` 属性；每条规则有探针模板 | `SiteTemplatePolicyTest` + `backend/src/test/jte-probe/` |
| 模板只在 `src/main/jte/site/`；禁止 `$unsafe{}`、`@raw`、`!{}`、事件属性/脚本/样式中的数据、`vod_content`/`.content()`；URL 属性的动态值只能是 `SafeUrl.href()`；只 import `site.web.view`/`site.application` 类型、`gg.jte.Content` 与 `java.util`；表达式中禁止全限定名、`System`/`Runtime`/文件等静态 API、`new`、`getClass()`；jte 版本固定、无 starter；site 组件都带启用条件 | `SiteGuardrailsTest`（含合成违规样本自检） |
| site 不依赖 Mapper/infrastructure/数据访问/文件/进程/网络 | `ModuleBoundaryTest` |

排查编译错误：jte 报错位置在生成的 Java 文件（`target/generated-sources/jte/.../Jte<name>Generated.java`），文件中的 `JTE_LINE_INFO` 映射回模板行号。

### 5. 本地运行

```bash
cd backend && ./mvnw -q package -DskipTests
MACCMS_DB_URL=... MACCMS_DB_USERNAME=... MACCMS_DB_PASSWORD=... \
MACCMS_SITE_ENABLED=true MACCMS_SITE_NAME='MacCMS 本地站（合成）' MACCMS_SITE_BASE_URL=http://maccms.local.test/ \
java -jar target/maccms-backend-0.1.0-SNAPSHOT.jar
curl -i http://127.0.0.1:18280/_site/placeholder
```

占位页与错误页不访问数据库；只验证 site 时可把数据源指向不可达的合成地址（如 `jdbc:mysql://127.0.0.1:1/x`），不要连接 `maccms_dev` 或其他已有库。

### 6. 与旧版的差异

- 站点关闭：旧 PHP 输出关闭模板后 `die`，未设置状态码（`application/index/controller/Base.php:73-79`，代码阅读，未实测）；新版返回 503 + `Retry-After` + `no-store` + noindex。
- 站点设置来源：旧版读 `application/extra/maccms.php` 的 `site.*`；新版只读 `maccms.site.*` 环境变量，两边由运维分别维护，并行期存在漂移风险。
- 非白名单 URL（`javascript:`、协议相对地址等）的 Logo 配置在启动时被拒绝，不会输出。
- 渲染失败返回完整 500 页（旧 PHP 的模板错误行为未核实）。
- 没有旧版对应页面被替换：骨架只新增 `/_site/placeholder`，且默认关闭。

### 7. 三页任务的前置条件与遗留

- 本骨架验收通过后再开始三页；三页仍需 URL 生成器/解析器与 PHP 黄金向量、图片地址规范化、富文本白名单过滤（`04-site-template-spike.md` 第 7 节 3～5）。图片地址规范化已由 MACC-11 实现（`06-image-url-normalizer.md`）；URL 生成器/解析器与黄金向量已由 MACC-10 完成（`docs/frontend/02-url-golden-vectors.md`）。两项配置绑定及 URL 页面路由接入随页面任务完成；富文本白名单过滤与文本实体处理由 MACC-12 实现（`07-rich-text-filter.md`），待验收，接入随详情页任务完成。
- 数据库不可用 → 503：`ContentReadService` 抛出的 Spring `DataAccessException` 在 site 中只能落到 500（site 禁止引用 `org.springframework.dao`）。三页任务需在 `content.application` 把连接类故障转换为 `ApiException(SERVICE_UNAVAILABLE)`，或经评审放宽护栏。**已由 MACC-14 在 `content.application` 中转换，护栏未放宽（`08-site-pages.md` 第 5 节）。**
- URL 形态与图片配置的绑定、页面路由接入、`SafeHtml` → `Content` 适配：已由 MACC-14 完成（`maccms.site.url.*`、`maccms.site.image.*`，`08-site-pages.md` 第 3 节）。
- 观看密码/版权的交还 PHP、canonical 301、计数、生产缓存 TTL、访问日志字段（03 方案第 7.4 节）、nginx 分流均未实现。
- 未测：性能与大页面内存、并发、真实数据中的实体与富文本、浏览器/移动端视觉、IDE 支持。
