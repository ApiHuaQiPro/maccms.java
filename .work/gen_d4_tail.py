import re,collections
f=lambda s:s.replace('|','\\|')
ALLT=sorted(cols)
OTHER=[t for t in ALLT if t not in TAG_TABLES]
assert len(ALLT)==26 and len(OTHER)==15

# ---------- C 登记簿 -> D 编号 ----------
crow=[]
for l in open('docs/prd/APPK-2/C-parity-registry.md',encoding='utf-8'):
    m=re.match(r'\| (R-\d+|I-\d) \| (\w+) \| `?([^|`]+)`? \| `?([^|]*?)`? \|',l)
    if m: crow.append(m.groups())
assert len(crow)==379,len(crow)
U={'D-07a':'index login logout reg regcheck reg_msg oauth logincallback bindmsg bind unbind findpass findpass_msg findpass_reset ajax_login ajax_info'.split(),
   'D-07b':'info portrait'.split(),
   'D-07c':'ajax_buy_popedom buy upgrade popedom'.split(),
   'D-07d':'favs plays downs ulog ajax_ulog ulog_del plog plog_del reward visit'.split(),
   'D-07e':'pay gopay qrcode orders order_info cards cash cash_del comment gbook'.split()}
um={m:d for d,ms in U.items() for m in ms}
FM={('index',x):'D-01' for x in 'Index Map Rss Help Note Search Label Verify Qrcode MyError Base'.split()}
FM.update({('index','Vod'):'D-03',('index','Ajax'):'D-05',('index','Comment'):'D-06',('index','Gbook'):'D-06',('index','Payment'):'D-07e'})
FM.update({('index',x):'D-04' for x in 'Art Actor Role Topic Website Plot'.split()})
FM.update({('api',x):'D-09' for x in 'Index Timming Wechat Base'.split()})
FM.update({('api','Provide'):'D-08',('api','Receive'):'D-08'})
FM.update({('admin',x):'D-10' for x in ['Index','Base']})
FM.update({('admin',x):'D-11' for x in 'Vod Art Actor Role Topic Website'.split()})
FM[('admin','Type')]='D-12'
FM.update({('admin',x):'D-13' for x in 'User Group Card Cash Order Plog Ulog Comment Gbook Link Visit Admin Annex'.split()})
FM.update({('admin','Collect'):'D-14',('admin','Cj'):'D-15',('admin','Images'):'D-16',('admin','Upload'):'D-17',('admin','Database'):'D-18',
           ('admin','Make'):'D-19',('admin','Template'):'D-20',('admin','Addon'):'D-22',('install','Index'):'D-23'})
FM.update({('admin',x):'D-21' for x in 'System Domain Vodplayer Voddowner Vodserver Timming Urlsend Safety Update'.split()})
cnt=collections.Counter(); byfile=collections.OrderedDict()
for rid,side,fl,meth in crow:
    fl=fl.replace('.php','')
    if (side,fl)==('index','User'):
        d=um[meth.strip('` ')]
    else:
        d=FM[(side,fl)]
    cnt[d]+=1
    byfile.setdefault((side,fl),collections.Counter())[d]+=1
assert sum(cnt.values())==379
assert sum(len(v) for v in U.values())==42==sum(1 for r in crow if r[1]=='index' and r[2]=='User.php')

L=[]
L.append('# APPK-2 附表 D：功能 → 现有表 → 字段 逐项清单\n')
L.append('> 属于 `docs/prd/APPK-2.md`（v4）。冲突时以主文件为准。表名/列名全部取自附表 A（脚本生成并逐列校验，写错列名会使生成失败）。v4 起：本表不再使用通配写法和开放表达（只在“v3 → v4 展开对照”表里引用 v3 的旧写法）；动态场景在 D.3 逐个给出允许集合、来源规则和越界判定；D.2 给出命名的列集合。**D.1～D.4 与 D-01～D-23 同属一个锁定版本（BR-10）。**\n')
L.append('**写法约定**：\n1. “整行”= 该表附表 A 中的全部列（括号里写列数，列名以附表 A 为准）。\n2. “指定列”= 本表或 D.2 命名集合里逐列列出的列，不得推广到同表其他列。\n3. 读取栏只写表（“整行”）的，表示 `SELECT` 可取该表任意列；某行单独写了“不返回/只读”的列限制时，以该限制为准。\n4. 所有写入只做 `INSERT/UPDATE/DELETE`，不含 DDL（BR-1）。\n5. 指定列取自 PHP 源码该写入点附近出现的该表字段并集（宁多勿少），可由 `php/application` 下对应控制器/模型的更新语句复核；未逐语句运行验证。\n')
L.append('**越界判定（唯一规则，AC-16 引用）**：对设计单或实现里的每一个读写项（表、列、文件/配置），在其所属功能行（D-01～D-23）依次判：①该表是否列在该行的读取栏或写入栏（方向要对：读项必须在读取栏，写项必须在写入栏）；②该列是否被该行列出，或该行对此表写的是“整行”；③若该行标注“判定见 DYN-n”，还必须满足 D.3 中 DYN-n 的允许集合；④文件/配置项是否列在该行的“文件与配置”栏。任一为“否”= **越界**，按 BR-8/RISK-8 停下并新增 OP-，不得自行扩大。\n')
L.append('**覆盖范围**：附表 C 登记簿 `index`、`api`、`admin`、`install` 共 379 行，每一行在 D.4 都映射到唯一的一个 D 编号（脚本核对：379 行全部映射，无遗漏、无重复）；D-02（标签库）、D-17 中的“前台头像上传”没有独立的 C 行，由 FR-3 / AC-3 与 D-07b 的 C 行验收。\n')
L.append('| 编号 | 对应 FR | 功能 | 入口（旧控制器方法） | 读取的表与字段 | 写入的表与字段 | 文件与配置（不在库里） |\n| --- | --- | --- | --- | --- | --- | --- |')
for i,fr,feat,entry,reads,writes,files,note in rows:
    L.append('| %s | %s | %s | %s | %s | %s | %s |'%(i,fr,f(feat),f(entry),f(reads),f(writes),f(files)))

L.append('\n## D.1 发现“装不下”的位置（供 OP- 使用）\n')
L.append('| 位置 | 旧版行为 | 为什么与 BR-1 冲突 | 当前处理 |\n| --- | --- | --- | --- |')
L.append('| `admin/Database.php` 还原备份、执行 SQL、优化表、修复表 | 执行备份文件或任意 SQL；`OPTIMIZE/REPAIR TABLE` | 备份含 `DROP/CREATE TABLE`；任意 SQL 可含 DDL | OP-6 |')
L.append('| `install/Index.php` `mkDatabase` | 建库建表 | DDL | OP-7 |')
L.append('| `think\\addons\\Service` 导入插件 `install.sql` | 插件建表 | DDL | OP-8 |')
L.append('| `admin/Vod.php:103-105`、`Art.php:51-53`、`Website.php:50-52` | 查重时 `DROP/CREATE TABLE `mac_tmpvod`、`mac_tmpart`、`mac_tmpwebsite`` | DDL | Java 已用子查询替代（D-B13-7），不需要决定；列入 AC-15 的 SNAP-2 守护 |')
L.append('| `common/util/Database.php:118-119` | 备份文件里写 `DROP TABLE IF EXISTS` 文本 | 只是导出文本，不执行；还原时才执行 | 随 OP-6 |')

# ---------- D.2 ----------
L.append('\n## D.2 命名列集合（逐列展开，供 D-02、D-05 和 D.3 引用）\n')
L.append('| 集合 | 所属表 | 包含的列（逐列） | 来源（PHP） |\n| --- | --- | --- | --- |')
L.append('| TS-TAG | 11 张表 | '+tb(*TAG_TABLES)+' | `common/taglib/Maccms.php` 各标签调用的 `common/model/*` 查询 |')
L.append('| CS-TYPE-TPL | `mac_type` | '+cl('type',['type_tpl','type_tpl_list','type_tpl_detail','type_tpl_play','type_tpl_down'])+'（v3 写法 `type_tpl*` 的展开结果，共 5 列） | `Type` 模型取分类模板 |')
for p in CT:
    L.append('| CS-HITS-%s | `mac_%s` | %s | `index/controller/Ajax.php` hits |'%(p,p,cl(p,[p+'_'+s for s in ['hits','hits_day','hits_week','hits_month','time_hits']])))
for p in CT+['comment']:
    L.append('| CS-DIGG-%s | `mac_%s` | %s | Ajax digg |'%(p,p,cl(p,[p+'_up',p+'_down'])))
for p in CT:
    L.append('| CS-SCORE-%s | `mac_%s` | %s | Ajax score |'%(p,p,cl(p,[p+'_score',p+'_score_num',p+'_score_all'])))
for p in CT:
    L.append('| CS-SUGGEST-%s | `mac_%s` | %s（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |'%(p,p,cl(p,SUG[p])))
for p in CT:
    L.append('| CS-DATA-EX-%s | `mac_%s` | %s | `Ajax.php` data 第 56 行 `unset` |'%(p,p,cl(p,DATA_EX[p])))
L.append('| CS-VOD-HIDE | `mac_vod` | '+cl('vod',VOD_HIDE)+'（非会员时不返回；v3 只写了 `vod_down_*`，源码实际 `unset` 的是播放 4 列加下载 4 列，v4 更正） | `Ajax.php` data 第 60-61 行 |')
L.append('| CS-PWD-VOD | `mac_vod` | '+cl('vod',PWD['vod'])+'（v3 写法 `vod_pwd*` 的展开结果） | `Ajax.php` pwd |')
L.append('| CS-PWD-ART | `mac_art` | '+cl('art',PWD['art'])+'（v3 写法 `art_pwd*` 的展开结果） | `Ajax.php` pwd |')
for p in SORTS:
    L.append('| CS-SORT-%s | `mac_%s` | %s | `common/model/%s.php` 的 `in_array($by, [...])` 白名单 |'%(p,p,cl(p,sortcols(p)),''.join(w.capitalize() for w in p.split('_'))))
L.append('| CS-EXTRA | 文件 | `application/extra/` 下现有 12 个文件：`addons.php`、`bind.php`、`captcha.php`、`domain.php`、`maccms.php`、`queue.php`、`quickmenu.php`、`timming.php`、`version.php`、`voddowner.php`、`vodplayer.php`、`vodserver.php` | `ls php/application/extra` |')
L.append('\n**v3 → v4 通配/开放表达展开对照**\n')
L.append('| v3 写法 | 所在行 | v4 处理 |\n| --- | --- | --- |')
for a,b,c in [
 ('`type_tpl*`','D-02','D-02 的 `type` / `vod` 读取改为 `mac_type` 整行（20 列）；其中模板列 5 列见 CS-TYPE-TPL'),
 ('`*_id/*_name/*_en/*_pic`','D-05 suggest','CS-SUGGEST-<表>（6 张表各 4 列）'),
 ('`*_status=1`','D-05 data','六张表各自的 `<前缀>_status`，已在 D-05 逐列写出'),
 ('`*_time_hits`、`*_time_make`','D-05 data','CS-DATA-EX-<表>（6 张表各 2 列）'),
 ('`vod_down_*`','D-05 data','CS-VOD-HIDE（8 列，含播放 4 列，更正 v3 漏项）'),
 ('`vod_pwd*`、`art_pwd*`','D-05 pwd','CS-PWD-VOD（6 列）、CS-PWD-ART（2 列）'),
 ('`*_time_make`','D-19','改为逐表逐列（D-19 已写出 6 列）'),
 ('“由模板里的标签决定”','D-01','TS-TAG 11 张表 + DYN-1 越界判定'),
 ('“可按 A 中 vod 任意列筛选/排序”','D-02','筛选列 = `mac_vod` 的 A 中列（82 列整行）；排序列限 CS-SORT-vod（DYN-2）'),
 ('“按规则 `SHOW COLUMNS` 映射，限 vod/art/actor/role/website 对应的列”','D-15','`mid`=1 → `mac_vod`，`mid`=2 → `mac_art`，只有这两张表（源码 `Cj.php` 第 121-124 行、`content_into`），DYN-6'),
 ('“列限于 A 中该表的文本列”','D-18','更正为 A 中该表的任一列（源码只校验列在 `SHOW COLUMNS` 中），DYN-7'),
 ('`extra/connect`、`extra/pay`、`extra/rewrite`','D-07a、D-07e、D-01','这三个文件不存在，对应配置在 `extra/maccms.php` 的 `connect`、`pay`、`rewrite` 节，已更正'),
 ('“`application/extra/*.php` 等”','D-21','CS-EXTRA 12 个文件，DYN-8')]:
    L.append('| %s | %s | %s |'%(f(a),b,f(c)))

# ---------- D.3 ----------
L.append('\n## D.3 动态场景：允许集合、来源规则、越界判定\n')
L.append('| 编号 | 对应 D 行 | 动态场景 | 允许集合（逐项） | 来源规则 | 越界判定 |\n| --- | --- | --- | --- | --- | --- |')
other='、'.join('`mac_%s`'%t for t in OTHER)
dyn=[]
dyn.append(('DYN-1','D-01','前台页面由模板里的标签/页面方法取数','只读 TS-TAG 的 11 张表（D.2）；不写任何表','页面所在模板里出现的 `{maccms:xxx}` 标签（D-02）和 D-01 列出的页面方法','读取 TS-TAG 以外的 15 张表（'+other+'）中任一张，或写任何表 = 越界'))
tagrows=[('vod','`mac_vod`、`mac_type`、`mac_vod_search`'),('art','`mac_art`、`mac_type`'),('topic','`mac_topic`、`mac_vod`、`mac_art`'),('actor','`mac_actor`'),('role','`mac_role`、`mac_vod`'),('website','`mac_website`'),('comment','`mac_comment`，加 `mac_user` 的 `user_id`、`user_portrait`'),('gbook','`mac_gbook`，加 `mac_user` 的 `user_id`、`user_portrait`'),('type','`mac_type`'),('link','`mac_link`')]
dyn.append(('DYN-2','D-02','标签的数据表、筛选与排序','①每个标签可读的表：'+'；'.join('`%s`：%s'%(a,b) for a,b in tagrows)+'；②排序列（`by` 属性）只能取 D.2 的 CS-SORT-<表>：'+'；'.join('%s：%s'%(p,cl(p,sortcols(p))) for p in SORTS)+'；`by="rnd"` 为随机排序，不指定列；其他取值回落为该表的默认列（`<前缀>_time`；`type` 与 `link` 为 `<前缀>_id`）；③筛选条件只能作用于该标签自己表的附表 A 中的列','源码：`common/model/{Vod,Art,Topic,Actor,Role,Website,Comment,Gbook,Type,Link}.php` 的 `in_array($by, [...])` 白名单。白名单中还有 `in`（Actor、Website），对应 `actor_in`、`website_in`，这两列不在附表 A，不属于允许集合，Java 侧不得为此新增列','标签读取上列以外的表、用 JOIN/子查询读到其他表、排序列不在 CS-SORT 中、或写任何表 = 越界'))
dyn.append(('DYN-3','D-05','Ajax 的 data / suggest / pwd / hits / digg / score / referer','①`data`：D-05 读取栏的 6 张内容表，返回列 = 附表 A 全列减去该表的 CS-DATA-EX，`vod` 在非会员时再减 CS-VOD-HIDE；②`suggest`：只返回 CS-SUGGEST-<表> 的 4 列，筛选列限该表的 `<前缀>_name`、`<前缀>_en`；③`pwd`：只读 CS-PWD-VOD、CS-PWD-ART；④写：`hits`→CS-HITS，`digg`→CS-DIGG，`score`→CS-SCORE，`referer`→D-05 写入栏 5 列；`mid`→表：1→vod、2→art、3→topic、4→comment（仅 digg）、8→actor、9→role、11→website','`Ajax.php` 的 `$field` 与 `unset` 行（见 D.2 来源）；mid 取值以 D-05 写入栏标注的 mid 为准','返回了 CS-DATA-EX / CS-VOD-HIDE 中的列、suggest 返回 4 列以外的列、写入栏以外的列、或 `mid` 不在上述映射内仍写库 = 越界'))
dyn.append(('DYN-4','D-11、D-12、D-13','后台内容/分类/会员的 field、batch、move、del','写入限定在各行写入栏的表：`D-11`→六张内容表（整行）+`mac_vod_search`；`D-12`→`mac_type` 整行，`move` 只写 `mac_vod`、`mac_art` 的 `type_id`、`type_id_1`；`D-13`→13 张表整行及行内指定列；`batch`/`field` 只改本行写入栏表的列','D-11～D-13 各行的写入栏','写入栏以外的表、DDL（含 `mac_tmpvod`、`mac_tmpart`、`mac_tmpwebsite` 临时表）= 越界'))
dyn.append(('DYN-5','D-14','采集类型决定入库目标表','`vod`→`mac_vod`+`mac_vod_search`；`art`→`mac_art`；`actor`→`mac_actor`；`role`→`mac_role`；`website`→`mac_website`；`comment`→`mac_comment`；以上写“整行”；另写 `mac_collect` 整行；远程图片落盘写 `mac_annex`（仅 OP-5 放开后）','`admin/controller/Collect.php` 的 vod/art/actor/role/website 方法与 `common/model/Collect.php` 的 `vod_data`、`art_data`、`actor_data`、`role_data`、`website_data`、`comment_data`','写以上 8 张表以外的表、`mac_topic` 入库、DDL = 越界'))
dyn.append(('DYN-6','D-15','自定义规则采集发布的目标表','节点 `mid`=1 → 只写 `mac_vod`（整行）+`mac_vod_search`；节点 `mid`=2 → 只写 `mac_art`（整行）；规则库 `mac_cj_node`、`mac_cj_content`（`nodeid`、`status`、`url`、`title`、`data`）、`mac_cj_history`（`md5`）按 D-15；`SHOW COLUMNS FROM` 只对 `mac_vod`/`mac_art` 执行','`Cj.php` 第 121-124 行（`mid`=2 取 art，否则 vod）与 `content_into`（第 424-428 行经 `Collect` 模型入库）','发布写入 `mac_actor`、`mac_role`、`mac_website`、`mac_topic`，或对其他表执行 `SHOW COLUMNS`，或 DDL = 越界'))
dyn.append(('DYN-7','D-18','数据库工具的表与列','`SHOW TABLE STATUS`/`SHOW COLUMNS`/`SHOW CREATE TABLE`/`SELECT *`：表 ∈ 附表 A 的 26 张；`rep`：表 T ∈ 附表 A 的 26 张，列 F ∈ 附表 A 中 T 的任一列，语句形态唯一为 `UPDATE T SET F=REPLACE(F,?,?) WHERE 1=1`；`import`、`sql`、`optimize`、`repair`：本期允许集合为空，见 OP-6（OP-6 决定前一律不执行）','`admin/controller/Database.php`：`isValidTable`（第 325-333 行，取自 `SHOW TABLE STATUS`）、`rep`（第 287-316 行，列取自 `SHOW COLUMNS`）','T 不在 26 张表内、F 不在 T 的附表 A 列内、`rep` 带其他 WHERE/SET、执行任何 DDL 或 `OPTIMIZE`/`REPAIR` = 越界'))
dyn.append(('DYN-8','D-21、D-09、D-01、D-07a、D-07e','配置文件读写','只能读写 CS-EXTRA 的 12 个文件；`maccms.php` 内共 22 个顶层节：`db`、`site`、`app`、`user`、`gbook`、`comment`、`upload`、`interface`、`pay`、`collect`、`api`、`connect`、`weixin`、`view`、`path`、`rewrite`、`email`、`play`、`sms`、`extra`、`seo`、`urlsend`；这些节都在同一个文件里，不是独立文件','`ls php/application/extra`；节名见 `extra/maccms.php`','新增 CS-EXTRA 以外的配置文件，或把配置写进数据库表 = 越界（配置不入库，BR-2）'))
dyn.append(('DYN-9','D-22、D-23','插件与安装向导','D-22：不执行插件自带 SQL，允许集合为空，见 OP-8；D-23：只写 `mac_admin`（首个管理员，整行）、`mac_group` 3 行、`mac_type` 18 行（均为 INSERT），建库建表允许集合为空，见 OP-7','D-22、D-23 行及 D.1','插件或安装向导里出现任何 DDL，或写以上三表以外的表 = 越界'))
for r in dyn: L.append('| '+' | '.join(f(x) for x in r)+' |')

# ---------- D.4 ----------
L.append('\n## D.4 附表 C 登记范围 → 功能行（覆盖核对）\n')
L.append('| 端 | 控制器文件 | C 行数 | 所属 D 编号 |\n| --- | --- | --- | --- |')
for (side,fl),c in byfile.items():
    L.append('| %s | `%s.php` | %d | %s |'%(side,fl,sum(c.values()),'、'.join('%s（%d 行）'%(d,n) for d,n in sorted(c.items())) if len(c)>1 else list(c)[0]))
L.append('\n| D 编号 | 覆盖的 C 行数 |\n| --- | --- |')
for d in sorted(cnt): L.append('| %s | %d |'%(d,cnt[d]))
L.append('| 合计 | %d |'%sum(cnt.values()))
L.append('\n核对结论：C 的 379 行（376 个待判定行 + 3 个“旧版无功能”预填行）全部落在除 D-02 以外的 26 个功能行（D-01、D-03～D-06、D-07a～D-07e、D-08～D-23）中，没有未归属的行；D-02 没有独立 C 行（模板标签，由 FR-3 验收）。上表由脚本从附表 C 机械生成，附表 C 改动后必须重新核对。')
open(OUT,'w',encoding='utf-8',newline='\n').write('\n'.join(L)+'\n')
print(len(rows),dict(cnt))
