# APPK-2 附表 F：PHP 对照基线与比较规则

> 属于 `docs/prd/APPK-2.md`（v8，本附表为 v8 新增，回应评审 Q-002、Q-003）。冲突时以主文件为准。本附表固定三件事：①做对照的 PHP 运行时（F.1）；②对照时比较什么、怎么归一化、什么差异绝不能忽略、哪些差异允许豁免（F.2～F.5）；③合成数据的构造规则（F.6）。AC-2～AC-10、KPI-1、KPI-5、PRE-2、BR-3、BR-7 都引用本附表，不再各写一套。
>
> **生效方式**：本附表随本文版本经 G0“范围确认”生效。发起人在 Issue 里回复的“确认范围 APPK-2 v8 <提交号前缀>”所指的版本包含本附表，即视为发起人对 F.5 豁免清单的确认（此前 BR-7 写的“豁免清单在设计单提出、经你确认后才生效”改由本附表一次给全，不再留到设计单阶段）。本附表没有待定项，不新增 OP-。
>
> **取证方式**：F.1 的版本、扩展、配置值取自本机已安装的 `D:\Tools\php\7.3`（`php -v`、`php -m`、`ini_get`）和仓库源码（`php/application/config.php`、`database.php`、`install/controller/Index.php`、`common/controller/All.php`）；**没有运行 `php/` 站点，没有运行任何对照**。凡“实际返回值”只能在 PRE-2 采集的，已写成“基线无效条件”，不是预测值。

## F.1 PHP 对照运行时（唯一一份；回应 Q-002）

PHP 对照用的 `php/` 代码是 `2023.1000.3051`（ThinkPHP 5.0.24），模板是附表 E 的包。运行时只有下面这一份，不允许“7.3 或 8.0”二选一；改变其中任一项都要修订本文（版本 +1）。

| 编号 | 项 | 固定值 | 依据 / 基线无效条件 |
| --- | --- | --- | --- |
| PHPB-1 | PHP 版本 | **PHP 7.3.33，NTS，x64，MSVC15**（本机 `D:\Tools\php\7.3\php.exe`）。不使用 7.2、8.0、8.2 | `AGENTS.md` 第 77～85 行：7.3 与 8.0 都验证过能启动对照站，但 8.x 下 `common/extend/sms/Qcloud.php:179`、`common/util/SinaUpload.php:105` 有 Deprecated，7.3 没有。`php -v` 输出写入基线报告，小版本不是 7.3.33 则基线无效 |
| PHPB-2 | 运行方式 | PHP 内置 Web 服务（`php.exe -S`，SAPI = `cli-server`），单进程串行处理样本，不并发；入口脚本、路由脚本和监听地址由 PRE-2 登记 | `AGENTS.md` 第 77 行记录的起法。SAPI 写入基线报告 |
| PHPB-3 | 必需扩展 | `pdo`、`pdo_mysql`、`mysqlnd`、`zip`、`fileinfo`、`curl`、`xml`、`mbstring`、`gd`、`json`、`openssl`、`iconv`、`session`、`ctype`、`tokenizer`。其中 `pdo`、`pdo_mysql`、`zip`、`fileinfo`、`curl`、`xml` 是安装向导 `checkFunc()`（`install/controller/Index.php:347-356`）检查的扩展或函数，`mbstring` 提供该处检查的 `mb_strlen`；其余是验证码图片、编码转换、会话和模板解析要用到的 | `php -m` 全文写入基线报告；缺任一项则基线无效。本机 7.3.33 的模块集合（`bcmath calendar Core ctype curl date dom exif fileinfo filter ftp gd gettext gmp hash iconv intl json ldap libxml mbstring mysqli mysqlnd openssl pcre PDO pdo_mysql PDO_ODBC pdo_sqlite Phar readline Reflection session SimpleXML soap sockets SPL sqlite3 standard tokenizer wddx xml xmlreader xmlwriter Zend OPcache zip zlib`）包含全部必需项；多出的模块不改变基线 |
| PHPB-4 | 时区 | `php.ini` 的 `date.timezone = PRC`，与 `php/application/config.php:42` 的 `default_timezone = 'PRC'` 一致；Java 侧取同一时区 `PRC`（UTC+8） | 本机 `php.ini` 现值是 `UTC`，PRE-2 必须为对照进程另指定一份 `php.ini` 改成 `PRC`；`ini_get('date.timezone')` 不是 `PRC` 则基线无效 |
| PHPB-5 | 字符集 | `default_charset = UTF-8`；PHP 到 MySQL 的连接字符集取 `php/application/database.php:29` 的 `utf8`（不改）；响应体 UTF-8，无 BOM | 基线报告记录 `ini_get('default_charset')` 与一个中文样本的 `Content-Type` 头 |
| PHPB-6 | 区域（locale） | 启动对照进程时环境变量 `LC_ALL=C`，不设 `LANG`；请求处理开始时 `setlocale(LC_ALL, 0)` 的返回中 `LC_CTYPE` 必须是 `C` | 本机 CLI 默认返回 `LC_CTYPE=English_United States.1252`，PHP 7.3 的 `strtolower` 等函数受它影响，会改变 UTF-8 高位字节；基线报告附 `setlocale(LC_ALL, 0)` 实际返回，`LC_CTYPE` 不是 `C` 则基线无效，需调整启动方式后重做 |
| PHPB-7 | 错误显示与数值精度 | `display_errors = Off`，`log_errors = On`（日志写文件，不进响应体）；`error_reporting` 不另设（由 ThinkPHP 自己的错误处理接管）；`precision = 14`，`serialize_precision = -1`；`short_open_tag = Off`；`memory_limit = 256M` | `php/application/config.php:20` `app_debug = false`、`:161` `show_error_msg = true`，PHP 自身出错时响应体是 ThinkPHP 的错误页，不是 PHP 原始报错文本。BR-3 说的“PHP 自身报错时 Java 输出相同”指这个错误页（状态码 + 页面内容），不要求复刻写入日志的 PHP 警告 |
| PHPB-8 | 数据库端 | MySQL 5.7.44（本机服务 `MySQL57`），PHP 与 Java 两个进程的会话 `time_zone` 都设为 `+08:00`；库为 A.3 的 `maccms_it_full` | `SELECT @@version, @@session.time_zone` 写入基线报告；两侧不同则基线无效。本机 `@@time_zone` 现为 `SYSTEM`（系统时区是太平洋时间），所以必须显式设置 |
| PHPB-9 | 配置文件 | PHP 与 Java 读取同一份 `application/extra/*.php`（`php/` 的 13 个 CS-CONFIG 文件，附表 D 的 D.2）拷贝，逐文件 SHA-256 相同；模板目录配置项 `template_dir`、`html_dir`、`mob_template_dir`、`mob_html_dir`、`ads_dir` 的取值按 OP-15 的决定，两侧各写其对应值 | 基线报告列出 13 个文件的 SHA-256；不一致则基线无效 |

**基线报告**：PRE-2 产出 `docs/test/APPK-2/php-baseline-report.md`，必须含：`php -v`、`php -m`、PHPB-4～PHPB-7 的 `ini_get` 实际值、`setlocale(LC_ALL, 0)` 实际返回、MySQL 版本与会话时区、13 个配置文件的 SHA-256，以及 PHP 自身对模板报错或输出异常的样本清单。任一“基线无效条件”命中，AC-2～AC-10 的结果作废。

## F.2 比较输入（每个样本比什么；回应 Q-003）

| 编号 | 规则 |
| --- | --- |
| CMP-1 | **请求相同**：同一 HTTP 方法、同一路径和查询串、同一请求体；固定请求头 `Accept: text/html,application/xhtml+xml`、`Accept-Language: zh-CN`；桌面样本固定 `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) APPK2-Desktop`，手机样本固定 `User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) APPK2-Mobile`；除样本清单（`sample-desktop.md`、`sample-mobile.md`）逐行写明的以外，不带 `Referer`。需要登录的样本，两侧各自登录一次，会话 Cookie 的值不同，不参与比较（F.4 的“不比较的响应头”） |
| CMP-2 | **数据相同**：两侧连同一个库 `maccms_it_full`（A.3），样本串行执行。每个样本执行前，由 `it_admin` 把 26 张表的数据恢复到 PRE-2 固化的数据快照（`CHECKSUM TABLE` 26 张表与快照一致才开始），PHP 请求与 Java 请求各自在恢复后的库上执行，互不影响。恢复只改数据不改结构 |
| CMP-3 | **比较的内容有且仅有三项**：①HTTP 状态码；②响应头 `Content-Type` 的完整值（含 `charset`）；③状态码为 3xx 时，响应头 `Location` 的值。外加④响应体字节（见 F.3） |
| CMP-4 | **静态生成文件**（AC-7 的 K-01～K-12）：比较两侧生成的文件相对路径与文件字节内容，规则同 F.3，没有状态码和响应头 |
| CMP-5 | **语言键**（KPI-5）：对每个键比较 `lang(key)` 的返回字符串，逐字节，不做替换 |

## F.3 归一化算法（唯一一份）

归一化只做“用占位符替换豁免项”这一件事，不做任何空白折叠、属性排序、实体转换、大小写转换。两侧按同一顺序执行：

| 编号 | 步骤 |
| --- | --- |
| NORM-1 | 取 CMP-3 的前三项和响应体字节。响应体的 `Content-Type` 主类型是 `text`，或者是 `application/xml`、`application/json`、`application/javascript` 时进入 NORM-2；其他类型（图片等）跳过 NORM-2～NORM-4，只做逐字节比较 |
| NORM-2 | 响应体按 UTF-8 解码；解码失败（出现非法字节序列）的样本跳过 NORM-3，逐字节比较，且该样本在报告中单独标注 |
| NORM-3 | 按 F.5 里 EX- 的编号从小到大，对 `Location` 值和响应体做字面串替换，每处命中替换为 `⟦EX-n⟧`；同时记录每个 EX- 在该样本该侧的命中次数 |
| NORM-4 | 替换完成后，两侧的状态码、`Content-Type`、`Location`、响应体逐字节比较；任何一处不同，该样本判失败 |
| NORM-5 | 同一样本两侧任一 EX- 的命中次数不相等，判失败（防止豁免规则只在一侧吞掉差异） |

## F.4 禁止忽略的差异（出现即判失败）

| 编号 | 不得忽略的差异 |
| --- | --- |
| NOIGN-1 | 状态码不同；`Content-Type` 的类型或 `charset` 不同；`Location` 的路径、参数不同（F.5 的 EX- 命中部分除外） |
| NOIGN-2 | 任何可见文字、数字、链接地址、图片地址、表单字段名和值不同 |
| NOIGN-3 | 标签属性的有无、取值、顺序不同；标签的嵌套和闭合不同；HTML 实体的写法不同（如 `&amp;` 与 `&`） |
| NOIGN-4 | 空格、制表符、空行、换行（`\r\n` 与 `\n` 视为不同）的数量和位置不同；行尾空白不同 |
| NOIGN-5 | HTML 注释、`<script>` 与 `<style>` 的内容不同 |
| NOIGN-6 | 来自数据库的任何字段值不同，包括排序、条数、分页链接 |
| NOIGN-7 | 编码、BOM 的有无不同 |
| NOIGN-8 | 响应体长度不同（由以上各项自然得出，单独列出以便报告里直接引用） |

**不参与比较的响应头**（只此一份，其余响应头也不参与，因为 CMP-3 已列全比较项）：`Date`、`Server`、`X-Powered-By`、`Set-Cookie`、`Content-Length`、`Connection`、`Cache-Control`、`Expires`、`Pragma`、`Last-Modified`、`ETag`、`Transfer-Encoding`、`Vary`。其中 `Set-Cookie` 的值（会话标识）和 `Date` 因运行而变；`Content-Length` 由响应体字节决定，响应体已逐字节比较。

## F.5 允许豁免的字段（封闭清单；回应 Q-003）

**豁免项只有 EX-1、EX-2 两个。** 原 BR-7 列出的“随机数、验证码与 token、会话标识”在本版**不再作为豁免类别**：这些值不进入 HTML 响应体（会话标识在 `Set-Cookie` 头，已不参与比较；验证码是单独的图片请求，不在 188 + 2 + 2 + 32 + 120 + 12 个样本之内；随机排序按 F.6 的数据构造规则消除，不靠豁免）。

| 编号 | 豁免字段 | 为什么会不同 | 识别与替换规则（NORM-3 执行） | Owner |
| --- | --- | --- | --- | --- |
| EX-1 | 当前日期：模板变量 `$maccms.date`（`php/application/common/controller/All.php:65`，`date('Y-m-d')`，时区按 PHPB-4） | 取运行当天的日期，样本在不同日期运行结果不同 | 把响应体中**字面等于对照运行当天（时区 PRC）日期的 `YYYY-MM-DD` 串**整体替换为 `⟦EX-1⟧`。前置：F.6 的 DATA-1（合成数据任何日期都不取运行当天），所以替换只会命中由当前时钟产生的输出。一次对照运行从开始到结束跨过 00:00（PRC）则整批作废重跑 | 发起人（确认）、测试人员（执行） |
| EX-2 | 服务器环境：站点自身的访问源（协议 + 主机 + 端口），出现在 `$maccms.http_url`（`All.php:79`，由 `SERVER_NAME`、`SERVER_PORT`、`REQUEST_URI` 拼成）和由它派生的绝对地址里 | PHP 对照服务与 Java 服务监听在不同的端口 | PRE-2 登记两侧各自的访问源字面串 `ORIGIN_PHP`、`ORIGIN_JAVA`（形如 `http://127.0.0.1:18180`）；两侧都把各自访问源字面串（最长优先）整体替换为 `⟦EX-2⟧`，其后的路径和查询串**不替换**，仍须逐字节一致 | 发起人（确认）、测试人员（执行） |

**封闭清单规则（唯一的变更路径）**：
1. 清单之外的差异一律判失败，不得因“看起来无害”放行，也不得从分母里删样本（BR-10）。
2. PRE-5 扫描 168 个 `.html` 时，必须把所有会产生“当前时间或日期（调用 `date(`、`time(`、`strtotime(`、`mac_day(` 的位置）、随机值（`by="rnd"`，调用 `rand(`、`mt_rand(`、`uniqid(` 的位置）、会话或 token（调用 `session_id(`、`token(` 的位置）”输出的位置逐处登记，并标明它属于 EX-1、EX-2、F.6 的数据构造规则，还是需要新增 EX-。**PRE-5 完成时，这份登记里不得有“归属未定”的行**（见 PRE-5 完成判定）。
3. 需要新增或修改 EX- 时：修订本文（版本 +1），新增 EX- 行写明识别规则；由发起人在 Issue 里逐字回复确认该版本后才生效；生效前涉及的样本照实判失败。
4. Owner：EX- 的确认人是发起人 huaqipro；执行与报告是测试人员；规则文字的维护是产品经理（经修订）。

## F.6 合成数据的构造规则

| 编号 | 规则 | 目的 |
| --- | --- | --- |
| DATA-1 | 合成库里所有日期、时间戳字段的值都不取对照运行当天（PRC）的日期；PRE-2 的数据脚本在生成前检查并拒绝 | 使 EX-1 的字面替换只命中时钟输出 |
| DATA-2 | PRE-5 登记的每一处 `by="rnd"`（`Vod.php:503` 一带的随机排序；PRE-5 发现其他随机来源的，按 F.5 规则 2 登记并修订本文）所在的标签，在该样本页面上的匹配行数 ≤ 1（0 或 1 行）；数据脚本为这类样本单独构造数据 | 随机排序在匹配行数 ≤ 1 时结果确定，两侧输出相同，不需要豁免随机数 |
| DATA-3 | 无法按 DATA-2 构造的（匹配行数必须 ≥ 2 才能得到有意义的样本），修订本文（版本 +1）处理，不得改用“不比较该块”的做法 | 防止用豁免绕过比较 |
