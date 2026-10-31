import re
s=open('.work/gen_d4.py',encoding='utf-8').read()
def rep(a,b,cnt=1):
    global s
    assert s.count(a)==cnt,(a[:50],s.count(a))
    s=s.replace(a,b)
rep("OUT='docs/prd/APPK-2-annex/D-feature-table-field.md'","OUT='docs/prd/APPK-2/D-feature-table-field.md'")

# helpers
rep("CT=['vod','art','topic','actor','role','website']","""CT=['vod','art','topic','actor','role','website']
def cl(t,cs=None):
    cs=cols[t] if cs is None else cs
    return '、'.join('`%s`'%c for c in chk(t,list(cs)))
TAG_TABLES=['vod','art','topic','actor','role','website','comment','gbook','type','link','vod_search']
SORT_COMMON=['id','time','time_add','score','hits','hits_day','hits_week','hits_month','up','down','level']
SORTS={'vod':SORT_COMMON,'art':SORT_COMMON,'topic':SORT_COMMON,'role':SORT_COMMON,'actor':SORT_COMMON,
       'website':SORT_COMMON+['referer','referer_day','referer_week','referer_month'],
       'comment':['id','time','up','down'],'gbook':['id','time','reply_time'],'type':['id','sort'],'link':['id','sort']}
def sortcols(p): return [p+'_'+x for x in SORTS[p]]
for _p in SORTS: chk(_p,sortcols(_p))
SUG={p:[p+'_id',p+'_name',p+'_en',p+'_pic'] for p in CT}
for _p in SUG: chk(_p,SUG[_p])
DATA_EX={p:[p+'_time_hits',p+'_time_make'] for p in CT}
for _p in DATA_EX: chk(_p,DATA_EX[_p])
VOD_HIDE=['vod_play_from','vod_play_server','vod_play_note','vod_play_url','vod_down_from','vod_down_server','vod_down_note','vod_down_url']
chk('vod',VOD_HIDE)
PWD={'vod':['vod_pwd','vod_pwd_url','vod_pwd_play','vod_pwd_play_url','vod_pwd_down','vod_pwd_down_url'],'art':['art_pwd','art_pwd_url']}
for _p in PWD: chk(_p,PWD[_p])
""")

# D-01
i=s.index("R('D-01'"); j=s.index("R('D-02'")
s=s[:i]+"""R('D-01','FR-5','首页、地图、RSS、帮助、公告、搜索聚合、自定义标签页、验证码页、二维码、404 页','前台 `Index`/`Map`/`Rss`/`Help`/`Note`/`Search`/`Label`/`Verify`/`Qrcode`/`MyError`/`Base._empty` 的全部方法（附表 C 中对应的 21 行）','只读，且限于 **TS-TAG**（D.2）列出的 11 张表：'+tb(*TAG_TABLES)+'；读取方式 = 页面模板里的 `{maccms:xxx}` 标签（读取范围与排序列见 D-02 和 D.3 DYN-1/DYN-2），或本行页面自带的数据查询（`Map`/`Rss`/`Search` 读 `mac_vod`、`mac_art` 等 TS-TAG 内的表）。超出 TS-TAG 的表（例如 `mac_user` 的密码列、`mac_admin`）不得读取，判定见 DYN-1','无','`template/<dir>/<html>/`（含 `label/*.html`）、`application/extra/maccms.php`（`site_*`、`app.page_404`）、`extra/captcha.php`、`extra/rewrite`/`route.php`；验证码与会话在库外')
"""+s[j:]

# D-02
i=s.index("R('D-02'"); j=s.index("R('D-03'")
s=s[:i]+"""R('D-02','FR-3','Maccms 标签库 19 个标签','模板中 `{maccms:xxx}`',
  '；'.join([
   '`vod`：`mac_vod` 整行（%d 列）+ `mac_type` 整行（%d 列，分类筛选与继承）+ `mac_vod_search`（搜索优化开启时）整行（%d 列）'%(len(cols['vod']),len(cols['type']),len(cols['vod_search'])),
   '`art`：`mac_art` 整行（%d 列）、`mac_type` 整行'%len(cols['art']),
   '`topic`：`mac_topic` 整行（%d 列）；`topic_rel_vod`/`topic_rel_art` 指向的 `mac_vod`/`mac_art` 整行'%len(cols['topic']),
   '`actor`：`mac_actor` 整行（%d 列）'%len(cols['actor']),
   '`role`：`mac_role` 整行（%d 列）；`role_rid` 关联的 `mac_vod` 整行'%len(cols['role']),
   '`website`：`mac_website` 整行（%d 列）'%len(cols['website']),
   '`comment`：`mac_comment` 整行（%d 列）；用户头像经 `mac_get_user_portrait` 只读 `mac_user`：`user_id`、`user_portrait`'%len(cols['comment']),
   '`gbook`：`mac_gbook` 整行（%d 列）；头像同 `comment`，只读 `mac_user`：`user_id`、`user_portrait`'%len(cols['gbook']),
   '`type`：`mac_type` 整行','`link`：`mac_link` 整行（%d 列）'%len(cols['link']),
   '`area`/`lang`/`class`/`year`/`version`/`state`/`letter`：不读表，取 `extra/maccms.php` 对应配置项（`mac_type.type_extend` 的筛选项由 `type` 标签读）','`foreach`/`for`：不读表',
   '每个标签的**排序列**限定为 D.3 DYN-2 的白名单；**筛选条件**只作用于该标签自己的表的附表 A 中的列，不得引入其他表']),
  '无（`cachetime` 只写库外缓存文件）','`extra/maccms.php`、`runtime/cache`（库外）')
"""+s[j:]

# D-05 reads
rep("'`data`：'+tb('vod','art','topic','actor','role','website')+'（`*_status=1`，排除 `*_time_hits`、`*_time_make`；非会员隐藏 `vod_down_*`）；`suggest`：`vod/art/topic/actor/role/website` 的 `*_id/*_name/*_en/*_pic`；`hits`/`score`/`digg`：读被计数行；`referer`：`mac_website.website_jumpurl`；`pwd`：`vod_pwd*`、`art_pwd*`'",
 "'`data`：'+tb('vod','art','topic','actor','role','website')+' 的整行，**但**不返回 D.2 CS-DATA-EX 列出的列，且非会员再不返回 CS-VOD-HIDE 的 8 列；筛选限 `vod_status`、`art_status`、`topic_status`、`actor_status`、`role_status`、`website_status` 各自等于 1；`suggest`：只读 D.2 CS-SUGGEST 的列；`hits`/`score`/`digg`：读被计数行的主键与被更新列（D.2 CS-HITS/CS-DIGG/CS-SCORE 即下列写入列）；`referer`：`mac_website`：`website_jumpurl`；`pwd`：只读 D.2 CS-PWD 的列'")

# D-07 method lists
rep("'前台 `User` 的 reg/login/logout/findpass*/bind/unbind/logincallback/oauth/ajax_login/ajax_info'","'前台 `User` 的 index/login/logout/reg/regcheck/reg_msg/oauth/logincallback/bindmsg/bind/unbind/findpass/findpass_msg/findpass_reset/ajax_login/ajax_info（附表 C 中 index/User.php 的这 16 行）'")
rep("'前台 `User` 的 info/portrait/regcheck/reg_msg/findpass_reset'","'前台 `User` 的 info/portrait（附表 C 中 index/User.php 的这 2 行）'")
rep("'前台 `User` 的 ajax_buy_popedom/buy/upgrade/popedom'","'前台 `User` 的 ajax_buy_popedom/buy/upgrade/popedom（附表 C 中 index/User.php 的这 4 行）'")
rep("'前台 `User` 的 favs/plays/downs/ulog/ajax_ulog/plog/reward'","'前台 `User` 的 favs/plays/downs/ulog/ajax_ulog/ulog_del/plog/plog_del/reward/visit（附表 C 中 index/User.php 的这 10 行）'")
rep("'前台 `User` 的 pay/orders/order_info/cards/cash/comment/gbook；`Payment.notify`'","'前台 `User` 的 pay/gopay/qrcode/orders/order_info/cards/cash/cash_del/comment/gbook（index/User.php 的这 10 行）；`Payment.notify`（1 行）'")

# D-11 reads
rep("'各自内容表 + '+tb('type','group','vod')","'各自内容表（`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website` 整行）+ '+tb('type','group','vod')")

# D-14
rep("'`mac_collect` 整行；入库写 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` 整行；`mac_vod_search`；远程图片落盘时 `mac_annex` 整行（仅 OP-5 放开后）'",
    "'`mac_collect` 整行（%d 列）；入库写 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` 整行（各表列数见 D-08）；`mac_vod_search`（搜索优化开启时）；远程图片落盘时 `mac_annex` 整行（仅 OP-5 放开后）；入库目标表由采集类型决定，只能是上述 6 张内容/评论表，判定见 D.3 DYN-5'%len(cols['collect'])")

# D-15
i=s.index("R('D-15'"); j=s.index("R('D-16'")
s=s[:i]+"""R('D-15','FR-11/FR-12','自定义规则采集','后台 `Cj`（14 行）',
  tb('cj_node','cj_content','cj_history','collect')+'（均整行）；`content_into`（发布）查重与分类映射读 `mac_vod`（节点 `mid`=1）或 `mac_art`（`mid`=2）、`mac_type`；`program` 页执行 `SHOW COLUMNS FROM mac_vod`（`mid`=1）或 `mac_art`（`mid`=2）取字段名清单（只读结构信息，不改结构）',
  '`mac_cj_node` 整行（%d 列，新增/修改/导入）；'%len(cols['cj_node'])+fmt(C('cj_node','lastdate'))+'（采集后更新）；'+fmt(C('cj_content','nodeid','status','url','title','data'))+'；'+fmt(C('cj_history','md5'))+'；**发布（`content_into`）只写**：节点 `mid`=1 时 `mac_vod` 整行（%d 列）+ `mac_vod_search`（搜索优化开启时），`mid`=2 时 `mac_art` 整行（%d 列）；不写 actor/role/website/topic。判定见 D.3 DYN-6'%(len(cols['vod']),len(cols['art'])),'规则导入导出文件')
"""+s[j:]

# D-18
i=s.index("R('D-18'"); j=s.index("R('D-19'")
s=s[:i]+"""R('D-18','FR-9','数据库工具','后台 `Database`（9 行）',
  '`SHOW TABLE STATUS`（库内 26 张表，即附表 A）、`SHOW COLUMNS FROM <表>`（<表> ∈ 附表 A 的 26 张表）、`SHOW CREATE TABLE <表>`（同）、导出备份时对 26 张表执行 `SELECT *`',
  '`rep`（批量替换）：对表 T 的列 F 执行且只执行一条 `UPDATE T SET F=REPLACE(F,:findstr,:tostr) WHERE 1=1`；允许集合 = T ∈ 附表 A 的 26 张表、F ∈ 附表 A 中 T 的任一列（旧版校验 F ∈ `SHOW COLUMNS FROM T`，**不限列类型**；v3 写的“文本列”不准确，v4 更正）；还原备份（`import`）、执行 SQL（`sql`）、优化（`optimize`）、修复（`repair`）：**会改结构或做表维护，见 OP-6**；判定见 D.3 DYN-7','`application/data/backup/*`（备份文件的写入与删除）')
"""+s[j:]

# D-19 label
rep("'`*_time_make`：'+","'各内容表的 `<前缀>_time_make` 列：'+")

rep("（v2）。冲突时以主文件为准。","（v4）。冲突时以主文件为准。")
open('.work/gen_d4.py','w',encoding='utf-8').write(s)
