import json
tabs=json.load(open('.work/tabs.json',encoding='utf-8'))
cols={t[4:]:[c for c,_ in v['cols']] for t,v in tabs.items()}
OUT='docs/prd/APPK-2/D-feature-table-field.md'

def chk(t,cs):
    for c in cs: assert c in cols[t],(t,c)
    return cs
def P(t): return 'ALL:'+t          # 整行
def C(t,*cs): chk(t,cs); return (t,list(cs))
def fmt(x):
    if isinstance(x,str) and x.startswith('ALL:'):
        t=x[4:]; return '`mac_%s` 整行（%d 列，附表 A 的 T 表）'%(t,len(cols[t]))
    t,cs=x; return '`mac_%s`：%s'%(t,'、'.join('`%s`'%c for c in cs))
def tb(*ts): return '、'.join('`mac_%s`'%t for t in ts)
def hits(p): return C(p,*[p+'_'+s for s in ['hits','hits_day','hits_week','hits_month','time_hits']])
def digg(p): return C(p,p+'_up',p+'_down')
def score(p): return C(p,p+'_score',p+'_score_num',p+'_score_all')
CT=['vod','art','topic','actor','role','website']
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


rows=[]
def R(i,fr,feat,entry,reads,writes,files,note=''):
    rows.append((i,fr,feat,entry,reads,writes,files,note))

R('D-01','FR-5','首页、地图、RSS、帮助、公告、搜索聚合、自定义标签页、验证码页、二维码、404 页','前台 `Index`/`Map`/`Rss`/`Help`/`Note`/`Search`/`Label`/`Verify`/`Qrcode`/`MyError`/`Base._empty` 的全部方法（附表 C 中对应的 18 行）','只读，且限于 **TS-TAG**（D.2）列出的 11 张表：'+tb(*TAG_TABLES)+'；读取方式 = 页面模板里的 `{maccms:xxx}` 标签（读取范围与排序列见 D-02 和 D.3 DYN-1/DYN-2），或本行页面自带的数据查询（`Map`/`Rss`/`Search` 读 `mac_vod`、`mac_art`，均在 TS-TAG 内）。超出 TS-TAG 的表（例如 `mac_user` 的密码列、`mac_admin`）不得读取，判定见 DYN-1','无','`template/<dir>/<html>/` 目录（含其下 `label/` 目录里的 `.html` 文件）、`application/extra/maccms.php`（`site` 节的 `site_` 开头配置键、`app` 节的 `page_404`）、`extra/captcha.php`、`extra/maccms.php` 的 `rewrite` 节、`application/route.php`；验证码与会话在库外')
R('D-02','FR-3','Maccms 标签库 19 个标签','模板中 `{maccms:xxx}`',
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
R('D-03','FR-5','视频前台页（分类、筛选、搜索、详情、播放、下载、观看密码、版权、角色、剧情、RSS、ajax 片段）','前台 `Vod` 全部方法',
  tb('vod','type','group','user','ulog','role','vod_search')+'；`group_popedom` 判定权限，`ulog` 判定是否已购买；`vod_pwd/vod_pwd_url/vod_pwd_play/vod_pwd_play_url/vod_pwd_down/vod_pwd_down_url` 观看密码；`vod_points/vod_points_play/vod_points_down` 积分','本类方法不直接写库；计数写入见 D-05，扣积分见 D-07','`extra/vodplayer.php`、`vodserver.php`、`voddowner.php`、`static/js/playerconfig.js`')
R('D-04','FR-5','文章、演员、角色、专题、网址、剧情前台页','前台 `Art`/`Actor`/`Role`/`Topic`/`Website`/`Plot` 全部方法',
  tb('art','actor','role','topic','website','type','group','user','ulog','vod')+'（专题/角色/剧情页按关联读 `mac_vod`、`mac_art`）','不直接写库；计数写入见 D-05','`extra/maccms.php`')
R('D-05','FR-5','前台计数与互动 ajax（`Ajax`）','`Ajax.php` 的 data/suggest/hits/referer/digg/score/pwd/verify_check',
  '`data`：'+tb('vod','art','topic','actor','role','website')+' 的整行，**但**不返回 D.2 CS-DATA-EX 列出的列，且非会员再不返回 CS-VOD-HIDE 的 8 列；筛选限 `vod_status`、`art_status`、`topic_status`、`actor_status`、`role_status`、`website_status` 各自等于 1；`suggest`：只读 D.2 CS-SUGGEST 的列；`hits`/`score`/`digg`：读被计数行的主键与被更新列（D.2 CS-HITS/CS-DIGG/CS-SCORE 即下列写入列）；`referer`：`mac_website`：`website_jumpurl`；`pwd`：只读 D.2 CS-PWD 的列',
  '；'.join([ '`hits`（mid 1,2,3,8,9,11）：'+'；'.join(fmt(hits(p)) for p in CT),
              '`digg`（mid 1,2,3,4,8,9,11）：'+'；'.join(fmt(digg(p)) for p in CT+['comment']),
              '`score`（mid 1,2,3,8,9,11）：'+'；'.join(fmt(score(p)) for p in CT),
              '`referer`：'+fmt(C('website','website_referer','website_referer_day','website_referer_week','website_referer_month','website_time_referer'))]),
  '验证码校验、已投票/已评分 cookie 在库外')
R('D-06','FR-5','评论、留言（前台）','前台 `Comment`、`Gbook`',
  tb('comment','gbook','user','vod','art','topic','actor','role','website')+'（评论目标存在性）','`comment`：'+fmt(P('comment'))+'（新增）、'+fmt(C('comment','comment_report'))+'（举报 +1）、'+fmt(digg('comment'))+'；`gbook`：'+fmt(P('gbook'))+'（新增）','`extra/maccms.php`（评论/留言开关、审核、间隔）、验证码')
R('D-07a','FR-5','会员注册、登录、退出、找回密码、绑定','前台 `User` 的 index/login/logout/reg/regcheck/reg_msg/oauth/logincallback/bindmsg/bind/unbind/findpass/findpass_msg/findpass_reset/ajax_login/ajax_info（附表 C 中 index/User.php 的这 16 行）',
  tb('user','group','msg'),
  '`mac_user`：新增注册时整行（'+str(len(cols['user']))+' 列）；登录更新 '+'、'.join('`%s`'%c for c in chk('user',['user_login_num','user_login_ip','user_login_time','user_last_login_ip','user_last_login_time','user_random']))+'；找回/改密 `user_pwd`；绑定 `user_openid_qq`、`user_openid_weixin`；'+fmt(P('msg'))+'（短信/邮件验证码记录）；邀请注册加分 `user_points`（见 D-07d）','`extra/maccms.php`（user 节）、`extra/captcha.php`、`extra/maccms.php` 的 `connect` 节（第三方登录）；登录态在库外')
R('D-07b','FR-5','会员资料、头像、密保','前台 `User` 的 info/portrait（附表 C 中 index/User.php 的这 2 行）',
  tb('user'),'`mac_user`：'+'、'.join('`%s`'%c for c in chk('user',['user_nick_name','user_email','user_phone','user_portrait','user_question','user_answer','user_pwd']))+'；头像文件另写 `mac_annex`（见 D-17）','`upload/` 目录文件')
R('D-07c','FR-5','购买播放/下载权限、会员升级、积分','前台 `User` 的 ajax_buy_popedom/buy/upgrade/popedom（附表 C 中 index/User.php 的这 4 行）',
  tb('user','group','ulog','plog','vod','art'),
  '`mac_user`：'+'、'.join('`%s`'%c for c in chk('user',['user_points','user_end_time','group_id']))+'；'+fmt(P('ulog'))+'（购买记录）；'+fmt(P('plog'))+'（积分流水）','—')
R('D-07d','FR-5','收藏、播放记录、下载记录、积分记录、分销','前台 `User` 的 favs/plays/downs/ulog/ajax_ulog/ulog_del/plog/plog_del/reward/visit（附表 C 中 index/User.php 的这 10 行）',
  tb('ulog','plog','user','vod','art','actor','topic','role','website'),
  fmt(P('ulog'))+'；'+fmt(P('plog'))+'（读写）；`mac_user`：'+'、'.join('`%s`'%c for c in chk('user',['user_points','user_pid','user_pid_2','user_pid_3']))+'；'+fmt(P('visit'))+'（邀请访问记录，邀请访问加分 `user_points`）','`extra/maccms.php`（分销比例、积分规则）')
R('D-07e','FR-5','在线支付、订单、充值卡、提现、我的评论/留言','前台 `User` 的 pay/gopay/qrcode/orders/order_info/cards/cash/cash_del/comment/gbook（index/User.php 的这 10 行）；`Payment.notify`（1 行）',
  tb('order','card','cash','user','plog','comment','gbook'),
  fmt(P('order'))+'（下单）；`mac_order`：'+'、'.join('`%s`'%c for c in chk('order',['order_status','order_pay_time','order_pay_type']))+'（支付回调）；`mac_card`：'+'、'.join('`%s`'%c for c in chk('card',['card_points','card_sale_status','card_use_status','card_use_time','user_id']) if c in cols['card'])+'；'+fmt(P('cash'))+'（申请提现）；`mac_user`：`user_points`、`user_points_froze`；'+fmt(P('plog'))+'（积分流水）','`extra/maccms.php` 的 `pay` 节（支付通道配置）')
R('D-08','FR-12','开放接口（`api` 控制器）','`Provide`（vod/art/actor/role/website/comment 列表与详情，采集协议 XML/JSON）、`Receive`（外部入库）',
  'Provide：'+tb('vod','art','actor','role','website','comment','type')+'（只读）','Receive：'+'；'.join(fmt(P(t)) for t in ['vod','art','actor','role','website','comment'])+'；`mac_vod_search`（搜索优化开启时）；其余控制器不直接写库','`extra/maccms.php`（api 节、接口密码）、`extra/bind.php`')
R('D-09','FR-12','定时任务入口、公众号、接口首页','API `Timming`、`Wechat`、`Index`','无（定时任务调用的生成/采集功能所读写的表见 D-14、D-19）','无','`extra/timming.php`、`extra/maccms.php`（weixin 节）')
R('D-10','FR-12','后台登录、首页、欢迎页、缓存清理、快捷菜单','后台 `Index`、`Base`',
  tb('admin','type','vod_search'),'`mac_admin`：'+'、'.join('`%s`'%c for c in chk('admin',['admin_login_num','admin_login_ip','admin_login_time','admin_last_login_ip','admin_last_login_time','admin_random']))+'；缓存清理时删除 `mac_vod_search` 的过期行（DML `DELETE`）','`runtime/` 缓存目录、`extra/quickmenu.php`')
R('D-11','FR-12','内容管理：视频、文章、演员、角色、专题、网址','后台 `Vod`/`Art`/`Actor`/`Role`/`Topic`/`Website` 的 data/info/del/batch/field/updateToday（`Vod` 另有 `iplot`）',
  '各自内容表（`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website` 整行）+ '+tb('type','group','vod'),'各自内容表整行：'+'；'.join(fmt(P(t)) for t in CT)+'；`mac_vod_search`（同步）；重复数据查询：**旧版用 `CREATE TABLE mac_tmpvod/tmpart/tmpwebsite`（DDL）**，Java 已改子查询（`docs/architecture/22-b13-admin-content.md` D-B13-7），本期不得回到 DDL（BR-1）','`upload/` 图片文件')
R('D-12','FR-12','分类管理','后台 `Type` 的 index/info/del/field/batch/extend/move',
  tb('type','vod','art','actor','website'),fmt(P('type'))+'；`move`：`mac_vod`、`mac_art` 的 `type_id`、`type_id_1`（'+fmt(C('vod','type_id','type_id_1'))+'；'+fmt(C('art','type_id','type_id_1'))+'）','`extra/maccms.php` 的筛选项配置')
R('D-13','FR-12','会员与交易类后台','后台 `User`/`Group`/`Card`/`Cash`/`Order`/`Plog`/`Ulog`/`Comment`/`Gbook`/`Link`/`Visit`/`Admin`/`Annex`',
  tb('user','group','card','cash','order','plog','ulog','comment','gbook','link','visit','admin','annex'),
  '各自表整行：'+'；'.join(fmt(P(t)) for t in ['user','group','card','cash','order','plog','ulog','comment','gbook','link','visit','admin','annex'])+'；`Cash.audit`：'+fmt(C('cash','cash_status','cash_time_audit'))+'、'+fmt(C('user','user_points_froze'))+'；`User.reward`：'+fmt(C('user','user_points'))+' + `mac_plog` 新增；`System` 保存会员组配置时读取 `mac_group`','`extra/maccms.php`')
R('D-14','FR-11/FR-12','资源站采集（本地版本与在线版本）','后台 `Collect`（含 `union/load/api/bind/clearbind/vod/art/actor/role/website` 与 `timing`），API `Receive`',
  tb('collect','type','vod','art','actor','role','website','comment'),'`mac_collect` 整行（%d 列）；入库写 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` 整行（各表列数见 D-08）；`mac_vod_search`（搜索优化开启时）；远程图片落盘时 `mac_annex` 整行（仅 OP-5 放开后）；入库目标表只能是上述 6 张内容/评论表，采集类型与表的对应见 D.3 DYN-5'%len(cols['collect']),'`extra/bind.php`、`extra/timming.php`；本地版本只读本地文件源')
R('D-15','FR-11/FR-12','自定义规则采集','后台 `Cj`（14 行）',
  tb('cj_node','cj_content','cj_history','collect')+'（均整行）；`content_into`（发布）查重与分类映射读 `mac_vod`（节点 `mid`=1）或 `mac_art`（`mid`=2）、`mac_type`；`program` 页执行 `SHOW COLUMNS FROM mac_vod`（`mid`=1）或 `mac_art`（`mid`=2）取字段名清单（只读结构信息，不改结构）',
  '`mac_cj_node` 整行（%d 列，新增/修改/导入）；'%len(cols['cj_node'])+fmt(C('cj_node','lastdate'))+'（采集后更新）；'+fmt(C('cj_content','nodeid','status','url','title','data'))+'；'+fmt(C('cj_history','md5'))+'；**发布（`content_into`）只写**：节点 `mid`=1 时 `mac_vod` 整行（%d 列）+ `mac_vod_search`（搜索优化开启时），`mid`=2 时 `mac_art` 整行（%d 列）；不写 actor/role/website/topic。判定见 D.3 DYN-6'%(len(cols['vod']),len(cols['art'])),'规则导入导出文件')
R('D-16','FR-12','图片同步（远程图片下载）','后台 `Images` 的 data/opt/del/sync',
  tb('vod','art','topic','actor','role','website'),'各内容表的图片/正文列：'+'；'.join(fmt(C(p,*[c for c in [p+'_pic',p+'_content'] if c in cols[p]])) for p in CT)+'；`mac_annex` 整行','`upload/`（仅 OP-5 放开后可下载远程图）')
R('D-17','FR-12','上传与附件','后台 `Upload`；前台头像上传',
  tb('annex','user'),fmt(P('annex'))+'；'+fmt(C('user','user_portrait')),'`upload/`、`extra/maccms.php`（upload 节）')
R('D-18','FR-9','数据库工具','后台 `Database`（9 行）',
  '`SHOW TABLE STATUS`（库内 26 张表，即附表 A）、`SHOW COLUMNS FROM <表>`（<表> ∈ 附表 A 的 26 张表）、`SHOW CREATE TABLE <表>`（同）、导出备份时对 26 张表执行 `SELECT *`',
  '`rep`（批量替换）：对表 T 的列 F 执行且只执行一条 `UPDATE T SET F=REPLACE(F,:findstr,:tostr) WHERE 1=1`；允许集合 = T ∈ 附表 A 的 26 张表、F ∈ 附表 A 中 T 的任一列（旧版校验 F ∈ `SHOW COLUMNS FROM T`，**不限列类型**；v3 写的“文本列”不准确，v4 更正）；还原备份（`import`）、执行 SQL（`sql`）、优化（`optimize`）、修复（`repair`）：**会改结构或做表维护，见 OP-6**；判定见 D.3 DYN-7','`application/data/backup/` 目录下的备份文件（写入与删除）')
R('D-19','FR-6','静态生成','后台 `Make`；定时任务 `make`',
  '各内容表 + `mac_type`','各内容表的 `<前缀>_time_make` 列：'+'；'.join(fmt(C(p,p+'_time_make')) for p in CT),'静态页输出目录、`extra/timming.php`')
R('D-20','FR-7','模板管理、广告','后台 `Template`','无','无','`template/`、`ads/` 目录文件')
R('D-21','FR-12','系统配置、域名、播放器/下载器/服务器配置、定时任务、站长推送、安全','后台 `System`/`Domain`/`Vodplayer`/`Voddowner`/`Vodserver`/`Timming`/`Urlsend`/`Safety`/`Update`',
  '`System`：读 `mac_group`（会员组下拉）；`Urlsend`：读 '+tb('vod','art','actor','role','topic','website'),'无','`application/extra/` 目录下现有的 12 个文件：`addons.php`、`bind.php`、`captcha.php`、`domain.php`、`maccms.php`、`queue.php`、`quickmenu.php`、`timming.php`、`version.php`、`voddowner.php`、`vodplayer.php`、`vodserver.php`（`var_export` 格式，BR-2；清单见 D.3 DYN-8）')
R('D-22','FR-12','插件','后台 `Addon`；`addons/` 目录下各插件目录',
  '无固定表','插件自带 SQL 可建表（旧 `think\\addons\\Service`），**按 BR-1 不执行，见 OP-8**','`addons/<插件名>/info.ini`、`addons/<插件名>/config.php`、`extra/addons.php`')
R('D-23','FR-10','安装向导','`install` 控制器',
  '校验 26 张表存在','初始数据：`mac_admin`（首个管理员，整行）；`install.sql` 内 `INSERT`：`mac_group` 3 行、`initdata.sql`：`mac_type` 18 行；建库建表 DDL **不执行，见 OP-7**','`application/database.php`、`extra/maccms.php`、`data/install/install.lock`')

