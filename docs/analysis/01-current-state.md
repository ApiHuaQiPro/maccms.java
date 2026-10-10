## 01 现状分析：MacCMS v10（生产备份 2026-07-11）

本文的所有结论都只依据仓库内代码，以及本地开发库 `maccms_dev` 上的实测。没有连接线上库，也没有读取仓库外的生产配置。

**证据标记**
- 【确认】：读代码确认，或在本地运行验证过。
- 【静态】：只读了代码，没有运行。
- 【待核实】：仓库里证据不足，需要用户或线上环境提供信息。

路径一律相对仓库根目录，`文件:行号` 指向当前提交里的代码。

---

### 1. 总览

| 项 | 现状 | 证据 |
|---|---|---|
| 版本 | 苹果CMS v10，`code` 为 `2023.1000.3051` | `application/extra/version.php` 【确认】 |
| 框架 | ThinkPHP 5.0.x 内核，放在 `thinkphp/` 目录；备份里 composer 的 `installed.json` 记录的是 `topthink/framework v5.0.14`，项目文档写的是 5.0.24 | `thinkphp/base.php`、备份内 `vendor/composer/installed.json` 【确认，具体小版本待核实】 |
| PHP 兼容性 | 193 个 PHP 文件在 7.2、7.3、8.0、8.2 上 `php -l` 全部通过（PHP 8.x 下有 2 条 Deprecated：`common/extend/sms/Qcloud.php:179`、`common/util/SinaUpload.php:105`）；后台在 7.3 和 8.0 上实测能运行 | 第 9 节 【确认】 |
| 数据库 | 只支持 MySQL，共 26 张表。其中 25 张是 MyISAM + utf8，只有 `mac_vod_search` 是 InnoDB + utf8mb4 | `application/install/sql/install.sql` 【确认】 |
| 代码规模（行数，含空行） | 后台控制器 8.5k；前台控制器 2.8k；API 1.2k；模型 10.7k（其中 `Collect.php` 2.5k）；公共函数 `application/common.php` 3.0k；后台视图 17k（layui）；第三方集成 `common/extend` 1.3k | `wc -l` 【确认】 |
| 入口 | `index.php` 前台、`api.php` 接口、`_admin.php` 后台、`install.php` 安装 | 第 2 节 【确认】 |

**仓库完整性问题（重要）**

这些问题会直接影响本地运行和评估的准确性。

1. **前台模板缺失**【确认】。`template/` 下只有 `template/default/html/index.html`，大小 0 字节。生产备份 zip 里也是这个状态。因此前台每个页面都报 `模板文件不存在`（见第 9 节）。线上实际用的模板（大概率是第三方或付费模板）**不在备份里**【待核实】。这一点直接决定了"模板能不能复用"以及前台的改造工作量。
2. **`vendor/` 没有进 git**【确认】。备份 zip 里有 `vendor/`，包括：
   - `topthink/think-captcha`、`think-image`、`think-helper`、`think-queue`、`think-installer`
   - `karsonzhang/fastadmin-addons 1.1.3`
   - `topthink/framework v5.0.14` 的元数据

   但 `vendor/.gitignore` 的内容是 `*`，导入仓库时这些文件被忽略了。直接克隆仓库后验证码类不存在，`/index.php/verify/index.html` 报 500。后台登录默认要求验证码（`application/extra/maccms.php:94` `admin_login_verify=1`），所以**纯 git 克隆的代码无法登录后台**。
3. **`extend/` 是空的**【确认】。备份里也只有 `.gitignore`。代码引用了下面这些类，生产环境是否存在要去线上确认【待核实】：
   - `login\ThinkOauth`（QQ/微信登录）
   - `phpmailer`
   - 七牛 SDK、又拍云 SDK

   它们对应的功能在本仓库里不可用。
4. **配置以 PHP 文件的形式存放，并且由后台回写**【确认】。主要有 `application/extra/*.php` 和 `application/route.php`，后台用 `mac_arr2file` 写入（例如 `admin/controller/System.php:229`）。仓库里的 `application/extra/maccms.php` 已经清空了密钥，但其余值（伪静态规则、`view`/`path` 设置等）是不是线上的当前值，需要核实【待核实】。

---

### 2. 模块与入口

| 入口 | 绑定模块 | 说明 |
|---|---|---|
| `index.php` | `index`（`index.php:23`） | 没有 `install.lock` 时跳转到安装页；会把 GBK 编码的 PATH_INFO 转成 UTF-8 |
| `api.php` | `api` | 包含资源站接口 Provide、入库接口 Receive、定时任务 Timming、微信 Wechat |
| `_admin.php` | 不绑定模块，`ENTRANCE=admin` | 文件名如果是 `admin.php` 会拒绝运行（`_admin.php:36-39`），用来强制修改后台地址；`common/behavior/Begin.php:19-29` 限制这个入口只能访问 admin 模块 |
| `install.php` | `install` | 导入 `install.sql` 和 `initdata.sql`，创建管理员，生成 `install.lock` |

**应用模块**
- `application/index`（前台）：23 个控制器。
  - 内容：Vod、Art、Actor、Role、Topic、Plot、Website、Type
  - 会员：User
  - 互动：Comment、Gbook
  - 工具：Ajax、Label、Map、Rss、Search、Verify、Qrcode
  - 支付回调：Payment
- `application/api`：6 个控制器，详见第 3.2 节。
- `application/admin`：39 个控制器，详见第 3.3 节。
- `application/common`：
  - `model/`：30 个模型
  - `taglib/`：模板标签
  - `extend/`：支付、短信、邮件、存储、推送、编辑器适配
  - `util/`：Pinyin、Ftp、Qrcode、PclZip 等
  - `behavior/`：Init、Begin
- `application/install`：安装程序。

**钩子与队列**【确认】
- `application/tags.php` 只挂了两个行为：`app_init → Init`（加载全局配置、选模板目录、设缓存）和 `app_begin → Begin`。
- `extra/queue.php` 配置的是 `connector=Sync`，没有真正的队列。
- `command.php` 是空的，没有命令行任务。

---

### 3. 关键路由与接口

#### 3.1 URL 生成与伪静态（直接影响 SEO 兼容）

- **ThinkPHP 层**（`application/config.php`）
  - `var_pathinfo='s'`（:80）
  - `url_html_suffix='html|htm|shtm|shtml|xml'`（:86）
  - `url_route_on` 运行时被 `maccms.rewrite.route_status` 覆盖（`common/behavior/Init.php:81`）
  - `pathinfo_depr` 被 `maccms.app.pathinfo_depr` 覆盖，当前值是 `/`（`Init.php:82-85`）
- **路由表**：`application/route.php`（471 行）由后台 `System::configurl` 根据 `rewrite.route` 文本生成（`admin/controller/System.php:164-232`）。示例：
  - `voddetail/<id>` → `vod/detail`（`route.php:281`）
  - `vodplay/<id>-<sid>-<nid>` → `vod/play`（`:301`）
  - `vodtype/<id>-<page?>`（`:261`）
  - `vodshow/<id>-<area?>-…`（`:321`）
- **统一生成函数** `mac_url()`（`application/common.php:1732-2427`）。它在以下配置之间切换：
  - `view.*` 各页面开关（`maccms.php:455-471`）：`0` 为动态，`2` 为静态路径；播放页和下载页另有 `3`、`4`
  - `path.*` 静态路径模板（`:473-484`）
  - `rewrite.status`：是否隐藏 `index.php`
  - `rewrite.suffix_hide`：`.html` 是否换成 `/`
  - `rewrite.vod_id` 等 ID 形式：`0` 数字，`1` 拼音，`2` 用 `mac_alphaID` 加密（`common.php:1681`）
  - `rewrite.encode_len`

  按模型封装的函数有 `mac_url_vod_detail`、`mac_url_vod_play`、`mac_url_type`、`mac_url_art_detail` 等（`common.php:2462-2620`）。分页用 `mac_url_page` 替换 `PAGELINK`。
- **结论**：旧站的 URL 形态由"配置值 + 生成函数"共同决定。只看路由表无法还原，必须拿到**线上当前的 `maccms.php`（rewrite/view/path）和 `route.php`**【待核实】。

#### 3.2 API（`application/api/controller`）

| 接口 | 作用 | 鉴权 | 证据 |
|---|---|---|---|
| `Provide::vod/art/actor/role/website` | 资源站输出，供其他站采集。参数 `ac=list/videolist/detail`、`t`、`ids`、`wd`、`h`、`pg`、`pagesize`（最大 100）、`at=xml`；JSON 或 RSS 5.1 风格的 XML；按 `cachetime` 缓存 | `api.{type}.status!=1` 时返回 `closed`（`Provide.php:23` 等）；`charge=1` 时启用 IP/域名白名单（`:785-804`） | 【确认】本地返回 `closed`，与默认配置一致 |
| `Receive::vod/art/…/comment` | 外部推送入库，调用 `model('Collect')->*_data` | 要求 `interface.status==1`，且 `pass` 和配置一致、长度不少于 16（`Receive.php:15-26`） | 【静态】 |
| `Timming::index` | 定时任务触发（采集、生成、清缓存、URL 推送），由外部 cron 访问 | **无鉴权**，`admin/controller/Base.php:22` 对它放行；带 `enforce=1` 可强制执行（`Timming.php:37`） | 【确认，代码】 |
| `Wechat::index` | 微信公众号消息 | `weixin.status` | 【静态】 |

**格式兼容的重要性**：`Provide` 的 JSON/XML 格式和 `Collect` 能消费的格式，事实上是苹果CMS 资源站生态的通用协议。新系统做消费端（采集）和生产端（对外提供）时，都必须按这个协议兼容。

#### 3.3 后台（`application/admin/controller`）

| 分组 | 控制器 |
|---|---|
| 内容 | Vod（含 `iplot` 分集剧情）、Art、Website、Actor、Role、Topic、Type（含 `extend`/`move`）、Comment、Gbook、Link |
| 播放与下载 | Vodplayer（含导入/导出）、Voddowner、Vodserver |
| 采集 | Collect（资源站 API 采集）、Cj（自定义规则采集，配合 `common/util/Collection.php`） |
| 生成与推送 | Make（静态生成）、Urlsend（百度推送） |
| 系统 | System（`config*`：站点、URL、用户、上传、评论、微信、支付、第三方登录、邮件、短信、API、入库接口、采集、播放、SEO）、Timming、Domain（站群）、Template（含广告）、Addon、Database（备份/还原/优化/修复/批量替换）、Annex、Images、Upload |
| 会员与财务 | User、Group、Card（卡密）、Order、Cash（提现）、Plog（积分日志）、Ulog（行为日志）、Visit（推广访问） |
| 管理员 | Admin、Index（登录、首页、清缓存、锁屏） |
| 空壳 | Safety、Update（`Update.php` 为空，但 `welcome.html:45-46` 仍然链接到 `update/step2`） |

**后台权限**【确认】
- 登录写入 session `admin_auth` 和 `admin_info`（`common/model/Admin.php:109-153`）。
- `check_auth`（`admin/controller/Base.php:42-58`）的规则：
  - `admin_id==1` 的超级管理员直接放行
  - 其他管理员要求 `admin_auth` 文本里包含 `,控制器/方法,`（小写）
- 菜单和权限的定义在 `application/admin/common/auth.php`。

---

### 4. 数据库结构（只有仓库证据）

**来源与限制**
- 来源是 `application/install/sql/install.sql`（26 张表）和 `initdata.sql`（18 条 `mac_type` 记录）。
- 线上数据量、实际字段（有没有后加的字段或索引）、字符集都**无法从仓库验证**【待核实】。
- 仓库里没有迁移机制：`Update` 是空类，`application/data/update/` 只有 `index.html`，库结构版本只能参考 `version.php`。

**表前缀与类型**
- 表前缀 `mac_`（`application/database.php:31`）。安装时用 `mac_parse_sql` 替换前缀（`install/controller/Index.php:225`）。
- 时间字段全部是 `int unsigned` 的 Unix 时间戳。
- IP 用 `ip2long` 存成 `int unsigned`（`common.php:990` `mac_get_ip_long`）。

| 业务域 | 表 |
|---|---|
| 内容 | `mac_vod`（视频）、`mac_type`（分类树，`type_mid` 1=视频 2=文章）、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_link` |
| 互动 | `mac_comment`、`mac_gbook` |
| 会员与财务 | `mac_user`、`mac_group`、`mac_order`、`mac_card`、`mac_cash`、`mac_plog`、`mac_ulog`、`mac_visit`、`mac_msg` |
| 采集 | `mac_collect`（资源站）、`mac_cj_node`、`mac_cj_content`、`mac_cj_history` |
| 系统 | `mac_admin`、`mac_annex`、`mac_vod_search`（搜索结果缓存） |

**关键编码约定**【确认】（迁移时必须逐字节兼容）

- **播放地址**
  - 存在四个字段里：`vod_play_from`、`vod_play_server`、`vod_play_note`（varchar 255）和 `vod_play_url`（mediumtext）（`install.sql:757-760`）。
  - 多个播放组之间用 `$$$` 连接，四个字段按下标一一对应（`common/model/Vod.php:677-680`）。
  - 一个播放组内，集与集之间用 `#` 分隔，每集是 `标题$地址[$来源]`。保存时换行会被替换成 `#`（`Vod.php:681`）。
  - 解析函数：
    - `mac_play_list()`（`common.php:1346-1407`）：过滤掉 `vodplayer` 里未启用的播放器，并按 `sort` 排序。
    - `mac_play_list_one()`（`common.php:1434-1455`）：没有标题时用"第N集"，并在地址前拼接 server 前缀。
  - 下载地址 `vod_down_*` 的格式相同。
- **其他多值字段**
  - 分集剧情 `vod_plot_name/detail` 和文章分页 `art_title/note/content` 也用 `$$$` 分隔（`common.php:1294-1342`）。
  - 截图字段用 `#` 和 `$` 分隔。
- **JSON 字段**
  - 分类扩展 `type_extend` 是 JSON，键有 class、area、lang、year、star、director、state、version（`common/model/Type.php:52,222-241`）。
  - `topic_extend` 同样是 JSON。
- **会员组权限**
  - `group_type` 是首尾带逗号的分类 ID 列表，例如 `,1,6,`。
  - `group_popedom` 是 JSON，结构为 `{type_id:{"1":"1",...}}`，权限位 1 列表、2 详情、3 播放、4 下载、5 试看（`admin/view/group/info.html:55-60`）。
  - 校验在 `User::popedom`（`common/model/User.php:528-537`）。
- **密码**：会员和管理员都是**不加盐的 `md5(明文)`**。管理员见 `Admin.php:65,76,124`，会员见 `User.php:77,87,180`。

**移植时要注意的 MySQL 专有写法**【静态】
- 大量 ThinkPHP 数组条件，以及 `_string` 原生 SQL 片段（`Vod.php:24-48,458,520-526`）。
- `orderRaw('FIND_IN_SET…')`（`Actor.php:47,320`，`Website.php:47,409`）。
- 原生 `CONCAT` 更新（`VodSearch.php:121-125`）。
- 查重时用 MyISAM 临时表（`admin/controller/Vod.php:103-105`）。
- 后台数据库工具使用 `SHOW TABLE STATUS`、`OPTIMIZE`、`REPAIR`（`admin/controller/Database.php`）。
- 搜索全部是 `LIKE '%x%'`，没有全文索引。

---

### 5. 模板与标签

**引擎**【确认】
- 模板引擎是 ThinkPHP 自带的 Think 引擎，定界符 `{ }`（`config.php:124-143`）。
- `taglib_pre_load = app\common\taglib\Maccms` 让 `{maccms:*}` 标签全局可用。

**标签**
- 列表类标签共 10 个：`vod`、`art`、`type`、`topic`、`actor`、`role`、`website`、`link`、`comment`、`gbook`。每个标签都调用对应模型的 `listCacheData`，例如 vod 在 `common/taglib/Maccms.php:611`。
- 7 个扩展字典标签：`area`、`lang`、`year`、`class`、`version`、`state`、`letter`。
- 另有 `foreach` 和 `for`。
- 支持 `paging="yes"`，会生成 `$__PAGING__`。

**目录选择**【确认】
- 模板目录是 `template/{template_dir}/{html_dir}/`（`common/behavior/Init.php:71`）。
- 手机端在 `mob_status` 开启时切换到 `mob_template_dir`。
- 控制器用 `mac_tpl_fetch` 拼出模板路径，例如 `vod/detail`、`vod/play`、`public/jump`。

**与改造的关系**
- 第三方苹果CMS 模板的写法是"TP 模板语法 + `{maccms:*}` 标签 + 直接调用 PHP 函数"。在模板里直接调用的函数有 `mac_url_*`、`mac_substring`、`mac_default` 等【静态，按通用写法推断，线上模板缺失，无法逐个核实】。
- Go 或 Java 的模板引擎都不能直接运行这类模板（见 02 文档）。

**静态生成**【静态】
- 由 `admin/controller/Make.php` 实现：`buildHtml()` 渲染后用 `file_put_contents` 写盘（`:29-44`）。
- 分批靠 meta-refresh 逐页跳转（`:690-720`）。
- 当前所有 `view.*` 都是 `0`，也就是全站动态，没有启用静态生成【确认，仅限仓库内的配置值】。

---

### 6. 插件（addons）

- **机制**：使用 FastAdmin 的 `karsonzhang/fastadmin-addons`（`think\Addons`）。仓库里缺这个依赖，备份里有。
- **配置**：`application/extra/addons.php` 中 `autoload=false`，hooks 为空。
- **现有插件**：只有 `addons/adminloginbg`（后台登录背景图），它的 `info.ini` 里 `state = 0`，即**未启用**【确认】。
- **钩子点**：代码里只有 `Hook::listen("admin_login_init")` 一处（`admin/controller/Index.php:22`）。
- **结论**：仓库层面"第三方插件"的依赖面很小。线上是否额外装过插件，或改过核心代码，需要核实【待核实】。

---

### 7. 采集

- **资源站采集**：入口是 `admin/controller/Collect.php`，按 `mid` 分到 vod、art、actor、role、website（`:140-170`）；核心逻辑在 `common/model/Collect.php`。
  - **格式**：`type=1` 为 XML，`type=2` 为 JSON，其他值先试 JSON 再试 XML（`Collect.php:85-103`）。XML 禁用了外部实体（`:175`）。
  - **请求**：用 `mac_curl_get`（`common.php:607-632`）。连接超时 15 秒、总超时 30 秒，跟随跳转，`SSL_VERIFYPEER=0`，UA 写死，没有重试。
  - **分类绑定**：用 `md5(采集URL)_远程分类ID` 映射到本地 `type_id`。绑定关系**存在文件 `application/extra/bind.php` 里，不在数据库**（`Collect.php:211-217`）。
  - **合并规则**：`inrule` 决定查重，`uprule` a～w 决定更新哪些字段。播放组按 `$$$` 追加或替换，`urlrole==1` 时按 `#` 合并去重（`Collect.php:748-971`）。
  - **并发**：严格串行。后台通过 meta-refresh 一页一页翻（间隔为 `collect_timespan`）；API 入口下，在同一个请求里递归翻页（`Collect.php:1003-1015`）。
  - **SSRF 防护**：只屏蔽了 `127.0.0.1` 和 `localhost`（`Collect.php:2533-2540`），防护较弱。
- **自定义规则采集**：`admin/controller/Cj.php` 加 `mac_cj_*` 三张表。
- **定时采集**：外部 cron 调用 `api.php/timming/index`，由它读取 `extra/timming.php`。
- **预置资源站**：`install.sql` 和 `initdata.sql` 里没有 `mac_collect` 数据；`extra/timming.php` 里有一个停用的示例任务。线上配置了哪些资源站、数量多少【待核实】。

---

### 8. 播放器与外部依赖

**播放器**【确认】
- 配置文件：
  - `application/extra/vodplayer.php`：7 个播放器，dplayer、videojs、iva、iframe、link、swf、flv
  - `vodserver.php`、`voddowner.php`
- 静态资源：`static/player/*.js`，以及 DPlayer、hls、flv、videojs 等库。
- 播放页的生成过程：
  1. `common/controller/All.php` 的 `label_vod_play` 组装 `player_aaaa` 变量，包含 url、next、from、server、encrypt 等。
  2. 页面加载 `static/js/playerconfig.js` 和 `static/js/player.js`。
  3. `player.js` 按 `from` 加载 `static/player/{from}.js`；如果需要解析（`ps=1`），走 `parse.js` 的 iframe。
- 地址加密：`app.encrypt` 取 0、1、2，分别是不加密、`escape`、`base64`（`All.php:533-540`）。
- **改造含义**：前端播放器资源可以原样复用。新后端只需要输出相同结构的 `player_aaaa` 和 `MacPlayerConfig`，并且 `playerconfig.js` 改由新后台生成。

**外部服务**【静态】，集成代码在 `application/common/extend/`，基本都是直接发 HTTP 请求，没有用 SDK：
- 支付：Alipay（MD5 签名）、Weixin、Codepay、Zhapay；配置在 `maccms.php:210-244`。回调入口是前台的 `Payment::notify`。
- 短信：阿里云、腾讯云。
- 邮件：PHPMailer，**依赖缺失**。
- 存储：local、ftp、七牛（SDK 缺失）、又拍（SDK 缺失）、阿里、Uomg、微博图床。
- 推送：百度 / 百度快速收录。
- 第三方登录：QQ、微信，依赖的 `extend/login` 缺失。
- 编辑器：配置的是 UEditor，静态资源只有 `static/ueditor`。
- 线上实际启用了哪些服务【待核实】。

---

### 9. 本地验证记录（2026-10-10）

| 项 | 方式 | 结果 |
|---|---|---|
| 语法 | 193 个 PHP 文件分别用 PHP 7.2、7.3、8.0、8.2 执行 `php -l` | 全部通过 【已运行】 |
| 建库 | 新建 `maccms_dev`（utf8mb4），导入 `install.sql` 和 `initdata.sql` | 26 张表，`mac_type` 18 行 【已运行】 |
| 前台首页、分类页、会员登录页 | `php -S`，分别用 7.3 和 8.0 | 500：`模板文件不存在:index\index.html`、`vod\type.html`、`user\login.html` 【已运行，失败原因：模板缺失】 |
| 后台 18 个页面（首页、视频、分类、采集、会员、会员组、管理员、系统配置、播放器、插件、数据库、模板、定时任务、文章等） | 用本地脚本向 PHP session 注入管理员身份，绕过验证码 | 在恢复 `vendor` 之后，7.3 和 8.0 上都返回 200。`admin/make/index` 因为前台模板缺失报错 【已运行】 |
| 没有 `vendor/` 时 | 临时移走 `vendor` | 验证码报 500，`Class think\captcha\Captcha not found`；后台页面本身能渲染 【已运行】 |
| 资源站接口 | `GET /api.php/provide/vod/?ac=list` | 返回 `closed`（默认关闭） 【已运行】 |
| 会员登录安全 | POST `/index.php/user/login.html`，用本地测试账号 | 见第 10 节第 1、2 条 【已运行】 |
| 采集、支付、短信、邮件、静态生成、前台模板标签 | — | 没有外部资源站，没有模板，没有密钥，**未验证** |

---

### 10. 主要风险（按严重度）

1. **会员登录可以直接用密码哈希登录**【确认，本地实测】。`User::login` 的条件是 `user_pwd = md5(输入) OR user_pwd = 输入`（`common/model/User.php:346`）。只要拿到数据库里的 md5 值，就能直接登录。
2. **会员登录可以只凭 openid 登录**【确认，本地实测】。前台 `User::login` 把 `input()` 原样传给模型（`index/controller/User.php:183-184`），模型在收到 `openid` 加 `col=user_openid_qq|weixin` 时，不做任何 OAuth 校验就按 openid 登录（`User.php:331-354`）。只要知道某个会员绑定的 openid，就能接管这个账号。线上是否开启了会员功能、有没有绑定数据【待核实】。
3. **定时任务接口无鉴权**【确认，代码】。`api.php/timming/index?enforce=1` 任何人都能触发采集、生成、清缓存。
4. **密码是不加盐的 md5，cookie 签名也只是 md5**【确认】。会员的 `user_check` 等于 `md5(user_random-用户名-ID-)`（`User.php:389,446`），cookie 没有 httponly（`config.php:232`）。
5. **客户端 IP 可以伪造**【静态】。`mac_get_client_ip` 优先读取 `CF-Connecting-IP`、`Client-IP`、`X-Real-IP` 等请求头（`common.php:950-`），资源站接口的 IP 白名单因此可以被绕过。
6. **采集的 SSRF 防护较弱，且不校验 TLS 证书**【静态】。
7. **配置写在 PHP 文件里，由后台回写**。后台的写文件能力加上模板编辑功能，权限面很大；多实例部署时这些文件无法同步。
8. **仓库不完整**（模板、`vendor`、`extend` 缺失，见第 1 节）。现在的评估只能覆盖后端逻辑，前台的工作量没法精确估算。
9. **MyISAM 没有事务**。积分、订单、卡密等资金类操作不具备原子性（例如 `Card.php:142` 的 `setInc` 分步执行）。
10. **没有自动化测试**。仓库里只有框架自带的 `thinkphp/phpunit.xml`，应用层没有任何测试。

> 第 1～3 条是现网系统本身的安全问题，与是否重构无关。建议另开任务，在 PHP 现网先修复，但需要用户确认。本任务不修改业务代码。
