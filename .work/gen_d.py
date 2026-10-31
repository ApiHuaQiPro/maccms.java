import json
tabs=json.load(open('.work/tabs.json',encoding='utf-8'))
cols={t[4:]:[c for c,_ in v['cols']] for t,v in tabs.items()}
OUT='docs/prd/APPK-2-annex/D-feature-table-field.md'

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

rows=[]
def R(i,fr,feat,entry,reads,writes,files,note=''):
    rows.append((i,fr,feat,entry,reads,writes,files,note))

R('D-01','FR-5','首页、地图、RSS、帮助、公告、搜索聚合、自定义标签页、验证码页、二维码','前台 `Index`/`Map`/`Rss`/`Help`/`Note`/`Search`/`Label`/`Verify`/`Qrcode`/`MyError` 的 index 等方法','由模板里的标签决定，见 D-02','无','`template/<dir>/<html>/`（含 `label/*.html`）、`application/extra/maccms.php`（site_*、app.page_404）、`extra/captcha.php`、`extra/rewrite`/`route.php`；验证码与会话在库外')
R('D-02','FR-3','Maccms 标签库 19 个标签','模板中 `{maccms:xxx}`',
  '；'.join([
   '`vod`：`mac_vod`（整行，可按 A 中 vod 任意列筛选/排序）+ `mac_type`（`type_id/type_pid/type_mid/type_name/type_en/type_sort/type_status/type_extend/type_tpl*`，用于分类筛选与继承）+ `mac_vod_search`（搜索优化开启时）',
   '`art`：`mac_art`、`mac_type`','`topic`：`mac_topic`（及 `topic_rel_vod`/`topic_rel_art` 指向的 `mac_vod`/`mac_art`）','`actor`：`mac_actor`','`role`：`mac_role`（`role_rid` 关联 `mac_vod`）','`website`：`mac_website`','`comment`：`mac_comment`（+ `mac_user` 取昵称头像）','`gbook`：`mac_gbook`（+ `mac_user`）','`type`：`mac_type`','`link`：`mac_link`',
   '`area`/`lang`/`class`/`year`/`version`/`state`/`letter`：不读表，取 `extra/maccms.php` 对应配置项（`mac_type.type_extend` 的筛选项由 `type` 标签读）','`foreach`/`for`：不读表']),
  '无（`cachetime` 只写库外缓存文件）','`extra/maccms.php`、`runtime/cache`（库外）')
R('D-03','FR-5','视频前台页（分类、筛选、搜索、详情、播放、下载、观看密码、版权、角色、剧情、RSS、ajax 片段）','前台 `Vod` 全部方法',
  tb('vod','type','group','user','ulog','role','vod_search')+'；`group_popedom` 判定权限，`ulog` 判定是否已购买；`vod_pwd/vod_pwd_url/vod_pwd_play/vod_pwd_play_url/vod_pwd_down/vod_pwd_down_url` 观看密码；`vod_points/vod_points_play/vod_points_down` 积分','本类方法不直接写库；计数写入见 D-05，扣积分见 D-07','`extra/vodplayer.php`、`vodserver.php`、`voddowner.php`、`static/js/playerconfig.js`')
R('D-04','FR-5','文章、演员、角色、专题、网址、剧情前台页','前台 `Art`/`Actor`/`Role`/`Topic`/`Website`/`Plot` 全部方法',
  tb('art','actor','role','topic','website','type','group','user','ulog','vod')+'（专题/角色/剧情页按关联读 `mac_vod`、`mac_art`）','不直接写库；计数写入见 D-05','`extra/maccms.php`')
R('D-05','FR-5','前台计数与互动 ajax（`Ajax`）','`Ajax.php` 的 data/suggest/hits/referer/digg/score/pwd/verify_check',
  '`data`：'+tb('vod','art','topic','actor','role','website')+'（`*_status=1`，排除 `*_time_hits`、`*_time_make`；非会员隐藏 `vod_down_*`）；`suggest`：`vod/art/topic/actor/role/website` 的 `*_id/*_name/*_en/*_pic`；`hits`/`score`/`digg`：读被计数行；`referer`：`mac_website.website_jumpurl`；`pwd`：`vod_pwd*`、`art_pwd*`',
  '；'.join([ '`hits`（mid 1,2,3,8,9,11）：'+'；'.join(fmt(hits(p)) for p in CT),
              '`digg`（mid 1,2,3,4,8,9,11）：'+'；'.join(fmt(digg(p)) for p in CT+['comment']),
              '`score`（mid 1,2,3,8,9,11）：'+'；'.join(fmt(score(p)) for p in CT),
              '`referer`：'+fmt(C('website','website_referer','website_referer_day','website_referer_week','website_referer_month','website_time_referer'))]),
  '验证码校验、已投票/已评分 cookie 在库外')
R('D-06','FR-5','评论、留言（前台）','前台 `Comment`、`Gbook`',
  tb('comment','gbook','user','vod','art','topic','actor','role','website')+'（评论目标存在性）','`comment`：'+fmt(P('comment'))+'（新增）、'+fmt(C('comment','comment_report'))+'（举报 +1）、'+fmt(digg('comment'))+'；`gbook`：'+fmt(P('gbook'))+'（新增）','`extra/maccms.php`（评论/留言开关、审核、间隔）、验证码')
R('D-07a','FR-5','会员注册、登录、退出、找回密码、绑定','前台 `User` 的 reg/login/logout/findpass*/bind/unbind/logincallback/oauth/ajax_login/ajax_info',
  tb('user','group','msg'),
  '`mac_user`：新增注册时整行（'+str(len(cols['user']))+' 列）；登录更新 '+'、'.join('`%s`'%c for c in chk('user',['user_login_num','user_login_ip','user_login_time','user_last_login_ip','user_last_login_time','user_random']))+'；找回/改密 `user_pwd`；绑定 `user_openid_qq`、`user_openid_weixin`；'+fmt(P('msg'))+'（短信/邮件验证码记录）；邀请注册加分 `user_points`（见 D-07d）','`extra/maccms.php`（user 节）、`extra/captcha.php`、第三方登录配置 `extra/connect`；登录态在库外')
R('D-07b','FR-5','会员资料、头像、密保','前台 `User` 的 info/portrait/regcheck/reg_msg/findpass_reset',
  tb('user'),'`mac_user`：'+'、'.join('`%s`'%c for c in chk('user',['user_nick_name','user_email','user_phone','user_portrait','user_question','user_answer','user_pwd']))+'；头像文件另写 `mac_annex`（见 D-17）','`upload/` 目录文件')
R('D-07c','FR-5','购买播放/下载权限、会员升级、积分','前台 `User` 的 ajax_buy_popedom/buy/upgrade/popedom',
  tb('user','group','ulog','plog','vod','art'),
  '`mac_user`：'+'、'.join('`%s`'%c for c in chk('user',['user_points','user_end_time','group_id']))+'；'+fmt(P('ulog'))+'（购买记录）；'+fmt(P('plog'))+'（积分流水）','—')
R('D-07d','FR-5','收藏、播放记录、下载记录、积分记录、分销','前台 `User` 的 favs/plays/downs/ulog/ajax_ulog/plog/reward',
  tb('ulog','plog','user','vod','art','actor','topic','role','website'),
  fmt(P('ulog'))+'；'+fmt(P('plog'))+'（读写）；`mac_user`：'+'、'.join('`%s`'%c for c in chk('user',['user_points','user_pid','user_pid_2','user_pid_3']))+'；'+fmt(P('visit'))+'（邀请访问记录，邀请访问加分 `user_points`）','`extra/maccms.php`（分销比例、积分规则）')
R('D-07e','FR-5','在线支付、订单、充值卡、提现、我的评论/留言','前台 `User` 的 pay/orders/order_info/cards/cash/comment/gbook；`Payment.notify`',
  tb('order','card','cash','user','plog','comment','gbook'),
  fmt(P('order'))+'（下单）；`mac_order`：'+'、'.join('`%s`'%c for c in chk('order',['order_status','order_pay_time','order_pay_type']))+'（支付回调）；`mac_card`：'+'、'.join('`%s`'%c for c in chk('card',['card_points','card_sale_status','card_use_status','card_use_time','user_id']) if c in cols['card'])+'；'+fmt(P('cash'))+'（申请提现）；`mac_user`：`user_points`、`user_points_froze`；'+fmt(P('plog'))+'（积分流水）','`extra/pay`（支付通道配置）')
R('D-08','FR-12','开放接口（`api` 控制器）','`Provide`（vod/art/actor/role/website/comment 列表与详情，采集协议 XML/JSON）、`Receive`（外部入库）',
  'Provide：'+tb('vod','art','actor','role','website','comment','type')+'（只读）','Receive：'+'；'.join(fmt(P(t)) for t in ['vod','art','actor','role','website','comment'])+'；`mac_vod_search`（搜索优化开启时）；其余控制器不直接写库','`extra/maccms.php`（api 节、接口密码）、`extra/bind.php`')
R('D-09','FR-12','定时任务入口、公众号、接口首页','API `Timming`、`Wechat`、`Index`','无（定时任务调用的生成/采集功能所读写的表见 D-14、D-19）','无','`extra/timming.php`、`extra/maccms.php`（weixin 节）')
R('D-10','FR-12','后台登录、首页、欢迎页、缓存清理、快捷菜单','后台 `Index`、`Base`',
  tb('admin','type','vod_search'),'`mac_admin`：'+'、'.join('`%s`'%c for c in chk('admin',['admin_login_num','admin_login_ip','admin_login_time','admin_last_login_ip','admin_last_login_time','admin_random']))+'；缓存清理时删除 `mac_vod_search` 的过期行（DML `DELETE`）','`runtime/` 缓存目录、`extra/quickmenu.php`')
R('D-11','FR-12','内容管理：视频、文章、演员、角色、专题、网址','后台 `Vod`/`Art`/`Actor`/`Role`/`Topic`/`Website` 的 data/info/del/batch/field/updateToday（`Vod` 另有 `iplot`）',
  '各自内容表 + '+tb('type','group','vod'),'各自内容表整行：'+'；'.join(fmt(P(t)) for t in CT)+'；`mac_vod_search`（同步）；重复数据查询：**旧版用 `CREATE TABLE mac_tmpvod/tmpart/tmpwebsite`（DDL）**，Java 已改子查询（`docs/architecture/22-b13-admin-content.md` D-B13-7），本期不得回到 DDL（BR-1）','`upload/` 图片文件')
R('D-12','FR-12','分类管理','后台 `Type` 的 index/info/del/field/batch/extend/move',
  tb('type','vod','art','actor','website'),fmt(P('type'))+'；`move`：`mac_vod`、`mac_art` 的 `type_id`、`type_id_1`（'+fmt(C('vod','type_id','type_id_1'))+'；'+fmt(C('art','type_id','type_id_1'))+'）','`extra/maccms.php` 的筛选项配置')
R('D-13','FR-12','会员与交易类后台','后台 `User`/`Group`/`Card`/`Cash`/`Order`/`Plog`/`Ulog`/`Comment`/`Gbook`/`Link`/`Visit`/`Admin`/`Annex`',
  tb('user','group','card','cash','order','plog','ulog','comment','gbook','link','visit','admin','annex'),
  '各自表整行：'+'；'.join(fmt(P(t)) for t in ['user','group','card','cash','order','plog','ulog','comment','gbook','link','visit','admin','annex'])+'；`Cash.audit`：'+fmt(C('cash','cash_status','cash_time_audit'))+'、'+fmt(C('user','user_points_froze'))+'；`User.reward`：'+fmt(C('user','user_points'))+' + `mac_plog` 新增；`System` 保存会员组配置时读取 `mac_group`','`extra/maccms.php`')
R('D-14','FR-11/FR-12','资源站采集（本地版本与在线版本）','后台 `Collect`（含 `union/load/api/bind/clearbind/vod/art/actor/role/website` 与 `timing`），API `Receive`',
  tb('collect','type','vod','art','actor','role','website','comment'),'`mac_collect` 整行；入库写 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` 整行；`mac_vod_search`；远程图片落盘时 `mac_annex` 整行（仅 OP-5 放开后）','`extra/bind.php`、`extra/timming.php`；本地版本只读本地文件源')
R('D-15','FR-11/FR-12','自定义规则采集','后台 `Cj`',
  tb('cj_node','cj_content','cj_history','collect'),'`mac_cj_node` 整行；'+fmt(C('cj_content','nodeid','status','url','title','data'))+'；'+fmt(C('cj_history','md5'))+'；发布到内容表整行（目标表按规则 `SHOW COLUMNS` 映射，限 vod/art/actor/role/website 对应的列）','规则导入导出文件')
R('D-16','FR-12','图片同步（远程图片下载）','后台 `Images` 的 data/opt/del/sync',
  tb('vod','art','topic','actor','role','website'),'各内容表的图片/正文列：'+'；'.join(fmt(C(p,*[c for c in [p+'_pic',p+'_content'] if c in cols[p]])) for p in CT)+'；`mac_annex` 整行','`upload/`（仅 OP-5 放开后可下载远程图）')
R('D-17','FR-12','上传与附件','后台 `Upload`；前台头像上传',
  tb('annex','user'),fmt(P('annex'))+'；'+fmt(C('user','user_portrait')),'`upload/`、`extra/maccms.php`（upload 节）')
R('D-18','FR-9','数据库工具','后台 `Database`',
  '`SHOW TABLE STATUS`、`SHOW COLUMNS`、`SHOW CREATE TABLE`、`SELECT`（读全库 26 张表）','批量替换（`rep`）：对用户选择的表和列执行单条 `UPDATE … SET col=REPLACE(col,…)`，列限于 A 中该表的文本列；还原备份（`import`）、执行 SQL（`sql`）、优化（`optimize`）、修复（`repair`）：**会改结构或做表维护，见 OP-6**','`application/data/backup/*`')
R('D-19','FR-6','静态生成','后台 `Make`；定时任务 `make`',
  '各内容表 + `mac_type`','`*_time_make`：'+'；'.join(fmt(C(p,p+'_time_make')) for p in CT),'静态页输出目录、`extra/timming.php`')
R('D-20','FR-7','模板管理、广告','后台 `Template`','无','无','`template/`、`ads/` 目录文件')
R('D-21','FR-12','系统配置、域名、播放器/下载器/服务器配置、定时任务、站长推送、安全','后台 `System`/`Domain`/`Vodplayer`/`Voddowner`/`Vodserver`/`Timming`/`Urlsend`/`Safety`/`Update`',
  '`System`：读 `mac_group`（会员组下拉）；`Urlsend`：读 '+tb('vod','art','actor','role','topic','website'),'无','`extra/maccms.php`、`domain.php`、`vodplayer.php`、`vodserver.php`、`voddowner.php`、`timming.php`、`captcha.php` 等 `application/extra/*.php`（`var_export` 格式，BR-2）')
R('D-22','FR-12','插件','后台 `Addon`；`addons/*`',
  '无固定表','插件自带 SQL 可建表（旧 `think\\addons\\Service`），**按 BR-1 不执行，见 OP-8**','`addons/*/info.ini`、`config.php`、`extra/addons.php`')
R('D-23','FR-10','安装向导','`install` 控制器',
  '校验 26 张表存在','初始数据：`mac_admin`（首个管理员，整行）；`install.sql` 内 `INSERT`：`mac_group` 3 行、`initdata.sql`：`mac_type` 18 行；建库建表 DDL **不执行，见 OP-7**','`application/database.php`、`extra/maccms.php`、`data/install/install.lock`')

L=[]
L.append('# APPK-2 附表 D：功能 → 现有表 → 字段 逐项清单\n')
L.append('> 属于 `docs/prd/APPK-2.md`（v2）。冲突时以主文件为准。表名/列名全部取自附表 A（脚本生成并逐列校验，写错列名会使生成失败）。\n')
L.append('**写法约定**：“整行”= 该表附表 A 中的全部列。列清单出现“指定列”时，是从 PHP 源码中该写入点附近出现的该表字段取并集所得（宁多勿少），可由 `php/application` 下对应控制器/模型的更新语句复核。读取默认也是整行（ThinkPHP `select *`），只有注明字段的才限于所列字段。所有写入只做 `INSERT/UPDATE/DELETE`，不含 DDL（BR-1）。\n')
L.append('**覆盖范围**：附表 C 登记簿中 `index`、`api`、`admin`、`install` 全部 %s 行都能在下表找到所属功能（D-01～D-23）；找不到所属功能的行，在判定时按 AC-16 处理。\n'%json.load(open('.work/ctot.json'))['n'])
L.append('| 编号 | 对应 FR | 功能 | 入口（旧控制器方法） | 读取的表与字段 | 写入的表与字段 | 文件与配置（不在库里） |\n| --- | --- | --- | --- | --- | --- | --- |')
for i,fr,feat,entry,reads,writes,files,note in rows:
    f=lambda s:s.replace('|','\\|')
    L.append('| %s | %s | %s | %s | %s | %s | %s |'%(i,fr,f(feat),f(entry),f(reads),f(writes),f(files)))
L.append('\n## D.1 发现“装不下”的位置（供 OP- 使用）\n')
L.append('| 位置 | 旧版行为 | 为什么与 BR-1 冲突 | 当前处理 |\n| --- | --- | --- | --- |')
L.append('| `admin/Database.php` 还原备份、执行 SQL、优化表、修复表 | 执行备份文件或任意 SQL；`OPTIMIZE/REPAIR TABLE` | 备份含 `DROP/CREATE TABLE`；任意 SQL 可含 DDL | OP-6 |')
L.append('| `install/Index.php` `mkDatabase` | 建库建表 | DDL | OP-7 |')
L.append('| `think\\addons\\Service` 导入插件 `install.sql` | 插件建表 | DDL | OP-8 |')
L.append('| `admin/Vod.php:103-105`、`Art.php:51-53`、`Website.php:50-52` | 查重时 `DROP/CREATE TABLE mac_tmp*` | DDL | Java 已用子查询替代（D-B13-7），不需要决定；列入 AC-15 的 SNAP-2 守护 |')
L.append('| `common/util/Database.php:118-119` | 备份文件里写 `DROP TABLE IF EXISTS` 文本 | 只是导出文本，不执行；还原时才执行 | 随 OP-6 |')
open(OUT,'w',encoding='utf-8',newline='\n').write('\n'.join(L))
print(len(rows))
