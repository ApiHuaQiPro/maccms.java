P = '.work/s1/'
D = 'docs/design/APPK-2/tech-design.md'
doc = open(D, encoding='utf-8').read()


def rd(n):
    return open(P + n, encoding='utf-8').read().rstrip('\n') + '\n'


def rep(old, new):
    global doc
    assert doc.count(old) == 1, (old[:60], doc.count(old))
    doc = doc.replace(old, new)


def before(anchor, text):
    rep(anchor, text + '\n' + anchor)


rep('# 技术设计 — APPK-2 PHP 模板迁移：模板包目录与对照环境（OP-15）',
    '# 技术设计 — APPK-2 PHP 剩余功能迁移 Java：PHP 模板兼容层与完整技术设计（含 OP-15）')
rep('| 版本 | v2 |', '| 版本 | v3 |')
rep('| 创建时间 | 2026-10-31 19:15（UTC+8） |',
    '| 创建时间 | 2026-10-31 19:15（UTC+8）；v3 修订 2026-11-01 08:30（UTC+8） |')
old = '| 上游 PRD | `docs/prd/APPK-2.md` 及附表 `docs/prd/APPK-2/`（A～F）@ `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`（v9） |'
rep(old, old[:-2] + '；已经 G0 范围确认 |\n'
    '| 上游技术设计 | 本文件 v2 @ `ebd7aa60ae968229997221d3086b45ddb4fa67e1`（OP-15，独立复核与架构复审 PASS，OP-15 已关闭）；v3 在其上续写，v2 的 DEC-1～6、PATH-1～4、ENV-1～8、C-1～C-16、V-1～V-9 内容不变 |\n'
    '| 派工依据 | Issue APPK-2 评论 `01a1946f-ec1e-74b8-85d9-42e88f3fe85f`（S0.5 核验 PASS 后派 S1）；deploy 分支 `release/APPK-2-php-template-migration` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969` |')
rep('| UI 设计 | 不涉及：本版只定目录与配置，不涉及页面呈现 |',
    '| UI 设计 | 与本版并行产出（S1，@UI设计师-海外），本版未引用其提交；页面呈现以模板包原样输出为准（BR-11），本设计只定实现与验证；两者若有冲突由调度裁决 |')
i = doc.index('| 本版范围 |')
j = doc.index('\n', i)
doc = doc[:i] + ('| 本版范围 | v3：FR-1～FR-13 的完整技术设计——模块边界与 PHP 模板兼容层（§4.6、§6.3～§6.7）、页面接入与 jte 下线（§6.8～§6.9）、'
                 '`/template/**` 静态资源（§6.10，关闭 OP-T1）、后台模板管理与静态生成（§6.11～§6.12）、数据库工具四项、安装向导建表、在线功能（§6.13～§6.15）、'
                 '旧控制器对齐口径（§6.16）、六项安全修复（§6.17）、缓存与 V9-001（§6.18，关闭 OP-T2）、数据表字段（§6.19）、对照数据恢复与会话时区（§6.20，关闭 OP-T3）、'
                 '实现顺序与子任务（§7.4）。v2 的 OP-15 部分（模板包目录、配置取值、路径量、对照环境）保持不变 |') + doc[j:]

before('## 2. 范围与非目标', rd('p1.md'))
rep('- FR-1～FR-13 的实现设计（模板解析器、标签、函数、PHP 片段等效、jte 前台下线、控制器对齐、在线功能）——G0 之后在本文件 v2 起续写。\n',
    '- （v2 时）FR-1～FR-13 的实现设计——v3 已续写，见下方“v3 范围”。\n')
before('## 3. 影响面', rd('p2.md'))
before('## 4. 方案对比', rd('p3.md'))
before('## 5. 改动清单', rd('p4.md'))
before('## 6. 数据与接口', rd('p5.md'))
rep('| DEC-6 | 静态生成读写目录（FR-6） | 见 §6.2 |',
    '| DEC-6 | 静态生成读写目录（FR-6） | 见 §6.2 |\n'
    '| DEC-7（v3） | PHP 模板兼容层实现方式 | 移植编译流水线 + PHP 子集解释器（§4.6 方案 C、§6.3～§6.7） |\n'
    '| DEC-8（v3） | `/template/**` 静态资源 | Java 带白名单提供（§4.8、§6.10），关闭 OP-T1 |\n'
    '| DEC-9（v3） | 应用层缓存与对照隔离 | CACHE-1～CACHE-4、ENV-9（§4.9、§6.18），关闭 OP-T2 / V9-001 |\n'
    '| DEC-10（v3） | 安装向导建表账号 | 表单账号只用于第 5 步，运行期用 `MACCMS_DB_*`（§4.10、§6.14） |')
rep('**接口**：不涉及——本版不新增或变更任何 HTTP 接口。',
    '**接口**：v2 不新增接口；v3 新增 `/template/**` 静态资源（§6.10）、恢复 PHP 已有的 `ajax/pwd`（§6.8 ALIGN-4），前台未匹配路径改为 PHP 404 页（ALIGN-2）。v2 原文：')
before('## 7. 实现步骤（按端）', rd('p6.md') + '\n' + rd('p6b.md'))
rep('两侧端口不同造成的访问源差异由附表 F 的 EX-2 处理 |',
    '两侧端口不同造成的访问源差异由附表 F 的 EX-2 处理 |\n'
    '| ENV-9（v3） | 每个请求单元开始前、CMP-2 ① 之前清缓存：PHP 侧删除 `php-site/runtime/cache/`、`php-site/runtime/temp/`；Java 侧更新 `.compare/APPK-2/java-reset.flag` 的修改时间（Java 以 `maccms.phptemplate.compare.reset-file` 指向它，CACHE-3 ②）；恢复记录注明已清缓存 |\n'
    "| ENV-10（v3） | MySQL 会话时区 `+08:00`：PHP 侧按 SNAPD-4 写入 `database.php` 的 `params`；Java 侧 `MACCMS_DB_URL` 附加 `sessionVariables=time_zone='+08:00'`；两侧在基线报告记录 `@@session.time_zone` |")
before('## 8. 验证方式', rd('p7.md'))
before('## 9. 回滚', rd('p8.md'))
before('## 10. 风险与待决', rd('p9.md'))
s = doc.index('| 编号 | 问题 | 是否阻塞开发 | 需谁回答 | 期限 |')
e = doc.index('## 11. 需求追溯')
doc = doc[:s] + rd('p10.md') + '\n' + doc[e:]
before('## 12. 源码合理自检', rd('p11.md'))
i = doc.index('| 与 PRD 无矛盾 | 有一处需评审确认')
j = doc.index('\n', i)
doc = doc[:j + 1] + rd('p12.md') + doc[j + 1:]
before('## 14. 修订记录',
       'v3（S1 完整设计，不是评审返修）：没有新的 REV-。v2 复审的不阻断建议“正文仍有‘完整设计 v2 起’的旧版本预告”已处理：§2 与 §10 OP-T 表改为指向 v3 内容。\n')
doc = doc.rstrip('\n') + '\n| 2026-11-01 | v3 | @架构师-海外 | 完整技术设计：§1.3/1.4 现状与目标；§3.2 影响面；§4.6～4.10 方案对比（兼容层、模块、静态资源、缓存、安装账号）；C-17～C-66；§6.3～6.20（DEC-7～10、TPL-、RT-、PG-、ALIGN-、DB-、INS-、NET-、SEC-、CACHE-、SNAPD-）；ENV-9/10；§7.4 子任务拆分；V-10～V-29；RISK-T9～T16；关闭 OP-T1～T3，新增 OP-T4、OP-T5 | — |\n'
open(D, 'w', encoding='utf-8', newline='\n').write(doc)
print(len(doc.encode('utf-8')))
