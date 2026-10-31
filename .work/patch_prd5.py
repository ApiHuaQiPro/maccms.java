# -*- coding: utf-8 -*-
# v5：主文同步修订（评审 Q-006-a～d）。运行：python -I -X utf8 .work/patch_prd5.py
P = 'docs/prd/APPK-2.md'
s = open(P, encoding='utf-8').read()
lines = s.split('\n')


def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:50], s.count(a))
    s = s.replace(a, b)


def line_starting(prefix):
    idx = [i for i, l in enumerate(s.split('\n')) if l.startswith(prefix)]
    assert len(idx) == 1, (prefix, idx)
    return idx[0]


def set_line(prefix, new):
    global s
    L = s.split('\n')
    L[line_starting(prefix)] = new
    s = '\n'.join(L)


rep('| 版本 | v4 |', '| 版本 | v5 |')
rep('| 状态 | 评审中（第 3 轮修订） |', '| 状态 | 评审中（评审第 3 轮升级人类后，经人类授权的 Q-006 修订） |')

set_line('- [附表 D]',
         '- [附表 D](APPK-2/D-feature-table-field.md)：27 行功能（D-01～D-23，含 D-07a～D-07e）逐项列出读取的表与字段、写入的表与字段（“整行”= 附表 A 中该表全部列，逐列见 D.5；指定列逐列列出，不使用通配符和占位写法）、不在库里的文件与配置；D.1 列出所有“装不下”的位置；D.2 命名列集合（TS-TAG 12 张表、CS-CONFIG 13 个配置文件、CS-AVATAR 头像文件、CS-HITS-vod～CS-HITS-website、CS-SORT-vod～CS-SORT-link 等，逐列展开）；D.3 动态场景 DYN-1～DYN-9 各写允许集合、来源规则和越界判定；D.4 把附表 C 的 379 行逐行映射到 D 编号（脚本核对无遗漏）；D.5 26 张表 503 列逐列锁定（LK-01～LK-26）；D.6 19 个标签逐项映射（TAGMAP-1～TAGMAP-19）；D.7 Ajax data 返回列逐项（RET-1～RET-7）。表名、列名由脚本对照附表 A 逐列校验。')

set_line('| AC-16 |',
         '| AC-16 | FR-13 | 附表 D 已锁定（v5：27 行 D-01～D-23 + D.2 命名列集合 + D.3 DYN-1～DYN-9 + D.4 映射 + D.5 LK-01～LK-26 + D.6 TAGMAP-1～TAGMAP-19 + D.7 RET-1～RET-7） | 评审任一迁移项的设计单与实现 | ①设计单“读写的表与字段”一栏逐项按附表 D 开头的“越界判定”四步判定：表在该行读取栏/写入栏（方向正确）、列在该行或 D.2、D.5 对应集合内、DYN-n 引用的 D.6/D.7/D.5 逐项映射满足、文件配置在该行“文件与配置”栏且配置文件属于 D.2 CS-CONFIG 的 13 个文件；任一为否即越界；②设计单对应的附表 C 行，每行在 D.4 都有唯一的 D 编号，设计单的读写范围按该 D 行判定；③出现越界项时，设计单被退回并新增 OP-（或修订本文增补附表 D，版本 +1）；④D 表中“整行”所指的表都在附表 A 的 26 张之内，列数与 D.5 一致（合计 503 列）；⑤在附表 D 里检索 `<`、`>`、`_*`、`*_`、“通配”“按规则”“由……决定”，检索范围是 D-01～D-23 的表体、D.3、D.5、D.6、D.7，命中 0 处（D.2 之后的两张“v3 → v4 展开对照”“v4 → v5 修订对照”表只引用旧写法原文，不在检索范围内；`SELECT *` 是字面 SQL，不算通配）；⑥D-01 的页面、`comment`/`gbook` 标签、D-02 都不读 `mac_user` 的任何列：在 D-01、D-02、D.6 的 TAGMAP-10、TAGMAP-11、DYN-1、DYN-2 里检索 `mac_user`，只出现在“不读”“越界”的表述中；⑦D-01 的文件栏里每个配置文件（`extra/maccms.php`、`extra/captcha.php`、`route.php`）都在 CS-CONFIG 内，D.2 与 DYN-8 的 CS-CONFIG 都是 13 个文件 |')

rep('| v4 | 2026-10-31 |', '| v4 | 2026-10-31 |')  # 存在性断言
# 追加 v5 修订记录：放在 v4 行之后
L = s.split('\n')
i4 = line_starting('| v4 | 2026-10-31 |')
v5 = ('| v5 | 2026-10-31 | 评审第 3 轮升级人类后，发起人回复“同意继续修订 Q-006”，只修订附表 D 与对应的主文引用，不动 Q-005 的分母（379 行 = 376 个真实功能行 + 3 个无功能占位行）。'
      '**Q-006-a**：评论/留言标签不读 `mac_user`，删除 D-02/DYN-2 对 `user_id`、`user_portrait` 的 `mac_user` 读取，D-01、DYN-1、DYN-2、D.6 TAGMAP-10/11 同一结论。'
      '**Q-006-b**：新增 D.2 CS-CONFIG（12 个 `extra/*.php` + `application/route.php` 共 13 个文件，逐文件写明写入方和源码位置），D-01 对 `route.php` 只读、D-21 写，DYN-8 以 CS-CONFIG 为允许集合。'
      '**Q-006-c**：DYN-2、DYN-3、DYN-7 里的 `CS-SORT-（表名）`、`CS-SUGGEST-（表名）`、前缀占位、`SHOW COLUMNS FROM （表名）` 全部换成具体表名和列名，并新增 D.5（26 张表 503 列逐列）、D.6（19 个标签逐项）、D.7（Ajax data 返回列逐项），AC-16 直接引用。'
      '**Q-006-d**：`mac_get_user_portrait` 只做 `file_exists`，删除没有源码依据的 `mac_user.user_portrait` 读取，新增 CS-AVATAR 头像文件（库外只读）。'
      '**对照源码顺带更正（v4 的事实错误）**：①TS-TAG 由 11 张改为 12 张（`vod`/`art`/`website` 等标签经会员组缓存读 `mac_group`）；②`vod` 标签和搜索页带关键词参数且搜索优化开启时，会 INSERT/UPDATE `mac_vod_search`（v4 写“只读、不写任何表”不对），D-01、D-02、D-03、D-04、DYN-1 已写明；③`area`、`lang`、`class`、`year`、`version`、`state` 标签带 `tid` 时读 `mac_type.type_extend`（v4 写“不读表”不对）；④评论标签按 `comment_mid` 读目标内容表（v4 未写）；⑤D-10、D-21 补写 `static/js/*.js`，D-22 改为基线里的 `addons/adminloginbg/`，D-01 的模板目录占位换成基线值。'
      '范围、OP-1～OP-14 的内容与状态未改；没有创建开发单、没有写代码 | 评审第 3 轮升级人类后的授权（Issue 评论 `01a190ef-b8eb-703a-acfc-eab17afe48a9` “同意继续修订 Q-006”）；智能调度-海外 转派评论 `01a190f0-48fe-7e65-97fd-8e22484618f4`；产品评审 FAIL 评论中的 Q-006-a～d |')
L.insert(i4 + 1, v5)
s = '\n'.join(L)

rep('v4 对 Q-005/Q-006 的处理见 §15；',
    'v4 对 Q-005/Q-006 的处理、v5 对 Q-006-a～d 的处理见 §15；v5 复核：附表 D 的 D-01～D-23、D.3、D.5～D.7 检索 `<`、`>`、`_*`、`*_`、“通配”“按规则”“由……决定”命中 0 处；D.4 仍是 379 行（376 + 3）全部映射到唯一 D 编号；D.5 为 26 张表 503 列；D.2 与 DYN-8 的 CS-CONFIG 都是 13 个文件；')
rep('附表 D（27 行 + D.2 命名列集合 + D.3 动态场景，列名脚本校验）',
    '附表 D（27 行 + D.2 命名列集合 + D.3 动态场景 + D.5 逐列锁定 + D.6 标签映射 + D.7 返回列，列名脚本校验）')
rep('④附表 D 中“指定列”取源码写入点附近该表字段的并集，宁多勿少，未逐语句运行验证（D.3 的标签排序白名单、Ajax 返回列、`rep` 的表与列、`Cj` 发布目标表四处已对照源码行核对）；',
    '④附表 D 中“指定列”取源码写入点附近该表字段的并集，宁多勿少，未逐语句运行验证（D.3 的标签排序白名单、Ajax 返回列、`rep` 的表与列、`Cj` 发布目标表、v5 新增的标签读取表与 `mac_get_user_portrait`、`route.php` 写入点五处已对照源码行核对；标签读取表只核对了各模型的 `listData`、`infoData` 和 `Extend.php`，没有逐个运行模板验证）；')
open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
