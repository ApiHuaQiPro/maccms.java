# 技术设计 — APPK-2 PHP 模板迁移：模板包目录与对照环境（OP-15）

| 字段 | 值 |
| --- | --- |
| 创建者 | @架构师-海外 |
| 创建时间 | 2026-10-31 19:15（UTC+8） |
| 版本 | v1 |
| 状态 | 评审中 |
| Issue | APPK-2 |
| 上游 PRD | `docs/prd/APPK-2.md` 及附表 `docs/prd/APPK-2/`（A～F）@ `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`（v9） |
| UI 设计 | 不涉及：本版只定目录与配置，不涉及页面呈现 |
| 涉及仓库 | `ApiHuaQiPro/maccms.java`（单仓） |
| 基线提交 | `ApiHuaQiPro/maccms.java` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969`（deploy 分支 `release/APPK-2-php-template-migration` 当前头） |
| 本版范围 | **只回答 PRD 的 OP-15**（模板包放哪、5 个目录配置项取值、PHP 对照环境配置、后台模板管理目录、静态生成读写目录）。FR-1～FR-13 的完整技术设计在 OP-15、OP-16 关闭并经 G0 范围确认后，在同一文件续写（v2 起） |

## 1. 现状与目标

### 1.1 现状（均在基线提交核实）

**PHP（`php/`，`2023.1000.3051`，只读）怎么定位模板**

| 事实 | 出处 |
| --- | --- |
| 配置基线值：`template_dir='default'`、`html_dir='html'`、`mob_status='0'`、`mob_template_dir='default'`、`mob_html_dir='html'`、`ads_dir='ads'`、`mob_ads_dir='ads'`、`install_dir='/'` | `php/application/extra/maccms.php:31,34-38,42-43` |
| 每个请求的模板根：`ROOT_PATH.'template/'.$TMP_TEMPLATEDIR.'/'.$TMP_HTMLDIR.'/'`；URL 前缀 `MAC_PATH_TEMPLATE = install_dir.'template/'.$TMP_TEMPLATEDIR.'/'`，`MAC_PATH_TPL`、`MAC_PATH_ADS` 在其后接 `html_dir`、`ads_dir`；ThinkPHP `template.view_path` 同值 | `php/application/common/behavior/Init.php:33-35,48-62,71` |
| 手机切换：UA 命中手机正则、没有命中站群，并且 `mob_status==2`，或 `mob_status==1` 且 `Host == site_wapurl` 时，改用 `mob_template_dir`/`mob_html_dir`/`mob_ads_dir` | `Init.php:14-19,37-43` |
| 站群 `extra/domain.php` 命中时并入站点项且不再切手机模板；基线 `domain.php` 是空数组 | `Init.php:21-31`；`php/application/extra/domain.php` |
| 后台保存系统配置时，`ads_dir`/`mob_ads_dir` 被改写为 `template/<template_dir>/info.ini`、`template/<mob_template_dir>/info.ini` 的 `adsdir`（没有该键时为 `ads`） | `php/application/admin/controller/System.php:73-87` |
| 后台模板管理：浏览根是 `./template`（`.@template`）；`current=1` 列 `./template/<template_dir>/<html_dir>/*`，`label=1` 列其下 `label/*`，`ads=1` 列其下 `ads/*`；广告页 `Template::ads` 列 `./template/<template_dir>/<ads_dir>/*.js`，目录不存在时创建。**只用桌面的 `template_dir`，不看 `mob_*`** | `php/application/admin/controller/Template.php:12-57,108-116` |
| 系统配置页的模板下拉 = `glob('./template/*', GLOB_ONLYDIR)` | `System.php:134-138` |
| 静态生成：`ac2=wap` 时改用 `mob_template_dir`/`mob_html_dir`/`mob_ads_dir` 读模板，否则用请求初始化时的桌面目录；输出文件相对站点根 `./`（如 `index.html`、`wap_index.html`、`map/index`、`rss/*`，后缀补全见 `reset_html_filename`） | `php/application/admin/controller/Make.php:16-25,28-41,169-175`；`php/application/common.php:2802-2824` |
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
| 手机/站群选择已按 `Init.php` 实现并由 PHP 黄金锁定，结果含 `templateDir`、`htmlDir`、`adsDir`、`pathTpl`、`pathAds` | `site/application/SiteTemplateSelection.java:28-110`；`SiteTemplateSelectionGoldenTest` |
| 后台系统配置的 `ads_dir`/`mob_ads_dir` 改写、模板下拉都已按 PHP 实现 | `admin/application/SystemConfigService.java:91-99,217-227,297-299` |
| 后台模板管理与广告页已按 PHP 路径实现（`./template/<template_dir>/<ads_dir>`，不存在时建目录）；编辑扩展名当前是 B15 改的 `jte/html/js/xml` | `admin/web/AdminTemplateActions.java:36-44,168-200` |
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

## 2. 范围与非目标

**本版做**：DEC-1～DEC-6 的决定、对应的文件级改动清单（模板包入库、对照环境装配脚本与配置、`.gitattributes`/`.gitignore`/部署样例的修改）和验证方式。

**本版不做**（防范围蔓延）：

- FR-1～FR-13 的实现设计（模板解析器、标签、函数、PHP 片段等效、jte 前台下线、控制器对齐、在线功能）——G0 之后在本文件 v2 起续写。
- OP-16（缺页面补不补）：由发起人决定，本设计的目录与配置对 a、b 两种答案都成立；答 b 时只需把补充的模板文件放进同一目录（见 RISK-T7）。
- 应用层缓存隔离（V9-001）、数据恢复脚本实现、`php/vendor/` 恢复步骤：属于 PRE-2 与完整设计，记入 OP-T2、OP-T3。
- 修改 `php/` 下任何文件（`php/` 只读，`AGENTS.md:5`）；修改线上配置。
- 产品范围、业务规则、样本分母：不改。附表 B 的 120 个固定模板名、桌面 79 有/41 缺、手机 63 有/57 缺在本设计的目录下保持不变（目录名不改变包内相对路径）。

## 3. 影响面

| 类别 | 对象 | 影响 | 证据（调用方 / 读取方检索） |
| --- | --- | --- | --- |
| 数据（文件） | 新增 `third_party/maccms-template-v2020.1000.1035/`（274 个模板文件 + `LICENSE` + `SOURCE.md`） | 新增，只读资产；不进 jar | 基线无 `third_party/`；`pom.xml` 资源段只引用 `php/static`，不会把它打包 |
| 配置（仓库） | `.gitattributes` | 加一行，使模板包按字节检出，不做换行转换 | 现有同类规则：`src/test/resources/legacyconfig/cases/*.php -text -whitespace` |
| 配置（仓库） | `.gitignore` | 加 `.compare/`（对照环境装配目录） | — |
| 配置（旧站） | `application/extra/maccms.php` 的 `site.template_dir`、`html_dir`、`mob_template_dir`、`mob_html_dir`、`ads_dir`、`mob_ads_dir`；对照环境另有 `site.mob_status` | **只在对照环境的副本和部署方的站点里取新值**，仓库 `php/application/extra/maccms.php` 不改 | 读取方（`git grep -n "template_dir\|html_dir\|ads_dir" a57e9cb`）：PHP `Init.php`、`Make.php`、`System.php`、`Template.php`、`Domain.php`；Java `SiteTemplateSelection`、`SiteTemplateService`、`SystemConfigService`、`AdminTemplateActions`、`ListConfigService`、`admin/pages/system/config.jte`、`admin/pages/domain/index.jte`、`static/admin-assets/domain-index.js`。这些读取方都按配置值拼路径或原样显示、保存（`ListConfigService`、两个 `domain` 文件只做站群配置的显示与保存），没有写死 `default`，**取值变化不需要改它们的代码**（`SiteTemplateService.root()` 的回退值 `default` 只在读不到配置时用，随 jte 前台下线一起处理，见 §2） |
| 配置（部署） | `config/maccms-backend.env.example` | 只加说明：站点根需有 `template/default_pc`、`template/default_wap` | 现有 `MACCMS_LEGACY_CONFIG_DIR` 说明段 |
| 测试工具 | 新增 `tests/compare/APPK-2/`（装配脚本、配置覆盖表、`php.ini`、模板包校验脚本） | 新增；只在本机对照时运行 | `MULTICA.md` §2 测试路径；PRE-2 的路径“设计单指定” |
| 外部依赖 | 官方仓库 `magicblack/maccms10` | **只在一次性入库时**读取；之后验收与对照不连外网 | 附表 E.1；Q6 本期不连外部沙箱 |

**明确不受影响**：`php/**`（只读）；数据库结构与数据（本设计不读写任何表）；`src/main/**` 的 Java 代码（本版无代码改动，见 §5）；`LegacySiteFiles` 的可读写区域（`template/` 已在 `RW_AREAS`）；jar 内容；`MULTICA.md` §0。

## 4. 方案对比

### 4.1 模板包目录与配置取值（DEC-2、DEC-3）

| 方案 | 做法 | 改动量 | 风险 | 可回滚 | 沿用现有模式 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| A 最小改动：保留配置基线 `default`/`html` | 把 `default_pc` 改名放到 `template/default/`，`default_wap` 保持原名，只改 `mob_template_dir=default_wap` | 配置改 1 项；包内 147 个文件路径改前缀 | 包内路径与附表 E.3 不再一一对应（AC-1 逐文件比对要做路径映射）；与 `info.ini`、官方文档的目录名不一致；`template/default` 与仓库 `php/template/default` 同名，易混入那份 0 字节文件 | 是 | 部分 | 不选 |
| B 官方目录名 + 改配置值 | 包按官方原样放 `template/default_pc/`、`template/default_wap/`；`template_dir=default_pc`、`mob_template_dir=default_wap`，其余 `html`/`ads` 不变 | 配置改 2 项（对照环境另改 `mob_status`）；包内路径不变 | 部署方须同时改配置（RISK-T6） | 是：改回配置值即可 | 是：PHP 与 Java 现有读取方都按配置拼路径，零代码改动 | **选** |
| C 两套包都改名为 `default` 下的不同 `html_dir` | `template/default/html_pc/`、`template/default/html_wap/` | 274 个文件全部改路径 | 拆开了包的 `css/js/images` 与 `info.ini`（两个包各有一份，同名冲突） | 是 | 否 | 不选：同名文件冲突，无法原样放置 |

选 B 的理由：模板文件和路径都与附表 E 完全一致（BR-11 原样使用，AC-1 可直接用目录树 SHA 校验）；PHP 与 Java 的现有代码都按配置值拼路径，只改配置、不改代码；回退只需改回配置值。

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

## 5. 改动清单

全部在 `ApiHuaQiPro/maccms.java`；路径相对仓库根。本版**没有** `src/main/**` 的改动。

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

## 6. 数据与接口

**数据库**：不涉及——本设计不读写任何表，不改结构。

**文件与配置（本版的“数据”）**——下列取值即 OP-15 的答案：

| 编号 | 项 | 取值 |
| --- | --- | --- |
| DEC-1 | 仓库内模板包位置 | `third_party/maccms-template-v2020.1000.1035/template/default_pc/`、`…/template/default_wap/`；目录树 SHA-1 必须分别等于 `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7`、`a211f900e060c1d405f2c9d75523e02abac45b80`，`…/template` 等于 `9398a1ed539cc754b7513c76e12134b3b5b49a53` |
| DEC-2 | 部署时模板包位置（Java 生产部署与 PHP/Java 对照环境相同） | 站点根下 `template/default_pc/`、`template/default_wap/`，从 DEC-1 原样复制；站点根 = PHP 的 `ROOT_PATH` = Java 的 `MACCMS_LEGACY_CONFIG_DIR` 上一级。对照环境的站点根里 `template/` 下只有这两个目录 |
| DEC-3 | `extra/maccms.php` 的 `site` 节 | `template_dir='default_pc'`、`html_dir='html'`、`mob_template_dir='default_wap'`、`mob_html_dir='html'`、`ads_dir='ads'`、`mob_ads_dir='ads'`（两个 `info.ini` 的 `adsdir` 都是 `ads`，与后台保存时的改写结果一致，保存配置不会让它漂移）。由此：桌面模板根 `template/default_pc/html/`，手机模板根 `template/default_wap/html/`；`{$maccms.path_tpl}` = `/template/default_pc/html/`（手机 `/template/default_wap/html/`），`{$maccms.path_ads}` = `/template/default_pc/ads/`（手机 `/template/default_wap/ads/`，该目录不存在，两侧都按文件不存在处理） |
| DEC-3a | `mob_status`（仅对照环境） | `'2'`。生产部署的 `mob_status` 由站点自己的配置决定，本设计不改 |
| DEC-4 | 对照环境 | 见 §7.3 ENV-1～ENV-8 |
| DEC-5 | 后台模板管理目录（FR-7） | 见 §6.1 |
| DEC-6 | 静态生成读写目录（FR-6） | 见 §6.2 |

### 6.1 后台模板管理（FR-7、AC-8、AC-9、附表 D 的 D-20）

按 PHP `Template.php` 原样（Java `AdminTemplateActions` 已按同一路径实现，取值变化后自动落在下列目录）：

| 入口 | 读写目录（相对站点根） |
| --- | --- |
| `template/index`（无参数） | 浏览根 `template/`，可逐级进入 `template/default_pc/…`、`template/default_wap/…`（手机模板经此浏览编辑） |
| `template/index?current=1` | `template/default_pc/html/` |
| `template/index?label=1` | `template/default_pc/html/label/`（包内有 `label/rank.html`） |
| `template/index?ads=1` | `template/default_pc/html/ads/`（PHP 原样：在 `html/` 下找 `ads/`，包内没有，列表为空） |
| `template/ads` | `template/default_pc/ads/*.js`（包内 `aaa.js`）；目录不存在时创建——只会创建桌面目录，不会在 `default_wap` 下建 `ads/` |
| `template/info`、`template/del`（编辑、保存、删除） | 只在 `template/` 区域内；写入沿用 `LegacySiteFiles` 原子写（BR-6） |
| 系统配置页“模板目录”下拉 | `template/` 下的子目录：对照环境为 `default_pc`、`default_wap` |

编辑扩展名、安全过滤、并发冲突提示属于 FR-7 的实现设计（v2 起），不在本版。

### 6.2 静态生成（FR-6、AC-7）

| 项 | 取值 |
| --- | --- |
| 读模板（桌面，`ac2` 不是 `wap`） | `template/default_pc/html/` |
| 读模板（`make/index?ac2=wap`） | `template/default_wap/html/`（`Make.php:16-25`） |
| 写输出 | 站点根下的相对路径，与 PHP `buildHtml(..., './', ...)` 相同（`index.html`、`wap_index.html`、`map/index.<suffix>`、`rss/*`、按 `view`/`path` 规则的分类、详情、专题页）；Java 经 `StaticFiles` 写入 |
| Java 独有的登记文件 | `application/data/backup/static-pages/generated.txt`（B16 回滚用），**不属于生成的静态页**，CMP-4 比较时排除（ENV-7） |
| 对照时“事先清空的输出目录”（AC-7） | 每个 K- 用例的每一侧单元开始前，用 `pristine/` 重新复制出该侧站点根（ENV-6），而不是在站点根里挑文件删除 |

**接口**：不涉及——本版不新增或变更任何 HTTP 接口。前台对 `/template/<dir>/{css,js,images,ads}/…` 静态资源的 URL 由 DEC-3 固定，Java 由谁提供这些文件见 OP-T1。

## 7. 实现步骤（按端）

### 7.1 后端（@后端开发-海外）——G1 放行后执行

1. C-1～C-4：在一次性的空目录里取官方提交（`git clone --filter=blob:none --no-checkout https://github.com/magicblack/maccms10` 后 `git -c core.autocrlf=false archive 4c418c2703692148a937ad92893ab8a494bff742 template LICENSE`，解到 `third_party/maccms-template-v2020.1000.1035/`），不运行其中任何脚本。可单独验证：§8 V-1。
2. C-5：先提交 `.gitattributes`，再 `git add` 模板包（顺序反了会按 `autocrlf` 入库）。验证：V-1 的目录树 SHA 相等。
3. 同一提交内不改 `src/main/**`。

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

用例关注点（交 @测试人员-海外）：手机 UA 必须拿到 `default_wap` 的页面（`mob_status=2`）；`default_wap` 下没有 `ads/`，任何请求都不得在其下建目录；后台保存系统配置后 `ads_dir`/`mob_ads_dir` 仍为 `ads`；把 `template_dir` 改成不存在的目录时（AC-1 ③）两侧都出模板缺失页。

## 8. 验证方式

| 编号 | AC- / 前置 | 验证方式 | 具体做法 | 门禁 |
| --- | --- | --- | --- | --- |
| V-1 | AC-1 ①（仓库内） | 人工核对 + 命令 | `git rev-parse <提交>:third_party/maccms-template-v2020.1000.1035/template` = `9398a1ed539cc754b7513c76e12134b3b5b49a53`；`…/template/default_pc` = `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7`；`…/template/default_wap` = `a211f900e060c1d405f2c9d75523e02abac45b80`；`git ls-tree -r --name-only <提交> third_party/maccms-template-v2020.1000.1035/template \| wc -l` = 274 | G2（后端评审） |
| V-2 | AC-1 ①（放置后的工作区） | 脚本 | `python tests/compare/APPK-2/verify_template_package.py .compare/APPK-2/php-site` 与 `…/java-site` 各一次：274 个文件 SHA-1 与附表 E.3 一致，`template/` 下只有两个目录，`default_wap/ads` 不存在 | PRE-2 |
| V-3 | PHPB-9、ENV-3/4 | 脚本 | C-8 输出两侧 13 个 CS-CONFIG 文件的 SHA-256 逐个相同；`maccms.php` 里 C-7 的 7 个键各出现一次且取值正确，其余行与 `php/application/extra/maccms.php` 逐字节相同（`diff` 只出现 7 行） | PRE-2 |
| V-4 | PRE-2（PHP 侧模板选择） | 接口请求 | 对 `php-site` 发 `GET /`：桌面 UA（CMP-1）返回 200 且响应体含 `default_pc/html/index/index.html` 的固定文本（由 PRE-2 选定一段不含标签的字面串）；手机 UA 返回 `default_wap/html/index/index.html` 的对应字面串 | PRE-2 |
| V-5 | AC-1 ②③、AC-10（Java 侧） | 接口用例 | 同 V-4 对 `java-site` 的 Java 进程执行（FR-1/FR-8 实现后）；再把 `java-site` 的 `template_dir` 改为不存在的目录并重启，请求首页得到模板缺失页（AC-1 ③），然后按 ENV-5 恢复 | G3 前的本机对照 |
| V-6 | AC-7 | 对照脚本 | 按 ENV-6、ENV-7 执行 K-01～K-12，比较两侧差异文件的路径集合与字节（CMP-4） | PRE-2 + AC-7 |
| V-7 | AC-8、AC-9 | 端到端 / 接口用例 | 以 §6.1 表格逐行请求两侧后台：列出的目录与文件一致；`template/ads` 不在 `default_wap` 下建目录 | AC-8 |
| V-8 | 构建不受影响 | 构建命令 | `./mvnw -B -q package -DskipTests` 成功，且 `jar tf target/maccms-backend-0.1.0-SNAPSHOT.jar` 中没有 `third_party/` 条目 | G2 |

构建验证命令（`MULTICA.md` §3）：`./mvnw -B verify`（本版无 Java 改动，作回归确认即可）。

回归点：后台系统配置保存（`ads_dir` 改写）、`SiteTemplateSelectionGoldenTest`（取值变化不应影响该纯函数测试）、后台模板管理列表与广告页、静态生成首页 `index.html`/`wap_index.html` 的路径。

## 9. 回滚

| 步骤 | 回退方式 | 顺序 |
| --- | --- | --- |
| C-1～C-5 | `git revert` 入库提交；删掉 `third_party/maccms-template-v2020.1000.1035/` 不影响任何运行代码（jar 不含它） | 可独立回退 |
| C-6～C-10 | `git revert`；删除本机 `.compare/APPK-2/` 目录（只在本机，不影响仓库） | 可独立回退 |
| C-11 | `git revert` 说明文字 | 可独立回退 |
| 部署方的配置（DEC-3） | 把 `template_dir`/`mob_template_dir` 改回原值并重启；模板目录可保留不删 | 先改配置、后删目录 |

没有不可逆改动：不动数据库，不改 `php/`，不改线上配置。

## 10. 风险与待决

| 编号 | 风险 | 触发条件 | 缓解 | 残余 |
| --- | --- | --- | --- | --- |
| RISK-T1 | 换行转换改写模板字节，SHA-1 与附表 E.3 不符、对照出现 `\r\n` 差异（NOIGN-4） | 本机 `core.autocrlf=true`；先 `add` 后加 `.gitattributes`；从工作区复制 `php/` | C-5 `-text`，先提交 `.gitattributes`；ENV-1 一律 `git -c core.autocrlf=false archive` 导出；V-1、V-2 校验 | 无 |
| RISK-T2 | 对照环境 `mob_status=2` 与仓库基线 `0` 不同 | 对照时 | 只写在对照副本（C-7），不改 `php/`；桌面样本用桌面 UA，不受影响；12 个模板选择样本走 `SiteTemplateSelectionGoldenTest` 的独立黄金，不读此值 | 生产站点的 `mob_status` 由部署方决定，不在本期验收 |
| RISK-T3 | `mob_ads_dir=ads` 但 `default_wap` 没有 `ads/`，手机页面引用的广告脚本 404 | 手机页面含 `{$maccms.path_ads}` | 两侧同为“文件不存在”，PHP 不会建该目录（`Template::ads` 只用桌面目录）；Java 实现不得在页面渲染时建目录（V-7 检查） | 无 |
| RISK-T4 | Java 静态生成多写登记文件，被当成多余生成文件 | AC-7 | ENV-7 明确排除 | 无 |
| RISK-T5 | 某单元的后台保存改写配置，后续单元两侧读到的配置不同 | FR-12 后台配置样本 | ENV-5 每单元前比对并恢复 13 个文件 | 无 |
| RISK-T6 | 已有站点升级后未放模板包或未改配置，前台全部“模板文件不存在” | 部署方漏做 C-11 的步骤 | C-11 写明部署顺序；AC-1 ③ 已覆盖缺目录时的错误页与后台提示 | 部署方责任，本期不部署（Q7） |
| RISK-T7 | OP-16 答 b（补页面）时目录要不要变 | 发起人选 b | 补充文件放进 `template/default_pc/html/`、`template/default_wap/html/` 的对应相对路径即可，目录与配置不变；但补充文件不属于官方包，须另起来源目录并修订 BR-11/附表 E 与本设计 | 待 OP-16 |

| 编号 | 问题 | 是否阻塞开发 | 需谁回答 | 期限 |
| --- | --- | --- | --- | --- |
| OP-T1 | Java 由谁提供 `/template/<dir>/{css,js,images,ads}/…` 静态资源：Java 从站点根读取提供，还是同 `/upload/` 一样由部署层 nginx 提供。URL 已由 DEC-3 固定，比较样本不含这些资源请求 | 不阻塞 OP-15 关闭；阻塞 FR-1 实现 | 架构师（完整设计 v2） | G1 前 |
| OP-T2 | 应用层缓存隔离（V9-001：PHP `runtime/cache`、Java 进程内缓存、模板编译缓存）在每个请求单元前怎么清 | 不阻塞 OP-15；阻塞 AC-5～AC-7、AC-10 执行 | 架构师（完整设计 v2）+ 测试人员（PRE-2） | G1 前 |
| OP-T3 | ENV-2 中 `php/vendor/` 恢复、MySQL 会话时区 `+08:00`（PHPB-8）在 PHP 侧的设置方式 | 不阻塞 | 测试人员（PRE-2） | PRE-2 完成前 |

## 11. 需求追溯

| 需求 | 方案要点 | 改动清单行 | 验证行 |
| --- | --- | --- | --- |
| OP-15 ①模板包放哪 | DEC-1、DEC-2 | C-1～C-5、C-11 | V-1、V-2 |
| OP-15 ②五个目录配置项 | DEC-3（另 `mob_ads_dir`、对照 `mob_status`） | C-7、C-8、C-11 | V-3、V-4 |
| OP-15 ③PHP 对照环境 | DEC-4、ENV-1～ENV-8 | C-6～C-10 | V-2、V-3、V-4 |
| OP-15 ④后台模板管理目录 | DEC-5、§6.1 | 无代码改动（取值驱动） | V-7 |
| OP-15 ⑤静态生成读写目录 | DEC-6、§6.2、ENV-6/7 | C-8 | V-6 |
| FR-1、AC-1、BR-11 | 原样入库、原样放置、按 E.3 校验 | C-1～C-5、C-9 | V-1、V-2、V-5 |
| FR-6、AC-7、BR-6 | §6.2；两侧独立站点根 | C-8 | V-6 |
| FR-7、AC-8、AC-9、D-20 | §6.1 | — | V-7 |
| FR-8、AC-10 | `mob_template_dir=default_wap`、`mob_status=2` | C-7 | V-4、V-5 |
| AC-5 | 对照环境 ENV-1～ENV-8 | C-6～C-10 | V-3、V-4 |
| PRE-2、PHPB-1～PHPB-9 | ENV-2/3/4/8、C-10 | C-8、C-10 | V-3 |
| 附表 D 的 D-01（模板目录写法引用 OP-15） | 模板读取目录 = `template/default_pc/html/`、`template/default_wap/html/` | — | V-4 |

## 12. 源码合理自检

| 清单项 | 结论 | 依据（@ `a57e9cb`） |
| --- | --- | --- |
| 现状有源码出处 | 是 | §1.1 每行带文件与行号 |
| 被改配置键的读取方查全 | 是 | `git grep -n "template_dir\|html_dir\|ads_dir\|mob_template\|mob_html" a57e9cb -- php/application src/main`：PHP 5 个控制器/行为、Java 8 处（§3 配置行逐一列出），全部按配置值拼路径或原样显示、保存，没有写死目录名 |
| 沿用现有模式 | 是 | 站点根 = `MACCMS_LEGACY_CONFIG_DIR` 上一级（`InstallConfiguration.java:36-38`）；文件写入经 `LegacySiteFiles`；`.gitattributes` 按字节规则同 `src/test/resources/legacyconfig/cases/*.php` |
| 无新依赖 | 是 | 不加 Maven 依赖；装配脚本用 Python 标准库与 git（与现有 `src/test/browser/*.py` 同语言） |
| 无数据库结构变更 | 是 | 不读写任何表 |
| 无安全回退 | 是 | 不放宽 `LegacySiteFiles` 的区域与路径校验；对照环境只监听 127.0.0.1；凭据只经环境变量，脚本不打印 |
| 不改 `php/` | 是 | 覆盖只作用于 `.compare/` 副本（C-7、C-8） |
| 改动可回滚 | 是 | §9 |
| 与 PRD 无矛盾 | 有一处需评审确认：PHPB-9 写“两侧各写其对应值”，本设计取“两侧取值相同”（ENV-4），这是“各写其值”的特例，且是同时满足“13 个文件 SHA-256 相同”的唯一做法 | 附表 F.1 PHPB-9 |

## 13. 评审回应

首版，无。

## 14. 修订记录

| 日期 | 版本 | 作者 | 变更摘要 | 对应 REV- |
| --- | --- | --- | --- | --- |
| 2026-10-31 | v1 | @架构师-海外 | 首版：回答 OP-15（DEC-1～DEC-6、ENV-1～ENV-8），列改动清单 C-1～C-11、验证 V-1～V-8；FR-1～FR-13 完整设计待 G0 后续写 | — |
