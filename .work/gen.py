import json,re,os
OUT='docs/prd/APPK-2-annex'
os.makedirs(OUT,exist_ok=True)
tabs=json.load(open('.work/tabs.json',encoding='utf-8'))
meth=json.load(open('.work/methods.json'))
tpls=json.load(open('.work/tpls.json',encoding='utf-8'))
cols={t:[c for c,_ in v['cols']] for t,v in tabs.items()}
order=sorted(tabs)
def w(name,txt): open(f'{OUT}/{name}','w',encoding='utf-8',newline='\n').write(txt)

# ---------------- A ----------------
A=[]
A.append('# APPK-2 附表 A：原表原结构基线与结构快照定义\n')
A.append('> 属于 `docs/prd/APPK-2.md`（v2）。冲突时以主文件为准。基线来源：`php/application/install/sql/install.sql`（26 条 `CREATE TABLE`，共 %d 列；初始数据在同目录 `initdata.sql`：`mac_type` 18 行 INSERT，`install.sql` 内 `mac_group` 3 行 INSERT）。本附表由脚本从该 SQL 解析生成，列顺序、类型、默认值照原文，未做改动。\n'%sum(len(v) for v in cols.values()))
A.append('## A.1 结构快照 SNAP 的固定内容\n')
A.append('SNAP 是对**同一个一次性库**用 MySQL 5.7 的 `information_schema` 与 `SHOW CREATE TABLE` 采集的下列 8 项。任何一项出现与基线不同的行，即判 BR-1 被破坏。所有验收（AC-11、AC-12、AC-15 及每个迁移项收尾）用**同一份采集脚本、同一份报告格式**，不另行比较。\n')
A.append('| 项 | 采集内容（来源表/命令与列） | 判定 |\n| --- | --- | --- |')
rows=[
('SNAP-1','库默认字符集与排序规则：`SCHEMATA.DEFAULT_CHARACTER_SET_NAME`、`DEFAULT_COLLATION_NAME`','与基线一致'),
('SNAP-2','表集合与表属性：`TABLES` 中 `TABLE_NAME`、`TABLE_TYPE`、`ENGINE`、`ROW_FORMAT`、`TABLE_COLLATION`、`CREATE_OPTIONS`、`TABLE_COMMENT`（不含 `AUTO_INCREMENT`、行数、数据长度、更新时间：这些随数据变化）','表名集合恰为本附表 A.2 的 26 张（多一张或少一张都算差异，含 `mac_tmp*` 临时表）；每张表属性与基线一致'),
('SNAP-3','列：`COLUMNS` 中 `TABLE_NAME`、`COLUMN_NAME`、`ORDINAL_POSITION`、`COLUMN_DEFAULT`、`IS_NULLABLE`、`DATA_TYPE`、`CHARACTER_MAXIMUM_LENGTH`、`NUMERIC_PRECISION`、`NUMERIC_SCALE`、`CHARACTER_SET_NAME`、`COLLATION_NAME`、`COLUMN_TYPE`、`COLUMN_KEY`、`EXTRA`、`COLUMN_COMMENT`、`GENERATION_EXPRESSION`','列数 = %d；逐列与基线一致'%sum(len(v) for v in cols.values())),
('SNAP-4','索引：`STATISTICS` 中 `TABLE_NAME`、`NON_UNIQUE`、`INDEX_NAME`、`SEQ_IN_INDEX`、`COLUMN_NAME`、`COLLATION`、`SUB_PART`、`NULLABLE`、`INDEX_TYPE`、`INDEX_COMMENT`（不含 `CARDINALITY`：随数据变化）','逐行与基线一致'),
('SNAP-5','约束边界：`TABLE_CONSTRAINTS`、`KEY_COLUMN_USAGE`、`REFERENTIAL_CONSTRAINTS` 全部列','与基线一致；`REFERENTIAL_CONSTRAINTS`（外键）必须为 0 行（基线没有外键）'),
('SNAP-6','数据库对象：`TRIGGERS`、`VIEWS`、`ROUTINES`（存储过程与函数）、`EVENTS` 各自在本库的行数','4 项均为 0 行（基线没有这些对象）'),
('SNAP-7','建表语句：26 张表各一条 `SHOW CREATE TABLE` 原文，去掉其中 `AUTO_INCREMENT=<数字>` 片段后逐字符比较','逐字符一致'),
('SNAP-8','采集环境：MySQL 版本字符串、`character_set_server`、`collation_server`、`sql_mode` 写进报告头','仅记录，不参与差异判定；基线与复测的版本必须相同，不同则该次报告无效'),
]
for r in rows: A.append('| %s | %s | %s |'%r)
A.append('\n**基线文件**：在一次性库导入 `install.sql`（并导入 `initdata.sql`）后，用同一采集脚本生成 `docs/test/APPK-2/schema-baseline.*`（格式由设计单定，必须能逐行 diff）。生成基线是 AC-15 的前置产物（见主文件 §13 前置验收产物 PRE-1）。基线中表、列、索引、表属性必须与本附表 A.2 逐项一致，不一致则基线无效。\n')
A.append('**报告判定**：`docs/test/APPK-2/schema-diff-<时点>.md` 逐项列出 SNAP-1～SNAP-7 的差异行数；全部为 0 才算通过，KPI-2 = 7 项差异行数之和。AC-11、AC-12、AC-15 引用同一报告格式。\n')
A.append('**辅助证据（不替代 SNAP）**：①`SchemaGuardTest` 静态扫描通过；②验收环境里应用使用的数据库账号只授予 `SELECT, INSERT, UPDATE, DELETE`，跑完验收用例后日志中没有因权限不足被拒绝的 DDL 语句（出现即说明有迁移项在尝试改结构，按 BR-1 判失败）。\n')
A.append('## A.2 26 张表的列清单（基线）\n')
A.append('| 编号 | 表 | 引擎与属性 | 列数 | 索引/键行数 |\n| --- | --- | --- | --- | --- |')
for i,t in enumerate(order,1):
    A.append('| T-%02d | `%s` | `%s` | %d | %d |'%(i,t,tabs[t]['opts'],len(cols[t]),len(tabs[t]['keys'])))
A.append('\n以下逐表列出全部列（名称 + 原文定义）与键。后文“整行”= 该表这里列出的全部列。\n')
for i,t in enumerate(order,1):
    A.append('### T-%02d `%s`（%d 列）\n'%(i,t,len(cols[t])))
    A.append('`%s`\n'%tabs[t]['opts'])
    A.append('| # | 列 | 定义 |\n| --- | --- | --- |')
    for j,(c,d) in enumerate(tabs[t]['cols'],1):
        A.append('| %d | `%s` | `%s` |'%(j,c,d.replace('|','\\|')))
    A.append('\n键/索引：'+'；'.join('`%s`'%k.replace('|','\\|') for k in tabs[t]['keys'])+'\n')
w('A-schema-baseline.md','\n'.join(A))

# ---------------- C parity registry ----------------
C=[]
C.append('# APPK-2 附表 C：旧控制器方法对齐登记簿（FR-12 的固定分母）\n')
C.append('> 属于 `docs/prd/APPK-2.md`（v2）。由脚本按下列规则从源码提取，基线为本仓库 `php/` 目录（提交 `b6cc7d8`）。\n')
C.append('**提取规则（固定）**：对 `php/application/{index,api,admin}/controller/*.php` 与 `php/application/install/controller/Index.php` 每个文件，取全部 `public function <名>(`，**排除 `__construct`**（构造函数不是功能入口，其行为由所属方法的行覆盖）；每个方法一行。`install` 控制器的 `index($step)` 内部按步骤拆成 5 行（I-1～I-5）。复现命令：`grep -nE "^\\s*public\\s+function\\s+\\w+" <文件>` 并剔除 `__construct`。\n')
C.append('**判定列取值**：初始均为“未判定”。判定后只能写 `✅已对齐`（附 Java 类/方法与测试名）、`旧版无功能`（附 PHP 源码行证据）、`排除-<OP-n 或 D 编号>`、`缺口`。`缺口` 行必须在 `GAP-n` 登记簿（主文件 §9.1）里有一行，并按 AC-19 走到终态。\n')
n=0;rows=[];tot={}
def add(grp,ctl,m,jud='未判定',ev=''):
    global n
    n+=1
    rows.append('| R-%03d | %s | `%s` | `%s` | %s | | %s |'%(n,grp,ctl,m,jud,ev))
    tot[grp]=tot.get(grp,0)+1
for k,ms in meth.items():
    grp,ctl=k.split('/')
    if grp=='install': continue
    real=[m for m in ms if m!='__construct']
    if not real:
        add(grp,ctl+'.php','（类体无 public function）','旧版无功能','源码核实：`php/application/%s/controller/%s.php` 无 public 方法'%(grp,ctl))
    for m in real: add(grp,ctl+'.php',m)
steps=[('I-1','协议/入口页（`index($step=0)`）'),('I-2','环境检测（`step2`，`checkNnv`/`checkDir`/`checkFunc`）'),('I-3','数据库信息页（`step3`）'),('I-4','安装执行（`step4`，`mkDatabase` 建库建表→OP-7；写 `database.php`）'),('I-5','完成页（`step5`，初始数据与 `install.lock`）')]
for sid,desc in steps:
    n+=1; rows.append('| R-%03d | install | `Index.php` | `%s` %s | 未判定 | | |'%(n,sid,desc)); tot['install']=tot.get('install',0)+1
C.append('**分母**：共 **%d** 行 = 前台 index %d + API %d + 后台 admin %d + 安装向导 %d（其中 2 行“类体无 public 方法”已预填“旧版无功能”，其余 %d 行初始为“未判定”）。\n'%(n,tot['index'],tot['api'],tot['admin'],tot['install'],n-2))
C.append('| 行号 | 端 | 控制器文件 | 方法 | 判定 | Java 位置与测试 | 证据 / 编号 |\n| --- | --- | --- | --- | --- | --- | --- |')
C+=rows
w('C-parity-registry.md','\n'.join(C))
json.dump({'n':n,'tot':tot},open('.work/ctot.json','w'))
print('C rows',n,tot)

# ---------------- B matrix ----------------
names=dict(tpls)
for k in ['actor','art','role','topic','vod','website','label/','public/','public/(page_404 自定义名)','label/<文件名>']: names.pop(k,None)
dyn_def={'actor/type','actor/show','actor/detail','art/type','art/show','art/detail','role/detail','topic/detail','vod/type','vod/show','vod/detail','vod/play','vod/down','website/type','website/show','website/detail'}
def kind(nm):
    mod,page=nm.split('/')
    if mod=='user': return '会员中心'
    if mod=='rss': return 'RSS'
    if page.startswith('ajax'): return 'ajax 片段'
    if page.endswith('_pwd'): return '观看密码'
    if page=='index' and mod in('vod','art','actor','role','topic','website','plot'): return '模块首页'
    if page in('type','show'): return '分类/列表'
    if page=='search': return '搜索'
    if page=='detail': return '详情'
    if page in('play','down','player','downer'): return '播放/下载'
    if page=='rss': return 'RSS'
    if page in('copyright','confirm'): return '提示'
    if mod=='public': return '公共提示'
    return '其他'
E_kinds={'分类/列表','搜索','详情','ajax 片段','RSS','播放/下载'}
gated={'vod/play','vod/down','vod/player','vod/downer','vod/detail','art/detail','vod/detail_pwd','art/detail_pwd','vod/player_pwd','vod/downer_pwd','vod/copyright'}
user_open={'user/login','user/reg','user/findpass','user/findpass_msg','user/ajax_login','user/ajax_info'}
user_list={'plays','downs','favs','ulog','plog','cash','orders','cards','comment','gbook','reward'}
B=[]
B.append('# APPK-2 附表 B：验收矩阵（前台桌面、手机、后台模板管理、静态生成）\n')
B.append('> 属于 `docs/prd/APPK-2.md`（v2）。本附表固定 KPI-1 与 AC-5～AC-10 的分母与样本。冲突时以主文件为准。\n')
B.append('## B.1 前台桌面页面矩阵（FR-5，分母固定）\n')
B.append('**提取规则（固定）**：模板名取 `php/application/index/controller/*.php` 与 `php/application/common/controller/All.php` 中所有 `fetch(\'<名>\')` / `label_fetch(\'<名>\')` 的字面名，加上 `mac_tpl_fetch(<模块>, …, <默认名>)` 的默认名（`type`/`show`/`detail`/`play`/`down`），去重。另有运行期动态名，见 B.1.2。v1 写的“111 条”无法由固定规则复现，v2 以本表为准。\n')
rowsF=[];nN=nE=nP=0
for i,nm in enumerate(sorted(names),1):
    k=kind(nm)
    flags='N'
    if k in E_kinds or (nm.startswith('user/') and nm.split('/')[1] in user_list): flags+='E'
    if nm in gated or (nm.startswith('user/') and nm not in user_open): flags+='P'
    nN+=1; nE+=('E' in flags); nP+=('P' in flags)
    src='动态默认名' if nm in dyn_def else '字面名'
    rowsF.append('| F-%03d | `%s` | %s | %s | %s |'%(i,nm,k,src,flags))
total_cases=nN+nE+nP
B.append('### B.1.1 固定模板名（%d 条）与样本\n'%len(names))
B.append('**样本规则（固定）**：每条模板名必有 **N 样本**（正常数据，1 个）；标 `E` 的另有 **E 样本**（空结果或内容不存在，期望旧版的空列表/404 表现）；标 `P` 的另有 **P 样本**（未登录或会员组权限不足，期望旧版的跳转/提示表现）。`E` 规则：分类/列表、搜索、详情、ajax 片段、RSS、播放/下载类，以及 `user/` 下的 plays、downs、favs、ulog、plog、cash、orders、cards、comment、gbook、reward 列表页；`P` 规则：`vod/play`、`vod/down`、`vod/player`、`vod/downer`、`vod/detail`、`art/detail`、各 `*_pwd`、`vod/copyright`，以及 `user/` 下除 login、reg、findpass、findpass_msg、ajax_login、ajax_info 之外的全部页面。\n')
B.append('**KPI-1 分母（桌面固定部分）= %d 个样本** = N %d + E %d + P %d。每个样本由“请求 URL + 数据状态 + 期望 PHP 输出”组成，样本数据由一次性库的合成数据产生；样本清单文件 `docs/test/APPK-2/sample-desktop.md` 是 AC-5 的前置产物（PRE-3），其行数必须等于固定部分 %d 加 B.1.2 的样本数，每个“F-编号 + 样本类型”占一行；行数不符则 AC-5 不可执行。\n'%(total_cases,nN,nE,nP,total_cases))
B.append('| 编号 | 模板名 | 类别 | 来源 | 样本类型 |\n| --- | --- | --- | --- | --- |')
B+=rowsF
B.append('\n### B.1.2 运行期动态模板名\n')
B.append('| 编号 | 动态名 | 样本（固定） |\n| --- | --- | --- |')
B.append('| F-D1 | `label/<文件名>`（`Label.php:22`） | 模板包 `label/` 目录下**每个** `.html` 文件 1 个 N 样本；另 1 个非法样本：文件名含 `/` 或文件不存在，期望旧版“非法请求”提示。分母 = 模板包 label 文件数 + 1，OP-1 关闭后由 `sample-desktop.md` 固定 |')
B.append('| F-D2 | `public/<page_404 配置名>`（`All.php:112`） | 2 个样本：`app.page_404` 为空（用 `public/jump`）；`app.page_404` 设为模板包内另一个文件名 |')
B.append('| F-D3 | 内容自带模板名覆盖（`mac_tpl_fetch` 的 `*_tpl` 字段） | 固定 16 个覆盖点：vod 5（`type_tpl`、`type_tpl_list`、`vod_tpl`、`vod_tpl_play`、`vod_tpl_down`）、art 3（`type_tpl`、`type_tpl_list`、`art_tpl`）、actor 3（`type_tpl`、`type_tpl_list`、`actor_tpl`）、website 3（`type_tpl`、`type_tpl_list`、`website_tpl`）、role 1（`role_tpl`）、topic 1（`topic_tpl`）；每个覆盖点 2 个样本（设为非默认文件名；清空），共 32 个样本 |')
B.append('\n**内置视图（不在模板包里，随程序）**：`php/application/index/view/index/index.html`、`public/jump.html`、`public/msg.html` 三个文件；由附表 C 登记簿相关行验收，不计入 KPI-1 分母。\n')
B.append('## B.2 手机模板矩阵（FR-8）\n')
B.append('- **页面样本**：B.1.1 的每条模板名各取其 N 样本，在手机 UA 且 `mob_status` 与 `site_wapurl`/Host 满足手机条件下重放一次，对照 `mob_template_dir/mob_html_dir`。分母 = %d。\n'%nN)
B.append('- **模板选择样本**：`mob_status` ∈ {0,1,2} × UA ∈ {手机,桌面} × Host ∈ {等于 `site_wapurl`, 不等于} = 12 个组合，期望值取 `SiteTemplateSelectionGoldenTest` 的黄金结果。分母 = 12。\n')
B.append('- **语言包样本**：`application/lang/zh-cn.php` 与 `zh-tw.php` 各 1773 个键（两个文件键集合完全相同，源码核实），每个键 1 个样本 = **3546** 个 `lang(key)` 输出对照。\n')
B.append('## B.3 后台模板管理矩阵（FR-7，`admin/controller/Template.php`）\n')
B.append('旧控制器共 6 个 public 方法：`__construct`、`index`、`ads`、`info`、`del`、`wizard`。固定验收用例（分母 = 10）：\n')
B.append('| 编号 | 方法 | 用例 | 期望 |\n| --- | --- | --- | --- |')
W=[('W-01','index','列出模板目录（模板目录与子目录各 1 个）','文件名、大小、修改时间与 PHP 列表一致'),
('W-02','ads','列出广告目录 `ads_dir` 下文件','与 PHP 一致'),
('W-03','info','打开 1 个 `.html` 文件编辑页','内容与文件一致'),
('W-04','info','保存修改（合法内容）后刷新对应前台页','不重启即生效（原子替换，BR-6）'),
('W-05','info','保存含 `<?`/`php`/`eval` 的内容（旧过滤规则命中项）','被拒绝，文件不变'),
('W-06','info','路径含 `../`、扩展名不在白名单（`html/htm/js/xml` 之外）','被拒绝'),
('W-07','info','两个管理员并发保存同一文件','后保存者收到冲突提示（B15 已有约定）'),
('W-08','del','删除 1 个文件 / 删除含 `../` 的路径','前者成功，后者被拒绝'),
('W-09','wizard','生成 vod 标签片段','输出为 `{maccms:vod …}` 语法'),
('W-10','全部','无 `template/*` 权限的管理员访问以上 5 个方法','全部拒绝，与 PHP 一致')]
for r in W: B.append('| %s | `%s` | %s | %s |'%r)
B.append('\n## B.4 静态生成矩阵（FR-6，`admin/controller/Make.php`）\n')
B.append('旧控制器共 11 个 public 方法（含 `__construct`，其余 10 个为生成入口）。v1 写“11 个生成方法”不准确，v2 以此表为准。固定验收用例（分母 = 12）：\n')
B.append('| 编号 | 方法 | 生成对象 | 用例 |\n| --- | --- | --- | --- |')
K=[('K-01','opt','生成选项页（选择要生成的内容）','页面字段与 PHP 一致'),
('K-02','make','统一入口（按参数分发）','分发到下面各方法，参数集合与 PHP 一致'),
('K-03','index','首页','路径与内容与 PHP 一致'),
('K-04','map','站点地图','同上'),
('K-05','rss','RSS 文件（baidu/google/so/sogou/bing/sm）','同上'),
('K-06','type','分类页（含分页）','1 个分类，首页、中间页、最后页'),
('K-07','topic_index','专题首页','同上'),
('K-08','topic_info','专题详情','同上'),
('K-09','info','内容详情（vod/art/actor/role/website 各 1 个）','路径与内容与 PHP 一致'),
('K-10','label','自定义标签页（`label/` 下 1 个文件）','同上'),
('K-11','全部','故意让 1 个模板渲染出错','该页进入失败列表，其余继续（沿用旧版）'),
('K-12','全部','生成后各内容表 `*_time_make` 更新','写入列恰为 vod/art/topic/actor/role/website 的 `*_time_make`（见附表 D 的 D-19）')]
for r in K: B.append('| %s | `%s` | %s | %s |'%r)
B.append('\n## B.5 其余后台功能与 API\n')
B.append('不在 B.1～B.4 内的后台功能、API、安装向导，**全部由附表 C 的登记簿验收**（分母 = %d 行）；它们不做“PHP/Java 页面逐字节对照”，按 AC-14、AC-19 判定。\n'%n)
w('B-acceptance-matrix.md','\n'.join(B))
print('B names',len(names),'N',nN,'E',nE,'P',nP,'total',total_cases)
json.dump({'names':len(names),'N':nN,'E':nE,'P':nP,'total':total_cases},open('.work/btot.json','w'))
