# 首批前台页面与旧 URL 兼容清单（对接需求）

> 本文基于仓库 `D:\Ai\MACCMS\src\maccms` 的旧 PHP 代码静态核对，为 MACC-5 交付物。后端正式 OpenAPI 契约由 Claude 后端工程师另行交付（`docs/contracts/content-read.openapi.yaml`），本文不修改契约。
>
> 证据标注：
> - 【代码】：旧代码中可直接核对的行为，给出文件与行号（行号基于当前 worktree）。
> - 【缺模板】：仓库前台模板 `template/` 只有 0 字节文件，无法实测渲染结果（含 canonical、页面结构、样式），只按控制器与配置推断。
> - 【待验证】：行为依赖线上配置或真实数据，本文不采样、不访问线上、不对共享开发库发送 PHP 请求。

## 0. 范围与边界

首批页面：首页、分类/筛选列表、视频详情、搜索、播放。

MACC-4 首批后端**只交付分类与视频公开元数据 API**（不提供未授权播放/下载地址）。以下能力在首批**仍归 PHP 处理**：登录、注册、退出、会员中心、评论、留言、积分购买、支付回调、观看密码、计数（hits/digg/score）、下载。带观看密码或会员/收费权限的播放路径不得用公开 API 绕过（见第 4 节）；播放页继续由 PHP 渲染，不把「公开 API 没有播放地址」视为缺陷。只有经过授权设计并单独验收后才改播放链路。

## 1. 页面级输入、数据需求与状态

### 1.1 首页

| 项 | 内容 | 依据 |
|---|---|---|
| 入口 | `GET /`（PATHINFO 下为 `index/index`） | 【代码】`application/index/controller/Index.php:11` |
| 输入 | 仅分页 `page`（路由别名 `index-<page?>` 时） | 【代码】`application/common.php:1757-1768`（`mac_url` 的 `index/index` 分支） |
| 数据需求 | 站点 SEO/标题（`config.site`）、导航分类树、推荐/最新视频列表（由模板标签 `maccms:vod`/`maccms:type` 决定）、搜索热词 | 【代码】`application/common/controller/All.php:58-96`（`label_maccms`）；`application/common/taglib/Maccms.php:24`（`vod` 标签属性行）【缺模板】具体区块与字段由模板决定，待模板取得后核对 |
| 状态 | 站点关闭时输出 `public/close` 并 `exit` | 【代码】`application/index/controller/Base.php`（`check_site_status`，构造内调用） |
| 隐式写入（适用范围） | 登录会员组到期时，`label_user → User::checkLogin` 执行 `UPDATE group_id=2`。**适用范围是所有继承 `index/Base` 的前台页面**（Base 构造 :12-19 调用 `label_user`），不是只有首页 | 【代码】`application/index/controller/Base.php:12-19`；`application/common/controller/All.php:118-145`（`label_user`）；`application/common/model/User.php:455-466`（到期 UPDATE） |

### 1.2 分类/筛选列表

| 项 | 内容 | 依据 |
|---|---|---|
| 分类页 | `vod/type`（`application/index/controller/Vod.php:17-21`，:19 调 `label_type`），输入 `id`（type_id / type_en / 编码 id，形态由 `rewrite.type_id` 决定；`mac_label_type` 依赖 Type 模型对数字 ID 与 `type_en` 的查找） | 【代码】`application/index/controller/Vod.php:17-21`；`application/common/model/Type.php:373-386`（`getCacheInfo`）；`application/extra/maccms.php:495`（`rewrite.type_id`） |
| 筛选页 | `vod/show`，输入 `id` + `area/by/class/lang/letter/level/order/page/state/tag/year` 等 | 【代码】`application/index/controller/Vod.php:23-28`；`application/common.php`（`mac_param_url`）；路由别名 `vodshow/<id>-<area?>-...` |
| 数据需求 | 分类信息（`mac_type`：`type_id/type_name/type_en/type_pid/type_key/type_des/type_title/type_pic` 等）、列表分页、筛选条件回显 | 【代码】`application/common/controller/All.php:166-186`（`label_type`）；`application/install/sql/install.sql`（`mac_type`） |
| 权限 | 进入前检查 `check_user_popedom(type_id, 1)`，无权限输出错误并跳 `user/index` | 【代码】`application/common/controller/All.php:180-185` |
| 分页 | `mac_page_param`，`page_sp` 默认 `_`，`page_url` 由 `mac_url` 生成 | 【代码】`application/common.php:517-523` 起（`mac_page_param`） |
| 空结果/404 | **分类查不到**时 `label_type` 返回错误信息（`$this->error(lang('controller/get_type_err'))`，ThinkPHP error 跳转页）；**详情缺失**才走 `page_error`（输出 404 模板并 `exit`）。两者行为不同，见 1.3 与 3.3 对新 API 404 目标的分开描述 | 【代码】`application/common/controller/All.php:177-178`（分类 error）；`application/common/controller/All.php:391-393`（详情 page_error）；`application/common/controller/All.php:98` 起（`page_error`） |

注意：旧行为中「分类不存在」与「详情不存在」的响应形态不同（error 跳转页 vs 404 模板），不能写成统一走 `page_error`。

### 1.3 视频详情

| 项 | 内容 | 依据 |
|---|---|---|
| 入口 | `vod/detail`，输入 `id`（vod_id / vod_en / 编码 id） | 【代码】`application/index/controller/Vod.php:55-64`（`detail`）；`application/common.php:2715-2732`（`mac_label_vod_detail`） |
| 数据需求 | `mac_vod` 公开元数据（见 3.2）；所属分类（含父级）；剧情分集（`vod_plot*`）。**播放源结构（vod_play_from/url 解析结果）属 PHP 播放专属，见 3.2 C 档** | 【代码】`application/install/sql/install.sql`（`mac_vod`）；`application/common/controller/All.php:385-418`（`label_vod_detail`） |
| 过渡分支 | `vod_copyright==1 && config.app.copyright_status==2` → `vod/copyright`（`application/index/controller/Vod.php:58-60`）；`vod_pwd` 非空且 session 未通过 → `vod/detail_pwd`（:61-63）；详情权限检查 `check_user_popedom(type_id, 2)`（在 `label_vod_detail` 内，view<2 时执行）。`vod_copyright==1` 单独只说明「有版权标记」，**不等于内容不可用**：详情/play/player 的版权模板分支分别要求 `copyright_status` 为 2/3/4（`application/index/controller/Vod.php:58-60`、:89-91、:98-100） | 【代码】`application/index/controller/Vod.php:55-64`；`application/common/controller/All.php:407-413`；`check_user_popedom` 在 `application/index/controller/Base.php:92-100` 起（不在 application/common/controller/All.php） |
| 不可见内容 | 查询强制 `vod_status=1`；状态非 1 视同「不存在」（走 `page_error`） | 【代码】`application/common.php:2727`（`$where['vod_status']=['eq',1]`）；`application/common/model/Vod.php` |
| 评论 | 详情/播放页经 `label_comment` assign `comment` 配置（配置读取/赋值在 `application/common/controller/All.php:149-152`；`label_vod_detail` 中 :416 调用 `label_comment`），是否开启评论由模板渲染 | 【代码】`application/common/controller/All.php:149-152,416` |

### 1.4 搜索

| 项 | 内容 | 依据 |
|---|---|---|
| 入口 | `vod/search`（及 `vod/ajax_search`），输入 `wd/actor/area/by/class/director/lang/letter/level/order/page/state/tag/year` 等 | 【代码】`application/index/controller/Vod.php:38-55` |
| 限制 | 搜索频率（`search_timespan`）、关键词长度（`search_len`）、可选验证码（`search_verify`） | 【代码】`application/index/controller/Base.php`（`check_search`）；`application/common.php`（`mac_search_len_check`） |
| 数据需求 | 与列表页相同，加关键词回显/高亮（高亮方式【缺模板】） | 【代码】同上 |
| 过滤 | `mac_filter_words`、`mac_escape_param`（围墙过滤，`wall_filter` 开启时） | 【代码】`application/common/controller/All.php:155-165` |
| 控制器位置 | 搜索在 `Vod` 控制器内；**不存在独立的 `application/index/controller/Search.php`** | 【代码】`application/index/controller/Vod.php:38` |

### 1.5 播放（本批仍归 PHP，见第 4 节）

| 项 | 内容 | 依据 |
|---|---|---|
| 入口 | `vod/play`，输入 `id/sid/nid`（播放源序号/集数） | 【代码】`application/index/controller/Vod.php:86-93`（`play`） |
| 数据需求 | 详情页全部字段 + `player_info`（当前/上下集 URL、`from/server/note`、加密标记、trysee/points） | 【代码】`application/common/controller/All.php:442-546`（`label_vod_play`） |
| 构成分支 | `label_vod_play` 内由 `check_user_popedom(type_id, pe==0?3:5, ...)` 返回 `$popedom`（赋值在 `application/common/controller/All.php:474`），其中试看值是**权限结果 `$popedom['trysee']`**。**无权限/无试看的拦截只发生在 `application/common/controller/All.php:482` 的 `pe==0` 分支**（confirm 页或 error 跳 `user/index`）；`pe==1`（player 入口）不走该拦截。注意区分另一个来源：`player_info['trysee']`（注入播放器元数据）取自**局部变量 `$trysee`**（`application/common/controller/All.php:470-472` 从 `config.user.trysee` 与 `vod_trysee` 求得、:499 写入），与 `$popedom['trysee']` 不是同一来源 | 【代码】`application/common/controller/All.php:468-499` |
| play 与 player 的分工 | `vod/play`（`application/index/controller/Vod.php:86` 起）与 `vod/player`（`application/index/controller/Vod.php:95` 起，`pe=1`）都先调用同一个 `label_vod_play` 得到 `$info`，再选择模板：`player()` 在 :97 调 `label_vod_play` 后，:98-100 版权分支（`vod_copyright==1 && copyright_status==4`）输出 `vod/copyright`，:101-103 播放密码未过 session 输出 `vod/player_pwd`，:104 才返回正常 `vod/player` 模板——**不能写成 pe=1 必然直接注入播放器或模板前置拦截**。播放器注入分支在 `application/common/controller/All.php:549`：条件为 `(pe==0 && flag=='play' && $popedom['trysee']>0)` 或 `vod_pwd_play 未过 session` 或 `vod_copyright==1 && copyright_status==4`（运算符优先级下第一支才受 `pe==0` 约束，密码与版权两支不受）。命中时为模板赋值 iframe 形式的 `player_js`（`application/common/controller/All.php:555-556`），指向 `vod/player`；**实际选用哪个模板（版权/密码/正常 player）由 `application/index/controller/Vod.php:98-104` 后续判断决定，最终 DOM 未验证（缺模板）**。未命中时**直接注入** `player_aaaa`/`player_js`（:559-560 的 else 分支） | 【代码】`application/common/controller/All.php:549-560`；`application/index/controller/Vod.php:95-104` |
| 版权分支 | `vod/play` 控制器侧：`vod_copyright==1 && copyright_status==3` → `vod/copyright`（`application/index/controller/Vod.php:89-91`）；`vod/player` 侧为 `copyright_status==4`（`application/index/controller/Vod.php:98-100`） | 【代码】`application/index/controller/Vod.php:86-104` |
| 播放器 | 未命中 iframe 分支时，模板变量包含 `static/js/playerconfig.js` + `static/js/player.js` 的脚本引用，`player_data` 赋给 `player_aaaa` | 【代码】`application/common/controller/All.php:559-560`【缺模板】最终渲染结果与页面结构未验证 |
| 计数 | 播放页面本身不写计数；计数走 `ajax/hits` 等（有写库副作用，见第 4 节）。但**整个请求不是只读**：所有继承 `index/Base` 的页面都可能在 `label_user` 中对到期会员执行 UPDATE（见 1.1 隐式写入） | 【代码】迁移草稿 3.1；`application/common/model/User.php:455-466` |

## 2. 旧 URL 兼容矩阵

### 2.0 配置事实与前提

- `application/config.php:80`：`var_pathinfo = 's'`；`:84`：`pathinfo_depr` 框架初始值 `'-'`。
- `application/common/behavior/Init.php:81-85`：运行时用 `app.pathinfo_depr` 覆盖框架值；仓库 `application/extra/maccms.php:47` 为 `'/'`（空时回退 `'/'`）。
- 同一段（`application/common/behavior/Init.php:81`）还用 `rewrite.route_status` 设置 `url_route_on`；仓库 `application/extra/maccms.php:489` 为 `'0'`，即**路由别名当前关闭**。
- `?s=` 读取在 `thinkphp/library/think/Request.php:401-423`（`pathinfo()`），读到后经路由别名表（仅 `route_status=1` 时启用）与 PATHINFO 解析。
- 因此 `/?s=vod-detail-id-123`（`-` 分隔）**只在 `pathinfo_depr='-'` 分支有效**；在仓库当前 `/` 配置下该串会被当成一段路径而非参数对。不能同时标为当前配置下有效。

> MACC-10 补充（旧代码执行结果，见 `docs/frontend/02-url-golden-vectors.md` 第 3 节）：`route_status=1` 时直达的模块路径（如 `/index.php/vod/detail/id/123.html`、`?s=/vod/detail/id/123`）返回 404，只有别名可达，二者不会并存；`pathinfo_depr=-` 时 `/` 仍可作分隔符。

URL 生成、PHP 路由匹配、nginx 去入口转发是三层不同的事：生成由 `mac_url`（`application/common.php`）按 `rewrite.status` 决定是否含 `index.php`；匹配由 ThinkPHP 按 `var_pathinfo`/`pathinfo_depr`/`url_route_on` 进行；去 `index.php` 直达依赖 nginx/部署层转发，**别名不是 `rewrite.status=1` 就必然启用**（`rewrite.status` 控制 URL 生成形态，`route_status` 控制别名匹配开关，且省略 `index.php` 的路径能否到达 PHP 取决于部署转发）。

### 2.1 基础形态（仓库当前配置：pathinfo_depr=/、rewrite.status=0、route_status=0、suffix=html、suffix_hide=0）

| 形态 | 示例（合成） | 说明 | 依据 |
|---|---|---|---|
| PATHINFO（含 index.php） | `/index.php/vod/detail/id/123.html` | `rewrite.status=0` 时生成含 `index.php` | 【代码】`application/common.php:2407-2412`（`mac_url` 尾部） |
| 去 index.php | `/vod/detail/id/123.html` | `rewrite.status=1` 时生成不含；能否到达 PHP 取决于部署转发 | 同上 |
| `?s=` 兼容（当前 `/` 配置） | `/?s=/vod/detail/id/123` | ThinkPHP 兼容模式入口 | 【代码】`thinkphp/library/think/Request.php:401-423` |
| `?s=` 兼容（`-` 分支） | `/?s=vod-detail-id-123` | 仅当 `app.pathinfo_depr='-'` 时有效 | 【代码】`application/common/behavior/Init.php:81-85` |
| 路由别名 | `/voddetail/123` → `vod/detail` | 别名文本在 `rewrite.route`（`application/extra/maccms.php:500` 起），**仅 `route_status=1` 时启用**；当前配置下关闭，属条件兼容形式 | 【代码】`application/common/behavior/Init.php:81`；`application/extra/maccms.php:489` |
| 分页 | `?page=2`，或别名 `vodtype/1-2`（`-` 为别名参数分隔） | `mac_page_param` 处理分页参数 | 【代码】`application/common.php:517` 起 |

### 2.2 各页面 URL（仓库默认）

| 页面 | 动态 URL（仓库默认） | 路由别名（route_status=1 时） | 静态化路径（view=2 时，`path.*` 配置） | 备注 | 依据 |
|---|---|---|---|---|---|
| 首页 | `/index.php` | `index-<page?>` | `index` | `/index-PAGELINK.html` 只是 `url()` 恰好返回 `/PAGELINK.html` 时的特殊分支（`application/common.php:1765-1768`），不是无条件输出 | 【代码】`application/common.php:1757-1768` |
| 分类 | `/index.php/vod/type/id/1.html` | `vodtype/<id>-<page?>` | `vodtypehtml/{id}/index` | id 形态受 `rewrite.type_id`（`application/extra/maccms.php:495`）控制；静态路径模板在 `path.vod_type`（`application/extra/maccms.php:477`） | 【代码】`application/extra/maccms.php:477,495` |
| 筛选 | `/index.php/vod/show/id/1.html` | `vodshow/<id>-<area?>-...-<year?>` | — | 参数顺序固定，空参数也占位 | 【代码】`application/extra/maccms.php`（`rewrite.route`） |
| 详情 | `/index.php/vod/detail/id/123.html` | `voddetail/<id>` | `vodhtml/{id}/index` | id 形态受 `rewrite.vod_id`（`application/extra/maccms.php:493`）控制 | 【代码】`application/extra/maccms.php:478,493` |
| 播放 | `/index.php/vod/play/id/123/sid/1/nid/1.html` | `vodplay/<id>-<sid>-<nid>` | `vodplayhtml/{id}/index` | 同上 | 【代码】`application/extra/maccms.php`（`path.*`、`rewrite.route`） |
| 搜索 | `/index.php/vod/search/wd/关键词.html` | `vodsearch/<wd?>-...-<year?>` | — | 关键词经 `htmlspecialchars(urldecode())` 处理 | 【代码】`application/common.php`（`mac_param_url`） |
| RSS | `/index.php/rss/index.html` | `rss/index` 等 | `rss/index.xml` | view=2 时输出 `.xml` | 【代码】`application/common.php`（`mac_url` 的 rss 分支） |
| 下载 | `/index.php/vod/down/id/123/sid/1/nid/1.html` | `voddown/<id>-<sid>-<nid>` | `voddownhtml/{id}/index` | 首批不做 | 【代码】`application/extra/maccms.php`（`path.*`） |

补充：`suffix_hide=1` 时 `mac_url` 分两步：先对**所有模型**把 `.html` 替换为 `/`（`application/common.php:2414-2415`，此步不排除 show/search）；再对 URL 中残留的 `-/`、`_/`、`-.`、`_.` 做清理，仅这第二步排除 show/search（:2416-2418）。即筛选/搜索 URL 的 `.html` 同样会被替换为 `/`，但其中的 `-/` 等占位残留不会被清理。属配置分支行为（`application/common.php:2414-2423`）。

### 2.3 canonical 与重复 URL

【缺模板】仓库模板无输出 `<link rel="canonical">` 的证据，线上是否输出、取值为何均待定。

- 同一内容在条件下可能共存多种可达 URL（动态 PATHINFO、`?s=`、别名、静态化路径），旧 PHP 代码中没有统一的 canonical 生成逻辑；但当前 `route_status=0`、静态文件缺失，不能声称别名/静态路径已实际可达——「可达」是配置与部署前提下的可能性，不是现状。
- 新前端页面需在「动态 PATHINFO（rewrite.status 当前值）、`?s=`、（若启用）别名」之间确定唯一 canonical 并输出 `<link rel="canonical">`；分页与带筛选的列表页 canonical 策略（自引用 vs 指向第一页）同样**缺模板证据，待定**，不虚构线上 canonical。

### 2.4 待验证项

- 线上 `app.pathinfo_depr`、`rewrite.status`、`route_status`、自定义 `route` 文本与 `path.*` 是否与仓库默认一致（属部署/切流量前核实项，不阻塞本批离线 API 与本地页面实验，见 5.1）。
- `vod_id/type_id` 的编码形态（`rewrite.vod_id=2` 时依赖 `encode_key`，仓库已清空，无法验证编码值）。
- nginx 层是否对 `index.php`、伪静态、`?s=` 做了额外转发或缓存规则（部署兼容另行验证）。
- 旧站点是否通过 `domain.php` 绑定了独立域名路径。

## 3. 只读前台公开字段需求（供后端契约核对）

后端首批只交付「分类与视频公开元数据 API」。下列字段分三档：**A. 首批公开元数据必需**；**B. 可选或后续筛选/搜索需求**；**C. PHP 授权播放/内部专属，不进公开 API**。后端尚未交付的字段一律标为需求而非已实现；不要求首批契约覆盖全部需求。

### 3.1 分类（`mac_type`）

| 字段 | 用途 | 档位 |
|---|---|---|
| type_id / type_name / type_en | 导航、URL、面包屑 | A |
| type_pid | 父级分类 | A |
| type_key / type_des / type_title | SEO meta | A |
| type_pic / type_logo | 分类图 | B |
| type_extend | 扩展筛选字典（旧代码中解析为 array） | B |

### 3.2 视频公开元数据（`mac_vod`）

| 字段 | 用途 | 档位 |
|---|---|---|
| vod_id / vod_name / vod_sub / vod_en | 标题、副标题、URL | A |
| type_id / type_id_1 / vod_class / vod_tag | 分类与标签 | A |
| vod_pic / vod_pic_thumb / vod_pic_slide | 海报 | A |
| vod_actor / vod_director / vod_writer | 演职员 | A |
| vod_blurb / vod_content | 简介 | A |
| vod_remarks / vod_pubdate / vod_total / vod_serial | 更新状态 | A |
| vod_area / vod_lang / vod_year / vod_version / vod_state | 筛选与展示 | A |
| vod_score / vod_score_num / vod_hits / vod_hits_day/week/month | 评分与热度（只读展示） | A |
| vod_time / vod_time_add | 时间 | A |
| vod_isend | 完结标记（展示） | A |
| vod_level | **推荐等级**（后台 `application/admin/view/vod/info.html:35-47` 将其作为推荐选项，9 为幻灯片；`application/lang/zh-cn.php:45` level=推荐、:212 slide=幻灯片；`application/common/model/Vod.php:290-294` 用它筛选推荐等级）。**不是会员等级**，不能据此提示「需要会员」 | A（仅推荐/展示语义） |
| vod_copyright | 版权标记（`vod_copyright==1` 仅表示有版权标记，**不等于不可用**；是否展示版权提示页还取决于后端配置 `copyright_status` 为 2/3/4，见 1.3/1.5，分支判断仍在 PHP，不强制本批公开 API 新增授权结果） | B（提示文案，非授权结果） |
| vod_points / vod_points_play / vod_trysee | 积分/试看提示文案（不含支付与鉴权结果；原始字段不等于完整授权结果，提示需求需写清配置条件） | B |
| vod_douban_id / vod_douban_score | 第三方评分 | B |
| vod_rel_vod / vod_rel_art | 相关推荐 | B |
| vod_plot / vod_plot_name / vod_plot_detail | 剧情分集 | B |
| vod_play_from / vod_play_server / vod_play_note / vod_play_url 及解析结构（sid/nid/urls 列表） | **PHP 播放专属**（`label_vod_play` 内解析注入播放器），不进入首批公开 API；公开 API 没有播放地址不是缺陷 | **C** |
| vod_pwd / vod_pwd_play / vod_pwd_down | 观看密码 | **C（不公开）**；是否存在密码保护可由后端另行设计一个布尔提示字段（需求，非首批必需） |
| vod_reurl / vod_jumpurl | 跳转地址（后端自行处理） | C |
| vod_status / vod_lock / 采集内部字段 | 后台管理状态；`vod_lock` 是编辑锁定字段（`application/common/model/Vod.php`），与前台展示/权限无关，不列为权限状态 | C |

说明：详情页能否展示「播放按钮」由 PHP 播放链路判断；新前端详情页只链接到旧播放 URL，播放权限提示文案所需的字段（vod_points_play、vod_trysee、vod_copyright）按 B 档需求提出，实际鉴权结果（`check_user_popedom`、`application/index/controller/Base.php:92-100` 的 group_type/group_popedom，以及后续积分/试看逻辑）不进入公开 API，也不强制本批公共 API 新增没有设计的授权能力。

### 3.3 分页与筛选

| 项 | 验收要求 |
|---|---|
| 分页参数 | `page`（>=1）、`page_size`（后端定；旧模板通过标签 num 控制，【缺模板】） |
| 分页元数据 | 旧代码事实：`mac_page_param`（`application/common.php:517-523`）在空结果时**只返回 `record_total=0`**，完整分页结构（page_num/page_url 等）仅在非空时生成。新 API 返回完整分页结构（含空结果时的 `record_total=0`）是**新需求**，不是旧事实 |
| 筛选参数 | `area/year/lang/class/state/level/letter/tag/order/by` 等，与 `mac_param_url`（`application/common.php`）对齐；属后续批次需求，不强制首批支持全部 |
| 空结果 | 返回空列表与正常分页结构（record_total=0），不报错 |
| 404（新 API 验收目标） | 分类或视频不存在时返回明确 404 语义，前端展示 404 页。**与旧行为分开**：旧分类不存在走 error 跳转页（`application/common/controller/All.php:177-178`），旧详情不存在走 `page_error`（`application/common/controller/All.php:391-393`）；新 API 的 404 目标另行约定，不声称旧行为已是 404 |

### 3.4 移动端与键盘使用（验收项）

| 项 | 验收要求 |
|---|---|
| 响应式 | 常见桌面宽度（1280/1440/1920）布局正常；移动端 375px 不横向滚动 |
| 键盘 | 分页、筛选、搜索框、剧集列表可用 Tab 聚焦并 Enter/Space 触发 |
| 焦点可见 | 交互元素有可见 focus 样式 |
| 无 JS 降级 | 列表页主要链接为真实 `<a href>`，便于爬虫与键盘 |

## 4. PHP 兼容交互边界与风险

以下能力**继续由旧 PHP 处理**，新前端通过链接或表单跳转到旧 URL，不通过新 API 实现。注意：只读 JSON API 不自动意味着整个页面只读——PHP 页面跳转仍有隐式写入（见 1.1、风险段）。

| 能力 | 旧入口 | 依据与备注 |
|---|---|---|
| 登录/注册/退出 | `user/login`、`user/reg`、`user/logout` | 含验证码（vendor 恢复后）；`application/index/controller/User.php:180,349,193` |
| 会员中心 | `user/index` 等 | 含积分、订单、推广 |
| 评论提交 | `POST comment/index`（POST 时内部调用 `saveData`） | 【代码】`application/index/controller/Comment.php:18-22`（`index` 内 `isPost` 分支） |
| 评论直达写入 | `comment/saveData`（直达方法） | 【代码】`application/index/controller/Comment.php:37` 起：方法体用 `input()` 取参，**本方法没有 `isPost()` 约束**，POST 约束只在外层 `index`；直达调用的方法限制不能由外层推及 |
| 评论列表/顶踩/举报 | `comment/ajax`（:30 起）、`comment/digg`（:140 起）、`comment/report`（:119 起） | 【代码】`application/index/controller/Comment.php:30,119,140`；不存在 `comment/save` 路由 |
| 留言提交 | `POST gbook/index`（POST 时内部调用 `saveData`） | 【代码】`application/index/controller/Gbook.php:17-21` |
| 留言直达写入 | `gbook/saveData`（直达方法，:44 起；同样无本方法 POST 守卫） | 【代码】`application/index/controller/Gbook.php:44` 起；不存在 `gbook/save` 路由 |
| 观看密码校验 | `ajax/pwd`（mid=1 视频 / mid=2 文章；type=1 详情 / 4 播放 / 5 下载），验证后写 session | 【代码】`application/index/controller/Ajax.php:400-457`：用 `input()` 取参，**本方法没有 `isPost()` 约束** |
| 计数 | `ajax/hits`、`ajax/digg`、`ajax/score` | 可用 GET 触发，有写库副作用（`application/index/controller/Ajax.php:152,302,347`） |
| 积分购买/试看确认 | `user/ajax_buy_popedom` | 【代码】`application/index/controller/User.php:101` 起：同样无本方法 POST 守卫 |
| 支付回调 | `payment/notify` | 【代码】`application/index/controller/Payment.php:13-36`：**只有 POST 分支**调用支付适配器 `notify()`；非 POST 返回 `success` 跳转 `user/index`。不能把任何 GET 称为成功支付 |
| 下载 | `vod/down`、`vod/downer` | 权限检查更严格 |

**风险**：旧前台大量「GET 有写副作用」的接口（迁移草稿 3.1 已列；如 `label_user` 的到期 UPDATE 适用于所有前台 Base 页面）。影子流量、黄金样本采集、搜索引擎抓取时不能把这些 URL 当只读，也不能声称普通 GET 一律安全。新前端不得用公开 API 暴露需要登录或计费的播放地址；播放页权限判断必须在服务端完成。

## 5. 后续实现前置条件与建议

### 5.1 前置条件

1. 队长验收后端正式契约（`docs/contracts/content-read.openapi.yaml`），字段覆盖第 3 节 A 档需求。
2. 取得缺失的前台模板，或确认重新设计（模板标签兼容方案另定）。
3. 线上伪静态/路由配置（2.4 待验证项）在**部署/切流量前**核实；本批离线 API 与本地页面实验不以此为阻塞。

### 5.2 实现建议（取舍）

| 方案 | 说明 | 取舍 |
|---|---|---|
| A. 新前端只渲染只读页面，交互跳回 PHP | 首页/列表/详情用新系统渲染公开元数据；点击登录、评论、购买、播放等跳旧 PHP URL | 最稳妥，不碰写入与授权播放；用户体验有跳站感 |
| B. 新前端 + PHP 兼容代理 | 新前端统一入口，PHP 页面通过反向代理嵌入 | 过渡平滑；登录态、CSRF、响应式适配复杂 |
| C. 直接重写模板到服务端渲染 | 用新后端模板引擎实现旧标签子集 | 标签体系庞大（`application/common/taglib/Maccms.php`），首批工作量高 |

建议首批采用 A：只读页面新做，写入与会员功能保持 PHP URL 跳转；播放页整体留在 PHP。待后端契约稳定后再评估是否把评论等交互迁入新 API。模板样式与迁移方式仍待定，本文不选择 Vue/React/模板架构。

## 6. 附：主要代码引用

- 前台控制器：`application/index/controller/Index.php`、`application/index/controller/Vod.php`、`application/index/controller/Base.php`（搜索在 `application/index/controller/Vod.php` 内；无独立 `application/index/controller/Search.php`）
- 基础与渲染：`application/common/controller/All.php`（`label_fetch:41`、`label_maccms:58`、`page_error:98`、`label_user:118`、`label_type:166`、`label_vod_detail:385`、`label_vod_play:442`、播放器注入分支 `:548-560`）
- 权限：`application/index/controller/Base.php:92-100`（`check_user_popedom`）
- URL 生成与参数：`application/common.php`（`mac_page_param:517`、`mac_url` 的 index 分支 :1757-1768、suffix_hide 分支 :2414-2423、`mac_label_vod_detail` :2715-2732，含 `vod_status=1` 于 :2727）
- 框架入口行为：`application/config.php:80`（var_pathinfo）、`:84`（pathinfo_depr 初始值）；`application/common/behavior/Init.php:81-85`；`thinkphp/library/think/Request.php:401-423`
- 配置：`application/extra/maccms.php`（`pathinfo_depr:47`、`page_sp:483`、`suffix:484`、`suffix_hide:488`、`route_status:489`、`rewrite.status:490`、`rewrite.vod_id:493`、`rewrite.type_id:495`、`rewrite.route:500` 起、`path.vod_type:477`、`path.vod_detail:478`）
- 表结构：`application/install/sql/install.sql`（`mac_type`、`mac_vod`）
- 交互边界：`application/index/controller/Comment.php`（POST 入口 :18-22、saveData :37、report :119、digg :140）、`application/index/controller/Gbook.php`（POST 入口 :17-21、saveData :44）、`application/index/controller/Ajax.php`（hits :152、digg :302、score :347、pwd :400-457）、`application/index/controller/User.php`（ajax_buy_popedom :101）、`application/index/controller/Payment.php`（notify :13-36）
- 推荐等级语义：`application/admin/view/vod/info.html:35-47`、`application/lang/zh-cn.php:45,212`、`application/common/model/Vod.php:290-294`
- 迁移草稿写入边界：`docs/analysis/03-migration-plan-draft.md` 3.1
