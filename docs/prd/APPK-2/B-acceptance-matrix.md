# APPK-2 附表 B：验收矩阵（前台桌面、手机、后台模板管理、静态生成）

> 属于 `docs/prd/APPK-2.md`（v9；v9 未改动本附表；v7 增加了下面的条件说明，v8 只增加下一段“比较规则”的指引，数据与计数未改）。本附表固定 KPI-1 与 AC-5～AC-10 的分母与样本。冲突时以主文件为准。
>
> **比较规则（v8）**：KPI-1 与 AC-5～AC-10 的每个样本怎么比（比较哪些项、归一化步骤、禁止忽略的差异、允许豁免的字段）、做对照的 PHP 运行时，都以 [附表 F](F-comparison-baseline.md) 为准；本附表只固定样本集合与分母，不另写比较规则。
>
> **条件说明（v7）**：本附表中“缺”的 41 个桌面页面、57 个手机页面的样本口径（只取 N 样本、与 PHP 的“模板文件不存在”输出对照，即主文件 BR-12）是 **OP-16 答 a“本期不补”时的条件性写法，OP-16 仍待发起人确认**；OP-15（模板目录配置）也待架构设计确认。二者关闭前，本附表不得作为范围确认或 G1 放行的依据，也不得据此派工；OP-16 答 b（要补）时升版本并重算本附表。
>
> **v6 变化**：模板包固定为 MacCMS 官方 `v2020.1000.1035` 的 `default_pc`（桌面）+ `default_wap`（手机），文件清单见 [附表 E](E-template-package.md)；PHP 对照代码固定为仓库 `php/` 的 `2023.1000.3051`，二者版本不同。120 条固定模板名由 `php/` 控制器代码提取，与模板包无关，所以编号 F-001～F-120 不变；但官方包并不提供全部 120 个页面文件，所以每条增加“桌面包”“手机包”两列（有/缺），样本数据随之重算。模板目录怎么配置不在本附表假设，见主文件 OP-15。

## B.1 前台桌面页面矩阵（FR-5，分母固定）

**提取规则（固定）**：模板名取 `php/application/index/controller/*.php` 与 `php/application/common/controller/All.php` 中所有 `fetch('<名>')` / `label_fetch('<名>')` 的字面名，加上 `mac_tpl_fetch(<模块>, …, <默认名>)` 的默认名（`type`/`show`/`detail`/`play`/`down`），去重。另有运行期动态名，见 B.1.2。v1 写的“111 条”无法由固定规则复现，v2 以本表为准。

### B.1.1 固定模板名（120 条）与样本

**样本规则（固定，v6 增补“包内有/缺”）**：每条模板名必有 **N 样本**（正常数据，1 个）；标 `E` 的另有 **E 样本**（空结果或内容不存在，期望旧版的空列表/404 表现）；标 `P` 的另有 **P 样本**（未登录或会员组权限不足，期望旧版的跳转/提示表现）。`E` 规则：分类/列表、搜索、详情、ajax 片段、RSS、播放/下载类，以及 `user/` 下的 plays、downs、favs、ulog、plog、cash、orders、cards、comment、gbook、reward 列表页；`P` 规则：`vod/play`、`vod/down`、`vod/player`、`vod/downer`、`vod/detail`、`art/detail`、各 `*_pwd`、`vod/copyright`，以及 `user/` 下除 login、reg、findpass、findpass_msg、ajax_login、ajax_info 之外的全部页面。**v6 增补**：模板名在 `default_pc/html` 里缺文件（“桌面包”列为“缺”）时，只取 1 个 N 样本，期望输出 = 同一请求下 PHP 对“模板文件不存在”的输出，不取 E、P 样本（“桌面实际样本”列即为最终取样类型）；模板名在包内有文件时，按“样本类型（按页面类别）”列取 N、E、P。手机侧见 B.2。

**KPI-1 分母（桌面固定部分）= 188 个样本** = N 120 + E 41 + P 27（v5 为 226 = N 120 + E 73 + P 33；E 少 32、P 少 6，是因为 41 个页面名在 `default_pc/html` 里缺文件，只留 N 样本。计数由脚本按“桌面实际样本”列统计：N 行 120、含 E 的行 41、含 P 的行 27）。每个样本由“请求 URL + 数据状态 + 期望 PHP 输出”组成，样本数据由一次性库的合成数据产生；样本清单文件 `docs/test/APPK-2/sample-desktop.md` 是 AC-5 的前置产物（PRE-3），其行数必须等于固定部分 188 加 B.1.2 的样本数 36（F-D1 2 + F-D2 2 + F-D3 32），共 224，每个“F-编号 + 样本类型”占一行；行数不符则 AC-5 不可执行。

| 编号 | 模板名 | 类别 | 来源 | 样本类型（按页面类别） | 桌面包 `default_pc/html` | 桌面实际样本 | 手机包 `default_wap/html` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-001 | `actor/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-002 | `actor/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-003 | `actor/ajax_show` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-004 | `actor/confirm` | 提示 | 字面名 | N | 缺 | N | 缺 |
| F-005 | `actor/detail` | 详情 | 动态默认名 | NE | 有 | NE | 缺 |
| F-006 | `actor/index` | 模块首页 | 字面名 | N | 有 | N | 缺 |
| F-007 | `actor/rss` | RSS | 字面名 | NE | 有 | NE | 缺 |
| F-008 | `actor/search` | 搜索 | 字面名 | NE | 缺 | N | 缺 |
| F-009 | `actor/show` | 分类/列表 | 动态默认名 | NE | 有 | NE | 缺 |
| F-010 | `actor/type` | 分类/列表 | 动态默认名 | NE | 缺 | N | 缺 |
| F-011 | `art/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-012 | `art/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-013 | `art/ajax_show` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-014 | `art/confirm` | 提示 | 字面名 | N | 有 | N | 有 |
| F-015 | `art/detail` | 详情 | 动态默认名 | NEP | 有 | NEP | 有 |
| F-016 | `art/detail_pwd` | 观看密码 | 字面名 | NP | 有 | NP | 有 |
| F-017 | `art/index` | 模块首页 | 字面名 | N | 有 | N | 缺 |
| F-018 | `art/rss` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-019 | `art/search` | 搜索 | 字面名 | NE | 有 | NE | 有 |
| F-020 | `art/show` | 分类/列表 | 动态默认名 | NE | 缺 | N | 缺 |
| F-021 | `art/type` | 分类/列表 | 动态默认名 | NE | 有 | NE | 有 |
| F-022 | `comment/ajax` | ajax 片段 | 字面名 | NE | 有 | NE | 有 |
| F-023 | `comment/index` | 其他 | 字面名 | N | 有 | N | 有 |
| F-024 | `gbook/ajax` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-025 | `gbook/index` | 其他 | 字面名 | N | 有 | N | 有 |
| F-026 | `gbook/report` | 其他 | 字面名 | N | 有 | N | 有 |
| F-027 | `help/index` | 其他 | 字面名 | N | 缺 | N | 缺 |
| F-028 | `index/index` | 其他 | 字面名 | N | 有 | N | 有 |
| F-029 | `map/index` | 其他 | 字面名 | N | 有 | N | 有 |
| F-030 | `note/index` | 其他 | 字面名 | N | 缺 | N | 缺 |
| F-031 | `plot/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-032 | `plot/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-033 | `plot/detail` | 详情 | 字面名 | NE | 有 | NE | 有 |
| F-034 | `plot/index` | 模块首页 | 字面名 | N | 有 | N | 有 |
| F-035 | `plot/rss` | RSS | 字面名 | NE | 缺 | N | 缺 |
| F-036 | `plot/search` | 搜索 | 字面名 | NE | 缺 | N | 缺 |
| F-037 | `public/browser` | 公共提示 | 字面名 | N | 有 | N | 有 |
| F-038 | `public/close` | 公共提示 | 字面名 | N | 有 | N | 有 |
| F-039 | `public/jump` | 公共提示 | 字面名 | N | 有 | N | 有 |
| F-040 | `public/verify` | 公共提示 | 字面名 | N | 缺 | N | 缺 |
| F-041 | `role/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-042 | `role/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-043 | `role/ajax_show` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-044 | `role/detail` | 详情 | 动态默认名 | NE | 有 | NE | 缺 |
| F-045 | `role/index` | 模块首页 | 字面名 | N | 有 | N | 缺 |
| F-046 | `role/rss` | RSS | 字面名 | NE | 缺 | N | 缺 |
| F-047 | `role/search` | 搜索 | 字面名 | NE | 缺 | N | 缺 |
| F-048 | `role/show` | 分类/列表 | 字面名 | NE | 有 | NE | 缺 |
| F-049 | `rss/baidu` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-050 | `rss/bing` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-051 | `rss/google` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-052 | `rss/index` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-053 | `rss/sm` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-054 | `rss/so` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-055 | `rss/sogou` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-056 | `search/index` | 其他 | 字面名 | N | 缺 | N | 缺 |
| F-057 | `topic/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-058 | `topic/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-059 | `topic/detail` | 详情 | 动态默认名 | NE | 有 | NE | 有 |
| F-060 | `topic/index` | 模块首页 | 字面名 | N | 有 | N | 有 |
| F-061 | `topic/rss` | RSS | 字面名 | NE | 缺 | N | 缺 |
| F-062 | `topic/search` | 搜索 | 字面名 | NE | 缺 | N | 缺 |
| F-063 | `user/ajax_info` | 会员中心 | 字面名 | N | 有 | N | 有 |
| F-064 | `user/ajax_login` | 会员中心 | 字面名 | N | 有 | N | 有 |
| F-065 | `user/bind` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-066 | `user/buy` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-067 | `user/cards` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-068 | `user/cash` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-069 | `user/comment` | 会员中心 | 字面名 | NEP | 缺 | N | 缺 |
| F-070 | `user/downs` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-071 | `user/favs` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-072 | `user/findpass` | 会员中心 | 字面名 | N | 有 | N | 有 |
| F-073 | `user/findpass_msg` | 会员中心 | 字面名 | N | 有 | N | 有 |
| F-074 | `user/gbook` | 会员中心 | 字面名 | NEP | 缺 | N | 缺 |
| F-075 | `user/index` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-076 | `user/info` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-077 | `user/login` | 会员中心 | 字面名 | N | 有 | N | 有 |
| F-078 | `user/order_info` | 会员中心 | 字面名 | NP | 缺 | N | 缺 |
| F-079 | `user/orders` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-080 | `user/pay` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-081 | `user/payment_weixin` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-082 | `user/plays` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-083 | `user/plog` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-084 | `user/popedom` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-085 | `user/portrait` | 会员中心 | 字面名 | NP | 缺 | N | 缺 |
| F-086 | `user/reg` | 会员中心 | 字面名 | N | 有 | N | 有 |
| F-087 | `user/reward` | 会员中心 | 字面名 | NEP | 有 | NEP | 有 |
| F-088 | `user/ulog` | 会员中心 | 字面名 | NEP | 缺 | N | 缺 |
| F-089 | `user/unbind` | 会员中心 | 字面名 | NP | 缺 | N | 缺 |
| F-090 | `user/upgrade` | 会员中心 | 字面名 | NP | 有 | NP | 有 |
| F-091 | `vod/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-092 | `vod/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-093 | `vod/ajax_show` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-094 | `vod/confirm` | 提示 | 字面名 | N | 有 | N | 有 |
| F-095 | `vod/copyright` | 提示 | 字面名 | NP | 有 | NP | 有 |
| F-096 | `vod/detail` | 详情 | 动态默认名 | NEP | 有 | NEP | 有 |
| F-097 | `vod/detail_pwd` | 观看密码 | 字面名 | NP | 有 | NP | 有 |
| F-098 | `vod/down` | 播放/下载 | 动态默认名 | NEP | 有 | NEP | 有 |
| F-099 | `vod/downer` | 播放/下载 | 字面名 | NEP | 有 | NEP | 有 |
| F-100 | `vod/downer_pwd` | 观看密码 | 字面名 | NP | 有 | NP | 有 |
| F-101 | `vod/index` | 模块首页 | 字面名 | N | 有 | N | 缺 |
| F-102 | `vod/play` | 播放/下载 | 动态默认名 | NEP | 有 | NEP | 有 |
| F-103 | `vod/player` | 播放/下载 | 字面名 | NEP | 有 | NEP | 有 |
| F-104 | `vod/player_pwd` | 观看密码 | 字面名 | NP | 有 | NP | 有 |
| F-105 | `vod/plot` | 其他 | 字面名 | N | 有 | N | 有 |
| F-106 | `vod/role` | 其他 | 字面名 | N | 有 | N | 缺 |
| F-107 | `vod/rss` | RSS | 字面名 | NE | 有 | NE | 有 |
| F-108 | `vod/search` | 搜索 | 字面名 | NE | 有 | NE | 有 |
| F-109 | `vod/show` | 分类/列表 | 动态默认名 | NE | 有 | NE | 有 |
| F-110 | `vod/type` | 分类/列表 | 动态默认名 | NE | 有 | NE | 有 |
| F-111 | `website/ajax_detail` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-112 | `website/ajax_search` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-113 | `website/ajax_show` | ajax 片段 | 字面名 | NE | 缺 | N | 缺 |
| F-114 | `website/confirm` | 提示 | 字面名 | N | 缺 | N | 缺 |
| F-115 | `website/detail` | 详情 | 动态默认名 | NE | 有 | NE | 缺 |
| F-116 | `website/index` | 模块首页 | 字面名 | N | 有 | N | 缺 |
| F-117 | `website/rss` | RSS | 字面名 | NE | 有 | NE | 缺 |
| F-118 | `website/search` | 搜索 | 字面名 | NE | 有 | NE | 缺 |
| F-119 | `website/show` | 分类/列表 | 动态默认名 | NE | 有 | NE | 缺 |
| F-120 | `website/type` | 分类/列表 | 动态默认名 | NE | 有 | NE | 缺 |

### B.1.2 运行期动态模板名

| 编号 | 动态名 | 样本（固定） |
| --- | --- | --- |
| F-D1 | `label/<文件名>`（`Label.php:22`） | 模板包 `label/` 目录下**每个** `.html` 文件 1 个 N 样本；另 1 个非法样本：文件名含 `/` 或文件不存在，期望旧版“非法请求”提示。v6 起模板包 `label/` 目录里只有 `rank.html`（`default_pc/html/label/rank.html` 与 `default_wap/html/label/rank.html` 各 1 个），桌面样本 = 1 个 N 样本（`label/rank`）+ 1 个非法样本，分母 = 1 + 1 = 2 |
| F-D2 | `public/<page_404 配置名>`（`All.php:112`） | 2 个样本：`app.page_404` 为空（用 `public/jump`）；`app.page_404` 设为 `default_pc/html` 内另一个存在的文件名（如 `public/msg`，具体文件由设计单在 E.3 清单内选定） |
| F-D3 | 内容自带模板名覆盖（`mac_tpl_fetch` 的 `*_tpl` 字段） | 固定 16 个覆盖点：vod 5（`type_tpl`、`type_tpl_list`、`vod_tpl`、`vod_tpl_play`、`vod_tpl_down`）、art 3（`type_tpl`、`type_tpl_list`、`art_tpl`）、actor 3（`type_tpl`、`type_tpl_list`、`actor_tpl`）、website 3（`type_tpl`、`type_tpl_list`、`website_tpl`）、role 1（`role_tpl`）、topic 1（`topic_tpl`）；每个覆盖点 2 个样本（设为 `default_pc/html` 内另一个存在的文件名；清空），共 32 个样本；清空后的默认文件在桌面包里缺失时，期望输出 = PHP 对“模板文件不存在”的输出（样本数不变） |

**内置视图（不在模板包里，随程序）**：`php/application/index/view/index/index.html`、`public/jump.html`、`public/msg.html` 三个文件；由附表 C 登记簿相关行验收，不计入 KPI-1 分母。

## B.2 手机模板矩阵（FR-8）

- **页面样本**：B.1.1 的每条模板名各取其 N 样本，在手机 UA 且 `mob_status` 与 `site_wapurl`/Host 满足手机条件下重放一次，对照手机模板包 `default_wap`（目录配置见主文件 OP-15）。分母 = 120，其中“手机包”列为“有”的 63 个对照渲染结果，为“缺”的 57 个对照 PHP 的“模板文件不存在”输出。手机侧 `default_wap/html` 没有 `ads/` 目录（官方包只有 `default_pc/ads/aaa.js`），所以不设手机广告样本。

- **模板选择样本**：`mob_status` ∈ {0,1,2} × UA ∈ {手机,桌面} × Host ∈ {等于 `site_wapurl`, 不等于} = 12 个组合，期望值取 `SiteTemplateSelectionGoldenTest` 的黄金结果。分母 = 12。

- **语言包样本**：`application/lang/zh-cn.php` 与 `zh-tw.php` 各 1773 个键（两个文件键集合完全相同，源码核实），每个键 1 个样本 = **3546** 个 `lang(key)` 输出对照。

## B.3 后台模板管理矩阵（FR-7，`admin/controller/Template.php`）

旧控制器共 6 个 public 方法：`__construct`、`index`、`ads`、`info`、`del`、`wizard`。固定验收用例（分母 = 10）：

| 编号 | 方法 | 用例 | 期望 |
| --- | --- | --- | --- |
| W-01 | `index` | 列出模板目录（模板目录与子目录各 1 个） | 文件名、大小、修改时间与 PHP 列表一致 |
| W-02 | `ads` | 列出广告目录 `ads_dir` 下文件 | 与 PHP 一致 |
| W-03 | `info` | 打开 1 个 `.html` 文件编辑页 | 内容与文件一致 |
| W-04 | `info` | 保存修改（合法内容）后刷新对应前台页 | 不重启即生效（原子替换，BR-6） |
| W-05 | `info` | 保存含 `<?`/`php`/`eval` 的内容（旧过滤规则命中项） | 被拒绝，文件不变 |
| W-06 | `info` | 路径含 `../`、扩展名不在白名单（`html/htm/js/xml` 之外） | 被拒绝 |
| W-07 | `info` | 两个管理员并发保存同一文件 | 后保存者收到冲突提示（B15 已有约定） |
| W-08 | `del` | 删除 1 个文件 / 删除含 `../` 的路径 | 前者成功，后者被拒绝 |
| W-09 | `wizard` | 生成 vod 标签片段 | 输出为 `{maccms:vod …}` 语法 |
| W-10 | `全部` | 无 `template/*` 权限的管理员访问以上 5 个方法 | 全部拒绝，与 PHP 一致 |

## B.4 静态生成矩阵（FR-6，`admin/controller/Make.php`）

旧控制器共 11 个 public 方法（含 `__construct`，其余 10 个为生成入口）。v1 写“11 个生成方法”不准确，v2 以此表为准。固定验收用例（分母 = 12）：

| 编号 | 方法 | 生成对象 | 用例 |
| --- | --- | --- | --- |
| K-01 | `opt` | 生成选项页（选择要生成的内容） | 页面字段与 PHP 一致 |
| K-02 | `make` | 统一入口（按参数分发） | 分发到下面各方法，参数集合与 PHP 一致 |
| K-03 | `index` | 首页 | 路径与内容与 PHP 一致 |
| K-04 | `map` | 站点地图 | 同上 |
| K-05 | `rss` | RSS 文件（baidu/google/so/sogou/bing/sm） | 同上 |
| K-06 | `type` | 分类页（含分页） | 1 个分类，首页、中间页、最后页 |
| K-07 | `topic_index` | 专题首页 | 同上 |
| K-08 | `topic_info` | 专题详情 | 同上 |
| K-09 | `info` | 内容详情（vod/art/actor/role/website 各 1 个） | 路径与内容与 PHP 一致 |
| K-10 | `label` | 自定义标签页（`label/` 下 1 个文件） | 同上 |
| K-11 | `全部` | 故意让 1 个模板渲染出错 | 该页进入失败列表，其余继续（沿用旧版） |
| K-12 | `全部` | 生成后各内容表 `*_time_make` 更新 | 写入列恰为 vod/art/topic/actor/role/website 的 `*_time_make`（见附表 D 的 D-19） |

## B.5 其余后台功能与 API

不在 B.1～B.4 内的后台功能、API、安装向导，**全部由附表 C 的登记簿验收**（分母 = 379 行）；它们不做“PHP/Java 页面逐字节对照”，按 AC-14、AC-19 判定。
