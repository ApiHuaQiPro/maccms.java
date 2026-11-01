# APPK-2 PRE-5 模板扫描清单（**初稿，未完成**）

> 本文件由 `tests/compare/APPK-2/scan_templates.py` 生成，只含**机器扫描数据**。
> PRE-5 的完成判定（PRD §13）要求：168 个 `.html` 各占一行以上；每处 PHP 代码有对应实现说明；
> 所有产生当前时间/日期、随机值、会话或 token 的位置逐处登记并归属 EX-1、EX-2 或 DATA-2，
> **没有“归属未定”的行**。实现说明与归属由架构师在 S3-04 补齐，本初稿不得当作 PRE-5 完成。

| 项 | 内容 |
| --- | --- |
| 生成来源 | `tests/compare/APPK-2/scan_templates.py` |
| 扫描根 | `.compare/APPK-2/becheck/third_party/maccms-template-v2020.1000.1035/template` |
| 扫描文件数 | 274（`.html` 168） |
| 清单比对 | `docs/test/APPK-2/fixtures/template-package-manifest.tsv`（274 行） |

## 1. 校验结果

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| E.2-总数 | OK | template/ 下文件数 274，期望 274 |
| E.2-default_pc | OK | default_pc 文件数 147，期望 147 |
| E.2-default_wap | OK | default_wap 文件数 127，期望 127 |
| E.2-default_pc-html | OK | default_pc 的 .html 92 个，期望 92 |
| E.2-default_wap-html | OK | default_wap 的 .html 76 个，期望 76 |
| E.2-目录集合 | OK | template/ 下的目录：default_pc, default_wap（期望只有 default_pc, default_wap） |
| E.2-default_wap/ads | OK | default_wap/ads：不存在 |
| RISK-T1-CRLF | OK | **文本**文件含 CRLF 的：0 个（二进制文件里的 0D 0A 不算；判断依据是字节/blob SHA-1 是否与附表 E.3 一致） |
| E.4-EF-1-js/css/ads 不含 {maccms | OK | js/、css/、ads/ 下共 106 个文件，命中 {maccms 的 0 个（期望 0 个） |
| E.3-清单条数 | OK | 清单 274 行，期望 274 行 |
| E.3-清单缺文件 | OK | 清单有、包里没有：0 个 |
| E.3-包内多文件 | OK | 包里有、清单没有：0 个 |
| E.3-字节数 | OK | 字节数不符：0 个 |
| E.3-blob-SHA-1 | OK | SHA-1 不符：0 个 |
| E.4-EF-3-default_pc | OK | 「无差异」 |
| E.4-EF-3-default_wap | OK | 「无差异」 |
| E.4-EF-5-include | OK | include 出现 234 次，期望 234 |
| E.4-EF-5-if | OK | if 出现 210 次，期望 210 |
| E.4-EF-5-volist | OK | volist 出现 35 次，期望 35 |
| E.4-EF-5-else | OK | else 出现 35 次，期望 35 |
| E.4-EF-5-elseif | OK | elseif 出现 18 次，期望 18 |
| E.4-EF-5-empty | OK | empty 出现 10 次，期望 10 |
| E.4-EF-5-foreach | OK | foreach 出现 4 次，期望 4 |
| E.4-EF-5-switch | OK | switch 出现 1 次，期望 1 |
| E.4-EF-2-已知 6 文件齐全 | OK | 「齐全」 |
| E.4-EF-2-无新增 PHP 文件 | OK | 「无新增」 |
| F.5-规则2-归属 | UNATTRIBUTED | 归属未定的时钟/随机/会话来源：22 处：default_pc/html/user/ajax_info.html:15（\|mac_day）; default_pc/html/user/cards.html:56（\|mac_day）; default_pc/html/user/cash.html:64（\|mac_day）; default_pc/html/user/index.html:43（\|mac_day）; default_pc/html/user/index.html:46（\|mac_day）; default_pc/html/user/index.html:48（\|mac_day）; default_pc/html/user/orders.html:58（\|mac_day）; default_pc/html/user/plog.html:53（\|mac_day）; default_pc/html/user/popedom.html:42（\|mac_day）; default_pc/html/user/reward.html:61（\|mac_day） |

## 2. `{maccms:…}` 标签（两个包分别统计）

| 标签 | default_pc | default_wap |
| --- | --- | --- |
| `actor` | 6 | 0 |
| `art` | 7 | 2 |
| `comment` | 2 | 2 |
| `for` | 1 | 1 |
| `foreach` | 49 | 27 |
| `gbook` | 1 | 1 |
| `link` | 1 | 1 |
| `link_gbook` | 1 | 0 |
| `link_map_baidu` | 1 | 0 |
| `link_map_google` | 1 | 0 |
| `link_map_rss` | 1 | 0 |
| `link_map_vod` | 1 | 0 |
| `path` | 0 | 1 |
| `siteaid` | 0 | 1 |
| `siteid` | 0 | 1 |
| `sitetid` | 0 | 1 |
| `topic` | 1 | 1 |
| `type` | 9 | 7 |
| `vod` | 60 | 28 |
| `website` | 5 | 0 |

## 3. 其他模板语法（两包合计）

| 语法 | 次数 |
| --- | --- |
| `{case}` | 5 |
| `{else}` | 35 |
| `{elseif}` | 18 |
| `{empty}` | 10 |
| `{foreach}` | 4 |
| `{if}` | 210 |
| `{include}` | 234 |
| `{php}` | 2 |
| `{switch}` | 1 |
| `{volist}` | 35 |

## 4. `{:函数()}` 调用清单（白名单 RT-7 的输入）

| 函数 | 次数 |
| --- | --- |
| `url` | 303 |
| `mac_url_type` | 102 |
| `mac_url_vod_detail` | 101 |
| `mac_url` | 55 |
| `mac_url_img` | 51 |
| `mac_url_art_detail` | 19 |
| `mac_url_plot_detail` | 8 |
| `mac_url_vod_play` | 8 |
| `mac_url_website_detail` | 6 |
| `mac_url_topic_detail` | 5 |
| `mac_url_search` | 3 |
| `mac_url_vod_plot` | 2 |
| `mac_url_actor_detail` | 1 |
| `mac_url_art_index` | 1 |
| `mac_url_role_detail` | 1 |
| `mac_url_topic_index` | 1 |
| `mac_url_website_index` | 1 |

## 5. 模板变量（`{$a.b}`）

| 变量 | 次数 |
| --- | --- |
| `$vo.vod_name` | 180 |
| `$maccms.site_name` | 96 |
| `$maccms.path` | 84 |
| `$obj.vod_name` | 79 |
| `$maccms.path_tpl` | 61 |
| `$num` | 50 |
| `$key` | 43 |
| `$maccms.site_url` | 38 |
| `$vo.vod_actor` | 38 |
| `$vo2` | 32 |
| `$obj.vod_id` | 30 |
| `$maccms.mid` | 27 |
| `$vo.vod_version` | 27 |
| `$obj.user_name` | 22 |
| `$obj.group.group_name` | 20 |
| `$param.wd` | 20 |
| `$vo.vod_serial` | 20 |
| `$obj.topic_name` | 19 |
| `$obj.actor_name` | 17 |
| `$vo.art_name` | 17 |
| `$maccms.site_description` | 16 |
| `$maccms.site_keywords` | 16 |
| `$obj.type_name` | 16 |
| `$popedom.trysee` | 16 |
| `$vo.vod_score` | 16 |
| `$vo.vod_total` | 16 |
| `$vo.website_name` | 15 |
| `$__PAGING__.record_total` | 14 |
| `$vo.comment_id` | 14 |
| `$obj.website_name` | 13 |
| `$vo.actor_name` | 13 |
| `$vo.group_name` | 13 |
| `$vo.type_name` | 13 |
| `$__PAGING__.page_total` | 12 |
| `$child.comment_id` | 12 |
| `$vo` | 12 |
| `$vo.data.name` | 12 |
| `$obj.art_name` | 11 |
| `$obj.type.type_name` | 11 |
| `$vo.ulog_id` | 11 |
| `$vo.vod_remarks` | 11 |
| `$param.nid` | 10 |
| `$param.sid` | 10 |
| `$vo.data.link` | 10 |
| `$vo2.name` | 10 |
| `$__PAGING__.page_current` | 8 |
| `$obj.type_des` | 8 |
| `$obj.type_key` | 8 |
| `$param.actor` | 8 |
| `$param.area` | 8 |
| `$param.class` | 8 |
| `$param.director` | 8 |
| `$param.lang` | 8 |
| `$param.year` | 8 |
| `$player_data` | 8 |
| `$player_js` | 8 |
| `$site_url` | 8 |
| `$vo.data.type.type_name` | 8 |
| `$vo.group_id` | 8 |
| `$vo.website_area` | 8 |
| `$obj.user_points` | 7 |
| `$obj.vod_blurb` | 7 |
| `$vo.player_info.show` | 7 |
| `$obj.art_id` | 6 |
| `$obj.vod_pwd_url` | 6 |
| `$vo.name` | 6 |
| `$vo.type.type_name` | 6 |
| `$art_id` | 5 |
| `$comment.login` | 5 |
| `$comment.verify` | 5 |
| `$info.order_code` | 5 |
| `$maccms.site_email` | 5 |
| `$obj.type_1.type_name` | 5 |
| `$obj.type_title` | 5 |
| `$obj.vod_content` | 5 |
| `$topic_id` | 5 |
| `$vo.cash_id` | 5 |
| `$vo.plog_id` | 5 |
| `$vod_id` | 5 |
| `$child.comment_down` | 4 |
| `$child.comment_name` | 4 |
| `$child.comment_up` | 4 |
| `$config.min` | 4 |
| `$gbook.login` | 4 |
| `$gbook.verify` | 4 |
| `$i` | 4 |
| `$maccms.http_type` | 4 |
| `$msg` | 4 |
| `$obj.user_id` | 4 |
| `$obj.vod_score` | 4 |
| `$obj.website_id` | 4 |
| `$param.page` | 4 |
| `$popedom.msg` | 4 |
| `$vo.comment_down` | 4 |
| `$vo.comment_name` | 4 |
| `$vo.comment_up` | 4 |
| `$vo.group_points_day` | 4 |
| `$vo.group_points_month` | 4 |
| `$vo.group_points_week` | 4 |
| `$vo.group_points_year` | 4 |
| `$vo.order_code` | 4 |
| `$vo.player_info.tip` | 4 |
| `$vo.topic_name` | 4 |
| `$vo.ulog_nid` | 4 |
| `$vo.ulog_rid` | 4 |
| `$vo.ulog_sid` | 4 |
| `$vo.vod_area` | 4 |
| `$vo.website_lang` | 4 |
| `$vo.website_remarks` | 4 |
| `$vo2.url` | 4 |
| `$info.order_id` | 3 |
| `$info.order_price` | 3 |
| `$obj.user_email` | 3 |
| `$obj.user_phone` | 3 |
| `$obj.user_qq` | 3 |
| `$obj.vod_actor` | 3 |
| `$obj.vod_nmae` | 3 |
| `$vo.art_hits_month` | 3 |
| `$vo.card_id` | 3 |
| `$vo.order_id` | 3 |
| `$vo.vod_blurb` | 3 |
| `$vo.vod_director` | 3 |
| `$vo1.type_name` | 3 |
| `$__PAGING__.page_next` | 2 |
| `$__PAGING__.page_sp` | 2 |
| `$__PAGING__.page_url` | 2 |
| `$ac` | 2 |
| `$child.comment_content` | 2 |
| `$child.type_name` | 2 |
| `$close_tip` | 2 |
| `$config.scale` | 2 |
| `$info.group.group_name` | 2 |
| `$info.user_name` | 2 |
| `$maccms.http_url` | 2 |
| `$obj.actor_area` | 2 |
| `$obj.actor_birthday` | 2 |
| `$obj.actor_blood` | 2 |
| `$obj.actor_height` | 2 |
| `$obj.actor_starsign` | 2 |
| `$obj.art_from` | 2 |
| `$obj.art_pwd` | 2 |
| `$obj.art_pwd_url` | 2 |
| `$obj.group_name` | 2 |
| `$obj.topic_content` | 2 |
| `$obj.topic_id` | 2 |
| `$obj.user_answer` | 2 |
| `$obj.user_login_num` | 2 |
| `$obj.user_question` | 2 |
| `$obj.vod_area` | 2 |
| `$obj.vod_director` | 2 |
| `$obj.vod_points_down` | 2 |
| `$obj.vod_points_play` | 2 |
| `$obj.vod_pwd` | 2 |
| `$obj.vod_pwd_down` | 2 |
| `$obj.vod_pwd_play` | 2 |
| `$obj.vod_year` | 2 |
| `$order.order_code` | 2 |
| `$order.order_id` | 2 |
| `$order.order_price` | 2 |
| `$param.id` | 2 |
| `$param.name` | 2 |
| `$site_email` | 2 |
| `$site_name` | 2 |
| `$type_name` | 2 |
| `$vo.card_money` | 2 |
| `$vo.card_no` | 2 |
| `$vo.card_points` | 2 |
| `$vo.cash_money` | 2 |
| `$vo.cash_points` | 2 |
| `$vo.comment_content` | 2 |
| `$vo.data.pic` | 2 |
| `$vo.gbook_content` | 2 |
| `$vo.gbook_id` | 2 |
| `$vo.gbook_name` | 2 |
| `$vo.gbook_reply` | 2 |
| `$vo.link_name` | 2 |
| `$vo.link_url` | 2 |
| `$vo.order_points` | 2 |
| `$vo.order_price` | 2 |
| `$vo.player_info.target` | 2 |
| `$vo.plog_points` | 2 |
| `$vo.remarks` | 2 |
| `$vo.topic_blurb` | 2 |
| `$vo.ulog_points` | 2 |
| `$vo.user_id` | 2 |
| `$vo.user_name` | 2 |
| `$vo2.title` | 2 |
| `$__PAGING__.page_prev` | 1 |
| `$art_down` | 1 |
| `$art_name` | 1 |
| `$art_up` | 1 |
| `$key2` | 1 |
| `$maccms.site_tj` | 1 |
| `$maccms.site_wapurl` | 1 |
| `$obj.actor_birtharea` | 1 |
| `$obj.actor_blurb` | 1 |
| `$obj.actor_content` | 1 |
| `$obj.actor_school` | 1 |
| `$obj.actor_weight` | 1 |
| `$obj.actor_works` | 1 |
| `$obj.art_content` | 1 |
| `$obj.art_down` | 1 |
| `$obj.art_hits` | 1 |
| `$obj.art_up` | 1 |
| `$obj.player_info.link_next` | 1 |
| `$obj.player_info.link_pre` | 1 |
| `$obj.role_actor` | 1 |
| `$obj.role_name` | 1 |
| `$obj.topic_down` | 1 |
| `$obj.topic_up` | 1 |
| `$obj.user_nick_name` | 1 |
| `$obj.vod_class` | 1 |
| `$obj.vod_down` | 1 |
| `$obj.vod_lang` | 1 |
| `$obj.vod_up` | 1 |
| `$obj.vod_version` | 1 |
| `$obj.website_blurb` | 1 |
| `$obj.website_jumpurl` | 1 |
| `$obj.website_nmae` | 1 |
| `$obj.website_score` | 1 |
| `$topic_down` | 1 |
| `$topic_up` | 1 |
| `$vo.actor_remarks` | 1 |
| `$vo.cash_remarks` | 1 |
| `$vo.data.remarks` | 1 |
| `$vo.data.vod_pic` | 1 |
| `$vo.plog_remarks` | 1 |
| `$vo.role_id` | 1 |
| `$vo.role_name` | 1 |
| `$vo.type_id` | 1 |
| `$vo.type_pid` | 1 |
| `$vo.vod_hits` | 1 |
| `$vo.vod_hits_month` | 1 |
| `$vo.vod_pic_slide` | 1 |
| `$vo.vod_year` | 1 |
| `$vo.website_version` | 1 |
| `$vo2.type_name` | 1 |
| `$vod_down` | 1 |
| `$vod_name` | 1 |
| `$vod_up` | 1 |

## 6. 含 PHP 代码的模板（每处一行，待架构师写实现说明）

| 文件 | 行 | 形式 | 片段 | 实现说明（待填） |
| --- | --- | --- | --- | --- |
| `default_pc/html/art/detail.html` | 33 | {php} | `{php}             $where=[];             $where['art_status'] = ['eq',1];       ` |  |
| `default_pc/html/public/jump.html` | 18 | <?php | `<?php echo($url);?>` |  |
| `default_pc/html/public/jump.html` | 18 | <?php | `<?php echo($wait);?>` |  |
| `default_pc/html/vod/copyright.html` | 19 | <?php | `<?php echo($obj['vod_jumpurl']);?>` |  |
| `default_pc/html/vod/plot.html` | 27 | {php} | `{php}             //分集剧情列表分页操作             $__LIST__=[];             $__LIST__['` |  |
| `default_wap/html/public/jump.html` | 18 | <?php | `<?php echo($url);?>` |  |
| `default_wap/html/public/jump.html` | 18 | <?php | `<?php echo($wait);?>` |  |
| `default_wap/html/vod/copyright.html` | 19 | <?php | `<?php echo($obj['vod_jumpurl']);?>` |  |

## 7. 时钟 / 随机 / 会话 / token 来源（附表 F.5 规则 2，归属待架构师填）

| 文件 | 行 | 类别 | 调用 | 归属 | 上下文 |
| --- | --- | --- | --- | --- | --- |
| `default_pc/html/actor/detail.html` | 142 | time | `|date` | DATA-1 | `<li><span>{$vo.art_time\|date='Y-m-d',###}</span><a href="{:mac_url_art_detail($` |
| `default_pc/html/art/detail.html` | 26 | time | `|date` | DATA-1 | `<div class="source"> 来源：{$obj.art_from}&nbsp;&nbsp;&nbsp;发布时间：{$obj.art_time\|da` |
| `default_pc/html/art/index.html` | 30 | time | `|date` | DATA-1 | `<li><span>{$vo.art_time\|date='Y-m-d',###}</span><a href="{:mac_url_art_detail($` |
| `default_pc/html/art/rss.html` | 15 | time | `|date` | DATA-1 | `<pubDate>{$obj.art_time\|date='Y-m-d H:i:s',###}</pubDate>` |
| `default_pc/html/art/type.html` | 25 | time | `|date` | DATA-1 | `<li><span>{$vo.art_time\|date='Y-m-d',###}</span><a href="{:mac_url_art_detail($` |
| `default_pc/html/comment/ajax.html` | 41 | time | `|date` | DATA-1 | `<span class="fr">{$vo.comment_time\|date='Y-m-d H:i:s',###}</span>` |
| `default_pc/html/comment/index.html` | 22 | time | `|date` | DATA-1 | `<span class="fr">{$vo.comment_time\|date='Y-m-d H:i:s',###}</span>` |
| `default_pc/html/gbook/index.html` | 30 | time | `|date` | DATA-1 | `<p class="msg_title"><strong><a class="name" href="javascript:;">{$vo.gbook_name` |
| `default_pc/html/gbook/index.html` | 37 | time | `|date` | DATA-1 | `<p class="msg_title"><strong>站长回复：</strong><span class="time">{$vo.gbook_reply_t` |
| `default_pc/html/map/index.html` | 38 | time | `|date` | DATA-1 | `<dd class="bg">状态：{if condition="$vo.vod_serial gt 0"}第{$vo.vod_serial}集/共{$vo.v` |
| `default_pc/html/map/index.html` | 44 | time | `|date` | DATA-1 | `<div class="lasted-time fn-right">{$vo.vod_time\|date='Y-m-d',###}</div>` |
| `default_pc/html/rss/baidu.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_pc/html/rss/bing.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_pc/html/rss/google.html` | 11 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_pc/html/rss/index.html` | 18 | time | `|date` | DATA-1 | `<pubDate>{$vo.vod_time\|date='Y-m-d H:i:s',###}</pubDate>` |
| `default_pc/html/rss/sm.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_pc/html/rss/so.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_pc/html/rss/sogou.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_pc/html/user/ajax_info.html` | 15 | time | `|mac_day` | **归属未定** | `上次登录时间：{$obj.user_last_login_time\|mac_day=color}` |
| `default_pc/html/user/cards.html` | 56 | time | `|mac_day` | **归属未定** | `<td align="center" valign="middle" bgcolor="#FFFFFF">{$vo.card_use_time\|mac_day` |
| `default_pc/html/user/cash.html` | 64 | time | `|mac_day` | **归属未定** | `<td align="center" valign="middle" bgcolor="#FFFFFF">{$vo.cash_time\|mac_day}</t` |
| `default_pc/html/user/index.html` | 43 | time | `|mac_day` | **归属未定** | `<p><span class="xiang">会员期限：</span>{$obj.user_end_time\|mac_day}</p>` |
| `default_pc/html/user/index.html` | 46 | time | `|mac_day` | **归属未定** | `<p><span class="xiang">注册时间：</span>{$obj.user_reg_time\|mac_day}</p>` |
| `default_pc/html/user/index.html` | 48 | time | `|mac_day` | **归属未定** | `<p><span class="xiang">登陆时间：</span>{$obj.user_login_time\|mac_day}</p>` |
| `default_pc/html/user/orders.html` | 58 | time | `|mac_day` | **归属未定** | `<td align="center" valign="middle" bgcolor="#FFFFFF">{$vo.order_time\|mac_day}</` |
| `default_pc/html/user/plog.html` | 53 | time | `|mac_day` | **归属未定** | `<td align="center" valign="middle" bgcolor="#FFFFFF">{$vo.plog_time\|mac_day}</t` |
| `default_pc/html/user/popedom.html` | 42 | time | `|mac_day` | **归属未定** | `<p><span class="xiang">期限：</span><span class="fen">{if condition="$obj.group_id ` |
| `default_pc/html/user/reward.html` | 61 | time | `|mac_day` | **归属未定** | `<td align="center" valign="middle" bgcolor="#FFFFFF">{$vo.user_reg_time\|mac_day` |
| `default_pc/html/user/upgrade.html` | 33 | time | `|mac_day` | **归属未定** | `<p><span class="xiang">到期时间：</span>{if condition="$obj.group_id lt 3"}[无限期]{else` |
| `default_pc/html/vod/detail.html` | 57 | time | `|date` | DATA-1 | `<dl class="fn-left"><dt>时间：</dt><dd><span id="addtime">{$obj.vod_time\|date='Y-m` |
| `default_pc/html/vod/index.html` | 131 | time | `|date` | DATA-1 | `<li><span>{$vo.vod_time\|date='m-d',###}.</span><a href="{:mac_url_vod_detail($v` |
| `default_pc/html/vod/index.html` | 149 | time | `|date` | DATA-1 | `<li><span>{$vo.vod_time\|date='m-d',###}.</span><a href="{:mac_url_vod_detail($v` |
| `default_pc/html/vod/index.html` | 167 | time | `|date` | DATA-1 | `<li><span>{$vo.vod_time\|date='m-d',###}.</span><a href="{:mac_url_vod_detail($v` |
| `default_pc/html/vod/index.html` | 185 | time | `|date` | DATA-1 | `<li><span>{$vo.vod_time\|date='m-d',###}.</span><a href="{:mac_url_vod_detail($v` |
| `default_pc/html/vod/index.html` | 203 | time | `|date` | DATA-1 | `<li><span>{$vo.vod_time\|date='m-d',###}.</span><a href="{:mac_url_vod_detail($v` |
| `default_pc/html/vod/index.html` | 334 | time | `|date` | DATA-1 | `<li><a href="{:mac_url_art_detail($vo)}" title="{$vo.art_name}">{$vo.art_name\|m` |
| `default_pc/html/vod/rss.html` | 16 | time | `|date` | DATA-1 | `<pubDate>{$obj.vod_time\|date='Y-m-d H:i:s',###}</pubDate>` |
| `default_pc/html/vod/search.html` | 41 | time | `|date` | DATA-1 | `<dl class="fn-left"><dt>时间：</dt><dd><span id="addtime">{$vo.vod_time\|date='Y-m-` |
| `default_pc/html/website/detail.html` | 55 | time | `|date` | DATA-1 | `<dl class="fn-left"><dt>时间：</dt><dd><span id="addtime">{$obj.website_time\|date=` |
| `default_pc/html/website/index.html` | 40 | time | `|date` | DATA-1 | `<dd class="bg">备注：{$vo.website_remarks}<br />更新时间：{$vo.website_time\|date='Y-m-d` |
| `default_pc/html/website/index.html` | 46 | time | `|date` | DATA-1 | `<div class="lasted-time fn-right">{$vo.website_time\|date='Y-m-d',###}</div>` |
| `default_pc/html/website/search.html` | 40 | time | `|date` | DATA-1 | `<dd class="bg">备注：{$vo.website_remarks}<br />更新时间：{$vo.website_time\|date='Y-m-d` |
| `default_pc/html/website/search.html` | 46 | time | `|date` | DATA-1 | `<div class="lasted-time fn-right">{$vo.website_time\|date='Y-m-d',###}</div>` |
| `default_pc/html/website/show.html` | 100 | time | `|date` | DATA-1 | `<dd class="bg">备注：{$vo.website_remarks}<br />更新时间：{$vo.website_time\|date='Y-m-d` |
| `default_pc/html/website/show.html` | 106 | time | `|date` | DATA-1 | `<div class="lasted-time fn-right">{$vo.website_time\|date='Y-m-d',###}</div>` |
| `default_pc/html/website/type.html` | 40 | time | `|date` | DATA-1 | `<dd class="bg">备注：{$vo.website_remarks}<br />更新时间：{$vo.website_time\|date='Y-m-d` |
| `default_pc/html/website/type.html` | 46 | time | `|date` | DATA-1 | `<div class="lasted-time fn-right">{$vo.website_time\|date='Y-m-d',###}</div>` |
| `default_wap/html/art/detail.html` | 23 | time | `|date` | DATA-1 | `<div class="source"> 来源：{$obj.art_from}&nbsp;&nbsp;&nbsp;发布时间：{$obj.art_time\|da` |
| `default_wap/html/art/rss.html` | 15 | time | `|date` | DATA-1 | `<pubDate>{$art_time\|date='Y-m-d H:i:s',###}</pubDate>` |
| `default_wap/html/art/type.html` | 25 | time | `|date` | DATA-1 | `<li><a href="{:mac_url_art_detail($vo)}">[{$vo.art_time\|date='Y-m-d',###}]{$vo.` |
| `default_wap/html/comment/ajax.html` | 41 | time | `|date` | DATA-1 | `<span class="fr">{$vo.comment_time\|date='Y-m-d H:i:s',###}</span>` |
| `default_wap/html/comment/index.html` | 22 | time | `|date` | DATA-1 | `<span class="fr">{$vo.comment_time\|date='Y-m-d H:i:s',###}</span>` |
| `default_wap/html/gbook/index.html` | 49 | time | `|date` | DATA-1 | `<p class="msg_title"><strong><a class="name" href="javascript:;">{$vo.gbook_name` |
| `default_wap/html/gbook/index.html` | 56 | time | `|date` | DATA-1 | `<p class="msg_title"><strong>站长回复：</strong><span class="time">{$vo.gbook_reply_t` |
| `default_wap/html/map/index.html` | 31 | time | `|date` | DATA-1 | `<div class="lasted-time fn-right">{$vo.vod_time\|date='Y-m-d H:i:s',###}</div>` |
| `default_wap/html/rss/baidu.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_wap/html/rss/bing.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_wap/html/rss/google.html` | 11 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_wap/html/rss/index.html` | 18 | time | `|date` | DATA-1 | `<pubDate>{$vo.vod_time\|date='Y-m-d H:i:s',###}</pubDate>` |
| `default_wap/html/rss/sm.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_wap/html/rss/so.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_wap/html/rss/sogou.html` | 6 | time | `|date` | DATA-1 | `<lastmod>{$vo.vod_time\|date='Y-m-d',###}</lastmod>` |
| `default_wap/html/user/ajax_info.html` | 15 | time | `|mac_day` | **归属未定** | `上次登录时间：{$obj.user_last_login_time\|mac_day=color}` |
| `default_wap/html/user/cards.html` | 35 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.card_use_time\|mac_day}</p>` |
| `default_wap/html/user/cash.html` | 47 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.cash_time\|mac_day}</p>` |
| `default_wap/html/user/downs.html` | 36 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.ulog_time\|mac_day}</p>` |
| `default_wap/html/user/favs.html` | 44 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.ulog_time\|mac_day}</p>` |
| `default_wap/html/user/index.html` | 23 | time | `|date` | DATA-1 | `<span>期限：{if condition="$obj.group_id lt 3"}永久{else}{$obj.user_end_time\|date='Y` |
| `default_wap/html/user/orders.html` | 35 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.order_time\|mac_day}</p>` |
| `default_wap/html/user/plays.html` | 35 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.ulog_time\|mac_day}</p>` |
| `default_wap/html/user/plog.html` | 34 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.plog_time\|mac_day}</p>` |
| `default_wap/html/user/popedom.html` | 22 | time | `|mac_day` | **归属未定** | `<span class="ml10">会员组：{$obj.group.group_name} 期限：{if condition="$obj.group_id l` |
| `default_wap/html/user/reward.html` | 37 | time | `|mac_day` | **归属未定** | `<p class="price">{$vo.user_reg_time\|mac_day}</p>` |
| `default_wap/html/user/upgrade.html` | 22 | time | `|mac_day` | **归属未定** | `<span class="ml10">会员组：[{$obj.group.group_name}] 积分：[{$obj.user_points}] 期限：{if ` |
| `default_wap/html/vod/detail.html` | 30 | time | `|date` | DATA-1 | `<dl><dt>时间：</dt><dd><span id="addtime">{$obj.vod_time\|date='Y-m-d',###}</span><` |
| `default_wap/html/vod/detail.html` | 92 | time | `|date` | DATA-1 | `<dl class="fn-left"><dt>时间：</dt><dd><span>{$obj.vod_time\|date='Y-m-d H:i:s',###` |
| `default_wap/html/vod/down.html` | 55 | time | `|date` | DATA-1 | `<dl class="fn-left"><dt>时间：</dt><dd><span>{$obj.vod_time\|date='Y-m-d H:i:s',###` |
| `default_wap/html/vod/play.html` | 81 | time | `|date` | DATA-1 | `<dl class="fn-left"><dt>时间：</dt><dd><span>{$obj.vod_time\|date='Y-m-d H:i:s',###` |
| `default_wap/html/vod/rss.html` | 16 | time | `|date` | DATA-1 | `<pubDate>{$vod_time\|date='Y-m-d H:i:s',###}</pubDate>` |
| `default_wap/html/vod/search.html` | 30 | time | `|date` | DATA-1 | `<dl><dt>时间：</dt><dd><span id="addtime">{$vo.vod_time\|date='Y-m-d',###}</span></` |

## 8. 逐文件清单（168 个 `.html` 各一行；实现说明列待架构师补）

| 文件 | 标签数 | 语法数 | include | 函数 | 变量 | PHP 代码 | 实现说明（待填） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `default_pc/html/actor/detail.html` | 3 | 3 | 3 | 2 | 47 | 0 |  |
| `default_pc/html/actor/index.html` | 5 | 3 | 3 | 4 | 16 | 0 |  |
| `default_pc/html/actor/rss.html` | 0 | 0 | 0 | 1 | 4 | 0 |  |
| `default_pc/html/actor/show.html` | 6 | 14 | 4 | 11 | 11 | 0 |  |
| `default_pc/html/art/confirm.html` | 0 | 1 | 0 | 1 | 5 | 0 |  |
| `default_pc/html/art/detail.html` | 1 | 8 | 4 | 4 | 18 | 1 |  |
| `default_pc/html/art/detail_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_pc/html/art/index.html` | 3 | 4 | 3 | 3 | 9 | 0 |  |
| `default_pc/html/art/rss.html` | 1 | 0 | 0 | 3 | 8 | 0 |  |
| `default_pc/html/art/search.html` | 0 | 3 | 3 | 0 | 4 | 0 |  |
| `default_pc/html/art/type.html` | 2 | 5 | 4 | 3 | 11 | 0 |  |
| `default_pc/html/comment/ajax.html` | 5 | 3 | 0 | 0 | 26 | 0 |  |
| `default_pc/html/comment/index.html` | 2 | 3 | 3 | 0 | 14 | 0 |  |
| `default_pc/html/gbook/index.html` | 1 | 6 | 4 | 0 | 7 | 0 |  |
| `default_pc/html/gbook/report.html` | 0 | 1 | 0 | 0 | 4 | 0 |  |
| `default_pc/html/index/index.html` | 0 | 0 | 0 | 20 | 0 | 0 |  |
| `default_pc/html/label/rank.html` | 12 | 3 | 3 | 12 | 37 | 0 |  |
| `default_pc/html/map/index.html` | 1 | 5 | 3 | 4 | 14 | 0 |  |
| `default_pc/html/plot/detail.html` | 1 | 5 | 3 | 6 | 18 | 0 |  |
| `default_pc/html/plot/index.html` | 0 | 0 | 0 | 0 | 0 | 0 |  |
| `default_pc/html/public/browser.html` | 0 | 0 | 0 | 0 | 5 | 0 |  |
| `default_pc/html/public/close.html` | 0 | 0 | 0 | 0 | 1 | 0 |  |
| `default_pc/html/public/digg.html` | 0 | 0 | 0 | 0 | 14 | 0 |  |
| `default_pc/html/public/foot.html` | 1 | 0 | 0 | 5 | 5 | 0 |  |
| `default_pc/html/public/head.html` | 2 | 3 | 0 | 7 | 10 | 0 |  |
| `default_pc/html/public/include.html` | 0 | 0 | 0 | 0 | 9 | 0 |  |
| `default_pc/html/public/jump.html` | 0 | 0 | 0 | 0 | 1 | 2 |  |
| `default_pc/html/public/msg.html` | 0 | 0 | 0 | 0 | 1 | 0 |  |
| `default_pc/html/public/paging.html` | 1 | 3 | 0 | 0 | 10 | 0 |  |
| `default_pc/html/public/score.html` | 0 | 0 | 0 | 0 | 4 | 0 |  |
| `default_pc/html/public/star.html` | 0 | 0 | 0 | 0 | 3 | 0 |  |
| `default_pc/html/public/star2.html` | 0 | 10 | 0 | 0 | 4 | 0 |  |
| `default_pc/html/role/detail.html` | 0 | 0 | 0 | 0 | 2 | 0 |  |
| `default_pc/html/role/index.html` | 0 | 0 | 0 | 0 | 0 | 0 |  |
| `default_pc/html/role/show.html` | 0 | 0 | 0 | 0 | 0 | 0 |  |
| `default_pc/html/rss/baidu.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_pc/html/rss/bing.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_pc/html/rss/google.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_pc/html/rss/index.html` | 1 | 0 | 0 | 1 | 6 | 0 |  |
| `default_pc/html/rss/sm.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_pc/html/rss/so.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_pc/html/rss/sogou.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_pc/html/topic/detail.html` | 2 | 4 | 3 | 3 | 22 | 0 |  |
| `default_pc/html/topic/index.html` | 1 | 4 | 4 | 3 | 5 | 0 |  |
| `default_pc/html/user/ajax_info.html` | 0 | 0 | 0 | 2 | 4 | 0 |  |
| `default_pc/html/user/ajax_login.html` | 0 | 1 | 0 | 3 | 0 | 0 |  |
| `default_pc/html/user/bind.html` | 0 | 5 | 3 | 12 | 4 | 0 |  |
| `default_pc/html/user/buy.html` | 0 | 4 | 3 | 12 | 9 | 0 |  |
| `default_pc/html/user/cards.html` | 0 | 7 | 3 | 10 | 19 | 0 |  |
| `default_pc/html/user/cash.html` | 0 | 8 | 3 | 13 | 13 | 0 |  |
| `default_pc/html/user/downs.html` | 0 | 7 | 3 | 10 | 23 | 0 |  |
| `default_pc/html/user/favs.html` | 0 | 11 | 3 | 10 | 19 | 0 |  |
| `default_pc/html/user/findpass.html` | 0 | 2 | 2 | 7 | 6 | 0 |  |
| `default_pc/html/user/findpass_msg.html` | 0 | 2 | 2 | 7 | 6 | 0 |  |
| `default_pc/html/user/foot.html` | 5 | 0 | 0 | 0 | 3 | 0 |  |
| `default_pc/html/user/head.html` | 0 | 0 | 0 | 1 | 4 | 0 |  |
| `default_pc/html/user/include.html` | 0 | 0 | 0 | 0 | 11 | 0 |  |
| `default_pc/html/user/index.html` | 0 | 4 | 2 | 15 | 14 | 0 |  |
| `default_pc/html/user/info.html` | 0 | 5 | 3 | 18 | 10 | 0 |  |
| `default_pc/html/user/login.html` | 0 | 5 | 2 | 4 | 7 | 0 |  |
| `default_pc/html/user/orders.html` | 0 | 10 | 3 | 11 | 20 | 0 |  |
| `default_pc/html/user/pay.html` | 2 | 9 | 3 | 9 | 13 | 0 |  |
| `default_pc/html/user/payment_weixin.html` | 0 | 3 | 3 | 10 | 10 | 0 |  |
| `default_pc/html/user/plays.html` | 0 | 7 | 3 | 10 | 22 | 0 |  |
| `default_pc/html/user/plog.html` | 0 | 8 | 3 | 11 | 12 | 0 |  |
| `default_pc/html/user/popedom.html` | 0 | 12 | 2 | 11 | 11 | 0 |  |
| `default_pc/html/user/reg.html` | 0 | 5 | 2 | 5 | 6 | 0 |  |
| `default_pc/html/user/reward.html` | 0 | 10 | 3 | 13 | 10 | 0 |  |
| `default_pc/html/user/upgrade.html` | 0 | 7 | 3 | 10 | 27 | 0 |  |
| `default_pc/html/vod/confirm.html` | 0 | 2 | 0 | 1 | 6 | 0 |  |
| `default_pc/html/vod/copyright.html` | 0 | 2 | 0 | 0 | 0 | 1 |  |
| `default_pc/html/vod/detail.html` | 6 | 6 | 4 | 9 | 56 | 0 |  |
| `default_pc/html/vod/detail_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_pc/html/vod/down.html` | 0 | 3 | 3 | 1 | 17 | 0 |  |
| `default_pc/html/vod/downer.html` | 0 | 3 | 0 | 4 | 11 | 0 |  |
| `default_pc/html/vod/downer_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_pc/html/vod/index.html` | 34 | 4 | 3 | 61 | 138 | 0 |  |
| `default_pc/html/vod/play.html` | 3 | 5 | 3 | 5 | 36 | 0 |  |
| `default_pc/html/vod/player.html` | 0 | 3 | 0 | 4 | 11 | 0 |  |
| `default_pc/html/vod/player_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_pc/html/vod/plot.html` | 0 | 6 | 4 | 4 | 16 | 1 |  |
| `default_pc/html/vod/role.html` | 0 | 1 | 0 | 1 | 2 | 0 |  |
| `default_pc/html/vod/rss.html` | 2 | 0 | 0 | 3 | 10 | 0 |  |
| `default_pc/html/vod/search.html` | 2 | 8 | 4 | 9 | 46 | 0 |  |
| `default_pc/html/vod/show.html` | 10 | 29 | 4 | 19 | 21 | 0 |  |
| `default_pc/html/vod/type.html` | 14 | 14 | 4 | 21 | 48 | 0 |  |
| `default_pc/html/website/detail.html` | 1 | 4 | 4 | 5 | 37 | 0 |  |
| `default_pc/html/website/index.html` | 1 | 3 | 3 | 5 | 10 | 0 |  |
| `default_pc/html/website/rss.html` | 0 | 0 | 0 | 1 | 4 | 0 |  |
| `default_pc/html/website/search.html` | 1 | 4 | 4 | 4 | 15 | 0 |  |
| `default_pc/html/website/show.html` | 8 | 24 | 4 | 18 | 20 | 0 |  |
| `default_pc/html/website/type.html` | 1 | 4 | 4 | 5 | 14 | 0 |  |
| `default_wap/html/art/confirm.html` | 0 | 1 | 0 | 1 | 5 | 0 |  |
| `default_wap/html/art/detail.html` | 0 | 3 | 3 | 0 | 8 | 0 |  |
| `default_wap/html/art/detail_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_wap/html/art/rss.html` | 1 | 0 | 0 | 3 | 8 | 0 |  |
| `default_wap/html/art/search.html` | 0 | 3 | 3 | 0 | 4 | 0 |  |
| `default_wap/html/art/type.html` | 1 | 4 | 4 | 1 | 6 | 0 |  |
| `default_wap/html/comment/ajax.html` | 5 | 3 | 0 | 0 | 25 | 0 |  |
| `default_wap/html/comment/index.html` | 2 | 3 | 3 | 0 | 14 | 0 |  |
| `default_wap/html/gbook/index.html` | 1 | 6 | 4 | 0 | 8 | 0 |  |
| `default_wap/html/gbook/report.html` | 0 | 1 | 0 | 0 | 4 | 0 |  |
| `default_wap/html/index/index.html` | 13 | 9 | 3 | 17 | 37 | 0 |  |
| `default_wap/html/label/rank.html` | 8 | 11 | 3 | 8 | 17 | 0 |  |
| `default_wap/html/map/index.html` | 1 | 4 | 3 | 1 | 5 | 0 |  |
| `default_wap/html/plot/detail.html` | 0 | 5 | 3 | 2 | 8 | 0 |  |
| `default_wap/html/plot/index.html` | 0 | 0 | 0 | 0 | 0 | 0 |  |
| `default_wap/html/public/browser.html` | 0 | 0 | 0 | 0 | 5 | 0 |  |
| `default_wap/html/public/close.html` | 0 | 0 | 0 | 0 | 1 | 0 |  |
| `default_wap/html/public/digg.html` | 0 | 0 | 0 | 0 | 14 | 0 |  |
| `default_wap/html/public/foot.html` | 0 | 0 | 0 | 0 | 1 | 0 |  |
| `default_wap/html/public/head.html` | 2 | 0 | 0 | 5 | 3 | 0 |  |
| `default_wap/html/public/include.html` | 0 | 0 | 0 | 0 | 7 | 0 |  |
| `default_wap/html/public/jump.html` | 0 | 0 | 0 | 0 | 1 | 2 |  |
| `default_wap/html/public/msg.html` | 0 | 0 | 0 | 0 | 1 | 0 |  |
| `default_wap/html/public/paging.html` | 1 | 2 | 0 | 0 | 10 | 0 |  |
| `default_wap/html/public/score.html` | 0 | 0 | 0 | 0 | 4 | 0 |  |
| `default_wap/html/public/star.html` | 0 | 0 | 0 | 0 | 3 | 0 |  |
| `default_wap/html/public/star2.html` | 0 | 10 | 0 | 0 | 4 | 0 |  |
| `default_wap/html/rss/baidu.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/rss/bing.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/rss/google.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/rss/index.html` | 1 | 0 | 0 | 1 | 6 | 0 |  |
| `default_wap/html/rss/sm.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/rss/so.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/rss/sogou.html` | 1 | 0 | 0 | 1 | 1 | 0 |  |
| `default_wap/html/topic/detail.html` | 2 | 3 | 3 | 3 | 18 | 0 |  |
| `default_wap/html/topic/index.html` | 1 | 5 | 5 | 2 | 4 | 0 |  |
| `default_wap/html/user/ajax_info.html` | 0 | 0 | 0 | 2 | 4 | 0 |  |
| `default_wap/html/user/ajax_login.html` | 0 | 1 | 0 | 3 | 0 | 0 |  |
| `default_wap/html/user/bind.html` | 0 | 3 | 1 | 3 | 3 | 0 |  |
| `default_wap/html/user/buy.html` | 0 | 2 | 1 | 3 | 5 | 0 |  |
| `default_wap/html/user/cards.html` | 0 | 2 | 1 | 2 | 6 | 0 |  |
| `default_wap/html/user/cash.html` | 0 | 3 | 1 | 3 | 7 | 0 |  |
| `default_wap/html/user/downs.html` | 0 | 2 | 1 | 2 | 13 | 0 |  |
| `default_wap/html/user/favs.html` | 0 | 6 | 1 | 1 | 14 | 0 |  |
| `default_wap/html/user/findpass.html` | 0 | 2 | 2 | 6 | 2 | 0 |  |
| `default_wap/html/user/findpass_msg.html` | 0 | 2 | 2 | 6 | 2 | 0 |  |
| `default_wap/html/user/foot.html` | 0 | 0 | 0 | 0 | 2 | 0 |  |
| `default_wap/html/user/head.html` | 0 | 0 | 0 | 0 | 0 | 0 |  |
| `default_wap/html/user/include.html` | 4 | 0 | 0 | 0 | 1 | 0 |  |
| `default_wap/html/user/index.html` | 0 | 5 | 1 | 15 | 11 | 0 |  |
| `default_wap/html/user/info.html` | 0 | 3 | 1 | 6 | 8 | 0 |  |
| `default_wap/html/user/login.html` | 0 | 3 | 2 | 5 | 1 | 0 |  |
| `default_wap/html/user/orders.html` | 0 | 5 | 1 | 3 | 7 | 0 |  |
| `default_wap/html/user/pay.html` | 2 | 7 | 1 | 1 | 11 | 0 |  |
| `default_wap/html/user/payment_weixin.html` | 0 | 1 | 1 | 3 | 5 | 0 |  |
| `default_wap/html/user/plays.html` | 0 | 2 | 1 | 1 | 13 | 0 |  |
| `default_wap/html/user/plog.html` | 0 | 3 | 1 | 1 | 6 | 0 |  |
| `default_wap/html/user/popedom.html` | 0 | 11 | 1 | 1 | 7 | 0 |  |
| `default_wap/html/user/reg.html` | 0 | 5 | 2 | 4 | 1 | 0 |  |
| `default_wap/html/user/reward.html` | 0 | 5 | 1 | 4 | 4 | 0 |  |
| `default_wap/html/user/upgrade.html` | 0 | 5 | 1 | 1 | 21 | 0 |  |
| `default_wap/html/vod/confirm.html` | 0 | 2 | 0 | 1 | 6 | 0 |  |
| `default_wap/html/vod/copyright.html` | 0 | 2 | 0 | 0 | 0 | 1 |  |
| `default_wap/html/vod/detail.html` | 5 | 4 | 3 | 5 | 40 | 0 |  |
| `default_wap/html/vod/detail_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_wap/html/vod/down.html` | 1 | 3 | 3 | 2 | 20 | 0 |  |
| `default_wap/html/vod/downer.html` | 0 | 2 | 0 | 3 | 11 | 0 |  |
| `default_wap/html/vod/downer_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_wap/html/vod/play.html` | 5 | 3 | 3 | 3 | 31 | 0 |  |
| `default_wap/html/vod/player.html` | 0 | 2 | 0 | 3 | 11 | 0 |  |
| `default_wap/html/vod/player_pwd.html` | 0 | 1 | 0 | 0 | 5 | 0 |  |
| `default_wap/html/vod/plot.html` | 0 | 4 | 3 | 2 | 12 | 0 |  |
| `default_wap/html/vod/rss.html` | 2 | 0 | 0 | 3 | 10 | 0 |  |
| `default_wap/html/vod/search.html` | 2 | 5 | 4 | 5 | 45 | 0 |  |
| `default_wap/html/vod/show.html` | 4 | 10 | 4 | 11 | 17 | 0 |  |
| `default_wap/html/vod/type.html` | 4 | 10 | 4 | 11 | 16 | 0 |  |
