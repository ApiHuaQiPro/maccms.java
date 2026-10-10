## 03 重构方案草稿（待用户确认，语言未定）

> 本方案和语言无关，Go 和 Java 都适用（选型见 02）。凡是标注"待确认"的地方，要等用户回答 02 第 5 节的问题后才能定稿。
> 原则：**先兼容、后改进**；**单一写入方**；**每一步都能回滚**；**不做破坏性的库结构变更，直到旧系统下线**。

### 1. 模块边界（模块化单体）

| 模块 | 职责 | 对应旧代码 | 拥有的表 |
|---|---|---|---|
| `content` | 视频、文章、专题、演员、角色、网址、分类、友链；扩展字典；播放串编解码 | `common/model/{Vod,Art,Topic,Actor,Role,Website,Type,Link,Extend}.php`、`common.php` 中的 `mac_play_list*` | `mac_vod/art/topic/actor/role/website/type/link`、`mac_vod_search` |
| `site` | 前台渲染、模板标签、SEO URL 生成与解析、播放页（`player_aaaa`）、sitemap/rss、静态生成 | `index/controller/*`、`common/taglib/Maccms.php`、`common/controller/All.php`、`mac_url*`、`admin/controller/Make.php` | 无，只读 content |
| `member` | 会员、会员组权限、登录态、积分、订单、卡密、提现、行为日志、推广 | `common/model/{User,Group,Order,Card,Cash,Plog,Ulog,Visit,Msg}.php`、`index/controller/User.php` | `mac_user/group/order/card/cash/plog/ulog/visit/msg` |
| `interact` | 评论、留言、顶踩、评分 | `Comment`、`Gbook`、`index/controller/Ajax.php` | `mac_comment/gbook` |
| `collect` | 资源站采集（JSON/XML）、分类绑定、合并规则、图片同步、自定义规则采集 | `admin/controller/{Collect,Cj}.php`、`common/model/{Collect,Cj}.php`、`util/Collection.php` | `mac_collect`、`mac_cj_*`；新增 `collect_bind` |
| `openapi` | Provide（对外资源站）和 Receive（入库）协议兼容 | `api/controller/{Provide,Receive}.php` | 无 |
| `admin` | 管理员、权限、系统配置、定时任务、数据库工具、附件 | `admin/controller/*` | `mac_admin`、`mac_annex`；新增 `sys_config`、`admin_permission` |
| `integration` | 支付、短信、邮件、对象存储、搜索引擎推送、第三方登录的适配器 | `common/extend/*` | 无 |

部署形态是一个 web 进程，加上一个 worker 进程（负责 collect、make、urlsend、定时任务），两者来自同一个代码库。

### 2. 数据库：先沿用

**阶段 A（并行期）：原样沿用 `mac_*` 表结构**
- 新系统直接读写旧库。
- 只允许**增量、向后兼容**的变更：新增表、新增可空列、新增索引。
- 不允许改名、删列、改类型。

**新增表或列（迁移脚本要在实施阶段编写，这里只列清单）**
- `sys_config`：把 `application/extra/*.php` 中的配置迁到库里，每一组是一行 JSON，并带版本号。
- `collect_bind`：把 `extra/bind.php` 迁到库里。
- `admin_permission`：把 `admin_auth` 的文本拆成行。
- `mac_user.user_pwd_v2`（可空）：用来渐进升级密码哈希（见第 4.3 节）。

**表引擎转换：MyISAM 改为 InnoDB**
- 作为一个**独立的步骤**执行，不和代码切换放在同一天。
- 执行前先全量备份，并在副本上演练、计时。
- 对 PHP 来说这是透明的，回滚可以直接从备份恢复，也可以执行 `ALTER ... ENGINE=MyISAM`。

**字符集转换：utf8 改为 utf8mb4**
- 和上面一样作为独立步骤。
- 要先确认索引长度不超限，`varchar(255)` 加索引时需要检查。

**阶段 C（旧系统下线以后）**：才考虑重新设计表结构，例如把播放串拆成 `vod_source` 和 `vod_episode` 两张表。到那时提供正式的迁移脚本和反向脚本。

### 3. 新旧并行与迁移顺序

前面放一层 nginx，**按 URL 前缀或路由灰度**，把流量逐步切到新系统（绞杀者模式）。原则是**同一张表在同一时间只有一个系统写**。

| 阶段 | 内容 | 写入方 | 验收 | 回滚 |
|---|---|---|---|---|
| 0 | PHP 现网先修复安全问题（01 第 10 节第 1～3 条）。补齐仓库里缺失的 `vendor`、模板和配置（脱敏）。生成"黄金样本"（见第 5 节） | PHP | 样本集已产出 | 不涉及 |
| 1 | 新系统上线**只读前台**：首页、分类、筛选、详情、播放、搜索、sitemap、rss。灰度方式是先按比例或 UA（例如先只切蜘蛛以外的流量），再逐步放量 | PHP（后台、采集、会员写入都不变） | 黄金样本 URL 和页面数据对比一致；SEO 收录和 404 监控正常 | nginx 切回 PHP，秒级完成 |
| 2 | `openapi`：Provide 对外接口，协议逐字段对比 | PHP | 同一请求的新旧输出 diff 为空，允许字段顺序不同 | nginx 切回 |
| 3 | `collect` worker 接管采集。上线前先"影子运行"：抓取并计算合并结果，但不写库，和 PHP 的合并结果做 diff | 采集写入切到新系统，同时停用 PHP 的定时任务 | 影子期 diff 为零或可以解释 | 停掉 worker，恢复 PHP 的定时任务 |
| 4 | `member` 与 `interact`：登录、注册、会员中心、评论、留言、支付回调 | 切到新系统 | 新旧 cookie 互通（见第 4.3 节），资金类操作对账一致 | nginx 切回。因为 md5 列一直保持同步，PHP 仍然可以登录 |
| 5 | `admin` 后台 | 切到新系统 | 后台逐个功能清单验收 | 恢复 PHP 后台入口。配置期间双写，或从 `sys_config` 导出回 PHP 文件 |
| 6 | 下线 PHP，进入阶段 C 的库结构优化 | 只剩新系统 | — | 依靠备份加反向迁移脚本 |

**缓存一致性**：并行期内 PHP 用的是文件缓存（`maccms.php:50` `cache_type=file`），新系统无法让它失效。处理方式如下：
- 新系统给只读页面设置短 TTL。
- PHP 后台修改内容后，写入一个"内容版本号"（例如 Redis 的一个键，或者在 `sys_config` 里加一行），新系统据此主动刷新。
- 需要给 PHP 加一个很小的钩子来写这个版本号，**属于业务代码修改，需要另外确认**。

### 4. 核心数据与行为的兼容策略

#### 4.1 视频与分类
- **字段**：`mac_vod`、`mac_type` 原样读取，时间戳和 IP 的格式保持不变。
- **分类树**：`type_pid` 加 `type_mid`。`type_extend` 按 JSON 解析，键名保持不变。
- **分类模板字段**：`type_tpl*` 在新系统里映射为模板名，找不到时回落到默认模板。
- **点击、评分、顶踩**：使用原子的 `UPDATE ... SET x=x+1`，和旧逻辑一致。日、周、月点击数的重置规则要照 `updateToday` 的逻辑实现。
- **查询**：列表和筛选条件要按 `Vod::listCacheData` 的参数逐项实现，包括 `type`、`class`、`area`、`lang`、`year`、`level`、`letter`、`order`/`by` 和 `rnd`。用黄金样本对比返回的 ID 序列。

#### 4.2 播放地址
- **存储格式不变**：`$$$` 分隔播放组，`#` 分隔集，`$` 分隔标题、地址和来源。
- **编解码**：新系统实现与 `mac_play_list` / `mac_play_list_one`（`common.php:1346-1455`）等价的函数。这些函数要有用 PHP 生成的测试向量覆盖，包括以下情况：
  - 空标题
  - 缺少 `$`
  - server 前缀
  - 播放器未启用时被过滤
  - 按 sort 排序
  - 结尾多出 `#`
  - 含 CRLF
- **前端契约**：播放页输出相同结构的 `player_aaaa`，字段为 flag、encrypt、trysee、points、link、link_next、link_pre、url、url_next、from、server、note、id、sid、nid。`app.encrypt` 取 0、1、2 时的行为也要一致，这样 `static/js/player.js` 和 `static/player/*` 才能原样复用。
- **播放器配置**：`vodplayer.php`、`vodserver.php`、`voddowner.php` 迁到 `sys_config`，新后台负责生成 `static/js/playerconfig.js`，格式与 `admin/controller/Base.php:85-103` 和 `System.php:675-690` 保持一致。
- **会员组权限**：播放和下载的权限检查（`group_popedom` 中的 3 播放、4 下载、5 试看）以及积分扣减规则，在 `member` 模块里实现。

#### 4.3 用户：密码、登录态、权限
- **密码**
  - 首次登录时用 `md5(明文)` 校验，校验通过后计算 bcrypt 或 argon2id，写入 `user_pwd_v2`。
  - 并行期内**继续把 `user_pwd` 更新为 md5**，保证回滚到 PHP 后用户仍能登录。
  - PHP 下线后清空 `user_pwd`，以后只校验 v2。
  - **不兼容**旧代码"把哈希当作密码也能登录"的行为（01 第 10 节第 1 条），这是有意的差异。
- **登录态**
  - 旧系统的会员登录态是**无状态 cookie**：`user_id`、`user_name`、`user_check = md5(user_random-name-id-)`，`user_random` 存在库里（`User.php:367-389,446`）。
  - 新系统在并行期**按同样的算法校验和签发**这些 cookie，这样新旧系统之间可以无缝切换，用户不需要重新登录。
  - PHP 下线后改为 HMAC 签名或服务端 session，并加上 httponly 和 secure。
- **第三方登录**：openid 登录只允许在 OAuth 回调里使用，**不兼容**旧代码"直接提交 openid 即可登录"的行为（01 第 10 节第 2 条）。
- **会员组**
  - `group_type` 和 `group_popedom` 原样解析。
  - 会员到期降级的规则要照搬：`group_id>2` 且 `user_end_time` 已过期时，降为 2（`User.php:362-365`）。
- **管理员**
  - 密码同样采用渐进式升级。
  - `admin_auth`（`,控制器/方法,` 格式的文本）由迁移脚本拆成权限码，写入 `admin_permission`，同时保留"`admin_id=1` 是超级管理员"的语义。
  - 新后台的权限码与旧的 `控制器/方法` 之间要有一张映射表。管理员 session 不能在新旧系统之间共享，切换时**需要重新登录**。

#### 4.4 采集配置
- `mac_collect` 原样读取，包括 `collect_url`、`type`、`mid`、`param`（base64）、`filter`、`filter_from` 和 `sync_pic_opt`。
- `extra/bind.php` 由迁移脚本导入 `collect_bind`，`cjflag` 的算法保持 `md5(url)` 不变。
- `maccms.php` 中 `collect.*` 的 inrule、uprule、过滤词和随机规则导入 `sys_config`。合并逻辑必须用"影子运行加 diff"验证（见第 3 节阶段 3）。
- `extra/timming.php` 中的定时任务迁到 worker 的调度表，触发接口要求鉴权（**不兼容**旧的无鉴权接口）。
- 新增能力：
  - 并发和限速，按资源站分别配置
  - 断点续采
  - TLS 证书校验，可以按资源站关闭
  - 更完整的 SSRF 防护，屏蔽内网网段

#### 4.5 旧 URL 与 SEO
- **前提**：拿到线上的 `maccms.php`（rewrite、view、path）和 `route.php`（待确认）。
- **URL 生成**：新系统实现与 `mac_url` 等价的生成器，包括：
  - ID 模式：数字、拼音、`mac_alphaID`
  - 后缀隐藏
  - 是否保留 `index.php`
  - 分页规则（第 1 页不带页码）
- **URL 解析**：同时支持三种形式：
  - 旧的 PATHINFO 形式，例如 `/index.php/vod/detail/id/1.html`
  - 路由形式，例如 `/voddetail/1.html`
  - 当前配置对应的形式

  非规范形式统一 **301 跳转到规范 URL**，规范 URL 与旧站保持一致。
- **黄金样本**：在 PHP 环境用 CLI 脚本遍历所有内容，调用 `mac_url_*` 导出 URL 清单，新系统必须 100% 生成一致。sitemap 和 rss 的输出格式也要逐一对比。
- **监控**：切换后监控 404、5xx 和蜘蛛抓取情况。

#### 4.6 接口
- **Provide**：参数（ac、t、ids、wd、h、pg、pagesize、at、year、isend、from）、JSON 字段、XML 结构都保持一致，`closed` 返回值也保留。IP 白名单改为只信任可信代理传来的真实 IP（**与旧版不同**：旧版信任任意请求头）。
- **Receive**：`pass` 校验的语义保留。
- **支付回调**：路径保持 `index.php/payment/notify`（POST，参数 `pay_type` 区分渠道，`index/controller/Payment.php:13-30`）不变，或者在支付平台改配新的回调地址（待确认）。并行期间回调只能由一方处理，避免重复入账。
- **Timming**：改为需要鉴权（与旧版不同）。

#### 4.7 第三方插件与模板
- PHP 插件（fastadmin-addons）**不能复用**。现有的 `adminloginbg` 未启用，评估后可以不迁。如果线上还有其他插件，需要列出清单，逐个决定是重写还是放弃（待确认）。
- 新系统用"模块内扩展点"（事件或钩子接口）替代插件机制，**不建议**在第一阶段做动态插件。
- **模板**有两条路，需要用户选择：
  - 甲：按新的模板引擎改写线上模板。工作量取决于模板页数，但可控。
  - 乙：实现一个 `{maccms:*}` 标签和常用 `mac_*` 函数的兼容层，或者做一个转换工具，以保留购买第三方模板的能力。前期成本高，模板中嵌入的任意 PHP 代码也无法兼容。

### 5. 验证手段（贯穿各阶段）

- **黄金样本**：在 PHP 本地环境用同一份库（脱敏副本）导出以下内容，作为新系统的测试基准：
  - URL 清单
  - 列表和详情的 JSON（通过 Provide）
  - 播放串解析结果
  - 播放页的 `player_aaaa`
  - 会员组权限判定表
- **影子流量**：nginx 用 mirror 把只读请求同时发给新系统，记录差异，不返回给用户。
- **对账**：每日核对积分、订单、卡密的总额。

### 6. 回滚策略汇总

- 路由级回滚：nginx 配置切回，所有阶段都可用，几秒内完成。
- 数据级回滚：
  - 并行期只做增量变更，旧系统完全不受影响。
  - 密码 md5 列一直保持同步。
  - 配置迁库期间，同时能导出回 PHP 文件格式。
- 引擎和字符集转换：执行前全量备份，副本演练后再上线。回滚方式是从备份恢复，需要评估停机窗口（待确认：取决于数据量）。
- 写入方切换（采集、会员、后台）：每个模块切换前停掉旧的写入任务。回滚时先停新写入，再恢复旧写入，期间的数据都在同一个库里，不需要回迁。

### 7. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 线上模板和配置缺失，无法确定 URL 和页面行为 | 前台无法验收，SEO 有损失 | 阶段 0 必须拿到脱敏后的线上配置和模板 |
| `mac_url` 和查询逻辑细节多（3000 行的 `common.php`） | URL 或列表不一致 | 用黄金样本加影子流量做逐项对比 |
| 采集合并规则复杂（uprule a～w） | 数据被覆盖或重复 | 影子运行加 diff，按资源站分批切换 |
| MyISAM 没有事务，资金类数据可能已经不一致 | 对账出现差异 | 迁移前先跑一次对账基线；切换到 InnoDB 后用事务 |
| 并行期缓存不一致 | 前台看到旧数据 | 短 TTL 加内容版本号 |
| 团队技能、流量、预算未知 | 选型或工期失准 | 先回答 02 第 5 节的问题；用一个 2～3 周的 PoC（只读详情页加采集 worker）验证 |
| 第三方模板生态从此不可用 | 长期改版成本上升 | 决策时由用户明确接受，或者选择兼容层方案（乙） |
| 现网安全漏洞在重构期间继续暴露 | 账号被接管 | 阶段 0 先在 PHP 修复（需要用户确认） |

### 8. 待用户确认的决策点

1. 语言：Go 还是 Java（见 02）。是否考虑"继续用 PHP 并加固"的路线？
2. 模板走甲（改写）还是乙（兼容层）？
3. 功能范围：哪些模块要砍掉（例如 Cj 规则采集、站群、静态生成、卡密、提现）。
4. 是否同意阶段 0 先在 PHP 现网修复三个安全问题。
5. MyISAM 转 InnoDB 和 utf8mb4 转换的时间窗口。
6. 能否提供脱敏后的线上配置（rewrite/view/path、route.php、bind.php、timming.php、播放器配置）和模板。
