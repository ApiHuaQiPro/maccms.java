# -*- coding: utf-8 -*-
# v5 补丁：在 v4 生成的附表 D 上修订 Q-006-a～d；运行：python -I .work/patch_d5.py
# 依赖：.work/tabs.json（附表 A 的 26 张表与 503 列）；所有表名、列名对照 tabs.json 校验，写错即中止。
import json, re, sys

D = 'docs/prd/APPK-2/D-feature-table-field.md'
s = open(D, encoding='utf-8').read()
tabs = json.load(open('.work/tabs.json', encoding='utf-8'))
cols = {t: [c[0] for c in v['cols']] for t, v in tabs.items()}
assert len(cols) == 26 and sum(len(v) for v in cols.values()) == 503


def chk(t, cs):
    t = t if t.startswith('mac_') else 'mac_' + t
    for c in cs:
        assert c in cols[t], (t, c)
    return cs


def cl(t, cs):
    return '、'.join('`%s`' % c for c in chk(t, cs))


def rep(a, b, cnt=1):
    global s
    assert s.count(a) == cnt, (a[:60], s.count(a))
    s = s.replace(a, b)


def set_line(prefix, new):
    global s
    lines = s.split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith(prefix) and l.count('|') >= 7]
    assert len(idx) == 1, (prefix, idx)
    lines[idx[0]] = new
    s = '\n'.join(lines)


def sub_line(prefix, a, b):
    global s
    lines = s.split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith(prefix) and l.count('|') >= 7]
    assert len(idx) == 1, (prefix, idx)
    assert lines[idx[0]].count(a) == 1, (prefix, a[:60], lines[idx[0]].count(a))
    lines[idx[0]] = lines[idx[0]].replace(a, b)
    s = '\n'.join(lines)


CT = ['vod', 'art', 'topic', 'actor', 'role', 'website']
TS_TAG = ['mac_vod', 'mac_art', 'mac_topic', 'mac_actor', 'mac_role', 'mac_website', 'mac_comment', 'mac_gbook',
          'mac_type', 'mac_link', 'mac_vod_search', 'mac_group']
NOT_TAG = sorted(set(cols) - set(TS_TAG))
assert len(TS_TAG) == 12 and len(NOT_TAG) == 14 and 'mac_user' in NOT_TAG
TS_TAG_TXT = '、'.join('`%s`' % t for t in TS_TAG)
NOT_TAG_TXT = '、'.join('`%s`' % t for t in NOT_TAG)
VS_WRITE = ('`mac_vod_search`：搜索优化开启时 INSERT 整行（8 列）、UPDATE `search_hit_count`、`search_last_hit_time`'
            '（`vod` 标签和 `Search` 页带关键词类参数 `wd`、`name`、`tag`、`class`、`actor`、`director` 时，源码 `common/model/VodSearch.php` '
            '`getResultIdList` 第 37-60 行）')
chk('mac_vod_search', ['search_hit_count', 'search_last_hit_time'])

SORT_COMMON = ['id', 'time', 'time_add', 'score', 'hits', 'hits_day', 'hits_week', 'hits_month', 'up', 'down', 'level']
SORTS = {'vod': SORT_COMMON, 'art': SORT_COMMON, 'topic': SORT_COMMON, 'role': SORT_COMMON, 'actor': SORT_COMMON,
         'website': SORT_COMMON + ['referer', 'referer_day', 'referer_week', 'referer_month'],
         'comment': ['id', 'time', 'up', 'down'], 'gbook': ['id', 'time', 'reply_time'],
         'type': ['id', 'sort'], 'link': ['id', 'sort']}


def sort_txt(p):
    fb = p + '_id' if p in ('type', 'link') else p + '_time'
    chk(p, [p + '_' + x for x in SORTS[p]] + [fb])
    m = '；'.join('%s→`%s_%s`' % (x, p, x) for x in SORTS[p])
    return 'by 取值 %s；其他取值一律回落为 `%s`' % (m, fb)


# ---------------------------------------------------------------- 头部
rep('> 属于 `docs/prd/APPK-2.md`（v4）。', '> 属于 `docs/prd/APPK-2.md`（v5）。')
rep('v4 起：本表不再使用通配写法和开放表达',
    'v5 起：①评论/留言标签不读 `mac_user`（头像只读库外文件，见 D.2 CS-AVATAR）；②配置文件固定为 D.2 CS-CONFIG 的 13 个文件（含 `route.php`）；③DYN-2、DYN-3、DYN-7 里的 `<表>`、`<前缀>`、`SHOW COLUMNS FROM <表>` 等占位全部换成具体表名和列名，并新增 D.5（26 张表逐列锁定）、D.6（19 个标签逐项映射 TAGMAP-1～TAGMAP-19）、D.7（Ajax data 返回列 RET-1～RET-7）；④对照源码修正 v4 的两处事实：标签读取表集合 TS-TAG 由 11 张改为 12 张（增加 `mac_group`），`mac_vod_search` 在搜索优化开启时会被前台关键词查询写入。v4 起：本表不再使用通配写法和开放表达')
rep('4. 所有写入只做 `INSERT/UPDATE/DELETE`，不含 DDL（BR-1）。',
    '4. 所有写入只做 `INSERT/UPDATE/DELETE`，不含 DDL（BR-1）。\n   路径写法：D 表里的 `extra/x.php` 都指 `application/extra/x.php`，`route.php` 指 `application/route.php`；文件与配置栏的取值范围见 D.2 CS-CONFIG；D.2 的 CS-AVATAR（头像文件，只读存在性判断）同时适用于渲染模板的前台行 D-01、D-02、D-03、D-04、D-06 和登录行 D-07a。')
rep('①该表是否列在该行的读取栏或写入栏（方向要对：读项必须在读取栏，写项必须在写入栏）；②该列是否被该行列出，或该行对此表写的是“整行”；③若该行标注“判定见 DYN-n”，还必须满足 D.3 中 DYN-n 的允许集合；④文件/配置项是否列在该行的“文件与配置”栏。',
    '①该表是否列在该行的读取栏或写入栏（方向要对：读项必须在读取栏，写项必须在写入栏）；②该列是否被该行列出（“整行”按 D.5 对应表的全部列），或该行对此表写的是“整行”；③若该行标注“判定见 DYN-n”，还必须满足 D.3 中 DYN-n 的允许集合（DYN-n 引用的 D.6、D.7、D.5 逐项映射同样是允许集合）；④文件/配置项是否列在该行的“文件与配置”栏，且配置文件还必须属于 DYN-8 的 CS-CONFIG 13 个文件。')

# ---------------------------------------------------------------- D-01
sub_line('| D-01 |', '只读，且限于 **TS-TAG**（D.2）列出的 11 张表：`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search`；',
         '读取限于 **TS-TAG**（D.2）列出的 12 张表：' + TS_TAG_TXT + '；')
sub_line('| D-01 |', '（读取范围与排序列见 D-02 和 D.3 DYN-1/DYN-2）', '（逐标签的读取表、排序列见 D.6 TAGMAP-1～TAGMAP-19，判定见 DYN-1、DYN-2）')
sub_line('| D-01 |', '（`Map`/`Rss`/`Search` 读 `mac_vod`、`mac_art`，均在 TS-TAG 内）' if False else '均在 TS-TAG 内）。超出 TS-TAG 的表（例如 `mac_user` 的密码列、`mac_admin`）不得读取，判定见 DYN-1',
         '均在 TS-TAG 内）。TS-TAG 以外的 14 张表一律不得读取，其中整张 `mac_user`（包括 `user_id`、`user_portrait` 在内的全部 30 列）都不得读取，判定见 DYN-1')
sub_line('| D-01 |', '| 无 | `template/<dir>/<html>/` 目录（含其下 `label/` 目录里的 `.html` 文件）、',
         '| ' + VS_WRITE + '；除此之外不写任何表 | `template/default/html/` 目录（含其下 `label/` 目录里的 `.html` 文件；目录名取自 `extra/maccms.php` `site` 节的 `template_dir` 键、`html_dir` 键，基线值分别为 `default`、`html`）、')
sub_line('| D-01 |', '`application/route.php`；验证码与会话在库外', '`application/route.php`（只读；框架启动时加载路由规则，写入方是 D-21 的 `System`）、D.2 CS-AVATAR 的头像文件（只读）；验证码与会话在库外')

# ---------------------------------------------------------------- D-02
tag_ext = ('`area`、`lang`、`class`、`year`、`version`、`state`：取值清单来自 `extra/maccms.php` `app` 节的 `vod_extend_area`、`vod_extend_lang`、`vod_extend_class`、`vod_extend_year`、`vod_extend_version`、`vod_extend_state`；'
           '标签带分类编号（`tid`）大于 0 时，改读 `mac_type` 的分类缓存（整行，实际取用其中的 `type_extend` 列，`common/model/Extend.php` 第 116-121 行等）；'
           '`letter`：不读表、不读配置（固定 A～Z 与 0-9）；`foreach`、`for`：不读表')
d02 = ('| D-02 | FR-3 | Maccms 标签库 19 个标签 | 模板中 `{maccms:xxx}` | '
       '逐标签的读取表、读取列、排序列、筛选列见 D.6 TAGMAP-1～TAGMAP-19，下面是同一结论的汇总：'
       '`vod`：`mac_vod` 整行（82 列）、`mac_type` 整行（20 列，分类缓存）、`mac_group` 整行（10 列，会员组缓存）、`mac_vod_search` 整行（8 列，搜索优化开启时）；'
       '`art`：`mac_art` 整行（46 列）、`mac_type`、`mac_group`；'
       '`topic`：`mac_topic` 整行（37 列），`topic_rel_vod`/`topic_rel_art` 指向的 `mac_vod`/`mac_art` 整行，以及这两张内容表带出的 `mac_type`、`mac_group`；'
       '`actor`：`mac_actor` 整行（42 列）、`mac_type`；'
       '`role`：`mac_role` 整行（29 列），`role_rid` 关联的 `mac_vod` 整行，以及 `mac_type`、`mac_group`；'
       '`website`：`mac_website` 整行（42 列）、`mac_type`、`mac_group`；'
       '`comment`：`mac_comment` 整行（14 列，含按 `comment_pid` 读取的子评论，同一张表），再按评论的 `comment_mid` 读目标内容 1→`mac_vod`、2→`mac_art`、3→`mac_topic`、8→`mac_actor`、9→`mac_role`、11→`mac_website`（各整行，`common/model/Comment.php` 第 56-92 行），目标内容带出的 `mac_type`、`mac_group`；**不读 `mac_user` 的任何列**，返回里的 `user_id` 是 `mac_comment.user_id` 本表列，`user_portrait` 不是库里的列，由库外头像文件判断得出（D.2 CS-AVATAR）；'
       '`gbook`：`mac_gbook` 整行（10 列），`user_id` 是 `mac_gbook.user_id` 本表列，`user_portrait` 同 `comment`，**不读 `mac_user`**；'
       '`type`：`mac_type` 整行；`link`：`mac_link` 整行（8 列）；' + tag_ext +
       '；每个标签的**排序列**只能取 D.6 对应行“排序”栏逐项列出的列；**筛选条件**只作用于该标签自己的表（评论标签含其目标内容表）在 D.5 里的列，不得引入其他表 | '
       '`mac_vod_search`：搜索优化开启时 INSERT 整行（8 列）、UPDATE `search_hit_count`、`search_last_hit_time`（仅 `vod` 标签带关键词类参数时，判定见 DYN-1）；其他表不写（`cachetime` 只写库外缓存文件） | '
       '`extra/maccms.php`、`runtime/cache`（库外）、D.2 CS-AVATAR 的头像文件（只读，`comment`、`gbook` 标签使用） |')
set_line('| D-02 |', d02)

# ---------------------------------------------------------------- D-03/04/06/07a/07b 补充
sub_line('| D-03 |', '| 本类方法不直接写库；计数写入见 D-05，扣积分见 D-07 |', '| 本类方法不直接写库；计数写入见 D-05，扣积分见 D-07；页面模板里的 `{maccms:xxx}` 标签读取按 DYN-1，其中 `mac_vod_search` 的写入同 D-01 |')
sub_line('| D-03 |', '`static/js/playerconfig.js` |', '`static/js/playerconfig.js`（只读）、D.2 CS-AVATAR 的头像文件（只读） |')
sub_line('| D-04 |', '| 不直接写库；计数写入见 D-05 | `extra/maccms.php` |', '| 不直接写库；计数写入见 D-05；页面模板里的 `{maccms:xxx}` 标签读取按 DYN-1，其中 `mac_vod_search` 的写入同 D-01 | `extra/maccms.php`、D.2 CS-AVATAR 的头像文件（只读） |')
sub_line('| D-06 |', '`extra/maccms.php`（评论/留言开关、审核、间隔）、验证码 |', '`extra/maccms.php`（评论/留言开关、审核、间隔）、验证码、D.2 CS-AVATAR 的头像文件（只读） |')
sub_line('| D-07a |', '`extra/maccms.php` 的 `connect` 节（第三方登录）；登录态在库外 |', '`extra/maccms.php` 的 `connect` 节（第三方登录）、D.2 CS-AVATAR 的头像文件（登录成功时 `common/model/User.php` 第 390 行用其写 cookie，只读存在性）；登录态在库外 |')
sub_line('| D-07b |', '| `upload/` 目录文件 |', '| `upload/user/` 下 `0`～`9` 十个子目录里的头像 `.jpg`（D.2 CS-AVATAR，写；`common/model/Upload.php` 第 187-206 行同时更新 `mac_user.user_portrait`） |')

# ---------------------------------------------------------------- D-05
sug = '；'.join('`mac_%s`：%s' % (p, cl(p, [p + '_id', p + '_name', p + '_en', p + '_pic'])) for p in CT)
sub_line('| D-05 |', '`suggest`：只读 D.2 CS-SUGGEST 的列；', '`suggest`（mid 1,2,3,8,9,11 对应表同 DYN-3）：只读各表下面这 4 列（对应 D.2 的 CS-SUGGEST-vod、CS-SUGGEST-art、CS-SUGGEST-topic、CS-SUGGEST-actor、CS-SUGGEST-role、CS-SUGGEST-website）：' + sug + '；')
sub_line('| D-05 |', '`hits`/`score`/`digg`：读被计数行的主键与被更新列（D.2 CS-HITS/CS-DIGG/CS-SCORE 即下列写入列）；',
         '`hits`/`score`/`digg`：读被计数行的主键（`vod_id`、`art_id`、`topic_id`、`actor_id`、`role_id`、`website_id`、`comment_id` 中与 mid 对应的一个）与下面写入栏逐列列出的被更新列（即 D.2 的 CS-HITS-vod～CS-HITS-website、CS-DIGG-vod～CS-DIGG-comment、CS-SCORE-vod～CS-SCORE-website）；')
sub_line('| D-05 |', '`pwd`：只读 D.2 CS-PWD 的列 |', '`pwd`：`mac_vod`：' + cl('vod', ['vod_pwd', 'vod_pwd_url', 'vod_pwd_play', 'vod_pwd_play_url', 'vod_pwd_down', 'vod_pwd_down_url']) + '（D.2 CS-PWD-VOD）；`mac_art`：' + cl('art', ['art_pwd', 'art_pwd_url']) + '（D.2 CS-PWD-ART） |')
sub_line('| D-05 |', '**但**不返回 D.2 CS-DATA-EX 列出的列，且非会员再不返回 CS-VOD-HIDE 的 8 列；',
         '**但**不返回 D.2 的 CS-DATA-EX-vod～CS-DATA-EX-website（每表 2 列），且 `vod` 对非会员再不返回 CS-VOD-HIDE 的 8 列；每张表实际返回的逐列清单见 D.7 RET-1～RET-7；')

# ---------------------------------------------------------------- D-10 / D-17 / D-18 / D-19 / D-21 / D-22 / D-23
sub_line('| D-10 |', '`runtime/` 缓存目录、`extra/quickmenu.php` |', '`runtime/` 缓存目录、`extra/quickmenu.php`、`static/js/playerconfig.js`（`admin/controller/Base.php` 第 102 行写） |')
sub_line('| D-17 |', '`upload/`、`extra/maccms.php`（upload 节） |', '`upload/`（后台上传目录）、`upload/user/` 下 `0`～`9` 十个子目录（前台头像，D.2 CS-AVATAR）、`extra/maccms.php`（upload 节） |')
allt = '、'.join('`%s`' % t for t in sorted(cols))
sub_line('| D-18 |', '`SHOW COLUMNS FROM <表>`（<表> ∈ 附表 A 的 26 张表）、`SHOW CREATE TABLE <表>`（同）、导出备份时对 26 张表执行 `SELECT *`',
         '`SHOW COLUMNS FROM` 与 `SHOW CREATE TABLE`：对 D.5 LK-01～LK-26 的 26 张表各执行一次（表名逐个列在 D.5 与 DYN-7）、导出备份时对这 26 张表执行 `SELECT *`')
sub_line('| D-18 |', '对表 T 的列 F 执行且只执行一条 `UPDATE T SET F=REPLACE(F,:findstr,:tostr) WHERE 1=1`；允许集合 = T ∈ 附表 A 的 26 张表、F ∈ 附表 A 中 T 的任一列（旧版校验 F ∈ `SHOW COLUMNS FROM T`，',
         '对 D.5 中某一行的表和该行“列”栏里的某一列（二者组成 (表, 列) 对，共 503 个）执行且只执行一条形如 `UPDATE 表名 SET 列名=REPLACE(列名,:findstr,:tostr) WHERE 1=1` 的语句（表名、列名取该 (表, 列) 对；旧版校验该列属于 `SHOW COLUMNS` 的结果，')
sub_line('| D-19 |', '各内容表的 `<前缀>_time_make` 列：', '六张内容表各自的 time_make 列（逐表列出）：')
sub_line('| D-21 |', '（`var_export` 格式，BR-2；清单见 D.3 DYN-8） |',
         '（`var_export` 格式，BR-2）、`application/route.php`（`System` 保存 `rewrite` 配置时由 `admin/controller/System.php` 第 231 行写出）；以上 13 个文件即 D.2 CS-CONFIG，判定见 DYN-8；另写 `static/js/tj.js`（`System.php` 第 124 行）、`static/js/playerconfig.js`（`System.php` 第 683 行）、`static/player/` 目录下各播放器的 `.js`（`Vodplayer` 保存时写） |')
sub_line('| D-22 |', '`addons/<插件名>/info.ini`、`addons/<插件名>/config.php`、`extra/addons.php` |',
         '`addons/adminloginbg/` 目录（基线里唯一的插件目录：`info.ini`、`config.php`、`Adminloginbg.php`、`controller/Index.php`）、`extra/addons.php`；安装、卸载其他插件时对 `addons/` 的读写见 OP-8（OP-8 决定前不执行） |')
sub_line('| D-23 |', '| `application/database.php`、`extra/maccms.php`、`data/install/install.lock` |',
         '| `application/database.php`（写，`install/controller/Index.php` 第 435 行）、`extra/maccms.php`（写，同文件第 215 行）、`application/data/install/install.lock`（写，同文件第 266 行） |')

# ---------------------------------------------------------------- D.2
rep('| TS-TAG | 11 张表 | `mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search` | `common/taglib/Maccms.php` 各标签调用的 `common/model/*` 查询 |',
    '| TS-TAG | 12 张表（v4 写 11 张，漏了 `mac_group`） | ' + TS_TAG_TXT + '（`mac_group` 来自 `Vod`、`Art`、`Website` 模型 `listData` 的会员组缓存，`common/model/Vod.php` 第 53 行等） | `common/taglib/Maccms.php` 各标签调用的 `common/model/*` 查询 |')
av = ('| CS-AVATAR | 文件 | `upload/user/0/`～`upload/user/9/` 十个子目录里、文件名为“user_id 数值加 `.jpg`”的文件（子目录号 = user_id 除以 10 的余数；user_id 取自被返回的 `mac_comment.user_id` 或 `mac_gbook.user_id`，登录时取自登录用户）；找不到时用 `static/images/touxiang.png`。**只做文件是否存在的判断，不读库** | `common.php` 第 1205-1215 行 `mac_get_user_portrait`（函数体只有 `file_exists`）；调用点：`common/model/Comment.php` 第 43、52 行、`common/model/Gbook.php` 第 33 行、`common/model/User.php` 第 390 行 |')
cfg_rows = [
    ('addons.php', '`Addon`（D-22）；本期只读（OP-8 决定前不改）'),
    ('bind.php', '`Collect`（D-14）第 194、222 行写；`common/model/Collect.php` 第 205 行读（D-14 采集入库；D-08 的 `Receive` 也经 Collect 模型入库，同样只读）'),
    ('captcha.php', '全目录检索 `mac_arr2file` 没有写入点，本期只读'),
    ('domain.php', '`Domain`（D-21）第 35、61、109 行写'),
    ('maccms.php', '`System`（D-21）、`Urlsend`（D-21）、安装向导（D-23）写；前台、API 读'),
    ('queue.php', '没有写入点，本期只读'),
    ('quickmenu.php', '`admin/Index`（D-10）第 123 行写'),
    ('timming.php', '`admin/Timming`（D-21）、`api/Timming`（D-09）写'),
    ('version.php', '没有写入点，本期只读'),
    ('voddowner.php', '`Voddowner`（D-21）写'),
    ('vodplayer.php', '`Vodplayer`（D-21）写'),
    ('vodserver.php', '`Vodserver`（D-21）写'),
]
cfg = ('| CS-CONFIG | 文件，共 13 个 | CS-EXTRA 的 12 个文件加 `application/route.php`（路由规则，由 `System` 保存 `rewrite` 配置时生成，`admin/controller/System.php` 第 231 行；前台由框架加载，只读）。'
       '各文件的写入方：' + '；'.join('`%s`：%s' % x for x in cfg_rows) + '；`route.php`：`System`（D-21）写，前台 D-01 读 | 上列各行的源码位置；`ls php/application/extra`、`ls php/application/route.php` |')
_i = s.index('| CS-EXTRA |')
_j = s.index('\n', _i)
s = s[:_j] + '\n' + cfg + '\n' + av + s[_j:]

# ---------------------------------------------------------------- v4 -> v5 对照
rep('## D.3 动态场景：允许集合、来源规则、越界判定',
    '''**v4 → v5 修订对照（评审 Q-006-a～d）**

| 编号 | v4 写法 | v5 处理 | 源码依据 |
| --- | --- | --- | --- |
| Q-006-a | D-02、DYN-2 允许 `comment`、`gbook` 标签读 `mac_user` 的 `user_id`、`user_portrait`；DYN-1 又把 `mac_user` 列为越界 | 删除 D-02/DYN-2 对 `mac_user` 的读取；D-01、D-02、DYN-1、DYN-2、D.6 的 TAGMAP-10/11 统一为“标签不读 `mac_user` 的任何列”；`user_id` 是 `mac_comment`/`mac_gbook` 本表列 | `common/model/Comment.php` 第 39-52 行、`Gbook.php` 第 31-34 行：只用查询结果里的 `$v['user_id']` 调函数，没有读 `mac_user` |
| Q-006-b | D-01 的文件栏有 `application/route.php`，DYN-8 只允许 12 个 `extra/*.php` | 新增 D.2 CS-CONFIG（13 个文件 = 12 个 `extra/*.php` + `route.php`），写明每个文件的写入方和来源；D-01 对 `route.php` 只读，D-21 写；DYN-8 改为以 CS-CONFIG 为允许集合 | `admin/controller/System.php` 第 231 行 `mac_arr2file(APP_PATH . 'route.php', $route)` |
| Q-006-c | DYN-2/DYN-3/DYN-7 用 `CS-SORT-<表>`、`CS-SUGGEST-<表>`、`<前缀>_*`、`SHOW COLUMNS FROM <表>`、`UPDATE T SET F` | DYN-2 改引用 D.6（19 个标签逐项）；DYN-3 逐表写出列名并引用 D.7（Ajax data 返回列 RET-1～RET-7）；DYN-7 逐个列出 26 张表并引用 D.5（26 张表共 503 列逐列）；AC-16 改为直接引用这三张表 | 同 v4 的源码行，另见 D.6 各行 |
| Q-006-d | 写“用户头像经 `mac_get_user_portrait` 只读 `mac_user`：`user_id`、`user_portrait`” | 删除；`mac_get_user_portrait` 只做 `file_exists`，头像文件读取放在库外，新增 D.2 CS-AVATAR | `common.php` 第 1205-1215 行 |
| 顺带更正 | D-01 写“只读”“TS-TAG 11 张表”“不写任何表”；D-02 写 `area`/`lang` 等标签“不读表” | TS-TAG 改为 12 张（加 `mac_group`）；`mac_vod_search` 搜索优化开启时被 INSERT/UPDATE（D-01、D-02、D-03、D-04、DYN-1）；`area`、`lang`、`class`、`year`、`version`、`state` 带 `tid` 时读 `mac_type` 的 `type_extend`；D-10、D-21 补写入的 `static/js` 文件，D-22 把按插件名的占位写法换成基线里的 `addons/adminloginbg/`，D-01 的模板目录写法由两个尖括号占位换成基线值 `default`、`html` | `common/model/Vod.php` 第 53 行、`VodSearch.php` 第 37-60 行、`Extend.php` 第 116-121 行等、`admin/controller/Base.php` 第 102 行、`System.php` 第 124、683 行、`php/addons/` 目录 |

## D.3 动态场景：允许集合、来源规则、越界判定''')

# ---------------------------------------------------------------- D.3
d1 = ('| DYN-1 | D-01、D-03、D-04、D-06 | 前台页面由模板标签/页面方法取数 | ①D-01：整行只读 TS-TAG 的 12 张表（D.2）：' + TS_TAG_TXT +
      '；②D-03、D-04、D-06：控制器自己的读写按各行读取栏、写入栏判定，页面模板里的 `{maccms:xxx}` 标签读取按 D-01 的 12 张表和 D.6 逐项判定；'
      '③全部四行共同的唯一写入：' + VS_WRITE + '；不写任何其他表，也不写 `mac_vod_search` 的其他列、不 DELETE | '
      '页面所在模板里出现的 `{maccms:xxx}` 标签（D-02、D.6）和 D-01 列出的页面方法；`mac_vod_search` 的写入来自 `common/model/VodSearch.php` 第 37-60 行 | '
      '读取 TS-TAG 以外的 14 张表（' + NOT_TAG_TXT + '）中任一张的任一列（`mac_user` 的 `user_id`、`user_portrait` 也在内）；写 `mac_vod_search` 以外的表；对 `mac_vod_search` 写 INSERT 整行、UPDATE `search_hit_count`、`search_last_hit_time` 以外的内容或 DELETE；D-01 读取 `route.php` 和 CS-AVATAR 以外的 `extra/*.php` 以外的配置文件（见 DYN-8）= 越界 |')
d1 = d1.replace('D-01 读取 `route.php` 和 CS-AVATAR 以外的 `extra/*.php` 以外的配置文件（见 DYN-8）', '文件与配置超出该行文件栏或 DYN-8 的 CS-CONFIG（见 DYN-8）')
set_line('| DYN-1 |', d1)

d2 = ('| DYN-2 | D-02 | 标签的数据表、筛选与排序 | 逐标签的读取表、读取列、排序列、筛选列、库外读取，一律以 D.6 的 TAGMAP-1（`link`）、TAGMAP-2（`area`）、TAGMAP-3（`lang`）、TAGMAP-4（`year`）、TAGMAP-5（`class`）、TAGMAP-6（`version`）、TAGMAP-7（`state`）、TAGMAP-8（`letter`）、TAGMAP-9（`type`）、TAGMAP-10（`comment`）、TAGMAP-11（`gbook`）、TAGMAP-12（`role`）、TAGMAP-13（`actor`）、TAGMAP-14（`topic`）、TAGMAP-15（`art`）、TAGMAP-16（`vod`）、TAGMAP-17（`website`）、TAGMAP-18（`foreach`）、TAGMAP-19（`for`）各行为准；每行引用的“整行”列集合见 D.5；排序 `by="rnd"` 是随机取行，不指定列 | '
      '源码：`common/model/{Vod,Art,Topic,Actor,Role,Website,Comment,Gbook,Type,Link}.php` 的 `in_array($by, [...])` 白名单；`Extend.php`；`common/taglib/Maccms.php` 的标签属性表。白名单中 `Actor`、`Website` 还有 `in`，对应 `actor_in`、`website_in`，这两列不在附表 A，不属于允许集合，Java 侧不得为此新增列 | '
      '标签读取该标签 TAGMAP 行以外的表或列、用 JOIN/子查询读到其他表、排序列不在该行“排序”栏、读取 `mac_user` 的任何列、或写 `mac_vod_search` 以外的表 = 越界 |')
set_line('| DYN-2 |', d2)

ex_names = '、'.join('CS-DATA-EX-' + p for p in CT)
d3 = ('| DYN-3 | D-05 | Ajax 的 data / suggest / pwd / hits / digg / score / referer | '
      '①`data`：对 mid 1→`mac_vod`、2→`mac_art`、3→`mac_topic`、8→`mac_actor`、9→`mac_role`、11→`mac_website`，返回列逐列见 D.7 RET-1（`vod` 会员）、RET-2（`vod` 非会员）、RET-3（`art`）、RET-4（`topic`）、RET-5（`actor`）、RET-6（`role`）、RET-7（`website`）；'
      '②`suggest`：mid 1→`mac_vod`：`vod_id`、`vod_name`、`vod_en`、`vod_pic`；2→`mac_art`：`art_id`、`art_name`、`art_en`、`art_pic`；3→`mac_topic`：`topic_id`、`topic_name`、`topic_en`、`topic_pic`；8→`mac_actor`：`actor_id`、`actor_name`、`actor_en`、`actor_pic`；9→`mac_role`：`role_id`、`role_name`、`role_en`、`role_pic`；11→`mac_website`：`website_id`、`website_name`、`website_en`、`website_pic`；筛选列只有下列两列：`vod_name`、`vod_en`；`art_name`、`art_en`；`topic_name`、`topic_en`；`actor_name`、`actor_en`；`role_name`、`role_en`；`website_name`、`website_en`；排序列只有该表主键 `vod_id`、`art_id`、`topic_id`、`actor_id`、`role_id`、`website_id`；'
      '③`pwd`（mid 1、2）：`vod`：`vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`；`art`：`art_pwd`、`art_pwd_url`；'
      '④写：`hits`→D-05 写入栏 hits 段（D.2 CS-HITS-vod、CS-HITS-art、CS-HITS-topic、CS-HITS-actor、CS-HITS-role、CS-HITS-website），`digg`→D-05 写入栏 digg 段（CS-DIGG-vod、CS-DIGG-art、CS-DIGG-topic、CS-DIGG-actor、CS-DIGG-role、CS-DIGG-website、CS-DIGG-comment），`score`→D-05 写入栏 score 段（CS-SCORE-vod、CS-SCORE-art、CS-SCORE-topic、CS-SCORE-actor、CS-SCORE-role、CS-SCORE-website），`referer`→`mac_website` 的 5 列；写入的 mid→表：`hits`、`score` 为 1→`mac_vod`、2→`mac_art`、3→`mac_topic`、8→`mac_actor`、9→`mac_role`、11→`mac_website`；`digg` 另有 4→`mac_comment` | '
      '`Ajax.php` 第 24-38、94-118、155-160、305-311、350-357、402-421 行的 `mid` 校验、`$field` 与 `unset`（`mid`=4 的表名来自 `common.php` `mac_get_mid_code`：4→`comment`） | '
      '返回了 D.7 对应行以外的列（含 ' + ex_names + ' 的列，非会员时含 CS-VOD-HIDE 的列）、suggest 返回或筛选了上列以外的列、写入栏以外的列、或 `mid` 不在上述映射内仍写库 = 越界 |')
set_line('| DYN-3 |', d3)

d7 = ('| DYN-7 | D-18 | 数据库工具的表与列 | `SHOW TABLE STATUS`、`SHOW COLUMNS FROM`、`SHOW CREATE TABLE`、`SELECT *`：表限于 D.5 的 26 张表，即 ' + allt +
      '；`rep`：(表, 列) 对限于 D.5 各行“列”栏里列出的 503 个对，语句形态唯一为 `UPDATE 表名 SET 列名=REPLACE(列名,?,?) WHERE 1=1`（表名、列名取该 (表, 列) 对）；`import`、`sql`、`optimize`、`repair`：本期允许集合为空，见 OP-6（OP-6 决定前一律不执行） | '
      '`admin/controller/Database.php`：`isValidTable`（第 325-333 行，取自 `SHOW TABLE STATUS`）、`rep`（第 287-316 行，列取自 `SHOW COLUMNS`） | '
      '表不在 D.5 的 26 张内、列不在该表在 D.5 的列内、`rep` 带其他 WHERE/SET、执行任何 DDL 或 `OPTIMIZE`/`REPAIR` = 越界 |')
set_line('| DYN-7 |', d7)

ex_files = 'addons.php、bind.php、captcha.php、domain.php、maccms.php、queue.php、quickmenu.php、timming.php、version.php、voddowner.php、vodplayer.php、vodserver.php'
cfg_rows_ids = [l.split('|')[1].strip() for l in s.split(chr(10)) if l.startswith('| D-') and ('extra/' in l.split('|')[-2] or 'route.php' in l.split('|')[-2])]
print('DYN-8 rows', cfg_rows_ids)
d8 = ('| DYN-8 | 所有“文件与配置”栏列出 `application/extra/` 下的 .php 文件或 `route.php` 的行（' + '、'.join(cfg_rows_ids) + '） | 配置文件读写 | '
      '只能读写 D.2 CS-CONFIG 的 13 个文件：`application/extra/` 下的 ' + '、'.join('`%s`' % f for f in ex_files.split('、')) + ' 共 12 个，加 `application/route.php`；每个文件的读写方向以 CS-CONFIG 行的“写入方”为准（只读文件不得写）；`maccms.php` 内共 22 个顶层节：`db`、`site`、`app`、`user`、`gbook`、`comment`、`upload`、`interface`、`pay`、`collect`、`api`、`connect`、`weixin`、`view`、`path`、`rewrite`、`email`、`play`、`sms`、`extra`、`seo`、`urlsend`，这些节都在同一个文件里，不是独立文件。'
      '本条只判 CS-CONFIG 里的文件；D 行里的其他文件和目录（`template/`、`upload/`、`static/`、`runtime/`、`application/data/backup/`、静态页输出目录、CS-AVATAR）只按越界判定第④步看该行文件栏 | '
      '`ls php/application/extra`、`php/application/route.php`；节名见 `extra/maccms.php`；写入点见 CS-CONFIG 行 | '
      '读写 CS-CONFIG 以外的配置文件、写入方向与 CS-CONFIG 行不符（例如写 `captcha.php`）、或把配置写进数据库表 = 越界（配置不入库，BR-2） |')
set_line('| DYN-8 |', d8)

d9 = ('| DYN-9 | D-22、D-23 | 插件与安装向导 | D-22：不执行插件自带 SQL，允许集合为空，见 OP-8；D-23：只写 `mac_admin`（首个管理员，整行）、`mac_group` 3 行、`mac_type` 18 行（均为 INSERT），建库建表允许集合为空，见 OP-7；D-23 写的文件只有 `application/database.php`、`application/extra/maccms.php`、`application/data/install/install.lock` | D-22、D-23 行及 D.1 | '
      '插件或安装向导里出现任何 DDL，写以上三表以外的表，或写以上三个文件以外的文件 = 越界 |')
set_line('| DYN-9 |', d9)

# ---------------------------------------------------------------- D.5 / D.6 / D.7
out = ['', '## D.5 26 张表逐列锁定（供 D-18、DYN-2、DYN-7 和所有“整行”引用）', '',
       '“整行”= 本表“列”栏全部列，列名和顺序与附表 A 一致（由 `.work/tabs.json` 生成，写错会中止）。', '',
       '| 编号 | 表 | 列数 | 列（逐列） |', '| --- | --- | --- | --- |']
LK = {}
for i, t in enumerate(sorted(cols), 1):
    LK[t] = 'LK-%02d' % i
    out.append('| %s | `%s` | %d | %s |' % (LK[t], t, len(cols[t]), '、'.join('`%s`' % c for c in cols[t])))
assert len(LK) == 26 and sum(len(v) for v in cols.values()) == 503

tag_rows = []


def tagrow(n, tag, tables, colsx, sortx, filt, ext):
    tag_rows.append('| TAGMAP-%d | `%s` | %s | %s | %s | %s | %s |' % (n, tag, tables, colsx, sortx, filt, ext))


def whole(t):
    return '`%s` 整行（%s，%d 列）' % (t, LK[t], len(cols[t]))


tc = whole('mac_type')
gc = whole('mac_group')
ext_none = '无'
cfg_keys = {'area': 'vod_extend_area', 'lang': 'vod_extend_lang', 'year': 'vod_extend_year', 'class': 'vod_extend_class',
            'version': 'vod_extend_version', 'state': 'vod_extend_state'}
tagrow(1, 'link', '`mac_link`', whole('mac_link'), sort_txt('link'), '`mac_link`（%s）的列；属性 `type` 对应 `link_type`' % LK['mac_link'], ext_none)
chk('mac_link', ['link_type'])
for n, tg in zip(range(2, 8), ['area', 'lang', 'year', 'class', 'version', 'state']):
    tagrow(n, tg, '无表；`tid` 大于 0 时读 `mac_type`', '`tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `%s` 项，经分类缓存读整行 %s）' % (tg, LK['mac_type']),
           '无 `by` 属性，`order` 只决定正序、倒序', '无（不筛选表）', '`extra/maccms.php` 的 `app` 节 `%s`' % cfg_keys[tg])
chk('mac_type', ['type_extend'])
tagrow(8, 'letter', '无', '无', '无 `by` 属性', '无', '无配置；清单固定为 A～Z 与 0-9')
tagrow(9, 'type', '`mac_type`', tc, sort_txt('type'), '`mac_type`（%s）的列；属性 `mid` 对应 `type_mid`' % LK['mac_type'], ext_none)
chk('mac_type', ['type_mid'])
tgt = '；'.join('`comment_mid`=%d→`%s`' % (m, t) for m, t in [(1, 'mac_vod'), (2, 'mac_art'), (3, 'mac_topic'), (8, 'mac_actor'), (9, 'mac_role'), (11, 'mac_website')])
chk('mac_comment', ['comment_pid', 'comment_rid', 'comment_mid', 'user_id', 'comment_id', 'comment_status'])
tagrow(10, 'comment', '`mac_comment`（含子评论）；目标内容 ' + tgt + '；以及目标内容带出的 `mac_type`、`mac_group`',
       whole('mac_comment') + '；目标内容各表整行（' + '、'.join('`%s` %s' % (t, LK[t]) for t in ['mac_vod', 'mac_art', 'mac_topic', 'mac_actor', 'mac_role', 'mac_website']) + '）；' + tc + '；' + gc + '。**不读 `mac_user` 的任何列**',
       sort_txt('comment'),
       '`mac_comment`（%s）的列；属性 `rid`、`pid`、`mid`、`uid` 依次对应 `comment_rid`、`comment_pid`、`comment_mid`、`user_id`；固定条件 `comment_status`=1，未给 `pid` 时再加 `comment_pid`=0（`common/model/Comment.php` 第 164-177 行）' % LK['mac_comment'],
       'D.2 CS-AVATAR：返回项 `user_portrait` 来自头像文件是否存在，不是库里的列')
chk('mac_gbook', ['gbook_rid', 'user_id', 'gbook_id'])
tagrow(11, 'gbook', '`mac_gbook`', whole('mac_gbook') + '。**不读 `mac_user` 的任何列**', sort_txt('gbook'),
       '`mac_gbook`（%s）的列；属性 `rid`、`uid` 依次对应 `gbook_rid`、`user_id`' % LK['mac_gbook'], 'D.2 CS-AVATAR：同 `comment`')
tagrow(12, 'role', '`mac_role`；`role_rid` 关联的 `mac_vod`；`mac_vod` 带出的 `mac_type`、`mac_group`',
       whole('mac_role') + '；' + whole('mac_vod') + '；' + tc + '；' + gc, sort_txt('role'),
       '`mac_role`（%s）的列；属性 `rid` 对应 `role_rid`' % LK['mac_role'], ext_none)
chk('mac_role', ['role_rid'])
tagrow(13, 'actor', '`mac_actor`；`mac_type`', whole('mac_actor') + '；' + tc, sort_txt('actor') + '；`actor_in` 不在附表 A，不允许',
       '`mac_actor`（%s）的列' % LK['mac_actor'], ext_none)
tagrow(14, 'topic', '`mac_topic`；`topic_rel_vod` 指向的 `mac_vod`、`topic_rel_art` 指向的 `mac_art`；这两张表带出的 `mac_type`、`mac_group`',
       whole('mac_topic') + '；' + whole('mac_vod') + '；' + whole('mac_art') + '；' + tc + '；' + gc, sort_txt('topic'),
       '`mac_topic`（%s）的列' % LK['mac_topic'], ext_none)
tagrow(15, 'art', '`mac_art`；`mac_type`；`mac_group`', whole('mac_art') + '；' + tc + '；' + gc, sort_txt('art'),
       '`mac_art`（%s）的列' % LK['mac_art'], ext_none)
tagrow(16, 'vod', '`mac_vod`；`mac_type`；`mac_group`；`mac_vod_search`（搜索优化开启时读写）',
       whole('mac_vod') + '；' + tc + '；' + gc + '；' + whole('mac_vod_search') + '；`mac_vod_search` 的写入：INSERT 整行、UPDATE `search_hit_count`、`search_last_hit_time`', sort_txt('vod'),
       '`mac_vod`（%s）的列；关键词类参数 `wd`、`name`、`tag`、`class`、`actor`、`director` 依次作用于 `vod_name`（含配置的附加规则列）、`vod_name`、`vod_tag`、`vod_class`、`vod_actor`、`vod_director`' % LK['mac_vod'], ext_none)
chk('mac_vod', ['vod_name', 'vod_tag', 'vod_class', 'vod_actor', 'vod_director'])
tagrow(17, 'website', '`mac_website`；`mac_type`；`mac_group`', whole('mac_website') + '；' + tc + '；' + gc,
       sort_txt('website') + '；`website_in` 不在附表 A，不允许', '`mac_website`（%s）的列' % LK['mac_website'], ext_none)
tagrow(18, 'foreach', '无', '无', '无', '无', '无')
tagrow(19, 'for', '无', '无', '无', '无', '无')

out += ['', '## D.6 19 个标签逐项映射（供 D-02、DYN-1、DYN-2 引用）', '',
        '数据来源：`common/taglib/Maccms.php` 第 9-27 行的标签表（19 个标签）和 `common/model/` 目录下各模型的 `listData`、`infoData`、`Extend.php`。表中“整行（LK-nn）”按 D.5 的该表全部列。', '',
        '| 编号 | 标签 | 读取的表（只读，另有说明的除外） | 读取的列 | 排序 | 筛选列 | 库外与配置 |', '| --- | --- | --- | --- | --- | --- | --- |']
out += tag_rows

ret = ['', '## D.7 Ajax data 返回列逐项（供 D-05、DYN-3 引用）', '',
       '返回列 = D.5 该表全部列去掉 D.2 中同名前缀的 CS-DATA-EX 行（CS-DATA-EX-vod、CS-DATA-EX-art、CS-DATA-EX-topic、CS-DATA-EX-actor、CS-DATA-EX-role、CS-DATA-EX-website，每表 2 列）；`vod` 对非会员再去掉 D.2 CS-VOD-HIDE（8 列）。来源：`index/controller/Ajax.php` 第 56 行、第 60-61 行的 `unset`。', '',
       '| 编号 | 对应 mid 与表 | 对象 | 返回列数 | 返回列（逐列） |', '| --- | --- | --- | --- | --- |']
VOD_HIDE = ['vod_play_from', 'vod_play_server', 'vod_play_note', 'vod_play_url', 'vod_down_from', 'vod_down_server', 'vod_down_note', 'vod_down_url']
chk('vod', VOD_HIDE)
specs = [(1, 'vod', '1 → `mac_vod`', '会员', []), (2, 'vod', '1 → `mac_vod`', '非会员', VOD_HIDE), (3, 'art', '2 → `mac_art`', '所有人', []),
         (4, 'topic', '3 → `mac_topic`', '所有人', []), (5, 'actor', '8 → `mac_actor`', '所有人', []),
         (6, 'role', '9 → `mac_role`', '所有人', []), (7, 'website', '11 → `mac_website`', '所有人', [])]
for n, p, mt, who, hide in specs:
    ex = [p + '_time_hits', p + '_time_make'] + hide
    chk(p, ex)
    keep = [c for c in cols['mac_' + p] if c not in ex]
    assert len(keep) == len(cols['mac_' + p]) - len(ex)
    ret.append('| RET-%d | %s | %s | %d | %s |' % (n, mt, who, len(keep), '、'.join('`%s`' % c for c in keep)))
out += ret

marker = '\n## D.4 附表 C 登记范围 → 功能行（覆盖核对）'
assert s.count(marker) == 1
tail = s[s.index(marker):].rstrip('\n')
s = s[:s.index(marker)].rstrip('\n') + '\n' + tail + '\n' + '\n'.join(out) + '\n'

# 历史说明里的通配/占位写法改为文字描述，使整个文件只有两张“对照表”里还保留旧写法原文
s = re.sub(r'（v3 写法 `[^`]*` 的展开结果[^）]*）', '（展开对照见“v3 → v4 展开对照”表）', s)
rep('v3 只写了 `vod_down_*`，源码', 'v3 只写了下载 4 列，源码')
s = s.replace('<表>', '（表名）').replace('<前缀>', '（前缀）')
# 越界判定、覆盖范围段里的 v4 字样
rep('D.1～D.4 与 D-01～D-23 同属一个锁定版本（BR-10）', 'D.1～D.7 与 D-01～D-23 同属一个锁定版本（BR-10）')
open(D, 'w', encoding='utf-8', newline='\n').write(s)
print('ok', len(s))
