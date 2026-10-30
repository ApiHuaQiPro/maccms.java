## 图片地址规范化（mac_url_img 等价）与 PHP 黄金向量（MACC-11）

> **状态：规范化器与黄金向量已实现，尚未接入页面，也未绑定外部化配置。** 只处理"作为图片 URL 的字符串"（`vod_pic`、`type_logo` 等字段），不过滤富文本：正文里的 `<img>`（旧 `mac_url_content_img`，`application/common.php:1659`）与 `vod_content` 输出属于富文本任务。不涉及图片上传/管理、旧 PHP、数据库、OpenAPI 或 content API。

### 1. 位置

| 位置 | 内容 |
|---|---|
| `src/main/java/com/maccms/backend/site/application/ImageUrlConfig.java` | 旧 `upload.protocol/mode/remoteurl/img_key/img_api` 的原始字符串 |
| `src/main/java/com/maccms/backend/site/application/ImageUrlNormalizer.java` | `create(config, SiteSettings)` 校验配置；`normalize(raw)` 返回 `Optional<SafeUrl>`；`resolve(raw)` 另给出命中的旧分支与拒绝原因 |
| `src/main/java/com/maccms/backend/site/application/HtmlAttributeCharRefs.java` | 按浏览器解析属性值的规则解码字符引用（子集） |
| `src/test/resources/site/image-url/mac-url-img-vectors.json` | 黄金向量：18 组合成配置、71 条向量 |
| `src/test/resources/site/image-url/mac_url_img_golden.php` | 生成/复核向量 `php` 字段的脚本（不在 Maven 构建中运行） |
| `src/test/java/com/maccms/backend/site/application/ImageUrlGoldenVectorsTest.java` | 逐条断言旧语义、新输出与兼容关系 |
| `src/test/java/com/maccms/backend/site/application/ImageUrlNormalizerTest.java` | 配置校验、边界、解码器与 jsoup 的差分 |

旧版的 `MAC_PATH`（`site.install_dir`，`application/common/behavior/Init.php:48`）用 `SiteSettings.homePath()`（`base-url` 的路径部分）代替；协议相对地址的补全用 `base-url` 的协议。

### 2. 旧逻辑映射（`application/common.php:1633-1657`）

| 旧代码 | 行为 | 新版 |
|---|---|---|
| `:1635-1640` 前 4 个字符是 `mac:` | `upload.protocol`（`empty()` 为真即空串或 `"0"` 时取 `http`）替换字符串中**所有** `mac:` | `Branch.MAC_PROTOCOL`，逐字等价（`String.replace`）。保存时旧代码把 `protocol:` 换成 `mac:`（如 `application/common/model/Actor.php:408`） |
| `:1642` 不以 `http`、`//`、`/` 开头（区分大小写） | 相对路径 | `RELATIVE_LOCAL` / `RELATIVE_REMOTE`。`MAC:`、`HTTPS:`、`javascript:`、`data:` 都落在这里 |
| `:1643-1644` `upload.mode == 'remote'` | 拼 `upload.remoteurl` | `RELATIVE_REMOTE`，直接拼接 |
| `:1646-1647` 其他 mode（含 `local`、`ftp` 等） | 拼 `MAC_PATH` | `RELATIVE_LOCAL`，拼 `homePath` |
| `:1650-1651` 以 `http`/`//`/`/` 开头，`img_key` 非空且 `preg_match('/'.img_key.'/', url)` | 前面拼 `upload.img_api`（URL 不编码） | `IMG_API`，Java 正则 `find()`，区分大小写；`mac:` 与相对路径不再检查 `img_key` |
| 以上都未命中 | 原样 | `UNCHANGED` |
| `:1653` `mac_filter_xss`（`:1222-1225`）= `trim(htmlspecialchars(strip_tags($s), ENT_QUOTES))` | 输出 HTML 转义后的字符串，模板原样插入 `src="…"` | 不再输出转义串，改为求浏览器最终请求的地址，见第 3 节 |
| `:1654` 删除 `&quot;&gt;` | 去掉 `">` 注入的残留 | 不模拟：含引号即拒绝 |
| `:1655` `&amp;` → `&` | 抵消 htmlspecialchars 对 `&` 的转义，库内的 `&amp;` 等字符引用留给浏览器解码 | 按属性规则解码后再由 jte 转义输出，浏览器得到同一地址 |

### 3. 新版步骤与安全策略

1. 分支选择与拼接，与旧代码逐字等价（黄金向量对拍）。
2. 空值（null、空串、只有空白）→ `EMPTY`，不输出地址。
3. 拼接结果含 `<` → `TAG_LIKE`（旧版 `strip_tags` 会改写地址，新版不模拟）。
4. PHP `trim`（空格、`\t\n\r\0\x0B`）→ 按 HTML 属性规则解码字符引用 → 去掉首尾 C0 控制字符与空格 → 中间空格编码为 `%20`。这三步就是浏览器处理旧输出的过程。解码只支持一个子集（`HtmlAttributeCharRefs`）：数字引用；带分号的 `amp/quot/lt/gt`（含全大写）与 `apos`；不带分号且浏览器会解码的只支持 `amp/quot/lt/gt`。其他可能被浏览器解码的引用（如 `&hellip;`、末尾的 `&copy`）→ `UNSUPPORTED_CHAR_REF`。浏览器不解码的（`&b=`、`&copy=1`、`&notify`）原样保留。
5. 协议相对地址（`//host/…`）按 `base-url` 的协议补全。
6. `SafeUrl` 白名单：只放行 http/https 绝对地址（有主机名、无用户信息）与以单个 `/` 开头的站内路径；空白、控制字符、反斜线、引号、尖括号、反引号、`java.net.URI` 不接受的字符都拒绝 → `NOT_ALLOWED`。
7. 主机不设白名单：采集数据中的图片本来就来自任意站点，旧版也照样输出；`SiteHtmlRenderer` 的 CSP `img-src 'self' http: https:` 与此一致。

`javascript:`、`data:` 等恶意 scheme 在旧代码中先落入相对分支，变成本站或图片域名下的普通路径（如 `/javascript:alert(1)`），不会执行，新版照搬；`mac:javascript:…` 得到 `http:javascript:…`，新版拒绝。

### 4. 配置校验（`ImageUrlNormalizer.create`）

不合法时抛 `IllegalStateException`，一次列出所有出错的配置项名称与规则，不回显取值。

| 配置 | 规则 | 旧版 |
|---|---|---|
| `upload.protocol` | 空、`0`（按 http）、`http`、`https` | 任意值照拼，如 `ftp://…`、`javascript://…` |
| `upload.mode` | 只与 `remote` 比较，其他值都按本地 | 同 |
| `upload.remoteurl` | 只在远程模式校验：http/https 绝对地址或协议相对地址，有主机名，不含用户信息、`?`、`#`、`&`，以 `/` 结尾，≤256 字符 | 为空时输出相对地址（浏览器按当前页面目录解析）；不以 `/` 结尾时主机名被改写（`…example.testupload/…`） |
| `upload.img_key` | 空或 `0` 不启用；否则按 PHP 定界符规则不含未转义的 `/`、不以未转义的 `\` 结尾，无控制字符，能被 Java 正则编译，≤256 字符 | 定界符错误时 `preg_match` 报 E_WARNING 并返回 false；在 ThinkPHP 中 E_WARNING 会被转为异常（`thinkphp/library/think/Error.php:28,71`，代码阅读，未实测） |
| `upload.img_api` | 只在启用 `img_key` 时校验：空，或补全协议后能通过 `SafeUrl`，不含 `<`，≤256 字符 | 任意值照拼 |

### 5. 黄金向量

- **来源**：向量的 `php` 字段是旧函数的真实输出。`mac_url_img_golden.php` 用 `token_get_all` 从 `application/common.php` 原样取出 `mac_url_img` 与 `mac_filter_xss` 两个函数并 `eval`，不改动旧代码，不加载其余部分；`$GLOBALS['config']['upload']` 只填向量文件里的合成配置；`MAC_PATH` 是常量，每个取值在独立子进程中定义。
- **复核**：`php src/test/resources/site/image-url/mac_url_img_golden.php`（默认核对，`--print` 打印实际输出）。2026-10-11 在本机 PHP 7.2.34、7.3.33、8.0.30、8.2.32 上 71/71 一致；把一条向量的 `php` 改错后脚本报 `MISMATCH` 并以 1 退出（随后已还原）。
- **脱敏**：只用合成值，域名都在保留顶级域 `.test` 下（`vectorFileUsesOnlySyntheticHosts` 检查），用户信息样例为 `user:pass`。线上 `upload` 配置未知，向量不代表生产行为。
- **覆盖**：旧分支 MAC_PROTOCOL 8、RELATIVE_LOCAL 21、RELATIVE_REMOTE 6、IMG_API 9、UNCHANGED 27；兼容关系 SAME 37、PROTOCOL_RELATIVE_COMPLETED 5、REJECTED 22、CONFIG_REJECTED 7。包括空值/NULL/纯空白、`javascript:`/`data:`/`httpx:`/`mac:javascript:` 等 scheme、协议相对与 `///`、`/\`、反斜线、`img_key` 命中/未命中/大小写/`"0"`、`img_api` 为站内路径/绝对/协议相对/空、HTML 实体与属性逃逸、标签、NUL、制表符、非 ASCII 与空格，以及 7 种不合法配置。
- **Java 断言**（每条）：① 新版选出的分支等于向量记录的分支；不含 `<` 的输入，把新版分支结果按旧过滤逐步处理（删除 NUL、`htmlspecialchars(ENT_QUOTES)`、PHP trim、两次 `str_replace`）后，必须等于 `php` 字段；② 结果与拒绝原因等于 `expect`；③ SAME / PROTOCOL_RELATIVE_COMPLETED 用 jsoup 按属性规则解码 `php` 字段（独立于被测解码器），求出浏览器地址并与新 `href` 比较。配置错误的向量断言异常含配置项名称、不含取值。

### 6. 与旧版的差异

| 情形 | 旧版 | 新版 |
|---|---|---|
| 空值、NULL、只有空白 | 输出 `MAC_PATH`（如 `/`）或 `remoteurl` | 无地址（页面显示占位图） |
| 协议相对地址（库内值、`remoteurl` 或 `img_api` 产生） | 原样，浏览器按当前页面协议补全 | 按 `base-url` 的协议补全；同一站点同时以 http 与 https 提供时，结果可能与浏览器当前协议不同 |
| 含 `<` | `strip_tags` 删除标签后输出剩余部分 | 拒绝 |
| 含 `"`、`'`、`>`（含解码后） | 转义输出，浏览器得到含引号的地址；`">` 被删除 | 拒绝 |
| 制表符、换行、NUL 等出现在中间 | 原样（NUL 被 `strip_tags` 删除），浏览器解析时删除制表符/换行 | 拒绝（不放过 `java<TAB>script:` 类混淆） |
| 反斜线、`/\host`、`///host` | 原样，浏览器可能当作其他主机 | 拒绝 |
| `http:host/…`（`mac:` 后没有 `//`）、`httpx:` 等非 http/https | 原样 | 拒绝 |
| 带用户信息；绝对地址中有 `java.net.URI` 不接受的字符（如 `\|`）或非法 `%` 编码；超过 2048 字符 | 原样 | 拒绝（站内路径不做 URI 解析，`%` 原样保留） |
| 浏览器会解码的其他字符引用（`&hellip;`、末尾的 `&copy`、0x80～0x9F 数字引用等） | 浏览器解码 | 拒绝 |
| 不合法配置（第 4 节） | 照拼或报警告 | 启动失败 |
| 正则方言 | PCRE，无 `u` 修饰符时按字节匹配 | Java 正则按字符匹配；常见写法（字面域名、`.`、`\|`、字符类）一致，少数 PCRE 专有语法未逐一核对 |

未改变的旧行为（照搬，结果安全）：`mac:` 全部替换；`MAC:`、`HTTPS:` 等大写前缀落入相对分支成为站内坏链；以 `/` 开头的路径在子目录安装时也不拼前缀；`img_api` 拼接时原地址不编码（地址中的 `&` 会被代理当作参数分隔）；`img_key` 区分大小写；http 不升级为 https。

### 7. 接入（后续页面任务）

- 配置绑定：在 `maccms.site.*` 下增加图片配置项（名称随页面任务确定），启用 site 时由 `SiteConfiguration` 调用 `ImageUrlNormalizer.create` 校验，与 `SiteSettings` 同样启动即失败。线上 `upload.*` 取值【待验证】，并行期两边分别维护，有漂移风险。
- 视图模型只放 `normalize` 的结果；为空时由模板显示占位图（旧站为 `static/images/nopic.gif`）。不要把原始值或 `resolve` 的结果写进日志。
- 富文本中的图片（`mac_url_content_img`）、`vod_content` 过滤不在本任务范围。

### 8. 未验证与风险

- 线上 `upload.*` 配置与 PHP 版本未知；仓库里的 `application/extra/maccms.php` 是开发值。
- 浏览器字符引用规则以 jsoup 作参照：jsoup 在属性中还把 `-`、`_` 当作阻止解码的后继字符，比规范多；新版只在支持的子集内与 jsoup 对拍，没有用真实浏览器验证。
- `img_key` 由管理员配置，未限制正则回溯；输入长度受库字段限制。
- ThinkPHP 把 `preg_match` 警告转成异常的行为只读了代码，没有在完整应用中实测。
- 没有用真实数据统计各类图片地址的分布（例如含空格、`|` 或字符引用的比例），拒绝后的占位图比例未知。
