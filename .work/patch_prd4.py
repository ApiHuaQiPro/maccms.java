import re,glob
def edit(path,pairs):
    s=open(path,encoding='utf-8').read()
    for a,b in pairs:
        assert s.count(a)==1,(path,a[:60],s.count(a))
        s=s.replace(a,b)
    open(path,'w',encoding='utf-8',newline='\n').write(s)

P='docs/prd/APPK-2.md'
edit(P,[
("| 版本 | v3 |","| 版本 | v4 |"),
("| 状态 | 评审中（第 2 轮修订） |","| 状态 | 评审中（第 3 轮修订） |"),
# FR-12 composition: lead with 376 + 3
("固定 **379 行** = 前台 index 143 + API 21 + 后台 admin 210 + 安装向导 5（69 个控制器文件 = index/api/admin 三个目录 68 个 + 安装向导 1 个，无 public 方法的类也计入；379 行 = 371 个 public 方法行（剔除 `__construct`）+ 3 个无方法类占位行 + 5 个安装向导步骤行；3 个无方法类为 `api/Base`、`admin/Safety`、`admin/Update`，已预填“旧版无功能”，其余 376 行为“未判定”；提取规则见附表 C 开头）",
 "固定 **379 行** = **376 个真实功能行**（前台 index 143 + API 20 + 后台 admin 208 + 安装向导 5，初始均为“未判定”）+ **3 个无 public 方法的类占位行**（`api/Base`、`admin/Safety`、`admin/Update`，R-144、R-294、R-334，已预填“旧版无功能”）；阶段 A 的待判定对象 = 376 行；源码口径：控制器文件 69 个（index/api/admin 三个目录 68 个 + 安装向导 1 个，无 public 方法的类也计入文件数），376 行里的方法行 371 个已剔除 `__construct`；提取规则见附表 C 开头"),
("[附表 D](APPK-2/D-feature-table-field.md)：27 行功能（D-01～D-23，含 D-07a～D-07e）逐项列出读取的表与字段、写入的表与字段（“整行”= 附表 A 中该表全部列；指定列的逐列列出）、不在库里的文件与配置；D.1 列出所有“装不下”的位置。表名、列名由脚本对照附表 A 逐列校验。",
 "[附表 D](APPK-2/D-feature-table-field.md)：27 行功能（D-01～D-23，含 D-07a～D-07e）逐项列出读取的表与字段、写入的表与字段（“整行”= 附表 A 中该表全部列；指定列逐列列出，不使用通配符）、不在库里的文件与配置；D.1 列出所有“装不下”的位置；D.2 命名列集合（TS-TAG、CS-SORT-<表>、CS-SUGGEST-<表> 等，逐列展开）；D.3 动态场景 DYN-1～DYN-9（标签读取与排序、Ajax 返回列、采集目标表、自定义规则采集、数据库工具批量替换、配置文件等）各写允许集合、来源规则和越界判定；D.4 把附表 C 的 379 行逐行映射到 D 编号（脚本核对无遗漏）。表名、列名由脚本对照附表 A 逐列校验。"),
("| AC-16 | FR-13 | 附表 D 已锁定（27 行，D-01～D-23） | 评审任一迁移项的设计单与实现 | ①设计单“读写的表与字段”一栏逐项落在附表 D 对应行的范围内（读写的表、列、文件配置都能在该行找到）；②设计单对应的附表 C 行，每行都能指向一个 D- 行；③出现 D 表没有的表/列/文件配置时，设计单被退回并新增 OP-（或修订本文增补附表 D）；④D 表中“整行”所指的表都在附表 A 的 26 张之内 |",
 "| AC-16 | FR-13 | 附表 D 已锁定（v4：27 行 D-01～D-23 + D.2 命名列集合 + D.3 DYN-1～DYN-9 + D.4 映射） | 评审任一迁移项的设计单与实现 | ①设计单“读写的表与字段”一栏逐项按附表 D 开头的“越界判定”四步判定：表在该行读取栏/写入栏（方向正确）、列在该行或 D.2 集合内、DYN-n 的允许集合满足、文件配置在该行“文件与配置”栏；任一为否即越界；②设计单对应的附表 C 行，每行在 D.4 都有唯一的 D 编号，设计单的读写范围按该 D 行判定；③出现越界项时，设计单被退回并新增 OP-（或修订本文增补附表 D，版本 +1）；④D 表中“整行”所指的表都在附表 A 的 26 张之内；⑤附表 D 中不得出现通配符或“由……决定”“按规则”类开放表达（用 `grep` 检索 D-01～D-23 与 D.3 的表体，命中 0 处，D 的“v3 → v4 展开对照”表除外） |"),
("字段逐项在附表 A（503 列）与附表 D（27 行，列名脚本校验）","字段逐项在附表 A（503 列）与附表 D（27 行 + D.2 命名列集合 + D.3 动态场景，列名脚本校验）"),
("附表 D 中“指定列”取源码写入点附近该表字段的并集，宁多勿少，未逐语句运行验证","附表 D 中“指定列”取源码写入点附近该表字段的并集，宁多勿少，未逐语句运行验证（D.3 的标签排序白名单、Ajax 返回列、`rep` 的表与列、`Cj` 发布目标表四处已对照源码行核对）"),
])

# revision row + self-check
s=open(P,encoding='utf-8').read()
v4=("| v4 | 2026-10-30 | 评审第 3 轮（针对 v2 提交 `003bf30` 的复核/复审 FAIL：Q-005、Q-006）。**Q-005**：v3 已修，v4 再核对并把 FR-12、附表 C 的写法改为“379 行 = 376 个真实功能行（index 143 + api 20 + admin 208 + 安装向导 5）+ 3 个无功能类预填占位行”，待判定对象 376，与 KPI-3a、AC-14、RISK-10 一致；源码口径（控制器文件 69 个、方法行 371 个已剔除构造函数）保留，并写有可复现命令。**Q-006**：附表 D 重写——①`type_tpl*`、`*_id/*_name/*_en/*_pic`、`vod_pwd*`、`vod_down_*`、`*_time_make`、`*_status` 等通配全部展开为附表 A 中的具体列（新增 D.2 命名列集合与“v3 → v4 展开对照”）；②D-01 改为 TS-TAG 11 张表、D-02 逐标签列表与整行、D-15 改为 `mid`=1→`mac_vod`/`mid`=2→`mac_art`、D-18 `rep` 的列更正为“附表 A 中该表任一列”（v3 的“文本列”与源码不符）；③新增 D.3 动态场景 DYN-1～DYN-9，各写允许集合、来源规则（带源码行）、越界判定；④新增 D.4：附表 C 的 379 行逐行映射 D 编号（脚本核对无遗漏）；D-07a～D-07e 的方法归属按 C 的 42 行重新划分；⑤顺带更正 3 处不存在的文件：`extra/connect`、`extra/pay`、`extra/rewrite` 实为 `extra/maccms.php` 的 `connect`、`pay`、`rewrite` 节；⑥AC-16 改为引用附表 D 开头的四步越界判定与 D.3，新增 ⑤ 通配符检索。其余编号与 OP-1～OP-14 的内容和状态未改 | 评审第 3 轮前置（智能调度-海外 独立复核 FAIL 评论 `01a190bb-f2db-7a41-af18-714c5d36b3c2`；产品评审 FAIL 评论 `01a190bb-7181-705d-ae68-c0a0cd65c768`；Q-005、Q-006） |\n")
# insert the v4 row immediately after the v3 row
lines=s.split("\n")
idx=[i for i,l in enumerate(lines) if l.startswith("| v3 |")]
assert len(idx)==1
lines.insert(idx[0]+1,v4.rstrip("\n"))
s="\n".join(lines)
a="✅ = 有依据；❌ = 有缺口并已说明。v2 对四项评审 Must Fix 的处理、v3 对 Q-005 的处理见 §15。"
assert s.count(a)==1
s=s.replace(a,a+"v4 对 Q-005/Q-006 的处理见 §15；v4 复核：附表 D 用脚本核对 D.2/D.3/D.4（379 行全部映射到唯一 D 编号，列名全部对照附表 A 通过）。")
open(P,'w',encoding='utf-8',newline='\n').write(s)

# appendix C wording
edit('docs/prd/APPK-2/C-parity-registry.md',[
("**分母**：共 **379** 行 = 371 个方法行 + 3 个无方法类占位行 + 5 个安装向导行；按端拆分为前台 index 143 + API 21（20 方法 + 1 占位）+ 后台 admin 210（208 方法 + 2 占位）+ 安装向导 5。其中 **3 行预填“旧版无功能”**（R-144、R-294、R-334），其余 **376 行**初始为“未判定”，即 KPI-3a 的分母为 **376**。",
 "**分母**：登记簿共 **379** 行 = **376 个真实功能行**（前台 index 143 + API 20 + 后台 admin 208 + 安装向导 5）+ **3 个无 public 方法的类占位行**（R-144 `api/Base.php`、R-294 `admin/Safety.php`、R-334 `admin/Update.php`，已预填“旧版无功能”）。阶段 A 的待判定对象 = 那 **376 个真实功能行**，即 KPI-3a 的分母为 **376**；3 个占位行只复核源码证据，不计入分母。"),
])
# version headers on A/B/C/D lines
for p in glob.glob('docs/prd/APPK-2/[ABC]-*.md'):
    s=open(p,encoding='utf-8').read()
    s2=s.replace("`docs/prd/APPK-2.md`（v3）","`docs/prd/APPK-2.md`（v4）")
    assert s2!=s,p
    open(p,'w',encoding='utf-8',newline='\n').write(s2)
