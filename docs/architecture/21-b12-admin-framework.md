## B12 后台框架：登录、会话、`admin_auth` 鉴权与管理员管理（路线图 B12，MACC-36）

> **状态：实施完成，待独立验收。** 依据：路线图 `09-roadmap.md` 第 5.3 节 B12 行、第 5.5 节 D2 ⑤；03 草稿第 4.3 (7) 节（管理员密码的字节语义）；B07 `12-write-infrastructure.md`（写 SQL 规范、并发与回滚演练）；B08 `17-b08-member-session-captcha.md`（进程内会话、本地验证码）；B05 `15-b05-readonly-pages.md` D-B05-8（手机模板与语言包归 B12）；MACC-36 任务说明中的用户决定"后台不做新视觉设计，复用旧后台 HTML 结构与静态资产"。旧代码位置一律相对仓库根目录。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | 平台基线 `a709915`（树与已验收 main `6a27970`，即 B11 验收后的路线图更新，相同） |
| 分支 | `agent/claude/macc-36`（本任务工作区；只用普通提交，不 reset、rebase、amend、force，不自行合并 main） |
| 提交 | 见交付评论（最终 SHA 以评论为准） |

### 1. 范围

| 在本批 | 不在本批（归属） |
|---|---|
| 后台入口（可配置的 `maccms.admin.entry`，旧版"改名后的 _admin.php"）与 ThinkPHP 地址写法、模块跳转、404 | 内容、会员与财务、系统配置等后台页面（B13～B20、B28、B29，第 3 节逐项登记） |
| 登录（`md5(原值)`、验证码、登录记录）、登录页验证码、退出、锁屏解锁 | 快捷菜单的**保存**（写 `extra/quickmenu.php`，写入方矩阵归 **B15**；本批只显示与编辑页） |
| 进程内后台会话；**D2 ⑤**：每个请求按会话里的 `admin_id` 重读 `mac_admin` | 公共下拉选择框 `index/select`（内容批量操作的弹窗，**B13**）、文件上传 `upload/upload`（**B13**） |
| `admin_auth` 文本鉴权（`check_auth`），auth.php 的菜单与全部权限码一次性落地 | 外部回链检测 `index/check_back_link`（E11，**B28**） |
| 框架页（菜单、快捷菜单）、欢迎页、快捷菜单页、布局 cookie、`checkcache`、清理缓存 | 站点页面的繁体输出与手机标志接线（与前端协调后实施，第 12 节） |
| 管理员列表、表单（新增/编辑、权限树、表单令牌）、删除、状态 | 安装向导创建第一个管理员（**B22**，复用本批的写入语义） |
| 旧语言包 zh-cn/zh-tw 的资源与选择（后台页面按 `app.lang` 输出简体或繁体） | |
| 承接 B05 D-B05-8：手机模板/站群选择语义与语言选择的契约、实现与黄金（第 12 节） | |

### 2. 模块与依赖

新模块 `admin`（`backend/src/main/java/com/maccms/backend/admin/`）：

| 包 | 内容 |
|---|---|
| `admin.config` | `AdminProperties`（`maccms.admin.*`）、`ConditionalOnAdminEnabled`（默认关闭，关闭时一个后台组件都不注册）、`AdminConfiguration`（装配，启动时读旧配置） |
| `admin.web` | `AdminController`（入口与全部 B12 动作）、`AdminRoute`（路径解析）、`AdminParams`（按字节解析入参）、`AdminSessions`（会话 cookie）、`AdminPages`（页面视图模型）、`AdminResponses`（JSON/跳转页/重定向）、`AdminHtmlRenderer`、`AdminExceptionHandler`、`AdminEndpoint`；`view/` 为模板参数 |
| `admin.application` | `AdminLoginService`、`AdminAccountService`、`AdminAuthorization`、`AdminPermissions`、`AdminMenus`、`LegacyAdminUrl`、`LegacyInput`、`AdminSettings`；`view/` 为不可变视图 |
| `admin.infrastructure` | 只读 `AdminMapper`（`mapper/admin/AdminMapper.xml`），写 `AdminWriteMapper`（`mapper-write/admin/AdminWriteMapper.xml`，只能经 `WriteDatabase.mapper()`） |

- 依赖：`common`（会话、验证码、cookie、`LegacyJson`、客户端 IP、语言包）、`legacyconfig.application`（旧配置）、`content.application`（`PhpIntval`、`ContentCacheMaintenance`）。**不引用 site**；application/infrastructure 不引用本模块 web/config；web 不直接用 Mapper。`ModuleBoundaryTest.adminRulesOnSyntheticSources` 锁定。
- 新增公共类：`common.lang.LegacyLang`（第 6 节）；`common.session.SessionStore.invalidate`（作废单个会话）。
- 后台模板在单独的 `backend/src/main/jte-admin/`，生成到 `com.maccms.backend.admin.web.jte`（pom 中 jte 插件的第二个 execution），与站点模板分开编译与检查。

### 3. 菜单与权限码（auth.php 一次性落地）

`AdminPermissions` 逐项照搬 `application/admin/common/auth.php`：12 个分组、179 个不重复的权限码（含隐藏条目与 `make/index?ac2=wap`），键、顺序、`show`、名称（`lang('menu/…')` 的键或写死的 `--…`）、控制器/动作、`param` 与旧文件相同（`AdminPermissionsGoldenTest` 与 PHP 导出的 `admin/auth-php.json` 逐项比对，auth.php 的 SHA-256 锁定）。另登记旧控制器里有、auth.php 没列的公开动作（如 `index/checkcache`、`vod/iplot`），同样按旧版的登录与权限检查处理。

**每个权限码的去向**（`AdminPermissions.batchOf`；路由一律存在：登录检查 → 权限检查 → 本批实现或"由后续批次实现"的提示）：

| 归属 | 数量 | 权限码 |
|---|---|---|
| **B12（本批实现）** | 9 | `index/welcome` `index/quickmenu`（保存归 B15） `index/iframe` `index/clear` `index/unlocked` `admin/index` `admin/info` `admin/del` `admin/field`；另有不在 auth.php 的 `index/index`、`index/login`、`index/logout`、`index/checkcache` |
| B13 | 54 | `index/select` `upload/upload`，`type/*` `topic/*` `link/*` `gbook/*` `comment/*` `annex/*` `images/*` `art/*` `vod/*` `actor/*` `role/*` `website/*` |
| B14 | 20 | `group/*` `user/*` `card/*` `order/*` `ulog/*` `plog/*` `cash/*` |
| B15 | 52 | `system/*` `domain/*` `vodserver/*` `vodplayer/*` `voddowner/*` `template/*` `database/*`（其中还原、执行 SQL、优化、修复按零结构变更在入口拒绝）`addon/index|uninstall|config|state|local|add` `urlsend/index` |
| B16 | 15 | `timming/*` `make/*`（含 `make/index?ac2=wap`） |
| B17 / B18 / B19 / B20 | 10 / 2 / 3 / 4 | 采集：`collect/union|bind|index|info|del`、`cj/index|info|del|export|import`；`collect/api|load`；`collect/vod|art|timing`；`cj/program|col_url|col_content|publish` |
| B28 / B29 | 1 / 3 | `urlsend/push`；`addon/downloaded|install|upgrade` |
| 旧版无方法（死链） | 6 | `topic/batch` `link/field` `safety/file` `safety/data` `urlsend/baidu_push` `urlsend/baidu_bear`：菜单或权限树里有，旧控制器里没有方法，新旧都是"页面不存在" |

后续批次接手时只在 `AdminController` 的分派里接上自己的动作，不改 `AdminPermissions` 的菜单数据（路线图第 5.2 节"同文件冲突"）。

### 4. 行为

#### 4.1 地址、入参与响应（旧 `_admin.php` + ThinkPHP 5.0 + `admin/controller/Base`）

- **地址**：`<entry>/<模块>/<控制器>/<动作>[.html|.htm|.shtm|.shtml|.xml][/键/值…]`。路径按 PATH_INFO 解码一次；末尾后缀不分大小写去掉；空段取默认值（控制器 `Index`、动作 `index`）；控制器与动作转小写（`url_convert`）；其余各段按 `(\w+)\|([^\|]+)` 成对取作参数（值 `strip_tags`）。模块必须原样等于 `admin`，否则（含入口本身、`/ADMIN/…`、`/index/…`）302 到 `admin/index/index.html`（旧 `common/behavior/Begin.php`）。
- **处理顺序**（与旧版相同）：控制器不存在 → 404（在登录检查之前）；`index/login`（与新版的 `index/verify`）不要求登录；其余先要求登录（302 到 `admin/index/login.html`），再除 `update` 控制器外做 `check_auth`（不通过：Ajax 为 `{"code":0,"msg":"您没有权限访问此页面",…}`，否则为跳转提示页），最后分派；动作不存在 → 404。
- **入参**：按旧 PHP 的规则**按字节**解析查询串与 urlencoded 正文（`AdminParams`），保留 `名[]` 数组；`input()` 的合并为 GET 请求"GET + 路径参数"、POST 请求"GET + POST + 路径参数"（后者覆盖前者，键保留首次出现的位置）；**每个值都经 ThinkPHP `filterExp`**：整值匹配 `EXP|NEQ|GT|…|LIKE|NOT LIKE|IN|NOT IN|…`（不分大小写，`$` 也匹配末尾换行之前）时末尾加一个空格。所以旧版密码 `like` 实际存的是 `md5("like ")`，新版照样（黄金 `login-pwd-like-filterexp` 等 7 个用例）。各动作取哪一路参数与旧版相同：登录与表单保存只取 POST（`input('post.')`），`iframe` 只取 POST 的 `val`，其余用合并后的参数。
- **Ajax 判定**：`X-Requested-With: XMLHttpRequest`（不分大小写），或参数 `_ajax` 为 PHP 真值。
- **响应**（旧 `Jump::success/error`）：Ajax 为 `{"code":1|0,"msg":…,"data":"","url":…,"wait":3}`，`application/json; charset=utf-8`，编码同 PHP `json_encode(…, JSON_UNESCAPED_UNICODE)`（`LegacyJson`）；成功的 `url` 为 Referer（只接受 http/https 绝对地址或以 `/` 开头的站内路径），没有时为当前动作的 `url()`；失败的 `url` 为空串。非 Ajax 为旧 ThinkPHP `dispatch_jump.tpl` 结构的跳转提示页（文字写死简体，与旧版相同）。Ajax 请求页面时返回整页 HTML 的 JSON 字符串（旧版控制器返回字符串、Ajax 的默认输出类型为 json；B13 的 `j-select` 弹窗依赖这一点）。
- **站内地址**：页面里的地址按旧 `url()`（`LegacyAdminUrl`：`入口/模块/控制器/动作` + `/键/urlencode(值)` + `.html`，空值跳过，控制器 `parseName`、动作小写），黄金页面里出现的地址逐个比对。

#### 4.2 登录、验证码与会话

- **登录**（`common/model/Admin.php:114-152`）：① `admin_name` 或 `admin_pwd` 为 PHP `empty()`（缺失、空串、`"0"`；数组形式按缺失）→ "参数错误"，不碰验证码；② `app.admin_login_verify != '0'`（宽松比较：`'0'`、`0`、`'00'`、`false` 不要验证码，空串、缺失要）时验证码不通过 → "验证码错误"；③ 按 `admin_name`（MySQL 比较规则：不分大小写、忽略尾部空格）、`admin_pwd = md5(原值)`（不 trim、不 urldecode、不 htmlspecialchars，按字节；03 草稿 4.3 (7)）、`admin_status = 1` 查一行（按 `admin_id` 取第一行），没有 → "账号或密码错误"；库里的 md5 值本身不能当密码；④ 写登录记录（第 5 节），没写进（读与写之间被停用或改密）→ "更新登录信息失败"；⑤ 成功："登录成功"。
- **验证码**：登录页的图片是后台自己的 `<entry>/admin/index/verify.html`（新地址，D-B12-4），参数取旧 `extra/captcha.php`，生成与校验同 B08（`common.captcha`；猜错即作废，D-B08-4）。
- **会话**（`AdminSessions`，D-B12-2）：进程内，独立于站点会话；cookie `mac_admin_sid`（随机 128 位），`Path` 为对外的后台入口路径，`HttpOnly`、`SameSite=Strict`，对外地址为 https 时 `Secure`；空闲 24 分钟失效（旧 PHP 会话默认 `gc_maxlifetime=1440`；框架页每 30 秒请求 `checkcache`，打开时不会过期）；最多 1 万个；**登录成功时换新标识**，登录前的会话（含验证码）作废；退出时作废并删除 cookie。会话里只存 `admin_id`、登录时比较过的密码哈希、表单令牌、验证码。重启即全部失效。
- **来源校验**（D-B12-3）：POST 请求带 `Origin`（没有时看 `Referer`）且与 `maccms.admin.base-url` 不同源 → 403，不执行任何操作；两者都没有时放行（非浏览器客户端）。

#### 4.3 D2 ⑤：每个请求重读当前行

每个要求登录的请求都按会话里的 `admin_id` 读 `mac_admin` 当前行（主键查询）：行不存在、`admin_status != 1`、或 `admin_pwd` 与登录时比较的值不同 → 作废会话、删除 cookie、302 到登录页；否则权限按**当前**的 `admin_auth` 判定。验收用例（黄金 `legacy-snapshot` 场景复放 + `AdminMvcTest`）：

| 登录后在库中 | 旧版（会话快照，黄金） | 新版 |
|---|---|---|
| 收回权限（`admin_auth=''`） | 仍按旧权限放行 | 下一个请求即"您没有权限访问此页面" |
| 授予权限（`,admin/info,`） | 仍拒绝（要重新登录） | 下一个请求即放行 |
| 改密码 | 照常使用；旧密码仍能解锁锁屏 | 下一个请求 302 到登录页（会话已作废） |
| 停用 / 删除 | 照常使用 | 下一个请求 302 到登录页 |
| 自己把自己停用（`admin/field`） | 照常使用 | 设置成功，下一个请求 302 到登录页 |

#### 4.4 管理员管理（`admin/controller/Admin.php` → `common/model/Admin.php`）

- **列表** `admin/index`：`wd` 非 `empty()` 时按 `admin_name LIKE '%' . htmlspecialchars(urldecode(wd)) . '%'` 筛选（`%`、`_` 照旧是通配符，与 B11 的视频搜索缓存相同的转写），`admin_id` 倒序；`page`、`limit` 按 `intval`（小于 1 时为 1 与 `app.pagesize`），`limit` 最多 1000（D-B12-11）；页面结构、开关、编辑/删除链接、"当前账号不显示复选框与删除"、`mac_day(…,'color')`（今天的时间标红）、`long2ip`、分页地址（`url('admin/index', $param)`，`page/limit` 为占位）与旧版相同（黄金 `admin-list` 12 个请求逐项比对）。
- **表单** `admin/info`（GET）：按 `intval(id)` 读一行，没有时是空表单（"禁用"默认选中，与旧版相同）；密码框总为空；权限树为 auth.php 全部条目，勾选按旧版**子串**判断（`strpos(','.admin_auth, 控制器/动作) > 0`，所以 `,make/index?ac2=wap,` 也会勾上 `make/index`），条目键 11（欢迎页面）强制勾选并只读，组的勾选为"组内每项都命中"；每次打开都签发新的表单令牌（覆盖会话里的旧令牌）。
- **保存** `admin/info`（POST）：先给 `admin_auth[]` 补上 `index/welcome`，再校验令牌（缺失 → "非法请求"；不一致或已用过 → "请不要重复提交表单"，令牌随之作废），再保存：名称必填（`require`：`'0'` 也算有）→ "参数错误：名称必须"；`admin_id` 为 `empty()` 时新增（密码 `md5(原值)`，缺失按空串），否则编辑（密码 `empty()` 时不改）；权限写成 `','.join(',', 各项).','`；只写名称、密码、状态、权限四列（D-B12-9）。
- **删除** `admin/del`：`ids` 为逗号串或 `ids[]`；为空 → "参数错误"；含当前管理员 → "禁止删除当前登录账号"；每项必须是十进制数字（D-B12-10）；成功"删除成功"（不存在的 id 也是成功，与旧版相同）。
- **状态** `admin/field`：`ids` 非空、`col` 为 `admin_status`、`val` 宽松等于 `'0'` 或 `'1'`（`1.0`、`01` 也行）时设置，否则"参数错误"；不检查是否当前管理员。
- 旧版的权限模型照旧：有 `admin/info` 权限的管理员可以修改任何管理员（含超级管理员）的密码与权限（黄金 `admin-save-editor-self` 在 PHP 7.3 上成功）。这是风险，见第 13 节。

#### 4.5 框架页、欢迎页与其他

- **框架页** `index/index`：菜单按第 3 节的数据与 `check_auth` 过滤（`show == 1` 且有权限；分组为空则去掉），`data-id` 为"条目键 + 组内序号"，顶部导航序号从 1 开始；快捷菜单（旧 `config('quickmenu')`，每次请求读，按修改时间热加载）在第 1 组末尾加"↓↓↓自定义菜单区域↓↓↓"后逐行加入，地址规则同旧版（`http`、`//`、`/` 开头或含 `###` 原样，`###` 显示为分隔线，否则按 `url()`；没有地址时是当前页）。快捷菜单为空时旧版改读 `application/data/config/quickmenu.txt`（仓库与备份都没有这个文件），新版按空处理，效果相同（只加分隔项）。黄金 8 个框架页（超级管理员、部分权限、无权限、编辑、快捷菜单各种写法、无快捷菜单、zh-tw）逐项相同。
- **欢迎页** `index/welcome`：提示文字与表格结构同旧版；数值见 D-B12-1、D-B12-7。
- **快捷菜单页** `index/quickmenu`：GET 显示（文本框为 `join(chr(13), …)`、带令牌）；POST 保存归 B15，本批返回"该功能尚未在新系统中实现（由 B15 批次提供）"。
- **锁屏** 是旧前端脚本的功能（`sessionStorage`），服务端只有解锁 `index/unlocked`：`input()` 的 `password` 的 `md5` 与会话里的密码哈希相同 → "解锁成功"，否则"密码错误"。非超级管理员要有 `index/unlocked` 权限（旧版同样如此，权限树里的"--锁屏解锁"）。
- **布局** `index/iframe`：POST 的 `val` 宽松等于 1 → `Set-Cookie: is_iframe=yes; path=/`；等于 0 或缺失 → 删除（`is_iframe=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/`）；其他值 → 失败，提示照旧是"缓存清理成功"（旧版的笔误，原样保留）。
- **`checkcache`**：正文 `no`（旧版只有采集与播放器配置会设置 `cache_data`，那些在 B15/B17 之后）。
- **退出** `index/logout`：作废会话，302 到登录页；不需要权限（D-B12-6）。

#### 4.6 清理缓存 `index/clear`

旧版（`Index.php:157-168`、`Base::_cache_clear`）：按播放器配置重写 `static/js/playerconfig.js`、删除 `runtime/cache|log|temp`、清缓存驱动，再 `VodSearch::clearOldResult(true)`。新版：`playerconfig.js` 由 B05 按旧配置即时生成，没有运行目录与缓存驱动，所以只剩"删除结果更新时间早于（现在 − `vod_search_optimise_cache_minutes` × 60，默认 14 天）的视频搜索缓存行"（强制清理不排除 `vod_actor`）。由内容模块的 `ContentCacheMaintenance` 执行（登记 `content / mac_vod_search / DELETE / B12`）。旧配置、语言包与站点设置的进程内缓存按修改时间或重启生效（站点设置的热加载在 B15）。

### 5. 写库（B07 基础设施）

登记 `WriteRegistry`：`admin / mac_admin / INSERT、UPDATE、DELETE`（B12）；`content / mac_vod_search / DELETE`（B12，路线图写入方矩阵 `mac_vod_search` 行补上 B12）。SQL 全部在 `mapper-write/` 下，单表、`WHERE` 带前置条件、检查匹配行数：

| 语句 | SQL 要点 | 旧版 |
|---|---|---|
| `recordLogin` | `SET admin_last_login_time = admin_login_time, admin_last_login_ip = admin_login_ip, admin_login_time = ?, admin_login_ip = ?, admin_login_num = admin_login_num + 1, admin_random = ? WHERE admin_id = ? AND admin_pwd = ? AND admin_status = 1`；匹配 0 行 → 登录失败 | 按账号 + 密码 + 状态更新（同名同密码多行都被写），登录次数是"读出值 + 1"（D-B12-12） |
| `insert` | 只写名称、密码、状态、权限 | 整个 POST 交给 `insert`（`fields_strict=false`，表里有的列都写） |
| `update` | `SET admin_name, admin_auth[, admin_pwd][, admin_status] WHERE admin_id = ?` | 同上 |
| `delete` / `setStatus` | `WHERE admin_id IN (…)` | 同 |
| `deleteUpdatedBefore` | `DELETE FROM mac_vod_search WHERE search_update_time < ?` | 同（`clearOldResult(true)`） |

- `admin_random` 写 32 位随机十六进制（旧版 `md5(rand())`）；新旧都不读它（旧 `checkLogin2` 未启用）。
- 登录 IP 按旧 `mac_get_ip_long`（非 IPv4 为 0），客户端 IP 只经 `ClientIpResolver`（D2 ④）。
- **并发**（`AdminGoldenMySqlIT`）：同一管理员 16 线程同时登录，16 次都成功、`admin_login_num` 恰好 +16（旧版会丢计数）；登录与改密竞争 10 轮，每轮 12 线程：库里最终是新密码，`admin_login_num` 等于成功登录的次数（登录记录只写给通过条件更新的那几次），改密后旧密码登录失败、用旧密码登录的会话被 D2 ⑤ 判为无效。
- **回滚演练**：快照（`mac_admin`、`mac_vod_search`）→ 登录、新增、编辑、改状态、删除、清理搜索缓存 → `DmlRestore` 只用 DML 恢复 → 与快照逐行 diff 为空 → 表结构指纹与导入 `install.sql` 时相同。
- 零结构变更：没有 DDL、没有新表新列；`SchemaGuardTest`、`WriteGuardTest` 通过，`-Pmysql-it` 结束时指纹比对通过。

### 6. 语言包

- 旧语言包 `application/lang/zh-cn.php`、`zh-tw.php` 是手写的 PHP 数组（含双引号字符串），新系统不执行 PHP，也不经 `var_export` 解析器；用 `backend/src/test/php/lang_export.php` 原样导出为 jar 内的 `lang/zh-cn.json`、`zh-tw.json`（各 1773 条，键按 `array_change_key_case` 转小写），源文件 SHA-256 写在 JSON 里，`LegacyLangTest` 校验与仓库源文件一致（改了语言包要重新导出）。
- `common.lang.LegacyLang`：`get(键)` 同 ThinkPHP `Lang::get`（键按 ASCII 转小写，找不到返回键名原文）；`select(app.lang)`：空或缺失为 zh-cn，`zh-cn`/`zh-tw` 原样，其他值回退 zh-cn 并由调用方告警（D-B12-L1）。
- 后台页面、菜单名、提示语全部取语言包（旧模板的 `{:lang(…)}`）；`app.lang = zh-tw` 时整套后台为繁体（黄金 `frame-zh-tw`：框架页、欢迎页、列表、表单、拒绝提示、解锁失败、登录页逐项相同）。跳转提示页的文字与旧 `dispatch_jump.tpl` 一样写死简体。
- 部署方若在线上改过语言包文件，新版不会读到（D9-b，未核实线上是否改过）。

### 7. 部署参数与配置

| 参数 | 说明 |
|---|---|
| `MACCMS_ADMIN_ENABLED` | `true`/`false`，默认 `false`（关闭时没有任何后台路由）；其他值启动失败 |
| `MACCMS_ADMIN_ENTRY` | 后台入口文件路径，必填，形如 `/_admin.php` 的单段 `.php`；不能是 `/admin.php`（旧入口拒绝这个名字）、`/index.php`、`/api.php`、`/install.php` |
| `MACCMS_ADMIN_BASE_URL` | 对外地址（同 `maccms.site.base-url` 的写法，路径以 / 结尾）：页面里的路径前缀（旧 `__ROOT__`、`SCRIPT_NAME`）、会话 cookie 的 Path/Secure、POST 来源校验 |
| `MACCMS_WRITE_ENABLED` 等 | 后台要写 `mac_admin`，可写数据源必须打开，否则启动失败 |
| `MACCMS_LEGACY_CONFIG_DIR` | 读 `extra/maccms.php` 的 `app.admin_login_verify`、`app.pagesize`、`app.lang`，`extra/version.php` 的 `code`，`extra/captcha.php`（启动时读一次，改了要重启）；`extra/quickmenu.php` 每次请求读。没有旧配置目录时用仓库值（要验证码、每页 20、zh-cn） |

缺失或不合法时启动失败，信息只含配置项名称（`AdminEndpointTest`、`AdminStartupTest`）。base-url 带子目录时与站点相同，由反向代理把前缀映射到本应用根路径。

### 8. 页面：复用旧后台结构

- **结构原样**：`admin/view/public/head.html`、`foot.html`、`index/login.html`、`index/index.html`、`index/welcome.html`、`index/quickmenu.html`、`admin/index.html`、`admin/info.html` 与 ThinkPHP `dispatch_jump.tpl`、`public/msg.html` 逐个转成 `jte-admin/admin/` 下的模板，DOM、类名、`lay-*`、`data-*` 与表单字段不变；`static/layui/**`、`static/css/admin_style.css`、`static/js/admin_common.js`、`static/js/jquery.js` 原样复用（构建时从仓库 `static/` 打进 jar，按旧路径 `/static/…` 提供，不复制、不修改）。没有新的视觉设计。
- **为安全所做的最小改动**（D-B12-5）：
  - 旧模板里的内联脚本（head 的全局变量、登录页、框架页、列表分页、权限树、跳转倒计时）逐行移到 `backend/src/main/resources/static/admin-assets/*.js`，服务端写入的地址与语言包文字改由 script 标签的 `data-*` 属性提供；登录页验证码图片的 `onclick` 改成脚本绑定；框架页 `setInterval("checkCache()")` 改为函数引用。
  - 内容安全策略（`AdminResponses.CONTENT_SECURITY_POLICY`）：脚本只来自本站文件；旧 `admin_common.js` 与 layui 用到 `new Function`（锁屏、laytpl），保留 `'unsafe-eval'`；旧模板大量 `href="javascript:;"`、`javascript:void(0);` 空链接和 layer 弹层 iframe 的 `onload="this.className='';"` 用 `'unsafe-hashes'` 只放行这几段固定代码的哈希，其他任何内联事件与 `javascript:` 地址仍被拦截；样式保留 `'unsafe-inline'`（layer 与旧模板的内联样式）；frame 放行 `'self' http: https:`（快捷菜单可以配置外部地址并在标签页中打开，只在用户点击时加载）。另有 `X-Frame-Options: SAMEORIGIN`、`Referrer-Policy: same-origin`、`nosniff`、`no-store`、`noindex`。
  - 模板一律转义输出（旧模板多处原样输出：管理员名称写进 `value="…"` 遇到双引号会截断属性，黄金 `admin-info-view/quote`）。jte 不输出 `a@href` 里以 `javascript:` 开头的动态值，所以旧版固定的 `javascript:void(0);return false;`、`javascript:history.back(-1);` 写成模板里的固定文字；跳转页对 `history.back` 由 `jump.js` 处理。
  - 语言包里自带 `<br>` 的两段文字经 `AdminText.trusted` 原样输出（只能是 `page.lang().get("键")`）。
- **模板策略**（`AdminTemplatePolicyTest`）：用 jte 的解析器以后台策略重新编译全部模板——禁止内联事件属性、单引号属性、`<object>/<embed>/<base>` 等、`srcdoc/formaction/http-equiv`，`<iframe>` 只在框架页，`<script>` 只能带 `type`、`src`、`data-*` 且内容为空；文本检查 `<style>` 与 style 属性里没有输出（唯一例外是侧栏"除第一组外隐藏"的固定三元表达式）、没有 `$unsafe`/`@raw`/`!{}`、`AdminText.trusted` 只接语言包键；页面脚本、`admin_common.js`、`admin_style.css` 与模板里没有外部主机。
- **目录归属**：`jte-admin/` 页面模板与视图模型由后端维护（同 site 的页面模板）；`static/admin-assets/` 是旧内联脚本的原样外移，B12 由后端写入，之后由前端维护还是后端维护请队长定（沿用旧资产、不做设计，所以本批没有请前端介入）。

### 9. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金（PHP 8.0.30 与 7.3.33 各一份） | `backend/src/test/php/admin_golden.php` + `admin_http_child.php`：一次性库 `maccms_it_*`，php-cgi 跑完整的 `_admin.php` + ThinkPHP 路由 + `Base` 构造函数 + 控制器，同一场景内按浏览器回送 PHP 会话 cookie；验证码直接写进会话文件。70 个场景、482 个请求步骤（含 178 个可访问的权限码逐个被拒——`make/index?ac2=wap` 只是权限码、不是地址——、29 个密码/账号字节向量、7 个 filterExp 向量、验证码、地址写法、会话快照、列表/表单/保存/删除/状态/解锁/布局/退出）。结果 `src/test/resources/admin/php80-golden.json`、`php73-golden.json`。不调用会写仓库文件的 `index/clear` 与快捷菜单保存 |
| 黄金复放（真实 MySQL） | `AdminGoldenMySqlIT.scenariosMatchPhpGolden`：两份黄金共 964 步逐步复放到新版（MockMvc + 完整应用上下文 + 可写数据源），比较状态码、Location、JSON 字节、页面结构（`AdminHtml` 同一套取法）、`is_iframe` cookie 与请求后 `mac_admin` 全部行；有意差异以 `JAVA`/`JAVA_ROWS` 逐条断言新版结果（第 11 节） |
| 并发、回滚演练 | `AdminGoldenMySqlIT` ②③（第 5 节） |
| 菜单 | `AdminFrameGoldenTest`：16 个黄金框架页（两版 PHP）与新版渲染结果逐项相同 |
| 权限码 | `AdminPermissionsGoldenTest`（auth.php 与控制器公开方法全量比对、每个码都有归属）；`AdminMvcTest.everyAuthCodeIsDeniedToAnAdminWithoutPermissions`（非超级管理员逐个访问 178 个可访问的码全部被拒，`index/welcome` 放行）、`superAdminSeesLaterBatchNoticesForEveryNonB12Action` |
| 入参与鉴权语义 | `LegacyInputTest`、`AdminAuthorizationTest`、`LegacyAdminUrlTest`、`AdminRouteTest` |
| HTTP 契约 | `AdminMvcTest`（13 个用例：路由、登录顺序与会话轮换、filterExp、D2 ⑤、权限即时生效、框架页操作、来源校验、令牌与保存、删除与状态、列表结构）；`AdminRealHttpTest`（真实 Tomcat 原始套接字：cookie 字节、子目录 Path、Secure、is_iframe、退出删除 cookie、CSP、403） |
| 启用条件 | `AdminEndpointTest`、`AdminStartupTest` |
| 模板与静态资源 | `AdminTemplatePolicyTest` |
| 语言包与手机模板 | `LegacyLangTest`、`SiteTemplateSelectionGoldenTest`（第 12 节） |
| 护栏 | `ModuleBoundaryTest`（新增后台规则）、`WriteGuardTest`/`WriteRegistryTest`（登记表与写入方矩阵）、`SchemaGuardTest`、`OutboundNetworkGuardTest`、`SqlEntryGuardTest` |
| 浏览器 | `backend/src/test/browser/b12_admin_check.py`（Playwright + 本机 Chromium，对运行中的新后台）：登录（错误提示）→ 框架页菜单 → 管理员标签页（layui 开关与分页渲染）→ 添加管理员弹层（208 个权限复选框渲染、勾选、保存后列表出现）→ 锁屏（错误提示、解锁）→ 退出 → 旧会话无效；全程 0 个内容安全策略违规、0 个脚本错误、0 个控制台错误、0 个对外请求 |

命令与结果见交付评论。

### 10. 回滚

- 代码：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到上一 main 快照再跑 `./mvnw verify`。不涉及表结构、数据迁移或配置格式；后台默认关闭（`MACCMS_ADMIN_ENABLED=false`）。
- 数据：本批的写入只有 `mac_admin` 的登录记录与管理员增删改、`mac_vod_search` 的过期行删除，全部是对现有列的 DML；回滚演练见第 5 节。
- 运行状态：会话只在进程内存，重启即清空；新旧后台的会话互不相通，切回 PHP 后台时所有管理员在 PHP 重新登录（03 草稿 4.3 (7)）。新版写入的 `admin_pwd`、`admin_auth` 与旧版格式相同，PHP 照常读取。

### 11. 与旧版的差异

| # | 旧版 | 新版 | 性质 |
|---|---|---|---|
| D-B12-1 | 登录时把整行存进会话，之后只看快照（`Admin.php:145-146,166-176`） | **D2 ⑤**：每个请求重读当前行，停用、改密、删除 → 立即要求重新登录；权限按当前值。欢迎页的"登录次数""上次登录 IP/时间"随之取当前行（含本次登录；"上次"是上一次登录），旧版取登录前的快照 | 安全修复（已决） |
| D-B12-2 | 与前台共用 PHP 会话 `PHPSESSID`（`Path=/`、无 HttpOnly），登录不换标识 | 独立的进程内会话 `mac_admin_sid`（Path 为后台入口、HttpOnly、SameSite=Strict、https 时 Secure），登录换新标识，空闲 24 分钟，重启失效 | 加固 + 实现差异 |
| D-B12-3 | 没有来源校验；删除、改状态、退出等 GET 操作可被其他网站触发 | POST 校验来源；会话 cookie 为 SameSite=Strict，跨站请求不带会话 | 加固 |
| D-B12-4 | 登录页验证码用前台 `/index.php/verify/index.html`（共用 PHP 会话）；猜错不作废 | 后台自己的 `<entry>/admin/index/verify.html`；猜错即作废（D-B08-4） | 实现差异 + 加固 |
| D-B12-5 | 模板内联脚本、`onclick`、`setInterval("…")` | 脚本移到 `static/admin-assets/`，内容安全策略；功能与 DOM 不变 | 加固 |
| D-B12-6 | 非超级管理员没有 `index/logout` 权限（权限树里没有这一项），点"退出登陆"只得到"您没有权限访问此页面" | 退出不需要权限 | 缺陷修复（请队长确认） |
| D-B12-7 | 欢迎页显示 PHP_OS、SERVER_SOFTWARE、PHP/ThinkPHP 版本、`upload_max_filesize`；有数据库升级脚本时显示升级链接；内联脚本定义 `MAC_VERSION`/`PHP_VERSION`/`THINK_VERSION`/`MAC_LANG`（无处使用） | 操作系统与 Servlet 容器、本应用的上传上限；PHP/ThinkPHP 版本两行显示 `-`；不显示升级链接（零结构变更，旧版的 `Update` 也是空类）；不定义那几个全局变量 | 实现差异 |
| D-B12-8 | 数组形式的 `admin_name[]`/`admin_pwd[]`：PHP 8 TypeError 500；7.3 账号数组 500、密码数组按"账号或密码错误" | "参数错误" | 非法参数 |
| D-B12-9 | 保存时整个 POST 写进表（可改 `admin_login_num`、`admin_random` 等），`admin_status` 原样（可存 5）；不交 `admin_auth[]` 时 PHP 8 TypeError 500（7.3 只留 `index/welcome`）；名称超长、含 4 字节字符时严格模式报错 500 | 只写名称、密码、状态、权限；状态只接受宽松等于 0 或 1 的值，否则"参数错误"；不交权限按 7.3；名称超过 30 个字符、非法 UTF-8、4 字节字符、权限含这类字符或超过 65535 字节 → "参数错误"；标量的 `admin_auth` → "参数错误"（旧版致命错误） | 加固 + 非法参数 |
| D-B12-10 | `ids` 交给 MySQL 隐式转换：`abc` 当 0（"删除成功"、什么都没删），`1abc` 当 1——PHP 8 的 `in_array` 认为 `1abc` 不等于当前管理员的 1，于是删掉了当前登录的超级管理员（黄金 `admin-del/self-loose`，PHP 7.3 拒绝） | 每项必须是十进制数字（可带首尾空格），否则"参数错误"；一次最多 1000 个 | 缺陷修复 |
| D-B12-11 | `page`、`limit` 的原值写进页面脚本（`limit=2abc` 时分页脚本出错）；每页条数不限 | 按 `intval`；每页最多 1000 | 加固 |
| D-B12-12 | 登录记录按 账号 + 密码 + 状态 更新（同名同密码的多行都被写，黄金 `login-duplicate-name`），登录次数"读出值 + 1"（并发丢计数），读与写之间被改密照样登录 | 只写登录的那一行，登录次数原子 +1，读写之间被停用或改密 → "更新登录信息失败" | 并发正确性 |
| D-B12-13 | 快捷菜单里含 `javascript:` 的地址原样写进 href | 写成固定的 `javascript:void(0);`（不执行） | 加固 |
| D-B12-14 | 清理缓存还清运行目录、缓存驱动并重写 `playerconfig.js` | 只清过期的视频搜索缓存（第 4.6 节） | 实现差异 |
| D-B12-15 | auth.php 里后续批次的页面是旧版的完整功能 | 返回"该功能尚未在新系统中实现（由 Bxx 批次提供）"：Ajax 为 200 的 `code=0` JSON，页面为 501 | 分批的功能缺口，不是终态差异 |
| D-B12-16 | 未知控制器、未知动作：ThinkPHP 的 System Error 页（404）；Ajax 同样是 HTML | 简单的 404 页；Ajax 为 404 的 JSON | 实现差异 |
| D-B12-17 | 页面里管理员名称、快捷菜单名称等原样输出；成功跳转地址取任意 Referer | 一律转义；Referer 只接受 http/https 或站内路径 | 加固 |
| D-B12-L1 | `app.lang` 不是现有语言包时 `Lang::load` 静默跳过，页面显示键名（Windows 上大小写不同的 `ZH-TW` 因文件名不分大小写而命中繁体） | 回退简体并告警 | 实现差异 |
| D-B12-L2 | 请求没有 `User-Agent` 时 `Init` 行为报错，整页 500（ThinkPHP 把 Notice 转成异常） | 按桌面处理 | 非法输入 |
| — | 旧配置每个请求读 | 后台用到的旧配置启动时读一次（快捷菜单除外） | 同 site，配置热加载在 B15 |

### 12. 手机模板与语言（承接 B05 D-B05-8）

B05 用响应式单模板与简体固定文案交付，并登记了旧版 `mob_status` 手机模板选择与 `zh-cn/zh-tw` 语言选择的差异，归属本批（"不是永久删除旧功能"）。本批先把**选择语义**定下来并实现、用黄金锁定，再在前端协调后接到页面。

#### 12.1 选择语义（契约，已实现）

| 项 | 规则（旧 `common/behavior/Init.php:12-98`、ThinkPHP `App::run`） | 新版 |
|---|---|---|
| 手机判定 | `User-Agent` 任意位置含 `nokia|sony|ericsson|mot|samsung|sgh|lg|philips|panasonic|alcatel|lenovo|meizu|cldc|midp|iphone|wap|mobile|android`，不分大小写（`mot`、`lg` 等短词会误判，照旧） | `site.application.SiteTemplateSelection`；没有 UA 按桌面（D-B12-L2） |
| 站群 | `extra/domain.php` 是数组且有 `Host` 头**原样**（区分大小写）的非空项时并入 site 配置；`mob_template_dir` 为空/`0`/`no` 时取并入后的 `template_dir`；`site_wapurl`、`mob_html_dir`、`mob_ads_dir` 取并入后的 `site_url`、`html_dir`、`ads_dir`；**命中站群时不切手机模板** | 同 |
| 手机模板 | 手机、未命中站群，且 `mob_status == 2`，或 `mob_status == 1` 且 `Host` 原样等于 `site_wapurl`（PHP 宽松比较：`'01'`、`' 2'`、整数按数值；`'abc'`、空串、3 都不切）→ 模板/html/广告目录取 `mob_*`（非站群时 `mob_template_dir` 为空或 `no` 也照用，旧版不回退） | 同 |
| 输出 | `MAC_MOB`；`{$maccms.path_tpl}` = 安装目录 + `template/模板目录/html目录/`；`{$maccms.path_ads}` 同理；旧版页面缓存键带 `MAC_MOB`（`All.php:27,52`） | `Result(wap, pathTpl, pathAds, site…)` |
| 语言 | `app.lang` 非空时作为 `default_lang`，否则 `zh-cn`；`lang_switch_on=false`，不按请求切换；加载 `application/lang/<lang>.php` | `LegacyLang.select`（第 6 节），其他值回退简体（D-B12-L1） |

黄金：`backend/src/test/php/template_select_golden.php`（+ `_child.php`，每个用例一个 php 进程执行旧 `Init::run`，不连库），PHP 8.0/7.3 各 93 个用例（21 个 UA 关键字、5 个桌面 UA、无 UA、`mob_status` 8 种取值 × 手机/桌面 × 3 种 Host、整数状态、手机目录为空/no、`site_wapurl` 为空、站群命中/大小写不同/目录回退/不是数组、子目录安装、5 种 `app.lang`）。`SiteTemplateSelectionGoldenTest` 逐项比对 `MAC_MOB`、三个路径、并入后的 site 配置、语言包与 `lang()` 的结果。

#### 12.2 文案资源契约（已实现）

- 前台与后台共用 `common.lang.LegacyLang`：键是旧语言包的键（如 `vod`、`menu/admin`、`admin/index/login/title`），值为该语言的原文；繁体就是旧 `zh-tw.php` 的原文，不做简繁自动转换。新页面要显示旧版也有的文字时**必须**取语言包的键，不在模板里写死简体；旧版没有的新增文字（如本批"由 Bxx 批次提供"）写死简体并在文档登记。

#### 12.3 未完成、需要协调的部分（如实说明）

- **前台页面尚未接线**：站点页面的文字仍是 B05 的简体固定文案，手机标志与 `path_ads` 等也没有用到页面上。把站点模板改为取语言包键、按 `wap` 输出的工作会改动站点的外壳与组件（`jte/site/layout`、`jte/site/components`、`static/site/`，前端维护），按任务要求"组件/外壳共享文件禁止未协调并行修改"，本批没有改。建议做法：页面视图模型带上 `LegacyLang` 与 `SiteTemplateSelection.Result`；外壳与组件的 `@param` 签名在接线批次开工时与前端确认（02 规范第 7 节）；页面缓存头按 `mob_status` 是否开启加 `Vary: User-Agent`（旧版按 `MAC_MOB` 分缓存键）。请队长安排前端（Kimi）与后端一起完成，验收用例：`mob_status` 0/1/2 × 手机/桌面 × Host、`zh-tw` 全站文案、URL/SEO（canonical、站内链接）与内容安全策略不变。
- 线上模板缺失（D9-a），手机模板目录里旧模板的具体内容无从对照；新版只实现"选择"本身，不承诺运行缺失的旧 PHP 模板（列入 B21 对齐总表）。

### 13. 风险与遗留

| 项 | 说明 |
|---|---|
| 旧权限模型本身的风险 | 有 `admin/info` 权限的管理员可以改任何管理员（含超级管理员 1 号）的密码与权限；`admin_name` 没有唯一约束，可以建同名账号。照搬旧版，未改；是否收紧（如只允许超级管理员编辑 1 号）需要用户决定 |
| 锁屏解锁没有频率限制 | 已登录会话可以反复尝试 `index/unlocked`，与旧版相同 |
| 不加盐 md5 | 用户已接受（D6） |
| D-B12-6 退出不需要权限 | 修复旧缺陷，请队长确认是否接受 |
| `static/admin-assets/` 的维护归属 | 见第 8 节，请队长定 |
| 站点的繁体与手机标志接线 | 第 12.3 节，需队长安排前端协调 |
| 浏览器检查依赖本机 Chromium | `b12_admin_check.py` 不在 `./mvnw verify` 中运行；结果见交付评论 |
