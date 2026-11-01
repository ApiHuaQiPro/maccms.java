# 技术设计 — APPK-2 PHP 剩余功能迁移 Java：PHP 模板兼容层与完整技术设计（含 OP-15）

| 字段 | 值 |
| --- | --- |
| 创建者 | @架构师-海外 |
| 创建时间 | 2026-10-31 19:15（UTC+8）；v3 修订 2026-11-01 08:30（UTC+8）；v4 修订 2026-11-01 08:50（UTC+8）；v5 修订 2026-11-01 09:20（UTC+8）；v6 修订 2026-11-01 10:40（UTC+8）；v7 修订 2026-11-01 11:20（UTC+8）；v8 修订 2026-11-01 12:10（UTC+8）；v9 修订 2026-11-01 18:10（UTC+8）；v10 修订 2026-11-01 18:50（UTC+8） |
| 版本 | v10 |
| 状态 | 评审中 |
| Issue | APPK-2 |
| 上游 PRD | `docs/prd/APPK-2.md` 及附表 `docs/prd/APPK-2/`（A～F）@ `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`（v9）；已经 G0 范围确认 |
| 上游技术设计 | 本文件 v2 @ `ebd7aa60ae968229997221d3086b45ddb4fa67e1`（OP-15，独立复核与架构复审 PASS，OP-15 已关闭）；v3 在其上续写，v2 的 DEC-1～6、PATH-1～4、ENV-1～8、C-1～C-16、V-1～V-9 内容不变 |
| 派工依据 | Issue APPK-2 评论 `01a1946f-ec1e-74b8-85d9-42e88f3fe85f`（S0.5 核验 PASS 后派 S1）；deploy 分支 `release/APPK-2-php-template-migration` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969` |
| 已关闭决策（v8） | OP-T4=a、OP-T5=a、OP-T6=b：Issue APPK-2 发起人确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`（调度核验评论 `01a194e9-f7f0-7bf0-a6cc-09af877c86bd`）。本文件以该评论为三项的唯一决策源，实现、验收、门禁与回滚只写所选答案；将来改选须先由发起人在 Issue 另行确认，再出设计修订 |
| v9 触发 | Issue APPK-2 评论 `01a19682-a499-7066-8e43-a23260dd1d44`（@后端开发-海外 第 1 批回传：契约 OUT-1-5“用已校验地址建连”在 v8 内无法实现，按职责退回架构）。契约 `docs/api/APPK-2/openapi.yaml` v1.3.0 @ `bc612e933aa67abab319b90534c5150c10a51452`（`x-outbound-policy` OUT-1-5）；出站代码现状核对于 feature 分支 `feature/APPK-2-be-php-template-migration` @ `6f78400102ed8a1e26a28b88df90a96391a7ac1e`（`integration/http` 相对基线 `a57e9cb` 只改了 `OutboundPolicy.java` 与其测试） |
| 已定方向（v9 DEC-14） | 出站客户端内部引擎换 Apache HttpClient 5（§4.14 方案 B）：Issue APPK-2 评论 `01a196b5-a035-71df-bbe3-077cd09512a6`（确认人代理 Mika 代人类转达：“同意 Apache HttpClient 5 方案，先评审后实现”，回复调度的方向请求 `01a1968b-baed-7015-a20c-14e0a1d13835`）；Mika 代为转达的依据是发起人评论 `01a1958e-5c60-7d72-9328-d7a5146e1d99`（“以后允许Mika 代我转达”）；调度核验评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`。该授权只定方向，不是 G1 或实现放行：S3-21e 仍须 v8→v10 增量经架构评审通过、调度复核放行 G1 后才派单；将来改选须先由发起人在 Issue 另行确认，再出设计修订 |
| UI 设计 | `docs/design/APPK-2/ui-design.md` @ `0910f7b5e12e779ad2b66f70823b16b601533cd2`（设计评审 PASS）；v6 未改变与其相关的页面路径、缺页处理与 JTE 下线边界；页面呈现以模板包原样输出为准（BR-11），本设计只定实现与验证；两者若有冲突由调度裁决 |
| 涉及仓库 | `ApiHuaQiPro/maccms.java`（单仓） |
| 基线提交 | `ApiHuaQiPro/maccms.java` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969`（deploy 分支 `release/APPK-2-php-template-migration` 当前头） |
| 本版范围 | v3：FR-1～FR-13 的完整技术设计——模块边界与 PHP 模板兼容层（§4.6、§6.3～§6.7）、页面接入与 jte 下线（§6.8～§6.9）、`/template/**` 静态资源（§6.10，关闭 OP-T1）、后台模板管理与静态生成（§6.11～§6.12）、数据库工具四项、安装向导建表、在线功能（§6.13～§6.15）、旧控制器对齐口径（§6.16）、六项安全修复（§6.17）、缓存与 V9-001（§6.18，关闭 OP-T2）、数据表字段（§6.19）、对照数据恢复与会话时区（§6.20，关闭 OP-T3）、实现顺序与子任务（§7.4）。v2 的 OP-15 部分（模板包目录、配置取值、路径量、对照环境）保持不变。v6：数据库工具的失败、并发与恢复契约 DBT-5（§4.12、§6.13）；`SiteHtmlRenderer` 全部调用方与 CSP/HTML `Content-Type` 的新共享边界（§4.11、§6.8、C-70～C-72）；SEC-8 按 PHP 源码更正为无条件行为，OP-T5 只剩 SEC-7。v7：前台 CSP 收敛到单点 C-75，删除与否列为待决 OP-T6，过渡期沿用现有严格策略、新增 S3-14h 门禁（§4.11、§6.8）；数据库工具互斥改为跨进程 OS 文件锁、补运行记录状态机与专用恢复动作（DBT-5、C-73）；DBT-2 改回 PHP 整段执行语义（§4.13、DBT-2）；DBT-3 还原表集合补上只有 `DROP/CREATE` 的空表。v8：按发起人确认收敛 OP-T4=a、OP-T5=a、OP-T6=b 为唯一实现（删除未选列；C-75 自 S3-14a 起即为三项策略，撤销 v7 的过渡值与 S3-14h，§4.11 方案 E）；删备份纳入 `dbtool.lock` 并在锁内保护绑定备份、预检移到取锁之后（DBT-4、DBT-5 ②④）；进程中断后按操作类型与阶段的唯一转移、页面按钮与日志（DBT-5 ⑩），V-31 ⑪～⑲。v9：OUT-1-5 地址固定的实现方式（§4.14 DEC-14、§6.15 NET-7、C-76～C-80、§7.4 S3-21e、V-33、RISK-T23）：出站客户端内部引擎改为 Apache HttpClient 5（版本由 Spring Boot BOM 管理），每一跳用只返回已校验地址的解析器建连；API 契约、对外接口与其他章节不变。v10：只改设计一致性与决策来源（REV-NET-D01）——§12 依赖结论改为准确的依赖清单与例外口径，元数据、§4.14 登记 DEC-14 方向授权来源；选型、契约、改动项与实现均不变 |

## 1. 现状与目标

### 1.1 现状（均在基线提交核实）

**PHP（`php/`，`2023.1000.3051`，只读）怎么定位模板**

| 事实 | 出处 |
| --- | --- |
| 配置基线值：`template_dir='default'`、`html_dir='html'`、`mob_status='0'`、`mob_template_dir='default'`、`mob_html_dir='html'`、`ads_dir='ads'`、`mob_ads_dir='ads'`、`install_dir='/'` | `php/application/extra/maccms.php:31,34-38,42-43` |
| 每个请求定义四个路径量（`$TMP_*` 为选择后的模板、html、广告目录）：① `MAC_ROOT_TEMPLATE = ROOT_PATH.'template/'.$TMP_TEMPLATEDIR.'/'.$TMP_HTMLDIR.'/'`——**模板读取根**（文件系统），ThinkPHP `template.view_path` 同值；② `MAC_PATH_TEMPLATE = install_dir.'template/'.$TMP_TEMPLATEDIR.'/'`——模板包的 URL 前缀，**不含** `html_dir`；③ `MAC_PATH_TPL = MAC_PATH_TEMPLATE.$TMP_HTMLDIR.'/'`——模板读取根的 URL 形式；④ `MAC_PATH_ADS = MAC_PATH_TEMPLATE.$TMP_ADSDIR.'/'` | `php/application/common/behavior/Init.php:33-35,48-62,71` |
| 模板变量：`{$maccms.path_tpl}` = `$GLOBALS['MAC_PATH_TEMPLATE']`（②，**不是** ③ `MAC_PATH_TPL`），`{$maccms.path_ads}` = `$GLOBALS['MAC_PATH_ADS']`（④） | `php/application/common/controller/All.php:58-63`（`label_maccms`） |
| ③ `MAC_PATH_TPL` 在 `php/` 里唯一的读取方：“模板文件不存在”错误页把消息里的 `MAC_PATH_TPL` 删掉 | `php/thinkphp/tpl/think_exception.tpl:363-366`（`git grep -n "MAC_PATH_TPL" a57e9cb -- php`） |
| 官方模板按 ② 引用静态资源：`{$maccms.path_tpl}css/style.css`、`{$maccms.path_tpl}js/…`，包内 `css/`、`js/`、`images/` 与 `html/` 平级 | 官方提交内 `template/default_pc/html/public/include.html:2-8`、`template/default_wap/html/public/include.html:2`、`template/default_wap/html/index/index.html:9-10`（本次用 `gh api` 读取） |
| 手机切换：UA 命中手机正则、没有命中站群，并且 `mob_status==2`，或 `mob_status==1` 且 `Host == site_wapurl` 时，改用 `mob_template_dir`/`mob_html_dir`/`mob_ads_dir` | `Init.php:14-19,37-43` |
| 站群 `extra/domain.php` 命中时并入站点项且不再切手机模板；基线 `domain.php` 是空数组 | `Init.php:21-31`；`php/application/extra/domain.php` |
| 后台保存系统配置时，`ads_dir`/`mob_ads_dir` 被改写为 `template/<template_dir>/info.ini`、`template/<mob_template_dir>/info.ini` 的 `adsdir`（没有该键时为 `ads`） | `php/application/admin/controller/System.php:73-87` |
| 后台模板管理：浏览根是 `./template`（`.@template`）；`current=1` 列 `./template/<template_dir>/<html_dir>/*`，`label=1` 列其下 `label/*`，`ads=1` 列其下 `ads/*`；广告页 `Template::ads` 列 `./template/<template_dir>/<ads_dir>/*.js`，目录不存在时创建。**只用桌面的 `template_dir`，不看 `mob_*`** | `php/application/admin/controller/Template.php:12-57,108-116` |
| 系统配置页的模板下拉 = `glob('./template/*', GLOB_ONLYDIR)` | `System.php:134-138` |
| 静态生成：`ac2=wap` 时改用 `mob_template_dir`/`mob_html_dir`/`mob_ads_dir` 并按同一公式重设 ①～④（`Make.php:16-23`），之后 `label_maccms` 取值（`Make.php:173`），否则用请求初始化时的桌面目录；输出文件相对站点根 `./`（如 `index.html`、`wap_index.html`、`map/index`、`rss/*`，后缀补全见 `reset_html_filename`） | `php/application/admin/controller/Make.php:16-25,28-41,169-175`；`php/application/common.php:2802-2824` |
| 仓库 `php/template/` 只有一个 0 字节的 `default/html/index.html` | `git ls-tree -r a57e9cb php/template` |
| `php/vendor/` 不在 git 里，要从仓库外的生产备份恢复；`install.lock` 不入库，需本地生成 | `MULTICA.md`「已知问题」；`AGENTS.md:60-80` |

**官方模板包（`magicblack/maccms10` @ `4c418c2703692148a937ad92893ab8a494bff742`，PRD 附表 E）**

| 事实 | 出处 |
| --- | --- |
| `template/default_pc/`（147 文件，含 `ads/aaa.js`）、`template/default_wap/`（127 文件，**没有 `ads/`**） | 附表 E.2 |
| 两个包的 `info.ini` 都写 `adsdir = ads`（`default_wap` 写了但没有该目录） | 官方提交内 `template/default_pc/info.ini`、`template/default_wap/info.ini`（本次用 `gh api` 读取）；附表 E.4 EF-6 |
| 目录树 SHA-1：`template/` = `9398a1ed539cc754b7513c76e12134b3b5b49a53`，`default_pc` = `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7`，`default_wap` = `a211f900e060c1d405f2c9d75523e02abac45b80` | 附表 E.1 |
| 许可：Apache License 2.0（官方提交根目录 `LICENSE`） | 官方提交内 `LICENSE` |

**Java（仓库根，基线提交）怎么定位站点根与模板**

| 事实 | 出处 |
| --- | --- |
| 旧配置目录 = `maccms.legacy-config.dir`（环境变量 `MACCMS_LEGACY_CONFIG_DIR`，指向旧站 `application/`）；站点根 = 它的上一级 | `src/main/resources/application.yml`（`maccms.legacy-config.dir`）；`src/main/java/com/maccms/backend/install/config/InstallConfiguration.java:36-38` |
| 站点根内文件访问统一经 `LegacySiteRoot`（只收相对路径、解析符号链接后仍须在根内、原子写）和 `LegacySiteFiles`（可读写区域 `static/`、`template/`、`addons/`、`application/data/backup/`） | `legacyconfig/infrastructure/LegacySiteRoot.java`；`legacyconfig/application/LegacySiteFiles.java:54-55` |
| 手机/站群选择按 `Init.php` 实现并由 PHP 黄金锁定。`Result` 的三个路径字段与 PHP 常量一一对应：`pathTemplate` = ②、`pathTpl` = ③ `MAC_PATH_TPL`（`pathTemplate + htmlDir + '/'`）、`pathAds` = ④；黄金脚本里键名 `path_tpl` 记录的也是 ③ `MAC_PATH_TPL`，不是模板变量 `{$maccms.path_tpl}` | `site/application/SiteTemplateSelection.java:41-47,99-101`；`src/test/php/template_select_golden_child.php:50-53`；`SiteTemplateSelectionGoldenTest.java:61-65` |
| **与 PHP 不一致**：类注释称 `pathTpl` 就是 `{$maccms.path_tpl}`（实际是 ③）；`SiteVariants.resolve` 把 `r.pathTpl()`（③）放进 `SiteVariant.pathTpl`，而 `SiteVariant` 注释定义该字段为 `{$maccms.path_tpl}`（应为 ②）。结果：桌面得到 `/template/<模板目录>/html/`，PHP 是 `/template/<模板目录>/`。`SiteVariantsTest` 按错值断言。基线 `src/main` 里没有代码读取 `SiteVariant.pathTpl()`/`pathAds()`，所以当前页面没有可见差异，但 FR-1 模板解析器接入后会直接输出错误 URL。修正见 C-12～C-16 | `SiteTemplateSelection.java:23`；`site/web/SiteVariants.java:95`；`site/web/view/SiteVariant.java:16-19`；`SiteVariantsTest.java:75,82`；`git grep -n "pathTpl\|pathAds" a57e9cb -- src/main` |
| 后台系统配置的 `ads_dir`/`mob_ads_dir` 改写、模板下拉按 `System.php` 同样的规则实现（只涉及目录名，不涉及模板 URL 变量） | `admin/application/SystemConfigService.java:91-99,217-227,297-299` |
| 后台模板管理与广告页按 `Template.php` 同样的配置键拼目录（不涉及模板 URL 变量；两侧一致性以 V-7 为准）（`./template/<template_dir>/<ads_dir>`，不存在时建目录）；编辑扩展名当前是 B15 改的 `jte/html/js/xml` | `admin/web/AdminTemplateActions.java:36-44,168-200` |
| 静态页经 `StaticFiles` → `LegacySiteFiles.replaceStaticPage` 写到站点根相对路径，另把每个路径追加到登记文件 `application/data/backup/static-pages/generated.txt`（B16 回滚用；PHP 没有这个文件）；首页路径 `index.html`/`wap_index.html` | `staticgen/application/StaticFiles.java:23-60`；`staticgen/application/StaticPaths.java:95-97` |
| 现有 jte 前台的外部模板目录（D4 在线编辑）也读 `template/<template_dir>/<html_dir>`，目录名限 `[A-Za-z0-9_-]{1,64}` | `sitetemplate/application/SiteTemplateService.java:54,131-147` |
| Java 不从站点根提供文件：`/static/**` 来自 jar（构建时打包 `php/static`），`/upload/**` 由部署层 nginx 提供 | `pom.xml:151-172`；`config/maccms-backend.env.example`（`MACCMS_MEMBER_UPLOAD_DIR` 注释） |
| 本机 git `core.autocrlf=true`；`.gitattributes` 没有覆盖模板包的规则 | `git config core.autocrlf`；`.gitattributes` |

### 1.2 目标

关闭 OP-15：把下列五件事定成唯一取值，使 FR-1、FR-6、FR-7、FR-8、AC-1、AC-5、AC-7、AC-8、AC-10、PRE-2、附表 D 的 D-01/D-20、附表 F 的 PHPB-9 都有可执行的目录与配置依据：

1. 模板包在仓库里放哪、部署时放哪（DEC-1、DEC-2）；
2. `template_dir`、`html_dir`、`mob_template_dir`、`mob_html_dir`、`ads_dir`（及随之的 `mob_ads_dir`、对照用的 `mob_status`）取什么值（DEC-3）；
3. PHP 对照环境怎么配（DEC-4，含 Java 侧对照配置）；
4. 后台模板管理读写哪个目录（DEC-5）；
5. 静态生成读哪个模板目录、写哪个输出目录（DEC-6）。

### 1.3 完整设计涉及的现状（v3 新增，均在基线提交核实）

**PHP 模板引擎（`php/thinkphp/` 在 git 里，167 个文件）**

| 事实 | 出处 |
| --- | --- |
| 模板设置：`type=Think`、`view_suffix=html`、标签起止符 `{` `}`、预加载 `app\common\taglib\Maccms`；其余取引擎默认值：`tpl_cache=true`、`strip_space=false`、`tpl_var_identify='array'`、`tpl_deny_php=false`、`taglib_build_in='cx'`、`layout_on=false`；`view_replace_str=[]`；`view_path` 每请求由 `Init.php:71` 设为 PATH-1 | `php/application/config.php:124-146`；`php/thinkphp/library/think/Template.php:27-54`；`view/driver/Think.php:26-39` |
| 编译顺序 `literal → extend → layout → include → literal → php → taglib(Maccms) → taglib(Cx) → tag → literal 还原`；编译后合并相邻代码块（`\?>\s*<\?php\s(?!echo\b)`）、做 `__ROOT__` 等 5 个基础替换、加头代码块；编译产物存 `runtime/temp/<md5>.php`，按 include 文件修改时间失效 | `Template.php:160-197,257-289,313-351,360-411`；`View.php:44-51,161-162` |
| `{include}` 是文本替换（`file` 可逗号列多个，`[k]` 参数替换，`$` 开头取数据）；普通标签正则只认 `a-w` 开头的变量名；`{$a.b}` → `$a['b']`；过滤器 `|default=`、`|fn=###`；5.0 不自动转义 | `Template.php:466-494,720-786,831-942,1091` |
| Cx 内置标签：`volist`、`foreach`、`for`、`if/elseif/else`、`switch/case/default`、`compare` 系列、`range` 系列、`empty/notempty`、`present`、`defined`、`load`、`assign/define`、`url`、`function`、`php`；条件里的 `eq/neq/gt/egt/lt/elt/heq/nheq` 改写成运算符 | `php/thinkphp/library/think/template/taglib/Cx.php:27-657`；`template/TagLib.php:68,87-322` |
| Maccms 标签库 19 个标签，数据标签生成 `$__TAG__ = '<json>';$__LIST__ = model("Vod")->listCacheData($__TAG__);` 再接 `{volist}`；`paging=yes` 另生成 `$__PAGING__ = mac_page_param(...)`；`cachetime` 只在 `app.cache_core==1` 时写缓存 | `php/application/common/taglib/Maccms.php:8-670`；`common/model/Vod.php:108,552-561`；`common.php:517` |
| 页面选模板：`mac_tpl_fetch(模型, 字段值, 默认名)`；`label_fetch` → `fetch` → 可选 `mac_compress_html`（`app.compress`，基线 `'0'`）→ 页面缓存（`app.cache_page`，基线 `'0'`） | `common.php:1617`；`common/controller/All.php:24-56`；`extra/maccms.php:55-60` |
| “模板文件不存在”：`Think.php:81-83`/`Template.php:1074` 抛 `TemplateNotFoundException`，`app_debug=false` 时用 `think_exception.tpl` 非调试分支输出，HTTP 500，消息经语言包译成“模板文件不存在”，并删去 `MAC_PATH_TPL` | `exception/Handle.php:131-233`；`thinkphp/lang/zh-cn.php:31`；`tpl/think_exception.tpl:362-370` |
| 页面变量：`label_maccms()` 给 `$maccms`（`site` 节全部键 + `path`、`path_tpl`、`path_ads`、`date`、`http_url`、`seo`、`*_extend_*` 等）；另有 `user`、`comment`、`param`、`obj`、`popedom`、`__PAGING__`、`player_data`/`player_js`、`url`/`wait`/`msg` 等 | `All.php:58-564`；`index/controller/Base.php:15,38-87` |
| 语言：`lang()` → `Lang::get`，键转小写、缺键原样返回；加载 `thinkphp/lang/zh-cn.php`、`application/lang/<app.lang>.php`、`application/index/lang/<app.lang>.php` | `thinkphp/helper.php:89`；`Lang.php:96-184`；`App.php:98-107,285` |

**Java 前台（`site` 模块）**

| 事实 | 出处 |
| --- | --- |
| 13 个前台页面控制器都在 `site/web/`，各自解析旧 URL（`LegacyUrlParser`）后按 `SiteRoute` 分派；页面统一经 `SiteHtmlRenderer` 调 jte 生成的 `Templates` 接口，视图模型是 `site/web/view/` 下的 record，不是 PHP 那种整行数组 | `SiteContentController.java:82`、`SiteSearchController.java:62`、`SiteVodShowController.java:53`、`SiteLibraryController.java:130,140`、`SiteModuleListController.java:73`、`SitePlaybackController.java:107,123`、`SiteMiscController.java:48`、`SiteAjaxController.java:106`、`SiteInteractionController.java:106`、`SiteUserController.java:168`（另 `SiteMemberFinance`、`SiteMemberBoards`）、`SitePaymentController.java:65`、`SiteVerifyController.java:55,70`、`SitePlaceholderController.java:24` |
| jte：`src/main/jte/site/**` 76 个文件（`pages` 41、`ajax` 9、`member` 8、`fragments` 6、`interaction` 5、`player` 4、`components` 2、`layout` 1）；`jte-maven-plugin` 预编译到 `com.maccms.backend.site.web.jte`；同一批源文件另拷到 `classpath:/site-jte/` 供 D4 在线编辑；后台 `src/main/jte-admin`（118 个）与安装 `src/main/jte-install`（5 个）也用 jte | `pom.xml`（`jte-maven-plugin` 三个 execution 与资源拷贝段）；`site/config/SiteConfiguration.java:355-365` |
| D4 在线编辑：`sitetemplate` 模块（`SiteTemplateService`、`JteSecurityFilter`、`TemplateWizard`、`JteOnlineCompiler`、`JteClassPath`、`BuiltinTemplateSources`、`OnlineHtmlPolicy`），只在后台启用时装配；`SiteConfiguration.OnlineTemplates` 每次渲染取当前引擎 | `sitetemplate/**`；`admin/config/AdminConfiguration.java:65,176-178`；`SiteConfiguration.java:365` |
| 错误页：`SiteExceptionHandler`（站点关闭 503、找不到 404、其他 500，均渲染 `sitePagesError`）；站点关闭由 `SiteStatusInterceptor` 抛出；前台未匹配的地址落到 `common/web/GlobalExceptionHandler`，返回 JSON 404“接口不存在”。PHP 对应：站点关闭 `public/close`、找不到 `public/<page_404>` 带 404、空方法 `Base._empty` | `site/web/SiteExceptionHandler.java`；`SiteStatusInterceptor`；`common/web/GlobalExceptionHandler.java:34`；`index/controller/Base.php:77`；`All.php:98-116` |
| `SiteHtmlRenderer` 的全部引用（v6 补全，`git grep -n 'SiteHtmlRenderer' a57e9cb -- src/main src/test`）：①构造注入渲染器的 18 个类：13 个页面控制器（含 `SiteMemberFinance`、`SiteMemberBoards`）、`SiteExceptionHandler`、`SiteVerifyGate`、`SitePlaceholderController`，以及 v5 漏列的 **`SearchThrottle`**（搜索频率限制，过快时 `renderer.error(429, tooFrequent, Retry-After)`，由 `SiteConfiguration.siteSearchThrottle` 装配，`SiteSearchController`、`SiteModuleListController` 使用）、**`SiteCounterActions`**（`hits/digg/score/referer/verify_check`，非 ajax 的 POST 用 `renderer.memberPlain` 渲染 jte 提示页 `sitePagesUserJump`，D-B11-10；由 `SiteAjaxController:88` 构造）；②只引用静态常量的：**`PlaybackPages:499`**（播放器内页提示用 `CONTENT_SECURITY_POLICY`）、**`SiteMemberFinance:525`** 与 `SitePaymentController:104`（空响应、支付页用 `HTML_UTF8`）；③装配：`SiteConfiguration:37,338,355-361`；④测试：**`SiteTestRoutes`**（测试专用路由，构造注入渲染器，`/boom-large` 用 `renderer.ok` 模拟渲染中途失败）、`SiteConfigurationTest:52,62`（断言有/无该 Bean）、`PlaybackSwitchCases:101`、`SiteAjaxFragmentsMvcTest:84`、`SiteFinanceContentMvcTest:140`、`SitePlaybackPagesMvcTest:73`、`SiteVodListPagesMvcTest:199`（断言响应头等于 `CONTENT_SECURITY_POLICY`）。另有三处各自私有的同值常量 `HTML_UTF8`：`SiteRequests:31`（包内可见，`SiteRequests.json` 等使用）、`SiteUserController:95`、`SiteVerifyController:42` | 同左各文件行号 |
| `SiteHtmlRenderer` 的响应头：所有页面 `Content-Type: text/html;charset=UTF-8`、`Content-Length`、`Cache-Control`（`no-cache` 或 `no-store`）、`X-Content-Type-Options: nosniff`、`Content-Security-Policy`（默认 `script-src 'none'`；会员页按响应 nonce 放行同源 `member.js`，`PlayerFramePolicy` 给播放器内页单独策略）、按 `SiteVariants.dependsOnUserAgent` 加 `Vary: User-Agent`、部分页面 `X-Robots-Tag: noindex`、503 带 `Retry-After`；PHP 前台不发 `Content-Security-Policy`（`git grep -n 'Content-Security-Policy' a57e9cb -- php` 无结果），PHP 内置视图 `public/jump.html:23` 和 `/static/js/player.js`（`document.write` 写出 `static/player/<来源>.js`，`PlaybackPages:76` 注释）都依赖脚本 | `SiteHtmlRenderer.java:27-31,148-166`；`site/application/PlayerFramePolicy.java` |
| 观看密码：Java 用签名 cookie（`PasswordTickets`）+ 频率限制（`PasswordAttempts`），旧 `ajax/pwd` 返回 404；PHP 走 `ajax/pwd` 写 session（`Ajax.php:400-456`，`All.php:547-549`），并有按会话的频率限制：`mac_get_time_span("last_pwd") < 5` 时返回 `{"code":1003,"msg":lang('index/pwd_frequently')}`（`Ajax.php:416-418`；`common.php:306-316` 每次调用都把 `last_pwd` 改为当前时间）。Java 的 `PasswordAttempts` 是 B05 用来“代替”这条限制的（按内容 10 次/60 秒、全站 30 次/秒，超限 429，D-B05-3） | `site/application/PasswordTickets.java`、`PasswordAttempts.java` |
| 取数：视频列表 `ContentReadService.listVideos` + `VideoListQuery`/`VideoFilter`/`VideoLegacyConditions`（B05，已按 `Vod::listCacheData` 的条件实现）；旧标签属性换算 `LegacyTagAttributes`、`LegacyRange` 只在 `VideoTagGoldenMySqlIT` 使用；整行数据 `LegacyRowReadService`（ajax/data、Provide 用）；其他类型 `ArticleReadService` 等与 `LibrarySearchService`；评论留言 `CommentService.comments/gbooks` | `content/application/*.java`；`comment/application/CommentService.java:276,301` |
| URL：`site/application/LegacyUrlBuilder`（`mac_url`/`url()` 等价）、`LegacyUrlStyle`、`LegacyUrlParser`；图片 `ImageUrlNormalizer`（`mac_url_img`）；静态分支 `staticgen/application/StaticPaths` | 同名类 |
| 语言包：`common/lang/LegacyLang` 读 `classpath:/lang/zh-cn.json`、`zh-tw.json`（由 `src/test/php/lang_export.php` 从 PHP 导出并校验 SHA-256） | `common/lang/LegacyLang.java` |
| 进程内缓存：没有 Caffeine、`@Cacheable`；`LegacyConfigService` 按修改时间与大小自动失效；`SiteSettings`、`LegacyUrlBuilder`、`SiteVariants`、`PlayerSettings` 等**启动时读一次配置**，改配置要重启；`RichTextCache` LRU | `legacyconfig/application/LegacyConfigService.java:31-92`；`SiteConfiguration.java:103` |
| 静态资源：只有 Spring Boot 默认的 `classpath:/static/**`（含 `pom.xml` 从 `php/static` 拷入的 `/static/js/player.js` 等）；`/static/js/playerconfig.js` 由 `PlayerConfigController` 生成；`/upload/**`、`/template/**` 没有处理器 | `pom.xml`；`site/web/PlayerConfigController.java:31` |
| 测试：`src/test/java/.../site/**` 97 个文件，约 34 个断言 jte 页面（MockMvc、结构、真实 HTTP、MySQL 黄金）；没有 HTML 黄金文件；另有 `SiteTemplatePolicyTest` 与 `src/test/jte-probe/`（11 个） | 见 §3.2 |

**Java 后台、静态生成、安装、采集**

| 事实 | 出处 |
| --- | --- |
| 模板管理 `admin/web/AdminTemplateActions`（`index`/`ads`/`info`/`del`/`wizard`，L69-339）：扩展名 `jte,html,js,xml`（L43，PHP 为 `html,htm,js,xml`，去掉 `htm` 是 D-B15-14）；非 jte 文件的内容过滤 `SiteTemplateService.LEGACY_FILTER` 与 PHP `Template.php:177` 的正则逐字相同（PHP 用 `mac_reg_replace` 判断，命中即拒绝）；保存带版本号，冲突提示“文件已被他人修改，请刷新页面后重新编辑”；标签向导 `TemplateWizard` 生成 **jte** 片段（D-B15-15），PHP 生成 `{maccms:…}` | `AdminTemplateActions.java:43-45,69-85,214-339`；`SiteTemplateService.java:41,49-53,222,297-326`；`php/application/admin/controller/Template.php:170-195,237` |
| 静态生成 `staticgen`：`MakeService.run` 分派 `index/map/rss/type/topicIndex/topicInfo/info/label`（L81-701）；页面内容经 `site.application.StaticPageSource` → `site/web/InternalPageRenderer`（进程内游客 GET，走真实前台控制器）；失败只打印“生成失败：…”继续；`label` 页（D-B16-5）与 RSS 第 2 页起（D-B16-6）不生成；`*_time_make` 只更新 `vod/art/topic`（`StaticSourceService.markMade`），可写数据源关闭时跳过（D-B16-8） | `staticgen/application/MakeService.java`；`site/web/InternalPageRenderer.java:109,122`；`content/application/StaticSourceService.java:94` |
| 安装向导 `install`（独立启动 `MACCMS_INSTALL_MODE=true`）：第 3 步表单填库与账号，第 4 步测连接（只读会话），账号写入部署配置文件 `MACCMS_DEPLOY_CONFIG`（属性文件，权限 600，不是 `database.php`）；第 5 步**不建表**，只按 `InstallSchema` 指纹只读校验，提示部署方库外导入 `install.sql`；运行期连库只用 `MACCMS_DB_*` | `install/application/InstallWizard.java:110,215,283`；`install/infrastructure/DeployConfigFile.java`；`src/main/resources/install/schema-fingerprint.txt` |
| 数据库工具 `AdminDatabaseActions` + `DatabaseToolService`：已实现列表、列、导出、删备份、批量替换；`import`/`sql`/`optimize`/`repair` 在 L63-65 拒绝，提示 `DatabaseToolService.REFUSED`（D-B15-1）；导出沿用 PHP 的 `backup.lock`（存在则拒绝“检测到有一个备份任务正在执行”，Java 中途出错也删锁）；后台没有 `execute SQL` 页面（`src/main/jte-admin/admin/pages/database/` 只有 `export.jte`、`import.jte`、`rep.jte`）；没有后台操作审计表或审计服务（26 张表中没有日志表），后台写操作只记应用日志 | `admin/web/AdminDatabaseActions.java:52-71`；`admin/application/DatabaseToolService.java:45,81-293,282,313-344` |
| PHP 数据库工具（v6 补）：`sql()` **不切分**，整段 `trim` 后替换 `{pre}`，把副本转小写、去 `/* */` 与 `--` 注释、压缩空白后判断：以 `select` 开头，或含 `outfile`、`dumpfile`、`load_file` 的**一律不执行**，其余整段 `Db::execute`；两种情况都返回“执行成功”（源码注释“安全修复”）。`import()` 先按卷号检查分卷齐全（`count($list) === $last[0]`，否则“文件损坏”），再逐卷逐条 `Db::execute`，某条失败立即返回“还原失败”，之前的语句已生效，没有锁文件，也不检查站点是否在使用；`optimize`/`repair` 把选中表拼成一条语句 | `php/application/admin/controller/Database.php:141-180,182-220,237-268`；`php/application/common/util/Database.php:170-191` |
| 表引擎与事务能力：`install.sql` 的 26 张表中 25 张 `ENGINE=MyISAM`，`mac_vod_search` 为 `InnoDB`；可写数据源自动提交、不配事务管理器，护栏禁止 `@Transactional`/`setAutoCommit(false)`；MyISAM 单行语句原子、多行语句中途出错留下前 N-1 行、连接中断/超时/中止时结果不确定且**不做补偿**，交人工对账；写语句锁整表 | `php/application/install/sql/install.sql`（`grep -o 'ENGINE=[A-Za-z]*'`）；`docs/architecture/12-write-infrastructure.md` 第 3.2 节、第 4.3～4.5 节；`22-b13-admin-content.md` “没有事务”表 |
| 采集：在线抓取资源站未实现（`collect/test` 拒绝）、`collect/timing` 与定时 `collect` 已删（D-B19-15，`AdminPermissions.NOT_PROVIDED`）、远程图片下载未实现（`VodIngest` 保留原地址，`images/sync` 拒绝）、自定义规则只读本地 HTML（`LocalHtmlFiles`）、远程存储没有实现（`AdminUploadService` 只存 `upload/`） | `admin/web/AdminCollectActions.java:47`；`admin/application/AdminPermissions.java:300`；`collect/application/VodIngest.java:302`；`admin/web/AdminContentActions.java:516`；`admin/application/AdminUploadService.java:29-33` |
| 出站护栏：只有 `integration.http.OutboundHttpClient` 能发请求（调用方包检查），`IntegrationPackages.REGISTERED` 登记业务包，`OutboundNetworkGuardTest` 静态扫描，`OutboundPolicy` 有 `LOOPBACK_ONLY` 测试模式 | `integration/http/*`；`integration/application/IntegrationPackages.java`；`src/test/java/.../architecture/OutboundNetworkGuardTest.java` |
| 结构守护：`SchemaGuardTest` 静态扫描 `src/main` 的 DDL（含 `OPTIMIZE/REPAIR TABLE`），登记例外只有 `DatabaseToolService.java`、`DatabaseToolMapper.xml`；运行期 `SchemaGuardMySqlIT` 比较前后指纹；仓库无 `schema.sql`/Flyway | `src/test/java/.../architecture/SchemaGuardTest.java:32-52`；`src/test/java/.../schema/SchemaGuardMySqlIT.java` |
| 写入登记：每个“模块 + 表 + 操作”登记在 `WriteRegistry.REGISTERED`，`WriteGuardTest` + `WriteGuardInterceptor` 检查 | `02-agent-development-rules.md` 第 2 节；`common/write/*` |
| 六项安全修复（D2，OP-9 保留）：①会员登录不再接受库里的 md5 值；②普通登录/注册忽略 `openid`/`col`；③`api.php/timming` 需本机或令牌；④客户端 IP 只信任可信代理的转发头；⑤管理员每请求重读 `mac_admin`；⑥注销、改密、重置时轮换 `user_random` | `09-roadmap.md` 第 5.5 节；`member/application/MemberAccountService.java:145-203,185-193,404,663,723`；`staticgen/web/TimmingAccess.java:55`；`common/clientip/ClientIpResolver.java:100,111`；`admin/application/AdminLoginService.java:108` |

### 1.4 完整设计的目标（v3 新增）

在 OP-15 的目录与配置之上，给 FR-1～FR-13 定模块边界、实现方式、改动清单、验证方式与实现顺序，使：

1. 前台全部页面改由附表 E 的 PHP 模板渲染，输出与 `php/`（PHP 7.3.33）按附表 F 逐字节一致（KPI-1、KPI-5）；jte 前台与 D4 在线编辑下线（OP-3）。
2. 后台模板管理、静态生成改为基于 PHP 模板，22 个用例通过（KPI-6）。
3. 数据库工具四项、安装向导建表、五项在线功能按 OP-5～OP-7 实现，结构差异为 0（KPI-2）。
4. 附表 C 379 行判定完、缺口闭环（KPI-3a、KPI-3b）。
5. 六项安全修复保留（BR-4）；OP-T1～OP-T3、V9-001 有确定做法。

## 2. 范围与非目标

**本版做**：DEC-1～DEC-6 的决定（含 DEC-3 推出的路径量 PATH-1～PATH-4）、对应的文件级改动清单（模板包入库、对照环境装配脚本与配置、`.gitattributes`/`.gitignore`/部署样例的修改、Java `{$maccms.path_tpl}` 映射修正 C-12～C-16）和验证方式。C-12～C-16 只修正路径量的映射、注释与测试，不实现模板解析（FR-1）。

**本版不做**（防范围蔓延）：

- （v2 时）FR-1～FR-13 的实现设计——v3 已续写，见下方“v3 范围”。
- OP-16（缺页面补不补）：由发起人决定，本设计的目录与配置对 a、b 两种答案都成立；答 b 时只需把补充的模板文件放进同一目录（见 RISK-T7）。
- 应用层缓存隔离（V9-001）、数据恢复脚本实现、`php/vendor/` 恢复步骤：属于 PRE-2 与完整设计，记入 OP-T2、OP-T3。
- 修改 `php/` 下任何文件（`php/` 只读，`AGENTS.md:5`）；修改线上配置。
- 产品范围、业务规则、样本分母：不改。附表 B 的 120 个固定模板名、桌面 79 有/41 缺、手机 63 有/57 缺在本设计的目录下保持不变（目录名不改变包内相对路径）。

**v3 范围（完整设计）**：FR-1～FR-13 全部；改动清单 C-17～C-66（v4 增 C-67、C-68；v5 增 C-69；v6 增 C-70～C-74；v7 增 C-75）；验证 V-10～V-29（v4 增 V-30；v6 增 V-31、V-32；v7 改写 V-23、V-31、V-32）；实现顺序 §7.4。

**v3 仍不做**：
- 不新增、不修改任何表结构（BR-1）；不做 App（Q8）；不做 CI、测试环境部署、发布（Q7）；不做外部沙箱联调（Q6），在线功能只用本机模拟验收（OP-5）。
- 不补模板包缺的页面（OP-16 a），缺页按 PHP“模板文件不存在”输出（BR-12）；不改模板文件（BR-11）。
- 不写 API 契约文件（本期没有新增业务 HTTP 接口：`ajax/pwd` 是恢复 PHP 已有接口，`/template/**` 是静态文件）；不写测试用例（由测试人员按 §8 编写）。
- 不预判附表 C 的 379 行结论（FR-12 由测试人员判定）；不为 GAP- 设计实现（每个 GAP- 单独建单）。
- 不做与本 Issue 无关的重构：各模块里重复的 `PhpLoose` 等 PHP 语义工具类保持原状，`phptemplate.runtime` 只服务模板解释器。

## 3. 影响面

| 类别 | 对象 | 影响 | 证据（调用方 / 读取方检索） |
| --- | --- | --- | --- |
| 数据（文件） | 新增 `third_party/maccms-template-v2020.1000.1035/`（274 个模板文件 + `LICENSE` + `SOURCE.md`） | 新增，只读资产；不进 jar | 基线无 `third_party/`；`pom.xml` 资源段只引用 `php/static`，不会把它打包 |
| 配置（仓库） | `.gitattributes` | 加一行，使模板包按字节检出，不做换行转换 | 现有同类规则：`src/test/resources/legacyconfig/cases/*.php -text -whitespace` |
| 配置（仓库） | `.gitignore` | 加 `.compare/`（对照环境装配目录） | — |
| 配置（旧站） | `application/extra/maccms.php` 的 `site.template_dir`、`html_dir`、`mob_template_dir`、`mob_html_dir`、`ads_dir`、`mob_ads_dir`；对照环境另有 `site.mob_status` | **只在对照环境的副本和部署方的站点里取新值**，仓库 `php/application/extra/maccms.php` 不改 | 读取方（`git grep -n "template_dir\|html_dir\|ads_dir" a57e9cb`）：PHP `Init.php`、`Make.php`、`System.php`、`Template.php`、`Domain.php`；Java `SiteTemplateSelection`、`SiteTemplateService`、`SystemConfigService`、`AdminTemplateActions`、`ListConfigService`、`admin/pages/system/config.jte`、`admin/pages/domain/index.jte`、`static/admin-assets/domain-index.js`。这些读取方都按配置值拼路径或原样显示、保存（`ListConfigService`、两个 `domain` 文件只做站群配置的显示与保存），没有写死 `default`，**改配置取值本身不需要改它们的代码**（`SiteTemplateService.root()` 的回退值 `default` 只在读不到配置时用，随 jte 前台下线一起处理，见 §2）。另有一处与取值无关、基线就存在的映射错误：`{$maccms.path_tpl}` 在 Java 取成了 ③，见下一行 |
| 代码（Java） | `SiteVariants.resolve`；`SiteVariant`、`SiteTemplateSelection` 的注释；`SiteVariantsTest`、`SiteTemplateSelectionGoldenTest` | C-12～C-16：`SiteVariant.pathTpl` 改取 `pathTemplate`（②）；`SiteTemplateSelection.Result` 与 `resolve` 逻辑不改；补 DEC-3 取值的单测 | `git grep -n "pathTpl\|pathAds\|pathTemplate" a57e9cb -- src`：`SiteVariant` 的构造只在 `SiteVariants.java:95` 与 `SiteVariant.DEFAULT`，`SiteVariant.pathTpl()` 的读取只在 `SiteVariantsTest.java:75,82`；`Result.pathTpl()` 的读取是 `SiteVariants.java:95` 与 `SiteTemplateSelectionGoldenTest.java:58,64` |
| 配置（部署） | `config/maccms-backend.env.example` | 只加说明：站点根需有 `template/default_pc`、`template/default_wap` | 现有 `MACCMS_LEGACY_CONFIG_DIR` 说明段 |
| 测试工具 | 新增 `tests/compare/APPK-2/`（装配脚本、配置覆盖表、`php.ini`、模板包校验脚本） | 新增；只在本机对照时运行 | `MULTICA.md` §2 测试路径；PRE-2 的路径“设计单指定” |
| 外部依赖 | 官方仓库 `magicblack/maccms10` | **只在一次性入库时**读取；之后验收与对照不连外网 | 附表 E.1；Q6 本期不连外部沙箱 |

**明确不受影响**：`php/**`（只读）；数据库结构与数据（本设计不读写任何表）；`src/main/**` 中 C-12～C-14 以外的 Java 代码（含 `SiteTemplateSelection.resolve` 的计算逻辑）；PHP 黄金脚本与黄金 JSON；`LegacySiteFiles` 的可读写区域（`template/` 已在 `RW_AREAS`）；jar 内容；`MULTICA.md` §0。

### 3.2 完整设计的影响面（v3 新增）

| 类别 | 对象 | 影响 | 证据（调用方 / 读取方检索） |
| --- | --- | --- | --- |
| 模块（新增） | `phptemplate`（engine/runtime/application/infrastructure/config） | 新增；只读模板文件与 12 张 TS-TAG 表 | §6.3 |
| 模块（改） | `site`：13 个前台控制器、`SiteExceptionHandler`、`SiteStatusInterceptor`、`SiteVerifyGate`、`SitePlaceholderController`、`InternalPageRenderer`、`SiteConfiguration`；v6 补：`SearchThrottle`、`SiteCounterActions`、`PlaybackPages`、`SiteRequests`，以及只引用静态常量的 `SiteMemberFinance:525`、`SitePaymentController:104` | 由 jte 改为 PHP 模板；ALIGN-1～7；HTML 响应头统一经新的 `SitePageResponses`（C-70），`Content-Type` 常量只留 `SiteRequests.HTML_UTF8`；前台 CSP 只由 C-75 `SiteSecurityHeaders` 提供，值为 OP-T6=b 的三项 `object-src 'none'; base-uri 'self'; frame-ancestors 'self'`（DEC-11，v8） | `git grep -n "SiteHtmlRenderer" a57e9cb -- src/main src/test`：§1.3 “`SiteHtmlRenderer` 的全部引用”一行逐个列出（main 21 个文件、test 7 个文件），每个文件的处理见 §6.8 末“`SiteHtmlRenderer` 调用方迁移表” |
| 模块（删除） | `sitetemplate`；`src/main/jte/site/**`（76 个）；`SiteHtmlRenderer`；`site/application/PlayerFramePolicy`（v6 补，只为 jte 播放器内页生成 CSP；删除后播放器页与其他前台 HTML 页共用 C-75 的三项策略，v8） | OP-3 下线；DEC-11 | `SiteTemplateService` 读取方：`SiteConfiguration`、`AdminTemplateActions`、`AdminConfiguration:65,176-178`、`AdminPages`、`TemplateWizard` |
| 模块（改） | `admin`：`AdminTemplateActions`、`AdminDatabaseActions`、`DatabaseToolService`（v6 增 `DatabaseRuns` 运行记录与互斥，DBT-5；v7 互斥改为跨进程 OS 文件锁，导出也持同一把锁）、`AdminCollectActions`、`AdminContentActions`（`images/sync`）、`AdminPermissions`、`AdminController.clear` | FR-7、FR-9、FR-11、CACHE-3 | 同名类，§1.3；v7 另改 `legacyconfig.application.LegacySiteFiles`（新增 `tryExclusiveLock`，调用方只有 C-73） |
| 模块（改） | `staticgen`：`MakeService`、`TimmingTasks`；`content`：`StaticSourceService`、`AdminUploadService` | FR-6、FR-11 | 同名类 |
| 模块（改） | `install`：`InstallWizard`、`InstallSchema`、`InstallConnections` | FR-10 | `InstallSchema` 调用方：`InstallWizard`、`InstallWizardMySqlIT` |
| 模块（改/新增） | `collect`：`CollectRunService`、`CjRunService`、`VodIngest`；`integration`：新增 `collect`/`image`/`storage` 子包与 `application` 接口，`IntegrationPackages` | FR-11 | `IntegrationPackages.REGISTERED` 现有 7 个包 |
| 接口（HTTP） | 新增 `GET/HEAD /template/<目录>/{css,js,images,ads}/**`；恢复 `ajax/pwd`（PHP 已有）；前台未匹配路径由 JSON 404 改为 PHP 404 页 | 对外可见 | §6.8、§6.10 |
| 数据 | 新增写入：`mac_actor.actor_time_make`、`mac_role.role_time_make`、`mac_website.website_time_make`（K-12）；`mac_annex`（NET-3）；数据库还原的 `DELETE/INSERT`（DBT-3）；`OPTIMIZE/REPAIR TABLE`（DBT-1）；安装执行 `install.sql`（INS-2） | 都在附表 D 对应行内，先登记 `WriteRegistry` | 附表 D 的 D-14、D-16、D-18、D-19、D-23 |
| 配置 | 新增 `maccms.phptemplate.limits.*`、`maccms.phptemplate.compare.reset-file`；旧配置文件读写不变 | 有默认值 | C-31、C-32 |
| 依赖 | 删除 `gg.jte:jte`（在线编译器）；不新增依赖 | — | `pom.xml` |
| 测试 | 新增 `T/phptemplate/**`、对照脚本；修改护栏测试（`ModuleBoundaryTest`、`SiteGuardrailsTest`、`ReadOnlyGuardrailsTest`、`SchemaGuardTest`、`OutboundNetworkGuardTest`、`RegisteredOutboundClientTest`）；约 34 个 jte 页面测试改写或删除：`SiteContentPagesMvcTest`、`SiteLibraryPagesMvcTest`、`SitePlaybackPagesMvcTest`、`SitePlaybackSwitch{00,01,10,11}MvcTest`、`SiteSearch*MvcTest`（4 个）、`SiteVodListPagesMvcTest`、`SiteModuleListPagesMvcTest`、`SiteMiscPagesMvcTest`、`SiteAjax*MvcTest`（4 个）、`SiteUserMvcTest`、`SiteFinance*MvcTest`（3 个）、`SitePaymentMvcTest`、`SiteOAuthMvcTest`、`SiteVerify*MvcTest`（3 个）、`SiteClosedMvcTest`、v6 补 `SiteTestRoutes`（测试路由，改用 `SitePageResponses`）、`SiteConfigurationTest`（Bean 断言）、`PlaybackSwitchCases`（CSP 断言）、`SiteContentClosedMvcTest`、`SiteVodListClosedMvcTest`、`SiteDisabledMvcTest`、`SitePageMvcTest`、`SiteContentHtmlStructureTest`、`SiteSearchHtmlStructureTest`、`SiteVariantPagesTest`、`SitePagesLogoTest`、`SiteLegacyConfigPagesTest`、`SiteComponentsRenderTest`、`SiteRichTextTemplateTest`、`Site{,Content,Search,User,Verify}RealHttpTest` | RISK-T12 | `src/test/java/com/maccms/backend/site/**` |

**v3 明确不受影响**：数据库结构；`php/**`；后台其他功能（B12～B15 中除模板管理、数据库工具四项、清理缓存一行之外）；`openapi`（Provide）、`Receive`、公众号、支付、OAuth、短信邮件；`/api/v1/**` JSON 接口；会员与财务的业务规则；六项安全修复的实现类。

## 4. 方案对比

### 4.1 模板包目录与配置取值（DEC-2、DEC-3）

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| A 最小改动：保留配置基线 `default`/`html` | 把 `default_pc` 改名放到 `template/default/`，`default_wap` 保持原名，只改 `mob_template_dir=default_wap` | 配置改 1 项；包内 147 个文件路径改前缀 | 包内路径与附表 E.3 不再一一对应（AC-1 逐文件比对要做路径映射）；与 `info.ini`、官方文档的目录名不一致；`template/default` 与仓库 `php/template/default` 同名，易混入那份 0 字节文件 | 是 | 部分 | 不选 |
| B 官方目录名 + 改配置值 | 包按官方原样放 `template/default_pc/`、`template/default_wap/`；`template_dir=default_pc`、`mob_template_dir=default_wap`，其余 `html`/`ads` 不变 | 配置改 2 项（对照环境另改 `mob_status`）；包内路径不变 | 部署方须同时改配置（RISK-T6） | 是：改回配置值即可 | 是：PHP 与 Java 现有读取方都按配置拼路径，改取值不需要改读取方（`{$maccms.path_tpl}` 的映射错误与取值无关，另由 §4.5 处理） | **选** |
| C 两套包都改名为 `default` 下的不同 `html_dir` | `template/default/html_pc/`、`template/default/html_wap/` | 274 个文件全部改路径 | 拆开了包的 `css/js/images` 与 `info.ini`（两个包各有一份，同名冲突） | 是 | 否 | 不选：同名文件冲突，无法原样放置 |

选 B 的理由：模板文件和路径都与附表 E 完全一致（BR-11 原样使用，AC-1 可直接用目录树 SHA 校验）；PHP 与 Java 的现有代码都按配置值拼路径，改取值不用改读取方；回退只需改回配置值。

### 4.2 模板包怎么进入仓库（DEC-1）

| 方案 | 做法 | 风险 | 结论 |
| --- | --- | --- | --- |
| A 每次装配时从 GitHub 下载 | 装配脚本按提交号拉官方包 | 对照与验收依赖外网（Q6 已明确本期验证不连外网）；离线不可复现 | 不选 |
| B 放进 `php/template/` | 与旧站目录一致 | `php/` 是生产备份的只读快照（`AGENTS.md:5`），混入 2020 官方文件会混淆来源；还要和 0 字节的 `default/html/index.html` 共处 | 不选 |
| C 独立的第三方目录，按字节入库 | `third_party/maccms-template-v2020.1000.1035/template/…`，附官方 `LICENSE` 与来源说明；`-text` 防换行转换 | 仓库体积增加约 274 个文件（Apache-2.0 允许再分发，须保留 LICENSE） | **选**：离线可复现；目录树 SHA 可直接证明与官方逐字节一致；Java 单元测试也能读真实模板 |

### 4.3 对照环境的站点根（DEC-4）

| 方案 | 做法 | 风险 | 结论 |
| --- | --- | --- | --- |
| A 两侧共用一个站点根 | PHP 和 Java 指向同一目录 | 静态生成两侧写同一批相对路径（如 `index.html`），互相覆盖，CMP-4 无法比较；后台保存配置会改到对方读的文件 | 不选 |
| B 两侧各一份，从同一份“原始树”复制 | `pristine/` → `php-site/`、`java-site/` | 多占一份磁盘；需要保证复制后 13 个配置文件仍同哈希（脚本校验） | **选**：满足 AC-7“两侧各写各自事先清空的输出目录”和 PHPB-9 |

### 4.4 对照环境的手机切换（`mob_status`）

| 方案 | 做法 | 风险 | 结论 |
| --- | --- | --- | --- |
| A 保留基线 `0` | 不切手机模板 | 手机 UA 也拿桌面模板，AC-10 的 120 个手机页面样本测不到 `default_wap` | 不选 |
| B `1` + `site_wapurl` | 只有 `Host == site_wapurl` 才切 | 两侧端口不同（18180/18280），`Host` 不同，需要两侧写不同的 `site_wapurl`，13 个配置文件就不能同哈希（违反 PHPB-9） | 不选 |
| C `2` | 手机 UA 一律切 `default_wap`，与 `Host` 无关 | 与仓库基线值 `0` 不同（只在对照环境的副本里改，见 RISK-T2） | **选** |

### 4.5 Java 的 `{$maccms.path_tpl}` 映射修正（REV-1）

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| A 最小改动：只改文档，不改代码 | 设计里写明 Java 当前值与 PHP 不同，留给 FR-1 | 0 | FR-1 实现者沿用 `SiteVariant.pathTpl` 时，模板里所有 `{$maccms.path_tpl}css/…` 都多一段 `html/`，css/js/图片全部 404；与“PHP 无漏洞即以 PHP 为准”冲突 | 是 | — | 不选 |
| B 改 `SiteTemplateSelection.Result.pathTpl` 为 ② | `resolve` 第 100 行改为返回 `pathTemplate` | 1 个主类 + 黄金比较 | `Result` 不再有 ③，而“模板文件不存在”错误页需要 ③（`think_exception.tpl:366`）；黄金 JSON 的键 `path_tpl` 记录的是 ③，比较会失败，需改比较或重新生成黄金 | 是 | 否：打破“`Result` 字段与 PHP 常量一一对应” | 不选 |
| C 在映射处修正 | `Result` 保持与 PHP 常量一一对应（②③④ 都保留）；`SiteVariants.resolve` 把 `r.pathTemplate()` 放进 `SiteVariant.pathTpl`；改正两处注释；单测改为 PHP 值 | 3 个主类（1 行代码 + 2 处注释）+ 2 个测试类 | 低：基线没有读取 `SiteVariant.pathTpl` 的产品代码 | 是：`git revert` | 是：与 `All.php:62` 在“取模板变量”处映射的做法相同；黄金不变 | **选** |

### 4.6 PHP 模板兼容层的实现方式（FR-2～FR-4，v3 新增）

前提：BR-3/NOIGN-4 要求输出与 PHP **逐字节**一致，空白、换行都算差异。ThinkPHP 5.0 不是“解析成语法树再输出”，而是一串正则把模板**改写成 PHP 源码**再执行（`php/thinkphp/library/think/Template.php:360-410` 的 `parse`），输出的空白由三条规则决定：①标签被替换成 `<?php … ?>`；②`compiler()` 把相邻的非 echo 代码块之间的空白整段删掉（`Template.php:341` 的 `/\?>\s*<\?php\s(?!echo\b)/s`）；③PHP 语言本身吃掉紧跟 `?>` 的一个换行。`{include}` 是对整段文本的 `str_replace`（`Template.php:466-490`），参数 `[k]` 也是文本替换。

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| A 最小改动：保留 jte，按模板手工改写 | 把 168 个 `.html` 逐个翻成 jte | 168 个 jte 文件 | 违反 OP-3（jte 前台下线）、BR-11（模板原样使用）；W-04 后台改模板后 jte 不会变 | 是 | 是 | 不选：与已确认范围冲突 |
| B 自写 Think 语法的语法树引擎 | 词法 → 语法树 → 解释执行 | 中 | 上面三条空白规则要在语法树上逐条模拟，`include` 的文本替换语义（参数可替换标签内部文字）无法用树表达，逐字节一致难以证明 | 是 | 否 | 不选 |
| C **移植编译流水线 + PHP 子集解释器** | Java 按 `Template.php`/`TagLib.php`/`Cx.php`/`Maccms.php` 原顺序、原正则把模板改写成同样的 PHP 文本（编译产物与 PHP `runtime/temp/*.php` 逐字节可比），再用 Java 写的 **PHP 子集解释器**执行；解释器只支持编译产物和 PRE-5 清单里实际出现的 PHP 语法与函数 | 新模块约 30 个类 | 解释器的 PHP 7.3 值语义（弱类型比较、字符串化、浮点精度）要逐项对齐，用 PHP 黄金向量锁定（RISK-T10） | 是：新模块独立，按页面开关逐批接入 | 是：仓库已有同类做法——用 PHP 黄金锁定 Java 复刻（`SiteTemplateSelectionGoldenTest`、`VarExportWriter`） | **选** |
| D 运行真 PHP（php-cgi 子进程或 Quercus） | Java 把数据交给 PHP 渲染 | 小 | 生产继续依赖 PHP 运行时，与“迁到 Java”目标相悖；Quercus 只到 PHP 5.x 语义；跨进程传数据要序列化整套 `$GLOBALS` | 是 | 否 | 不选 |
| E 转成其他 Java 模板引擎（Pebble/Thymeleaf） | 写转换器 | 中 | 同 B 的空白问题，且值语义是 Java 的 | 是 | 否 | 不选 |

选 C 的理由：空白和输出顺序由“同样的改写 + 同样的 PHP 执行规则”自然得出，不需要逐个模板去凑；编译产物可以和 PHP 的 `runtime/temp` 编译文件直接比对（V-12），差异定位到具体标签；模板里的 `{php}`、`<?php` 代码（附表 E.4 EF-2）进入同一个解释器，PRE-5 清单内的写法就能执行，清单外的写法在解释器里报“不支持”，满足 FR-2 的 500 + 文件名 + 行号。

### 4.7 模块放在哪（v3 新增）

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| A 放进 `site` | 引擎、标签、函数都在 `site` 模块 | 不选：`site` 的护栏禁止读文件、禁止编译模板、只能经视图模型取数（`02-agent-development-rules.md` 第 2 节 `site` 条）；标签库要查 12 张表，`site` 不拥有表 |
| B 放进 `sitetemplate` | 复用 D4 在线编辑模块 | 不选：D4 随 OP-3 作废，`sitetemplate` 的职责（jte 运行期编译）整体下线（C-40） |
| C **新模块 `phptemplate`**：`engine`（编译流水线）、`runtime`（PHP 子集解释器与值语义）、`application`（对外服务 `PhpTemplateRenderer`、函数与标签的 Java 实现、页面变量）、`infrastructure`（标签查询的只读 Mapper）、`config` | **选**：读模板文件经 `LegacySiteFiles`，读配置经 `LegacyConfigService`，与 `staticgen`、`admin` 的现有边界一致；`site` 只经 `phptemplate.application` 的公开服务渲染，不自己碰模板文件 |

### 4.8 `/template/**` 静态资源由谁提供（关闭 OP-T1，v3 新增）

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| A 交给部署层 nginx（同 `/upload/`） | Java 不提供，部署方配置 nginx | 不选：本期不做部署（Q7），本机对照与验收没有 nginx，`java-site` 上的页面资源全部 404，人工核对页面时无法判断样式；PHP 内置服务（PHPB-2）对存在的文件直接返回，两侧不对称 |
| B **Java 从站点根提供，带白名单** | 新增资源处理：只映射 `/template/<目录>/{css,js,images,ads}/**`，扩展名限 `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf`，经 `LegacySiteFiles.readTemplateAsset`（C-44）读文件（路径不出 `template/`、不跟随符号链接）；`html/`、`info.ini`、`.html`、`.php` 一律 404 | **选**：Java 自带即可用；不泄露模板源码（PHP+nginx 默认会把 `.html` 模板当静态文件返回，这一点不照搬，属于安全收紧，不进比较样本，见 SEC-7；OP-T5 已按 a 关闭（v8），这是唯一实现）；生产仍可在 nginx 层直出同一目录，两者 URL 相同（PATH-2、PATH-4） |

### 4.9 应用层缓存在对照时怎么隔离（关闭 OP-T2 / V9-001，v3 新增）

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| A 改配置关闭缓存 | 对照环境把 `app.cache_core`、`cache_page` 写死为 0 | 部分采用：基线 `php/application/extra/maccms.php:56,58` 已是 `cache_core='0'`、`cache_page='0'`，Java 按同一开关实现（CACHE-1），对照环境不需要改值；但模板编译缓存、旧配置缓存不受这两个开关控制 |
| B 每个单元前重启 Java | 彻底 | 不选：356 个样本 × 每次启动约 20 秒，不可行 |
| C **每个单元前做一次“清缓存”，两侧语义相同** | PHP：删除 `php-site/runtime/cache/`、`runtime/temp/`（等同后台“清理缓存”删 `runtime` 的效果）；Java：调用本设计新增的进程内清理钩子（`CacheResetHook`，由后台“清理缓存”入口与对照专用开关共用，见 CACHE-3） | **选**，与 CMP-2 的数据恢复放在同一时点、在其之前执行（ENV-9） |

### 4.10 安装向导怎么取得建表账号（RISK-15，v3 新增）

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| A 向导与运行期共用 `MACCMS_DB_*` | 运行期账号需要 `CREATE/DROP` | 不选：违反 A.3（`it_app` 只有 DML），AC-12 与 AC-15 不能同时满足 |
| B **向导用表单里填的连接（同 PHP 第 3 步），只在本次请求内使用；运行期仍用环境变量 `MACCMS_DB_*`** | 安装模式（`MACCMS_INSTALL_MODE=true`）下，第 3 步表单提交的主机、库名、账号（AC-12 用 `it_install`）只用于建表与初始数据那一次连接，用后关闭，不写日志、不进会话之外的存储；完成后按 PHP 写 `application/database.php`（D-23 的写入项）；正常模式启动的 Java 不读 `database.php`，连库只看 `MACCMS_DB_*`（AC-15 用 `it_app`） | **选**：与 PHP 的交互一致（PHP 也是用户在第 3 步填账号），且两组账号天然分开 |

### 4.11 `SiteHtmlRenderer` 删除后，HTML 响应头与 CSP 放在哪（REV-8，v6 新增）

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 |
| --- | --- | --- | --- | --- | --- |
| A 最小改动：保留 `SiteHtmlRenderer`，只换掉 jte 入口 | 把 `render(Function<Templates,…>)` 改成收 `RenderedPage`，常量和各响应方法原样保留 | 小 | 类名与职责不符（不再渲染）；默认 CSP `script-src 'none'` 会挡住 PHP 模板与内置视图的脚本（`public/jump.html:23` 的倒计时跳转、`player.js` 的 `document.write`），真实浏览器里跳转页不跳、播放器不出；会员 nonce 策略依赖 jte 页面末尾的 nonce，PHP 模板没有 | 好 | 是 |
| B 放宽 CSP 后继续发 | 共享边界里给出一个与官方模板兼容的策略 | 中 | 兼容策略必须放行内联脚本、`document.write`、任意第三方播放器与广告脚本的源（站长在后台配置，不可枚举），等于没有保护；每接一个播放源都可能要改策略 | 好 | 部分 |
| C 新共享边界 `SitePageResponses`，前台页面不发 CSP（v6 选定，v7 撤回；即 OP-T6 a，发起人未选） | 新增包内类 `SitePageResponses`，是 `site.web` 中唯一把 `RenderedPage` 变成 HTML 响应的地方：`Content-Type` 用 `SiteRequests.HTML_UTF8`（全包唯一的 HTML 类型常量）、`Content-Length`、按调用方选 `no-cache`/`no-store`、`X-Content-Type-Options: nosniff`、按 `SiteVariants.dependsOnUserAgent` 加 `Vary: User-Agent`、按调用方加 `X-Robots-Tag: noindex`；**不发 `Content-Security-Policy`**（同 PHP）。`CONTENT_SECURITY_POLICY`、`memberPolicy`、`PlayerFramePolicy` 随 jte 页面删除 | 中 | 前台失去 CSP 这层纵深防护（RISK-T18）；不影响比较（CMP-3 只比状态码、`Content-Type`、`Location`） | 好（按 PG 组提交） | 是：与 `admin.web.AdminResponses`、`install.web.InstallResponses` 同样是“各入口一个响应工具类” |
| D C 的共享边界 + CSP 单点 C-75 + OP-T6 门禁（v7 写法，v8 撤回） | `SitePageResponses` 同方案 C，CSP 只取自 C-75；OP-T6 未决期间取基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文作过渡值，S3-14h 再按 OP-T6 改值 | 中 | 过渡期真实浏览器里模板内联脚本、`player.js`、支付网关表单被拦，须另设“S3-14h 合入前不得发布”门禁 | 好 | 是 |
| **E（v8 选定，DEC-11 修订）C 的共享边界 + C-75 固定为 OP-T6=b 的三项** | `SitePageResponses` 同方案 C；C-75 `SiteSecurityHeaders.frontPolicy()` 返回常量 `object-src 'none'; base-uri 'self'; frame-ancestors 'self'`，所有前台 HTML 页共用，随 S3-14a 落地即为此值、没有过渡值；会员 nonce、播放器内页专用策略、网关 `form-action` 变体都不再生成 | 中（比 D 少一个子任务、少一道发布门禁） | 不限制脚本，XSS 纵深防护比基线弱（RISK-T18，发起人已按 OP-T6=b 接受）；站点不能被第三方页面用 iframe 嵌入 | 好：C-75 随 S3-14a 提交；只改策略时单独提交 C-75 一处（§9） | 是 |

选 E（v8，回应 REV-13）：OP-T6 已由发起人按 b 关闭（确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`），方案 C（删除 Java 现有 CSP，即 OP-T6 a）未被选择；方案 D 的过渡值与 S3-14h 门禁只为“未决”而设，决定后不再需要，v8 撤回：C-75 从 S3-14a 起就是三项策略，浏览器里模板脚本可以运行，不存在“过渡期不得发布”。方案 B 的“兼容策略”（放行内联脚本与任意源）等于没有保护，不采用；所选三项都与脚本无关、官方模板不受影响（插件对象、`<base>` 改写、被第三方页面嵌套），基线策略已有。保留的 `nosniff`、`Cache-Control`、`Vary`、`X-Robots-Tag` 不改变页面渲染，也不参与比较。将来若改选，须先由发起人在 Issue 另行确认、再出设计修订，实现只改 C-75 一处（§9）。

### 4.12 数据库工具“执行 SQL”“还原备份”在非事务表上怎么保证可恢复（REV-7，v6 新增）

前提：26 张表中 25 张 MyISAM，事务回滚无效；可写数据源自动提交，护栏禁止事务（§1.3）；`it_app` 只有 `SELECT, INSERT, UPDATE, DELETE`，不能 `LOCK TABLES`。

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 |
| --- | --- | --- | --- | --- | --- |
| A 最小改动：同 PHP 逐条执行，遇错即停 | 失败只提示“还原失败”/错误信息 | 小 | 管理员不知道停在哪、哪些已生效；还原中途失败会留下“已删未插”的空表；前台并发写入可能让 `INSERT` 主键冲突而中途失败 | 无数据层回退 | 是（PHP 原样） |
| B 改用事务或转 InnoDB | `BEGIN … ROLLBACK` | — | MyISAM 无效；转 InnoDB 违反 BR-1 零结构变更；护栏禁止 | — | 否 |
| **C（选定，DEC-12；v7 修订互斥与状态机）预检 + 逐条状态 + OS 文件锁互斥 + 运行记录状态机 + 还原前自动备份 + 专用恢复动作** | 见 §6.13 DBT-5：发送任何语句前完成整批预检；执行时逐条记录，第一处失败即停、不补偿，按 12 文档第 4.3 节区分“确定未生效”与“结果不确定”；互斥靠跨进程的独占 OS 文件锁 `dbtool.lock`（持锁期间才读写运行记录，进程退出时由操作系统释放）；运行记录 `dbtool-run.json` 只做状态与审计；还原前自动导出将被还原的表并记哈希，失败后只能经专用恢复动作 `recover` 用该次运行绑定的备份恢复 | 中 | 自动备份增加耗时与磁盘；仍无法阻止其他管理员在同一时段改内容（运维约定 + 提示）；文件锁要求站点根在本机文件系统（RISK-T20） | 数据：`recover` 用自动备份恢复；代码：`git revert` | 是：失败分类同 `WriteSequence`，导出复用 B15 `export`，仍兼容 PHP 的 `backup.lock` |
| D 只用运行记录文件当锁（v6 写法） | 先查记录与 `backup.lock`，再原子替换写 `RUNNING` | 小 | “检查 → 占用”不是原子操作，两个进程可同时通过检查（REV-10）；崩溃后无法判断占用者是否还活着 | — | 部分 |

选 C：不改结构、不引入事务，在“无法原子化”的前提下让每次失败都有确定的可见状态、可执行的恢复步骤和数据层回退点。v7 撤回 v6 的 D 写法：原子替换只保证一次写入不被撕裂，不保证“检查不存在 → 占用”互斥；PHP `export` 的 `is_file` 后 `file_put_contents`（`Database.php:96-105`）与 Java 基线 `DatabaseToolService.export` 的 `stat` 后 `replace`（`DatabaseToolService.java:313-325`）是同样的检查后创建竞态，不能当跨进程占用依据。

### 4.13 “执行 SQL”按整段执行还是切分后逐条执行（REV-12，v7 新增）

基线 PHP `sql()`（`Database.php:237-271`）：`trim`、替换 `{pre}` 后，把**整个输入**交给一次 `Db::execute($sql)`；连接是 `PDO::ATTR_EMULATE_PREPARES => false`（`thinkphp/library/think/db/Connection.php:121`）、`break_reconnect => false`（同文件 `:112`），即服务端预处理一次、断线不重连。MySQL 的服务端预处理只接受一条语句，所以含两条及以上语句的输入整体被数据库拒绝、一条都不执行；单条语句末尾的一个 `;` 允许。

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 |
| --- | --- | --- | --- | --- | --- |
| **A（v7 选定，DEC-13）保持 PHP 整段语义** | 跳过判定（以 `select` 开头或含文件读写关键字 → 不执行、返回“执行成功”）完全按 PHP 对整段做；之后 AC-11 结构保护对整段做；整段作为一条语句一次发送，连接 `allowMultiQueries=false`，多语句由 Java 在发送前拒绝（PHP 由数据库拒绝，结果同为一条都不执行） | 小 | 只剩两处可观察差异：AC-11 已确认的结构语句拒绝（OP-6）；失败时的提示文字（PHP 为框架异常页） | 好 | 是（PHP 原样） |
| B 切分后逐条分类、逐条执行（v6 写法） | 按 `mac_parse_sql` 切分 | 中 | 改变 PHP 语义：`SELECT 1; INSERT …` 在 PHP 整体跳过，B 会执行后面的 `INSERT`；多语句在 PHP 一条都不执行，B 会逐条执行并可能部分生效；需要发起人按 OP-6 另行确认 | 好 | 否 |

选 A：PHP 本身没有漏洞的地方以 PHP 为准，不需要新增待决项；v6 说“切分只为分类、成功路径不变”不成立（REV-12），撤回。逐条状态、失败序号这些只对还原（DBT-3，PHP 本来就逐条执行）保留。

### 4.14 OUT-1-5 出站地址固定怎么实现（v9 新增，DEC-14）

现状（@ `6f78400`）：`OutboundHttpClient` 用 JDK `java.net.http.HttpClient`（`OutboundHttpClient.java:79-83`，`Redirect.NEVER`、HTTP/1.1），每一跳先 `policy.check(uri)`（`:115`、`:130`）解析并校验全部地址，`check` 只返回日志用的 `host[:port]`（`OutboundPolicy.java:104`）；随后 `http.sendAsync`（`:186`）按主机名**再解析一次**后建连。两次解析之间 DNS 可以改答（DNS 重绑定），第二次的结果没有经过 OUT-1-4 检查，不满足契约 OUT-1-5。JDK `HttpClient` 没有按请求指定解析结果或自定义套接字的入口；`OutboundNetworkGuardTest.java:69-73` 把 `httpclient5` 列为禁止依赖，`org.apache.hc.` 类型只允许在 `integration.http` 包内使用（同文件 `:43-63` 与合成用例）。

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 |
| --- | --- | --- | --- | --- | --- |
| A 最小改动：保留 JDK 客户端，接受重绑定窗口 | 不改代码，把窗口记为残余风险 | 无 | 不满足已批准契约 OUT-1-5；API-040/041 的目标 URL 由管理员填写，重绑定正是绕过 OUT-1-4 的常见手法；要改契约并请发起人接受风险 | 好 | 是 |
| **B（选定，DEC-14）出站客户端内部换用 Apache HttpClient 5，每一跳固定已校验地址** | `OutboundPolicy.check` 返回“主机名 + 已校验地址”；每一跳新建只含一条连接的连接管理器，注入只返回这组地址的解析器（不调系统 DNS）；TLS 的 SNI 与证书主机名校验、`Host` 头都用原主机名；显式直连路由，不读系统代理 | 中：`integration/http` 内 2 个文件改、1 个新增，`pom.xml` 加 1 个依赖，护栏测试放开 1 个构件名 | 换引擎影响全部出站调用方（支付、短信、OAuth、插件市场、URL 推送、反链）；用现有 12 个出站测试类和 3 个 MySQL IT 原样回归，行为差异逐项写死（NET-7 ⑤） | 好：一个提交整体 `git revert` | 是：仍是唯一出站客户端，调用方与 `OutboundRequest`/`OutboundResponse`/`OutboundException` 不变 |
| C JDK 解析器 SPI（`InetAddressResolverProvider`，JEP 418） | 进程级替换 DNS 解析，按线程取固定地址 | 小 | 由系统类加载器经 `ServiceLoader` 加载，Spring Boot 可执行 jar 的内嵌 jar 加载不到，要改部署方式；影响进程内全部解析（含 JDBC、Redis） | 差 | 否 |
| D JDK 客户端改连 IP 字面量 | URI 换成已校验 IP，手工写 `Host` | 中 | `Host` 是 JDK 客户端的受限头，要进程级系统属性 `jdk.httpclient.allowRestrictedHeaders`；HTTPS 的 SNI 与证书校验绑定 URI 主机，只能每请求新建客户端、关掉主机名校验再自己校验，安全代码自写最多 | 中 | 否 |

选 B（方向已获授权，见元数据“已定方向”：Issue APPK-2 评论 `01a196b5-a035-71df-bbe3-077cd09512a6`（确认人代理 Mika 代人类转达：“同意 Apache HttpClient 5 方案，先评审后实现”，回复调度的方向请求 `01a1968b-baed-7015-a20c-14e0a1d13835`）；Mika 代为转达的依据是发起人评论 `01a1958e-5c60-7d72-9328-d7a5146e1d99`（“以后允许Mika 代我转达”）；调度核验评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`；该授权不代替 G1 增量评审）。依赖理由：要满足 OUT-1-5，必须能控制建连时用哪个地址，JDK 自带客户端做不到（C、D 各有进程级副作用）；`org.apache.httpcomponents.client5:httpclient5` 已由 `spring-boot-dependencies` 4.1.1 管理（5.6.4），`pom.xml` 不写版本；只在 `integration.http` 内使用，“只有 `OutboundHttpClient` 能发请求”的护栏不变。每跳一条连接、不复用，与 PHP `mac_curl_get`/`mac_curl_post` 每次新建 curl 句柄一致。

## 5. 改动清单

### 5.1 OP-15 部分（v2，内容不变）

全部在 `ApiHuaQiPro/maccms.java`；路径相对仓库根。`src/main/**` 只有 C-12～C-14（`{$maccms.path_tpl}` 映射与注释），由后端在 G1 放行后单独提交；本次修订本身只改本设计文档。

| # | 文件路径 | 动作 | 改什么 | 为什么 | 端 |
| --- | --- | --- | --- | --- | --- |
| C-1 | `third_party/maccms-template-v2020.1000.1035/template/default_pc/`（147 个文件，逐个见附表 E.3 第 1～147 行，路径去掉开头的 `template/default_pc/` 后一一对应） | 新增 | 从官方提交 `4c418c2703692148a937ad92893ab8a494bff742` 的 `template/default_pc/` 按字节复制，不改任何内容 | DEC-1；FR-1、BR-11、AC-1 | 后端 |
| C-2 | `third_party/maccms-template-v2020.1000.1035/template/default_wap/`（127 个文件，附表 E.3 第 148～274 行） | 新增 | 同上，来自 `template/default_wap/`；不创建 `ads/` | DEC-1；FR-8、BR-11、AC-1、AC-10 | 后端 |
| C-3 | `third_party/maccms-template-v2020.1000.1035/LICENSE` | 新增 | 官方同一提交根目录 `LICENSE`（Apache License 2.0）原文 | Apache-2.0 再分发须保留许可 | 后端 |
| C-4 | `third_party/maccms-template-v2020.1000.1035/SOURCE.md` | 新增 | 来源仓库、标签、提交号、3 个目录树 SHA-1（附表 E.1）、取得命令、“不得修改，改动须新开目录与 OP-”说明 | DEC-1；可追溯 | 后端 |
| C-5 | `.gitattributes` | 修改 | 末尾加 `third_party/maccms-template-v2020.1000.1035/** -text -whitespace`（附注释：按字节比对 blob SHA，`autocrlf` 不得改写） | RISK-T1；AC-1 ① | 后端 |
| C-6 | `.gitignore` | 修改 | 加 `.compare/`（对照环境装配目录，不入库） | DEC-4 | 测试 |
| C-7 | `tests/compare/APPK-2/maccms-overlay.json` | 新增 | 对照环境对 `application/extra/maccms.php` 的覆盖表，固定 7 项：`site.template_dir=default_pc`、`site.html_dir=html`、`site.mob_template_dir=default_wap`、`site.mob_html_dir=html`、`site.ads_dir=ads`、`site.mob_ads_dir=ads`、`site.mob_status=2`；只允许这 7 个键（其他配置差异由 PRE-2 另行登记，不放这里） | DEC-3、DEC-4；PHPB-9 | 测试 |
| C-8 | `tests/compare/APPK-2/assemble_site.py` | 新增 | 按 §7.3 的 ENV-1～ENV-4 装配 `.compare/APPK-2/pristine/`，再复制出 `php-site/`、`java-site/`；对 `maccms.php` 只做 C-7 列出键的**整行字面替换**（每个键在基线文件里恰好出现一次，替换前后都断言），不重新序列化文件；输出 13 个 CS-CONFIG 文件的 SHA-256 | DEC-4；PRE-2、PHPB-9 | 测试 |
| C-9 | `tests/compare/APPK-2/verify_template_package.py` | 新增 | 输入一个站点根或 `third_party` 目录，对 `template/` 下文件逐个 `git hash-object`，与附表 E.3 的 274 行比对（路径、字节数、SHA-1），并断言 `template/` 下只有 `default_pc`、`default_wap` 两个目录、`default_wap/ads` 不存在 | AC-1 ①；BR-11 | 测试 |
| C-10 | `tests/compare/APPK-2/php.ini` | 新增 | 对照 PHP 进程专用 ini：PHPB-4～PHPB-7 的固定值（`date.timezone=PRC`、`default_charset=UTF-8`、`display_errors=Off`、`log_errors=On`、`precision=14`、`serialize_precision=-1`、`short_open_tag=Off`、`memory_limit=256M`）和 PHPB-3 的扩展加载行；`extension_dir` 不写本机路径，由启动命令 `-d extension_dir=` 传入 | DEC-4；PHPB-3～7 | 测试 |
| C-11 | `config/maccms-backend.env.example` | 修改 | 在 `MACCMS_LEGACY_CONFIG_DIR` 说明处补：站点根（该目录上一级）须放 `template/default_pc/`、`template/default_wap/`（来自 `third_party/maccms-template-v2020.1000.1035/template/`），且旧配置 `site.template_dir=default_pc`、`site.mob_template_dir=default_wap` | DEC-2、DEC-3；U-4 部署方 | 流水线/配置 |
| C-12 | `src/main/java/com/maccms/backend/site/web/SiteVariants.java` | 修改 | `resolve(String userAgent, String host)`（第 95 行）：`new SiteVariant(r.wap(), r.domain(), lang, r.pathTpl(), r.pathAds())` 改为 `new SiteVariant(r.wap(), r.domain(), lang, r.pathTemplate(), r.pathAds())`；没有旧配置时仍为 `""` 不变 | REV-1；PATH-2；FR-1、FR-8 | 后端 |
| C-13 | `src/main/java/com/maccms/backend/site/web/view/SiteVariant.java` | 修改 | 只改注释：`@param pathTpl` 写成“旧 `{$maccms.path_tpl}` = `MAC_PATH_TEMPLATE`（`All.php:62`），即 `install_dir + template/<模板目录>/`，不含 `html_dir`”；`@param pathAds` 写成“= `MAC_PATH_ADS`（`All.php:63`）”。记录字段名与顺序不变 | REV-1；PATH-2、PATH-4 | 后端 |
| C-14 | `src/main/java/com/maccms/backend/site/application/SiteTemplateSelection.java` | 修改 | 只改类注释第 23 行：改为“`pathTemplate`、`pathTpl`、`pathAds` 分别是 `MAC_PATH_TEMPLATE`、`MAC_PATH_TPL`、`MAC_PATH_ADS`；模板变量 `{$maccms.path_tpl}` 取 `pathTemplate`、`{$maccms.path_ads}` 取 `pathAds`（`All.php:62-63`）；`pathTpl` 供‘模板文件不存在’消息使用（`think_exception.tpl:366`）”。`resolve` 逻辑不改 | REV-1；PATH-2、PATH-3 | 后端 |
| C-15 | `src/test/java/com/maccms/backend/site/web/SiteVariantsTest.java` | 修改 | `filterResolvesEachRequestFromUserAgentAndHost`：第 75 行期望改为 `/template/wapt/`，第 82 行改为 `/template/default/`（第 76 行 `pathAds` 期望 `/template/wapt/wads/` 不变）；新增 `pathVariablesFollowLabelMaccms`：用 DEC-3 的 7 个取值（`mob_status='2'`、`install_dir='/'`）组装 site，桌面 UA 断言 `pathTpl=/template/default_pc/`、`pathAds=/template/default_pc/ads/`，手机 UA 断言 `pathTpl=/template/default_wap/`、`pathAds=/template/default_wap/ads/` | REV-1；V-9 | 后端 |
| C-16 | `src/test/java/com/maccms/backend/site/application/SiteTemplateSelectionGoldenTest.java` | 修改 | 比较逻辑与黄金 JSON 不改；第 61 行旁加注释“黄金键 `path_tpl` 记录的是 `MAC_PATH_TPL`，模板变量见 `SiteVariant.pathTpl`”；新增 `dec3ValuesSplitThePathConstants`：DEC-3 取值下 `resolve` 的桌面结果 `pathTemplate=/template/default_pc/`、`pathTpl=/template/default_pc/html/`、`pathAds=/template/default_pc/ads/`、`templateDir=default_pc`、`htmlDir=html`，手机 UA 结果对应 `default_wap` | REV-1；V-9 | 后端 |

### 5.2 完整设计的改动清单（v3 新增，C-17 起）

路径相对仓库根；`B=src/main/java/com/maccms/backend`，`T=src/test/java/com/maccms/backend`。“端”按实现归属：**后端**＝@后端开发-海外，**前端**＝@前端开发-海外（本仓的前端是服务端页面：前台控制器接模板、静态资源、jte 下线），**测试**＝@测试人员-海外，**配置**＝@运营打包-海外。新增文件都在现有目录习惯内。

**(a) PHP 模板兼容层 `phptemplate`（FR-2～FR-4）**

| # | 文件路径 | 动作 | 改什么 | 为什么 | 端 |
| --- | --- | --- | --- | --- | --- |
| C-17 | `B/phptemplate/runtime/ZValue.java`、`ZArray.java`、`PhpSemantics.java` | 新增 | RT-2～RT-5：值类型、有序数组（键规范化同 `PhpArray.normalizeKey`）、字符串化（`precision=14`）、PHP 7.3 弱比较、未定义取值 | FR-2、FR-4；RISK-T10 | 后端 |
| C-18 | `B/phptemplate/runtime/PhpLexer.java`、`PhpParser.java`、`PhpAst.java` | 新增 | RT-1 子集：把编译产物切成字面段/代码段（TPL-5 吃换行），代码段解析成语法树；子集外语法抛 `TemplateUnsupportedException` | FR-2、TPL-5、TPL-10 | 后端 |
| C-19 | `B/phptemplate/runtime/Interpreter.java`、`ExecutionLimits.java` | 新增 | 执行语法树，输出到字节缓冲；函数只经 `FunctionTable` 分派；RT-8 上限 | FR-2；RT-7、RT-8 | 后端 |
| C-20 | `B/phptemplate/engine/TemplateCompiler.java` | 新增 | 移植 `Template.php` 的 `parse`/`compiler`/`parseInclude`/`parseLiteral`/`parsePhp`/`parseTag`/`parseVar`/`parseVarFunction`/`parseAttr`/`getRegex`（TPL-1～TPL-6、TPL-11），正则逐字移植 | FR-2 | 后端 |
| C-21 | `B/phptemplate/engine/TagLibSupport.java`、`CxTagLib.java` | 新增 | 移植 `TagLib.php`（标签配对、`parseCondition`、`autoBuildVar`）与 `Cx.php` 全部标签 | FR-2 | 后端 |
| C-22 | `B/phptemplate/engine/MaccmsTagLib.java` | 新增 | 移植 `Maccms.php` 19 个 `tagXxx`（含 `json_encode` 转义规则：`/`→`\/`、非 ASCII→`\uXXXX`） | FR-3 | 后端 |
| C-23 | `B/phptemplate/engine/OriginMap.java`、`TemplateUnsupportedException.java` | 新增 | TPL-9 行号表；异常带模板文件、行号、原文片段 | FR-2 异常与边界 | 后端 |
| C-24 | `B/phptemplate/application/PhpTemplateRenderer.java`、`TemplateRequest.java`、`RenderedPage.java`、`PageVars.java` | 新增 | §6.3 对外接口；按 `SiteVariant` 选 PATH-1；执行 TPL-7 压缩（TPL-6 的替换在 C-20 编译阶段完成）；“模板文件不存在”转 `TemplateNotFoundPage` | FR-1、FR-2、FR-5、FR-8 | 后端 |
| C-25 | `B/phptemplate/application/TemplateNotFoundPage.java`；资源 `src/main/resources/phptemplate/think_exception.html` | 新增 | 按 `think_exception.tpl:362-370` 非调试分支生成同字节的页面（HTTP 500，消息“模板文件不存在:<路径>”删去 PATH-3） | AC-1 ③、BR-12、AC-5 缺页样本 | 后端 |
| C-26 | `B/phptemplate/application/CompiledTemplateCache.java`、`CacheResetHook.java` | 新增 | TPL-8 编译缓存；CACHE-1～CACHE-3 | W-04；V9-001 | 后端 |
| C-27 | `B/phptemplate/application/FunctionTable.java`、`TemplateFunctions.java` | 新增 | RT-7 白名单：编译产物内部函数 + PRE-5 清单的模板函数；`mac_url_*` 委托 `LegacyUrlBuilder`/`ImageUrlNormalizer`，`lang` 委托 `LegacyLang`，`mac_page_param`、`mac_compress_html` 逐字移植 | FR-4、AC-4 | 后端 |
| C-28 | `B/phptemplate/application/TagModels.java` 及 `tag/VodTagModel.java`、`ArtTagModel.java`、`TopicTagModel.java`、`ActorTagModel.java`、`RoleTagModel.java`、`WebsiteTagModel.java`、`CommentTagModel.java`、`GbookTagModel.java`、`TypeTagModel.java`、`LinkTagModel.java`、`ExtendTagModel.java` | 新增 | §6.6：各模型 `listCacheData` 的属性解析、条件、排序、分页、派生键；能复用的走 `content.application`（`ContentReadService.listVideos`+`VideoLegacyConditions`、`LegacyTagAttributes`、`LegacyRange`、`LegacyRowReadService`）与 `comment.application.CommentService` | FR-3、AC-3、TAGMAP-1～19 | 后端 |
| C-29 | `B/phptemplate/infrastructure/TagReadMapper.java`；`src/main/resources/mapper/phptemplate/TagReadMapper.xml` | 新增 | C-28 中现有服务不覆盖的只读查询（只 `SELECT`，表列按 D.6） | FR-3；AC-16 | 后端 |
| C-30 | `B/phptemplate/application/PageVariables.java` | 新增 | `label_maccms`（All.php:58-96）逐键移植，含 PATH-2/4、EX-1 `date`、EX-2 `http_url` | FR-4、FR-8 | 后端 |
| C-31 | `B/phptemplate/config/PhpTemplateConfiguration.java`、`PhpTemplateProperties.java` | 新增 | 装配；`maccms.phptemplate.limits.*`（RT-8）、`maccms.phptemplate.compare.reset-file`（CACHE-3 ②，默认空=关闭） | RT-8、CACHE-3 | 后端 |
| C-32 | `src/main/resources/application.yml` | 修改 | 增加 C-31 两组配置项的默认值与注释（不含凭据） | 同上 | 配置 |
| C-33 | `src/test/php/tpl_compile_golden.php`、`tpl_runtime_golden.php`；`src/test/resources/phptemplate/{compile,runtime}-golden/` | 新增 | 黄金生成脚本（PHP 7.3.33 运行，只读 `php/`）：①对 168 个模板与语法样例输出 ThinkPHP 编译产物；②值语义向量（字符串化、比较、数组键、`json_encode`、`mac_*` 函数返回值） | V-11、V-12；RISK-T10 | 测试 |
| C-34 | `T/phptemplate/**`（`TemplateCompilerGoldenTest`、`PhpSemanticsGoldenTest`、`InterpreterTest`、`TagLibCompileTest`、`TemplateFunctionsGoldenTest`、`TagModelsMySqlIT`、`PhpTemplateRendererTest`） | 新增 | 黄金对比与单测；`TagModelsMySqlIT` 用一次性库 `maccms_it_*` 对 19 个标签逐属性断言（与 PHP 同库输出对比） | AC-2～AC-4 | 后端 |
| C-35 | `T/architecture/ModuleBoundaryTest.java`、`T/site/SiteGuardrailsTest.java`、`T/ReadOnlyGuardrailsTest.java` | 修改 | 登记 `phptemplate` 分层与依赖（§6.3 表）；`site` 允许依赖 `phptemplate.application`；`phptemplate.runtime` 只依赖 JDK；`phptemplate.infrastructure` 只读 | §6.3；02 规范 | 后端 |

**(b) 前台接入与 jte 下线（FR-1、FR-5、FR-8）**

| # | 文件路径 | 动作 | 改什么 | 为什么 | 端 |
| --- | --- | --- | --- | --- | --- |
| C-36 | `B/site/web/legacy/`（新增包）：`HomeVars.java`、`VodVars.java`、`LibraryVars.java`、`InteractionVars.java`、`UserVars.java`、`MiscVars.java`、`PublicVars.java` | 新增 | §6.8：每个 PHP `label_*`/控制器 `assign` 对应一个方法，产出 `PageVars`；整行数据经 `LegacyRowReadService` | FR-4 ②、FR-5 | 前端 |
| C-37 | `B/site/web/SiteContentController.java`、`SiteSearchController.java`、`SiteVodShowController.java`、`SiteLibraryController.java`、`SiteModuleListController.java`、`SitePlaybackController.java`、`SiteMiscController.java`、`SiteAjaxController.java`、`SiteInteractionController.java`、`SiteUserController.java`、`SiteMemberFinance.java`、`SiteMemberBoards.java`、`SitePaymentController.java` | 修改 | 各页面分支由 `SiteHtmlRenderer.xxx(viewModel)` 改为 `PhpTemplateRenderer.render(variant, mac_tpl_fetch 结果, vars)`；按 PG-1～PG-6 分批（§7.4）；ALIGN-4 恢复 `ajax/pwd`（含 PHP 的按会话 5 秒限制；`PasswordAttempts` 按 OP-T4=a 保留并移到 `ajax/pwd`，见 C-68）；响应一律经 C-70 `SitePageResponses`；v6 补：`SiteMemberFinance:525`、`SitePaymentController:104` 的 `SiteHtmlRenderer.HTML_UTF8` 改为 `SiteRequests.HTML_UTF8`，`SiteUserController:95`、`SiteVerifyController:42` 的私有同值常量删除、改用 `SiteRequests.HTML_UTF8`；会员页不再生成 nonce；CSP 头一律由 C-75 提供（DEC-11，OP-T6=b 的三项策略） | FR-5、FR-8 | 前端 |
| C-38 | `B/site/web/SiteExceptionHandler.java`、`SiteStatusInterceptor.java`、`SiteVerifyGate.java`、`SitePlaceholderController.java`、`B/common/web/GlobalExceptionHandler.java` | 修改 | ALIGN-1～3：站点关闭、404、跳转、验证码页走 `public/*` 模板（跳转/提示统一经 C-70 `JumpPages`）；错误页模板本身渲染失败时沿用现有兜底（固定文本、不含数据，常量从 `SiteHtmlRenderer.FALLBACK_ERROR_HTML` 移到 `SiteExceptionHandler`）；站点路径未匹配按 PHP `_empty`（`GlobalExceptionHandler` 只对 `/api/v1/**` 保持 JSON） | FR-5、PG-7 | 前端 |
| C-39 | `src/main/resources/phptemplate/builtin/index/view/{index/index.html,public/jump.html,public/msg.html}` | 新增 | 从 `php/application/index/view/` 按字节复制的 3 个内置视图（`.gitattributes` 同 C-5 加 `-text`） | ALIGN-3；B.1.2 | 前端 |
| C-40 | `src/main/jte/site/**`（76 个）、`B/sitetemplate/**`、`src/main/resources/static/site/**`、`src/main/resources/static/site-player/player-boot.js`、`src/test/jte-probe/**`、`T/site/SiteTemplatePolicyTest.java`、`T/sitetemplate/**`、`B/site/web/SiteHtmlRenderer.java`、`B/site/application/PlayerFramePolicy.java`（v6 补；`PlaybackPrimitivesTest` 中它的用例一并删除）、`B/site/web/view/` 中只为 jte 服务的 record | 删除 | §6.9：按 PG 组逐批删，全部 PG 完成后删 `sitetemplate` 与 `SiteHtmlRenderer`；删除前 V-32 ① 的检索必须无结果（v7 更正：检索限定在 `site` 包，`admin`/`install` 各自的 `CONTENT_SECURITY_POLICY` 不在本期范围） | OP-3；RISK-7；DEC-11 | 前端 |
| C-41 | `pom.xml` | 修改 | 删 site 的 `jte-maven-plugin` execution 与 `site-jte` 资源拷贝；删只供在线编辑的 `gg.jte:jte` 编译器依赖（后台、安装的 jte 预编译保留）；不新增依赖 | OP-3 | 前端 |
| C-42 | `B/site/config/SiteConfiguration.java` | 修改 | 删 `siteHtmlRenderer`/`OnlineTemplates`（L37 的 import、L355-361）；`siteSearchThrottle`（L338）的参数由 `SiteHtmlRenderer` 改为 C-70 的 `JumpPages`（随 S3-14b 先改，见 C-71）；装配 `PhpTemplateRenderer`、`SitePageResponses`、`JumpPages` 依赖（C-43 自带 `@Controller` + `@ConditionalOnSiteEnabled`，同现有 `site/web` 控制器自动装配，不在此注册） | OP-3 | 前端 |
| C-43 | `B/site/web/TemplateAssetController.java` | 新增 | §6.10 `/template/**` 静态资源控制器（`@Controller` + `@ConditionalOnSiteEnabled`）：只做 URL → 站点根相对路径 `template/…`、`GET/HEAD`、`Content-Type`（按 §6.10 固定表）与 `Last-Modified` 条件请求；**放行范围不在本类判断**，子目录与扩展名全部由 C-44 `readTemplateAsset` 决定（避免两处规则不一致），收到任何 `FileException` 一律 404、不回显路径 | DEC-8；OP-T1；SEC-7（OP-T5=a） | 前端 |
| C-44 | `B/legacyconfig/application/LegacySiteFiles.java` | 修改 | 增加只读方法 `readTemplateAsset(String relative, int maxBytes)`，是 `/template/**` 唯一的放行边界（OP-T5=a，v8 起只此一种实现）：① `relative` 经 `LegacySiteRoot.validRelative`（拒绝 `..`、`.`、空段、反斜杠、`:`、空字节、控制字符），且以 `template/<目录>/` 开头、其后至少一段；② 经 `LegacySiteRoot.resolve` 规范化，真实路径须在 `<站点根>/template/` 内（比现有 `read` 只校验站点根更严：中间目录是符号链接时，解析后出 `template/` 或出站点根都拒绝）；③ 最终文件按 `NOFOLLOW_LINKS` 打开，本身是符号链接、目录或非普通文件都拒绝；④ 扩展名 `php`（按小写比较）一律拒绝；⑤ 只读：不建目录、不写、不改时间；超 `maxBytes` 拒绝（`TOO_LARGE`）；⑥ **SEC-7 白名单**：`<目录>` 后的第一段必须是 `css`/`js`/`images`/`ads` 之一，且扩展名（按小写）在 `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf` 内，否则 `FORBIDDEN`（`.html`、`.htm`、`.ini`、`.txt`、无扩展名都拒绝）。不设运行时开关，不扩大写区域，不改现有 `read`（属 S3-13b） | DEC-8；SEC-7（OP-T5=a） | 后端 |
| C-45 | `B/site/web/InternalPageRenderer.java` | 修改 | `ac2=wap` 时设置手机 `SiteVariant`（替代 UA 推断） | FR-6、§6.2 | 前端 |
| C-46 | `T/site/**` 约 34 个页面测试类（§3.2 列出） | 修改 / 删除 | 按 §6.9 逐类改为断言 PHP 模板输出，或被 AC-5 对照覆盖后删除 | RISK-7 | 前端 |
| C-70 | 新增 `B/site/web/SitePageResponses.java`、`B/site/web/legacy/JumpPages.java`；修改 `B/site/web/SiteRequests.java` | 新增 / 修改 | DEC-11 的共享边界。`SitePageResponses`（包内 `final` 类，由 `SiteConfiguration` 按 `SiteVariants.dependsOnUserAgent()` 构造一个实例）：`page(RenderedPage, CacheMode{NO_CACHE,NO_STORE}, boolean noindex)` → `ResponseEntity<byte[]>`，头部按 §4.11 方案 E，状态码取 `RenderedPage.status`，CSP 头取 C-75 `SiteSecurityHeaders.frontPolicy()`（v8：恒有值，即三项策略）；`RenderedPage` 是完整缓冲的字节，渲染中途失败时什么都没写出（保留 `SiteHtmlRenderer` 的缓冲语义，由 `SiteExceptionHandler` 出完整错误页）。`JumpPages`：PHP `$this->error()/success()`（前台模块由 `Init.php:67-68` 把 `dispatch_success_tmpl`/`dispatch_error_tmpl` 设为 `public/jump`）的唯一入口，`error(variant, msg, url, wait)`/`success(...)` 组 `code/msg/data/url/wait` 页面变量交 `PhpTemplateRenderer`，再经 `SitePageResponses` 输出（HTTP 200）。`SiteRequests.HTML_UTF8` 保持包内可见、成为全包唯一的 HTML `Content-Type` 常量 | ALIGN-3、ALIGN-5、ALIGN-6、ALIGN-7；REV-8 | 前端 |
| C-71 | `B/site/web/SearchThrottle.java`、`SiteCounterActions.java`、`PlaybackPages.java` | 修改 | v5 漏列的三个调用方（§6.8 迁移表）：`SearchThrottle` 构造参数 `SiteHtmlRenderer renderer, SitePages pages` 改为 `JumpPages jumps`，过快时按 ALIGN-6 返回 `jumps.error(lang('search_frequently')+timespan+lang('seconds'))`（HTTP 200，无 `Retry-After`），会话计时逻辑不改；`SiteCounterActions` 删 `renderer` 字段与构造参数，非 ajax 分支按 ALIGN-7 改；`PlaybackPages` 删 `strictFramePolicy()`、`Frame.policy` 与只供 jte 的视图记录，授权与地址判定（W1/W2、协议白名单）不改，结果改为供 C-36 `VodVars` 组页面变量 | REV-8；ALIGN-6、ALIGN-7；BR-4 | 前端 |
| C-72 | `T/site/web/SiteTestRoutes.java`、`T/site/config/SiteConfigurationTest.java`、`T/site/web/PlaybackSwitchCases.java`、`SiteAjaxFragmentsMvcTest.java`、`SiteFinanceContentMvcTest.java`、`SitePlaybackPagesMvcTest.java`、`SiteVodListPagesMvcTest.java`；新增 `T/site/web/SitePageResponsesTest.java`、`T/site/web/SearchThrottleTest.java`（若已有同名类则修改） | 修改 / 新增 | `SiteTestRoutes`：构造参数改为 `SitePageResponses`；`/boom-large` 改为让 `PhpTemplateRenderer` 的替身在写出约 200 KB 后抛异常，仍断言响应是完整 500 页、不含 `PARTIAL` 片段（保住“不会 200 + 半页”的护栏）；其余路由不变。`SiteConfigurationTest`：`SiteHtmlRenderer` 的 Bean 断言改为 `PhpTemplateRenderer`、`SitePageResponses`。五个 CSP 断言改为“`Content-Security-Policy` 头等于 `SiteSecurityHeaders.frontPolicy()`、`Content-Type` 等于 `text/html;charset=UTF-8`”，这样 C-75 将来改值时不必再改这些测试（v7；v8 起 C-75 固定为 OP-T6=b 的三项，原文只由 `SitePageResponsesTest` 断言），随所属 PG 组修改，测试类本身被 AC-5 对照覆盖后可按 C-46 删除。新增 `SitePageResponsesTest`：头部集合、`Vary`、`no-store`、长度、CSP 取自 C-75；`SearchThrottleTest`：ALIGN-6 | REV-8；V-32 | 前端 |

**(c) 后台模板管理、静态生成（FR-6、FR-7）**

| # | 文件路径 | 动作 | 改什么 | 为什么 | 端 |
| --- | --- | --- | --- | --- | --- |
| C-47 | `B/admin/application/TemplateFileService.java` | 新增 | 从 `SiteTemplateService` 拆出非 jte 的部分：列表、读、版本保存（`LegacySiteFiles.replace`）、删除、`LEGACY_FILTER`（原文不变） | FR-7；W-01～W-08 | 后端 |
| C-48 | `B/admin/web/AdminTemplateActions.java` | 修改 | `EXTENSIONS` 改为 `html,htm,js,xml`（L43）；改用 C-47；删 jte 编译、备份恢复分支；`wizard` 改为 C-49 | §6.11；W-06、W-09 | 后端 |
| C-49 | `src/main/jte-admin/admin/pages/template/wizard.jte`；`src/main/resources/static/admin-assets/template-wizard.js`（新增） | 修改 / 新增 | 按 PHP `admin@template/wizard` 视图的表单与生成脚本移植，输出 `{maccms:…}` 片段；删 `TemplateWizard` 的 jte 片段 | AC-9 | 后端 |
| C-50 | `B/staticgen/application/MakeService.java` | 修改 | `label` 生成（撤销 D-B16-5）；RSS 分页（撤销 D-B16-6）；`markMade` 扩到 actor/role/website；失败输出按 PHP 格式 | FR-6；K-10～K-12 | 后端 |
| C-51 | `B/content/application/StaticSourceService.java`；`src/main/resources/mapper-write/content/ContentAdminWriteMapper.xml`；`B/common/write/WriteRegistry.java` | 修改 | `markMade` 支持 `actor`/`role`/`website` 的 `*_time_make`；登记三张表该列的 `UPDATE` | K-12；D-19 | 后端 |
| C-52 | `B/admin/web/AdminController.java` | 修改 | `clear`（L304）末尾调用 `CacheResetHook.resetAll()`（CACHE-3 ①） | V9-001 | 后端 |

**(d) 数据库工具、安装、在线功能（FR-9～FR-11）**

| # | 文件路径 | 动作 | 改什么 | 为什么 | 端 |
| --- | --- | --- | --- | --- | --- |
| C-53 | `B/admin/application/DatabaseToolService.java`；新增 `B/admin/application/SqlStatements.java`（`mac_parse_sql` 移植，只供 DBT-3；DBT-2 的整段跳过判定、多语句检测与 AC-11 分类器） | 修改 / 新增 | DBT-1 `optimize`/`repair`；DBT-2 `sql` 按 PHP 整段语义（v7，DEC-13：`{pre}` 替换、PHP 跳过判定、多语句发送前拒绝、AC-11 分类、整段一次发送，见 §6.13 DBT-2）；DBT-3 `import` 只还原数据（v7：还原表集合含只有 `DROP/CREATE` 的空表）与 `recover`；DBT-4 导出与删备份 `delete` 改为在 C-73 的锁内执行（v8，REV-14：`delete` 在锁内先做中断判定、再读运行记录，拒绝删除 DBT-3 待处理记录绑定的备份，按卷号升序删除）；DBT-5 ⑩ 的中断判定与清理；DBT-5 的预检、逐条状态、失败分类、还原前自动备份与哈希；删除 `REFUSED` 常量的四项用途 | FR-9；AC-11；DBT-5 | 后端 |
| C-54 | `B/admin/web/AdminDatabaseActions.java`；`B/admin/infrastructure/DatabaseToolMapper.java` 与 `src/main/resources/mapper/admin/DatabaseToolMapper.xml`（或写 Mapper 对应 XML）；`src/main/jte-admin/admin/pages/database/import.jte`（修改）、`sql.jte`（新增，按 PHP `admin@database/sql` 视图移植） | 修改 / 新增 | L63-65 的拒绝改为调用 C-53；语句执行经可写数据源，`DELETE`/`INSERT` 按表登记 `WriteRegistry`；页面显示 DBT-5 的运行结果（还原：总数、已执行、失败表与序号、结果分类；执行 SQL：成功 / 未生效 / 结果不确定）与“待处理”提示条；提示条上的按钮只按 DBT-5 状态机出现：`recover`（目标“还原前自动备份”或“重跑原备份”，仅 DBT-3）、`acknowledge`（“我已处理”，未恢复的 DBT-3 记录须勾选“接受当前数据”）；新增两个后台动作 `database/recover`、`database/acknowledge`（POST + 表单令牌，权限同 `database/import`）；v8：`del` 分支（基线 `AdminDatabaseActions.java:60`）仍调用 `DatabaseToolService.delete`，入参与 JSON 结构不变，只新增 DBT-4 的两种拒绝提示；数据库工具各页面加载时调用 `DatabaseRuns.probe()` 做一次不等待的中断判定（DBT-5 ⑩），提示条与按钮按 ⑩ 表的“页面”列出现 | FR-9；DBT-5 | 后端 |
| C-73 | 新增 `B/admin/application/DatabaseRuns.java`、`DatabaseRun.java`（运行记录、互斥、状态机、失败分类）；修改 `B/legacyconfig/application/LegacySiteFiles.java`（新增 `tryExclusiveLock(String relative)`） | 新增 / 修改 | DBT-5 ③④⑥ 与状态机（v7，REV-10/REV-11）。**锁**：`LegacySiteFiles.tryExclusiveLock` 按现有路径校验解析站点根相对路径 `application/data/backup/database/dbtool.lock`（不存在则创建，永不删除），`FileChannel.open(CREATE, WRITE)` 后 `tryLock()` 取独占锁，拿不到返回空；同一 JVM 内另加按规范化路径登记的 `ReentrantLock.tryLock()`（`FileLock` 只在进程之间互斥，同进程重叠加锁会抛 `OverlappingFileLockException`，按“占用”处理）；返回的句柄 `close()` 时先放 OS 锁再放进程内锁。**所有权**：持锁者就是占用者；锁随持有进程退出由操作系统释放，所以不需要租约或心跳。**规则**：运行记录 `dbtool-run.json` 只在持锁时读后写（经 `LegacySiteFiles.replace` 原子替换），页面显示可以不持锁只读；持锁者读到 `RUNNING`/`RECOVERING`，说明上一个占用者已不在（否则拿不到锁），先按 DBT-5 ⑩ 的中断结果表**按 `op` 与 `phase` 唯一改写**（v8，REV-15：DBT-1、导出、DBT-3 备份阶段 → `DONE` 并清理半成品；DBT-2、DBT-3 还原阶段 → `INTERRUPTED`；`RECOVERING` → `RECOVERY_INTERRUPTED`）并记 `interrupted`，再做本次判断；取锁的入口共八个：DBT-1、DBT-2、DBT-3、导出、`recover`、`acknowledge`、`delete` 与页面加载的 `probe()`（v8），中断判定是它们共用的第一步；持锁后另查 `backup.lock`（只为兼容 PHP 后台，存在则拒绝）；导出（DBT-4）与还原前自动备份在锁内执行，仍按 B15 写删 `backup.lock`，写入值同时记入 `backup_lock`；`delete` 在锁内执行（v8，REV-14）。记录字段：`run`（UUID）、`op`（`OPTIMIZE`/`REPAIR`/`SQL`/`IMPORT`/`EXPORT`）、`phase`（v8：`IMPORT` 为 `AUTO_BACKUP` 或 `RESTORE`，其他为空）、`state`、`admin_id`、`owner`（`host`、`pid`、进程启动时间，只作审计显示）、`started_at`、`total`、`executed`、`failed_index`、`outcome`、`sqlstate`、`error_code`、`tables_done`、`table_current`、`inserted_current`、`source_backup`（原备份时间戳）、`auto_backup`（时间戳、分卷名与各卷 SHA-256、表清单）、`export_files`（v8：导出或自动备份本次已创建的分卷文件名，每新建一卷先记入再创建）、`backup_lock`（v8：本次写入 `backup.lock` 的值）、`interrupted`（v8：判定时间、判定前状态与阶段、清理结果 `OK`/`LOCK_KEPT`/`FAILED` 与残留文件名）、`recovery`（目标、次数、最近一次的结果与时间）、`ack`（处理人、时间、`accepted_as_is`）。失败分类按 12 文档第 4.3 节第 3、4 条（与 `WriteSequence` 的判定一致，复用其 SQLState/错误码表，不复制）；只记语句序号、类别、表名、SQLState 与错误码，不记 SQL 原文与数据值 | FR-9；DBT-5；REV-7、REV-10、REV-11、REV-14、REV-15 | 后端 |
| C-74 | 新增 `T/admin/application/SqlStatementsTest.java`、`DatabaseRunsTest.java`、`T/admin/DatabaseRunsLockIT.java`（跨进程）、`T/admin/DatabaseRunsLockProbe.java`（测试侧子进程入口，只在 `src/test`）、`T/admin/DatabaseToolMySqlIT.java` | 新增 | V-23、V-31 的单测与集成测试（整段语义样本、中途失败、结果不确定、两个进程同时起跑的互斥、持锁进程被杀后的中断识别、恢复状态机、重复还原幂等；v8：按操作类型与阶段被杀后的唯一转移与清理、删备份与恢复的竞争）；故障注入用测试侧的 `DataSource` 包装（在第 n 条语句抛指定 SQLState），不在产品代码里留测试钩子 | AC-11；DBT-5 | 后端 |
| C-75 | 新增 `B/site/web/SiteSecurityHeaders.java` | 新增 | 前台 HTML 页 CSP 的唯一来源（v7，REV-9；v8 按 OP-T6=b 定值，REV-13）：包内 `final` 类，常量 `FRONT_POLICY` = `object-src 'none'; base-uri 'self'; frame-ancestors 'self'`，`static String frontPolicy()` 返回它；随 S3-14a 落地即为此值，所有前台 HTML 页共用，没有过渡值；会员 nonce、`PlayerFramePolicy`、网关 `form-action` 变体不再生成。其他类不得出现 `Content-Security-Policy` 字面量（V-32 ① 检索） | REV-9、REV-13；OP-T6=b | 前端 |
| C-55 | `T/architecture/SchemaGuardTest.java` | 修改 | `REGISTERED_TEXT` 增加 `SqlStatements.java`、`DatabaseToolService.java` 的 `OPTIMIZE TABLE`/`REPAIR TABLE`/拒绝关键字表，注明 AC-11；其他位置照旧禁止 | BR-1；FR-13 | 后端 |
| C-56 | `B/install/application/InstallWizard.java`、`InstallSchema.java`；新增 `B/install/application/InstallSqlFile.java`；`B/install/infrastructure/InstallConnections.java` | 修改 / 新增 | INS-1～INS-4：空库 + SHA-256 判定后执行 `install.sql`（常量 `48dbe0fb…`，见 INS-1）；删除指纹只读校验分支 | FR-10；AC-12 | 后端 |
| C-57 | `T/install/InstallSqlFileTest.java`（新增）、`T/install/InstallWizardMySqlIT.java`（修改） | 新增 / 修改 | 常量 = 仓库文件（git blob 字节）的哈希，且断言工作区文件不含 `\r`（`autocrlf` 检出会让哈希变成 `152e5424…`，测试须直接失败并提示 C-67）；空库执行、已有表拒绝、哈希不同拒绝（1 字节差异、整文件改为 CRLF 两种）三种情况，各断言发出的语句集合 | AC-12 | 后端 |
| C-67 | `.gitattributes` | 修改 | 末尾加 `php/application/install/sql/install.sql text eol=lf`（附注释：安装向导按 SHA-256 校验该文件，工作区必须与 blob 同为 LF；写法同已有的 `schema-fingerprint.txt text eol=lf`）。只改检出规则，不改 `php/` 下文件内容（blob 本来就是 LF，无需 renormalize） | INS-1；RISK-T17 | 后端 |
| C-68 | `B/site/application/PasswordAttempts.java`；`B/site/config/SiteConfiguration.java`（装配）；`T/site/application/PlaybackPrimitivesTest.java`（`PasswordAttempts` 用例） | 修改 | OP-T4=a（v8 起唯一实现）：保留类、装配与测试，调用点从 `SiteLibraryController`/`SitePlaybackController` 的密码表单移到 `ajax/pwd`（ALIGN-4 ⑤），超限返回 PHP 已有的 1003 JSON（HTTP 200，不再用 429） | ALIGN-4；SEC-9（OP-T4=a） | 前端 |
| C-69 | `B/legacyconfig/application/LegacySiteFiles.java` | 修改 | 增加只读方法 `readInstallSql(int maxBytes)`：只读 `application/install/sql/install.sql` 这一个固定路径（不收调用方传入的路径），经 `LegacySiteRoot.resolve` 校验在站点根内，最终文件按 `NOFOLLOW_LINKS` 打开；返回原始字节，不做换行转换（INS-2 ③ 按原始字节算哈希）；不扩大写区域。v4 及以前与 `readTemplateAsset` 同列在 C-44，v5 拆出，因为它属于 S3-20，与 OP-T5 无关 | INS-1 | 后端 |
| C-58 | `B/integration/collect/`（新增包）、`B/integration/application/ResourceSiteClient.java`（新增）、`B/collect/application/CollectRunService.java`、`CjRunService.java`、`B/admin/web/AdminCollectActions.java`、`B/admin/application/AdminPermissions.java`、`B/staticgen/application/TimmingTasks.java` | 新增 / 修改 | NET-1、NET-2、NET-4 | FR-11；AC-13 | 后端 |
| C-59 | `B/integration/image/`（新增包）、`B/integration/application/RemoteImages.java`（新增）、`B/collect/application/VodIngest.java`、`B/admin/web/AdminContentActions.java`（L516 `images/sync`） | 新增 / 修改 | NET-3 | FR-11；AC-13 | 后端 |
| C-60 | `B/integration/storage/`（新增包：`UpyunUploader`、`QiniuUploader`、`FtpUploader`、`WeiboUploader`、`AlibabaUploader`、`UomgUploader`）、`B/integration/application/RemoteStorage.java`（新增）、`B/content/application/AdminUploadService.java` | 新增 / 修改 | NET-5 | FR-11；AC-13 | 后端 |
| C-61 | `B/integration/application/IntegrationPackages.java`；`T/architecture/OutboundNetworkGuardTest.java`；`T/integration/demo/RegisteredOutboundClientTest.java` | 修改 | NET-6 登记三个新包（`storage` 的 FTP 另登记 `RAW_API`） | AC-13 | 后端 |
| C-76 | `pom.xml` | 修改 | 增加依赖 `org.apache.httpcomponents.client5:httpclient5`，**不写 `<version>`**（由 Spring Boot BOM 管理，4.1.1 为 5.6.4）；不加其他 HTTP 客户端构件 | OUT-1-5；DEC-14 | 后端 |
| C-77 | `B/integration/http/OutboundPolicy.java` | 修改 | NET-7 ①：`check(URI)` 改为返回包内公开 record `OutboundPolicy.Target(String logHost, String host, List<InetAddress> addresses)`；`logHost` 即原返回值；`host` 为 URI 主机（去掉 IPv6 方括号、末尾点，小写）；open 模式 `addresses` 是 `resolveChecked` 的全部结果且已逐个过 `forbidden`（顺序保持解析顺序，不去重以外的改动）；`LOOPBACK_ONLY` 模式由回环字面量直接得到（字面量解析不走 DNS）。拒绝分支、禁止类别、端口与主机名规则一律不改。另加包内构造器 `OutboundPolicy(Mode, Resolver, Predicate<InetAddress> forbidden)` 仅供 V-33 测试替换禁止判定；公开构造器固定用 `forbidden(InetAddress)` | OUT-1-3、OUT-1-5 | 后端 |
| C-78 | `B/integration/http/OutboundHttpClient.java`；新增 `B/integration/http/PinnedDnsResolver.java` | 修改 / 新增 | NET-7 ②～⑥：内部引擎从 `java.net.http.HttpClient` 换为 HttpClient 5 classic，每一跳按 `Target` 固定地址建连；公开构造器、`send` 两个重载、`mode()`、调用方检查、跳转逻辑（含 303/301/302 改 GET、跨源去凭据头、`MAX_REDIRECTS=5`）、日志字段与 `OutboundException.Reason` 全部不变；另加包内构造器多收一个 `SSLContext`（只给 V-33 的 TLS 用例，公开构造器用 JDK 默认 `SSLContext`） | OUT-1-5、OUT-1-6～OUT-1-8 | 后端 |
| C-79 | `T/architecture/OutboundNetworkGuardTest.java` | 修改 | `FORBIDDEN_ARTIFACTS` 去掉 `httpclient5`（`httpclient`、`httpasyncclient`、`okhttp` 等保持禁止），注释写明 APPK-2 DEC-14：只作为 `integration.http` 的内部引擎；新增断言：`pom.xml` 中 `httpclient5` 的 `<dependency>` 不含 `<version>`。`OUTBOUND_TYPES` 与合成用例不改（`org.apache.hc.` 仍只允许在 `integration.http`） | DEC-14；NET-6 护栏 | 后端 |
| C-80 | `T/integration/http/OutboundPolicyTest.java`（修改：断言改读 `Target.logHost()`/`addresses()`）；新增 `T/integration/http/OutboundAddressPinningTest.java`、`T/integration/http/PinnedDnsResolverTest.java`；新增测试资源 `src/test/resources/outbound-tls/`（自签证书的 PKCS12：`pin.invalid`、`other.invalid` 各一份，口令写在测试代码里，只用于测试） | 修改 / 新增 | V-33 ①～⑦ | OUT-1-5 | 后端 |

**(e) 对照环境与验收工具（PRE-1～PRE-5 中由本设计指定的部分）**

| # | 文件路径 | 动作 | 改什么 | 为什么 | 端 |
| --- | --- | --- | --- | --- | --- |
| C-62 | `tests/compare/APPK-2/data_snapshot.py` | 新增 | SNAPD-1～SNAPD-3：快照、恢复、校验、恢复记录 | PRE-2；CMP-2 | 测试 |
| C-63 | `tests/compare/APPK-2/assemble_site.py`（C-8） | 修改 | ENV-2 的 `database.php` 加会话时区（SNAPD-4）；`vendor/` 摘要（SNAPD-5） | OP-T3 | 测试 |
| C-64 | `tests/compare/APPK-2/run_compare.py` | 新增 | 按附表 F 执行样本：每单元 ENV-9 清缓存 → SNAPD-2 恢复 → 请求 → NORM-1～5 → 报告（KPI-1/5 计数） | AC-5～AC-7、AC-10 | 测试 |
| C-65 | `tests/compare/APPK-2/scan_templates.py` | 新增 | PRE-5 扫描：对 168 个 `.html` 列标签、函数、变量、`include`、PHP 代码、时钟/随机/会话来源，生成 `docs/test/APPK-2/template-scan.md` 的初稿（逐处实现说明由架构师补全） | PRE-5 | 测试 |
| C-66 | `tests/compare/APPK-2/schema_snapshot.py` | 新增 | PRE-1：按附表 A.1 采集 SNAP-1～SNAP-7 并输出差异行数 | AC-15、AC-18、KPI-2 | 测试 |

## 6. 数据与接口

**数据库**：不涉及——本设计不读写任何表，不改结构。

**文件与配置（本版的“数据”）**——下列取值即 OP-15 的答案：

| 编号 | 项 | 取值 |
| --- | --- | --- |
| DEC-1 | 仓库内模板包位置 | `third_party/maccms-template-v2020.1000.1035/template/default_pc/`、`…/template/default_wap/`；目录树 SHA-1 必须分别等于 `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7`、`a211f900e060c1d405f2c9d75523e02abac45b80`，`…/template` 等于 `9398a1ed539cc754b7513c76e12134b3b5b49a53` |
| DEC-2 | 部署时模板包位置（Java 生产部署与 PHP/Java 对照环境相同） | 站点根下 `template/default_pc/`、`template/default_wap/`，从 DEC-1 原样复制；站点根 = PHP 的 `ROOT_PATH` = Java 的 `MACCMS_LEGACY_CONFIG_DIR` 上一级。对照环境的站点根里 `template/` 下只有这两个目录 |
| DEC-3 | `extra/maccms.php` 的 `site` 节 | `template_dir='default_pc'`、`html_dir='html'`、`mob_template_dir='default_wap'`、`mob_html_dir='html'`、`ads_dir='ads'`、`mob_ads_dir='ads'`（两个 `info.ini` 的 `adsdir` 都是 `ads`，与后台保存时的改写结果一致，保存配置不会让它漂移）；`install_dir` 保持基线 `'/'`。由此推出的路径量见下表 PATH-1～PATH-4 |
| DEC-3a | `mob_status`（仅对照环境） | `'2'`。生产部署的 `mob_status` 由站点自己的配置决定，本设计不改 |
| DEC-4 | 对照环境 | 见 §7.3 ENV-1～ENV-8 |
| DEC-5 | 后台模板管理目录（FR-7） | 见 §6.1 |
| DEC-6 | 静态生成读写目录（FR-6） | 见 §6.2 |
| DEC-7（v3） | PHP 模板兼容层实现方式 | 移植编译流水线 + PHP 子集解释器（§4.6 方案 C、§6.3～§6.7） |
| DEC-8（v3） | `/template/**` 静态资源 | Java 带白名单提供（§4.8、§6.10），关闭 OP-T1 |
| DEC-9（v3） | 应用层缓存与对照隔离 | CACHE-1～CACHE-4、ENV-9（§4.9、§6.18），关闭 OP-T2 / V9-001 |
| DEC-10（v3） | 安装向导建表账号 | 表单账号只用于第 5 步，运行期用 `MACCMS_DB_*`（§4.10、§6.14） |

**DEC-3 推出的路径量**（`install_dir='/'`；公式出处 `Init.php:50-53,59-62`、`All.php:62-63`；“手机”列适用于手机 UA 且 `mob_status=2`，以及静态生成 `ac2=wap`）

| 编号 | PHP 名 | 含义 | 桌面 | 手机 | Java 对应（C-12～C-14 之后） |
| --- | --- | --- | --- | --- | --- |
| PATH-1 | `MAC_ROOT_TEMPLATE`、`template.view_path` | 模板读取根（文件系统，相对站点根） | `template/default_pc/html/` | `template/default_wap/html/` | `"template/" + templateDir + "/" + htmlDir + "/"` |
| PATH-2 | `MAC_PATH_TEMPLATE` = 模板变量 `{$maccms.path_tpl}` | 模板包 URL 前缀（不含 `html_dir`） | `/template/default_pc/` | `/template/default_wap/` | `Result.pathTemplate`；`SiteVariant.pathTpl` |
| PATH-3 | `MAC_PATH_TPL` | 模板读取根的 URL 形式，只用于“模板文件不存在”消息 | `/template/default_pc/html/` | `/template/default_wap/html/` | `Result.pathTpl` |
| PATH-4 | `MAC_PATH_ADS` = 模板变量 `{$maccms.path_ads}` | 广告脚本 URL 前缀 | `/template/default_pc/ads/` | `/template/default_wap/ads/`（包内没有该目录，两侧都按文件不存在处理，不得创建） | `Result.pathAds`；`SiteVariant.pathAds` |

所以模板里 `{$maccms.path_tpl}css/style.css` 在桌面输出 `/template/default_pc/css/style.css`，正好指向包内 `template/default_pc/css/style.css`（附表 E.3）；若误用 PATH-3，会输出不存在的 `/template/default_pc/html/css/style.css`。

### 6.1 后台模板管理（FR-7、AC-8、AC-9、附表 D 的 D-20）

按 PHP `Template.php` 的目录（基线 Java `AdminTemplateActions.java:36-44,168-200` 也由同样的 `template_dir`/`html_dir`/`ads_dir` 拼目录，不涉及 PATH-2/PATH-3；两侧是否一致以 V-7 对照为准）：

| 入口 | 读写目录（相对站点根） |
| --- | --- |
| `template/index`（无参数） | 浏览根 `template/`，可逐级进入 `template/default_pc/…`、`template/default_wap/…`（手机模板经此浏览编辑） |
| `template/index?current=1` | `template/default_pc/html/` |
| `template/index?label=1` | `template/default_pc/html/label/`（包内有 `label/rank.html`） |
| `template/index?ads=1` | `template/default_pc/html/ads/`（PHP 原样：在 `html/` 下找 `ads/`，包内没有，列表为空） |
| `template/ads` | `template/default_pc/ads/*.js`（包内 `aaa.js`）；目录不存在时创建——只会创建桌面目录，不会在 `default_wap` 下建 `ads/`。页面上的“调用代码”是字面 `<script src="{$maccms.path_ads}aaa.js"></script>`（Java `admin/pages/template/ads.jte:37` 相同），前台输出时按 PATH-4 展开为 `/template/default_pc/ads/aaa.js` |
| `template/info`、`template/del`（编辑、保存、删除） | 只在 `template/` 区域内；写入沿用 `LegacySiteFiles` 原子写（BR-6） |
| 系统配置页“模板目录”下拉 | `template/` 下的子目录：对照环境为 `default_pc`、`default_wap` |

编辑扩展名、安全过滤、并发冲突提示、标签向导见 §6.11（v3）。

### 6.2 静态生成（FR-6、AC-7）

| 项 | 取值 |
| --- | --- |
| 读模板（桌面，`ac2` 不是 `wap`） | `template/default_pc/html/` |
| 读模板（`make/index?ac2=wap`） | `template/default_wap/html/`（`Make.php:16-25`） |
| 生成页里的模板变量 | 桌面 `{$maccms.path_tpl}`=`/template/default_pc/`、`{$maccms.path_ads}`=`/template/default_pc/ads/`；`ac2=wap` 时 `/template/default_wap/`、`/template/default_wap/ads/`（`Make.php:21-23` 重设后由 `label_maccms` 取值） |
| 写输出 | 站点根下的相对路径，与 PHP `buildHtml(..., './', ...)` 相同（`index.html`、`wap_index.html`、`map/index.<suffix>`、`rss/*`、按 `view`/`path` 规则的分类、详情、专题页）；Java 经 `StaticFiles` 写入 |
| Java 独有的登记文件 | `application/data/backup/static-pages/generated.txt`（B16 回滚用），**不属于生成的静态页**，CMP-4 比较时排除（ENV-7） |
| 对照时“事先清空的输出目录”（AC-7） | 每个 K- 用例的每一侧单元开始前，用 `pristine/` 重新复制出该侧站点根（ENV-6），而不是在站点根里挑文件删除 |

**接口**：v2 不新增接口；v3 新增 `/template/**` 静态资源（§6.10）、恢复 PHP 已有的 `ajax/pwd`（§6.8 ALIGN-4），前台未匹配路径改为 PHP 404 页（ALIGN-2）。静态资源 URL 由 PATH-2、PATH-4 固定，由 Java 提供（OP-T1 已按 DEC-8 关闭）。

### 6.3 模块边界与对外接口（v3 新增）

| 模块 / 包 | 职责 | 可以依赖 | 不可以 |
| --- | --- | --- | --- |
| `phptemplate.engine` | ThinkPHP 编译流水线的 Java 移植：模板文件定位、`literal`/`include`/`php` 预处理、标签库（`Maccms` 预加载、`Cx` 内置）、普通标签 `{$…}`/`{:…}`，产出“编译产物”（与 PHP `runtime/temp/<md5>.php` 同形的 PHP 文本 + 来源行号表） | `phptemplate.runtime`（只用其 PHP 子集解析器类型）、`legacyconfig.application`（`LegacySiteFiles` 的现有方法 `read` 读模板——`template/` 已在 `RW_AREAS`，不用 `readTemplateAsset`；`LegacyConfigService` 读配置） | 访问数据库；执行任何代码 |
| `phptemplate.runtime` | PHP 子集解释器：把编译产物切成“字面段 + 代码段”，按 RULE-WS 规则处理空白，解析代码段为语法树并执行；PHP 7.3 值语义（`ZValue`：null/bool/int/float/string/有序数组；字符串化、弱类型比较、数组键规范化、`echo` 规则） | 只依赖 JDK | 读文件、访问数据库、反射调用任意 Java 方法（函数只经 FUNC 白名单表分派） |
| `phptemplate.application` | 对外服务 `PhpTemplateRenderer`；`mac_*` 等模板函数的 Java 实现（`TemplateFunctions`）；19 个标签对应的旧模型方法 `listCacheData`（`TagModels`）；页面变量 `label_maccms` 等（`PageVariables`）；编译缓存（`CompiledTemplateCache`）；“模板文件不存在”页（`TemplateNotFoundPage`） | `content.application`（含 `.view`，复用已实现的旧标签条件 `VideoLegacyConditions`/`LegacyTagAttributes`/`LegacyRange`、URL 生成）、`member.application`、`comment.application`、`common.lang.LegacyLang`、`legacyconfig.application` | 依赖 `site`、`admin`、`staticgen` |
| `phptemplate.infrastructure` | 标签查询中现有 `content.application` 不提供的只读查询（Mapper + XML，只 `SELECT`，表与列按附表 D 的 D.6 TAGMAP） | MyBatis | 写入（不登记 `WriteRegistry`） |
| `site`（改） | 页面控制器保留现有数据与业务逻辑（BR-4），把“返回 jte 视图”改为“调用 `PhpTemplateRenderer.render(模板名, 页面变量)`”，HTML 响应只经 `SitePageResponses`、跳转提示只经 `JumpPages`（v6，C-70）；`/template/**` 静态资源（DEC-8，文件只经 `LegacySiteFiles.readTemplateAsset`，C-44） | 增加对 `phptemplate.application` 的依赖（护栏同步，C-35） | 读模板文件、编译模板 |
| `staticgen`（改） | 内容仍只经 `site.application.StaticPageSource`（进程内游客 GET），所以静态页自动改用 PHP 模板；`ac2=wap` 按手机变体请求 | 不变 | 不变 |
| `admin`（改） | 模板管理：扩展名白名单回到 `html/htm/js/xml`，过滤规则回到 PHP，标签向导生成 `{maccms:…}`；数据库工具四项；系统配置不变 | 不变 | 不变 |

**对外接口（Java 内部，不是 HTTP 接口）**

| 接口 | 签名（边界） | 错误语义 |
| --- | --- | --- |
| `PhpTemplateRenderer.render` | 入参：`TemplateRequest(SiteVariant variant, String templateName, PageVars vars)`；`templateName` 形如 `vod/detail`（同 PHP `fetch` 的参数，不带后缀）；返回 `RenderedPage(int status, String contentType, byte[] body)` | 模板文件不存在 → 返回 `TemplateNotFoundPage`（与 PHP 一致的状态码与页面，消息按 PATH-3 删前缀）；编译或执行遇到不支持的写法 → `TemplateUnsupportedException(file, line, 片段)`，由站点异常处理转成 500 页面，日志写模板文件、行号与片段（FR-2）；PHP 本身也会出错的情形（BR-3）→ 按 PRE-2 基线给出 ThinkPHP 错误页 |
| `PageVars` | 有序键值（`ZValue`），由页面控制器按 PHP 控制器 `assign()` 的键名与层级装配（FR-4 ②）；`maccms` 键由 `PageVariables.labelMaccms(variant, request)` 生成 | 键名、层级、取值以 PHP 为准（AC-4） |
| `TemplateFunctions` | 名称 → Java 实现的白名单表；未登记的函数名 → `TemplateUnsupportedException` | 同上 |
| `TagModels.listCacheData(model, tagParams)` | `model` ∈ `Vod`/`Art`/`Topic`/`Actor`/`Role`/`Website`/`Comment`/`Gbook`/`Type`/`Link`/`Extend`（筛选项 7 个标签）；返回与 PHP 同结构的 `ZValue` 数组（`list`、`page`、`pagecount`、`total`、`pageurl` 等键以 PHP 模型为准） | 属性非法、越界：与 PHP 模型的处理一致（BR-5、AC-3） |
| `LegacySiteFiles.readTemplateAsset(String relative, int maxBytes)`（v5） | 只读；唯一调用方是 `site.web.TemplateAssetController`（C-43）；`relative` 是站点根相对路径，须以 `template/<目录>/` 开头；放行范围为 OP-T5=a 的 SEC-7 白名单（§6.10、C-44），不设运行时开关 | 不放行一律抛 `FileException`（`INVALID_NAME`/`OUTSIDE_ROOT`/`FORBIDDEN`/`NOT_REGULAR_FILE`/`IS_DIRECTORY`/`TOO_LARGE`），消息不含路径与内容；调用方统一返回 404，不区分原因 |
| `SitePageResponses.page(RenderedPage, CacheMode, boolean noindex)`（v6，`site.web` 包内） | 唯一把渲染结果变成 HTML 响应的地方；调用方：全部页面控制器、`JumpPages`、`SiteExceptionHandler`、`SiteVerifyGate`、`SitePlaceholderController`、测试 `SiteTestRoutes` | 不抛业务异常；`RenderedPage` 为空字节时照常输出 `Content-Length: 0`（`SiteMemberFinance` 的空响应） |
| `JumpPages.error/success(SiteVariant, String msg, String url, int wait)`（v6，`site.web.legacy`） | PHP `$this->error()/success()` 的唯一入口；调用方：`SearchThrottle`（ALIGN-6）、`SiteCounterActions`（ALIGN-7）、ALIGN-3 的各提示页 | `public/jump` 缺失 → `TemplateNotFoundPage`（同 PHP） |
| `DatabaseRuns.begin/step/finish/recover/acknowledge`（v6；v7 改为 OS 文件锁与状态机，`admin.application`） | DBT-5 的锁、运行记录与状态机；只由 `DatabaseToolService` 调用；`LegacySiteFiles.tryExclusiveLock` 只由它调用 | 拿不到锁 → “已有数据库操作在执行”；记录处于待处理状态 → “上次操作未处理”（只放行 `recover`/`acknowledge`）；两者都不发任何语句 |
| `CacheResetHook.resetAll()` | 清空 `phptemplate` 的编译缓存、标签结果缓存、页面缓存，以及 `LegacyConfigService` 的派生缓存 | 只由后台“清理缓存”入口和对照开关（CACHE-3）调用 |

### 6.4 编译与空白规则（逐字节一致的关键，v3 新增）

| 编号 | 规则 | PHP 出处 |
| --- | --- | --- |
| TPL-1 | 模板读取根 = PATH-1；`fetch('a/b')` 读 `<PATH-1>a/b.html`；`{include file="public/include"}` 同根解析，`file` 支持逗号分隔多个文件、`[k]` 参数文本替换、`$` 开头参数取当次渲染变量 | `Template.php:466-490`（`parseInclude`）、`parseTemplateName` |
| TPL-2 | 流水线顺序固定：`literal` → `extend` → `layout`（`layout_on` 为假，只删 `{__NOLAYOUT__}`）→ `include` → `literal` → `php`（短标签 `<?` 改写）→ 预加载标签库 `Maccms` → 内置标签库 `Cx` → 普通标签 → 还原 `literal` | `Template.php:360-410` |
| TPL-3 | 合并相邻代码块：编译产物上执行与 PHP 相同的正则 `\?>\s*<\?php\s(?!echo\b)`（`s` 修饰），删除匹配段 | `Template.php:341` |
| TPL-4 | 编译产物头部加一段与 PHP 等价的“头”代码块，其后的 `\n` 按 TPL-5 被吃掉（结果：头不产生输出） | `Template.php:345` |
| TPL-5 | 执行时，代码段结束符 `?>` 之后紧跟的一个换行（`\n`，或 `\r\n` 整体）不输出——PHP 语言规则 | PHP 词法 |
| TPL-6 | `strip_space=false`：不做空白压缩。`tpl_replace_string` 替换在**编译阶段**、TPL-3 之后对编译产物执行（不是对渲染输出）：`__ROOT__`、`__URL__`、`__STATIC__`、`__CSS__`、`__JS__`（`View.php:44-51` 按请求根计算，合并为空的 `view_replace_str`），所以只改写模板文本里的这些字样，不影响来自数据的输出；对照环境请求根为空串 | `Template.php:37,343-344`；`View.php:44-51,161-162` |
| TPL-7 | `app.compress=1` 时对整页执行 `mac_compress_html`（Java 逐字移植该函数）；基线 `extra/maccms.php:60` 为 `'0'` | `All.php:41-56` |
| TPL-8 | 编译缓存：键 = 模板绝对路径；缓存项记录本次编译读到的全部文件（主模板 + `include` 链）的修改时间与大小，任何一个变了就重编译（与 PHP `checkCache` 比较 include 文件时间的效果相同），保证 W-04“保存后不重启即生效” | `Template.php:257-290` |
| TPL-9 | 行号：编译时维护“编译产物偏移 → (模板文件, 行号)”表，`include` 拼接与每次替换都更新偏移；解释器报错时按代码段起点查表，日志写 `模板文件:行号` 与该行原文（不写变量取值） | FR-2、§11 |
| TPL-10 | `{php}…{/php}`、`<?php … ?>` 原样进入编译产物，由解释器执行；PRE-5 列出的 6 个文件里的代码都在解释器子集内（RT-1）；子集外的语法报 `TemplateUnsupportedException` | 附表 E.4 EF-2；OP-4 |
| TPL-11 | 未知的 `{maccms:xxx}`（`path`、`siteaid`、`sitetid`、`siteid`，EF-4）：`Maccms` 标签库不认识的标签在 ThinkPHP 里**不被替换**，按原文进入编译产物；随后普通标签解析不匹配 `{maccms:` 开头，所以原文输出。Java 按同一规则自然得到相同结果，由 V-12 的编译产物对比与 AC-4 证实 | `TagLib.php` 标签匹配规则 |

### 6.5 PHP 子集解释器与值语义（v3 新增）

| 编号 | 规则 |
| --- | --- |
| RT-1 | 支持的语法 = 编译产物会出现的全部写法 + PRE-5 登记的模板内 PHP 代码：`echo`、赋值（含 `.=`、`++`）、`if/elseif/else`、`foreach ($a as $k=>$v)`、`for`、`switch/case/break/default`、三元与 `?:`、`isset`/`empty`、数组字面量 `[]`/`array()`、数组下标与 `->` 属性取值（对象只出现在 `model(...)->listCacheData` 调用，解释器把它分派给 `TagModels`）、字符串字面量（单/双引号与 `\` 转义、双引号内 `$var` 插值）、`.` 连接、算术与比较、逻辑运算、函数调用。其他语法（类定义、`include`/`require`、`eval`、反引号、可变函数名、`$$`）一律报不支持 |
| RT-2 | 值类型：`null`、`bool`、`int`（64 位）、`float`（双精度）、`string`（**字节串**，按 UTF-8 字节存，字符串函数按字节操作，与 PHP 一致）、有序数组（键为 int 或 string，`"1"` 规范化为 int 1，规则同 `legacyconfig.application.view.PhpArray.normalizeKey`） |
| RT-3 | 字符串化：`true`→`"1"`、`false`/`null`→`""`、`int` 十进制、`float` 按 `precision=14`（PHPB-7）的 `%.14G` 规则并去掉多余的 0、`-0` 输出 `-0`、数组→`"Array"`（PHP 同时写 Notice 到日志，不进响应体） |
| RT-4 | 比较：`==`、`!=`、`<`、`>` 等按 **PHP 7.3** 的弱类型规则（数字串与数字比较按数值；非数字串与 0 比较时 0 先转串——即 7.3 的 `"abc" == 0` 为真）；`===` 类型与值都相同；`in_array` 默认弱比较 |
| RT-5 | 未定义变量 / 下标：取值为 `null`（输出空串），与 PHP 7.3 在 `display_errors=Off` 下的响应体一致（Notice 只进日志）；对字符串做下标取单字节 |
| RT-6 | 数据库行的值类型：按 PHP 侧 ThinkPHP + PDO mysqlnd 的实际返回为准（整数列可能是 `int`、小数列 `string`、`NULL` 为 `null`）；Java 由 `TagModels` 统一转换。转换表以 PRE-2 的黄金向量为准（RT 黄金，V-11），不凭推断 |
| RT-7 | 函数分派：只调用 `TemplateFunctions` 白名单。白名单 = Cx/Maccms 编译产物会生成的内部函数（如 `is_array`、`count`、`array_slice`、`isset`、`empty` 等，以编译器代码为准）+ PRE-5 扫描出的模板函数（附表 E.4 EF-5 的 `url`、`mac_url_*` 等）。每个函数一个 Java 实现，返回值以 `php/` 的 `2023.1000.3051` 在 PHP 7.3.33 下的结果为准（AC-4） |
| RT-8 | 资源上限：单次渲染循环总次数、输出字节数、递归深度设上限（默认 1,000,000 次、32 MiB、64 层，配置项 `maccms.phptemplate.limits.*`），超限按不支持处理并返回 500；防止模板写错拖垮进程（PHP 由 `max_execution_time` 兜底） |

### 6.6 标签库（FR-3，v3 新增）

- 编译：`Maccms.php` 的 19 个标签逐个移植其 `tagXxx` 方法，产出与 PHP 相同的代码文本（含 `$__TAG__` 参数串、`$__LIST__`、分页变量），由 V-12 逐字比对编译产物。
- 执行：编译产物里的 `model("Vod")->listCacheData($__TAG__)` 由解释器分派到 `TagModels`，实现按 `php/application/common/model/<模型>.php` 的 `listCacheData`/`listData` 移植：属性解析、`order`/`by` 映射、筛选条件、`num`/`start`、`paging`（`page`、`pageurl`、`half`）、`not`/`ids`/`rel` 等，查询的表、列、排序列、筛选列严格按附表 D 的 D.6 TAGMAP-1～TAGMAP-19（AC-16）。已有的 `content.application.VideoLegacyConditions`、`LegacyTagAttributes`、`LegacyRange`（B05）覆盖的条件直接复用；不覆盖的由 `phptemplate.infrastructure` 的只读 Mapper 补齐（只 `SELECT`）。
- 行数据装配：每行的派生键（如 `type`、`type_1`、`group`、`vod_play_list` 等，以 PHP 模型代码为准）在 `TagModels` 里补齐，取值类型按 RT-6。
- `cachetime`：只在 `app.cache_core=1` 时缓存结果（键规则同 PHP：`cache_flag` + 标签参数），对照环境 `cache_core=0`（基线值），所以不缓存（CACHE-1）。
- 随机排序 `by="rnd"`：沿用 `content.application.VideoRandom` 的旧版算法；对照样本按 F.6 的 DATA-2 构造（匹配行数 ≤ 1），不靠豁免。
- 搜索优化写 `mac_vod_search`（TAGMAP-16）：沿用 Java 现有的写入登记（B05/D-01），不新增写入。

### 6.7 函数与页面变量（FR-4，v3 新增）

- 函数清单 = PRE-5（`docs/test/APPK-2/template-scan.md`）。实现位置 `phptemplate.application.TemplateFunctions`，`mac_url_*` 优先委托 `content.application` 已有的旧 URL 生成（`09-roadmap.md` 第 3.2 节；BR-4 不改 URL 规则），`url()` 按 ThinkPHP `Url::build` + `route.php` 的旧规则实现（只实现模板实际用到的参数形态）。
- 页面变量：`PageVariables.labelMaccms` 逐键移植 `All.php:58-110` 的 `label_maccms()`（含 PATH-2 的 `path_tpl`、PATH-4 的 `path_ads`、EX-1 的 `date`、EX-2 的 `http_url`）；`$obj`、`$param`、`$type`、`$page` 等由各页面控制器按 PHP 控制器的 `assign()` 装配（逐页清单在 FR-5 各页面子任务里列出，AC-4 验）。
- 语言包：`{:lang('…')}` 委托现有 `common.lang.LegacyLang`（B12，按 `app.lang` 读 `application/lang/zh-cn.php`、`zh-tw.php`），3546 个键逐字节对照（KPI-5，V-17）。

### 6.8 前台页面接入（FR-5、FR-8，v3 新增）

**做法**：路由与业务逻辑不动（BR-4：URL 解析、权限、计数、积分、密码校验仍用现有 Java 服务）；每个页面控制器把“组 jte 视图模型”改为“组 PHP 页面变量（`PageVars`）+ 模板名”，交给 `PhpTemplateRenderer`。页面变量里的整行数据取自 `content.application.LegacyRowReadService`（已按 PHP 整行返回，D.7），派生键按 PHP `All.php` 的 `label_*` 函数补齐（新增 `site/web/legacy/` 下的页面装配类，一个 PHP `label_*` 函数对应一个方法）。

| 编号 | 页面组（附表 B.1.1 编号范围以 PRE-3 为准） | 现有控制器 | PHP 对照 | 模板名规则 |
| --- | --- | --- | --- | --- |
| PG-1 | 首页 | `SiteContentController` | `index/controller/Index.php:13` | `index/index` |
| PG-2 | 视频分类、筛选、搜索、详情、播放、下载、播放器、版权、剧情、角色、RSS、ajax 片段 | `SiteContentController`、`SiteSearchController`、`SiteVodShowController`、`SitePlaybackController`、`SiteLibraryController`、`SiteMiscController` | `index/controller/Vod.php:14-131`；`All.php:398-564` | `mac_tpl_fetch('vod', type_tpl/type_tpl_list/vod_tpl/vod_tpl_play/vod_tpl_down, 默认名)`（F-D3 覆盖点） |
| PG-3 | 文章、专题、演员、角色、网址、剧情 | `SiteLibraryController`、`SiteModuleListController` | `Art.php`、`Topic.php`、`Actor.php`、`Role.php`、`Website.php`、`Plot.php` | 同上，各自 `*_tpl` |
| PG-4 | 评论、留言 | `SiteInteractionController` | `Comment.php`、`Gbook.php` | 字面名；`comment/ajax`、`gbook/ajax` 返回 JSON（FR-5） |
| PG-5 | 会员中心 28 页、登录注册找回 | `SiteUserController`、`SiteMemberFinance`、`SiteMemberBoards`、`SitePaymentController` | `User.php` | 字面名 |
| PG-6 | 地图、RSS、帮助、公告、搜索聚合、自定义标签页 | `SiteMiscController`、`SiteAjaxController`（`label/**`） | `Map.php`、`Rss.php`、`Help.php`、`Note.php`、`Search.php`、`Label.php:19-22` | 字面名；`label/<文件名>`（F-D1，文件名含 `/` 或不存在按 PHP 非法请求） |
| PG-7 | 公共提示页：站点关闭、404、跳转、验证码页、浏览器提示 | `SiteExceptionHandler`、`SiteStatusInterceptor`、`SiteVerifyGate` | `Base.php:39,67,77,87`；`All.php:98-116` | `public/close`、`public/<app.page_404，空则 jump>`（F-D2）、`public/verify`、`public/browser` |

**与 PHP 输出不一致、须随 FR-5 改成 PHP 行为的点**（“PHP 无漏洞即以 PHP 为准”；六项安全修复除外，见 §6.17）：

| 编号 | Java 现状 | 改为 | 依据 |
| --- | --- | --- | --- |
| ALIGN-1 | 站点关闭返回 503 + `Retry-After` | 状态码、页面按 PHP `public/close` | `Base.php:77`；比较项 CMP-3 ① |
| ALIGN-2 | 前台未匹配地址返回 JSON 404“接口不存在” | 站点路径（`/api/v1/**`、`/api.php/**`、后台入口之外）按 PHP `_empty` → `page_error`（404 + `public/<page_404>`）；`/api/v1/**` 仍为 JSON | `Base.php` `_empty`；`All.php:98-116` |
| ALIGN-3 | 错误页、跳转页是 jte 自绘（`ErrorPage`、`UserJumpPage`） | 一律经模板包的 `public/jump`、`public/msg` 或 PHP 内置视图（`php/application/index/view/public/jump.html`、`msg.html`，按字节随 jar 打包为资源，经同一引擎渲染）；模板文件缺失时按 TPL“模板文件不存在” | B.1.2“内置视图”；`Init.php:67-68` |
| ALIGN-4 | 观看密码：签名 cookie，`ajax/pwd` 返回 404；按内容/全站限流 `PasswordAttempts`，超限 429 | 恢复 `ajax/pwd`，判定顺序与 JSON 逐条按 PHP：① 参数校验 1001；② 本会话已通过 1002；③ 按会话 `last_pwd` 间隔 < 5 秒 1003（每次调用都刷新 `last_pwd`，含被拒的调用，同 `mac_get_time_span`）；④ 取数失败 1011/1021、密码不符 1012/1013/1014/1022；⑥ 通过后写会话并返回 `{"code":1,"msg":"ok"}`。“已通过”与 `last_pwd` 都放进程内会话（`SessionStore`，PHP 是 session，不进库、不参与比较）。⑤ **OP-T4=a（v8 已关闭）**：在 ③ 之后、④ 查库之前再过 `PasswordAttempts`，超限返回与 ③ 相同的 `{"code":1003,"msg":lang('index/pwd_frequently')}`（HTTP 200，不再用 429），与 PHP 的差异只在“哪一次请求得到 1003”，记为 SEC-9（C-68） | `Ajax.php:400-456`（限制在 `:416-418`）；`common.php:306-316`；`All.php:547-549` |
| ALIGN-5 | 响应头 `Content-Type` 由各控制器设；`SiteHtmlRenderer` 另发 CSP | 统一按 PHP：HTML 页 `text/html; charset=utf-8`，RSS/地图 XML 与 ajax JSON 按 PHP 控制器（PRE-2 基线记录实际值）；HTML 页只经 C-70 `SitePageResponses` 输出，类型常量只用 `SiteRequests.HTML_UTF8`；CSP 只由 C-75 提供，值为 OP-T6=b 的三项（DEC-11，v8） | CMP-3 ② |
| ALIGN-6（v6） | 搜索过快：`SearchThrottle` 返回 429 + `Retry-After` 的 jte 提示页 | 按 PHP `check_search`：`echo $this->error(lang('search_frequently').search_timespan.lang('seconds'))` → `public/jump`，HTTP 200；计时规则（第 1 页、被拒也刷新）不改 | `Base.php:59-61`；`SearchThrottle.java` 类注释“与旧版的差异” |
| ALIGN-7（v6） | 计数接口非 ajax 的 POST 返回 jte 提示页（D-B11-10，为 jte 无脚本表单所加） | jte 表单随 PG 组删除；`hits/digg/score/referer` 一律按 PHP 返回 JSON（`Ajax.php:152-460` 各方法都 `return json(...)`）；`verify_check` 非 ajax 时按 PRE-2 基线记录的 PHP 实际输出（PHP 返回数组，非 ajax 请求由 ThinkPHP 按默认输出类型处理），附表 C 对应行由 T-FR12 判定 | `Ajax.php:302-346,460-475`；`SiteCounterActions.java:41-45,329-350` |

页面变量的逐页清单（每页 `assign` 的键、层级、取值来源）在各 PG 子任务的设计说明里按 PHP 控制器逐行列出，由 AC-4/AC-5 的样本验证；不允许出现 PHP 没有的键（会改变 `{if isset}` 类分支）。

**`SiteHtmlRenderer` 调用方迁移表（v6，REV-8；基线 `git grep -n 'SiteHtmlRenderer' a57e9cb -- src/main src/test` 的全部结果）**

| 调用方 | 基线用法 | 处理 | 子任务 |
| --- | --- | --- | --- |
| 13 个页面控制器（C-37 列出） | 注入渲染器，`ok/noStore/member/memberPlain/memberGatewayForm/playerFrame` | 改 `PhpTemplateRenderer` + `SitePageResponses`；`noStore` 类页面传 `NO_STORE`，会员页不再生成 nonce；支付宝下单页不再改写 CSP 的 `form-action`（页面内容按 PHP 对应输出，PRE-2 基线；CSP 统一取 C-75） | S3-14a～g 各组 |
| `SiteExceptionHandler`、`SiteVerifyGate`、`SitePlaceholderController` | 注入渲染器，`error(...)` | ALIGN-1～3；经 `JumpPages`/`PhpTemplateRenderer` + `SitePageResponses`；兜底文本常量移入 `SiteExceptionHandler` | S3-14a |
| `SearchThrottle` + `SiteConfiguration.siteSearchThrottle` | 注入渲染器，`error(429, …, Retry-After)` | 改注入 `JumpPages`，ALIGN-6（C-71、C-42） | S3-14b（`SiteSearchController` 先用到；`SiteModuleListController` 在 S3-14d 只是继续注入同一个 Bean） |
| `SiteCounterActions`（`SiteAjaxController:88` 构造） | 注入渲染器，非 ajax POST 用 `memberPlain` | 删渲染器依赖，ALIGN-7（C-71）；`SiteAjaxController` 构造处同步去掉该实参 | S3-14g |
| `PlaybackPages` | 静态引用 `CONTENT_SECURITY_POLICY`（`strictFramePolicy`） | 删 `strictFramePolicy`、`Frame.policy`；授权与地址判定保留（C-71） | S3-14c |
| `SiteMemberFinance:525`、`SitePaymentController:104` | 静态引用 `HTML_UTF8` | 改 `SiteRequests.HTML_UTF8`（C-37） | S3-14f |
| `SiteUserController:95`、`SiteVerifyController:42` | 私有同值常量 | 删，改 `SiteRequests.HTML_UTF8`（C-37） | S3-14f、S3-14a |
| `SiteConfiguration:37,355-361` | 装配渲染器与 `OnlineTemplates` | 删（C-42） | S3-15 |
| 测试 `SiteTestRoutes` | 注入渲染器，`/boom-large` 模拟半页失败 | 改注入 `SitePageResponses`，用渲染器替身保留“半页失败 → 完整 500 页”断言（C-72） | S3-14a（`SiteExceptionHandler` 改动的同一提交） |
| 测试 `SiteConfigurationTest:52,62` | 断言有/无 `SiteHtmlRenderer` Bean | 改断言 `PhpTemplateRenderer`、`SitePageResponses`（C-72） | S3-15 |
| 测试 `PlaybackSwitchCases:101`、`SitePlaybackPagesMvcTest:73`、`SiteAjaxFragmentsMvcTest:84`、`SiteFinanceContentMvcTest:140`、`SiteVodListPagesMvcTest:199` | 断言 CSP 等于常量 | 改为断言 CSP 等于 C-75 的值（为空时无该头）、`Content-Type` 正确（C-72），或随 C-46 整类删除 | 分别随 S3-14c、S3-14g、S3-14f、S3-14b |

S3-15 删除 `SiteHtmlRenderer` 之前，上表其余各行必须都已合入；S3-15 的门禁是 V-32 ① 的检索结果为空。

**前台 CSP（v8，REV-13，OP-T6=b）**：C-75 是唯一来源，自 S3-14a 起所有前台 HTML 页发 `object-src 'none'; base-uri 'self'; frame-ancestors 'self'`。三项都不限制脚本，模板内联脚本（如 `public/jump.html:23` 倒计时）、`player.js` 的 `document.write`、支付网关自动提交表单都能在浏览器中运行（V-32 ⑥）；页面对照（CMP-3 只比状态码、`Content-Type`、`Location` 与正文）不受影响。v7 的“过渡期严格策略 + S3-14h + 发布门禁”随 OP-T6 关闭撤销。

**手机（FR-8）**：手机/站群选择沿用 `SiteTemplateSelection` + `SiteVariants`（黄金锁定）；`PhpTemplateRenderer` 按 `SiteVariant` 选读取根（PATH-1）与 `path_tpl`/`path_ads`（PATH-2/4，C-12～C-16 修正后）。手机模板目录缺失时不回退桌面（`21-b12-admin-framework.md` 第 12 节）。语言键对照（KPI-5）直接比 `LegacyLang.get(key)` 与 PHP `lang(key)`，不经页面。

### 6.9 jte 前台与 D4 在线编辑下线（OP-3，v3 新增）

- 删除：`src/main/jte/site/**`（76 个）、`sitetemplate` 模块全部类、`SiteConfiguration.OnlineTemplates` 与 `SiteHtmlRenderer`、`site/web/view/*Page` 等只为 jte 服务的视图 record（逐个以“删除后编译通过”为准）、`pom.xml` 中 site 的 `jte-maven-plugin` execution 与 `site-jte` 资源拷贝、只在在线编辑用到的 `gg.jte:jte` 编译器依赖（后台、安装仍用预编译的 `jte-runtime`，保留）、`src/main/resources/static/site/**` 与 `static/site-player/player-boot.js`（只被 jte 页面引用；删除前用 `git grep` 确认模板包与 `php/static` 不引用）、`src/test/jte-probe/**`、`SiteTemplatePolicyTest`、`SiteTemplateServiceTest`。
- 改写：约 34 个断言 jte 页面的测试类（§3.2 列出）改为断言 PHP 模板输出（用 `third_party` 下的真实模板包做输入），或在页面黄金对照（AC-5）覆盖后删除；逐类决定写在对应 PG 子任务里，不留“暂时跳过”的测试。
- 下线是**按页面组逐批**做的：某 PG 组切到 PHP 模板并通过本组样本后，才删除该组 jte 文件与测试；全部 PG 完成后删 `sitetemplate` 与插件配置（C-40）。

### 6.10 `/template/**` 静态资源（DEC-8，关闭 OP-T1，v3 新增）

| 项 | 取值 |
| --- | --- |
| URL | `GET/HEAD /template/<目录>/<相对路径>`（`<相对路径>` 的第一段限 `css`/`js`/`images`/`ads`，见下行）；`<目录>` 必须是 `template/` 下实际存在的子目录（不限于 `default_pc`/`default_wap`，与 PHP+nginx 一样跟随配置） |
| 文件 | 站点根 `template/<目录>/<相对路径>`，只经 `LegacySiteFiles.readTemplateAsset`（C-44）读取：只读；路径规范化后真实路径仍在 `template/` 内；拒绝 `..`、反斜杠、空字节、控制字符；中间目录符号链接解析后出 `template/` 拒绝，最终文件是符号链接拒绝 |
| 子目录与扩展名（OP-T5=a，SEC-7） | 只映射 `css`、`js`、`images`、`ads` 四个子目录，扩展名白名单 `css js png jpg jpeg gif ico svg webp woff woff2 ttf eot swf`，其他（含 `html htm ini php txt`）返回 404。与 PHP 的差异（PHP 内置服务与 nginx 会把 `.html` 模板源码、`info.ini` 原样返回）按 SEC-7 记为已确认的安全差异。路径规范化、不出 `template/`（因而不出站点根）、拒绝符号链接出根、只读四条同时生效；放行规则只在 C-44 一处实现，C-43 不另设白名单 |
| 响应 | `Content-Type` 按扩展名固定表（只含白名单内的扩展名）；`Last-Modified` + 条件请求；不设缓存控制以外的额外头 |
| 位置 | `site/web/TemplateAssetController`（新增），只在 `maccms.site.enabled=true` 时装配 |

### 6.11 后台模板管理（FR-7，v3 新增；目录见 §6.1）

| 项 | 改为 | 依据 |
| --- | --- | --- |
| 扩展名白名单 | `html,htm,js,xml`（撤销 D-B15-14，`jte` 去掉、`htm` 恢复） | `Template.php:170`；W-06 |
| 内容过滤 | 规则文本与判定方式不变（`LEGACY_FILTER`，与 PHP 逐字相同，命中即拒绝，提示用 `admin/template/php_safe_tip`）；常量从 `SiteTemplateService` 移到 `admin.application.TemplateFileService` | `Template.php:177,192`；W-05 |
| 保存 | 经 `LegacySiteFiles.replace` 原子写 + 版本冲突检查（保留 B15 约定，W-07）；保存后由 TPL-8 的修改时间检查使下一次请求重编译（W-04） | BR-6 |
| 备份与恢复 | 删除 jte 专用的“覆盖前备份 / `op=restore`”（PHP 没有） | PHP 无此功能 |
| 删除 | 同 PHP：只能删 `template/` 内文件，路径校验同保存 | W-08 |
| 标签向导 | `wizard` 改为 PHP `admin@template/wizard` 的同一表单与生成规则，输出 `{maccms:vod …}…{/maccms:vod}` 片段（逐项按 PHP 视图里的 JS 生成逻辑移植，生成在浏览器端完成的部分照搬为后台静态脚本） | `Template.php:237`；W-09、AC-9 |
| 权限 | 沿用 `admin_auth` 的 `template/*` 权限码（W-10） | B12 |

### 6.12 静态生成（FR-6，v3 新增；目录见 §6.2）

- 页面内容仍经 `StaticPageSource` → `InternalPageRenderer`（进程内 GET），FR-5 完成后自动使用 PHP 模板；`ac2=wap` 时 `InternalPageRenderer` 用手机 `SiteVariant`（手机 UA 等价的选择结果），读 PATH-1 手机根，变量取 PATH-2/4 手机值。
- 按 PHP 补齐与 B16 的差异（PHP 为准）：`label` 生成（撤销 D-B16-5：用 `label/` 下文件逐个生成）、RSS 分页（撤销 D-B16-6）、`*_time_make` 写入扩到 `actor`/`role`/`website`（K-12，D-19；写入需在 `WriteRegistry` 登记这三张表的该列 `UPDATE`）、失败列表输出格式按 PHP（K-11）。
- 生成失败（模板报错）记入失败输出，继续下一页（K-11）。

### 6.13 数据库工具四项（FR-9，v3 新增）

| 编号 | 动作 | 设计 |
| --- | --- | --- |
| DBT-1 | 优化表、修复表 | 入参表名必须属于附表 A 的 26 张表（白名单常量 + 前缀校验），对每张选中的表各发一条 `OPTIMIZE TABLE <名>` / `REPAIR TABLE <名>`（表名用反引号，来自白名单而不是请求原文）；返回结果按 PHP 页面展示。`it_app` 的 `SELECT, INSERT` 权限足够执行这两条语句 |
| DBT-2 | 执行 SQL（v7 改回 PHP 整段语义，DEC-13，§4.13） | 输入当作**一整段**处理，不切分、不逐条执行。① 按 PHP `trim`；为空 → 不执行、返回“执行成功”（PHP 同）；把 `{pre}` 替换为表前缀（`Database.php:246-249`）。② **PHP 跳过判定，逐字照搬**：副本转小写、`/\*.*?\*/`（跨行）换空格、`--` 至行尾换空格、压缩空白并 `trim`；以 `select` 开头，或含 `outfile`、`dumpfile`、`load_file` 之一 → 不发送、返回“执行成功”（`Database.php:252-263`，提示文字也同 PHP）。所以 `SELECT 1; INSERT …` 整段不执行，含文件读写关键字的输入整段不执行。③ **多语句**：按词法扫描（识别单引号、双引号、反引号字符串及其中的 `\` 转义，`--`/`#`/`/* */` 注释），第一个不在字符串与注释里的 `;` 之后若还有非空白、非注释内容 → 不发送，提示“一次只能执行一条语句”。PHP 把整段交给数据库，数据库拒绝多语句，结果同为一条都不执行，只有提示不同。末尾单个 `;` 允许并原样发送（数据库同样接受）。④ **AC-11 结构保护**（OP-6 已确认的差异）：同一词法下去掉注释（含 `#`；出现 `/*!` 版本注释视为不可识别，拒绝），取首个关键字，只允许 `INSERT`、`UPDATE`、`DELETE`（`SELECT` 已在 ② 跳过；其他如 `REPLACE`、`WITH`、`(`、`DROP`、`TRUNCATE`、`ALTER` 一律拒绝），不通过 → 不发送，提示“结构类操作请由部署方在库外完成”。⑤ 通过后在 DBT-5 的锁内把整段（`{pre}` 已替换、文本不再改动）用一次 `Statement.execute` 发出，连接 `allowMultiQueries=false`、自动提交、不重试（PHP `break_reconnect=false`）；成功 → “执行成功”（PHP `run_ok`），不展示结果集。⑥ 失败按 DBT-5 ③ 分类，提示不回显 SQL 原文 |
| DBT-3 | 还原备份 | 读取备份文件（只限 `application/data/backup/database/` 下由本系统或 PHP 导出的分卷），按 `mac_parse_sql` 切分；`DROP TABLE`、`CREATE TABLE` 以及其他非 DML 语句**跳过不执行**并计数显示；**还原表集合**（v7 补全）= 备份里 `DROP TABLE`、`CREATE TABLE`、`INSERT INTO` 出现过的全部表（必须在 26 张白名单内）——PHP 导出对 0 行的表只写删表与建表，PHP 还原后该表为空，所以这些表也要清空；对集合中每张表先执行一次 `DELETE FROM <表>`，再执行该表在备份中的全部 `INSERT`（可能为 0 条）（“只还原数据”：结果与 PHP 删表重建后插入的数据相同，结构不动） |
| DBT-4 | 其余 | 列表、列、批量替换保持 B15 现状。导出（v7）在 DBT-5 的锁内执行（拿不到锁或记录待处理 → 拒绝，提示同 `lock_check`）。**删备份 `delete`（v8，REV-14）**：基线调用链 `AdminDatabaseActions.java:60`（`del`）→ `DatabaseToolService.delete`（`DatabaseToolService.java:255-273`：列目录后逐个 `files.delete`，不查任何锁与记录）。v8 顺序：① 入参校验同 B15（`id` 为空 → `select_del_file`；备份目录不合法 → `BAD_BACKUP_PATH`），不取锁、不删文件；② `tryExclusiveLock(dbtool.lock)`，拿不到 → 拒绝“已有数据库操作在执行”，0 个文件被删；③ 锁内先做中断判定（DBT-5 ⑩），再读运行记录：记录是 `IMPORT` 且处于待处理（含 `RECOVERED`），并且要删的前缀（`Ymd-His-`，与 B15 同样由 `STAMP` 从 `id` 算出）等于 `source_backup` 或 `auto_backup` 的前缀 → 拒绝“该备份是未处理数据库操作的恢复点”，0 个文件被删；④ 否则在锁内按卷号升序删除各分卷（中途被杀留下的残缺备份必然缺第 1 卷，还原预检 ② (a) 判“文件损坏”，不会被当作完整备份还原），返回与 B15 相同（`del_ok`/`del_err`）；⑤ 不写运行记录（删除不进入状态机），日志一行 `INFO`（`op=DELETE`、时间戳、结果）。PHP `del` 同样不查锁；PHP 与 Java 后台不同时使用数据库工具（RISK-T20） |
| DBT-5 | 失败、并发与恢复（v6，REV-7；方案见 §4.12） | 见下表，适用于 DBT-1～DBT-3；互斥（④）与中断判定（⑩）另适用于导出与删备份 |

**DBT-5 契约（DBT-1～DBT-3 共用）**

| 项 | 契约 |
| --- | --- |
| ① 事务能力 | 25 张 MyISAM 表没有事务，`mac_vod_search`（InnoDB）也不开事务（护栏禁止，§1.3）。所以 DBT-3 **没有整批原子性**：执行单位是一条语句；单行语句要么生效要么不生效，多行语句（`DELETE FROM 表`、多值 `INSERT`、条件命中多行的 `UPDATE/DELETE`）中途出错会留下部分结果。DBT-2（v7）只发一条语句，同样可能在多行语句中途出错时部分生效。不做自动补偿，也不自动重试（补偿会覆盖并发写入，12 文档第 4.3 节第 4 条） |
| ② 整批预检（发送任何语句之前全部完成，任一不通过 → 整批拒绝、发出语句数为 0） | DBT-1：表名都在 26 张白名单内。DBT-2：DBT-2 ①～④（v7）。DBT-3：(a) 分卷齐全（同 PHP `count($list) === $last[0]`，否则“文件损坏”）；(b) 全部分卷可读、`.gz` 可解压；(c) 全部语句切分完成，还原表集合（DBT-3）都在 26 张白名单内（否则整批拒绝）；(d) **站点已关闭**（`site.site_status` 为 0，经 `LegacyConfigService` 读）——否则提示“还原数据前请先在系统设置中关闭站点”，原因见 ④；(e) 磁盘可写（自动备份目录存在且可写）。`recover` 的预检另见 ⑨。**顺序（v8，REV-14）**：所有动作统一为“取锁（④）→ 中断判定（⑩）→ 状态检查（状态机）→ 预检 → 写 `RUNNING`”；DBT-3 与 `recover` 的预检要读备份文件，放在取锁之后，预检与执行之间备份不会被删除；预检不通过 → 释放锁、记录不变。预检只读，不发任何写语句；预检不能发现的只有运行期错误（数据不合列定义、主键冲突、连接中断） |
| ③ 执行与逐条状态 | 持锁（④）且预检通过后，`DatabaseRuns.begin` 写运行记录（`state=RUNNING`、操作类型 `op`、管理员编号、开始时间、语句总数、DBT-3 的原备份时间戳与表集合）。DBT-3 先记 `phase=AUTO_BACKUP`，接着做**还原前自动备份**：调用 B15 现有导出逻辑，把还原表集合导出到同一备份目录（文件名同 PHP 规则；若同一秒的分卷已存在则顺延到下一个不冲突的秒，不覆盖），每新建一卷先把文件名记入 `export_files` 再创建；写完后计算各分卷 SHA-256，连同时间戳、表清单记入 `auto_backup`，然后把 `phase` 改为 `RESTORE` 并落盘，之后才发第一条 `DELETE`（所以 `phase=RESTORE` 时 `auto_backup` 必然完整，`phase=AUTO_BACKUP` 时必然一条写语句都没发）；这次内部导出属于本次运行，已在锁内，不再查 DBT-4 的锁，与普通导出一样写 `backup.lock` 并把写入值记入 `backup_lock`。导出失败 → `state=DONE`、`outcome=FAILED_BEFORE_WRITE`（已执行 0 条，数据未动，不进入待处理）。DBT-3 按顺序每条语句单独一次 JDBC 调用，每张表的顺序是 `DELETE FROM 表` → 该表全部 `INSERT`，每完成一张表更新一次记录（`tables_done`、`table_current`、`inserted_current`）。DBT-2 只有一条语句。**第一处失败立即停止**，其后的语句不执行，按 12 文档第 4.3 节分类：**确定未生效**——SQLState 以 `42` 开头（语法、对象不存在、无权限，数据库在执行前就拒绝），或服务端返回的非连接类、非中止类错误且出错的是单行语句（DBT-3 的单值 `INSERT`；DBT-2 中只有一个值组的 `INSERT … VALUES`）；**结果不确定**——SQLState `08xxx` 或客户端错误码 2000～2999、1028/1053/1317/1927/3024、`SQLTimeoutException`、非 SQL 异常，或出错的是多行语句（`DELETE FROM 表`、多值 `INSERT`、`UPDATE`/`DELETE`、`INSERT … SELECT`）。各操作的落点见下方状态机；全部成功 → `DONE`、`outcome=OK` |
| ④ 并发协调 | (a) **工具内互斥（v7，REV-10；v8 补删备份与页面判定，REV-14）**：DBT-1、DBT-2、DBT-3、`recover`、`acknowledge`、导出、删备份 `delete` 与页面加载的 `probe()` 都先经 `LegacySiteFiles.tryExclusiveLock("application/data/backup/database/dbtool.lock")` 取独占 OS 文件锁（C-73），拿不到 → 立即拒绝“已有数据库操作在执行”，不等待、不发任何语句；拿到后才读运行记录、判断状态、写 `RUNNING`，直到本次操作写完最终记录才释放（`try-with-resources`）。“检查 → 占用”在同一把锁内完成，两个进程同时起跑时只有一个能拿到锁（V-31 ②）。锁的所有权就是持有它的进程；进程崩溃或被杀时由操作系统释放，后来的持锁者读到 `RUNNING`/`RECOVERING` 即可确定原占用者已不在，按 ⑩ 的中断结果表唯一改写（V-31 ④、⑪～⑯）。锁文件只创建、不删除（删除锁文件会让两个进程锁住不同的文件）。前提：站点根在本机文件系统，多个 Java 进程共用同一站点根时互斥成立；网络文件系统（NFS/SMB）上的锁语义不可靠，部署说明写明不支持（RISK-T20）。PHP 后台不参与这把锁，Java 持锁后仍检查 `backup.lock` 以避开正在导出的 PHP 后台，但这一步不是原子的，PHP 与 Java 后台不得同时对同一库使用数据库工具（RISK-T20）。(b) 与前台写入：DBT-3 与 `recover` 要求站点关闭（② d、⑨），否则前台在 `DELETE` 与 `INSERT` 之间写入的行会让 `INSERT` 主键冲突而中途失败，或在还原后被覆盖。(c) 与其他管理员、定时任务：本期不加库级锁（`it_app` 没有 `LOCK TABLES` 权限，加应用层全局锁要改所有写入口，超出范围）；页面在还原与恢复的确认步骤写明“请确认没有其他管理员在编辑内容、`api.php/timming` 定时任务已停”，属运维约定（RISK-T19）。(d) DBT-1：MySQL 对 MyISAM 的 `OPTIMIZE/REPAIR` 持表锁，期间该表写入排队，与 PHP 相同；逐表执行，一张表失败不影响其他表，结果逐表显示，DBT-1 总是以 `DONE` 结束、不进入待处理 |
| ⑤ 重试与幂等 | DBT-1：幂等，可直接重试。DBT-2：一般不幂等（无主键的 `INSERT`、`x=x+1`）；“确定未生效”时数据未动，输入框保留原文，管理员修正后重新提交；“结果不确定”时进入待处理，管理员先在数据库客户端或后台对应列表核对该语句是否已生效（DBT-2 本身按 PHP 不执行 `SELECT`，不能用它查询），再 `acknowledge`，之后决定是否重新提交。DBT-3：每张表“先清空、再插入备份中的全部行”，在没有并发写入时整体幂等，所以失败后的两种恢复（重跑原备份、用自动备份退回）都可以反复执行，结果相同（V-31 ⑤）。任何情况都不自动重试 |
| ⑥ 审计与告警 | (a) 运行记录文件 `application/data/backup/database/dbtool-run.json`（C-73）：处于待处理状态（下方状态机）时，数据库工具各页面顶部显示红色提示条：操作类型、开始时间、管理员、失败序号与分类、DBT-3 的已完成表/当前表/自动备份时间戳、恢复次数与最近结果，以及 ⑦ 的恢复步骤和状态机允许的按钮；页面加载时先调用 `probe()` 做一次不等待的取锁（v8）：拿不到锁 → 显示“执行中”，不出任何操作按钮；拿到锁 → 先完成 ⑩ 的中断判定、释放锁，再按记录显示提示条与 ⑩ 表“页面”列的按钮；记录已不是 `RUNNING`/`RECOVERING` 时 `probe()` 不改写记录、不写日志。(b) 应用日志：logger `maccms.admin.dbtool`，开始与结束各一行 `INFO`，`FAILED`/`RECOVERY_FAILED` 为 `WARN`，`UNKNOWN`/`INTERRUPTED`/`RECOVERY_UNKNOWN`/`RECOVERY_INTERRUPTED` 为 `ERROR`，`acknowledge` 为 `INFO`（含 `accepted_as_is`）；中断判定按 ⑩ 表“日志”列（DBT-1、导出、DBT-3 备份阶段 `WARN`，清理失败升为 `ERROR`；其余 `ERROR`）；字段 `run`、`op`、`phase`、`state`、`admin_id`、`total`、`executed`、`failed_index`、`outcome`、`sqlstate`、`error_code`、`tables`、`backup_file`、`recovery_attempt`、`duration_ms`、`cleanup`；**不写 SQL 原文、数据值、库连接串**（同 `SafeExceptionLog`）。部署方按 `ERROR` 级别告警 |
| ⑦ 管理员恢复步骤（写进提示条与页面帮助） | DBT-3 待处理（`FAILED`/`UNKNOWN`/`INTERRUPTED`/`RECOVERY_*`）：1. 保持站点关闭、停定时任务；2. 查明原因（提示条给出失败表与 SQLState）；3a. 原因已排除且要完成这次还原 → 点 `recover`，目标选“重跑原备份”；3b. 要回到还原前的数据 → 点 `recover`，目标选“还原前自动备份”；两种都只能用记录里绑定的文件，不能另选；4. 恢复成功（`RECOVERED`）后核对行数，点“我已处理”，再开站；5. 恢复本身又失败 → 仍是待处理，排除原因后可再次 `recover`（幂等）；若数据库工具已无法完成（例如磁盘或连接持续故障），由部署方用 `mysql` 客户端导入该次自动备份的分卷（它是 PHP 格式的完整导出，含删表与建表），完成后在页面勾选“接受当前数据”并点“我已处理”。DBT-2 `UNKNOWN`/`INTERRUPTED`：按 ⑤ 核对后点“我已处理”；DBT-2 没有自动备份，页面提示“执行前请先导出将被修改的表”，需要回退时用管理员事先导出的备份走一次普通还原 |
| ⑧ 与 PHP 的差异 | DBT-2（v7）：执行与否、执行什么与 PHP 相同（§4.13），差异只有 AC-11 结构语句拒绝（OP-6 已确认）、多语句与失败时的提示文字、运行记录与锁；成功路径与失败路径的数据结果都与 PHP 相同。DBT-3：PHP 还原没有预检站点状态、没有自动备份、没有运行记录与恢复动作，失败只提示“还原失败”；这些都是在“只还原数据”（OP-6）之上为可恢复性增加的保护，不改变成功路径的数据结果，其中“还原前站点须关闭”是新增前置条件。FR-9 不做页面对照，相应附表 C 行由 T-FR12 按本表判定 |
| ⑨ `recover` 动作（v7，REV-11） | 只对 DBT-3 的待处理记录开放。入参只有 `run`（须等于当前记录的 `run`）与目标（`AUTO_BACKUP` 或 `SOURCE`），不接受文件名。预检（任一不通过 → 拒绝、记录不变、0 条语句）：持锁；站点已关闭；目标为 `AUTO_BACKUP` 时 `auto_backup` 存在且各分卷 SHA-256 与记录相符；目标为 `SOURCE` 时原备份分卷齐全可读；DBT-3 预检 (a)～(c)。执行：记录改 `RECOVERING`（保留原失败信息，`recovery.attempt` 加 1），按 DBT-3 同一流程逐表还原，**不再做自动备份**（此时数据已是部分状态，再备份会覆盖真正的回退点）。结果：成功 → `RECOVERED`；失败按 ③ 分类 → `RECOVERY_FAILED` 或 `RECOVERY_UNKNOWN`；进程中途退出 → 下一个持锁者按 ⑩ 记 `RECOVERY_INTERRUPTED` |
| ⑩ 进程中断后的唯一转移（v8，REV-15） | 中断判定是每个取锁入口（④ (a) 的八个）的第一步：读到 `RUNNING`/`RECOVERING` → 按下方“进程中断后的唯一转移”表由 `op`、`phase`、`state` 唯一确定新状态、清理动作、页面按钮与日志级别，原子替换写回（`interrupted` 记判定时间、判定前状态与阶段、清理结果）后才继续本次动作；每条记录只判定一次，判定后不再是 `RUNNING`/`RECOVERING`。清理只在锁内、只删记录点名的文件：`export_files` 列出的分卷，以及内容与 `backup_lock` 相同的 `backup.lock`（内容不同说明是 PHP 后台写的，不删，记 `LOCK_KEPT`）；不按通配删除。清理失败不阻止状态转移，记 `FAILED` 与残留文件名，`backup.lock` 残留时之后的导出与还原按 B15 `lock_check` 被拒，页面提示部署方核对后手动删除。`acknowledge` 只做一次原子替换、`delete` 不写记录，二者被杀都不留下 `RUNNING` |

**运行记录状态机（v7，REV-11；所有转换都在锁内完成）**

| 当前状态 | 动作 / 事件 | 前提 | 新状态 |
| --- | --- | --- | --- |
| 无记录 或 `DONE` | DBT-1/DBT-2/DBT-3/导出 `begin` | 拿到锁；`backup.lock` 不存在 | `RUNNING` |
| `RUNNING` | 全部成功 | — | `DONE`（`outcome=OK`） |
| `RUNNING`（DBT-1） | 有表失败 | — | `DONE`（逐表结果写入 `outcome`） |
| `RUNNING`（DBT-2） | 确定未生效 | — | `DONE`（`outcome=FAILED`，数据未动，不进入待处理） |
| `RUNNING`（DBT-3） | 自动备份失败 | — | `DONE`（`outcome=FAILED_BEFORE_WRITE`，数据未动） |
| `RUNNING`（DBT-3） | 确定未生效 | — | `FAILED`（待处理：前 n-1 条已生效） |
| `RUNNING`（DBT-2、DBT-3） | 结果不确定 | — | `UNKNOWN`（待处理） |
| `RUNNING`（导出） | 失败 | — | `DONE`（`outcome=FAILED`，B15 原样提示） |
| `RUNNING`/`RECOVERING` | 进程退出 | 下一个持锁者（任一取锁入口，含页面加载）发现 | 按下方“进程中断后的唯一转移”表，由 `op` 与 `phase` 唯一确定（v8，REV-15） |
| `FAILED`/`UNKNOWN`/`INTERRUPTED`/`RECOVERY_FAILED`/`RECOVERY_UNKNOWN`/`RECOVERY_INTERRUPTED`（DBT-3） | `recover(run, 目标)` | ⑨ 的预检 | `RECOVERING` |
| `RECOVERING` | 成功 / 确定未生效 / 结果不确定 | — | `RECOVERED` / `RECOVERY_FAILED` / `RECOVERY_UNKNOWN`（进程退出见下表） |
| `RECOVERED` | `acknowledge(run)` | 拿到锁 | `DONE`（`ack.accepted_as_is=false`） |
| 其他待处理状态（含 DBT-2 的 `UNKNOWN`/`INTERRUPTED`；全部 `RECOVERY_*`） | `acknowledge(run, 接受当前数据=true)` | 拿到锁；页面勾选“接受当前数据” | `DONE`（`ack.accepted_as_is=true`，日志记录） |
| 任一待处理状态或 `RECOVERED` | DBT-1/DBT-2/DBT-3/导出 `begin` | — | 拒绝，状态不变，0 条语句 |
| 任一状态（含无记录） | 删备份 `delete`（v8，REV-14） | 拿到锁；中断判定已完成 | 记录为 `IMPORT` 的待处理（含 `RECOVERED`）且要删的前缀等于 `source_backup` 或 `auto_backup` → 拒绝，0 个文件被删；否则按卷号升序删除；记录不变 |
| `RUNNING`/`RECOVERING` 且锁被持有 | 任何动作（含 `delete`）；页面加载 | — | 动作拒绝“已有数据库操作在执行”，0 条语句、0 个文件被删；页面显示“执行中”、无操作按钮 |

“待处理”= `FAILED`、`UNKNOWN`、`INTERRUPTED`、`RECOVERY_FAILED`、`RECOVERY_UNKNOWN`、`RECOVERY_INTERRUPTED`、`RECOVERED`。`acknowledge` 是离开待处理的唯一出口；恢复不会自动确认，管理员核对后才确认。

**进程中断后的唯一转移（v8，REV-15；DBT-5 ⑩，所有改写都在锁内完成）**

| 被中断的记录（`op` / `phase` / `state`） | 判定后状态 | 清理 | 待处理 | 页面（提示条与按钮） | 日志 | 理由 | 验证 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `OPTIMIZE`/`REPAIR`（DBT-1）/ — / `RUNNING` | `DONE`，`outcome=INTERRUPTED`，保留 `tables_done` | 无 | 否 | 灰色说明一行“上次优化/修复在第 k 张表中断，可重新执行”；无 `recover`、“我已处理”；普通操作可用 | `WARN` | 表维护语句由 MySQL 自行完成或放弃，不改行数据的取值；重跑即可（与 PHP 相同） | V-31 ⑪ |
| `SQL`（DBT-2）/ — / `RUNNING` | `INTERRUPTED` | 无 | 是 | 红色提示条“该语句可能已生效，也可能未生效”，附 ⑤ 的核对步骤；按钮只有“我已处理”，须勾选“接受当前数据”；无 `recover`（没有自动备份） | `ERROR` | 无法区分“发送前被杀”与“执行中被杀”，按结果不确定处理 | V-31 ⑫ |
| `IMPORT`（DBT-3）/ `AUTO_BACKUP` / `RUNNING` | `DONE`，`outcome=INTERRUPTED_BEFORE_WRITE` | 删除 `export_files` 列出的半成品分卷；`backup.lock` 内容等于 `backup_lock` 时删除 | 否 | 灰色说明“上次还原在备份阶段中断，数据未改动，可重新发起”；无恢复按钮；普通操作可用 | `WARN`（清理失败 `ERROR`） | 还原语句只在 `phase=RESTORE` 落盘后发送，此阶段数据未动；半成品分卷不是可用备份 | V-31 ⑬ |
| `IMPORT`（DBT-3）/ `RESTORE` / `RUNNING` | `INTERRUPTED` | 无（自动备份是恢复点，保留） | 是 | 红色提示条（已完成表、当前表、自动备份时间戳）；按钮 `recover`（“还原前自动备份”“重跑原备份”）与“我已处理”（须勾选“接受当前数据”）；普通操作与删除绑定备份被拒 | `ERROR` | 数据处于部分还原状态 | V-31 ⑭ |
| `EXPORT`（导出）/ — / `RUNNING` | `DONE`，`outcome=INTERRUPTED` | 同 `IMPORT`/`AUTO_BACKUP` 一行的清理 | 否 | 灰色说明“上次导出中断，不完整的分卷已删除，请重新导出”；清理为 `LOCK_KEPT`/`FAILED` 时改为列出残留文件并提示部署方手动处理；无恢复按钮 | `WARN`（清理失败 `ERROR`） | 导出只读数据库，不改数据；半成品分卷不是可用备份 | V-31 ⑮ |
| `IMPORT`（DBT-3）/ —（`recover` 不分阶段）/ `RECOVERING` | `RECOVERY_INTERRUPTED`，`recovery.attempt` 不变，最近结果记 `INTERRUPTED` | 无 | 是 | 同 `RESTORE`/`RUNNING` 一行：`recover`（两个目标）与“我已处理”（须勾选） | `ERROR` | 恢复本身中途停止，数据仍是部分状态；`recover` 幂等，可再次执行 | V-31 ⑯ |

`SchemaGuardTest` 的登记例外增加 DBT-1、DBT-2 的实现类（它们的源码里出现 `OPTIMIZE TABLE`、`REPAIR TABLE` 字样与拒绝关键字表），写明理由与 AC-11 编号（C-55）。

### 6.14 安装向导建表（FR-10，v3 新增）

| 编号 | 规则 |
| --- | --- |
| INS-1 | 待执行文件 = 站点根 `application/install/sql/install.sql`（经 `LegacySiteFiles.readInstallSql` 只读，C-69，只允许这一个固定路径）；Java 内置常量 `INSTALL_SQL_SHA256 = 48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4`（= 基线提交 `a57e9cb` 里 blob `php/application/install/sql/install.sql` 的 SHA-256，45049 字节、全部 LF、不含 `\r`；核对命令 `git show a57e9cb2b94d3e500bea7dfe2c579daee093d969:php/application/install/sql/install.sql \| sha256sum`）。`core.autocrlf=true` 的 Windows 工作区里该文件被检出为 CRLF（45950 字节，SHA-256 `152e5424…`，v3 误用了这个值），所以 C-67 用 `.gitattributes` 固定 LF；单测断言常量等于仓库文件的哈希且文件不含 `\r`，防漂移 |
| INS-2 | 第 5 步顺序：①用第 3 步填写、存于 `MACCMS_DEPLOY_CONFIG` 的账号开连接（AC-12 为 `it_install`）；②查 `information_schema.TABLES` 中该库 `mac_` 开头的表数，>0 → 提示“已存在数据表”，不执行；③读文件按字节算 SHA-256（不做换行归一化），≠ 常量 → 提示“文件哈希不一致”，不执行（CRLF 检出的同一文件也走这一支）；④按 `mac_parse_sql` 切分后逐条执行（前缀固定 `mac_`，Java 已要求前缀为 `mac_`，所以不做替换，“一字不改”）；⑤按 PHP 写初始数据（`initdata` 选 1 时执行 `initdata.sql` 的 INSERT，现有 `InstallWrites`）、写首个管理员、写 `extra/maccms.php`；⑥写 `install.lock` |
| INS-3 | 不发 `CREATE DATABASE`（库由部署方或验收脚本预建，A.3）；不执行除该文件外的任何 DDL；删除 `InstallSchema` 指纹校验分支（改为 INS-2 ②③），指纹资源保留给 `InstallWizardMySqlIT` 做结果核对 |
| INS-4 | 账号分离：建表连接只在第 5 步请求内使用，用后关闭；正常模式运行期只读 `MACCMS_DB_*`（AC-15 的 `it_app`），不读 `MACCMS_DEPLOY_CONFIG`、不读 `database.php`（RISK-15 的交接方式）。PHP 第 5 步写 `application/database.php`（D-23 文件栏）；Java 现状写部署配置文件，是否另写 `database.php` 由 FR-12 对 install 行（附表 C 安装向导 5 行）判定，本设计不预设 |

### 6.15 在线采集、远程图片、规则在线抓取、远程存储、定时采集（FR-11，v3 新增）

| 编号 | 功能 | 设计 |
| --- | --- | --- |
| NET-1 | 资源站在线采集（`collect/api` 在线、`collect/test`） | 新增 `integration.collect`（登记 `IntegrationPackages.REGISTERED`）：按 PHP `Collect` 模型的请求构造（`ac`、`t`、`pg`、`h`、`ids`、`wd` 参数，XML/JSON 两种协议）经 `OutboundHttpClient` 取数；`collect` 模块只经 `integration.application.ResourceSiteClient` 接口调用，取回的文本交给已有的解析与入库（B18/B19，`CollectPreviewService`、`CollectRunService`） |
| NET-2 | 定时采集（`collect/timing`、定时任务 `collect`） | 从 `AdminPermissions.NOT_PROVIDED` 与 `TimmingTasks` 的拒绝项移除，按 PHP `Collect::timing` 与 `api/Timming` 的 `collect` 分支调用 NET-1（撤销 D-B19-15） |
| NET-3 | 远程图片下载（采集入库 `sync_pic_opt`、`images/sync`） | 新增 `integration.image`：按 PHP `common/model/Image.php:7` `down_load` 的规则下载、校验类型与大小、按 `upload` 配置落盘（经 `LegacySiteFiles` 的 `upload/` 写区域）并写 `mac_annex`；`VodIngest` 与 `images/sync` 改调此接口 |
| NET-4 | 自定义规则在线抓取（`cj`） | `CjRunService` 的取页从 `LocalHtmlFiles` 扩为“本地文件或经 `integration.collect` 在线取页”，规则执行不变 |
| NET-5 | 远程存储 | 新增 `integration.storage`：六个上传器按 PHP `common/extend/upload/{Upyun,Qiniu,Ftp,Weibo,Alibaba,Uomg}.php` 移植；HTTP 类经 `OutboundHttpClient`（签名用 JDK `javax.crypto`），FTP 用 JDK `ftp:` URL 连接（不加依赖，登记为 `RAW_API` 并自行执行同一套目标检查，同 `integration.mail` 的做法）；`AdminUploadService` 按 `upload.mode` 选择 |
| NET-6 | 出站白名单与护栏 | 三个新包登记进 `IntegrationPackages`（AC-13“登记进出站白名单”）；`OutboundNetworkGuardTest`、`RegisteredOutboundClientTest` 同步；验收在 `OutboundPolicy.LOOPBACK_ONLY` 下用本机模拟服务（沿用 `integration.testing` 的做法），断言无本机以外的连接 |
| NET-7 | 出站地址固定（v9，DEC-14，契约 OUT-1-5） | ① `OutboundPolicy.check` 返回 `Target`（C-77），同一跳只解析一次，解析结果既用于 OUT-1-4 检查也用于建连。② 每一跳：用 `PinnedDnsResolver(target.host(), target.addresses())` 建 `BasicHttpClientConnectionManager.create(DefaultSchemePortResolver.INSTANCE, resolver, {https → TLS 策略}, null)`，再建 `CloseableHttpClient`：`setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))`（直连，不读系统代理，不调用 `useSystemProperties()`/`setProxy`/`setProxySelector`）、`disableRedirectHandling()`、`disableAutomaticRetries()`、`disableCookieManagement()`、`disableAuthCaching()`、`disableContentCompression()`、`disableDefaultUserAgent()`；跳结束（成功或异常）即关闭客户端与连接管理器，连接不跨跳、不跨请求复用。TLS 策略在构造时建一次：`new DefaultClientTlsStrategy(sslContext)`，主机名校验保持默认开启。③ `PinnedDnsResolver.resolve(name)`：`name` 按 ① 的规则规范化后等于 `host` → 返回 `addresses` 的副本；否则抛 `UnknownHostException`；`resolveCanonicalHostname` 原样返回；类内不得出现 `InetAddress.getAllByName`/`getByName`（V-33 ⑦ 检索）。请求目标 `HttpHost` 用原 scheme、原主机名、原端口，因此 `Host` 头、SNI 与证书主机名校验都是原主机名。④ 超时：连接管理器 `ConnectionConfig` 的连接超时 = 现有 `connectTimeout`；总时限沿用现有语义（从发出请求到读完响应，含全部跳转）：每跳剩余时间 ≤ 0 → `TIMEOUT`；跳内设 `RequestConfig.setResponseTimeout(剩余)`，并由客户端持有的单个守护线程 `ScheduledExecutorService` 在剩余时间到点时对该请求 `cancel()`，读完即撤销定时；被取消或读超时 → `TIMEOUT`。⑤ 与基线逐项一致：HTTP/1.1；请求头原样复制，未带 `User-Agent` 时补 `LEGACY_USER_AGENT`；调用方给出 `host`、`connection`、`content-length`、`expect`、`upgrade`（不区分大小写）→ `IllegalArgumentException`（同 JDK 客户端的受限头行为）；有请求体时用 `ByteArrayEntity`（内容类型取请求头），POST/PUT 无请求体时发 `Content-Length: 0`；不发 `Accept-Encoding`、不自动解压；响应体边读边计数，超过本次上限立即 `cancel()` 并 `RESPONSE_TOO_LARGE`（不信任 `Content-Length`）；响应头按现有 `lowerCaseHeaders` 规则合并。⑥ 异常映射：`ConnectTimeoutException`/`SocketTimeoutException`/被定时取消 → `TIMEOUT`；`HttpHostConnectException`/`ConnectException`/`NoRouteToHostException`/解析器抛出的 `UnknownHostException` → `CONNECT_FAILED`；线程中断 → `INTERRUPTED`（恢复中断标志）；其余 `IOException`（含 `SSLException`）→ `IO_ERROR`；日志仍只记类型链。业务层按契约把这些原因统一映射为 `COLLECT_FETCH_FAILED`/`IMAGES_FETCH_FAILED`（OUT-1-9 不变） |

### 6.16 旧控制器对齐（FR-12，v3 新增）

- 判定由测试人员按附表 C 逐行做（AC-14），报告 `docs/test/APPK-2/parity-*.md`，缺口登记 `docs/test/APPK-2/gap-register.md`（§9.1 格式）；本设计不预判结果。
- 判定口径（交测试人员）：PHP 无漏洞的行为以 PHP 为准；六项安全修复（§6.17 SEC-1～6）判 `排除-D2`；本设计的安全收紧 SEC-7（OP-T5=a）、SEC-9（OP-T4=a）已由发起人确认（v8，评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`），按“已确认的安全差异”判 `排除-SEC-7` / `排除-SEC-9`（SEC-8 v6 起不是差异，见 §6.17），报告引用该评论；历史 D-Bxx 决定与 PHP 不同、又不是安全修复的（如 D-B15-14、D-B15-15、D-B16-5、D-B16-6、D-B19-15、D-B13-2、D-B19-14），本设计已按 PHP 改回（§6.11～§6.15），对应行判定以改回后的行为为准。
- 缺口迁移：每个 GAP- 单独建单、单独设计说明（引用本设计的模块边界与附表 D 越界判定），不在本设计里列实现。

### 6.17 六项安全修复（BR-4、OP-9，v3 新增）

| 编号 | 修复 | 本期改动是否触及 | 保持方式 |
| --- | --- | --- | --- |
| SEC-1 | 会员登录只比 `md5(输入)` | FR-5 PG-5 改登录页输出 | `MemberAccountService.login` 不改；PG-5 只换呈现 |
| SEC-2 | 普通登录/注册忽略 `openid`/`col` | 同上 | 同上 |
| SEC-3 | `api.php/timming` 鉴权 | FR-6、FR-11（NET-2 定时采集） | `TimmingAccess` 不改，NET-2 只增加任务类型 |
| SEC-4 | 可信代理才信转发头 | FR-11（在线采集记录来源 IP）、评论 | 一律经 `ClientIpResolver` |
| SEC-5 | 管理员每请求重读 | FR-7、FR-9 后台 | `AdminController` 不改 |
| SEC-6 | 注销/改密/重置轮换 `user_random` | PG-5 | 不改 |
| SEC-7（本设计，OP-T5=a 已确认） | `/template/**` 只给静态资源，不给 `.html`/`.ini` | DEC-8、§6.10、C-44 | 不进比较样本，FR-12 相关行按“已确认的安全差异”判 `排除-SEC-7` |
| SEC-8（v6 更正：不是差异，无条件；v7 连提示也与 PHP 相同） | 执行 SQL 不执行以 `select` 开头或含 `outfile`/`dumpfile`/`load_file` 的输入 | DBT-2 ② | 与 PHP 逐字相同（`Database.php:252-263`）：整段不发送，返回“执行成功”；FR-12 相关行判“已对齐” |
| SEC-9（本设计，OP-T4=a 已确认） | 观看密码在 PHP 按会话 5 秒限制之外，再按内容/全站限流（`PasswordAttempts`），超限返回 PHP 已有的 1003 | ALIGN-4 ⑤ | 只有“超限”样本与 PHP 不同，判 `排除-SEC-9` |

SEC-7、SEC-9 不属于 OP-9 已确认的六项，是本设计提出、发起人已确认（v8：OP-T5=a、OP-T4=a）的行为差异；V-24 的“仍通过”只对 SEC-1～6 适用，SEC-7、SEC-9 的用例见 V-18、V-30。SEC-8 v5 及以前误写为“PHP 照常执行”的收紧，v6 按 PHP 源码更正为与 PHP 一致的无条件行为，v7 连提示文字也改回 PHP（V-23）。前台 CSP 不属于六项；OP-T6 已按 b 关闭（v8），只保留三项不拦脚本的策略（C-75）。

验证：每项在所属批次已有的用例（“旧行为不再成立”断言）必须在 FR-5～FR-11 合入后仍通过（V-24），列入回归点。

### 6.18 缓存（关闭 OP-T2 / V9-001，v3 新增）

| 编号 | 规则 |
| --- | --- |
| CACHE-1 | 标签结果缓存、详情缓存、页面缓存只在 `app.cache_core=1`、`app.cache_page=1` 时启用，键规则同 PHP；进程内实现（有界 LRU，按 `cache_time`/`cachetime` 过期），不落库；对照环境用基线值 `0`，不缓存 |
| CACHE-2 | 编译缓存（TPL-8）按文件修改时间自动失效，对照时不影响结果；仍随 CACHE-3 清空，保证“冷启动”路径也被覆盖 |
| CACHE-3 | `CacheResetHook.resetAll()`：清 CACHE-1、CACHE-2 和 `RichTextCache`。触发方式：①后台“清理缓存”入口（PHP `admin/index/clear`，现有 Java 入口加一行调用）；②对照专用：Java 进程启动参数 `maccms.phptemplate.compare.reset-file=<路径>`（只在对照环境设置，生产不设，默认关闭），每个请求开始时若该文件的修改时间变了就先执行一次 `resetAll()`——测试脚本在 ENV-9 里“touch”该文件，不需要新增 HTTP 接口 |
| CACHE-4 | “启动时读一次配置”的站点 Bean（`SiteSettings`、`LegacyUrlBuilder`、`SiteVariants`、`PlayerSettings` 等）：对照中会改配置的样本只有 F-D2（`app.page_404`）与后台保存类样本；F-D2 两侧都通过“先改配置文件、再重启该侧进程”执行（在样本清单写明），ENV-5 恢复配置后同样重启该侧；PHP 每请求读配置，不需要重启。是否把这些 Bean 改为按 `LegacyConfigService.generation()` 刷新，属于 FR-12 对“改配置立即生效”行为的判定，不在本设计预设 |

### 6.19 数据表与字段（v3 新增）

- 本设计**不新增、不修改任何表、列、索引**（BR-1）。
- 读写范围逐功能以附表 D 为准：标签库 D-02/D.6 TAGMAP-1～19（只读，`mac_vod_search` 写入沿用 D-01）；前台页面 D-01、D-03～D-07e；静态生成 D-19（`vod/art/topic/actor/role/website` 的 `*_time_make`）；数据库工具 D-18/DYN-7；安装 D-23/DYN-9；采集与图片 D-14～D-16/DYN-5～6（`mac_annex` 写入在 OP-5 放开后启用）；模板管理 D-20（只有文件）。
- 新增的写入（静态生成 `actor_time_make`、`role_time_make`、`website_time_make`；图片下载 `mac_annex`；数据库还原的 `DELETE`/`INSERT`）都先在 `WriteRegistry.REGISTERED` 登记，表与列必须在附表 D 对应行内（AC-16）。

### 6.20 对照数据快照与恢复（PRE-2，CMP-2，v3 新增）

| 编号 | 规则 |
| --- | --- |
| SNAPD-1 | 快照 = `it_admin` 用 `mysqldump --no-create-info --skip-triggers --complete-insert --hex-blob --skip-extended-insert` 导出 26 张表数据到 `.compare/APPK-2/data-snapshot.sql`，另记录每张表的 `CHECKSUM TABLE` 值与 `information_schema.TABLES.AUTO_INCREMENT` 到 `docs/test/APPK-2/data-snapshot.json`（脚本 `tests/compare/APPK-2/data_snapshot.py`） |
| SNAPD-2 | 恢复（每个单元前）：对 26 张表 `DELETE FROM`、执行快照 SQL、对每张表 `ALTER TABLE <表> AUTO_INCREMENT=<记录值>`（由 `it_admin` 在库外执行，只改计数器，SNAP 不含该值，CMP-2 ① 明确允许）、再校验 `CHECKSUM TABLE` 与 `AUTO_INCREMENT`，不等重试一次，仍不等标“环境失败” |
| SNAPD-3 | 恢复记录按 CMP-2“报告义务”逐单元写一行；脚本输出机器可读结果供对照报告汇总 |
| SNAPD-4 | MySQL 会话时区 `+08:00`（PHPB-8，关闭 OP-T3 一半）：PHP 侧在 ENV-2 生成的 `database.php` 里加 `'params' => [PDO::MYSQL_ATTR_INIT_COMMAND => "SET time_zone='+08:00'"]`；Java 侧 `MACCMS_DB_URL` 加 `sessionVariables=time_zone='+08:00'`（只在对照环境）；两侧都在基线报告里记录 `@@session.time_zone` |
| SNAPD-5 | `php/vendor/`（关闭 OP-T3 另一半）：按 `AGENTS.md`「本地运行与测试」从仓库外生产备份解压到 `pristine/vendor/`，基线报告记录 `vendor/` 目录的文件数与按路径排序后的 SHA-256 汇总值；备份文件只在本机，不入库 |

## 7. 实现步骤（按端）

### 7.1 后端（@后端开发-海外）——G1 放行后执行

1. C-1～C-4：在一次性的空目录里取官方提交（`git clone --filter=blob:none --no-checkout https://github.com/magicblack/maccms10` 后 `git -c core.autocrlf=false archive 4c418c2703692148a937ad92893ab8a494bff742 template LICENSE`，解到 `third_party/maccms-template-v2020.1000.1035/`），不运行其中任何脚本。可单独验证：§8 V-1。
2. C-5：先提交 `.gitattributes`，再 `git add` 模板包（顺序反了会按 `autocrlf` 入库）。验证：V-1 的目录树 SHA 相等。
3. C-1～C-5 的入库提交不改 `src/main/**`。
4. C-12～C-16：另起一个提交，只含这 5 个文件；须在 FR-1 模板解析器读取 `SiteVariant.pathTpl` 之前合入。验证：V-9，以及 `./mvnw -B verify` 全绿（含未改的 `SiteTemplateSelectionGoldenTest.selectionMatchesLegacyInit`）。

### 7.2 流水线与配置（@运营打包-海外）

1. C-11：部署样例补说明。部署顺序：先把 DEC-1 的两个目录复制到站点根 `template/`，再改 `maccms.php` 的 `template_dir`/`mob_template_dir`（或在后台“系统配置”里选择后保存——保存会按 `info.ini` 重写 `ads_dir`/`mob_ads_dir` 为 `ads`），最后重启 Java（站点配置启动时读取）。
2. 本期 Q7 移出：不做测试环境部署与发布，只交付样例说明。

### 7.3 对照环境（@测试人员-海外，PRE-2 内执行；C-6～C-10）

| 编号 | 规则 |
| --- | --- |
| ENV-1 | 装配目录 `.compare/APPK-2/`（已 gitignore）。`pristine/` = 本次被测提交里 `php/` 的已跟踪文件，用 `git -c core.autocrlf=false archive <被测提交> php/` 导出（不从工作区复制，避免本机 `autocrlf=true` 把 LF 改成 CRLF，触发 NOIGN-4），**去掉 `php/template/`**，再放入 DEC-1 的 `template/default_pc`、`template/default_wap` |
| ENV-2 | `pristine/vendor/` 按 `AGENTS.md`「本地运行与测试」从仓库外生产备份恢复；`pristine/application/data/install/install.lock` 写入固定内容 `APPK-2`（只需文件存在）；`pristine/application/database.php` 由 C-8 从 Java 同名环境变量 `MACCMS_DB_URL`/`MACCMS_DB_USERNAME`/`MACCMS_DB_PASSWORD`（即 A.3 的 `maccms_it_full` 与 `it_app`）生成，脚本不打印取值。`database.php` 不属于 13 个 CS-CONFIG 文件 |
| ENV-3 | `pristine/application/extra/maccms.php` 只按 C-7 的 7 个键做整行替换；其余 12 个 CS-CONFIG 文件不改。装配后输出 13 个文件的 SHA-256，记入 `docs/test/APPK-2/php-baseline-report.md` |
| ENV-4 | `php-site/`、`java-site/` 都由 `pristine/` 完整复制；两侧 13 个 CS-CONFIG 文件 SHA-256 必须逐个相同（PHPB-9：两侧取值相同，不是“各写各值”） |
| ENV-5 | 每个请求单元开始前（与 CMP-2 的数据恢复同一时点），该侧 13 个 CS-CONFIG 文件与 `pristine/` 比 SHA-256，不同则从 `pristine/` 复制回去并在恢复记录里注明；防止上一单元的后台保存（如 `System`、`Domain`）改动后续单元读到的配置 |
| ENV-6 | 静态生成用例（AC-7 的 K-01～K-12）：每一侧单元开始前删除该侧站点根并从 `pristine/` 重新复制，生成后与 `pristine/` 做目录差异，差异即“该侧生成的文件” |
| ENV-7 | ENV-6 的差异排除：`runtime/`（PHP 缓存与日志）、`application/data/backup/static-pages/generated.txt`（Java 登记文件）。其余任何新增或改动的文件都计入 CMP-4 比较 |
| ENV-8 | 启动：PHP 用 PHPB-1 的 PHP 7.3.33，工作目录为 `php-site/`，`LC_ALL=C`，命令 `<PHP73>/php.exe -c tests/compare/APPK-2/php.ini -d extension_dir=<PHP73>/ext -S 127.0.0.1:18180 -t .`（`<PHP73>` 是 PHPB-1 的安装目录，由 PRE-2 登记，不写进仓库）；Java 用仓库同一提交构建的 jar，`MACCMS_LEGACY_CONFIG_DIR=<…>/.compare/APPK-2/java-site/application`、`MACCMS_UPLOAD_DIR` 与 `MACCMS_MEMBER_UPLOAD_DIR` 指向 `java-site/upload`、`MACCMS_SERVER_PORT=18280`，数据库同 ENV-2 的环境变量。两侧端口不同造成的访问源差异由附表 F 的 EX-2 处理 |
| ENV-9（v3） | 每个请求单元开始前、CMP-2 ① 之前清缓存：PHP 侧删除 `php-site/runtime/cache/`、`php-site/runtime/temp/`；Java 侧更新 `.compare/APPK-2/java-reset.flag` 的修改时间（Java 以 `maccms.phptemplate.compare.reset-file` 指向它，CACHE-3 ②）；恢复记录注明已清缓存 |
| ENV-10（v3） | MySQL 会话时区 `+08:00`：PHP 侧按 SNAPD-4 写入 `database.php` 的 `params`；Java 侧 `MACCMS_DB_URL` 附加 `sessionVariables=time_zone='+08:00'`；两侧在基线报告记录 `@@session.time_zone` |

用例关注点（交 @测试人员-海外）：手机 UA 必须拿到 `default_wap` 的页面（`mob_status=2`）；页面里的静态资源地址是 PATH-2 加包内相对路径（`/template/default_pc/css/…`），任何响应都不得出现 `/template/default_pc/html/css/`、`/template/default_wap/html/css/` 这类以 PATH-3 为前缀的资源地址；`default_wap` 下没有 `ads/`，任何请求都不得在其下建目录；后台保存系统配置后 `ads_dir`/`mob_ads_dir` 仍为 `ads`；把 `template_dir` 改成不存在的目录时（AC-1 ③）两侧都出模板缺失页。

### 7.4 实现顺序与子任务拆分（v3 新增；按“一次运行约 60 分钟、改动集中在一个模块”拆，G1 放行后由调度建单）

每个子任务交付时都要：`./mvnw -B test -Dtest=<本任务测试类>` 通过，交付前 `./mvnw -B verify` 全绿（`MULTICA.md` §3，需 `JDK_JAVA_OPTIONS`）。

| 序 | 子任务 | 端 | 改动清单行 | 依赖 | 完成判据（验证行） |
| --- | --- | --- | --- | --- | --- |
| S3-01 | 模板包入库 + `.gitattributes` | 后端 | C-1～C-5 | G1 | V-1、V-8 |
| S3-02 | `path_tpl` 映射修正 | 后端 | C-12～C-16 | G1 | V-9 |
| S3-03 | 对照环境装配、快照恢复、结构快照脚本 | 测试 | C-6～C-10、C-62、C-63、C-66 | S3-01 | V-2、V-3、V-20、V-21 |
| S3-04 | PRE-5 模板扫描（脚本 + 逐处实现说明） | 测试 + 架构师 | C-65；`docs/test/APPK-2/template-scan.md` | S3-01 | PRE-5 完成判定 |
| S3-05 | PHP 黄金脚本：编译产物、值语义、函数 | 测试 | C-33 | S3-03、S3-04 | 黄金文件入库、生成命令可复跑 |
| S3-06 | 运行时值语义 | 后端 | C-17 | S3-05 | V-11 |
| S3-07 | 词法/语法/解释器 | 后端 | C-18、C-19 | S3-06 | V-11、`InterpreterTest` |
| S3-08 | 编译流水线（Template + Cx） | 后端 | C-20、C-21、C-23 | S3-07 | V-12（Cx 部分） |
| S3-09 | Maccms 标签编译 | 后端 | C-22 | S3-08 | V-12（168 个文件编译产物逐字节相同） |
| S3-10 | 渲染服务、缓存、缺模板页、配置、护栏 | 后端 | C-24～C-26、C-30、C-31、C-32、C-35 | S3-09、S3-02 | V-13、V-14 |
| S3-11 | 模板函数 | 后端 | C-27 | S3-10 | V-15 |
| S3-12a～e | 标签模型（a：vod；b：art/topic；c：actor/role/website；d：type/link/extend 七个筛选项；e：comment/gbook） | 后端 | C-28、C-29、C-34 | S3-10 | V-16（各自标签的属性样例） |
| S3-13a | 内置视图 | 前端 | C-39 | S3-10 | 3 个资源与 `php/application/index/view/` 对应文件逐字节相同（`git diff --no-index --binary` 无输出）；`./mvnw -B verify` |
| S3-13b | `readTemplateAsset` 放行边界 | 后端 | C-44 | G1（OP-T5=a 已关闭，v8） | V-18 单测部分 |
| S3-13c | `/template/**` 静态资源控制器 | 前端 | C-43 | S3-13b（OP-T5=a 已关闭，v8） | V-18 接口用例部分 |
| S3-14a～g | 页面接入（a：PG-1 首页 + PG-7 公共页 + ALIGN-1～3 + C-70 共享边界（`SitePageResponses`、`JumpPages`）+ `SiteTestRoutes` 迁移；b：PG-2 vod 列表/搜索/筛选 + ALIGN-6（`SearchThrottle` 与其 Bean）；c：PG-2 vod 详情/播放/下载/播放器/版权 + ALIGN-4 + C-68（OP-T4=a）+ `PlaybackPages`；d：PG-3；e：PG-4；f：PG-5（分两次运行：账号类、财务与记录类；含 `SiteMemberFinance`/`SitePaymentController` 的 `HTML_UTF8`）；g：PG-6 + ALIGN-7（`SiteCounterActions`）；a 同时落地 C-75，取值为 OP-T6=b 的三项策略） | 前端 | C-36～C-38、C-45、C-46、C-70～C-72 的对应部分（§6.8 迁移表“子任务”列），并删除该组 jte（C-40 的对应部分） | S3-11、S3-12 对应标签、S3-13a；b～g 另需 S3-14a（共享边界） | 本组样本 V-19 子集通过；本组涉及的 V-32 ①～⑤ 断言与 ⑥ 的本组页面（a：跳转页；c：播放页；f：支付页）；c 另需 V-30 |
| S3-14h | （v8 撤销）v7 的“前台 CSP 落定”并入 S3-14a：OP-T6 已按 b 关闭，C-75 随 S3-14a 落地即为最终值 | — | — | — | — |
| S3-15 | jte 前台与 D4 收尾删除 | 前端 | C-40 剩余部分、C-41、C-42、C-72 的 `SiteConfigurationTest` | S3-14 全部（含 §6.8 迁移表中除 S3-15 外的全部行） | V-8、V-32、`./mvnw -B verify` |
| S3-16 | 后台模板管理 + 标签向导 | 后端 | C-47～C-49 | S3-10 | V-7、V-22 |
| S3-17 | 静态生成差异补齐 | 后端 | C-45、C-50、C-51 | S3-14 全部 | V-6 |
| S3-18 | 缓存清理钩子接入后台 | 后端 | C-52 | S3-10 | V-14 |
| S3-19a | 数据库工具：优化/修复、执行 SQL（PHP 整段语义）、锁、运行记录与状态机公共部分、`acknowledge` | 后端 | C-53（DBT-1、DBT-2、DBT-4 导出与删备份入锁、DBT-5 公共部分与 ⑩ 中断判定）、C-54（`sql.jte`、提示条、`acknowledge`、页面 `probe()`）、C-55、C-73、C-74（`SqlStatementsTest`、`DatabaseRunsTest`、`DatabaseRunsLockIT`、`DatabaseRunsLockProbe`、`DatabaseToolMySqlIT` 的 DBT-1/DBT-2/导出部分） | G1（不依赖模板，不依赖任何 OP-T） | V-23 ①～④ 与集成测试中 DBT-1/DBT-2 部分、V-31 ①～④、⑪、⑫、⑮、⑰、⑲（DBT-1/DBT-2/导出部分） |
| S3-19b | 数据库工具：还原备份（预检、自动备份与哈希、阶段与逐表状态、`recover`、绑定备份保护） | 后端 | C-53（DBT-3、DBT-4 绑定备份保护、⑩ 的 `IMPORT`/`RECOVERING` 分支）、C-54（`import.jte`、`recover`）、C-74（`DatabaseToolMySqlIT` 其余部分） | S3-19a | V-23 ⑤、V-31 ⑤～⑩、⑬、⑭、⑯、⑱、⑲（DBT-3/`RECOVERING` 部分） |
| S3-20 | 安装向导建表 | 后端 | C-56、C-57、C-67、C-69 | G1 | V-25 |
| S3-21a～d | 在线功能（a：资源站在线采集 + 定时采集；b：远程图片；c：规则在线抓取；d：远程存储六种） | 后端 | C-58～C-61 | G1 | V-26 |
| S3-21e | 出站地址固定（v9，OUT-1-5，NET-7） | 后端 | C-76～C-80 | G1（v8→v10 增量评审通过）；不依赖模板与 OP-T；与后端第 1 批同一 feature 分支续做，须在 S3-21a、S3-21b 合入 deploy 前完成 | V-33、`OutboundNetworkGuardTest`、`./mvnw -B verify` |
| S3-22 | 对照执行器与报告 | 测试 | C-64 | S3-03 | 对 PHP 两次运行结果自比一致（V-19 前置） |
| T-FR12 | 附表 C 阶段 A 判定（可与 S3 并行，后台/API/安装部分不依赖模板）；阶段 B 按 GAP- 建单 | 测试 | `docs/test/APPK-2/parity-*.md`、`gap-register.md` | G1 | AC-14、AC-19 |

**已关闭决策（v8，REV-13；取代 v4～v7 的条件门禁）**：OP-T4=a、OP-T5=a、OP-T6=b 已由发起人在 Issue 确认（评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`，本设计三项的唯一决策源）。S3-13b、S3-13c、S3-14c 不再等待决定，按 G1 与表中依赖派单；S3-14h 撤销（并入 S3-14a），deploy 分支不再有“前台 CSP 未落定不得发布”的门禁。建单时 S3-13b、S3-13c、S3-14a、S3-14c 单内引用该确认评论；实现与用例只有一种写法（C-43/C-44 的 SEC-7 白名单、C-68 保留 `PasswordAttempts`、C-75 三项策略），评审按此核对（V-29）。将来要改选，须先由发起人在 Issue 另行确认、再出设计修订，不在子任务里直接改。S3-14 的 PG 组只依赖 S3-13a（内置视图），不依赖 S3-13b/c：页面渲染只经 `PhpTemplateRenderer` 用现有 `read` 读模板，不经 `/template/**`；S3-14 各组的 V-19 子集只含页面样本，样本清单里若有 `/template/**` 地址，归 S3-13c 收尾时核对；S3-15 也不依赖 S3-13b/c（C-42 不再注册 C-43）。

并行关系：S3-19a/b、S3-20、S3-21、T-FR12 与模板线（S3-01～S3-18）互不依赖，可并行；模板线内部按表中依赖串行。PG 组切换顺序按 PRD §13 阶段 2（首页与 vod 先）。

### 7.5 后端（@后端开发-海外）补充步骤

1. 编译流水线先以“编译产物逐字节等于 PHP `runtime/temp` 文件”为唯一验收（V-12），再接解释器；任何编译差异先修编译器，不在解释器里补偿。
2. 标签模型每接一个标签，先在 `TagModelsMySqlIT` 里对同一库与 PHP 输出逐属性对比，再交页面使用。
3. 所有写入（K-12 三张表、数据库还原、`mac_annex`）先登记 `WriteRegistry`，再写 Mapper XML。
4. 不改 `php/`；PHP 黄金脚本只读 `php/` 并在 PHP 7.3.33 下运行。

### 7.6 前端（@前端开发-海外）补充步骤

1. 每个 PG 组：①按 PHP 控制器列出该组每页 `assign` 的键（写在子任务说明里）；②写 `site/web/legacy/*Vars`；③控制器改调 `PhpTemplateRenderer`；④本组 PHP 对照样本跑通；⑤删本组 jte 与已被覆盖的测试。
2. 不改 URL 解析、权限、计数逻辑（BR-4）；ALIGN-1～5 之外发现与 PHP 不同的行为，在 Issue 报给架构师，不自行决定。

### 7.7 用例关注点（@测试人员-海外）

- 编译产物与 PHP `runtime/temp` 的逐字节对比（V-12）是定位空白差异的第一手段。
- 每单元顺序固定：ENV-9 清缓存 → SNAPD-2 恢复并校验 → CMP-2 ③ 改数据 → 登录（如需）→ 请求。
- 回归：六项安全修复（V-24）、`SiteTemplateSelectionGoldenTest`、后台系统配置保存、静态生成首页路径。
- 边界：`paging=yes` 的首页/中间页/末页/越界页（AC-3）；未定义变量；`{maccms:path}` 等四个未定义标签（AC-4）；模板包缺页（BR-12）；模板含不支持语法（500 + 日志行号）。
- 权限：后台无 `template/*` 权限（W-10）；会员组权限页（P 样本）。

### 7.8 流水线与配置（@运营打包-海外）补充

- 新配置项（只写变量名/键名）：`maccms.phptemplate.limits.loop-count`、`limits.output-bytes`、`limits.depth`（RT-8，有默认值）；`maccms.phptemplate.compare.reset-file`（只在对照环境设置）。
- 部署顺序：模板包与配置（§7.2）→ 新版 jar → 启动；回退顺序相反。本期 Q7 不做部署，只在 `config/maccms-backend.env.example` 记录说明（C-11）。

## 8. 验证方式

### 8.1 OP-15 部分（v2，内容不变）

| 编号 | AC- / 前置 | 验证方式 | 具体做法 | 门禁 |
| --- | --- | --- | --- | --- |
| V-1 | AC-1 ①（仓库内） | 人工核对 + 命令 | `git rev-parse <提交>:third_party/maccms-template-v2020.1000.1035/template` = `9398a1ed539cc754b7513c76e12134b3b5b49a53`；`…/template/default_pc` = `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7`；`…/template/default_wap` = `a211f900e060c1d405f2c9d75523e02abac45b80`；`git ls-tree -r --name-only <提交> third_party/maccms-template-v2020.1000.1035/template \| wc -l` = 274 | G2（后端评审） |
| V-2 | AC-1 ①（放置后的工作区） | 脚本 | `python tests/compare/APPK-2/verify_template_package.py .compare/APPK-2/php-site` 与 `…/java-site` 各一次：274 个文件 SHA-1 与附表 E.3 一致，`template/` 下只有两个目录，`default_wap/ads` 不存在 | PRE-2 |
| V-3 | PHPB-9、ENV-3/4 | 脚本 | C-8 输出两侧 13 个 CS-CONFIG 文件的 SHA-256 逐个相同；`maccms.php` 里 C-7 的 7 个键各出现一次且取值正确，其余行与 `php/application/extra/maccms.php` 逐字节相同（`diff` 只出现 7 行） | PRE-2 |
| V-4 | PRE-2（PHP 侧模板选择与路径量） | 接口请求 | 对 `php-site`：① 模板读取根（PATH-1）——桌面 UA（CMP-1）`GET /` 返回 200 且响应体含 `default_pc/html/index/index.html` 的固定文本（由 PRE-2 选定一段不含标签的字面串），手机 UA `GET /` 含 `default_wap/html/index/index.html` 的对应字面串；② `{$maccms.path_tpl}`（PATH-2）——桌面 UA 请求一个引用 `public/include` 的页面（由 PRE-2 选定，例如搜索页，模板 `default_pc/html/vod/search.html:8`），响应体含 `href="/template/default_pc/css/style.css"` 且不含 `/template/default_pc/html/css/`；手机 UA `GET /` 的响应体含 `/template/default_wap/css/style.css` 与 `/template/default_wap/js/jquery.glide.js`，且不含 `/template/default_wap/html/css/`；③ `{$maccms.path_ads}`（PATH-4）——PRE-2 用 `git grep -n "maccms.path_ads"` 查包内页面，有引用的页面断言输出前缀 `/template/default_pc/ads/`（手机 `/template/default_wap/ads/`），没有引用则在基线报告里写“包内无引用”，由 V-9 覆盖 | PRE-2 |
| V-5 | AC-1 ②③、AC-10（Java 侧） | 接口用例 | V-4 的 ①②③ 断言原样对 `java-site` 的 Java 进程执行（FR-1/FR-8 实现后，且 C-12～C-16 已合入），并与 PHP 侧同一请求的响应体按附表 F 比较；再把 `java-site` 的 `template_dir` 改为不存在的目录并重启，请求首页得到模板缺失页（AC-1 ③），其消息按 PATH-3 删去前缀后与 PHP 侧逐字节相同，然后按 ENV-5 恢复 | G3 前的本机对照 |
| V-6 | AC-7 | 对照脚本 | 按 ENV-6、ENV-7 执行 K-01～K-12，比较两侧差异文件的路径集合与字节（CMP-4） | PRE-2 + AC-7 |
| V-7 | AC-8、AC-9 | 端到端 / 接口用例 | 以 §6.1 表格逐行请求两侧后台：列出的目录与文件一致；`template/ads` 不在 `default_wap` 下建目录 | AC-8 |
| V-9 | REV-1、PATH-2～PATH-4（Java 单元） | 单测 | `./mvnw -B test -Dtest=SiteVariantsTest,SiteTemplateSelectionGoldenTest`：C-15 的 `pathVariablesFollowLabelMaccms` 与改后的 `filterResolvesEachRequestFromUserAgentAndHost`、C-16 的 `dec3ValuesSplitThePathConstants` 通过；未改的 `selectionMatchesLegacyInit`（php73/php80 两份黄金）仍通过，证明 `Result` 与 PHP 常量的对应没变 | G2（后端评审） |
| V-8 | 构建不受影响 | 构建命令 | `./mvnw -B -q package -DskipTests` 成功，且 `jar tf target/maccms-backend-0.1.0-SNAPSHOT.jar` 中没有 `third_party/` 条目 | G2 |

构建验证命令（`MULTICA.md` §3）：`./mvnw -B verify`（C-12～C-16 合入后必须全绿）。

回归点：后台系统配置保存（`ads_dir` 改写）、`SiteTemplateSelectionGoldenTest.selectionMatchesLegacyInit`（黄金不变，必须仍通过）、`SiteVariantsTest` 的手机标志、语言、手机 Logo 断言（C-15 只改两行路径期望）、后台模板管理列表与广告页、静态生成首页 `index.html`/`wap_index.html` 的路径。

### 8.2 完整设计的验证方式（v3 新增，V-10 起）

| 编号 | AC- / 前置 | 验证方式 | 具体做法 | 门禁 |
| --- | --- | --- | --- | --- |
| V-10 | AC-1 ②③ | 接口用例 | `java-site` 桌面 UA `GET /` → 200，响应体等于 PHP 侧（附表 F）；`template_dir` 改为不存在目录并重启 → 两侧都是 HTTP 500 的“模板文件不存在”页，逐字节相同；后台首页出现模板缺失提示；响应体不含任何 jte 页面特征（`/static/site/site.css`） | G3 前本机对照 |
| V-11 | FR-2、FR-4（值语义） | 单测（黄金） | `./mvnw -B test -Dtest=PhpSemanticsGoldenTest,InterpreterTest`：C-33 ②的向量逐条相等（字符串化、7.3 弱比较、数组键、`json_encode`、未定义取值） | G2 |
| V-12 | AC-2（编译） | 单测（黄金） | `./mvnw -B test -Dtest=TemplateCompilerGoldenTest,TagLibCompileTest`：附表 E 的 168 个 `.html` 与 PRE-5 语法样例，Java 编译产物（去掉头代码块里的序列化路径与时间）与 PHP `runtime/temp` 黄金逐字节相同 | G2 |
| V-13 | AC-2（渲染、PHP 代码、不支持语法） | 单测 + 对照 | `PhpTemplateRendererTest`：每个语法样例渲染结果等于 PHP 黄金；6 个含 PHP 代码的文件渲染等于 PHP；一个子集外写法（如 `{php}eval('1');{/php}`）返回 500，日志含 `模板文件:行号` 与原文 | G2 |
| V-14 | W-04、V9-001 | 单测 | 改模板文件修改时间后下一次渲染重编译；`CacheResetHook.resetAll()` 后编译缓存与标签缓存为空；`reset-file` 修改时间变化触发清空、未设置时不触发 | G2 |
| V-15 | AC-4 | 单测（黄金） | `TemplateFunctionsGoldenTest`：PRE-5 清单每个函数的样例返回值等于 PHP 黄金；`{maccms:path}`、`{maccms:siteaid}`、`{maccms:sitetid}`、`{maccms:siteid}` 的渲染等于 PHP | G2 |
| V-16 | AC-3 | MySQL 集成测试 + 对照 | `./mvnw -B verify -Pmysql-it -Dit.test=TagModelsMySqlIT`：19 个标签每个属性一个样例，与 PHP 同库输出按附表 F 比较；`paging=yes` 首页、中间页、末页、越界页各一次；合成数据每内容表 ≥ 50 行（AC-3 前置） | G2（集成）+ G3 前对照 |
| V-17 | AC-10（语言键） | 对照脚本 | `run_compare.py --lang`：`zh-cn`、`zh-tw` 各 1773 键，Java `LegacyLang.get` 与 PHP `lang()` 逐字节相同（CMP-5），计入 KPI-5 | G3 前 |
| V-18 | DEC-8、SEC-7（OP-T5=a） | 单测 + 接口用例 | 单测 `LegacySiteFilesTemplateAssetTest`（C-44，S3-13b）：`template/` 下的目录符号链接与文件符号链接，各自指向站点根内的 `application/extra/maccms.php` 和站点根外的一个文件，均拒绝；最终文件本身是符号链接（即使目标在 `template/` 内）拒绝；`template/default_pc` 目录本身、`template/default_pc/a.php`、`template/default_pc/x.PHP` 拒绝；不存在的文件拒绝且不创建目录；`template/default_pc/html/index/index.html`、`template/default_pc/info.ini`、`template/default_pc/css/x.txt` 抛 `FORBIDDEN`；`template/default_pc/css/style.css` 返回字节等于文件（符号链接用例的写法同现有 `LegacyConfigDirectoryTest`）。评审另核：C-43 不含子目录或扩展名判断，C-44 没有运行时开关。接口用例（C-43，S3-13c）：`/template/default_pc/css/style.css` 200 且字节等于文件；`/template/../application/extra/maccms.php`、`/template/default_pc/css/%2e%2e/%2e%2e/%2e%2e/application/extra/maccms.php` 404（不出站点根）；`/template/default_wap/ads/x.js` 404 且不创建目录；`/template/default_pc/html/index/index.html`、`/template/default_pc/info.ini`、`/template/default_pc/css/%2e%2e/info.ini` 均 404；`template/` 下任一 `.php` 404 | G2 |
| V-19 | AC-5、AC-6、AC-10（页面）、AC-20 | 对照脚本 | `run_compare.py --desktop docs/test/APPK-2/sample-desktop.md --mobile docs/test/APPK-2/sample-mobile.md`：224 + 120 个页面样本、12 个模板选择样本，按 CMP-1～CMP-3、NORM-1～5、EX-1/2 判定，报告每样本两行恢复记录；通过数 / 356 | G3 前本机对照 |
| V-20 | PRE-2、CMP-2 | 脚本 | `data_snapshot.py restore` 连续两次后 26 张表 `CHECKSUM TABLE` 与 `AUTO_INCREMENT` 都等于记录值 | PRE-2 |
| V-21 | AC-15、AC-18、KPI-2 | 脚本 | `schema_snapshot.py` 在 SP-1 前后采集，差异行数 0；`./mvnw -B test -Dtest=SchemaGuardTest` 通过；仓库无 `schema.sql`/Flyway/Liquibase | G2 + 验收 |
| V-22 | AC-8、AC-9（Java 侧细项） | 单测 + 端到端 | `AdminTemplateActions` 测试：扩展名 `htm` 允许、`jte` 拒绝；`LEGACY_FILTER` 命中拒绝；版本冲突提示；向导生成的 `vod` 片段经 `PhpTemplateRenderer` 渲染成功（W-09） | G2 + AC-8 |
| V-23 | AC-11、SEC-8（DBT-2 ②～④，v7 按 PHP 整段语义重写） | 单测 + MySQL 集成 + 对照 | `SqlStatementsTest`（C-74）按下表样本断言“是否发送、发送几次、提示”：①**PHP 跳过**：`select * from {pre}vod`、前导换行/制表符的 `  SeLeCt 1`、`/* x */select 1`、`-- x` 换行后 `select 1`、`SELECT 1; INSERT INTO mac_link …`、`insert into mac_link select load_file('/etc/passwd')`、`INSERT … INTO/**/OUTFILE …`、`… DuMpFiLe …`、空输入 → 发送 0 次，提示“执行成功”；②**多语句**：`INSERT …; INSERT …`、`UPDATE …; DROP TABLE mac_x`、`INSERT …; -- c` 换行后 `DELETE …` → 发送 0 次，提示“一次只能执行一条语句”；`INSERT INTO mac_link VALUES ('a;b')`、`UPDATE … SET x='\';DROP'`、`INSERT …;`（末尾单个分号）、`INSERT …; -- 尾注释` → 不算多语句；③**AC-11**：`DROP TABLE`、`TRUNCATE`、`ALTER`、`CREATE`、`RENAME`、`REPLACE`、`WITH … DELETE`、`# c` 换行后 `DROP …`、`/*!50000 DROP TABLE x*/`、`(SELECT 1)` → 发送 0 次，提示“结构类操作请由部署方在库外完成”；`INSERT`/`UPDATE`/`DELETE` 各大小写与前导注释变体 → 发送 1 次，文本与 `{pre}` 替换后的输入逐字相同；④`{pre}` 被替换为表前缀；提示与日志不含 SQL 原文（SUG-1）。`DatabaseToolMySqlIT`（C-74）：以 `it_app` 执行优化/修复各 1 张表（`general_log` 中只有这两条）；上表 ①②③ 的样本提交后 `general_log` 中该批 0 条，四类允许的 DML 各 1 条；对照：上表 ①② 各取一例在 PRE-2 对照环境的 PHP 后台提交，前后 `CHECKSUM TABLE` 不变（证明 PHP 同样不执行）；⑤**还原**：含 `DROP/CREATE` 的备份还原后数据还原、表仍在；备份中只有 `DROP/CREATE`、没有 `INSERT` 的表还原后为空（同 PHP）；前后 SNAP 差异 0 | G2 + 验收 |
| V-31 | DBT-5（REV-7、REV-10、REV-11、REV-14、REV-15） | 单测 + 跨进程集成 + MySQL 集成 | `DatabaseRunsTest`（单测，测试侧 `DataSource` 包装注入故障）：①**DBT-2 结果分类**：服务端返回 SQLState `42S02` → `DONE`/`outcome=FAILED`、不进入待处理；单值 `INSERT` 返回 `23000` → 同上；多行 `UPDATE` 返回非连接错误 → `UNKNOWN`、待处理；抛 `08S01` → `UNKNOWN`、无任何补偿语句、不重试。②**同时起跑**：同一 JVM 两个线程用 `CyclicBarrier` 同时调 `begin`，重复 200 轮，每轮恰好一个成功、另一个被拒且发出 0 条语句。`DatabaseRunsLockIT`（跨进程，`ProcessBuilder` 启动 C-74 的 `DatabaseRunsLockProbe`，同一临时站点根）：③两个子进程等待同一个起跑信号文件后同时 `begin`，重复 50 轮，每轮恰好一个输出 `ACQUIRED`、另一个输出 `BUSY`，`dbtool-run.json` 始终是一个合法记录；④**持锁拒绝与被杀识别**（以 DBT-2 为例）：子进程拿锁、写 `RUNNING` 后挂起，父进程 `begin`、`delete`、页面加载 → 前两者拒绝“已有数据库操作在执行”（0 条语句、0 个文件被删），页面显示“执行中”且无操作按钮，记录仍为 `RUNNING`；随后 `destroyForcibly()` 子进程，父进程再 `begin` → 记录被改为 `INTERRUPTED`（待处理）、本次 `begin` 被拒、`acknowledge` 可用（其他操作类型的分支见 ⑪～⑯）；日志行字段齐全且不含 SQL 原文与数据值。`DatabaseToolMySqlIT`（一次性库 `maccms_it_*`）：⑤**中途失败与两种恢复**：备份含表 A、B、C，B 的第 3 条 `INSERT` 构造主键冲突 → A 已还原、B 部分插入、C 未动，记录 `FAILED` 指向 B，自动备份各卷哈希已记录；此时普通还原、执行 SQL、导出、删除该自动备份都被拒；`recover(AUTO_BACKUP)` → `RECOVERING` → `RECOVERED`，A、B、C 的 `CHECKSUM TABLE` 与还原开始前相同，期间另一会话的 `begin` 被拒；修正备份后另跑一轮，`recover(SOURCE)` → A、B、C 等于备份内容；⑥**恢复再失败**：`recover` 时在 B 表注入 `08S01` → `RECOVERY_UNKNOWN`，仍待处理；再次 `recover` 成功 → `RECOVERED`，`recovery.attempt=2`；⑦**确认规则**：`RECOVERED` 时 `acknowledge` → `DONE`；`FAILED` 时不勾选“接受当前数据”的 `acknowledge` 被拒，勾选后 → `DONE`、`accepted_as_is=true`；⑧**自动备份被改**：篡改自动备份一个分卷后 `recover(AUTO_BACKUP)` 被拒、0 条语句、记录不变；⑨**站点未关闭**：`site_status=1` 时还原与 `recover` 都被拒，`general_log` 中无 `DELETE`/`INSERT`；⑩**连接中断**：在 B 表执行期间由 `it_admin` `KILL` 应用连接 → 记录 `UNKNOWN`，`general_log` 中断点之后无该运行的语句（不补偿、不重试）；重复还原同一份正确备份两次，26 张表 `CHECKSUM TABLE` 两次相同（幂等）。**按操作类型与阶段的被杀断言（v8，REV-15；`DatabaseToolMySqlIT` 的跨进程部分：子进程 `DatabaseRunsLockProbe` 连接同一一次性库执行指定操作，测试侧 `DataSource` 包装在指定点写出 `PAUSED` 后挂起，父进程 `destroyForcibly()`；父进程随后只打开一次数据库工具页面，断言记录、页面按钮、`maccms.admin.dbtool` 日志行与数据，均对照 DBT-5 “进程中断后的唯一转移”表）**：⑪DBT-1 在第 2 张表的 `OPTIMIZE` 前被杀 → `DONE`、`outcome=INTERRUPTED`、`tables_done=1`，不进入待处理；页面一行灰色说明、无 `recover`/“我已处理”，随后 DBT-1 可直接再执行；一行 `WARN`；⑫DBT-2 分别在“写 `RUNNING` 后、发送前”与“语句执行中（`SELECT SLEEP` 包装挂起）”被杀 → 两次都为 `INTERRUPTED`（待处理）；页面只有“我已处理”且未勾选“接受当前数据”时被拒，没有 `recover`；DBT-1/DBT-2/DBT-3/导出 `begin` 被拒、0 条语句；一行 `ERROR`；⑬DBT-3 在 `phase=AUTO_BACKUP`、第 1 个自动备份分卷写到一半时被杀 → `DONE`、`outcome=INTERRUPTED_BEFORE_WRITE`；`export_files` 列出的分卷与值相符的 `backup.lock` 被删除、其他备份文件逐个比对不变；被还原表的 `CHECKSUM TABLE` 与开始前相同，`general_log` 中该运行无 `DELETE`/`INSERT`；不进入待处理，页面无恢复按钮，同一备份可立即重新发起还原；一行 `WARN`（含清理的文件名）；⑭DBT-3 在 `phase=RESTORE`、B 表 `INSERT` 执行中被杀 → `INTERRUPTED`（待处理），`auto_backup` 各卷哈希完整；页面有 `recover`（两个目标）与“我已处理”（须勾选）；普通操作与删除绑定备份被拒；`recover(AUTO_BACKUP)` 后 A、B、C 的 `CHECKSUM TABLE` 等于还原前；一行 `ERROR`；⑮导出在第 2 张表第 1 批 `SELECT` 后被杀 → `DONE`、`outcome=INTERRUPTED`；半成品分卷与值相符的 `backup.lock` 被删除，备份列表中无该时间戳，之后导出成功；另一轮在被杀后把 `backup.lock` 改写为其他值（模拟 PHP 后台）→ 不删除它，`interrupted.cleanup=LOCK_KEPT`，页面列出残留文件，之后导出按 `lock_check` 被拒；一行 `WARN`；⑯`recover` 在 `RECOVERING`、B 表执行中被杀 → `RECOVERY_INTERRUPTED`（待处理），`recovery.attempt` 不变；页面有 `recover`（两个目标）与“我已处理”（须勾选）；再次 `recover(AUTO_BACKUP)` → `RECOVERED`、`attempt` 加 1、A、B、C 等于还原前；一行 `ERROR`。**删备份（v8，REV-14）**：⑰子进程持锁挂起时，父进程删除任一备份（含非绑定）→ 拒绝“已有数据库操作在执行”，目录文件逐个比对不变（0 个删除）；没有待处理记录时删除成功、返回与 B15 相同；单测断言按卷号升序删除，删到第 2 卷前被杀后留下的备份在还原预检 (a) 判“文件损坏”；⑱存在 DBT-3 待处理记录（`FAILED`、`RECOVERED` 各一轮）时，删除其 `source_backup` 或 `auto_backup` 时间戳 → 拒绝“该备份是未处理数据库操作的恢复点”、0 个删除；删除另一个非绑定备份 → 成功；两个子进程同时发起 `recover(SOURCE)` 与删除原备份，重复 50 轮：每轮或删除被拒（锁忙或绑定保护）、或 `recover` 被拒（锁忙），绑定备份的分卷与哈希始终完整，`recover` 若执行则 A、B、C 等于备份内容；⑲**页面判定幂等**：⑪～⑯ 每个场景中第二次打开页面不再改写记录、不再写日志，按钮与第一次相同 | G2 |
| V-32 | REV-8（`SiteHtmlRenderer` 调用方迁移）、REV-9、REV-13（OP-T6=b） | 检索 + 单测 + 接口用例 + 人工浏览器核对 | ①S3-15 合入前：`git grep -n 'SiteHtmlRenderer\|CONTENT_SECURITY_POLICY\|PlayerFramePolicy\|memberPolicy' -- src/main/java/com/maccms/backend/site src/test/java/com/maccms/backend/site` 无结果（v7 更正：限定 `site` 包，`admin`/`install` 的同名常量不在范围），`git grep -n 'Content-Security-Policy' -- src/main/java/com/maccms/backend/site` 只在 `SitePageResponses`（设置头）与 `SiteSecurityHeaders`，`git grep -n 'new MediaType("text", "html"' -- src/main/java/com/maccms/backend/site` 只剩 `SiteRequests`；②`SitePageResponsesTest`：头部为 `Content-Type: text/html;charset=UTF-8`、`Content-Length`、`Cache-Control`、`X-Content-Type-Options: nosniff`，`varyUserAgent` 时有 `Vary: User-Agent`；CSP 头等于 `SiteSecurityHeaders.frontPolicy()`，且 `SitePageResponsesTest` 断言其原文恰为 `object-src 'none'; base-uri 'self'; frame-ancestors 'self'`（OP-T6=b），不含 `script-src`；③`SiteTestRoutes` 的 `/boom-large`：响应为完整 500 页，不含 `SYNTHETIC-PARTIAL-CHUNK`，状态码不是 200；④`SearchThrottleTest`：第 1 页过快 → HTTP 200、`public/jump` 页面、消息为 `lang('search_frequently')+timespan+lang('seconds')`、无 `Retry-After`，第 2 页不计时；⑤计数接口：非 ajax 的 POST `hits/digg/score` 返回与 PHP 同请求相同的 JSON（PRE-2 基线）；⑥各 PG 组收尾时核对本组页面（S3-14a 跳转页、S3-14c 播放页、S3-14f 支付页）：真实浏览器中 `public/jump` 倒计时跳转、播放页播放器脚本、会员支付下单页的网关表单都可执行，浏览器控制台无 CSP 拦截报告（人工核对一次，截图附交付说明）；另核对官方模板页面没有 `<object>`/`<embed>`/`<base>` 被拦（S3-04 模板扫描给出出现位置） | G2 |
| V-24 | BR-4（六项安全修复） | 回归 | 各批次已有的安全修复用例（`MemberAccountService` 登录/注册/注销/改密、`TimmingAccess`、`ClientIpResolver`、`AdminLoginService` 相关测试类）在全部 S3 合入后仍通过 | G2 |
| V-25 | AC-12 | MySQL 集成 + 验收 | `InstallSqlFileTest` + `InstallWizardMySqlIT`：常量等于 `48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4`，也等于 `git show a57e9cb:php/application/install/sql/install.sql \| sha256sum` 与工作区文件的 SHA-256（C-67 生效后，Windows `autocrlf=true` 检出也一致），文件不含 `\r`；空库执行后 SNAP 等于附表 A 基线；已有 26 表的库提示“已存在数据表”且无 DDL；1 字节差异的文件、整文件改为 CRLF 的文件（SHA-256 `152e5424…`）都提示“文件哈希不一致”、`mac_` 表 0 张；`it_app` DDL 数 0 | G2 + 验收 |
| V-26 | AC-13 | 集成测试（本机模拟） | `OutboundPolicy.LOOPBACK_ONLY` 下，五项各对本机模拟资源站/存储执行，结果与 PHP 对同一模拟数据一致；`OutboundNetworkGuardTest`、`RegisteredOutboundClientTest` 通过；抓包或连接日志无本机以外地址 | G2 + 验收 |
| V-33 | OUT-1-5（契约 v1.3.0）；DEC-14 | 单测 + 检索 + 回归 | `OutboundAddressPinningTest`（本机模拟服务，经 C-77/C-78 的包内构造器把禁止判定换成“只禁止 `127.0.0.2`”，其余规则照旧）：①重绑定：主机名用 `rebind.invalid`（`.invalid` 系统 DNS 必然解析失败），注入的 `Resolver` 第 1 次返回 `127.0.0.1`、之后返回 `127.0.0.2`；请求成功到达 127.0.0.1 上的模拟服务、收到的 `Host` 为 `rebind.invalid:<端口>`，`Resolver` 每跳只被调用 1 次——若引擎再解析一次，请求会因系统 DNS 失败或命中 127.0.0.2 而失败；②跳转：第 1 跳 302 到 `hop2.invalid`，第 2 跳同样按 `Target` 固定、`Resolver` 对 `hop2.invalid` 只调用 1 次；跳转目标解析到 `127.0.0.2` → `TARGET_REJECTED`，且 127.0.0.2 上的监听没有收到连接；③TLS：本机 HTTPS 模拟服务用 `pin.invalid` 证书，客户端信任该证书，请求 `https://pin.invalid:<端口>/` 成功，服务端记录的 SNI（`ExtendedSSLSession.getRequestedServerNames`）为 `pin.invalid`；换成 `other.invalid` 证书 → `IO_ERROR`（证书主机名不符）；④代理：`ProxySelector.setDefault` 设为指向本机“代理”监听的选择器（`finally` 恢复），请求仍直连目标、选择器未被调用、代理监听无连接；⑤受限头：带 `Host` 头调用 → `IllegalArgumentException`；⑥请求头断言：模拟服务收到的请求无 `Accept-Encoding`、无 `Cookie`。`PinnedDnsResolverTest`：⑦固定主机返回地址副本，其他主机（含大小写与末尾点变体以外的名字）抛 `UnknownHostException`；`git grep -n 'getAllByName\|getByName' -- src/main/java/com/maccms/backend/integration/http/PinnedDnsResolver.java` 无结果。回归：`OutboundPolicyTest`（只改取值方式，用例与断言范围不减）、`RegisteredOutboundClientTest`、`UnregisteredCallerTest`、`OutboundNetworkGuardTest` 及其余出站调用方测试（`AddonMarketGoldenTest`、`FastadminAddonMarketTest`、`BacklinkFetcherTest`、`OAuthLoginTest`、`PaySafetyTest`、`PaymentGoldenTest`、`SmsSenderTest`、`BaiduUrlPushTest`）不改断言全部通过，`./mvnw -B verify` 通过；有 MySQL 环境时 `OAuthGoldenMySqlIT`、`PaymentGoldenMySqlIT`、`SmsMailGoldenMySqlIT` 通过（没跑须在实现说明写明）；实现说明附 `./mvnw -B dependency:tree -Dincludes=org.apache.httpcomponents.*` 输出，只出现 `httpclient5` 及其传递的 `httpcore5`/`httpcore5-h2` | G2 |
| V-27 | AC-14、AC-19 | 人工核对 | `parity-*.md` 379 行无“未判定”；`gap-register.md` 无非终态 GAP- | 本期完成检查 |
| V-28 | AC-16 | 人工核对 | 评审每个 S3 子任务的“读写的表与字段”按附表 D 四步判定，无越界；`WriteRegistry` 新增登记与附表 D 一致 | G2（后端评审） |
| V-29 | AC-17 | 人工核对 | 每个子任务派单前与收尾时，单内引用的 OP-/OP-T 均已关闭；S3-13b、S3-13c、S3-14a、S3-14c 单内引用确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`（OP-T5=a、OP-T6=b、OP-T4=a）；实现与用例只含所选答案：C-44 有且只有 SEC-7 白名单、无运行时开关；C-43 的 `Content-Type` 表只含白名单扩展名；`SiteSecurityHeaders.FRONT_POLICY` 原文等于三项策略；`PasswordAttempts` 仍在且只被 `ajax/pwd` 调用 | 派单前 + 每单收尾 |
| V-30 | AC-5（`ajax/pwd`）、ALIGN-4、SEC-9（OP-T4=a） | 接口用例 + 对照 | 1001（缺参）、1002（本会话已通过后再验）、1003（同一会话 5 秒内第二次，含第一次被拒的情况）、1012/1013/1014/1022（密码错）、1（通过）与 PHP 同请求序列的 JSON 逐字节相同；每次换新会话、对同一内容在 60 秒内第 11 次请求得到 1003 且不查库（`general_log` 无该内容的 `SELECT`），HTTP 200；该超限样本按 SEC-9 判 `排除-SEC-9`，不进逐字节比较 | G2 + 对照 |

构建验证命令（`MULTICA.md` §3）：开发中 `./mvnw -B test -Dtest=<类名>`；交付前 `./mvnw -B verify`；MySQL 集成 `./mvnw -B verify -Pmysql-it`（`MACCMS_IT_ADMIN_URL`/`_USERNAME`/`_PASSWORD`）；打包 `./mvnw -B -q package -DskipTests`。

运维日志字段（v6，SUG-2）：模板资源拒绝（C-43）记 `DEBUG` 一行 `asset_denied reason=<FileException 原因>`，不写请求路径原文；模板编译/执行不支持写法（`TemplateUnsupportedException`）记 `ERROR`，字段 `template`（站点根相对路径）、`line`、`kind`，不写变量值；安装哈希不一致记 `WARN`，字段 `expected`、`actual`、`bytes`；出站护栏拒绝沿用 `OutboundHttpClient` 现有日志；数据库工具见 DBT-5 ⑥。部署方按 `ERROR` 级别告警。

回归点（v3 增补）：后台（B12～B15）全部现有测试；`InternalPageRendererTest`（改为 PHP 模板后的静态生成）；`PlayerConfigController` 输出；`Provide`、`Receive` 接口不受影响（不经模板）；会员登录态与验证码；`SiteVariantsTest`、`SiteTemplateSelectionGoldenTest`。

## 9. 回滚

| 步骤 | 回退方式 | 顺序 |
| --- | --- | --- |
| C-1～C-5 | `git revert` 入库提交；删掉 `third_party/maccms-template-v2020.1000.1035/` 不影响任何运行代码（jar 不含它） | 可独立回退 |
| C-6～C-10 | `git revert`；删除本机 `.compare/APPK-2/` 目录（只在本机，不影响仓库） | 可独立回退 |
| C-11 | `git revert` 说明文字 | 可独立回退 |
| C-12～C-16 | `git revert` 该单独提交；FR-1 接入前基线没有读取 `SiteVariant.pathTpl` 的产品代码，回退无可见影响；FR-1 接入后回退会让资源地址多出 `html/`，须与 FR-1 一起回退 | FR-1 接入后：先回退 FR-1，再回退本项 |
| 部署方的配置（DEC-3） | 把 `template_dir`/`mob_template_dir` 改回原值并重启；模板目录可保留不删 | 先改配置、后删目录 |

（v2 部分）没有不可逆改动：不动数据库，不改 `php/`，不改线上配置。v3 部分的不可逆项见下。

**完整设计（v3 新增）的回退**

| 步骤 | 回退方式 | 顺序 |
| --- | --- | --- |
| S3-06～S3-12（`phptemplate` 模块） | `git revert` 各提交；模块未被 `site` 使用前回退无可见影响 | 先回退依赖它的 S3-14 |
| S3-13a、S3-14（内置视图、页面接入，按 PG 组） | 每组一个提交，`git revert` 该组即恢复该组 jte 页面与测试（jte 文件在同一提交里删除，回退即恢复）；该组迁出的 `SiteHtmlRenderer` 调用方（§6.8 迁移表）随之恢复为注入渲染器——S3-15 之前 `SiteHtmlRenderer` 仍在，所以可编译 | 按组倒序；S3-15 之后须先回退 S3-15；S3-14a 含共享边界 C-70，须在 b～g 全部回退之后才能回退 |
| C-70～C-72（`SitePageResponses`、`JumpPages`、三个漏列调用方与测试） | 随所属 PG 组提交回退（迁移表“子任务”列）；不单独回退，避免出现既不用渲染器、也没有共享边界的中间状态 | 同上 |
| C-75（前台 CSP，v8 随 S3-14a） | 随 S3-14a 回退（S3-14h 已撤销，没有单独提交）；只需改策略时（发起人另行改选后），单独提交改 C-75 一处、可单独 `git revert`，不涉及模板与数据 | 随 S3-14a |
| C-76～C-80（v9，出站地址固定） | 五项同一提交，整体 `git revert`；回退后恢复 JDK 客户端与重绑定窗口，即 OUT-1-5 不再满足，S3-21a/b 已合入时不得在“OUT-1 已满足”的前提下发布，须先报调度 | 可独立回退 |
| S3-15（jte 与 D4 删除） | `git revert`，恢复 `sitetemplate`、`SiteHtmlRenderer`、`PlayerFramePolicy`、`SiteConfiguration` 中的渲染器装配与 `SiteConfigurationTest` 的 Bean 断言、插件 execution、依赖 | 最先回退 |
| S3-16～S3-18 | `git revert`；模板管理回到 jte 版（依赖 `sitetemplate`，所以 S3-15 已合入时须先回退 S3-15） | S3-15 之后 |
| S3-19a/b（数据库工具） | `git revert` 即恢复“四项拒绝”（先回退 b 再回退 a）；回退前先确认 `dbtool-run.json` 不处于待处理状态（处于待处理则先按 DBT-5 ⑦ 处理完，否则回退后旧代码看不到待处理提示）；站点根下残留的 `dbtool-run.json`、`dbtool.lock` 与自动备份文件不影响旧代码（旧代码不读它们，备份列表只认 `Ymd-His-*.sql*`，自动备份按同名规则仍可被 B15 列出、删除）；回退 a 后导出恢复为 B15 的 `backup.lock` 检查、`delete` 恢复为 B15 的不取锁删除（v8：所以回退前必须先处理完待处理记录，否则绑定备份失去保护）；v8 的中断清理只删记录点名的半成品分卷与值相符的 `backup.lock`，它们不是可用备份，回退不需要恢复 | 独立 |
| OP-T4 / OP-T5 / OP-T6 的决定事后改变（v8） | 现行实现只有已确认的一种（OP-T4=a、OP-T5=a、OP-T6=b）。改选须先由发起人在 Issue 确认、再出设计修订；届时改动只在 C-44（放行范围）与 C-43（`Content-Type` 表）、C-68（`PasswordAttempts` 调用点）、C-75（CSP 取值），各自单独提交、可单独 `git revert`，不涉及数据 | 独立 |
| S3-20（安装） | `git revert` 即恢复“只读校验” | 独立 |
| S3-21（在线功能） | `git revert`；`IntegrationPackages` 登记随之撤销 | 独立 |
| 数据 | 本期不改结构；新增写入只是 DML（`*_time_make`、`mac_annex`、还原的 `DELETE/INSERT`），代码回退后不需反向迁移 | — |
| 配置 | 新配置项都有默认值，删除配置即回到默认；`compare.reset-file` 生产不设 | — |

**不可逆项**：数据库工具“还原备份”（DBT-3）与“执行 SQL”（DBT-2）会改数据，属于功能本身的语义（与 PHP 相同），由管理员操作；代码回退不恢复已改数据，单列 RISK-T13。可逆替代（v6，DBT-5；v7 改为专用恢复动作）：DBT-3 每次执行前自动导出将被还原的表并记哈希，失败后只能经 `recover`（DBT-5 ⑨）用该次运行绑定的自动备份退回，数据回到还原开始前，期间保持互斥、记录保持待处理；DBT-2 没有自动备份，页面要求先导出相关表，回退靠管理员事先导出的备份。两者中途失败都不自动补偿，状态与恢复步骤见 DBT-5 ③⑥⑦⑨⑩ 与状态机。删备份本身不可逆（B15 现状，与 PHP 相同）；v8 只加保护：拿不到锁不删，待处理记录绑定的恢复点不能删（DBT-4）。

## 10. 风险与待决

| 编号 | 风险 | 触发条件 | 缓解 | 残余 |
| --- | --- | --- | --- | --- |
| RISK-T1 | 换行转换改写模板字节，SHA-1 与附表 E.3 不符、对照出现 `\r\n` 差异（NOIGN-4） | 本机 `core.autocrlf=true`；先 `add` 后加 `.gitattributes`；从工作区复制 `php/` | C-5 `-text`，先提交 `.gitattributes`；ENV-1 一律 `git -c core.autocrlf=false archive` 导出；V-1、V-2 校验 | 无 |
| RISK-T2 | 对照环境 `mob_status=2` 与仓库基线 `0` 不同 | 对照时 | 只写在对照副本（C-7），不改 `php/`；桌面样本用桌面 UA，不受影响；12 个模板选择样本走 `SiteTemplateSelectionGoldenTest` 的独立黄金，不读此值 | 生产站点的 `mob_status` 由部署方决定，不在本期验收 |
| RISK-T3 | `mob_ads_dir=ads` 但 `default_wap` 没有 `ads/`，手机页面引用的广告脚本 404 | 手机页面含 `{$maccms.path_ads}` | 两侧同为“文件不存在”，PHP 不会建该目录（`Template::ads` 只用桌面目录）；Java 实现不得在页面渲染时建目录（V-7 检查） | 无 |
| RISK-T4 | Java 静态生成多写登记文件，被当成多余生成文件 | AC-7 | ENV-7 明确排除 | 无 |
| RISK-T5 | 某单元的后台保存改写配置，后续单元两侧读到的配置不同 | FR-12 后台配置样本 | ENV-5 每单元前比对并恢复 13 个文件 | 无 |
| RISK-T6 | 已有站点升级后未放模板包或未改配置，前台全部“模板文件不存在” | 部署方漏做 C-11 的步骤 | C-11 写明部署顺序；AC-1 ③ 已覆盖缺目录时的错误页与后台提示 | 部署方责任，本期不部署（Q7） |
| RISK-T8 | 黄金键 `path_tpl` 实为 `MAC_PATH_TPL`，实现者按键名把它当作模板变量，再次引入 REV-1 的错误 | FR-1 实现读黄金或 `Result.pathTpl` 时 | C-13、C-14 改正注释，C-16 在比较处加注释；V-9 锁定 PATH-2；V-4/V-5 ② 断言响应里没有 `/html/css/` 前缀 | 无 |
| RISK-T7 | OP-16 答 b（补页面）时目录要不要变 | 发起人选 b | 补充文件放进 `template/default_pc/html/`、`template/default_wap/html/` 的对应相对路径即可，目录与配置不变；但补充文件不属于官方包，须另起来源目录并修订 BR-11/附表 E 与本设计 | 待 OP-16 |

**完整设计（v3 新增）的风险**

| 编号 | 风险 | 触发条件 | 缓解 | 残余 |
| --- | --- | --- | --- | --- |
| RISK-T9 | 编译流水线移植时正则语义不同（PHP PCRE 与 Java `java.util.regex` 在占有量词、`\s`、`/s`、`/i` 上的细节） | 移植 C-20～C-22 | 正则逐条写单测，并以 V-12 编译产物逐字节对比兜底；Java 侧显式写出 `(?s)`、`(?i)`，`\s` 按 PCRE 的 ASCII 空白集合 | 低 |
| RISK-T10 | PHP 7.3 值语义（弱比较、浮点字符串化、`json_encode`）复刻不全 | 解释器、函数 | C-33 黄金向量（PHP 7.3.33 生成），V-11、V-15；规则只以黄金为准 | 低 |
| RISK-T11 | 数据库行值类型（RT-6）与 PHP 不同，导致 `{if}` 分支或数字输出不同 | 标签模型 | RT 黄金在 PRE-2 库上由 PHP 导出每列的 `gettype`，`TagModels` 按表转换，V-16 对比 | 低 |
| RISK-T12 | 34 个 jte 页面测试改写工作量大、回归面大（PRD RISK-7） | S3-14 | 按 PG 组随页面切换一起改，AC-5 对照覆盖后再删；每组一个可回退提交 | 中：工期 |
| RISK-T13 | 数据库还原/执行 SQL 改数据不可随代码回退；MyISAM 下中途失败留下部分结果 | 管理员操作；SQL 错误、连接中断、并发写入 | DBT-5：整批预检、逐条状态与失败分类、跨进程锁、运行记录状态机与提示条、还原前自动备份（带哈希）、`recover` 专用恢复、站点关闭前置、人工恢复步骤；验收只在一次性库（V-31） | DBT-2 无自动备份，靠管理员先导出；“结果不确定”须人工核对 |
| RISK-T18 | 前台 CSP 从基线严格策略放宽为三项后，XSS 纵深防护减弱 | S3-14a 起（OP-T6=b） | 发起人已确认 OP-T6=b（评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`）；保留 `object-src`、`base-uri`、`frame-ancestors` 三项；模板变量的转义按 PHP 模板原样（BR-11）；保留 `nosniff`；改动只在 C-75 一处（§9） | 不限制脚本；与 PHP（不发 CSP）相比多三项防护；发起人已接受 |
| RISK-T19 | 还原或恢复期间其他管理员或定时任务写入被还原的表 | DBT-3、`recover` 执行中 | 站点关闭前置（DBT-5 ② d、⑨）挡住前台；工具内跨进程锁挡住其他数据库操作；确认步骤提示停定时任务与其他编辑；失败按 DBT-5 ⑦ 恢复 | 低：运维约定，未加库级锁 |
| RISK-T20 | 数据库工具的文件锁在网络文件系统上失效，或 PHP 与 Java 后台同时对同一库使用数据库工具 | 站点根放在 NFS/SMB；切换期两套后台并存 | 部署说明写明站点根须在本机文件系统、同一库只由一套后台使用数据库工具；Java 持锁后仍检查 PHP 的 `backup.lock`；删备份也在同一把锁内（v8）；V-31 ②③ 只在本机文件系统上验证 | 低：部署约束，本期不部署 |
| RISK-T21 | （v8 关闭）v7 的“过渡期严格 CSP 拦截模板脚本、S3-14h 合入前不得发布”随 OP-T6=b 关闭不再存在：C-75 自 S3-14a 起即为三项策略，S3-14h 撤销 | — | — | 无 |
| RISK-T22（v8） | 中断清理误删文件 | 导出或还原前自动备份被杀后的中断判定 | 只删 `export_files` 点名、由本次运行创建的分卷（每卷先记名再创建），`backup.lock` 只在内容等于 `backup_lock` 时删除；不按通配删除；清理结果写入记录与日志（DBT-5 ⑩）；V-31 ⑬⑮ | 低 |
| RISK-T23（v9） | 换出站引擎改变现有调用方的线上行为（请求头、TLS、超时、错误分类） | C-78 合入后全部出站调用 | NET-7 ⑤⑥ 逐项写死与基线一致的行为；现有 12 个出站测试类与 3 个 MySQL IT 原样回归（V-33）；不开压缩、不自动重试、不读系统代理 | 每跳不复用连接，同一主机的批量图片下载比基线多 TLS 握手（与 PHP 每次新建 curl 句柄相同，可接受）；TLS 协议与套件取 JDK 默认 `SSLContext` |
| RISK-T14 | 在线采集、远程存储的真实对接未验证（Q6 无外部沙箱） | 上线后 | 只用本机模拟验收（OP-5）；PRD RISK-9 已登记 | 发起人已接受 |
| RISK-T15 | 渲染性能：解释执行比预编译 jte 慢 | 高流量 | 编译产物缓存为语法树（不重复解析）；Q11 只记录指标、不设门槛；PHP 同样是解释执行 | 记录指标 |
| RISK-T17 | `install.sql` 在 Windows `core.autocrlf=true` 工作区被检出为 CRLF，SHA-256 与常量不符，安装向导拒绝执行（v3 的 `152e5424…` 就是这样算错的） | 在 Windows 上 clone 后直接运行或打包站点根 | C-67 `text eol=lf`；INS-1 常量按 blob 计算；C-57 断言文件不含 `\r`；V-25 覆盖 CRLF 拒绝 | 已有 Windows 工作区在 C-67 合入后须删掉该文件再 `git checkout -- php/application/install/sql/install.sql` 一次（S3-20 交付说明写明） |
| RISK-T16 | “启动时读一次配置”的站点 Bean 与 PHP 每请求读配置不同，改配置后两侧短暂不一致 | F-D2、后台保存配置后 | 对照按 CACHE-4 重启；是否改为即时生效由 FR-12 判定 | 待 FR-12 |

**技术待决（v3 更新）**

| 编号 | 问题 | 状态 / 决定 | 是否阻塞开发 | 需谁回答 | 期限 |
| --- | --- | --- | --- | --- | --- |
| OP-T1 | `/template/**` 静态资源由谁提供 | **v3 已决**：Java 带白名单提供（DEC-8、§4.8、§6.10），nginx 可选直出同一目录 | 否 | — | — |
| OP-T2 | 应用层缓存隔离（V9-001） | **v3 已决**：CACHE-1～CACHE-4 + ENV-9 | 否 | — | — |
| OP-T3 | `php/vendor/` 恢复、PHP 侧会话时区 | **v3 已决**：SNAPD-4、SNAPD-5 | 否 | — | — |
| OP-T4 | 观看密码在 PHP 已有的“按会话 5 秒一次”限制（`Ajax.php:416-418`，1003）之外，是否再保留 Java 的按内容/全站限流 `PasswordAttempts`（SEC-9）。PHP 的 5 秒限制换一个会话就能绕过；`PasswordAttempts` 能限住换会话的爆破，代价是同一内容被大量试错时其他访客 60 秒内也会收到 1003 | **已关闭（v8）：a**——发起人确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`（调度核验 `01a194e9-f7f0-7bf0-a6cc-09af877c86bd`）。保留 `PasswordAttempts`，超限返回 PHP 已有的 1003，按 SEC-9 记为已确认的安全差异（ALIGN-4 ⑤、C-68、V-30）；ALIGN-4 ①～④⑥ 按 PHP 恢复。未选的 b（删除 `PasswordAttempts`）不出现在实现与验证中 | 否（S3-14c 按 G1 与依赖派单） | — | — |
| OP-T5 | SEC-7（`/template/**` 不返回 `.html`/`.ini` 等模板源码与配置）是六项安全修复之外的安全收紧，与“PHP 怎么做 Java 就怎么做”不同。**v6 范围缩小**：v5 把 SEC-8（执行 SQL 拒绝 `INTO OUTFILE/DUMPFILE`、`LOAD_FILE`）也列在这里，并写“PHP 照常执行”，这与 PHP 源码不符——`Database.php:251-262` 对含 `outfile`/`dumpfile`/`load_file` 的语句同样不执行，所以 SEC-8 不是选择题，v6 改为与 PHP 一致的无条件行为（v7 起为 DBT-2 ②），不再需要发起人回答 | **已关闭（v8）：a**——发起人确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`。接受 SEC-7：`/template/**` 只给白名单静态资源，按已确认的安全差异记录，相关地址不进页面比较样本（§6.10、C-43、C-44、V-18）。未选的 b（按 PHP 原样返回 `.html`/`.ini`）不出现在实现与验证中 | 否（S3-13b、S3-13c 按 G1 与依赖派单） | — | — |
| OP-T6（v7，REV-9） | Java 前台现有 `Content-Security-Policy`（基线 `SiteHtmlRenderer.java:30-31`：`script-src 'none'`，会员页按 nonce 放行，播放器内页另有策略）会拦住 PHP 模板与内置视图的脚本（`public/jump.html:23` 倒计时、`player.js` 的 `document.write`、支付网关自动提交表单）；PHP 前台不发该头。删除或放宽它属于六项安全修复之外的安全取舍 | **已关闭（v8）：b**——发起人确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`。前台只发 `object-src 'none'; base-uri 'self'; frame-ancestors 'self'`，不限制脚本，保留防插件对象、防 `<base>` 改写、防被第三方页面嵌套三项（`frame-ancestors` 使站点不能被其他网站用 iframe 嵌入）；C-75 随 S3-14a 落地即为此值，S3-14h 与发布门禁撤销（§4.11 方案 E、§6.8、V-32 ②⑥）。未选的 a（不发 CSP）不出现在实现与验证中 | 否 | — | — |

## 11. 需求追溯

| 需求 | 方案要点 | 改动清单行 | 验证行 |
| --- | --- | --- | --- |
| OP-15 ①模板包放哪 | DEC-1、DEC-2 | C-1～C-5、C-11 | V-1、V-2 |
| OP-15 ②五个目录配置项 | DEC-3（另 `mob_ads_dir`、对照 `mob_status`）及推出的 PATH-1～PATH-4 | C-7、C-8、C-11；C-12～C-16（Java 的 PATH-2 映射） | V-3、V-4、V-5、V-9 |
| OP-15 ③PHP 对照环境 | DEC-4、ENV-1～ENV-8 | C-6～C-10 | V-2、V-3、V-4 |
| OP-15 ④后台模板管理目录 | DEC-5、§6.1 | 本版不改 `AdminTemplateActions`；一致性由 V-7 对照确认 | V-7 |
| OP-15 ⑤静态生成读写目录 | DEC-6、§6.2、ENV-6/7 | C-8 | V-6 |
| FR-1、AC-1、BR-11 | 原样入库、原样放置、按 E.3 校验；模板变量按 PATH-2/PATH-4、缺失消息按 PATH-3 | C-1～C-5、C-9、C-12～C-16 | V-1、V-2、V-5、V-9 |
| FR-6、AC-7、BR-6 | §6.2（含 `ac2=wap` 的模板变量）；两侧独立站点根 | C-8、C-12～C-16 | V-6、V-9 |
| FR-7、AC-8、AC-9、D-20 | §6.1 | — | V-7 |
| FR-8、AC-10 | `mob_template_dir=default_wap`、`mob_status=2`；手机 PATH-2 = `/template/default_wap/` | C-7、C-12～C-16 | V-4、V-5、V-9 |
| AC-5 | 对照环境 ENV-1～ENV-8 | C-6～C-10 | V-3、V-4 |
| PRE-2、PHPB-1～PHPB-9 | ENV-2/3/4/8、C-10 | C-8、C-10 | V-3 |
| 附表 D 的 D-01（模板目录写法引用 OP-15） | 模板读取根 PATH-1 = `template/default_pc/html/`、`template/default_wap/html/`（与 URL 变量 PATH-2 分开） | — | V-4 ① |

**完整设计的需求追溯（v3 新增；逐条覆盖 FR-1～FR-13、AC-1～AC-20、BR-1～BR-12、PRE-1～PRE-5）**

| 需求 | 方案要点 | 改动清单行 | 验证行 |
| --- | --- | --- | --- |
| FR-1 / AC-1 / BR-11 | DEC-1～3、PATH-1～4；缺目录 → TPL“模板文件不存在” | C-1～C-5、C-11、C-12～C-16、C-24、C-25 | V-1、V-2、V-5、V-9、V-10 |
| FR-2 / AC-2 / PRE-5 | §4.6 方案 C；TPL-1～11；RT-1～8 | C-17～C-23、C-33、C-34、C-65 | V-11、V-12、V-13 |
| FR-3 / AC-3 / BR-5 | §6.6；TAGMAP-1～19；CACHE-1 | C-22、C-28、C-29、C-34 | V-12、V-16 |
| FR-4 / AC-4 | §6.7；RT-7；`label_maccms` | C-27、C-30、C-36 | V-15、V-19 |
| FR-5 / AC-5 / AC-6 / AC-20 / BR-12 | §6.8 PG-1～7、ALIGN-1～5；F-D1～F-D3 由 `mac_tpl_fetch` 与 PG-6/PG-7 实现 | C-36～C-40、C-43、C-44、C-46、C-68、C-70～C-72、C-75 | V-10、V-19、V-30、V-32 |
| FR-6 / AC-7 / BR-6 | §6.2、§6.12；ENV-6/7 | C-45、C-50、C-51 | V-6 |
| FR-7 / AC-8 / AC-9 | §6.1、§6.11 | C-47～C-49 | V-7、V-22 |
| FR-8 / AC-10 / KPI-5 | §6.8 手机；PATH-1～4 手机列；`LegacyLang` | C-12～C-16、C-24、C-37 | V-4、V-5、V-17、V-19 |
| FR-9 / AC-11 | DBT-1～DBT-5（v6：SEC-8 按 PHP 无条件；失败、并发、恢复契约 DBT-5；§4.12 DEC-12。v7：DBT-2 按 PHP 整段语义，§4.13 DEC-13；跨进程锁与状态机、`recover`；DBT-3 表集合含空表）。与 PHP 的差异判定：DBT-2 只有 AC-11 拒绝与提示文字；DBT-3 只有可恢复性保护与“站点须关闭”前置（DBT-5 ⑧）。v8：删备份入锁并保护绑定备份（DBT-4）；进程中断按操作类型与阶段唯一转移（DBT-5 ⑩） | C-53～C-55、C-73、C-74 | V-23、V-31 |
| FR-10 / AC-12 / RISK-15 | INS-1～INS-4（常量 `48dbe0fb…`）；§4.10；RISK-T17 | C-56、C-57、C-67、C-69 | V-25 |
| FR-11 / AC-13 | NET-1～NET-6 | C-58～C-61 | V-26 |
| 契约 OUT-1-5（SEC-BE-1，v9） | DEC-14、NET-7 | C-76～C-80 | V-33 |
| FR-12 / AC-14 / AC-19 / BR-9 | §6.16（判定口径，测试人员执行） | `docs/test/APPK-2/parity-*.md`、`gap-register.md`（测试产物） | V-27 |
| FR-13 / AC-15 / AC-18 / BR-1 / BR-10 | §6.19；不新增结构；SchemaGuard 登记例外仅 AC-11 所需 | C-55、C-66 | V-21 |
| AC-16 | §6.19；新增写入先登记、按附表 D 判定 | C-29、C-51、C-54、C-59 | V-28 |
| AC-17 | 每个 S3 子任务引用的 OP-T 在派单前已关闭。v8：OP-T4=a、OP-T5=a、OP-T6=b 已关闭（确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`），S3-13b、S3-13c、S3-14a、S3-14c 单内引用该评论、只实现所选答案；S3-14h 撤销；当前没有未关闭的 OP-T（§7.4、§10） | C-43、C-44、C-68、C-75 | V-29 |
| BR-2 | 配置只经 `LegacyConfigService`/`VarExportWriter` 写；密码 md5 不加盐不变 | 不改 | V-24 |
| BR-3 | 以 PHP 7.3.33 输出为准；编译与值语义以黄金为准 | C-33 | V-11～V-13、V-19 |
| BR-4 / OP-9 | §6.17 SEC-1～6 不改；ALIGN 只改呈现；SEC-7、SEC-9 是发起人已确认的差异（OP-T5=a、OP-T4=a，v8），不计入六项；SEC-8 与 PHP 一致；前台 CSP 不属于六项，按 OP-T6=b 只保留三项（C-75） | — | V-24；SEC-7、SEC-9 见 V-18、V-30；SEC-8 见 V-23；OP-T6 见 V-32 ②⑥ |
| BR-6 | 模板、静态页、广告写文件经 `LegacySiteFiles` 原子写 | C-47、C-50 | V-6、V-7 |
| BR-7 | 对照执行器按附表 F | C-64 | V-19 |
| BR-8 | OP-15、OP-16 已关闭（Issue 记录） | — | — |
| PRE-1 | 结构快照脚本 | C-66 | V-21 |
| PRE-2 | ENV-1～10、SNAPD-1～5、CACHE-3 | C-6～C-10、C-62、C-63 | V-2、V-3、V-4、V-20 |
| PRE-3 / PRE-4 | 样本清单由测试人员按附表 B 编写（本设计不改分母）；F-D2 选 `public/msg`（桌面包内存在） | — | AC-20 行数核对 |
| PRE-5 | 扫描脚本 + 架构师逐处实现说明；时钟/随机/会话来源归属 EX-1/EX-2/DATA-2 | C-65 | PRE-5 完成判定 |
| V9-001 | CACHE-1～4、ENV-9 | C-26、C-31、C-52 | V-14 |

## 12. 源码合理自检

| 清单项 | 结论 | 依据（@ `a57e9cb`） |
| --- | --- | --- |
| 现状有源码出处 | 是 | §1.1 每行带文件与行号 |
| 被改配置键的读取方查全 | 是 | `git grep -n "template_dir\|html_dir\|ads_dir\|mob_template\|mob_html" a57e9cb -- php/application src/main`：PHP 5 个控制器/行为、Java 8 处（§3 配置行逐一列出），全部按配置值拼路径或原样显示、保存，没有写死目录名 |
| 路径量与 PHP 一致 | **基线不一致，按 C-12～C-16 修正**：PHP `{$maccms.path_tpl}` = `MAC_PATH_TEMPLATE`（`All.php:62`），Java `SiteVariant.pathTpl` 取的是 `MAC_PATH_TPL`（`SiteVariants.java:95`）；`Result` 的 `pathTemplate`/`pathTpl`/`pathAds` 与 PHP ②③④ 一致（黄金锁定），不改 | `Init.php:50-53`；`All.php:62-63`；`SiteTemplateSelection.java:99-101`；`SiteVariant.java:16-19` |
| 被改符号的调用方查全 | 是 | `git grep -n "pathTpl\|pathAds\|pathTemplate" a57e9cb -- src`：`SiteVariant` 构造只在 `SiteVariants.java:95` 与 `SiteVariant.DEFAULT`；`SiteVariant.pathTpl()` 读取只在 `SiteVariantsTest.java:75,82`；`Result.pathTpl()` 读取只在 `SiteVariants.java:95`、`SiteTemplateSelectionGoldenTest.java:58,64`；`src/main/jte*` 里只有 `ads.jte` 的字面 `{$maccms.path_ads}` |
| 沿用现有模式 | 是 | 站点根 = `MACCMS_LEGACY_CONFIG_DIR` 上一级（`InstallConfiguration.java:36-38`）；文件写入经 `LegacySiteFiles`；`.gitattributes` 按字节规则同 `src/test/resources/legacyconfig/cases/*.php` |
| OP-15 部分（v2）：无新依赖 | 是（仅限 v2 OP-15 范围；全文依赖见下方“完整设计：依赖清单”） | OP-15 部分不加 Maven 依赖；装配脚本用 Python 标准库与 git（与现有 `src/test/browser/*.py` 同语言） |
| 无数据库结构变更 | 是 | 不读写任何表 |
| 无安全回退 | 是 | 不放宽 `LegacySiteFiles` 的区域与路径校验；对照环境只监听 127.0.0.1；凭据只经环境变量，脚本不打印 |
| 不改 `php/` | 是 | 覆盖只作用于 `.compare/` 副本（C-7、C-8）；PHP 黄金脚本 `src/test/php/template_select_golden*.php` 与黄金 JSON 也不改 |
| 改动可回滚 | 是 | §9 |
| 与 PRD 无矛盾 | 有一处需评审确认：PHPB-9 写“两侧各写其对应值”，本设计取“两侧取值相同”（ENV-4），这是“各写其值”的特例，且是同时满足“13 个文件 SHA-256 相同”的唯一做法 | 附表 F.1 PHPB-9 |
| 完整设计：分层与依赖方向 | 是 | `phptemplate` 按 `api(无)→application→infrastructure` 分层，`runtime`/`engine` 不访问数据库；`site` 只经 `phptemplate.application`，符合 `02-agent-development-rules.md` 第 2 节“只能调用对方 application 层公开服务”；护栏同步（C-35） |
| 完整设计：复用而非重写 | 是 | 取数复用 `ContentReadService`/`VideoLegacyConditions`/`LegacyTagAttributes`/`LegacyRange`/`LegacyRowReadService`/`CommentService`；URL 复用 `LegacyUrlBuilder`/`ImageUrlNormalizer`；语言复用 `LegacyLang`；文件复用 `LegacySiteFiles`；出站复用 `OutboundHttpClient`；模板选择复用 `SiteTemplateSelection` |
| v9：新依赖有理由、范围受控（v10 起清单见下方“完整设计：依赖清单”） | 是 | `httpclient5` 只为 OUT-1-5 的地址固定（JDK 客户端无此入口，§4.14 C/D 有进程级副作用）；版本由 Spring Boot BOM 管理；只在 `integration.http` 使用，护栏类型规则不变（C-79）；调用方接口不变（核对 @ `6f78400`：`policy.check` 只有 `OutboundHttpClient.java:115`、`:130` 两处调用） |
| 完整设计：被改公共符号的调用方 | 是（v6 补全） | `SiteHtmlRenderer`：`git grep -n "SiteHtmlRenderer" a57e9cb -- src/main src/test` 的 main 21 个文件、test 7 个文件逐个列在 §1.3，处理与子任务在 §6.8 迁移表（v5 只核对了 `src/main/java` 的页面控制器，漏掉 `SearchThrottle`、`SiteCounterActions`、`PlaybackPages`、`SiteMemberFinance:525` 的静态常量、`SitePaymentController:104` 的静态常量，以及 7 个测试文件，REV-8）；`HTML_UTF8` 的四处定义收敛为 `SiteRequests.HTML_UTF8`；`PlayerFramePolicy` 调用方 `PlaybackPages`、`SiteMemberBoards`、`SiteMemberFinance`、`SitePlaybackController`、`SiteUserController`、`PlaybackPrimitivesTest` 都在 C-37/C-40/C-71 内；`SiteTemplateService` 调用方 = `SiteConfiguration`、`AdminTemplateActions`、`AdminConfiguration`、`AdminPages`、`TemplateWizard`（C-40～C-42、C-47～C-49 覆盖；`AdminPages` 的欢迎页提示随 C-40 改为读模板目录是否存在）；`DatabaseToolService.REFUSED` 只在 `AdminDatabaseActions`；v7 新增的 `LegacySiteFiles.tryExclusiveLock` 是新方法，不改现有方法签名，调用方只有 C-73；`DatabaseToolService.export` 的调用方只有 `AdminDatabaseActions`（改为在锁内执行，不改其入参与返回）；v8：`DatabaseToolService.delete` 的调用方只有 `AdminDatabaseActions.java:60`（`del` 分支，改为在锁内执行，不改其入参与返回结构）；`InstallSchema` 调用方 `InstallWizard`、`InstallWizardMySqlIT` |
| 完整设计：依赖清单（v10 更正，REV-NET-D01；v3～v9 此行写“无新依赖｜是”，自 v9 C-76 起不成立） | 新增 1 项（唯一例外）、删除 1 项，其余不变 | **新增**：`org.apache.httpcomponents.client5:httpclient5`（C-76，S3-21e），理由与方案对比见 §4.14 DEC-14，方向授权见元数据“已定方向”（评论 `01a196b5-a035-71df-bbe3-077cd09512a6`）；`pom.xml` 不写 `<version>`，版本由 `spring-boot-dependencies` 4.1.1 管理（5.6.4），C-79 断言该 `<dependency>` 不含 `<version>`；传递依赖只允许 `httpcore5`/`httpcore5-h2`（V-33 附 `dependency:tree`）；包边界：`org.apache.hc.` 类型只允许在 `integration.http` 内使用，`OUTBOUND_TYPES` 与合成用例不改，“只有 `OutboundHttpClient` 能发请求”的护栏不变；护栏：`OutboundNetworkGuardTest` 的 `FORBIDDEN_ARTIFACTS` 只去掉 `httpclient5`，`httpclient`、`httpasyncclient`、`okhttp` 等仍禁止（C-79）。**删除**：只供在线编辑的 `gg.jte:jte` 编译器依赖（C-41）。**其余**：解释器、正则、签名、FTP 都用 JDK，不加其他 Maven 依赖。**例外口径**：本设计的新增依赖只有 `httpclient5` 一项；实现中需要任何其他新依赖（含改为显式版本），须先出设计修订并经 G1 评审 |
| 完整设计：无结构变更 | 是 | §6.19；DDL 只出现在 DBT-1（表维护，OP-6 已确认）与 INS-2 ④（原版 `install.sql`，OP-7 唯一例外）；`SchemaGuardTest` 登记例外只增 AC-11 所需（C-55） |
| 完整设计：安全 | 是 | 注入：DBT-2 先按 PHP 跳过判定、再拒绝多语句与结构语句，之后整段一次发送（v7），表名来自白名单；CSP：按发起人确认的 OP-T6=b 只保留三项不拦脚本的策略（C-75）；路径穿越：`/template/**` 与模板管理都经 `LegacySiteFiles` 规范化；任意代码执行：解释器 RT-1 子集不含 `eval`/`include`/反射，函数白名单分派；拒绝服务：RT-8 上限；凭据：只经环境变量与部署配置文件，日志不写；出站：NET-6 登记 + 护栏测试；鉴权：后台沿用 `admin_auth`，安全修复不动（§6.17） |
| 完整设计：可测试 | 是 | 编译与值语义有 PHP 黄金（C-33）；标签有 MySQL 集成测试；页面有 PHP 对照（附表 F）；解释器纯 JDK，可单测 |
| 完整设计：可回滚 | 是 | §9 v3 表，每个子任务一个可 `revert` 的提交；数据层：DBT-3 有还原前自动备份并经 `recover` 退回，DBT-2 要求先导出（DBT-5）；回退 S3-19 前先处理完待处理记录（§9） |
| 完整设计：失败与并发边界（v6） | 是 | 数据库工具按 MyISAM 无事务的事实写了预检、逐条状态、失败分类（同 12 文档第 4.3 节）、互斥、站点关闭前置、重试规则、审计日志与提示条、人工恢复步骤（DBT-5），并有中途失败、恢复、幂等、并发、连接中断的验证（V-31）。v7：v6 的“查记录后原子替换”不是互斥（REV-10），改为跨进程独占 OS 文件锁，锁内读写记录，崩溃由“拿到锁时记录仍为 RUNNING”识别，V-31 ②③④ 验证同时起跑与被杀；v6 的恢复步骤与锁状态冲突（REV-11），改为状态机与专用 `recover`，V-31 ⑤～⑧ 按状态机验证。v8：删备份纳入同一把锁、在锁内保护绑定备份，预检移到取锁之后（REV-14）；进程中断按 `op`/`phase` 唯一转移，并统一页面按钮与日志级别（REV-15），V-31 ⑪～⑲ 逐分支验证 |
| 完整设计：PHP 行为描述有源码出处（v6） | 是（v6 修正） | v5 写 OP-T5 b“SEC-8 这类 SELECT 照常发给数据库”不成立：`Database.php:251-262` 不执行含 `outfile`/`dumpfile`/`load_file` 的语句；v6 改为无条件不执行，SEC-8 移出 OP-T5，S3-19 不再受门禁 |
| 完整设计：常量与哈希按 blob 计算 | 是（v4 修正） | `INSTALL_SQL_SHA256` 按 `git show a57e9cb:php/application/install/sql/install.sql \| sha256sum` = `48dbe0fb…`；v3 的 `152e5424…` 是 `autocrlf` 工作区文件（CRLF）的哈希，已更正并加 C-67、RISK-T17 |
| 完整设计：PHP 行为描述有源码出处 | 是（v4 修正） | v3 写“PHP 没有观看密码频率限制”不成立：`Ajax.php:416-418` 有按会话 5 秒限制；v4 把它列为 ALIGN-4 ③（按 PHP 恢复），OP-T4 只问是否在其之外保留 `PasswordAttempts` |
| 与 PRD 无矛盾（完整设计） | 是（v8）：OP-T4、OP-T5、OP-T6 已由发起人确认（评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`：a、a、b），设计只保留所选答案，没有未关闭的待决项；DBT-2 v7 改回 PHP 整段语义，不再有未声明的语义差异（REV-12）；ALIGN-1～7 是按“PHP 为准”把 Java 现有差异改回 PHP，不改范围；DBT-5 在 OP-6“只还原数据”之上增加可恢复性保护，不改变成功路径的数据结果，其中“还原前站点须关闭”是新增前置条件（PHP 没有），FR-9 不做页面对照，由 T-FR12 按 DBT-5 判定；前台 CSP 按 OP-T6=b（C-75） | §6.8、§6.13、§10 |

## 13. 评审回应

> 读法（v8，SUG-1）：下面各轮记录保留当时的原文。其中关于 OP-T4、OP-T5、OP-T6 的“待发起人决定 / 未关闭 / a、b 两列”是 v4～v7 当时的状态，已被发起人确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040`（OP-T4=a、OP-T5=a、OP-T6=b）取代；当前状态以元数据“已关闭决策”、§7.4、§10 与本节最后一轮为准。

G1 第 1 轮（@架构评审-海外，对象 `ab57748b6f1fee314eeb97de099fac6f5d7b5ef8`；调度接受 REV-1 并给出 5 条完成判据）：

| REV- | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-1 ① 拆开 `MAC_PATH_TEMPLATE`、`MAC_PATH_TPL`/模板读取根、`MAC_PATH_ADS` | 已修改 | 按 `Init.php:50-53` 列出四个量（另含文件系统的 `MAC_ROOT_TEMPLATE`），按 `All.php:62-63` 写明模板变量各取哪一个 | §1.1 PHP 表第 2～5 行；§6 PATH-1～PATH-4 |
| REV-1 ② 桌面、手机取值 | 已修改 | 桌面 `path_tpl=/template/default_pc/`、模板读取根 `template/default_pc/html/`（URL 形式 `/template/default_pc/html/`）、`path_ads=/template/default_pc/ads/`；手机对应 `default_wap`。用官方 `include.html` 的 `{$maccms.path_tpl}css/style.css` 核对：只有 PATH-2 能指到包内 `css/` | §6 DEC-3 与 PATH 表；§6.1；§6.2 |
| REV-1 ③ 同步 DEC-3、§1.1、§6.1/§6.2、V-4/V-5、§11、§12 | 已修改 | V-4 分成读取根、`path_tpl`、`path_ads` 三组断言；V-5 对 Java 原样执行，并加缺失消息按 PATH-3 删前缀后逐字节相同；§11 四行补 PATH 与 C-12～C-16、V-9；§12 新增“路径量与 PHP 一致：基线不一致”与“被改符号的调用方查全”两行 | §6、§8、§11、§12 |
| REV-1 ④ Java `pathTpl` 的后续代码修正与验证落点；删除不成立结论 | 已修改 | 选 §4.5 方案 C：`Result` 保持与 PHP 常量一一对应（黄金不变），`SiteVariants.resolve` 改取 `pathTemplate`，改正两处注释、两行测试期望，新增两个单测（V-9，G2）；须在 FR-1 读取前合入。已删除“Java 已按同一路径实现”“零代码改动”“本版无 Java 改动”“无代码改动（取值驱动）”等表述 | §1.1 Java 表；§3；§4.1；§4.5；§5 C-12～C-16；§6.1；§7.1 第 4 步；§8 V-9；§9；§10 RISK-T8 |
| REV-1 ⑤ OP-T1～T3 不扩大 | 已遵守 | OP-T1～T3 保留原状，仅把 OP-T1 里“URL 已由 DEC-3 固定”改为“由 PATH-2、PATH-4 固定” | §10 |

v3（S1 完整设计，不是评审返修）：没有新的 REV-。v2 复审的不阻断建议“正文仍有‘完整设计 v2 起’的旧版本预告”已处理：§2 与 §10 OP-T 表改为指向 v3 内容。

S1 完整设计独立复核（智能调度，对象 v3 `0dd59f5f72b67e286a93a60c25f19c03f9c91990`，评论 `01a19485-64c8-7afa-93c6-1c1061354ac5`）：

| REV- | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-3-001 安装 SQL 哈希错误 | 已修改 | 复核结论正确。原因：v3 对 Windows `core.autocrlf=true` 工作区里的文件算了哈希（CRLF，45950 字节，`152e5424…`），基线 blob 是 LF（45049 字节，`48dbe0fb…`）。常量改为 `48dbe0fbd138f670919208d8bcc0ffe28d09b507c527d2a6a1a8f7b77ffa3aa4`；新增 C-67（`.gitattributes` 固定该文件 LF）与 RISK-T17；C-57、V-25 增加“不含 `\r`”断言与“整文件 CRLF → 哈希不一致”用例；空库执行、已有表拒绝两种用例不变 | §6.14 INS-1、INS-2 ③；§5.2 C-56、C-57、C-67；§7.4 S3-20；§8.2 V-25；§10 RISK-T17；§11 FR-10；§12 |
| REV-3-002 OP-T4/OP-T5 状态与门禁矛盾 | 已修改 | 两项改为“待发起人确认、阻塞对应子任务”；实现与验证都写成 a/b 两列：OP-T4 → ALIGN-4 ⑤、C-68、V-30、S3-14c；OP-T5 → §6.10、DBT-2、C-43、C-53、V-18、V-23、S3-13、S3-19。§7.4 新增条件门禁：OP 未关闭不派单、不合入，单内写明所选答案；选 b 改回 PHP。SEC-7～9 从“与六项同列的修复”改为条件差异，V-24 只对 SEC-1～6 无条件适用；FR-12 判定口径改为“OP 关闭且选 a 才判排除，否则按 PHP 比较” | §4.8；§5.2 C-37、C-43、C-53、C-68；§6.8；§6.10；§6.13；§6.16；§6.17；§7.4；§8.2 V-18、V-23、V-29、V-30；§9；§10 OP-T4/T5；§11 FR-5、FR-9、AC-17、BR-4；§12 |
| （自查）OP-T4 前提写错 | 已修改 | 修 REV-3-002 时核对 `Ajax.php:400-456`：PHP **有**按会话 5 秒的观看密码限制（`:416-418`，返回 1003），v3“PHP 没有频率限制”不成立。v4 把这条限制列入 ALIGN-4 ③，两种答案下都按 PHP 恢复；OP-T4 改问“是否在其之外再保留 `PasswordAttempts`”，a 时超限也返回 PHP 已有的 1003，不再用 429 | §1.3；§6.8 ALIGN-4；§10 OP-T4 |

S1 完整设计独立复核（智能调度，对象 v4 `9f6782d20fddb21f2d69a9492b01ae8dfbf090af`，评论 `01a19490-1c01-7407-a950-8c21a1af29d9`）：

| REV- | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-4-001 C-44 `readTemplateAsset` 契约没有跟随 OP-T5 | 已修改 | 复核结论正确：v4 的 C-44 写死了扩展名白名单，选 b 时会继续拒绝 `.html`、`info.ini`。C-44 改写为两层：两种答案共同的约束①～⑤（路径校验与规范化、真实路径在 `template/` 内、最终文件不跟随符号链接、拒绝 `.php`、只读），加上按 OP-T5 只实现所选那一列的放行范围（a = 四类子目录加扩展名白名单；b = 任意非 `.php` 文件）。放行规则只在 C-44 一处，C-43 只做 URL 映射与响应，不另设白名单。§6.3 新增 `readTemplateAsset` 的接口与错误语义，并写明模板引擎读模板用现有 `read`、不用此方法 | §4.8；§5.2 C-42、C-43、C-44；§6.3；§6.10 |
| （同步）S3-10 依赖与 C-44 拆分 | 已修改 | v4 把 C-44 放在不受门禁的 S3-10，而 C-44 的放行范围取决于 OP-T5；同一行的 `readInstallSql` 又属于安装向导（S3-20 没列 C-44）。v5 把 `readInstallSql` 拆成 C-69 归 S3-20；C-44 移出 S3-10；S3-13 拆成 S3-13a（内置视图 C-39，不受门禁）、S3-13b（C-44，后端，受 OP-T5 门禁）、S3-13c（C-43，前端，受 OP-T5 门禁）；S3-14 只依赖 S3-13a；C-42 不再注册 C-43（C-43 自带 `@ConditionalOnSiteEnabled`），所以 S3-15 也不依赖 OP-T5。这同时修正了 v4 §7.4 的矛盾：v4 写“S3-14c 之外的 PG 组不受影响”，但 S3-14 依赖 S3-13 | §5.2 C-69；§6.14 INS-1；§7.4；§9；§10 OP-T5；§11 FR-5、FR-10、AC-17；§12 |
| （同步）V-18 与 OP-T5 相关描述 | 已修改 | V-18 增加单测 `LegacySiteFilesTemplateAssetTest`：两种答案都测符号链接出 `template/`（目录链接和文件链接）、最终文件是符号链接、`.php`/`.PHP`、目录本身、不存在的文件不建目录；a/b 各测 `html/…/index.html`、`info.ini`、`css/x.txt`。评审另核 C-43 不含子目录或扩展名判断。V-29、§10 OP-T5、§11 AC-17、§12 的子任务编号改为 S3-13b、S3-13c；§9 回滚行加上 C-44 | §8.2 V-18、V-29；§9；§10；§11；§12 |

G1 架构评审第 1 轮（@架构评审-海外，对象 v5 `f44b73920b40756cf84766dbac9dee1dfb8103d1`，评论 `01a194a2-ff37-7e6c-8079-028acd934e57`；调度接受驳回，评论 `01a194a4-798d-7a9c-8942-30fff4fb58c4`）：

| REV- | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-5 OP-T4 未关闭 | **待发起人决定，本版无法关闭** | 截至 v6 提交，Issue 中没有发起人对 OP-T4 的确认评论。设计不能代发起人选择；a/b 两列与 S3-14c 门禁保持 v5 原样，§10 标注“截至 v6 未关闭”。发起人确认后出 v7：§10 记关闭评论，从 ALIGN-4 ⑤、C-68、V-30、§7.4 中删除未选列 | §10 OP-T4；§11 AC-17 |
| REV-6 OP-T5 未关闭 | **部分处理；SEC-7 待发起人决定** | 修订时核对 PHP 源码发现 v5 的 SEC-8 前提错误：`Database.php:251-262` 对含 `outfile`/`dumpfile`/`load_file` 的语句同样不执行，SEC-8 不是安全收紧。v6 把它改为与 PHP 一致的无条件行为（DBT-2 ⑤、§6.17、V-23），从 OP-T5 移出；C-53 不再有 a/b 列，S3-19 不再受 OP-T5 门禁。OP-T5 只剩 SEC-7（C-43/C-44、V-18、S3-13b/c），仍待发起人确认；确认后出 v7，只保留所选列 | §6.13 DBT-2；§6.16；§6.17；§7.4；§8.2 V-23、V-29；§9；§10 OP-T5；§11；§12 |
| REV-7 DBT-2/DBT-3 失败、并发、恢复 | 已修改 | 新增 §4.12 方案对比（DEC-12）与 §6.13 DBT-5 契约：①事务能力（25 张 MyISAM + `mac_vod_search` InnoDB，均不开事务，无整批原子性）；②整批预检（分卷齐全、可解压、分类、白名单、站点已关闭），不通过则 0 条语句；③逐条执行、第一处失败即停、不补偿，按 12 文档第 4.3 节分 `FAILED`/`UNKNOWN`，DBT-3 逐表记录；还原前自动导出涉及的表；④运行记录文件兼作互斥（与 `backup.lock` 互认），还原要求站点关闭，其他管理员与定时任务按运维约定；⑤重试规则（DBT-2 回填剩余语句、DBT-3 整体幂等重跑）；⑥审计：运行记录 + 提示条 + “我已处理” + 结构化日志（不含 SQL 原文）；⑦管理员恢复步骤。新增 C-73、C-74，C-53/C-54 扩充；V-31 覆盖中途失败、用自动备份恢复、重复还原幂等、站点未关闭、并发互斥、连接中断；S3-19 拆为 a/b；§9 不可逆项给出可逆替代；RISK-T13 更新、新增 RISK-T19 | §1.3；§4.12；§5.2 C-53、C-54、C-73、C-74；§6.3；§6.13；§7.4；§8.2 V-31；§9；§10；§11 FR-9；§12 |
| REV-8 `SiteHtmlRenderer` 调用方遗漏 | 已修改 | 按基线 `git grep` 补全 main 21 个、test 7 个文件（§1.3），§6.8 新增逐文件迁移表（处理方式 + 子任务）。共享边界：新增 `SitePageResponses`（唯一的 HTML 响应构造处）与 `JumpPages`（PHP `error/success` 的唯一入口），`Content-Type` 常量收敛为 `SiteRequests.HTML_UTF8`；CSP 按 §4.11 方案 C（DEC-11）不再发送（同 PHP，严格 CSP 会挡住模板与内置视图的脚本），删除 `CONTENT_SECURITY_POLICY`、`memberPolicy`、`PlayerFramePolicy`。`SearchThrottle` 改注入 `JumpPages`，过快时按 PHP 返回 200 跳转页（ALIGN-6）；`SiteCounterActions` 删渲染器依赖，非 ajax 按 PHP（ALIGN-7）；`PlaybackPages` 删 CSP 引用，授权判定不改；`SiteTestRoutes` 改用新边界并保留“半页失败 → 完整 500 页”的断言；5 个 CSP 断言与 `SiteConfigurationTest` 改写。新增 C-70～C-72，改 C-37、C-38、C-40、C-42；V-32；§7.4 各组子任务、§9 回滚顺序（S3-14a 的共享边界最后回退，S3-15 最先）；RISK-T18 | §1.3；§3.2；§4.11；§5.2；§6.3；§6.8；§7.4；§8.2 V-32；§9；§10；§11；§12 |
| SUG-1 分类器绕过与日志 | 已采纳 | V-23 ②③⑤ 增加大小写、注释/空白变体、`OUTFILE`/`DUMPFILE`/`LOAD_FILE` 词法变体，拒绝原因不回显 SQL | §8.2 V-23 |
| SUG-2 结构化日志 | 已采纳 | §8.2 增“运维日志字段”：模板资源拒绝、编译 500、安装哈希不一致、出站拒绝、数据库工具 | §8.2；§6.13 DBT-5 ⑥ |

S1 完整设计独立复核（智能调度，对象 v6 `55afe8645440de83120b75bb1360ab7dd73fbb4a`，评论 `01a194bb-d7b2-76e2-8962-d247596fa529`；巡检补派评论 `01a194c4-379f-71f6-89d0-f328b05e4ca3`）：

| REV- | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-5 / REV-6 OP-T4、OP-T5 未关闭 | **待发起人决定，本版仍无法关闭** | 截至 v7 提交，Issue 中仍没有发起人对 OP-T4、OP-T5 的确认。两项继续保留 a/b 两列与门禁，§10、§11 AC-17 改为“截至 v7 未关闭”。发起人确认后出下一版：§10 引用关闭评论，删除未选列 | §10；§11 AC-17 |
| REV-9 删除前台 CSP 需要人类安全确认 | 已修改（新增待决 OP-T6） | 撤回 v6 的 DEC-11“选 C、前台不发 CSP”。新增 C-75 `SiteSecurityHeaders` 作为前台 CSP 唯一来源；OP-T6 关闭前取基线 `SiteHtmlRenderer.CONTENT_SECURITY_POLICY` 原文，所有前台 HTML 页共用，安全水平不低于基线（会员 nonce、播放器内页与网关 `form-action` 变体都比它宽，不再生成）。OP-T6：a 不发（同 PHP）；b（建议）只发 `object-src`/`base-uri`/`frame-ancestors` 三项。新增 S3-14h（只改 C-75，受 OP-T6 门禁），S3-14h 未合入前 deploy 分支不得发布（RISK-T21）；V-32 不再断言“无 CSP”，改为“等于 C-75 的值”，S3-14h 再按所选列断言并做浏览器核对。顺带更正 V-32 ① 的检索范围（v6 的检索会命中 `admin`/`install` 的同名常量，永远不会为空） | §3.2；§4.11 方案 D；§5.2 C-37/C-40/C-70/C-72/C-75；§6.8 ALIGN-5、迁移表与“前台 CSP”段；§6.17；§7.4 S3-14、S3-14h、条件门禁；§8.2 V-32；§9；§10 RISK-T18/T21、OP-T6；§11 |
| REV-10 运行记录不是跨进程锁 | 已修改 | 复核结论正确：v6 的“查记录与 `backup.lock` → 原子替换写 `RUNNING`”两步之间没有互斥；Java 基线 `export` 的 `stat` 后 `replace` 与 PHP 的 `is_file` 后 `file_put_contents` 同样有竞态。改为 `LegacySiteFiles.tryExclusiveLock` 取 `dbtool.lock` 的独占 OS 文件锁（同 JVM 另加进程内锁），所有数据库工具动作与导出都在锁内读写运行记录；所有权 = 持锁进程，进程退出由操作系统释放，持锁者读到 `RUNNING`/`RECOVERING` 即可判定中断，不需租约或心跳；锁文件不删除；本机文件系统前提与 PHP 并存约束列 RISK-T20。V-31 ② 同 JVM 同时起跑 200 轮、③ 两个子进程同时起跑 50 轮、④ 持锁子进程挂起与被杀 | §4.12；§6.13 DBT-4、DBT-5 ③④⑥；§6.3；C-73、C-74；V-31；RISK-T20 |
| REV-11 恢复步骤与锁状态矛盾 | 已修改 | 新增运行记录状态机与专用 `recover(run, 目标)`：只对 DBT-3 待处理记录开放，只能用记录绑定的自动备份（校验 SHA-256）或原备份，不能另选文件；恢复全程持锁、站点须关闭、不再做自动备份；结果 `RECOVERED`/`RECOVERY_FAILED`/`RECOVERY_UNKNOWN`/`RECOVERY_INTERRUPTED`，失败后仍待处理、可再次 `recover`；`acknowledge` 是离开待处理的唯一出口，`RECOVERED` 直接确认，其余须勾选“接受当前数据”；待处理期间普通操作与删除绑定备份都被拒；工具已无法恢复时由部署方用 `mysql` 客户端导入自动备份再确认。V-31 ⑤～⑧ 按状态机验证 | §6.13 DBT-4、DBT-5 ⑤⑥⑦⑨、状态机；C-54、C-73；V-31；§9 |
| REV-12 DBT-2 拆句改变 PHP 语义 | 已修改（选保持 PHP 整段语义，不新增待决项） | 核对基线：`sql()` 整段一次 `Db::execute`，连接 `ATTR_EMULATE_PREPARES=false`、`break_reconnect=false`，数据库只接受一条语句，多语句整体被拒。v7 DBT-2：PHP 跳过判定逐字照搬（含“执行成功”提示）→ 多语句在发送前拒绝（结果同 PHP：一条都不执行）→ AC-11 结构保护（OP-6 已确认）→ 整段一次发送、不重试。`SELECT 1; INSERT …` 整段跳过，与 PHP 相同；v6 的“`SELECT` 执行后不展示结果集”也更正为不执行。逐条状态只保留给 DBT-3。V-23 改为样本表（跳过、多语句、AC-11、允许的 DML），并在对照环境对 PHP 取样确认不执行 | §4.13 DEC-13；§6.13 DBT-2、DBT-5 ①②③⑤⑧；§6.17 SEC-8；C-53；V-23；§11 FR-9 |
| （自查）DBT-3 空表 | 已修改 | PHP 导出对 0 行的表只写删表与建表，PHP 还原后该表为空；v6 只清空备份里有 `INSERT` 的表，这类表会留下现有数据，与“结果与 PHP 相同”不符。v7 还原表集合改为备份中 `DROP`/`CREATE`/`INSERT` 出现过的全部表 | §6.13 DBT-3；V-23 ⑤ |

G1 第 2 轮（@架构评审-海外，对象 v7 `3da8b4b448ba4ef54051f2d54379461b667b0f08`，评论 `01a194fb-d97e-7a43-9e61-49aa483eaae6`，结论驳回；调度返工评论 `01a194fc-69ae-70d8-85a5-f952c94b95b5`）：

| REV- | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-13 已确认的 OP-T4/5/6 仍按待决与 a/b 双分支描述 | 已修改 | 以发起人确认评论 `01a194e9-23e8-7682-8ae5-b7c5e19b0040` 为唯一决策源（元数据新增“已关闭决策”行）。OP-T4=a：C-68 只剩“保留并移到 `ajax/pwd`”，ALIGN-4 ⑤ 去掉条件，V-30 只测保留限流。OP-T5=a：C-44 只剩 SEC-7 白名单（并入约束 ⑥），§4.8、§6.10、C-43 去掉 b 列与补充的 `Content-Type`，V-18 只测白名单。OP-T6=b：新增 §4.11 方案 E 并选定，C-75 改为常量三项策略、随 S3-14a 落地，撤销 v7 的过渡值、S3-14h 与“S3-14h 合入前不得发布”（RISK-T21 关闭），V-32 ② 断言三项原文、⑥ 改为各 PG 组收尾的浏览器核对。§7.4 条件门禁改为“已关闭决策”，S3-13b、S3-13c、S3-14c 不再受门禁；§10 三项记“已关闭”；§11 AC-17、BR-4 与 V-29 改为“单内引用确认评论、只实现所选答案”；§9 改为“改选须先由发起人另行确认、再出修订”；§6.16、§6.17、§12 同步 | 元数据；§3；§4.8；§4.11；§5 C-37、C-43、C-44、C-68、C-70、C-72、C-75；§6.3；§6.8 ALIGN-4、ALIGN-5 与“前台 CSP”段；§6.10；§6.16；§6.17；§7.4；§8.2 V-18、V-29、V-30、V-32；§9；§10；§11；§12 |
| REV-14 删备份未纳入 `dbtool.lock` | 已修改 | 核对基线：`AdminDatabaseActions.java:60` 的 `del` → `DatabaseToolService.delete`（`DatabaseToolService.java:255-273`）列目录后逐个删除，不查锁与记录。v8 DBT-4：入参校验 → 取同一把 `dbtool.lock`（拿不到 → 拒绝、0 个删除）→ 锁内先做中断判定、再读运行记录 → `IMPORT` 待处理（含 `RECOVERED`）且时间戳等于 `source_backup`/`auto_backup` → 拒绝、0 个删除 → 否则在锁内按卷号升序删除（被杀留下的残缺备份必然缺第 1 卷，还原预检判“文件损坏”）。另把所有动作的顺序统一为“取锁 → 中断判定 → 状态检查 → 预检 → 写 `RUNNING`”，DBT-3 与 `recover` 读备份的预检移到锁内，消除预检与执行之间备份被删的窗口。V-31 新增 ⑰（锁忙 0 删除、升序删除）与 ⑱（绑定保护、非绑定可删、`recover` 与删除两进程同时 50 轮）；C-53、C-54、C-73、§9 写明回滚与清理规则 | §6.13 DBT-4、DBT-5 ②④ 与状态机；§5 C-53、C-54、C-73；§7.4 S3-19a/b；§8.2 V-31；§9；§10 RISK-T20；§11；§12 |
| REV-15 中断状态分支不唯一、V-31 只测通用场景 | 已修改 | C-73 的“统一改为 `INTERRUPTED`”与状态机的例外合并为 DBT-5 ⑩ 与一张“进程中断后的唯一转移”表：DBT-1 → `DONE`/`INTERRUPTED`（不待处理，`WARN`）；DBT-2 → `INTERRUPTED`（待处理，只有“我已处理”且须勾选，`ERROR`）；DBT-3 新增 `phase`，`AUTO_BACKUP` 阶段 → `DONE`/`INTERRUPTED_BEFORE_WRITE` 并清理半成品（不待处理，`WARN`），`RESTORE` 阶段 → `INTERRUPTED`（待处理，`recover` 两目标 + “我已处理”，`ERROR`）；导出 → `DONE`/`INTERRUPTED` 并清理半成品（`WARN`）；`RECOVERING` → `RECOVERY_INTERRUPTED`（`ERROR`）。清理只删 `export_files` 点名的分卷与值相符的 `backup.lock`（RISK-T22）。页面加载经 `probe()` 不等待取锁完成判定，拿不到锁显示“执行中”、无按钮。状态机的中断行改为引用该表，`RECOVERING` 行去掉“进程退出”。V-31 ④ 限定为 DBT-2 例，新增 ⑪～⑯ 逐分支跨进程被杀断言（记录、按钮、日志、数据）与 ⑲ 判定幂等 | §6.13 DBT-5 ③④⑥⑨⑩、状态机与中断表；§5 C-53、C-54、C-73、C-74；§7.4；§8.2 V-31；§10 RISK-T22；§12 |
| SUG-1 引用确认评论 | 采纳 | 元数据、§7.4、§10、§11 与本节开头都直接引用确认评论；本节开头说明历史记录中的“待确认”已被取代 | 元数据；§13 开头 |
| REV-5 / REV-6（复审时未解决） | 已解决 | 随 REV-13：OP-T4=a、OP-T5=a 已关闭并收敛为唯一实现 | 同 REV-13 |
| REV-10 / REV-11（复审时部分解决） | 已解决 | 随 REV-14（删备份入锁）与 REV-15（中断按操作类型唯一转移） | 同 REV-14、REV-15 |

实现回退（@后端开发-海外 第 1 批，评论 `01a19682-a499-7066-8e43-a23260dd1d44`；v9 处理，须经 G1 增量评审）：

| 问题 | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| OUT-1-5 在 v8 内无法实现（JDK `HttpClient` 不能按请求指定解析结果，存在重绑定窗口） | 已修改（DEC-14，选后端所列 (a)） | 核对 @ `6f78400`：`OutboundHttpClient.java:79-83` 的 JDK 客户端在 `:186` 按主机名再次解析；`OutboundNetworkGuardTest.java:69-73` 禁止 `httpclient5`。选 Apache HttpClient 5（BOM 管理版本）作为 `integration.http` 的内部引擎，每跳注入只返回已校验地址的解析器；(b) 进程级且可执行 jar 加载不到、(c) 要进程级放开受限头并自写 TLS 主机名校验，均不选；不改契约 | §4.14；§6.15 NET-7；C-76～C-80；§7.4 S3-21e；V-33；§9；RISK-T23；§11；§12 |
| 同一评论“未完成”第 2 项（OUT-1-7、OUT-1-8/9、TRN-1 参数个数、SRC-2） | 不涉及设计 | 均在 v8 与契约 v1.3.0 内已有明确规则，属后端后续批次，不需要架构决定 | — |

G1 增量独立复核退回（@智能调度-海外，评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`，对象 v9 @ `f2307632f362d4fa7013c1e7a327749fed586c81`；v10 处理）：

| 问题 | 处理 | 说明 | 修改位置 |
| --- | --- | --- | --- |
| REV-NET-D01 §12 同时写“v9：新依赖有理由、范围受控”与“完整设计：无新依赖｜是”，与 C-76 新增 `httpclient5` 矛盾；未登记方向授权来源 | 已修改 | “完整设计：无新依赖”改为“完整设计：依赖清单”：新增 `httpclient5` 一项（唯一例外）、删除 `gg.jte:jte`（C-41），并写明 BOM 管理版本、传递依赖、包边界、护栏与例外口径，与 C-76、C-79、§4.14、V-33 一致；v2 的“无新依赖”行限定为 OP-15 范围；元数据新增“已定方向（v9 DEC-14）”并在 §4.14 引用：授权评论 `01a196b5-a035-71df-bbe3-077cd09512a6`（Mika 代人类转达），代转依据为发起人评论 `01a1958e-5c60-7d72-9328-d7a5146e1d99`，调度核验 `01a196b6-9850-74d8-b3e5-22800625b6e6`，并写明该授权不是 G1 或实现放行；S3-21e 门禁改为 v8→v10 增量评审通过。选型、契约、C-76～C-80、NET-7、V-33 与实现内容不变 | 元数据（版本、创建时间、已定方向、本版范围）；§4.14；§7.4 S3-21e；§12 依赖相关三行；§13；§14 |

## 14. 修订记录

| 日期 | 版本 | 作者 | 变更摘要 | 对应 REV- |
| --- | --- | --- | --- | --- |
| 2026-10-31 | v1 | @架构师-海外 | 首版：回答 OP-15（DEC-1～DEC-6、ENV-1～ENV-8），列改动清单 C-1～C-11、验证 V-1～V-8；FR-1～FR-13 完整设计待 G0 后续写 | — |
| 2026-10-31 | v2 | @架构师-海外 | 拆开路径量 PATH-1～PATH-4，`{$maccms.path_tpl}` 改为 `MAC_PATH_TEMPLATE`（桌面 `/template/default_pc/`）；新增 §4.5 与 C-12～C-16（Java 映射修正，G1 后单独提交）、V-9、RISK-T8；改 V-4/V-5 断言及 §1.1、§3、§6、§9、§11、§12 | REV-1 |
| 2026-11-01 | v3 | @架构师-海外 | 完整技术设计：§1.3/1.4 现状与目标；§3.2 影响面；§4.6～4.10 方案对比（兼容层、模块、静态资源、缓存、安装账号）；C-17～C-66；§6.3～6.20（DEC-7～10、TPL-、RT-、PG-、ALIGN-、DB-、INS-、NET-、SEC-、CACHE-、SNAPD-）；ENV-9/10；§7.4 子任务拆分；V-10～V-29；RISK-T9～T16；关闭 OP-T1～T3，新增 OP-T4、OP-T5 | — |
| 2026-11-01 | v4 | @架构师-海外 | INS-1 常量改为 blob 哈希 `48dbe0fb…`，新增 C-67、RISK-T17；OP-T4/OP-T5 改为阻塞对应子任务的条件项，§6.8/§6.10/§6.13 写 a/b 两列，新增 SEC-9、C-68、V-30 与 §7.4 条件门禁；更正“PHP 没有观看密码频率限制”，ALIGN-4 按 PHP 恢复按会话 5 秒限制 | REV-3-001、REV-3-002 |
| 2026-11-01 | v5 | @架构师-海外 | C-44 `readTemplateAsset` 改为“共同约束 + 按 OP-T5 所选列放行”，放行规则只在 C-44；`readInstallSql` 拆为 C-69 归 S3-20；S3-13 拆成 a/b/c，只有 b、c 受 OP-T5 门禁，S3-14/S3-15 不再间接依赖 OP-T5；C-42 不再注册 C-43；§6.3 新增 `readTemplateAsset` 接口；V-18 增加单测部分；V-29、§9、§10、§11、§12 同步 | REV-4-001 |
| 2026-11-01 | v6 | @架构师-海外 | 数据库工具失败/并发/恢复契约 DBT-5（§4.12 DEC-12、§6.13、C-73、C-74、V-31，S3-19 拆 a/b）；`SiteHtmlRenderer` 全部调用方迁移表与新共享边界 `SitePageResponses`/`JumpPages`、前台不发 CSP（§4.11 DEC-11、§6.8 ALIGN-6/7、C-70～C-72、V-32、RISK-T18）；按 PHP 源码更正 SEC-8 为无条件行为、移出 OP-T5，S3-19 不再受门禁；OP-T4、OP-T5（SEC-7）仍待发起人确认；SUG-1、SUG-2 采纳 | REV-5（未关闭）、REV-6（部分）、REV-7、REV-8 |
| 2026-11-01 | v7 | @架构师-海外 | 前台 CSP 收敛到 C-75，删除/放宽列为待决 OP-T6，关闭前保持基线严格策略，新增 S3-14h 与发布门禁、RISK-T21，V-32 改断言并更正检索范围（§4.11 方案 D）；数据库工具互斥改为跨进程 OS 文件锁（`LegacySiteFiles.tryExclusiveLock`）、运行记录状态机与专用 `recover`、删备份保护、RISK-T20，V-31 重写（同时起跑、被杀、恢复状态机）；DBT-2 改回 PHP 整段语义（§4.13 DEC-13），V-23 改为样本表；DBT-3 还原表集合补空表；OP-T4、OP-T5 仍待发起人确认 | REV-5、REV-6（未关闭）、REV-9、REV-10、REV-11、REV-12 |
| 2026-11-01 | v8 | @架构师-海外 | 按发起人确认（OP-T4=a、OP-T5=a、OP-T6=b）收敛为唯一实现：删除未选列，C-44 只剩 SEC-7 白名单，C-68 保留 `PasswordAttempts`，C-75 固定为三项策略并随 S3-14a 落地（§4.11 方案 E），撤销 S3-14h、过渡值与发布门禁（RISK-T21 关闭），条件门禁改为“已关闭决策”；删备份纳入 `dbtool.lock`、锁内保护绑定备份、按卷号升序删除，预检移到取锁之后；新增 DBT-5 ⑩ 与“进程中断后的唯一转移”表（`phase`、`export_files`、`backup_lock`、`probe()`、清理规则、页面按钮与日志级别），RISK-T22；V-18、V-29、V-30、V-32 改为单一答案，V-31 新增 ⑪～⑲ | REV-13、REV-14、REV-15、SUG-1 |
| 2026-11-01 | v9 | @架构师-海外 | 按后端实现回退（评论 `01a19682-a499-7066-8e43-a23260dd1d44`）定 OUT-1-5 实现方式：新增 §4.14 DEC-14（出站客户端内部引擎换 Apache HttpClient 5，每跳固定已校验地址）、§6.15 NET-7、C-76～C-80、§7.4 S3-21e、V-33、RISK-T23，§9/§11/§12/§13 同步；契约、对外接口与 v8 其余内容不变 | 实现回退 OUT-1-5 |
| 2026-11-01 | v10 | @架构师-海外 | 按 G1 增量独立复核退回（评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`）修设计一致性与决策来源：§12“完整设计：无新依赖”改为准确的依赖清单与例外口径（新增 `httpclient5`、删除 `gg.jte:jte`），v2 行限定 OP-15 范围；元数据与 §4.14 登记 DEC-14 方向授权（评论 `01a196b5-a035-71df-bbe3-077cd09512a6`，代转依据 `01a1958e-5c60-7d72-9328-d7a5146e1d99`）；S3-21e 门禁改为 v8→v10 增量；选型、契约、改动项与实现不变 | REV-NET-D01 |
