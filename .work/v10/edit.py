import sys
p='docs/design/APPK-2/tech-design.md'
s=open(p,encoding='utf-8',newline='').read()
def rep(old,new,cnt=1):
    global s
    n=s.count(old)
    assert n==cnt,(n,old[:80])
    s=s.replace(old,new)
AUTH=('Issue APPK-2 评论 `01a196b5-a035-71df-bbe3-077cd09512a6`（确认人代理 Mika 代人类转达：“同意 Apache HttpClient 5 方案，先评审后实现”，回复调度的方向请求 `01a1968b-baed-7015-a20c-14e0a1d13835`）；'
      'Mika 代为转达的依据是发起人评论 `01a1958e-5c60-7d72-9328-d7a5146e1d99`（“以后允许Mika 代我转达”）；调度核验评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`')
# metadata
rep('v9 修订 2026-11-01 18:10（UTC+8） |','v9 修订 2026-11-01 18:10（UTC+8）；v10 修订 2026-11-01 18:50（UTC+8） |')
rep('| 版本 | v9 |','| 版本 | v10 |')
rep('\r\n| UI 设计 |',
    '\r\n| 已定方向（v9 DEC-14） | 出站客户端内部引擎换 Apache HttpClient 5（§4.14 方案 B）：'+AUTH+'。该授权只定方向，不是 G1 或实现放行：S3-21e 仍须 v8→v10 增量经架构评审通过、调度复核放行 G1 后才派单；将来改选须先由发起人在 Issue 另行确认，再出设计修订 |\r\n| UI 设计 |')
rep('API 契约、对外接口与其他章节不变 |',
    'API 契约、对外接口与其他章节不变。v10：只改设计一致性与决策来源（REV-NET-D01）——§12 依赖结论改为准确的依赖清单与例外口径，元数据、§4.14 登记 DEC-14 方向授权来源；选型、契约、改动项与实现均不变 |')
# 4.14
old='选 B。依赖理由：'
rep(old,'选 B（方向已获授权，见元数据“已定方向”：'+AUTH+'；该授权不代替 G1 增量评审）。依赖理由：')
# S3-21e gate
rep('| G1（v9 增量评审通过）；','| G1（v8→v10 增量评审通过）；')
# §12 rows
rep('| 无新依赖 | 是 | 不加 Maven 依赖；装配脚本用 Python 标准库与 git（与现有 `src/test/browser/*.py` 同语言） |',
    '| OP-15 部分（v2）：无新依赖 | 是（仅限 v2 OP-15 范围；全文依赖见下方“完整设计：依赖清单”） | OP-15 部分不加 Maven 依赖；装配脚本用 Python 标准库与 git（与现有 `src/test/browser/*.py` 同语言） |')
rep('| v9：新依赖有理由、范围受控 | 是 |','| v9：新依赖有理由、范围受控（v10 起清单见下方“完整设计：依赖清单”） | 是 |')
rep('| 完整设计：无新依赖 | 是 | 解释器、正则、签名、FTP 都用 JDK；删除在线编辑用的 `gg.jte:jte` 编译器依赖（C-41） |',
    '| 完整设计：依赖清单（v10 更正，REV-NET-D01；v3～v9 此行写“无新依赖｜是”，自 v9 C-76 起不成立） | 新增 1 项（唯一例外）、删除 1 项，其余不变 | '
    '**新增**：`org.apache.httpcomponents.client5:httpclient5`（C-76，S3-21e），理由与方案对比见 §4.14 DEC-14，方向授权见元数据“已定方向”（评论 `01a196b5-a035-71df-bbe3-077cd09512a6`）；'
    '`pom.xml` 不写 `<version>`，版本由 `spring-boot-dependencies` 4.1.1 管理（5.6.4），C-79 断言该 `<dependency>` 不含 `<version>`；'
    '传递依赖只允许 `httpcore5`/`httpcore5-h2`（V-33 附 `dependency:tree`）；'
    '包边界：`org.apache.hc.` 类型只允许在 `integration.http` 内使用，`OUTBOUND_TYPES` 与合成用例不改，“只有 `OutboundHttpClient` 能发请求”的护栏不变；'
    '护栏：`OutboundNetworkGuardTest` 的 `FORBIDDEN_ARTIFACTS` 只去掉 `httpclient5`，`httpclient`、`httpasyncclient`、`okhttp` 等仍禁止（C-79）。'
    '**删除**：只供在线编辑的 `gg.jte:jte` 编译器依赖（C-41）。'
    '**其余**：解释器、正则、签名、FTP 都用 JDK，不加其他 Maven 依赖。'
    '**例外口径**：本设计的新增依赖只有 `httpclient5` 一项；实现中需要任何其他新依赖（含改为显式版本），须先出设计修订并经 G1 评审 |')
# §13 new round, before ## 14
rep('\r\n## 14. 修订记录',
    '\r\nG1 增量独立复核退回（@智能调度-海外，评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`，对象 v9 @ `f2307632f362d4fa7013c1e7a327749fed586c81`；v10 处理）：\r\n\r\n'
    '| 问题 | 处理 | 说明 | 修改位置 |\r\n| --- | --- | --- | --- |\r\n'
    '| REV-NET-D01 §12 同时写“v9：新依赖有理由、范围受控”与“完整设计：无新依赖｜是”，与 C-76 新增 `httpclient5` 矛盾；未登记方向授权来源 | 已修改 | '
    '“完整设计：无新依赖”改为“完整设计：依赖清单”：新增 `httpclient5` 一项（唯一例外）、删除 `gg.jte:jte`（C-41），并写明 BOM 管理版本、传递依赖、包边界、护栏与例外口径，与 C-76、C-79、§4.14、V-33 一致；v2 的“无新依赖”行限定为 OP-15 范围；'
    '元数据新增“已定方向（v9 DEC-14）”并在 §4.14 引用：授权评论 `01a196b5-a035-71df-bbe3-077cd09512a6`（Mika 代人类转达），代转依据为发起人评论 `01a1958e-5c60-7d72-9328-d7a5146e1d99`，调度核验 `01a196b6-9850-74d8-b3e5-22800625b6e6`，并写明该授权不是 G1 或实现放行；S3-21e 门禁改为 v8→v10 增量评审通过。选型、契约、C-76～C-80、NET-7、V-33 与实现内容不变 | '
    '元数据（版本、创建时间、已定方向、本版范围）；§4.14；§7.4 S3-21e；§12 依赖相关三行；§13；§14 |\r\n'
    '\r\n## 14. 修订记录')
# §14 row: append after v9 row (last line of table)
i=s.index('| 2026-11-01 | v9 | @架构师-海外 |')
j=s.index('\r\n',i)
s=s[:j]+'\r\n| 2026-11-01 | v10 | @架构师-海外 | 按 G1 增量独立复核退回（评论 `01a196b6-9850-74d8-b3e5-22800625b6e6`）修设计一致性与决策来源：§12“完整设计：无新依赖”改为准确的依赖清单与例外口径（新增 `httpclient5`、删除 `gg.jte:jte`），v2 行限定 OP-15 范围；元数据与 §4.14 登记 DEC-14 方向授权（评论 `01a196b5-a035-71df-bbe3-077cd09512a6`，代转依据 `01a1958e-5c60-7d72-9328-d7a5146e1d99`）；S3-21e 门禁改为 v8→v10 增量；选型、契约、改动项与实现不变 | REV-NET-D01 |'+s[j:]
open(p,'w',encoding='utf-8',newline='').write(s)
print('ok')
