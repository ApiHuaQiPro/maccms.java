## 11 出站护栏、表结构护栏、浏览器侧外部地址放行参数与旧配置只读层（B02 / MACC-26）

> **状态：已在本地实现并通过 `./mvnw verify` 与 `./mvnw verify -Pmysql-it`，未部署。** 依据：`docs/architecture/09-roadmap.md`（以下简称"路线图"）第 1.2、1.3、4.2、4.3 节与第 5.3 节 B02 行。
> 本批不改旧 PHP、不改表结构、不读 `prod-config`、不连线上。新增部署参数：旧配置目录默认未配置；浏览器侧外部地址放行按路线图第五轮 W1/W2 默认打开、W3 默认关闭。路径相对仓库根目录，代码在 `backend/` 下。

### 1. 交付物一览

| 路线图 B02 | 实现 | 自动测试 |
|---|---|---|
| ① 出站护栏 | `architecture/OutboundNetworkGuardTest`：出站网络 API 只允许出现在登记的 `integration` 子包，登记表为空；pom 不得引入出站客户端依赖 | 同名测试（真实源码 + 合成用例自检） |
| ② 表结构护栏 | 静态：`architecture/SchemaGuardTest`（DDL/表维护/账号语句、SQL 脚本、自动迁移配置与依赖）；运行期：`schema/SchemaFingerprint` + `schema/SchemaGuardMySqlIT`，另外三个 `*MySqlIT` 删库前同样比对 | 同名测试；`-Pmysql-it` |
| ③ `legacyconfig` 模块 | `legacyconfig.application.LegacyConfigService`（只读、按修改时间热加载）、`VarExportParser`（不执行 PHP）、`legacyconfig.infrastructure.LegacyConfigDirectory`（路径检查）；site 启动时经它读 `extra/maccms.php` 并按字段并入站点设置（`site.application.SiteLegacyConfig`，第 4.4 节） | `VarExportParserTest`、`LegacyConfigGoldenVectorsTest`（PHP 黄金向量）、`LegacyConfigServiceTest`、`LegacyConfigDirectoryTest`；`SiteLegacyConfigTest`、`SiteLegacyConfigStartupTest`、`SiteLegacyConfigPagesTest`（三页等价性） |
| ④ 浏览器侧外部地址放行参数 | `common.browser.BrowserExternalAccess`（W1/W2 默认打开并保留关闭态，W3 默认关闭）；W2 接入已有三页的图片输出（`ImageUrlNormalizer.withExternalImages`） | `BrowserExternalAccessTest`、`ExternalImageGateTest`、`ImageUrlGoldenVectorsTest`（新增放行/关闭断言）、`SiteExternalImagesMvcTest`、`SiteExternalImagesRemoteMvcTest`（关闭态）、`SiteContentPagesMvcTest`（打开态）、`SiteLegacyConfigPagesTest`（默认即打开、两态等价性） |
| ⑤ SQL 入口护栏 | `architecture/SqlEntryGuardTest`：SQL 执行入口只接受常量 SQL；`${}` 只允许登记的表达式（登记表为空）；禁止 MyBatis Provider/SQL 构建器/ScriptRunner | 同名测试（含"把请求参数拼成整条 SQL 执行"的合成用例） |

### 2. 出站护栏

- **扫描对象**：`src/main/java` 全部源文件，去掉注释与字符串后匹配（注释、字符串里提到的类名不算）。
- **视为出站的 API**：`java.net.http.*`、`java.net.URL/URLConnection/HttpURLConnection/Socket/InetAddress/…`、`java.net.*` 通配导入、`java.nio.channels` 的套接字通道、`javax.net.*`、RMI、JNDI（`javax.naming`）、JavaMail（`jakarta.mail`/`javax.mail`/Angus/Spring Mail）、Spring `web.client`（RestClient、RestTemplate）、WebClient、`http.client`、HTTP Interface、WebSocket 客户端、OpenFeign、`UrlResource`/`ResourceUtils`、Apache HttpClient、commons-net（FTP）、OkHttp、Retrofit、Netty、Jetty 客户端、JSch、`com.sun.net`/`sun.net`，以及 jsoup 的 `Jsoup`、`Connection`、`HttpConnection` 与通配导入。另按方法名拦截不需要导入就能调用的 `.openConnection(`、`.openStream(`、`.toURL(`。
- **登记表** `OutboundNetworkGuardTest.REGISTERED`：键为 `integration.<子包>`，值为登记批次。B02 交付时为空。只接受 `integration.[a-z][a-z0-9]*`；登记子包的下级包同样允许，名字相似的包（`integration.payx`）不算。B23 起每批只登记本批子包（路线图 4.3 ④），登记改动必须出现在该批的合并提交中。E01/E02/E03/E08 的本地版本、采集链、上传、图片处理永远不登记；E05、E12 是入站，不需要登记。**B23（MACC-46）改为两级**：登记表移到主代码 `IntegrationPackages`；出站 API 只允许在 `RAW_API`（`integration.http`，出站客户端本身），`REGISTERED` 的子包只能经出站客户端发请求，客户端只能被已登记子包引用，运行期再查调用方。详见 `26-b23-integration-foundation.md` 第 2 节。
- **pom 检查**：`spring-boot-starter-mail/webflux/webclient/restclient/websocket`、OpenFeign、commons-net、OkHttp、httpclient(5)、Jakarta Mail、Angus Mail、JSch、Netty 等不得出现。B23 引入出站客户端时同步调整。
- **局限**（静态扫描本身的边界，B23 的"测试期出站护栏"在运行时补上）：反射/动态类加载、第三方库内部发起的请求（例如 XML 解析器解析外部实体）扫描不到。B27 解析公众号 XML 时必须关闭外部实体（路线图 E12 行），这一点不能靠本护栏保证。

### 3. 表结构护栏

#### 3.1 静态（`SchemaGuardTest`）

- `src/main/java` 去掉注释、保留字符串，并把相邻字面量的 `" + "` 合并、把 `\n`/`\t` 等转义换成空白后匹配；`src/main/resources` 下的 XML（去掉注释）、yml、properties、json、txt、jte、html、js 同样匹配。
- 拒绝：`CREATE/ALTER/DROP [修饰] TABLE/DATABASE/SCHEMA/INDEX/VIEW/TRIGGER/PROCEDURE/FUNCTION/EVENT/USER/ROLE/SERVER/TABLESPACE/…`、`RENAME TABLE/USER/DATABASE`、`TRUNCATE`（后跟反引号或含下划线的表名）、`OPTIMIZE/REPAIR/ANALYZE/CHECK/CHECKSUM TABLE`（旧后台"数据库工具"四项，D10）、`GRANT … ON/TO`、`REVOKE … ON/FROM`、`SET PASSWORD`、`LOAD DATA/XML`、`IMPORT TABLE`、`SOURCE x.sql`。普通英文（"create a table of contents""truncate the text""grant access"）不误报，由合成用例锁定。
- 资源中不得有 `*.sql`、`db/migration`、`db/changelog`；配置中不得出现 `flyway`、`liquibase`、`ddl-auto`、`hbm2ddl`、`schema-locations`、`data-locations`、`initialize-schema`；`spring.sql.init.mode: never` 保留。pom 不得引入 Flyway、Liquibase、JPA/Hibernate、Spring Data JDBC/R2DBC、MyBatis-Plus。

#### 3.2 运行期（`-Pmysql-it`）

- `schema/SchemaFingerprint` 从 information_schema 读取：库的默认字符集与排序规则；每张表的类型、引擎、行格式、排序规则、建表选项、注释；每列的序号、默认值、可空、类型、字符集、排序规则、键、EXTRA、注释；每个索引列的唯一性、列、前缀长度、类型、排序、可空、注释；触发器、存储过程/函数、事件、视图。**不含**随数据变化的行数、`AUTO_INCREMENT`、更新时间、数据长度。
- `SchemaGuardMySqlIT`：新建一次性库 → 原样导入 `install.sql` → 取指纹（断言 26 张表、MyISAM/utf8、无触发器/过程/事件/视图）→ 打开全部只读功能（content 搜索、site 页面与搜索、旧配置目录、W2）以真实 HTTP 请求 11 个地址 → 关闭应用后再取指纹，必须与导入时**完全一致** → 自检：在同一一次性库里加列、改引擎、建索引、改列、建表，比对都能发现 → 删库并确认不存在。
- `ContentApiMySqlIT`、`ContentSearchMySqlIT`、`SiteSearchMySqlIT` 在导入并写完合成数据后取指纹，删库前再比对；差异在删库之后断言，比对失败不会留下一次性库。
- 所有比对只在 `maccms_it_<时间>_<随机>` 上进行，不碰 `maccms_dev`。

#### 3.3 SQL 入口护栏（`SqlEntryGuardTest`）

- 只检查引用了 `java.sql`、`javax.sql`、Spring JDBC、MyBatis 包的源文件（别处的 `execute(`、`update(` 与 SQL 无关）。
- `Statement.execute*`、`addBatch`、`prepareStatement`、`prepareCall`、`nativeSQL`、`JdbcTemplate.query*`/`update`/`batchUpdate`/`execute`、`JdbcClient.sql` 的第一个参数只能是字符串字面量、全大写常量（可带类名限定）或它们的 `+` 拼接。小写变量、方法调用（含 `String.format`）、字面量与变量拼接都拒绝。
- `${...}` 只允许 `SqlEntryGuardTest.REGISTERED_DOLLAR` 登记的表达式（各批文档登记：经 `SHOW COLUMNS`/表清单校验的表名、列名，排序方向），XML 与注解同样检查，XML 注释不算。B02 交付时为空。注意 `ReadOnlyGuardrailsTest` 仍然整体禁止 Mapper 中的 `${`，第一次登记时两处要一起改。
- 禁止 `@SelectProvider` 等四个 Provider 注解与 `org.apache.ibatis.jdbc`（`SQL`、`SqlRunner`、`ScriptRunner`）。
- 局限：按名字判断"常量"，一个非 final 却写成全大写的变量能骗过检查；代码评审仍要看。

### 4. 旧配置只读层（`legacyconfig`）

#### 4.1 配置与文件

| 配置项 / 环境变量 | 默认 | 作用 |
|---|---|---|
| `maccms.legacy-config.dir` / `MACCMS_LEGACY_CONFIG_DIR` | 空（未配置） | 旧站 `application/` 目录，可在仓库之外。留空时读取抛 `NOT_CONFIGURED`；不为空但不存在或不是目录时启动失败，信息只含配置项名称 |

- 能读的文件只有 `LegacyConfigFile` 枚举里的 13 个：`extra/` 下的 `maccms`、`bind`、`domain`、`quickmenu`、`timming`、`vodplayer`、`voddowner`、`vodserver`、`addons`、`version`、`captcha`、`queue`，以及 `route.php`（与仓库内文件一一对应，测试锁定）。`database.php`、`config.php` 等含凭证或含代码的文件读不到。
- 路径（`LegacyConfigDirectory`）：相对名只能是小写字母、数字、下划线组成的段加 `/`，以 `.php` 结尾，最长 128；拒绝 `..`、`.`、绝对路径、盘符、反斜线、空段、大写、编码过的 `%2e`。解析符号链接后的真实路径必须仍在目录的真实路径内（文件链接、目录链接指向目录外都拒绝，本机实测）。必须是普通文件；读取前检查大小，读取时最多读上限 + 1 字节，读取期间变大同样拒绝。
- 热加载：每次读取取修改时间与大小，与上次相同就返回缓存，否则重新读取解析。解析失败**不回退到旧结果**（旧 PHP 遇到语法错误同样直接报错），同一状态的失败也缓存，不会每次请求都重读。修改时间与大小都不变的改写检测不到（已知限制）。
- 只读：不写回（写回在 B15，同样按 `mac_arr2file` 格式），不监听目录，不列目录。

#### 4.2 解析规则（`VarExportParser`，不执行 PHP）

接受的子集（与 `mac_arr2file` 的 `var_export` 输出及仓库内手写的 `captcha.php`、`queue.php` 一致）：

- `<?php`（不区分大小写）加空白开头，唯一一条 `return 值;`，末尾可有 `?>` 与空白。UTF-8、无 BOM、最多 1 MiB。
- 值：`array(...)`、`[...]`（尾逗号、省略键）；单引号字符串（只有 `\\`、`\'` 两个转义）；十进制整数（可带 `-`，64 位）；十进制浮点（`1.5`、`-0.0`、`1.0E+25`）；`true`/`false`/`null`（不区分大小写）。
- 键：整数或单引号字符串，字符串键按 PHP 规则规范化（`'1'`→1，`'01'`、`'-0'`、`' 1'`、`'1.0'`、超出 64 位的数字串仍是字符串）；重复键后者覆盖、保留原位置；省略键取"已用最大整数键 + 1"。
- 空白与 `//`、`/* */` 注释。
- 上限：嵌套 32 层、元素总数 100 000。

拒绝（返回 `LegacyConfigException`，原因 + 固定说明 + 行列，不含文件内容）：常量与函数调用（含 `NAN`、`INF`、`PHP_INT_MAX`）、`include`/`eval`/`new`、变量、双引号字符串、heredoc、反引号、拼接与其他运算、类型转换、`\Foo::__set_state`、`(object)`、前导零/十六进制/二进制/数字分隔符、浮点/布尔/null/数组作键、`#` 注释、含 `?>` 的行注释、`return` 之后的任何代码或输出、BOM、非法 UTF-8（含过长编码与代理项）、超限。

两处与 PHP 有意不同（都是 fail closed，仓库与 `mac_arr2file` 写出的文件不会出现）：

1. 整数键全是负数时省略键：本机实测 PHP 7.2/7.3 从 0 开始、PHP 8.0/8.2 从"最大负数键 + 1"开始，线上 PHP 版本未核实，新版直接拒绝。
2. PHP 7.3+ 的 `var_export` 把含 NUL 的字符串写成 `'' . "\0" . ''`、把 `PHP_INT_MIN` 写成 `-9223372036854775807-1`，二者都属拼接/运算，拒绝。

`PhpArray`、`PhpString` 的 `toString()` 不输出内容（配置里可能有密钥）。

#### 4.3 PHP 黄金向量

- `backend/src/test/resources/legacyconfig/var_export_golden.php` 用 PHP 自己 `include` 每个文件，转成带类型的 JSON（区分整数键/字符串键、整数/浮点，保留顺序）写入 `legacy-config-vectors.json`；`--write` 重写，不带参数时核对。合成文件在 `cases/`：`generated_var_export.php` 由脚本用 `var_export` 写出（转义、Unicode、换行、数值边界、键规范化），`handwritten.php` 是手写的短数组、注释、大小写、省略键、重复键写法。`cases/*.php` 在 `backend/.gitattributes` 中标为 `-text -whitespace`：保持原字节（var_export 的行尾空格与字符串内的 CRLF 都是被测内容），`git diff --check` 不检查它们。
- 本机 PHP 7.2.34、7.3.33、8.0.30、8.2.32 核对结果全部一致（15 个文件）。
- `LegacyConfigGoldenVectorsTest` 逐项比较 Java 解析结果；浮点按数值比较（含 -0.0）。每个文件记录"CRLF 换成 LF 后"的 SHA-256：仓库按 LF 存储、Windows 检出为 CRLF，两种检出得到同一个值；`maccms.php` 的 `rewrite.route` 值内含换行，所以仓库文件两侧都按 LF 比较。

#### 4.4 site 设置从旧配置读取（MACC-26 第二个提交）

路线图 B02 验收要点："`site` 已有的设置项改为从这里读取时行为不变"。做法如下（队长 2026-10-16 确认：逐字段环境变量优先、`base-url` 必填、不支持的 URL 形态拒绝、启动时读取、热加载放 B15、空串 `site_status` 暂按开站）。

- **何时读**：site 启用且设置了 `MACCMS_LEGACY_CONFIG_DIR` 时，`SiteConfiguration` 在**启动时**经 `LegacyConfigService` 读一次 `extra/maccms.php`，交给 `site.application.SiteLegacyConfig` 合并。**没有设置目录时只用环境变量，行为与此前完全相同。** site 未启用时不读。
- **不热加载**：站点设置仍是启动时校验的单例，改了 `maccms.php` 要重启才生效。`legacyconfig` 模块本身的热加载（第 4.1 节）不变；site 设置的热加载与 B15（配置写回）一起做。
- **逐字段合并**：环境变量（`maccms.site.*`）非空就用环境变量；否则旧配置有这个键就用旧值（换算见下表）；都没有就保持空，由现有校验取默认值。合并结果交给原有的 `SiteSettings.from`、`SiteUrlProperties.toStyle`、`SiteImageProperties.toNormalizer`，**校验规则一行没改**，旧值不合规时与环境变量不合规一样启动失败。
  - 为区分"没设置"和"设成默认值"，`MACCMS_SITE_STATUS`、`MACCMS_SITE_URL_REWRITE`、`MACCMS_SITE_URL_SUFFIX_HIDE` 的默认值由 `open`/`false` 改为空。现有校验本来就把空当作 open/false，所以没有旧配置时结果不变。
  - 已知限制：环境变量不能把旧配置里的非空文本"覆盖成空"（空即未设置）。
- **`base-url` 不取旧配置**：旧 `site_url` 不带协议，新版 canonical 只用配置的 `base-url`，仍须由 `MACCMS_SITE_BASE_URL` 提供，缺失时启动失败。`canonical-redirect`、`hit-counter-enabled`、`search.enabled` 也没有旧配置来源。

| 旧配置（仓库 `application/extra/maccms.php` 行号） | 新设置项 | 换算 |
|---|---|---|
| `site.site_name`（:20）、`site_keywords`（:23）、`site_description`（:24）、`site_icp`（:25）、`site_logo`（:32）、`site_close_tip`（:41） | `name`、`keywords`、`description`、`icp`、`logo-url`、`closed-message` | 字符串原样；整数按十进制转成字符串；其他类型拒绝 |
| `site.site_status`（:40） | `status` | 旧版 `index/controller/Base.php:75` 用 `site_status == 0` 判断关站：整数或数字字符串（前后可有空白，如 `'0'`、`' 0'`、`'0.0'`）等于 0 → `closed`，否则 `open`；**空串 → `open`**（见第 6 节 PHP 版本差异）；其他字符串与类型拒绝 |
| `rewrite.status`（:490） | `url.rewrite` | 旧版 `common.php:2396-2411` 只处理 `==0`、`==1`：数值 0 → `false`，1 → `true`，其余（含空串）拒绝 |
| `rewrite.suffix_hide`（:488） | `url.suffix-hide` | 旧版 `common.php:2414` 用 `==1`：数值等于 1 → `true`；其他数值与空串 → `false`；非数字字符串拒绝 |
| `upload.protocol`（:157）、`mode`（:158）、`remoteurl`（:159）、`img_key`（:147）、`img_api`（:148） | `image.protocol`、`mode`、`remote-url`、`img-key`、`img-api` | 字符串原样，再按 06/07 文档的现有规则校验（`img_key` 仍只接受字面域名多选一） |

- **新版不支持的 URL 形态 → 启动失败**，不静默生成与旧站不同的地址（对应 `LegacyUrlStyle` 的固定前提）：
  - `rewrite.route_status`（:489）为真（PHP 布尔转换；`Init.php:81` 直接作为 `url_route_on`）；
  - `app.pathinfo_depr`（:47）不是 `/`（PHP `empty()` 为真时按 `/`，`Init.php:82`）；
  - `site.install_dir`（:31）不是 `/`（`MAC_PATH`，`Init.php:48`；键不存在也拒绝）；
  - `rewrite.type_id`、`rewrite.vod_id`（:495、:493）不是 0 或空串（`common.php:1807,1866` 的 switch：1 拼音、2 加密 id）；
  - `view.index`、`view.vod_type`、`view.vod_detail`（:457、:462、:468）不是数字（B16 起取值 2 不再拒绝：静态文件由静态生成写出，前台链接仍是动态地址，`25-b16-static-timming.md` D-B16-1）。
- **错误信息**：只写键名与原因，不写取值，也不含文件内容。读文件失败时写 `LegacyConfigException` 的原因（如 `NOT_FOUND`、`UNSUPPORTED_CONSTRUCT`）与行列；合并后的校验失败时在原信息后补"以下项取自旧配置 extra/maccms.php，可用对应环境变量覆盖：maccms.site.name←site.site_name，……"。
- **依赖边界**：site 只能引用 `LegacyConfigService`、`LegacyConfigFile`、`LegacyConfigException`、`LegacyConfigConfiguration`、`PhpArray`、`PhpValue`（`ModuleBoundaryTest.SITE_LEGACYCONFIG`，02 规范第 2 节）；site 仍不自己读文件，不能引用 `LegacyConfigProperties` 与 `legacyconfig.infrastructure`。`LegacyConfigConfiguration` 让 site 的切片测试也能拿到同一个服务，与组件扫描并存时只有一份。
- **测试**：
  - `SiteLegacyConfigTest`：逐字段优先级、缺键保持默认、`site_status`/`rewrite.*` 换算表、各类拒绝、错误信息不含取值且注明来源、仓库自带 `maccms.php` 的合并结果。
  - `SiteLegacyConfigStartupTest`：启动时读取、运行中改文件不影响已启动的设置、环境变量覆盖、`base-url` 仍必填、`route_status`/写坏/缺文件/不合规取值都启动失败且不回显。
  - `SiteLegacyConfigPagesTest`：**等价性**——同一组取值分别经环境变量与经旧配置注入，首页、分类（含第 2 页）、详情，非规范地址、404、关站共 12 个地址的状态码、Content-Type、Cache-Control 与正文逐字节相同；三组取值（默认形态 + 本地图片；伪静态 + 隐藏后缀 + 远程图片 + `img_key`；关站）× W2 打开/关闭，另验证 W2 未设置时与打开时输出相同。

### 5. 浏览器侧外部地址放行（W1/W2/W3）

| 配置项 / 环境变量 | 默认 | 本批接受的值 |
|---|---|---|
| `maccms.browser-external.w1` / `MACCMS_BROWSER_EXTERNAL_W1` | `true` | `true`/`false`；放行输出在 B05 实现，在此之前取值不影响任何页面 |
| `maccms.browser-external.w2` / `MACCMS_BROWSER_EXTERNAL_W2` | `true` | `true`/`false`（不区分大小写）；`true` 时图片输出与 MACC-11/14 相同，`false` 时按下文关闭行为 |
| `maccms.browser-external.w3` / `MACCMS_BROWSER_EXTERNAL_W3` | `false` | 只接受 `false`：放行输出在 B24 实现 |

留空（环境变量设为空串）与未设置相同，取上表默认值。

- 部署级参数：只来自本应用环境变量，不是站点配置项，不出现在后台，不写入 `application/extra/*.php`。不合法时启动失败，信息只含配置项名称。始终注册（`common.browser.BrowserExternalConfiguration`），site 装配时引入。按路线图第五轮（D1 已全部决定，路线图第 4.2 节）：W1、W2 按旧版放行、生产默认打开；关闭态只是给部署方的可选项，也用于测试中的"无外部主机"断言；W3 随 B24 打开。
- **"本站"的判定**：只看配置的 `maccms.site.base-url` 的主机名（不区分大小写，忽略末尾的点、端口与协议），**不看**请求的 Host、X-Forwarded-Host（与 canonical 的做法一致，避免 Host 注入把外部主机变成"本站"）。站内路径（以单个 `/` 开头）一律算本站。
- **W2 设为 `false` 时图片的行为**（`ImageUrlNormalizer.withExternalImages(false)`，MACC-11 的规范化步骤不变，只在最终输出前判断）：
  - 结果是绝对地址且主机不是本站 → 新结果 `EXTERNAL_BLOCKED`，按空值处理。覆盖 `http(s)://`、协议相对 `//`、`mac:` 换成协议后的地址、`remote` 模式拼上外部 `remoteurl` 的地址。`remoteurl` 是本站主机时照常输出。
  - `img_key` 命中时**不拼 `img_api`**（它指向的代理脚本要由服务端去抓外部图片，属于 E02，新系统不提供），按原地址判断：外部图片被拦下，本站图片原样输出。
  - 视频卡片与详情海报显示现有的占位元素（`video-card__placeholder`、`video-detail__placeholder`，与图片为空时相同）；正文（富文本）里的外部图片整张删除（与 MACC-12 对不合格图片的处理相同）。
  - 正文里的外部**链接**（`a[href]`）照常输出：它是导航，不是页面加载的资源。
- **与路线图的一处出入：占位图**。路线图第 4.2 节写的是"本地占位图（从仓库 `static/` 现有图片中选定）"。本批改用组件里已有的 CSS 占位元素，理由：旧站 `static/` 不由 Java 应用提供，要用图片就得把图片放进 `backend/src/main/resources/static/site/`，并改 `components/videoCard.jte`——这两处归 Kimi（02 规范第 7 节），按任务要求须先协调。现状已满足"不输出外部主机"。如需换成图片（例如旧站的 `static/images/nopic.gif`），请 Kimi 在样式/组件里替换占位元素，后端视图模型不需要改。
- W1（播放器解析、缓冲/预加载页、`iva` 外部脚本）与外部播放/下载地址（W2 ②）所在页面在 B05 实现，届时接入本参数；W3 在 B24 接入。
- `maccms.site.logo-url` 目前没有任何页面输出；以后输出 Logo 时同样要经过 W2 判断（B05 或 Kimi 的外壳改动时处理）。

### 6. 与旧版的差异

| 项 | 旧版 | 新版（本批） |
|---|---|---|
| 页面中的外部图片 | 原样输出，浏览器直接请求外部主机；`img_key` 命中时拼 `img_api` 代理前缀 | 默认（W2 打开）与 MACC-11/14 一致，即与旧版相同；部署方把 W2 设为 `false` 时外部图片显示占位（正文中删除），不拼 `img_api` |
| 站点设置来源 | PHP 每次请求 `include` 配置，后台改完立即生效 | 环境变量优先、留空取旧 `extra/maccms.php`；启动时读一次，改了要重启（热加载在 B15）；`base-url` 只能由环境变量给出 |
| `site_status` 为空串 | PHP 7：`'' == 0` 为真 → 关站；PHP 8：为假 → 开站 | 按开站（PHP 8 的结果）。线上 PHP 版本未核实；后台单选框只写 `0`/`1`，正常不会出现空串。其他非数字字符串两版结果不同，新版拒绝启动 |
| 旧配置里新版不支持的 URL 形态 | 路由别名、`-` 分隔符、子目录安装、拼音/加密 id、静态页都能用 | 启动失败（第 4.4 节），不生成与旧站不同的地址；静态页（`view.*=2`）B16 起不再拒绝，链接仍为动态地址（25 文档 D-B16-1） |
| 配置读取 | PHP `include` 执行配置文件 | 只解析 `var_export` 子集，任何可执行写法都拒绝；负数键后省略键、NUL 字符串、`PHP_INT_MIN` 写法拒绝（第 4.2 节） |
| 配置语法错误 | ThinkPHP 报错 | 抛 `LegacyConfigException`（不含内容），不回退旧值 |
| 数据库工具、执行 SQL 等 | 可执行任意 SQL | 新系统任何入口都没有执行结构/维护/账号语句的路径，由护栏锁定（D10） |

### 7. 验证记录（2026-10-16，本机）

| 命令 | 结果 |
|---|---|
| `./mvnw verify`（第一个提交 `6815046`） | BUILD SUCCESS，1345 个测试，0 失败、0 跳过（符号链接测试在本机实际执行，未跳过） |
| `./mvnw verify`（第二个提交，含旧配置接入与 W1/W2 默认打开） | BUILD SUCCESS，1415 个测试，0 失败、0 错误、0 跳过。比 1345 多 70 个：`SiteLegacyConfigTest` 55、`SiteLegacyConfigStartupTest` 7、`SiteLegacyConfigPagesTest` 7、`BrowserExternalAccessTest` 净增 1 |
| `./mvnw verify -Pmysql-it`（MySQL 5.7.44，本机管理账号，一次性库，第二个提交） | BUILD SUCCESS：单元 1415 个；集成 8 个（`ContentApiMySqlIT` 2、`ContentSearchMySqlIT` 2、`SchemaGuardMySqlIT` 3、`SiteSearchMySqlIT` 1）。`SchemaGuardMySqlIT` 以仓库 `application/` 为旧配置目录、打开 site 启动整个应用，覆盖旧配置合并的完整装配。`maccms_it_*` 库均已删除并确认不存在，没有使用 `maccms_dev` |
| `php var_export_golden.php`（7.2.34 / 7.3.33 / 8.0.30 / 8.2.32） | 全部一致（15 个文件）。第二个提交没有改解析器，未重跑 |

测试数统计口径：Maven 的汇总行与 surefire XML 中 `<testcase>` 的个数一致（第一个提交都是 1345）。若把各 XML 的 `<testsuite tests="…">` 相加会得到 1344：`SiteSearchSwitchMvcTest` 含 `@Nested` 类，它的 XML 写的是 `tests="8"`，实际有 9 个 `<testcase>`。没有用例丢失。

### 8. 风险与遗留

- 线上配置文件可能含仓库里没有的写法（路线图 B02 风险）：解析器对不认识的写法一律拒绝，不会误读；真正接入线上副本前，应在本地对其副本跑一次 `LegacyConfigService`（不把内容写进仓库或评论）。
- 部署方把 W2 设为 `false` 时本地站点会大面积显示占位（路线图 4.2 节"后果"），不是缺陷。
- 静态护栏是文本扫描，有第 2、3.3 节所列局限；B23 的运行期出站护栏与代码评审补足。
- 旧 `site_logo` 若是后台上传得到的相对路径（如 `upload/...`），不符合 `logo-url` 的规则，会让启动失败；目前没有页面输出 Logo，可用 `MACCMS_SITE_LOGO_URL` 填绝对地址或站内路径覆盖。Logo 的旧版输出规则在 B05 做页面外壳时一并对齐。
- 旧 `site_name` 默认是空串：用仓库默认配置时仍须设置 `MACCMS_SITE_NAME`，否则启动失败（错误信息会指出来源）。
- site 设置不热加载（第 4.4 节），与旧版"后台改完立即生效"不同，B15 补上。
- 占位图是否换成图片需与 Kimi 协调（第 5 节）。

### 9. 回滚

本批两个提交（`6815046` 与其后的旧配置接入提交）以一个合并提交进入 main，`git revert` 即可回到上一 main。不改表结构、不改旧 PHP、不部署，回滚后页面恢复 MACC-14 的行为（外部图片原样输出，站点设置只读环境变量）。

- 只撤销第二个提交（回到 `6815046`）时：site 不再读旧配置；W1/W2 默认值回到关闭，且 `6815046` 的 W1 只接受 `false`——如果部署环境里显式设置了 `MACCMS_BROWSER_EXTERNAL_W1=true`，要先删掉或改为 `false`，否则启动失败。
- `MACCMS_SITE_STATUS`、`MACCMS_SITE_URL_*` 留空在两个版本里都等于 `open`/`false`，不需要清理。
