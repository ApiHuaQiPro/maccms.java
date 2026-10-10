## 03 重构方案草稿（待用户确认，语言未定）

> 本方案和语言无关，Go 和 Java 都适用（选型见 02）。凡是标注"待确认"的地方，要等用户回答 02 第 5 节的问题后才能定稿。
>
> **本文全部是设计草稿，没有任何一项已经实现或部署。** 文中的新增表和字段（`sys_config`、`collect_bind`、`admin_permission`、`user_pwd_v2` 等）、PHP 侧写入守卫、配置导出脚本、兼容钩子、权限映射表，都是**待实施事项**；其中凡是要改 PHP 业务代码的，都需要用户另外批准。
>
> 原则：**先兼容、后改进**；**按"表 + 操作"确定唯一写入方，切换前先封住旧入口**；**回滚要有门槛和验证步骤，耗时以演练结果为准**；**旧系统下线前不做破坏性的库结构变更**。

### 1. 模块边界（模块化单体）

| 模块 | 职责 | 对应旧代码 | 拥有的表 |
|---|---|---|---|
| `content` | 视频、文章、专题、演员、角色、网址、分类、友链；扩展字典；播放串编解码；点击/顶踩/评分计数 | `common/model/{Vod,Art,Topic,Actor,Role,Website,Type,Link,Extend}.php`、`common.php` 中的 `mac_play_list*`、`index/controller/Ajax.php` 的 hits/digg/score | `mac_vod/art/topic/actor/role/website/type/link`、`mac_vod_search` |
| `site` | 前台渲染、模板标签、SEO URL 生成与解析、播放页（`player_aaaa`）、sitemap/rss、静态生成 | `index/controller/*`、`common/taglib/Maccms.php`、`common/controller/All.php`、`mac_url*`、`admin/controller/Make.php` | 无，只读 |
| `member` | 会员、会员组权限、登录态、积分、订单、卡密、提现、行为日志、推广、短信/邮件验证码 | `common/model/{User,Group,Order,Card,Cash,Plog,Ulog,Visit,Msg}.php`、`index/controller/User.php`、`index/controller/Payment.php` | `mac_user/group/order/card/cash/plog/ulog/visit/msg` |
| `interact` | 评论、留言 | `Comment`、`Gbook` | `mac_comment/gbook` |
| `collect` | 资源站采集（JSON/XML）、分类绑定、合并规则、图片同步、自定义规则采集 | `admin/controller/{Collect,Cj}.php`、`common/model/{Collect,Cj}.php`、`util/Collection.php` | `mac_collect`、`mac_cj_*`；拟新增 `collect_bind` |
| `openapi` | Provide（对外资源站）和 Receive（入库）协议兼容 | `api/controller/{Provide,Receive}.php` | 无 |
| `admin` | 管理员、权限、系统配置、定时任务、数据库工具、附件 | `admin/controller/*` | `mac_admin`、`mac_annex`；拟新增 `sys_config`、`admin_permission` |
| `integration` | 支付、短信、邮件、对象存储、搜索引擎推送、第三方登录的适配器 | `common/extend/*` | 无 |

部署形态是一个 web 进程，加上一个 worker 进程（负责 collect、make、urlsend、定时任务），两者来自同一个代码库。

### 2. 数据库：先沿用

**阶段 A（并行期）：原样沿用 `mac_*` 表结构**
- 新系统直接读写旧库。
- 只允许**增量、向后兼容**的变更：新增表、新增可空列、新增索引。
- 不允许改名、删列、改类型。

**拟新增的表或列（待实施；迁移脚本和反向脚本在实施阶段编写，这里只列清单）**
- `sys_config`：把 `application/extra/*.php` 中的配置迁到库里，每一组是一行 JSON，并带版本号。何时成为配置的唯一来源见第 3.5 节。
- `collect_bind`：把 `extra/bind.php` 迁到库里。
- `admin_permission`：把 `admin_auth` 的文本拆成行。
- `mac_user.user_pwd_v2`、`mac_user.user_pwd_v2_src`（均可空）：密码渐进升级，规则见第 4.3 节。

**表引擎转换：MyISAM 改为 InnoDB**
- 作为一个**独立的维护步骤**执行，不和任何代码切换放在同一个窗口。
- 执行前：全量备份；在副本上演练并计时；确认线上是否开启 binlog（待核实）。
- **备份之后的新写入怎么保全**，二选一，要在演练时定下来：
  - 维护窗口内停写。`app.site_status`（`application/extra/maccms.php:40`）只能关前台，后台、`api.php` 的 Receive/Timming、支付回调是否都能停住**待核实**，可能还要在 nginx 层临时拒绝写入路径；
  - 不停写，靠"全量备份 + binlog 增量"恢复到任意时间点。前提是线上已开 binlog，并且演练过回放。
- **回滚**：从备份恢复会丢掉备份之后的写入，除非用 binlog 补齐或者窗口内停了写。`ALTER TABLE ... ENGINE=MyISAM` 技术上可行，但它要重建整张表，耗时和锁表时间都未知，**没演练过就不能当成安全回滚手段**。
- **不可逆边界**：一旦新系统的资金类逻辑（第 3 节阶段 4）开始依赖 InnoDB 事务，就不再支持退回 MyISAM。如果必须退，只能连同阶段 4 一起回退到 PHP 写入，并重新对账。

**字符集转换：utf8 改为 utf8mb4**
- 和上面一样作为独立步骤；先确认 `varchar(255)` 加索引后不超过索引长度限制。
- PHP 的连接字符集是 `utf8`（`application/database.php:29`）。表改为 utf8mb4 之后，PHP 读到的 4 字节字符（例如 emoji）会显示异常，所以 PHP 的连接字符集要不要同步改，需要一起演练。
- **不可逆边界**：转换之后，一旦有 4 字节字符写入，再转回 utf8 就会丢字符或报错（取决于 `sql_mode`），**不能无损回转**。所以回滚只能"从备份恢复 + 补齐增量"，或者接受 utf8mb4 不再回退。这一点要由用户在转换前确认。

**阶段 C（旧系统下线以后）**：才考虑重新设计表结构，例如把播放串拆成 `vod_source` 和 `vod_episode` 两张表。到那时提供正式的迁移脚本和反向脚本。

### 3. 新旧并行、写入方与迁移顺序

前面放一层 nginx，按路由把流量逐步切到新系统（绞杀者模式）。核心约束是：**任一"表 + 操作"在任一时刻只有一个写入方**。只停旧的定时任务是不够的，旧后台、旧接口、前台 Ajax、数据库工具都会写同一张表，下面逐一列出。

#### 3.1 旧系统的写入入口清单（代码静态核对）

| 表 / 操作 | 旧系统的全部写入入口 |
|---|---|
| `mac_vod` 内容字段（新增、修改、删除、播放串） | 后台 `Vod::info` 保存（`admin/controller/Vod.php:366-370`）；`Vod::batch` 批量修改（`:141-347`，`:347` 执行 update）；`Vod::del`、`Vod::field`、`Vod::iplot`（`:236,448,490-499,418`）；后台资源站采集（`admin/controller/Collect.php:281` → `common/model/Collect.php:736` insert、`:982` update）；自定义规则采集 Cj（`admin/controller/Cj.php:428`）；入库接口 Receive（`api/controller/Receive.php:54`）；定时任务触发的采集（`api/controller/Timming.php`，翻页在 `Collect.php:1012`）；数据库工具的执行 SQL、批量替换、还原（`admin/controller/Database.php:237-265,287-314,141`） |
| `mac_vod` 计数字段（hits、hits_day/week/month、up、down、score*） | 前台 `Ajax::hits`（`index/controller/Ajax.php:152-203`，先读后写）、`Ajax::digg`（`:302-323`，setInc）、`Ajax::score`（`:347-379`，先读后写）；Provide 详情计数（`api/controller/Provide.php:143-150`，`detail_inc_hits` 开启时）；`updateToday`（`admin/controller/Vod.php:510-514`、`admin/controller/Collect.php:175`、`admin/controller/Make.php:87,245`）；**后台 `Vod::info` 表单本身带计数字段**（`admin/view/vod/info.html` 中有 22 处 hits/up/score），保存时整行覆盖；采集新增时按配置随机生成 hits（`configcollect.html:33-38`） |
| `mac_vod_search` | 前台搜索（`common/model/Vod.php:404` 起，搜索优化开启时写缓存）、采集（`Collect.php:431`）、新增视频（`Vod.php:725`）、后台清缓存（`admin/controller/Index.php:162`） |
| `mac_user` | 前台注册、登录、改资料、密保找回、邮件/短信找回、绑定和解绑、升级会员组、头像（`index/controller/User.php` 各动作，头像在 `common/model/Upload.php:209`）；购买观看权限扣积分（`index/controller/User.php:155`）；推广访问奖励（`User.php:848` 起，`index/controller/User.php:909`）；OAuth 回调建号与绑定（`index/controller/User.php:236-274`）；**每个前台请求的登录检查**在会员组到期时把 `group_id` 降为 2（`common/model/User.php:455-466`，由 `common/controller/All.php:131` 调用）；支付回调加积分（`common/model/Order.php:138`）；卡密充值（`Card.php:142`）；提现冻结（`Cash.php:180`）；后台 `User::info`（`admin/controller/User.php:142` → `User::saveData`）、`User::del`（`:173`）、`User::field`（`:193`）；后台会员列表触发批量到期降级（`admin/controller/User.php:19`）；数据库工具 |
| `mac_order/card/cash/plog/ulog/visit/msg` | 前台会员中心与 `ajax_ulog`、`ajax_buy_popedom`（`index/controller/User.php:47-170`）；支付回调（`index/controller/Payment.php:13-30` → `common/extend/pay/*` → `Order::notify`）；后台 Order/Card/Cash/Plog/Ulog/Visit 各控制器；数据库工具 |
| `mac_comment`、`mac_gbook` | 前台发表（`index/controller/Comment.php:103`、`Gbook.php:103`）；后台编辑（`admin/controller/Comment.php:60`、`Gbook.php:61`）；评论采集（`common/model/Collect.php:2433,2457`）；Receive 的 comment 接口；数据库工具 |
| 配置文件 | 系统配置 `System::config*` 写 `extra/maccms.php`（`admin/controller/System.php:126-719` 共 15 处）和 `route.php`（`:231`）；分类绑定写 `extra/bind.php`（`admin/controller/Collect.php:194,222`）；定时任务写 `extra/timming.php`（`admin/controller/Timming.php:34,53,76`，**`api/controller/Timming.php:42` 每次执行都会回写 runtime**）；播放器配置生成 `static/js/playerconfig.js`（`admin/controller/Base.php:94-102`、`System.php:675-690`） |

**带写入副作用、但可以用 GET 触发的入口**【静态】。这些入口用 `input()` 或 `mac_param_url()` 取参，代码里没有看到请求方法限制：`ajax/hits`、`ajax/digg`、`ajax/score`、`ajax/referer`（网址访问计数，`Ajax.php:250`）、`user/ajax_ulog`、`user/ajax_buy_popedom`（扣积分）、`user/visit`（推广奖励）、`api.php/provide/vod?ac=detail&ids=`（开启计数时）、`api.php/timming/index`。另外，前台的普通页面请求也不是绝对只读：登录检查可能写 `group_id`，搜索可能写 `mac_vod_search`。**影子流量和黄金样本采集都不能把这些请求当作只读。**

#### 3.2 阶段划分

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 | 先核实 01 第 10 节第 1～4 条安全问题在线上是否存在，存在则在 PHP 端修复（需用户确认）；补齐仓库缺失的 `vendor`、模板和脱敏配置；在**一次性的库副本**上生成黄金样本（第 5 节）；准备 PHP 侧写入守卫和配置导出脚本（待批准） | 样本集已产出；守卫在测试环境验证过 |
| 1 | 新系统上线**只读前台**：首页、分类、筛选、详情、播放、搜索、sitemap、rss | 黄金样本 URL 和页面数据一致；404/5xx 和蜘蛛抓取正常；新系统的库账号只有 SELECT 权限 |
| 2 | `openapi`：Provide 对外接口，协议逐字段对比 | 同一请求的新旧输出 diff 为空，允许字段顺序不同 |
| 3 | `collect` worker 接管**抓取**（推荐方案甲，见 3.3） | 影子期抓取结果 diff 为零或可以解释；PHP 侧采集入口已封住 |
| 4 | `member` + `interact`：会员、积分、订单、卡密、提现、评论、留言、支付回调，**连同它们的后台管理页一起**切换 | 资金类对账一致；PHP 侧对应入口已封住 |
| 5 | `content` 写入 + 其余 `admin`：内容编辑、计数、采集合并逻辑、Receive、系统配置、定时任务、管理员 | 后台逐功能清单验收；配置唯一来源切换完成 |
| 6 | 下线 PHP，进入阶段 C 的库结构优化 | — |

#### 3.3 各阶段写入方矩阵

"封堵"指用 3.4 节的手段让旧入口返回 403 或维护提示；"转发"指旧入口的请求由 nginx 送到新系统处理。

| 表 / 操作 | 阶段 0～2 | 阶段 3 | 阶段 4 | 阶段 5 起 | 切换时旧入口的处理 | 回滚恢复 |
|---|---|---|---|---|---|---|
| `mac_vod` 等内容字段 | PHP | **PHP**（新 worker 只抓取，写入经 PHP 入库端点，见方案甲） | PHP | 新系统 | 阶段 5：封堵后台 Vod/Art/… 写动作、Collect/Cj 执行、Receive、Database 的 sql/rep/import；Receive 的 URL 转发给新系统 | 按 3.6 节流程，停新写入 → 排空 → 对账 → 解除 PHP 封堵 |
| 内容计数字段 | PHP | PHP | PHP | 新系统（和内容字段一起切） | 阶段 5：`ajax/hits`、`digg`、`score`、`referer`、Provide 计数转发给新系统 | 同上；计数是近似值，允许丢失回滚窗口内的增量（需用户接受） |
| `mac_vod_search` | PHP | PHP | PHP | 新系统 | 阶段 1～4 新系统不写这张表，搜索结果缓存放在自己的 Redis | 新系统缓存直接丢弃 |
| `mac_user` 及会员财务表 | PHP | PHP | **新系统** | 新系统 | 阶段 4：封堵前台 `user/*`、`ajax_buy_popedom`、`ajax_ulog`、`user/visit`，并转发给新系统；支付回调路径转发；封堵后台 User/Group/Card/Order/Cash/Plog/Ulog/Visit 写动作（新后台同时提供这些页面）；Database 工具对这些表封堵 | 3.6 节流程；额外核对密码指纹（4.3）和支付回调在途 |
| `mac_comment`、`mac_gbook` | PHP | PHP | **新系统** | 新系统 | 阶段 4：前台发表、后台编辑、评论采集、Receive comment 一并转发或封堵 | 同上 |
| 配置文件 / `sys_config` | PHP 文件 | PHP 文件 | PHP 文件（会员组在库里，随阶段 4 切换） | `sys_config` | 见 3.5 节 | 见 3.5 节 |

**阶段 3 的两种方案**

- **方案甲（推荐）**：新 worker 负责抓取、解析、并发、限速、断点续采和过滤；**入库仍然调用 PHP 的合并逻辑** `Collect::vod_data`，所以 PHP 仍是 `mac_vod` 的唯一写入方。
  - 现有的 Receive（`api/controller/Receive.php:54`）调用 `vod_data([], $data, 0)`，传入的是空参数，不会套用采集源的 `filter`、`filter_from`、`opt`、`sync_pic_opt`（`Collect.php` 第 420 行起的 `vod_data` 依赖这些参数）。因此需要新增一个**仅内网、强鉴权**的入库端点，接收 `collect_id` 并套用该采集源的参数。这是 PHP 业务代码改动，待用户批准。
  - 提交入库必须**串行**。PHP 的查重是"先查后插"，MyISAM 没有事务，同一条视频并发提交可能产生重复记录。抓取可以并发，提交排队。
  - PHP 侧封堵：后台 `Collect::api`、`Collect::timing` 和 `Cj` 的采集执行动作；`timming.php` 中的采集任务设为停用。绑定、采集源和规则的编辑仍然留在 PHP 后台（配置来源见 3.5）。
  - 回滚：停 worker，等在途提交完成，解除 PHP 侧采集入口的封堵，恢复定时任务。因为写入方一直是 PHP，不需要数据对账。
- **方案乙**：阶段 3 就让新系统成为 `mac_vod` 的唯一写入方。这要求同时封堵后台 Vod 编辑、Receive、Cj、Database 工具，相当于把阶段 5 的内容后台提前，工作量和风险都更大，不推荐。

合并逻辑（inrule、uprule a～w）迁到新系统放在阶段 5。届时先做"影子合并"：新系统对同一批抓取数据算出合并结果但不写库，和 PHP 实际写入的结果做 diff。

**阶段 1 里，会员权限和收费播放怎么处理**
- 新前台只读：用只有 SELECT 权限的库账号，技术上保证不写库。
- 登录态：新系统按旧算法**只校验、不签发**会员 cookie（`user_check = md5(user_random-用户名-ID-)`，`common/model/User.php:446`）。校验失败时当作游客处理，**不像 PHP 那样回写 cookie**（`common/controller/All.php:136-138`），避免两边互相覆盖。
- 会员组到期：在内存里按旧规则计算有效会员组（`group_id>2` 且 `user_end_time` 已过期时按 2 处理，`User.php:455-466`），**不写库**。真正的降级写入仍由 PHP 在下一次 PHP 请求时完成，可见效果相同。
- 播放、下载、试看权限：新系统只读取 `mac_group.group_popedom`、`mac_ulog`（是否已购买）等表来判定。需要付费确认时，页面上的确认按钮仍然调用 PHP 的 `user/ajax_buy_popedom`，由 PHP 扣积分、写 `mac_ulog` 和 `mac_plog`。
- 登录、注册、退出、会员中心、`ajax/*`、评论、留言、支付回调：全部仍由 nginx 路由到 PHP。
- **设了观看密码的内容**依赖 PHP session（`common/controller/All.php:547-549`、`index/controller/Ajax.php:400-456`），新系统读不到，这类请求由新系统内部回源给 PHP 处理，或者在 nginx 层按规则留在 PHP。

#### 3.4 封住旧入口的手段

只靠 nginx 按 URL 拦截不可靠，原因是同一个动作有多种 URL 写法：PATHINFO、`?s=` 参数（`application/config.php:80` `var_pathinfo='s'`）、`route.php` 中的路由别名、多种后缀（`config.php:86`），而且后台入口文件名可以随意改（`_admin.php`）。建议组合使用：

1. **PHP 侧写入守卫（推荐，待批准）**：在已有的 `app_begin` 行为（`application/tags.php`、`common/behavior/Begin.php`）里，按路由解析之后的"模块/控制器/动作"对照一个封堵清单，命中就返回 403 或维护提示。路由解析之后再判断，就能覆盖所有 URL 写法。清单放在一个配置文件里，切换和回滚只改这个文件。
2. **nginx 路由**：负责把需要转发的路径送到新系统。它只是辅助，不作为唯一的封堵手段。
3. **数据库账号兜底**：MySQL 5.7 支持表级权限，可以收回 PHP 账号对"已归新系统所有"的表的 INSERT/UPDATE/DELETE 权限。这是最硬的保证，但 PHP 中剩下的任何隐式写入（例如登录检查的到期降级）都会失败，所以要在测试环境确认 PHP 侧已经没有对这些表的写入路径，再启用。
4. **数据库工具**（`admin/controller/Database.php` 的 sql、rep、import）能写任何表。从阶段 3 起，对这几个动作要么封堵，要么只在维护流程中由超级管理员使用。

#### 3.5 配置的唯一来源

| 时期 | 唯一来源 | 谁能改 | 新系统怎么拿到 |
|---|---|---|---|
| 阶段 0～4 | PHP 文件（`extra/*.php`、`route.php`；会员组、采集源等在库里的配置以库为准） | 只有 PHP 后台 | 一个只读的导出脚本（PHP CLI，`include` 配置文件后输出 JSON，不改业务代码）按文件修改时间增量导出，新系统热加载。阶段 3 新 worker 用的分类绑定 `bind.php`、采集参数 `collect.*` 都从导出结果读取，**worker 不写配置** |
| 阶段 5 起 | `sys_config` | 只有新后台。PHP 后台的 `System::config*`、`Collect::bind/clearbind`、`Timming` 的写动作全部封堵 | — |

- 阶段 5 起，新后台每次保存配置时，同时生成一份 PHP 文件格式的导出（与 `mac_arr2file` 的输出等价，包括 `route.php` 和 `playerconfig.js`），只写到一个暂存目录，**不覆盖** PHP 正在使用的文件。回滚时把这份导出替换到 PHP 目录，和回滚前的版本做 diff 确认后再解除封堵。
- 阶段 4 的会员组（`mac_group`）由新后台管理。PHP 用文件缓存保存会员组列表（`model('Group')->getCache`），新系统改了会员组之后，PHP 剩余页面看到的可能是旧缓存，需要同时清理 PHP 缓存或缩短缓存时间。
- 管理员权限：阶段 5 拆成 `admin_permission`，同时**回写** `admin_auth` 文本，以便回滚。只有新系统才有的权限码无法回写，回滚后这些权限会丢失，需要在回滚记录里列出。
- `timming.php` 的 runtime 字段由 `api.php/timming/index` 每次执行回写，所以停用 PHP 的定时任务时，要把对应任务设为停用，而不能只停外部 cron。

#### 3.6 写入方切换的回滚流程

适用于阶段 4、5（阶段 3 采用方案甲时，只需停 worker 并解除采集入口封堵）。**这里不承诺具体耗时**，耗时以演练结果为准。

1. **触发门槛**：对账出现无法解释的差异、核心流程（登录、购买、支付回调、发表评论）冒烟失败、错误率明显高于切换前的基线。具体阈值在演练时和用户一起确定。
2. **停新写入**：新系统对应模块切到维护或只读状态，停掉新 worker 中相关任务。
3. **排空在途**：等在途请求完成；支付回调在这段时间返回失败，让支付平台按其重试策略稍后重发（各渠道的重试策略待核实）。旧 `Order::notify` 是"先查状态后更新"，没有用条件更新（`common/model/Order.php:116-138`），并发重复回调可能重复加积分，所以**任何时刻只能有一方处理回调**；新系统要用 `WHERE order_status=0` 的条件更新，只在影响行数为 1 时加积分。
4. **核对**：订单、积分、卡密、提现的总额对账；切换窗口内新系统写入的记录逐条核对；密码指纹一致性（4.3）；配置导出与 PHP 现用文件的 diff。
5. **恢复旧方**：替换配置导出，解除 PHP 侧封堵和数据库权限限制，nginx 路由切回。
6. **验证**：用黄金用例冒烟（登录、退出、购买、测试订单回调、后台改会员），确认 PHP 缓存已刷新。

只读阶段（1、2）的回滚只需要 nginx 切回 PHP，前提是 PHP 实例一直在运行、配置没动过；切回后用黄金 URL 抽样验证。

**缓存一致性**：并行期内 PHP 用文件缓存（`maccms.php:50` `cache_type=file`），新系统无法让它失效。新系统给只读页面设置短 TTL。如果要让 PHP 后台改内容后主动通知新系统，需要给 PHP 加一个写"内容版本号"的钩子，属于业务代码修改，需要另外确认。

### 4. 核心数据与行为的兼容策略

#### 4.1 视频与分类
- **字段**：`mac_vod`、`mac_type` 原样读取，时间戳和 IP 的格式保持不变。
- **分类树**：`type_pid` 加 `type_mid`。`type_extend` 按 JSON 解析，键名保持不变。
- **分类模板字段**：`type_tpl*` 在新系统里映射为模板名，找不到时回落到默认模板。
- **点击、评分、顶踩**：旧代码中 hits 和 score 是"先读后写"（`Ajax.php:203,379`），并发时会丢计数；新系统接管后改用原子的 `UPDATE ... SET x=x+1`，这是有意的改进。日、周、月点击数的重置规则照 `updateToday` 实现。
- **查询**：列表和筛选条件要按 `Vod::listCacheData` 的参数逐项实现，包括 `type`、`class`、`area`、`lang`、`year`、`level`、`letter`、`order`/`by` 和 `rnd`。用黄金样本对比返回的 ID 序列。

#### 4.2 播放地址
- **存储格式不变**：`$$$` 分隔播放组，`#` 分隔集，`$` 分隔标题、地址和来源。
- **编解码**：新系统实现与 `mac_play_list` / `mac_play_list_one`（`common.php:1346-1455`）等价的函数，并用 PHP 生成的测试向量覆盖：空标题、缺少 `$`、server 前缀、播放器未启用时被过滤、按 sort 排序、结尾多出 `#`、含 CRLF。
- **前端契约**：播放页输出相同结构的 `player_aaaa`（flag、encrypt、trysee、points、link、link_next、link_pre、url、url_next、from、server、note、id、sid、nid）。`app.encrypt` 取 0、1、2 时的行为也要一致，这样 `static/js/player.js` 和 `static/player/*` 才能原样复用。
- **播放器配置**：`vodplayer.php`、`vodserver.php`、`voddowner.php` 在阶段 5 迁到 `sys_config`，新后台生成 `static/js/playerconfig.js`，格式与 `admin/controller/Base.php:85-103`、`System.php:675-690` 一致。
- **会员组权限**：播放和下载的权限检查（`group_popedom` 中的 3 播放、4 下载、5 试看）以及扣积分规则，阶段 1 只读判定、扣积分仍由 PHP 执行，阶段 4 起由 `member` 模块实现（见 3.3）。

#### 4.3 用户：密码、登录态、权限

**(1) 旧系统的密码写入和校验入口，以及各自的字节语义**【静态，需黄金向量确认】

`default_filter` 为空（`application/config.php:46`），所以 `input()` 拿到的是原始参数，各入口自己再做处理：

| 入口 | 位置 | 写入或比较的值 |
|---|---|---|
| 前台注册 | `common/model/User.php:124,180` | 存 `md5(trim(原值))`。但"非空"和"两次输入一致"的校验用的是 `htmlspecialchars(urldecode(trim(原值)))`（`:126-142`） |
| 前台登录 | `User.php:324,346` | 匹配 `md5(trim(原值))`，**或者**库里的值直接等于 `htmlspecialchars(urldecode(trim(原值)))`（即哈希登录漏洞） |
| 前台改资料时改密 | `User.php:297-299,315-316` → `saveData` `:77` | 旧密码接受 `md5(trim(原值))` 或 `md5(htmlspecialchars(urldecode(trim(原值))))`；新密码存 `md5(trim(user_pwd2))` |
| 密保问题找回 | `User.php:483,515` | 存 `md5(trim(原值))` |
| 邮件/短信找回 | `User.php:792,798,840` | 存 `md5(trim(原值))`；长度校验（≥6）用的是格式化之后的值 |
| 后台新增/编辑会员 | `admin/controller/User.php:142` → `User.php:77,87` | 存 `md5(POST 原值)`，**不做 trim** |
| OAuth 首次登录自动建号 | `index/controller/User.php:263-267` | 密码是 `time()`，即注册时刻的时间戳，可以被推算（新发现的风险，见 01 第 10 节） |
| 管理员 | `common/model/Admin.php:65,76`（保存）、`:124`（登录）、`admin/controller/Index.php:185`（锁屏解锁） | 都是 `md5(原值)`，不做 trim |
| 数据库工具 | `admin/controller/Database.php:237-265,287-314` | 可以直接改任何字段 |

所以"旧密码 = `md5(明文)`"只是近似说法。同一个用户输入的字节，在不同入口可能得到不同的存储值（例如首尾空格、`%xx`、`&<>"` 等字符）。**新系统的校验必须按"该账号的密码是从哪个入口写入的"无法得知这一前提来设计**：登录时按 `md5(trim(原值))` 校验（与旧登录主路径一致），另外对后台写入的"不 trim"情况补一个 `md5(原值)` 的候选。具体候选集合要用黄金测试向量确定，至少覆盖：首尾空格、`+` 与 `%20`、`%xx` 编码、`& < > " '`、中文和 4 字节字符、空字符串。**不兼容**"库里的哈希值本身可以当密码"的行为（01 第 10 节第 1 条），这是有意的差异。

**(2) 密码归属**
- 阶段 0～3：所有密码写入都在 PHP。新系统只读。
- 阶段 4 起：前台注册、改密、两种找回、后台新增/编辑会员（新后台）都归新系统；对应 PHP 入口封堵（3.3）。
- 管理员密码：阶段 5 前归 PHP，阶段 5 起归新后台。
- 无论哪个阶段，数据库工具都可能改 `user_pwd`，下面的失效规则必须能处理这种情况。

**(3) 渐进升级与失效规则（待实施）**
- 新系统校验旧密码成功后，计算 bcrypt 或 argon2id 写入 `user_pwd_v2`，同时把**当时的** `user_pwd` 值写入 `user_pwd_v2_src`（指纹）。
- 每次校验先看指纹：
  - `user_pwd_v2` 非空，且 `user_pwd_v2_src == 当前 user_pwd`：v2 有效，**只用 v2 判定**。v2 不通过就直接拒绝，不再尝试 md5。
  - `user_pwd_v2_src != 当前 user_pwd`：说明 PHP 或数据库工具改过密码，v2 已过期。**忽略 v2**，只按当前 `user_pwd` 校验；通过后重新生成 v2 和指纹。
  - 不允许"v2 校验失败后降级接受 md5"，也不允许"v2 过期时仍然接受旧密码"。
- 可选的数据库兜底：给 `mac_user` 加一个 `BEFORE UPDATE` 触发器，当 `user_pwd` 变化而 `user_pwd_v2` 没变时把 v2 置空。触发器属于库结构变更，需要评估权限和 binlog 设置后再决定。
- 并行期内，新系统改密时**同时写** `user_pwd`（按旧的 `md5(trim(原值))` 语义）、`user_pwd_v2` 和指纹，保证回滚到 PHP 后用户仍能用新密码登录。
- 回滚后：PHP 不认识 v2 字段，会直接改 `user_pwd`；之后再切回新系统时，指纹不一致，v2 自动作废，用户按当前 md5 登录后重新升级。
- PHP 下线后：所有账号都升级完（或者强制剩余账号走找回流程）才清空 `user_pwd`。

**(4) 登录态 cookie**

旧系统的会员登录态是**无状态 cookie**：`user_id`、`user_name`、`group_id`、`group_name`、`user_check`、`user_portrait`，有效期 30 天（`User.php:384-389`）；`user_check = md5(user_random-用户名-ID-)`，`user_random` 只在登录时更新（`User.php:368`）。cookie 属性来自 `application/config.php:220-235`：`path=/`，`domain` 为空（只对当前主机名生效），`secure=false`，`httponly` 为空。`static/js/home.js:596-602` 会在前端读取 `user_id`、`user_name`、`group_id`、`group_name`、`user_portrait`。

兼容条件：
- 并行期内，新系统签发的 cookie 必须**同名、同 path、同样不设 domain**。如果新系统设了 `Domain=.example.com`，浏览器会同时带上两个同名 cookie，PHP 可能读到错的那个。
- 值的编码要和 PHP 的 `setcookie` 一致（中文用户名会被 URL 编码；PHP 读取时做 `urldecode` + `htmlspecialchars`，`User.php:428-430`）。用黄金向量覆盖含中文、`&` 等字符的用户名。
- **注销**：旧系统的注销只是删除 cookie（`User.php:411-419`），不会让已经被复制走的 `user_check` 失效；改密也不会更新 `user_random`。新系统在注销和改密时**同时更新 `user_random`**，旧 PHP 校验时读的是库里的值，所以这个改动对 PHP 同样生效，并行期就可以做。
- 删除 cookie 时也要用相同的 path 和 domain，否则删不掉。

哪些加固可以在并行期做，哪些要等 PHP 下线：
| 加固项 | 并行期能否做 | 条件 |
|---|---|---|
| `user_check` 加 HttpOnly | 可以 | 前端 JS 不读 `user_check`（`home.js` 只读上面 5 个）；PHP 侧也要同步设置，需改 PHP 配置或代码，待批准 |
| `user_id` 等 5 个 cookie 加 HttpOnly | 不可以 | 会破坏 `home.js` 的前端逻辑，除非同时改前端 |
| 全部加 Secure | 视情况 | 全站已是 HTTPS 才能做，线上情况待核实 |
| 注销/改密时更新 `user_random` | 可以 | 见上 |
| 换成 HMAC 签名或服务端 session | 不可以 | PHP 无法校验，要等 PHP 下线 |

**(5) 第三方登录**：openid 登录只允许在 OAuth 回调里使用，**不兼容**旧代码"直接提交 openid 即可登录"的行为（01 第 10 节第 2 条）。OAuth 自动建号不再使用时间戳密码。

**(6) 会员组**
- `group_type` 和 `group_popedom` 原样解析。
- 会员到期降级规则照搬：`group_id>2` 且 `user_end_time` 已过期时降为 2（`User.php:362-365,455-466`）。阶段 1～3 只在内存里计算（3.3），阶段 4 起由新系统写库。

**(7) 管理员**
- 密码同样渐进升级，规则同 (3)。
- `admin_auth`（`,控制器/方法,` 格式的文本）由迁移脚本拆成权限码写入 `admin_permission`，保留"`admin_id=1` 是超级管理员"的语义；新旧权限码之间要有映射表，并回写 `admin_auth`（3.5）。
- 旧后台把整条管理员记录存进 PHP session，之后**不再回库校验**（`common/model/Admin.php:145-146,166-176`），所以在新后台停用某个管理员，不会让他已登录的 PHP 会话失效。阶段 5 切换时要同时封堵 PHP 后台，回滚时要求重新登录。
- 管理员 session 不能在新旧系统之间共享，切换时**需要重新登录**。

#### 4.4 采集配置
- `mac_collect` 原样读取，包括 `collect_url`、`type`、`mid`、`param`（base64）、`filter`、`filter_from` 和 `sync_pic_opt`。
- `extra/bind.php` 阶段 0～4 仍由 PHP 后台维护，新 worker 只读导出结果；阶段 5 由迁移脚本导入 `collect_bind`，`cjflag` 的算法保持 `md5(url)` 不变。
- `maccms.php` 中 `collect.*` 的 inrule、uprule、过滤词和随机规则，阶段 5 导入 `sys_config`。合并逻辑必须用"影子合并 + diff"验证（3.3）。
- `extra/timming.php` 中的定时任务迁到 worker 的调度表，触发接口要求鉴权（**不兼容**旧的无鉴权接口）。
- 新增能力：按资源站配置并发和限速、断点续采、TLS 证书校验（可按资源站关闭）、屏蔽内网网段的 SSRF 防护。

#### 4.5 旧 URL 与 SEO
- **前提**：拿到线上的 `maccms.php`（rewrite、view、path）和 `route.php`（待确认）。
- **URL 生成**：新系统实现与 `mac_url` 等价的生成器，包括 ID 模式（数字、拼音、`mac_alphaID`）、后缀隐藏、是否保留 `index.php`、分页规则（第 1 页不带页码）。
- **URL 解析**：同时支持旧的 PATHINFO 形式（例如 `/index.php/vod/detail/id/1.html`）、`?s=` 形式、路由形式（例如 `/voddetail/1.html`）和当前配置对应的形式。非规范形式统一 **301 跳转到规范 URL**，规范 URL 与旧站一致。
- **黄金样本**：在 PHP 环境的库副本上用 CLI 脚本遍历所有内容，调用 `mac_url_*` 导出 URL 清单，新系统必须 100% 生成一致。sitemap 和 rss 的输出格式也逐一对比。
- **监控**：切换后监控 404、5xx 和蜘蛛抓取情况。

#### 4.6 接口
- **Provide**：参数（ac、t、ids、wd、h、pg、pagesize、at、year、isend、from）、JSON 字段、XML 结构保持一致，`closed` 返回值也保留。IP 白名单改为只信任可信代理传来的真实 IP（**与旧版不同**）。如果线上开了 `detail_inc_hits`（待核实），阶段 2 的新 Provide 不能写计数，带 `ac=detail&ids=` 的请求留在 PHP，直到阶段 5。
- **Receive**：`pass` 校验的语义保留；阶段 5 起由新系统处理。
- **支付回调**：路径保持 `index.php/payment/notify`（POST，参数 `pay_type` 区分渠道，`index/controller/Payment.php:13-30`），或者在支付平台改配新的回调地址（待确认）。任何时刻只能由一方处理回调，条件更新规则见 3.6。
- **Timming**：改为需要鉴权（与旧版不同）。

#### 4.7 第三方插件与模板
- PHP 插件（fastadmin-addons）**不能复用**。现有的 `adminloginbg` 未启用，评估后可以不迁。如果线上还有其他插件，需要列出清单，逐个决定重写还是放弃（待确认）。
- 新系统用"模块内扩展点"（事件或钩子接口）替代插件机制，**不建议**在第一阶段做动态插件。
- **模板**有两条路，需要用户选择：
  - 甲：按新的模板引擎改写线上模板。工作量取决于模板页数，但可控。
  - 乙：实现一个 `{maccms:*}` 标签和常用 `mac_*` 函数的兼容层，或者做一个转换工具，以保留使用现成苹果CMS 模板的能力。前期成本高，模板中嵌入的任意 PHP 代码也无法兼容。

### 5. 验证手段（贯穿各阶段）

- **黄金样本**：在**一次性的库副本**上运行 PHP 导出以下内容（因为很多 GET 请求有写入副作用，见 3.1，不能对生产库或共享开发库跑）：
  - URL 清单
  - 列表和详情的 JSON（通过 Provide）
  - 播放串解析结果
  - 播放页的 `player_aaaa`
  - 会员组权限判定表
  - 密码和 cookie 的字节语义向量（4.3）
- **影子流量**：nginx 用 mirror 把请求复制一份发给新系统，记录差异，不返回给用户。约束：
  - 只复制 GET/HEAD，并且只复制已列入只读白名单的页面路由；3.1 节列出的有副作用的入口一律不复制。
  - 接收影子流量的新系统实例用只有 SELECT 权限的库账号，并关闭所有写入代码路径。
  - 方向只能是"真实请求给 PHP，复制品给新系统"，**不能把请求复制给 PHP**，否则 PHP 的计数、扣积分等副作用会执行两次。
  - 复制的请求带着用户 cookie，新系统的日志不能记录 cookie 和会员身份信息。
- **对账**：资金类切换前先跑一次对账基线（MyISAM 下历史数据可能已经不一致），切换后每日核对积分、订单、卡密、提现的总额。

### 6. 回滚策略汇总

| 类型 | 方式 | 前提与边界 |
|---|---|---|
| 只读路由（阶段 1、2） | nginx 切回 PHP，黄金 URL 抽样验证 | PHP 实例一直在运行、配置未改；耗时待演练 |
| 采集抓取（阶段 3 方案甲） | 停 worker，等在途提交完成，解除 PHP 采集入口封堵，恢复定时任务 | 写入方一直是 PHP，不需要数据回迁 |
| 写入方（阶段 4、5） | 按 3.6 节六步执行 | 必须停新写入、排空、对账后才恢复旧方；耗时待演练 |
| 配置（阶段 5 起） | 用新后台持续生成的 PHP 格式导出替换 PHP 目录，diff 后再解除封堵 | 只有新系统才有的配置项和权限码无法回写，需记录 |
| 密码 | PHP 继续用 `user_pwd`；v2 靠指纹自动作废 | 见 4.3 (3) |
| 引擎转换 | 从备份恢复 + binlog 补增量，或窗口内停写 | 未演练前不把 `ALTER ENGINE` 当回滚手段；阶段 4 之后不再支持回到 MyISAM |
| 字符集转换 | 从备份恢复 + 补增量 | 写入 4 字节字符后不能无损回转 utf8，需用户事先确认 |
| PHP 下线后（阶段 6） | 依靠备份和反向迁移脚本 | 反向脚本在阶段 C 设计时编写 |

### 7. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 线上模板和配置缺失，无法确定 URL 和页面行为 | 前台无法验收，SEO 有损失 | 阶段 0 必须拿到脱敏后的线上配置和模板 |
| `mac_url` 和查询逻辑细节多（3000 行的 `common.php`） | URL 或列表不一致 | 黄金样本加影子流量逐项对比 |
| 采集合并规则复杂（uprule a～w） | 数据被覆盖或重复 | 阶段 3 合并仍由 PHP 执行；阶段 5 影子合并加 diff，按资源站分批切换 |
| 旧入口封堵不全，两个系统同时写同一张表 | 数据覆盖、重复入账 | 路由解析后的 PHP 守卫 + 数据库表级权限兜底；切换前按 3.1 清单逐项测试 |
| 很多 GET 请求有写入副作用 | 影子流量或样本采集意外改数据 | 白名单复制、只读库账号、样本只在一次性副本上采集 |
| 密码字节语义不一致 | 部分用户切换后无法登录 | 黄金向量；保留候选校验集合；找回流程兜底 |
| MyISAM 没有事务，资金类数据可能已经不一致 | 对账出现差异 | 迁移前先跑对账基线；切换到 InnoDB 后用事务 |
| 引擎和字符集转换不可完全回退 | 回滚时丢数据或丢字符 | 独立窗口、演练、binlog 或停写；事先由用户确认不可逆边界 |
| 并行期缓存不一致 | 前台看到旧数据，会员组权限不同步 | 短 TTL；必要时加内容版本号钩子（待批准） |
| 团队技能、流量、预算未知 | 选型或工期失准 | 先回答 02 第 5 节的问题；用一个 2～3 周的 PoC（只读详情页加采集 worker）验证 |
| 市面上现成的苹果CMS 模板从此不能直接使用 | 长期改版成本上升 | 决策时由用户明确接受，或选择兼容层方案（乙） |
| 本仓库中发现的安全问题，线上是否同样存在未核实 | 若存在，重构期间账号可被接管 | 先核实线上；确认存在则阶段 0 在 PHP 修复（需用户确认） |

### 8. 待用户确认的决策点

1. 语言：Go 还是 Java（见 02）。是否考虑"继续用 PHP 并加固"的路线？
2. 模板走甲（改写）还是乙（兼容层）？
3. 功能范围：哪些模块要砍掉（例如 Cj 规则采集、站群、静态生成、卡密、提现）。
4. 是否同意阶段 0 先核实并在 PHP 修复 01 第 10 节第 1～4 条安全问题。
5. 是否同意为切换增加 PHP 侧写入守卫和内网入库端点（都是 PHP 业务代码改动）。
6. 阶段 3 采用方案甲还是方案乙。
7. MyISAM 转 InnoDB、utf8mb4 转换的时间窗口，以及是否接受其中的不可逆边界。
8. 能否提供脱敏后的线上配置（rewrite/view/path、route.php、bind.php、timming.php、播放器配置、`api.vod.detail_inc_hits`）和模板；线上是否开启 binlog、是否全站 HTTPS。
