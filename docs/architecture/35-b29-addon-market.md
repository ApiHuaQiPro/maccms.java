## 35 B29 插件市场联网下载、安装、升级（E13，MACC-52）

> **状态：已在本地实现并通过 `./mvnw verify`，未部署，未与真实插件市场联调。** 依据：`docs/architecture/09-roadmap.md`（以下简称"路线图"）第 3.2 节 B29 行、第 4 节 E13 行、第 4.3 节实施规则、第 5.3 节 B29 验收行、第 5.7 节（本地插件与"没有 Java 实现的插件保持禁用"）、第 1.2 节零结构变更；B23 出站基础（26 文档）；B15 本地插件（24 文档第 11 节）。
> 不改旧 PHP、不改表结构、不连 `maccms_dev`、不连真实市场；黄金样本只在一次性库 `maccms_it_*` 与临时站点副本上生成。路径相对仓库根目录，代码在仓库根目录下。

### 1. 先说结论：旧版插件市场在这份生产代码里不能用

用真实旧后台（php-cgi + ThinkPHP + fastadmin-addons）跑出来的黄金（第 9 节）证实了三处旧版缺陷，决定了"与旧版一致"的含义：

| 旧版位置 | 现象（PHP 7.3 与 8.0 相同） |
|---|---|
| `Service::download` 用 `fast\Http::sendRequest`（`vendor/karsonzhang/fastadmin-addons/src/addons/Service.php:43`） | 备份 zip 的 `vendor/`、`extend/` 里都没有 `fast\Http` 类，也没有配置 `fastadmin.api_url`：**任何安装都是致命错误页**（场景 `prod-as-is`） |
| `Service::importsql` 用 `Db::getPdo()->exec()`（`:190`） | ThinkPHP 5.0.24 的 `Db::getPdo()` 在这里返回 `false`：插件包只要带 `install.sql`，安装就在导入这一步致命错误——**SQL 一条也没执行**，但插件文件、资源与全局文件已经复制，插件留在半装状态 |
| `Service::upgrade` 用 `__('Please disable addon first')`（`:506`） | `__()` 未定义：升级一个启用中的插件是致命错误页；升级不存在的插件在 `Service::backup` 里抛 `UnexpectedValueException`，也是致命错误页 |

所以黄金除了生产现状（`prod-as-is`）之外，都在临时站点副本里加了测试替身 `extend/fast/Http.php`（按 fastadmin 原版 `Http::sendRequest` 的 curl 用法写成）与 `application/extra/fastadmin.php`（`api_url` 指向本机模拟市场），以得到"能下载时"旧版的文件效果。新版按这些效果实现，致命错误的地方给出旧版本意的提示（第 10 节）。

### 2. 交付物

| 路线图 B29 | 实现 | 自动测试 |
|---|---|---|
| 市场下载（经 B23 出站客户端） | `integration.addonmarket.FastadminAddonMarket`、`AddonMarketConfiguration`；接口 `integration.application.AddonMarket`；白名单登记 `integration.addonmarket`（B29） | `FastadminAddonMarketTest` |
| 包大小上限 | `OutboundHttpClient.send(request, maxResponseBytes)`（按请求给上限，其余调用不变） | 同上 |
| 解包与路径穿越、zip 炸弹 | `admin.application.AddonPackage` | `AddonPackageTest` |
| 插件 SQL 只允许数据语句 | `admin.application.AddonSql` | `AddonSqlTest` |
| 安装、升级、备份、回滚 | `admin.application.AddonMarketService`；`AddonService.setState` 改为包内可见 | `AddonMarketServiceTest` |
| 后台动作 `addon/install`、`addon/upgrade` | `AdminAddonActions`（`AdminResponses.errorTo`）；`AdminConfiguration` 装配 | `AddonMarketGoldenTest`（PHP 黄金复放） |
| 文件区域 | `LegacySiteFiles.readAddonGlobal`、`writeAddonBackup`（只能写 `runtime/addons/<名称>-backup-<14 位数字>.zip`，只新建） | 同上 |
| 部署参数 | `application.yml` 的 `maccms.integration.addonmarket.*`、`config/maccms-backend.env.example` | `FastadminAddonMarketTest.apiUrlAndSizeConfiguration` |
| PHP 黄金 | `src/test/php/addon_market_golden.php` → `src/test/resources/admin/addonmarket/php{80,73}-golden.json` | `AddonMarketGoldenTest` |

后台界面：旧后台插件列表里的"安装"按钮本来就是空处理（`application/admin/view/addon/index.html:69-71`，B15 已原样转写），也没有"升级"按钮，所以本批**不改任何后台页面**；`install`/`upgrade` 只是后台接口，与旧版一样。

### 3. 下载（`FastadminAddonMarket`，对应 `Service::download`，Service.php:28-68）

- 请求：`GET <市场地址>/addon/download?name=<名称>&uid=…&token=…&version=…&faversion=…`。参数顺序与编码同 PHP `http_build_query(array_merge(['name' => $name], $extend))`：后台没提交的项不出现（值为 null）、空格编码为 `+`。请求头 `X-REQUESTED-WITH: XMLHttpRequest`。跟随跳转。**不看状态码**（旧版只看 curl 是否出错，404 的正文照样当作插件包，随后在解包时报"Unable to open the zip file"）。
- 正文以 `{` 开头时按 JSON：`data` 是带非 null `url` 的对象 → 再 `GET` 这个地址（不带上面的请求头），失败时按市场拒绝处理（带第一次的 JSON）；否则市场拒绝。市场拒绝时后台输出旧版 `$this->result($data, $code, $msg)` 的 `{code,msg,time,data}`，三项取 JSON 里的原值。
- 其余失败（连接失败、超时、超过包大小上限、`loopback-only` 下的外部地址、地址未配置）→ 旧版"无法下载远程文件"。
- 包只在内存里，不写 `runtime/addons/<名称>.zip`。
- 部署参数：`MACCMS_INTEGRATION_ADDONMARKET_API_URL`（对应 `fastadmin.api_url`；**默认留空**＝与这份生产代码一样没有配置，不发请求；填写时必须是不带查询串、片段、用户名密码的 http/https 地址，末尾 `/` 去掉，不合法即启动失败）；`MACCMS_INTEGRATION_ADDONMARKET_MAX_PACKAGE_BYTES`（默认 16 MiB，1 KiB～256 MiB）。fastadmin 原版的默认地址是 `https://api.fastadmin.net`，但这份生产代码没有用它，是否填写由部署方决定（第 11 节）。
- 密钥与日志：市场账号的 `uid`/`token` 照旧由后台表单 POST 提交，只放进发往市场的查询串；出站客户端只记用途（`addonmarket.download`/`addonmarket.package`）、主机、状态码、字节数与耗时，不记路径与查询串（`FastadminAddonMarketTest.downloadsThePackageWithTheLegacyQuery` 断言日志里没有令牌）。市场地址不承载令牌，所以不需要 `secret()`。

### 4. 插件包校验（`AddonPackage`）

任何一条不过，整包拒绝、不写任何文件：

| 检查 | 规则 | 提示 |
|---|---|---|
| 是 zip | 开头 `PK\3\4`（或空包 `PK\5\6`），能按中央目录打开，条目不加密 | 旧版的 `Unable to open the zip file` |
| 路径穿越 | 条目名不以 `/` 开头；不含 `\`、`:`、控制字符；没有空段、`.`、`..` 段；段不以点或空格结尾；不是 Windows 设备名（`con`、`nul`、`com1`…）；长度 ≤ 900；不能有不分大小写的同名条目，不能同一路径既是文件又是目录 | 插件包含不安全的路径，已拒绝安装 |
| zip 炸弹 | 条目 ≤ 2000；**按实际解压出的字节**（不信任头部声明）单个 ≤ 32 MiB、合计 ≤ 64 MiB；≥ 1 MiB 的条目压缩比 ≤ 200 | 插件包解压后超过文件数或大小上限，已拒绝安装 |

解析时整个包先写进系统临时目录的一个临时文件（`ZipFile` 需要文件），解析完立即删除；测试断言不留临时文件。zip 里的符号链接条目当作普通文件写出，不创建链接；写入一律经 `LegacySiteFiles`（逐段校验相对路径、解析后的真实路径必须在站点根目录内，符号链接逃逸也拒绝）。

### 5. 插件 SQL（`AddonSql`）

- 先按旧版 `importsql` 切块：逐行读，以 `--` 或 `/*` 开头的行跳过，去掉首尾空白后以 `;` 结尾的行结束一块；`__PREFIX__` 换成 `mac_`、`INSERT INTO ` 换成 `INSERT IGNORE INTO `（不分大小写）；最后没以 `;` 结尾的部分旧版不执行。
- 再把每一块按 MySQL 词法切成语句（引号、反引号、`--`/`#`/块注释里的 `;` 不算；旧版 `PDO::exec` 会执行一块里的多条语句，所以同一行的第二条也要看到），逐条看第一个关键字：**只有 `INSERT`、`UPDATE`、`DELETE`、`REPLACE` 是数据语句**。结构语句（`CREATE/ALTER/DROP/RENAME/TRUNCATE`）以及 `SET`、`SELECT`（可 `INTO OUTFILE`）、`GRANT`、`LOAD`、`CALL`、`LOCK`、MySQL 可执行注释 `/*!…*/`、无法识别的文本，一律视为不能执行。
- 只要有一条不能执行，**整个插件包拒绝、不落盘**："插件包的 SQL 含有不能执行的语句（关键字），新系统只接受数据语句，已拒绝安装"。安装时看合并后的 `install.sql`（插件包里的，或强制安装/升级时插件目录原有的），与旧版读的是同一个文件。
- **数据语句校验通过但不执行**（D-B29-3）：写入方矩阵（路线图第 5.3 节）没有给插件写库登记任何表，可写数据源也只执行写 Mapper 里登记的静态语句（12 文档第 3 节，禁止拼接 SQL）。这与旧版的实际效果相同——旧版在这一步致命错误，库里什么也没写（黄金里 `mac_link` 行数与表清单不变）。日志记一条"N 条数据语句校验通过、未执行"（不记 SQL 内容）。新系统没有任何路径执行插件 SQL，所以 `information_schema` 必然不变。

### 6. 安装与升级（`AddonMarketService`、后台动作）

后台动作（`AdminAddonActions`）：

| 动作 | 参数（同旧版） | 成功 | 失败 |
|---|---|---|---|
| `addon/install` | `input()` 的 `name`、`(int)force`；POST 的 `uid`、`token`、`version`、`faversion`。**新版只接受 POST**（D-B29-6） | `{code:1,msg:"安装失败",…}`——旧版 `lang('install_err')` 的笔误，照旧 | 名称为空：`param_err`；其他：旧版 `$this->error($msg, $code)` 把代码当成跳转地址，`url` 是当前动作地址；`AddonException`（冲突 -3、市场拒绝）输出 `{code,msg,time,data}` |
| `addon/upgrade` | POST 的 `name` 与上面四项 | `{code:1,msg:"更新成功",…}` | `param_err`；其他 `url` 为空 |

名称只接受 `[A-Za-z0-9_]+`（"插件名称不合法"）：旧版把名称直接拼进 `runtime/addons/<名称>.zip` 与 `addons/<名称>/`。

安装（`Service::install`，Service.php:261-327）：
1. 已存在且没有 `force` → `Addon already exists`；
2. 下载（第 3 节）→ 解析校验（第 4 节）；
3. 按"解到已有目录之上"的结果做旧版 `check`：主类文件 `<首字母大写的名称>.php`（"插件主启动程序不存在"）、`info.ini` 有 `name/title/intro/author/version/state`（"配置文件不完整"）；没有 `force` 时做冲突检查（`application/`、`static/` 下全局已有且内容不同的文件 → `AddonException("发现冲突文件", -3, conflictlist)`）；SQL 分析（第 5 节）——**以上全部通过才开始写**；
4. 记下将被改动的文件的原内容（插件目录、`static/addons/<名称>/`、`static/js/addons.js`、全局文件），写插件目录；
5. 启用规则（路线图第 5.7 节）：**有 Java 实现的插件**（`AddonService.JAVA_ADDONS`，目前只有 `adminloginbg`）按旧版复制 `assets/` 到 `static/addons/<名称>/`、复制全局文件、`state` 为假时置 1；**其他插件保持禁用**：`info.ini` 的 `state` 为真时改为 0，不复制资源与全局文件，钩子表里不出现；
6. 数据语句不执行（第 5 节）；插件自己的 PHP `install()` 不执行；
7. `Service::refresh`（`addons.js` 与 `extra/addons.php` 的钩子表，沿用 B15 的逐字节实现）。

升级（`Service::upgrade`，:502-551）：插件不存在 → `Addon not exists`；`state` 为真 → `Please disable addon first`；取升级前的配置值（`get_addon_config`）→ 下载、解析校验、SQL 分析（合并后的 `install.sql`）→ **通过后**写旧版的备份 `runtime/addons/<名称>-backup-<YmdHis>.zip`（北京时间，只新建）→ 记下原内容 → 把包解到插件目录之上（旧文件保留）→ 新 `config.php` 中同名项换回升级前的值（`set_addon_config`：非数组类型遇到数组时逗号连接）→ 没有 Java 实现的插件若被包里的 `info.ini` 置成启用，改回 0 → `refresh`。

同一进程内安装、升级串行执行（`synchronized`）。

### 7. 出站护栏与登记

- `IntegrationPackages.REGISTERED` 加 `integration.addonmarket → B29`；出站客户端按直接调用方的包检查，后台模块不直接出站（`OutboundNetworkGuardTest` 照常扫描 `src/main`）。
- 测试只连本机模拟服务（`LoopbackMockServer`），`TestOutboundGuard` 无命中。
- 紧急停止：`MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only` 时市场请求以 `TARGET_REJECTED` 失败，后台提示"无法下载远程文件"；或直接把 `MACCMS_INTEGRATION_ADDONMARKET_API_URL` 留空。

### 8. 回滚

- **单次操作失败的自动回滚**：写插件目录、启用、恢复配置、刷新钩子表任何一步失败，按第 6 节记下的原内容恢复（原来没有的删除，插件目录与 `static/addons/<名称>/` 整体恢复），再按恢复后的状态刷新一次钩子表与 `addons.js`。回滚本身失败只记异常类型，由管理员按下一条用快照恢复。测试：`AddonMarketServiceTest.failureWhileWritingRollsBackEverything`、`failedUpgradeRestoresTheOldDirectory`（站点逐字节恢复）。
- **部署回滚**：开放插件市场（填写市场地址）前复制 `addons/`、`static/addons/`、`static/js/addons.js`、`application/extra/addons.php`；回滚时用复制件覆盖、删除新增文件（含 `runtime/addons/*-backup-*.zip`），再把 `MACCMS_INTEGRATION_ADDONMARKET_API_URL` 留空或回退代码。演练：`AddonMarketServiceTest.snapshotRestoreDrill`（安装、禁用、升级后恢复快照，站点与开启前逐字节相同）。新版写出的 `info.ini`、`addons.php`、`addons.js` 与 PHP 逐字节相同（黄金第 9 节），切回 PHP 照常读取。
- **代码回滚点**：本批基于 `dc38bae`（main `36750f4` 之上的任务基线），回退本批提交即可；数据库无任何变化。

### 9. 验证

**PHP 黄金**（`src/test/php/addon_market_golden.php`，PHP 8.0.30 与 7.3.33 各一份，15 个场景、每版 23 步）：一次性库 `maccms_it_<时间>_<随机>`、临时站点副本、本机模拟市场（`php -S 127.0.0.1:随机端口`，插件包由脚本生成并以 base64 写进黄金）。每步记录状态码、正文、模拟市场收到的请求（地址与 `X-REQUESTED-WITH`）、站点里变化的文件（SHA-256）、一次性库里新出现的表与 `mac_link` 行数。场景：生产现状；`adminloginbg` 卸载 → 市场安装（带 `uid/token`）→ 启用中升级 → 禁用 → 升级（配置值保留、备份）；第三方插件（资源、全局文件、`bootstrap.js`、数据语句）与重复安装；JSON 下载地址；JSON 地址下载失败；市场拒绝两种；HTTP 404；连接失败；坏 zip；路径穿越 zip；40 MiB zip 炸弹；结构语句；缺主类；冲突文件；参数缺失与升级不存在的插件。

**新版复放**（`AddonMarketGoldenTest`，不连库，`./mvnw verify` 里运行）：同样的初始文件、同样字节的插件包、同样的下载计划，两版共 **46 步全部一致**（差异按第 10 节逐条换算后断言）；`adminloginbg` 的安装、禁用、升级在 `info.ini`、`config.php`、`extra/addons.php`、`static/js/addons.js`、`static/addons/…` 上与 PHP **逐字节相同**；另断言系统临时目录没有残留。

**单元测试**：`AddonSqlTest`（8）、`AddonPackageTest`（9）、`AddonMarketServiceTest`（13）、`FastadminAddonMarketTest`（7）。全量 `./mvnw verify` 结果见交付评论。

### 10. 与旧版的差异

| 编号 | 旧版 | 新版 | 性质 |
|---|---|---|---|
| D-B29-1 | 生产代码没有 `fast\Http`、没有 `fastadmin.api_url`：安装是致命错误页 | 市场地址是部署参数，默认留空 → "无法下载远程文件"、不发请求；填写后可用 | 修复（旧版不可用） |
| D-B29-2 | 升级启用中的插件：`__()` 未定义，致命错误页；升级不存在的插件：致命错误页 | `Please disable addon first`；`Addon not exists` | 修复 |
| D-B29-3 | 插件 SQL：`Db::getPdo()` 为 false，带 `install.sql` 的安装/升级在导入时致命错误，SQL 一条没执行，插件半装 | 非数据语句整包拒绝、不落盘；数据语句校验通过但不执行（写入方矩阵未登记插件写库），安装照常完成；库都不变 | 零结构变更（路线图 1.2）＋写入规则；**是否要执行数据语句待队长定**（第 11 节） |
| D-B29-4 | 安装后默认启用：复制资源与全局文件、登记钩子、执行插件 PHP 的 `install()` | 只有登记了 Java 实现的插件按旧版启用；其他插件 `state = 0`、不复制资源与全局文件、不登记钩子；插件 PHP 方法都不执行 | 路线图第 5.7 节（同 D-B15-11） |
| D-B29-5 | 下载失败、坏包时留下 `runtime/addons/<名称>.zip` | 包只在内存里，不留文件 | 加固（无残留） |
| D-B29-6 | `addon/install` 用 GET 也会安装 | 只接受 POST（后台 POST 另有来源校验，B12） | 加固（防跨站请求） |
| D-B29-7 | libzip 把 `../` 去掉后照样安装；名称直接拼路径 | 路径不安全的包整包拒绝；名称只接受 `[A-Za-z0-9_]+` | 加固 |
| D-B29-8 | 不限大小：40 MiB 的 zip 炸弹整个解开并复制到 `static/addons/` | 下载上限 16 MiB（可配）；解压上限见第 4 节 | 加固 |
| D-B29-9 | 冲突列表在 Windows 上用 `\` | 一律 `/`（与 Linux 上的旧版相同） | 平台差异 |
| D-B29-10 | `check`/冲突失败时 `rmdirs` 插件目录（`force` 时连原有插件一起删掉）；写到一半失败没有回滚 | 先校验后落盘，不写不删；写入失败自动回滚 | 加固 |
| D-B29-11 | 升级在下载前先写备份；备份 zip 含目录条目、Windows 上条目名带 `\` | 包通过校验后才写备份；只含文件条目、名称用 `/`（内容等价，不逐字节） | 无残留 |
| D-B29-12 | `CURLOPT_SSL_VERIFYPEER=false`；连接与总时限各 30 秒 | 校验 TLS 证书；超时按出站客户端（连接 15 秒、整体 30 秒） | 同 B23 |
| D-B29-13 | `{code,msg,time,data}` 的 `time` 是字符串 | 数字（沿用 B15 的 `AddonException` 输出） | 格式 |

保持不变（照旧）：成功提示"安装失败"（旧版 `lang('install_err')` 笔误）；`Addon already exists`、`插件主启动程序不存在`、`配置文件不完整`、`发现冲突文件`、`Unable to open the zip file`、`无法下载远程文件` 等提示原文；JSON 下载地址的二次下载；状态码不看；`uid/token` 的传法。

### 11. 遗留与待定

- **待联调**（转 B21）：真实插件市场的测试源与地址（路线图第 4.3 节 ②，需用户提供）。fastadmin 官方市场的包是给 fastadmin 后台的，与 MacCMS 插件格式未必一致；这份生产代码从未配置过市场地址，旧版实际上一直不可用。
- **待队长定（D-B29-3）**：插件包里的数据语句要不要执行。要执行就需要先在路线图第 5.3 节写入方矩阵里登记（表 + 操作 + B29），并设计一条不拼接 SQL 的写入路径（现有写入规则禁止动态 SQL）。在此之前保持"校验但不执行"，与旧版实际效果一致。
- 插件市场列表（`downloaded` 拉取市场数据）在这份生产代码里已被去掉（`Addon.php:88`），本批不恢复；后台"安装"按钮仍是空处理（旧版如此）。
- 本地上传 zip 安装（`addon/local`）照旧返回 `closed`（B15）。
