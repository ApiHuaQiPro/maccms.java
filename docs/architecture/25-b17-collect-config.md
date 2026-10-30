## B17 采集配置管理（路线图 B17，MACC-41）

> **状态：交付待验收。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B17 行、第 5.3 节 B17 验收行（增删改与 PHP 一致；`bind.php` 键算法 `md5(url)` 不变；本批不发起任何请求；回滚演练）、写入方矩阵、第 1.2 节（零结构变更，硬性要求 2）；
> - B07 `12-write-infrastructure.md`（写 SQL 规范、登记表、回滚演练工具）、B12 `21-b12-admin-framework.md`（后台入口、会话、权限、页面与脚本策略）、B13 `22-b13-admin-content.md`（后台表单取值规则 `AdminTable`/`AdminRowValues`、D-B13-3/4/19 的同一规则）、B15 `24-b15-admin-system.md`（旧配置原子写回 `LegacyConfigService.update`、`var_export` 等价输出、D-B15-18 重启生效的口径）；
> - 用户决定（2026-10-17）：后台复用旧后台 HTML 结构与静态资产，转成 jte，不做新视觉设计。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | main `4ad7b83`（B15 合并 `1a0ac63` 之后；平台基线提交 `20cdfcc` 的树与它相同） |
| 分支 | `agent/claude/macc-41`，只用普通提交，不 reset、rebase、amend、force，不自行合并 main |
| 回滚点 | main `4ad7b839f23916d8b748c020d24af3881ab7109d` |
| 提交 | 见交付评论 |

### 1. 范围

| 旧控制器（`application/admin/controller/`） | 本批实现的动作 |
|---|---|
| `Collect.php` | `index`（采集源列表）、`info`（GET 表单、POST 保存）、`del`、`union`（只剩空壳页面）、`bind`、`clearbind`（分类绑定）、`test`（固定提示，不连接，D-B17-1） |
| `Cj.php` | `index`（自定义规则列表）、`info`（GET 表单、POST 保存）、`del`（连同节点下的内容与采集历史）、`export`、`import` |

| 不在本批（归属，`AdminPermissions.ACTION_BATCH`，批次表未改） | 说明 |
|---|---|
| `collect/api`、`collect/load`（资源站解析与预览、断点续采） | B18（本地文件源）；按 B12 分派返回"由 B18 批次提供"，不发请求 |
| `collect/vod` `art` `actor` `role` `website` `timing`（入库） | B19 |
| `cj/program` `col_url` `col_content` `publish` `col_all` `show` `content_del` `content_into` `show_url`（规则执行） | B20；规则表单上的"测试"按钮打开 `cj/show_url`，在本批得到"由 B20 批次提供" |
| 采集参数配置页 `system/configcollect`（`maccms.php` 的 `collect.*`） | B15 已交付；本批不改 |

"先只读、再编辑"：只读部分（两张列表、两个表单、导出）只用只读 Mapper；编辑部分（保存、删除、导入、绑定、清除绑定）只经第 6 节登记的写 Mapper 与 `LegacyConfigService.update`。两者都只在后台开启（`maccms.admin.enabled`）且可写数据源打开（`maccms.write.enabled`）时注册（后台本身要求可写数据源，21 文档第 7 节）；没有注册时这些动作照旧返回"由 B17 批次提供"。

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| `collect.application` | `CollectAdminServices`（三项服务的组合，只供 admin 调用）、`CollectAdminConfiguration`（`@ConditionalOnWriteEnabled` 装配）、`CollectSourceAdminService`（采集源，第 3 节）、`CollectBindService` + `CollectBindings`（分类绑定，第 4 节）、`CjNodeAdminService`（自定义规则节点，第 5 节）、`TypeNames`（本站分类名，取自 content 的分类缓存）；`view.CjNodeForm`、`view.CjNodeExport` |
| `collect.infrastructure` | 只读 `CollectAdminMapper`（`mapper/collect/CollectAdminMapper.xml`）；写 `CollectAdminWriteMapper`（`mapper-write/collect/CollectAdminWriteMapper.xml`）+ `CollectAdminWrites`（把 MySQL 严格模式的数据错误换成 `CollectDataException` → "参数错误"）+ `CollectInsert`；装配 `CollectInfrastructureConfiguration` |
| `admin.web` | `AdminCollectActions`（动作分派，`AdminController` 在 B15 之后交给它）；视图 `view/collect/{CollectListView,CollectInfoView,CjListView,CjInfoView}` |
| `content.application` | `AdminTable` 新增 `COLLECT`、`CJ_NODE` 两张表的列定义（取值规则复用 B13 的 `AdminRowValues`：只写表里有的列、整数与字符长度按严格模式校验） |

- 边界：`admin` 只经 `AdminCollectActions` → `CollectAdminServices` 调用 collect；collect 用 content 的 `AdminTable`/`AdminRowValues`/`PhpForm`/`PhpJson` 与 `legacyconfig.application` 的 `LegacyConfigService`/`VarExportWriter`（同 B13 comment、B14 member 模块的用法），`ModuleBoundaryTest` 不需改动。
- 采集源的只读查询**不读** `collect_appid`、`collect_appkey`（资源站接口账号与密钥；旧后台页面也不显示）：`ReadOnlyGuardrailsTest.collectAdminMapperNeverSelectsResourceSiteCredentials` 锁定。保存时这两列只在表单提交了它们时才写（旧表单没有这两个字段，所以编辑不会清空它们，与旧版相同）。

### 3. 采集源（`mac_collect`）

旧 `Collect::index/info/del` → `common/model/Collect.php:23-77`。

- 列表：无条件、`collect_id desc`；每页默认 100（`intval($param['limit']) < 1 ? 100 : $param['limit']`，不用后台每页条数）。每行的"进入资源库""采集当天/本周/所有"链接按旧模板的 `http_build_query` 生成（`cjflag = md5(collect_url)`、`param` 为 `base64_encode(collect_param)`），指向 `collect/api`（B18）。
- 表单：`where collect_id = input('id')`，没有这一行时为空表单。
- 保存：先校验表单令牌（缺失 → "非法请求"，不一致 → "请不要重复提交表单"，同 B12）；验证器 `edit` 场景（名称、地址必填，新增与编辑都用它）；`collect_id` 非空时按主键覆盖提交的列，否则新增。
- 删除：`ids` 为逗号串或数组；为空时"参数错误"；每项必须是十进制数字（D-B17-3）。

### 4. 分类绑定（`application/extra/bind.php`）

旧 `Collect::bind/clearbind`（`admin/controller/Collect.php:181-228`）。键算法不变（`CollectBindings`，供 B18/B19 沿用）：

- 资源站标识 `cjflag = md5(采集地址)`（按地址原始字节，小写十六进制；`admin/view/collect/index.html:47`）；
- 绑定键 `cjflag . '_' . 远程分类编号`（`common/model/Collect.php:211、343、1102、1429、2020`，`admin/controller/Collect.php:232`）；
- 本地分类 = `intval($bind_list[$key])`，大于 0 时为绑定的分类，否则未绑定；键按 PHP 数组规则规范化（`'15'` 与整数键 15 是同一个键）。

`bind`：`col` 为 `empty()`（缺失、空串、`'0'`）时"参数错误"；否则 `$config[$col] = intval($val)` 写回，成功时 `success(lang('save_ok'), null, data)`，`data` 为 `id`（`col` 原值）、`st`（`intval($val) > 0` 时 1）、`local_type_id`（`$val` 原值）、`local_type_name`（`st` 为 1 时取本站分类名：`$val` 是规范十进制整数且分类存在时为分类名，否则 null；`st` 为 0 时空串）。

`clearbind`：`cjflag` 非 `empty()` 时只保留键里不含它的项（`strpos === false`，整数键按文字比较），否则清空；返回 `{"code":1,"msg":"清除成功"}`，写不进去时 `code=0`、"清除失败"。

写回（D-B17-5）：只经 B15 的 `LegacyConfigService.update` —— 进程内文件锁里"读出当前内容 → 改一个键 → `VarExportWriter.macArr2File` → 先确认新版能读回 → 原子改名"。两个管理员同时绑定不互相覆盖；读写之间被进程外改过（`CONFLICT`）、或文件读不回（旧版 `config('bind')` 会 include 任意 PHP，新版只解析 `var_export` 子集，不执行 PHP）时"保存失败"、文件不变。写出的内容与 PHP `mac_arr2file` 逐字节相同，切回 PHP 后照常读取。

生效范围：新系统里读 `bind.php` 的只有本批（B18/B19 尚未实施），每次请求按文件版本重读，保存后即生效；不涉及 D-B15-18 的"重启生效"。

### 5. 自定义规则节点（`mac_cj_node`）

旧 `Cj::index/info/del/export/import` → `common/model/Cj.php`。

- 列表：无条件、`nodeid desc`；每页默认为后台每页条数（`app.pagesize`）。
- 表单：`where nodeid = input('id')`；`customize_config` 非 `empty()` 时 `json_decode(…, true)` 后逐项列出 `name/en_name/rule/html_rule`（不是数组时没有项）。
- 保存（旧版不校验表单令牌，照旧）：`$data = $param['data']`；`urlpage = (string)$param['urlpage' . $data['sourcetype']]`；`data[customize_config]` 非 `empty()` 时按 `name[]` 逐项取四个字段（名称为 `empty()` 的项跳过）后 `json_encode(…, JSON_FORCE_OBJECT)`，一项都没有时写 `null`（旧版同）。`lastdate = time()`；`nodeid` 非空时按主键覆盖，否则新增（`urlpage`、`page_base` 缺省为空串）。
- 删除（`Cj::delData`）：`ids` 为空时"参数错误"；依次删节点 → 按节点下各内容网址的 `md5` 删采集历史（每批 500 个网址）→ 删节点下的内容。顺序与旧版相同；表是 MyISAM 没有事务，中途失败时已完成的步骤不回退（与旧版相同）；重复删除是空操作。
- 导出：没有这一行时"获取失败"；否则附件 `mac_cj_名称.txt`，正文 `base64_encode(json_encode($node))`（列按 install.sql 顺序，整数列是 JSON 数字，与旧版经 PDO 取回的类型相同）；IE 时文件名先 `urlencode`；文件名里的控制字符不进头部（D-B17-7）。
- 导入：上传的 `file`；`json_decode(base64_decode(内容), true)`（非严格 base64）；解出的不是非空数组时 `success(lang('import_err'))`（旧版就是 `success`，照旧）；否则去掉 `nodeid` 后按保存的规则新增。没有文件、超过 10,240,000 字节、值是数组或 null 的列 → "参数错误"（D-B17-6）。

### 6. 写入登记（`WriteRegistry`）

| 模块 | 表 | 操作 | 语句（`CollectAdminWriteMapper.xml`） |
|---|---|---|---|
| collect | `mac_collect` | INSERT / UPDATE / DELETE | `insertCollect`、`updateCollect`（按主键）、`deleteCollects`（`collect_id IN (…)`） |
| collect | `mac_cj_node` | INSERT / UPDATE / DELETE | `insertCjNode`、`updateCjNode`（按主键）、`deleteCjNodes`（`nodeid IN (…)`） |
| collect | `mac_cj_content` | DELETE | `deleteCjContentsOfNodes`（`nodeid IN (…)`，只在删除节点时） |
| collect | `mac_cj_history` | DELETE | `deleteCjHistories`（`md5 IN (…)`，只在删除节点时） |

- 全部是单表 DML，不建表、不改结构（`SchemaGuardTest`、`WriteGuardTest`、`WriteRegistryTest`、`AdminTableSchemaTest` 锁定；`AdminTableSchemaTest` 另核对 `COLLECT`、`CJ_NODE` 的列定义与 install.sql 一致）。
- `mac_cj_content`、`mac_cj_history` 的写入（采集、发布）仍在 B20；本批只在删除节点时照旧连带删除（路线图写入方矩阵已同步）。
- 文件：`application/extra/bind.php`（经 `LegacyConfigService`，第 4 节）。

### 7. 页面：复用旧后台结构

- 旧 `application/admin/view/collect/{index,info,union}.html`、`cj/{index,info}.html` 逐个转写为 `src/main/jte-admin/admin/pages/{collect,cj}/*.jte`，标签、类名、布局原样；旧内联脚本逐行移到 `src/main/resources/static/admin-assets/{collect-info,cj-index,cj-info}.js`，地址与语言包文字经 `script` 标签的 `data-*` 属性传入（B12 的页面脚本策略）。
- 最小改动：`cj/info` 里的 `onclick` 与 `javascript:` 调用改为 `data-*` 属性，由页面脚本按同样参数调用同样的函数；`collect/union` 不再输出旧模板里的空 `<script src="">`（D-B17-8）；`cj/info` 里旧模板自带的示例文字 `http://www.phpcms.cn/help/rumen/(*).html` 照旧（不是链接，`AdminTemplatePolicyTest` 登记）；断点采集的按钮（按缓存里的断点地址显示）在 B18 接上，本批没有断点，不显示。
- 输出转义按 D-B13-19 的同一规则（D-B17-4）。

### 8. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金（PHP 8.0.30 与 7.3.33） | `src/test/php/admin_collect_golden.php`：一次性库 `maccms_it_<时间>_<随机>`、php-cgi 跑完整的 `_admin.php` + ThinkPHP + 控制器（复用 B15 的 `admin_system_child.php`），站点是临时副本。每个场景清空四张表并复位自增计数、写入场景的行与预置的 `bind.php`；每步记录状态码、头、正文、`bind.php` 的变化与四张表的全部行。两版各 **22 个场景、97 步** → `src/test/resources/admin/collect/php{80,73}-golden.json`。不调用会联网的动作 |
| 黄金复放（真实 MySQL） | `AdminCollectGoldenMySqlIT.scenariosMatchPhpGolden`：同样的请求发给新版（时钟拨到 PHP 请求开始的秒），比较状态码、JSON 字节、页面结构（`AdminContentHtml`）、导出正文与文件名、`bind.php` 逐字节与四张表的全部行；有意差异按第 9 节登记 |
| 绑定键黄金向量 | `CollectBindingsGoldenTest`：黄金里采集源列表页的 `cjflag` 与 PHP `md5`、绑定键与本地分类取值逐个对照 |
| 绑定（不连库） | `CollectBindServiceTest`：写出的 `bind.php` 与 `mac_arr2file` 逐字节相同、`data` 各字段、数字串键与整数键、清除的两种方式、文件不存在与读不回、24 线程并发绑定不丢更新 |
| 不发起网络请求 | `AdminCollectGoldenMySqlIT.collectTestNeverConnects`：`collect/test` 指向本机一个监听端口，返回固定提示且 2 秒内端口上没有连接；`OutboundNetworkGuardTest` 不变 |
| 并发 | `concurrentBindsNeverLoseAnUpdate`（32 线程绑定 32 个键，一个不丢、原有键不变）；`concurrentDeletesOfTheSameNodesAreIdempotent`（12 线程同时删同一批节点，无错误、结果正确） |
| 回滚演练 | `rollbackDrillRestoresEveryB17WriteWithDmlAndFileSnapshot`：四张表 + `bind.php` 快照 → 经各服务做一遍本批全部写入 → `DmlRestore` 只用 DML 恢复、`bind.php` 用快照覆盖 → diff 为空、文件逐字节相同 → 表结构指纹不变 |
| 结构不变 | IT 结束时表结构指纹与导入 install.sql 时相同，删库并确认不存在 |
| 护栏 | `WriteGuardTest`、`WriteRegistryTest`、`ReadOnlyGuardrailsTest`、`SchemaGuardTest`、`SqlEntryGuardTest`、`ModuleBoundaryTest`、`OutboundNetworkGuardTest`、`AdminTemplatePolicyTest`、`AdminTableSchemaTest` |
| 测试总数 | 见交付评论（`./mvnw verify` 与 `./mvnw verify -Pmysql-it` 在最终树上的结果） |

### 9. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B17-1 | `collect/test` 按采集地址发起 HTTP 请求，返回资源站的结果 | 不连接，固定返回 `{"code":1001,"msg":"新系统不连接资源站（在线采集按 E01 只做本地文件源），未进行连接测试"}`；表单脚本照旧显示 `msg` | 路线图 B17 行"本批不发起任何请求"，E01 只做本地版本 |
| D-B17-2 | 超长名称、非数字的类型等取值：MySQL 严格模式下整页 500；数组值被静默丢掉；`cj/info` 缺 `data` 时写入空行 | "参数错误"，不写（`collect-save-bad/*`、`cj-save-bad/*`、`cj-add/add-form`、`cj-import/nested`） | D-B13-3 的同一规则 |
| D-B17-3 | 删除的 `ids` 交给 MySQL 隐式转换（`abc` 当 0 什么都不删；`1abc` 当 1，删掉规则 1） | 每项必须是十进制数字，否则"参数错误"、不删 | D-B13-4 的同一规则 |
| D-B17-4 | 模板原样输出库里的文字（标签被浏览器解析、双引号截断属性值） | 按浏览器看到的文字转义输出 | D-B13-19 的同一规则 |
| D-B17-5 | `bind.php` 读改写没有锁，并发绑定可能丢更新；`mac_arr2file` 不检查写入结果；`config('bind')` include 任意 PHP | 进程内锁里读改写、原子写；读不回或被进程外改过时"保存失败"、文件不变；不执行 PHP | 正确性（同 D-B15-3、D-B15-5） |
| D-B17-6 | `cj/import` 没有文件时报错（500）；`validate` 的大小限制实际不生效；值是数组或 null 的列写库时报错（500） | 没有文件、超过 10,240,000 字节、值是数组或 null → "参数错误" | 健壮性 |
| D-B17-7 | `cj/export` 的文件名原样写进头部（含换行时 PHP `header()` 拒绝，整个头不发出） | 去掉控制字符后写出 | 健壮性 |
| D-B17-8 | `collect/union` 输出一个空的 `<script src="">` | 不输出 | 页面脚本策略（没有内容的脚本标签） |
| D-B17-9 | `bind` 的 `col`/`val` 或 `clearbind` 的 `cjflag` 是数组：PHP 8 报错（500）；`cj/info` 的 `data`、`customize_config` 是标量：PHP 8 报错（500），7.3 告警后照写 | "参数错误"，不写 | 健壮性 |
| — | `cj/info` 保存不校验表单令牌；导入失败也是 `success(import_err)`；导入不带令牌 | 相同 | 行为兼容 |

### 10. 回滚

- **代码**：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到 `4ad7b83` 的行为（这些动作恢复为"由 B17 批次提供"）。后台默认关闭（`maccms.admin.enabled=false`），可写数据源默认关闭；不涉及表结构、数据迁移。
- **数据**：只写 `mac_collect`、`mac_cj_node`（增删改）与删除节点时连带的 `mac_cj_content`、`mac_cj_history`，回滚演练证明可用 DML 从快照恢复（第 8 节）。开启后台写入前先备份这四张表（`mysqldump` 只取这四张表即可）。
- **文件**：只写 `application/extra/bind.php`；开启前复制一份，回滚时覆盖回去。写出的内容与 PHP 逐字节相同，切回 PHP 后照常读取。

### 11. 风险与遗留

| 项 | 说明 |
|---|---|
| 删除节点不是原子的 | MyISAM 没有事务，三步删除中途失败时已完成的步骤不回退（与旧版相同）；重复执行删除即可补齐 |
| 进程外修改 `bind.php` | 旧 PHP 仍在运行且同时绑定时，新版检测到版本变化报"保存失败"，刷新后重试即可；新版不能防止旧 PHP 覆盖新版刚写的内容 |
| 资源站密钥 | 新系统不读 `collect_appid`/`collect_appkey`；B18/B19 若需要它们，另行登记读取范围 |
| B18～B20 的入口 | 列表与表单上的"进入资源库""采集""测试规则"等链接指向 B18～B20 的动作，在这些批次交付前返回批次提示 |
