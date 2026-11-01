# APPK-2 模板包校验与 PRE-5 扫描方案（S3-03 交付）

> 归属：测试人员-海外（S3-03）。状态：**工具已就绪；真包已用 S3-01 的入库提交在本地只读跑通 C-9 与 C-65**。
> 本文件不含“PRE-5 已完成 / AC-1 通过”这类结论：PRE-5 第 6、7 节仍需架构师补实现说明与归属（见第 5 节）。
> 依据版本：PRD v9 `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`；技术设计 v10 `08043003d67209d79a7c2334407def58a58d2d56`；
> 旧系统模板来源提交 `4c418c2703692148a937ad92893ab8a494bff742`（MacCMS `v2020.1000.1035`）。

## 0. 一句话结论（先看这里）

| 项 | 状态 | 依据 |
| --- | --- | --- |
| 逐文件校验工具（C-9） | 已交付，离线自测 23 项全过 | `tests/compare/APPK-2/verify_template_package.py`、`selftest_offline.py` |
| 274 行清单副本 | 已交付，从 PRD 附表 E.3 机械生成并 `--check` 校验一致 | `docs/test/APPK-2/fixtures/template-package-manifest.tsv` |
| PRE-5 扫描工具（C-65） | 已交付，离线自测全过 | `scan_templates.py` |
| **真包 C-9 校验** | **已跑，返回码 0**：274 文件的路径、字节数、blob SHA-1、目录边界全部符合附表 E.3 | 第 5.1 节 |
| **真包 PRE-5 机器数据** | **已跑，27 项检查 1 项非 OK**：唯一非 OK 是「归属未定」，属预期（等架构师填） | 第 5.2 节 |
| PRE-5 **完成判定** | **不成立**：第 6、7 节的实现说明与归属仍空 | 第 5.3 节 |
| AC-1 | **不由本文件判**：需 PRE-5 定稿 + S3-05 复核后判 | — |

## 1. 工具清单与职责边界

| 文件 | 技术设计条目 | 做什么 | 谁在什么阶段跑 |
| --- | --- | --- | --- |
| `tests/compare/APPK-2/build_template_manifest.py` | 支撑 C-9 | 从 PRD 附表 E.3 生成/校验 274 行清单副本 | 任何一方；`--check` 用于防手改 |
| `tests/compare/APPK-2/verify_template_package.py` | **C-9** | 逐文件 `git blob SHA-1` + 字节数 + 路径集合 + 目录边界（只 `default_pc`/`default_wap`，无 `default_wap/ads`） | S3-05 对 S3-01 的包跑；PRE-2 对 `php-site`/`java-site` 各跑一次 |
| `tests/compare/APPK-2/scan_templates.py` | **C-65** | PRE-5 需要的机器事实：标签计数、其他语法计数、`{:函数}`、`{$变量}`、PHP 代码位置、`{include}`、时钟/随机/会话来源台账 | S3-05 出数据；架构师在 S3-04 补实现说明与归属 |
| `tests/compare/APPK-2/make_synthetic_package.py` | 支撑自测 | 造合成夹具（**不是真包**，文件名带 SYNTHETIC 前缀） | 只用于自测 |
| `tests/compare/APPK-2/selftest_offline.py` | 支撑自测 | 23 条「应当通过 / 应当报错」的离线自测 | 任何一方在改过这四个脚本后重跑 |

三个约束，写给复核人核对：

1. **只读、离线。** 五个脚本都不联网、不启动 PHP/Java、不发 HTTP、不读凭据；对输入目录只 `open(..., 'rb')` 与 `os.scandir`，不写、不删输入目录里的任何文件。
2. **不冒充结论。** `scan_templates.py` 遇到无法归属的时钟/随机/会话来源时记 `owner=null` 并返回 2；**从不**为了让检查变绿而自动填一个 EX-。附表 F.6 已明文规定 `by="rnd"` → DATA-2，这是唯一由脚本预填的映射。
3. **返回码不混用。** `0` = 一致；`2` = 比对发现不符（缺陷/环境信号）；`1` = 用法或输入错误；`3` = 脚本自身异常。复核时看返回码不看措辞，可避免把“脚本崩了”读成“校验失败”。

## 2. `verify_template_package.py`（C-9）判定口径

| 检查 | 通过条件 | 不符时 |
| --- | --- | --- |
| 路径集合 | 目标 `template/` 下 274 个相对路径与清单逐一相同 | 逐条列出「清单有、目标没有」「目标有、清单没有」 |
| 字节数 | 每个文件与附表 E.3 的字节数相等 | 逐条列出实际值与期望值 |
| blob SHA-1 | `sha1("blob <len>\0" + bytes)` 与清单相等 | 逐条列出两个哈希 |
| 目录边界 | `template/` 下只有 `default_pc`、`default_wap`；`default_wap/ads` 不存在；`template/` 下没有裸文件 | 逐条列出 |
| 分目录计数 | `default_pc` 147、`default_wap` 127、总计 274 | 列出实际值 |
| CRLF | 无文件含 `\r\n` | 列出前 5 个（RISK-T1 / NOIGN-4：CRLF 会让 blob SHA-1 与官方不符） |

**关于 `git hash-object` 的一个坑（本机实测，必须写进验收口径）：** 本工作区 `core.autocrlf=true`。
`git hash-object <工作区文件>` 会先应用 CRLF 过滤器，于是 CRLF 文件得到的是 **LF 化之后**的哈希，
与“按字节校验”自相矛盾。实测：某 38 字节 CRLF 文件，`git hash-object` 给出的是 36 字节 LF 版本的
blob `950c42d…`，而按字节算是 `c98fd11…`。因此脚本默认在 Python 里按字节算，`--use-git` 走
`git hash-object --no-filters`；并在启动时把 `core.autocrlf` 的实际取值打印进报告，避免复核人
用交替口径争论。**这也是 RISK-T1 的机制本身**，S3-05 跑真包时若报出 CRLF，就是 `.gitattributes`
规则（C-5）没生效或包被转换过，属于要退回 S3-01 的问题。

三种输入形态（`--target`）都支持：`<站点根>`（`template/` 在其下）、
`third_party/maccms-template-v2020.1000.1035`、以及 `template/` 目录本身。

## 3. `scan_templates.py`（C-65）产出与 PRE-5 的关系

脚本输出两份东西：

* `scan-result.json`：**机器事实**（每文件的字节数、blob SHA-1、标签/语法/函数/变量/`{include}`/PHP 代码/非确定来源逐处行号），供 S3-05 的复核与后续 RT 用例引用；
* `template-scan-draft.md`：**PRE-5 表格初稿**（Markdown），其中“实现说明”与“归属”两列**留空**。

PRE-5 的完成判定（PRD §13）与初稿的差距，逐条写清楚：

| PRE-5 要求 | 初稿是否满足 | 谁补 |
| --- | --- | --- |
| 168 个 `.html`（92+76）各占一行以上 | 满足（第 8 节逐文件表，每行一个 `.html`） | — |
| 每处 PHP 代码有对应实现说明 | **不满足**：第 6 节列出文件与行号，说明列空 | 架构师（S3-04），写作条目 C-17～C-24 的实现依据 |
| 所有当前时间/日期、随机值、会话/token 位置逐处登记并归属 EX-1 / EX-2 / DATA-2 | **不满足**：第 7 节逐处登记，但只有 `by="rnd"` → DATA-2 有归属，其余 `owner=null` | 架构师；若需新增 EX-，按 BR-7 ⑤ 走 PRD 版本 +1 与发起人确认 |
| 没有“归属未定”的行 | **不满足**：脚本会返回 2 并列出未归属项 | 同上 |
| 与附表 E.4 EF-1～EF-7 事实一致 | 脚本逐条比对 EF-2/EF-3/EF-5 与目录结构，不一致即报 MISMATCH | 若真包报 MISMATCH，先查是扫描器写错还是 PRD 附表 E 需要更正（后者走 PRD 版本 +1） |

**因此：任何人不得把 `template-scan-draft.md` 当作 PRE-5 完成件提交。** 它只是把架构师要填的空格
先机械填好，减少手工抄错。

## 4. 扫描口径的已知取舍（供架构师 S3-04 确认或否决）

1. **标签/语法用正则近似，不是 PHP 词法分析。** 统计对象是 `{maccms:…}`、`{if}`、`{volist}`、`{include}` 等
   ThinkPHP 定界标签的**文本出现次数**，与 PRD 附表 E.4 的取证口径相同（附表 E.4 也是按文本出现统计）。
   若架构师认为 PRE-5 需要“按语法树统计”（例如嵌套标签内的条件判断分支数），工具需要重写——现在说清楚，别到 S3-05 才发现口径不一致。
2. **`{:函数()}` 与 `{$变量}` 只统计“出现次数 + 行号”，不判断该函数/变量在 PHP 里是否存在。** 后者属于
   RT-7 白名单与实现完备性的问题，不在扫描范围内。
3. **时钟/随机台账按两种形态识别**：内联调用名（`date`/`time`/`strtotime`/`mac_day`/`rand`/`mt_rand`/`uniqid`/`session`/`token`/`cookie` 等，常量 `TIME_RANDOM_CALLS`）与 **ThinkPHP 过滤器**（`{$x|date='Y-m-d',###}`、`{$x|mac_day}`，常量 `FILTER_NONDETERMINISTIC`）。
   两者都必须扫：真包实测内联 `date(`/`mac_day(` **一处也没有**，全部写成过滤器——只按调用名扫会得出“非确定来源 0 处”的假结论（本工具初版就踩了这个坑，现已修）。
   `|date='…',###` 的 `###` 表示“用数据里的时间戳”（由 DB 构造，记 DATA-1）；不带 `,###` 的 `|date='…'` 才是当前时间。
   若真包出现表外函数（例如 `now()`），脚本会漏记——S3-05 出数后请复核人抽查一次“含时间语义的名单是否完整”。
4. **`by="rnd"` 的识别只看属性文本**，包括 `{maccms:… by="rnd"}` 与 `by='rnd'`；若模板里写成 `by=$var` 之类动态值，不计入 DATA-2。
5. **不做模板渲染，也不判断“某标签是否被实现”。** 这是 T3 的事（页面级对比）。

## 5. BLOCKED：真包扫描与对照环境还缺什么

| 缺什么 | 影响 | 谁提供 | 现状证据 |
| --- | --- | --- | --- |
| 真模板包（274 文件）入库 | C-9 与 PRE-5 都**无真包可跑** | 后端开发（S3-01，C-1～C-5） | **已解除**：S3-01 已在分支 `agent/agent/appk-2-f59b49bb7f70` 提交 `8fca22d`（C-1～C-4）与 `3f01ff0`（C-5）入库，`git ls-tree -r` 可见 276 个 `third_party/` 路径 |
| 模板来源提交 `4c418c2…` 的本地对象 | 测试线无法直接从源提交取字节 | 后端开发（S3-01 负责导入并留取证） | **已绕过（不是已解除）**：本机仍无 `4c418c2…` 对象；S3-03 改用 `git archive agent/agent/appk-2-f59b49bb7f70 third_party` 从**S3-01 的入库提交**取字节，保证与入库件同一份。溯源仍应回到源提交，见 5.1 节 |
| 对照环境（PHP 7.3 + Java 两侧站点根）与执行权限 | 无法跑 PRE-2 的 `.compare/APPK-2/php-site`、`java-site` | 运营打包（部署）+ 架构师（S3-04 定边界） | 本机 PHP 7.3.33 存在但 S3-03 明确不允许连接环境；对照环境的地址/权限由 S3-04 与智能调度给 |
| 架构师对扫描口径与归属表的确认 | PRE-5 第 6、7 节无法定稿 | 架构师（S3-04） | 本文件第 3、4 节列出了待确认项 |

按“缺料 → BLOCKED，不猜、不用模拟顶替”的规定，S3-03 的结论是：**测试资产已交付；真包上的 C-9 已返回 0；PRE-5 的机器数据已出；PRE-5 定稿仍 BLOCKED（等架构师补说明与归属）**。
两项原本卡在 S3-01 的缺料（真包、取字节路径）已解决，剩下两项卡在 S3-04（扫描口径确认与归属）与部署/智能调度（对照环境、执行权限）。

## 5.1 真包 C-9 校验结果（返回码 0）

| 项 | 值 |
| --- | --- |
| 取字节方式 | `git archive agent/agent/appk-2-f59b49bb7f70 third_party \| tar -x`（**只读**，不 checkout、不改工作区） |
| 被校对象 | `third_party/maccms-template-v2020.1000.1035/template`（来自 S3-01 提交 `8fca22d` + `3f01ff0`） |
| 结果 | `verify_template_package.py` **返回码 0**：“OK（路径、字节数、blob SHA-1、目录边界全部符合清单）” |
| 覆盖 | 274 个文件逐个；`git hash-object --no-filters` 交叉验证也是 0 |
| 副产物 | 本机 `core.autocrlf=true`，已按 `--no-filters` 跳过过滤器（否则 RISK-T1 会自相矛盾，见第 2 节） |

**一句限制，别误读**：这证明“S3-01 入库的那份字节 = PRD 附表 E.3”，**不等于**“字节 = 源提交 `4c418c2…`”。
本机没有源提交对象，那一步溯源仍要 S3-01 或持有源仓库者补证。

## 5.2 真包 PRE-5 机器数据（27 项检查）

| 检查 | 结果 |
| --- | --- |
| E.2 总数/分目录/`.html` 计数、目录集合、无 `default_wap/ads` | 全部 OK（274 / 147 / 127 / 92 / 76） |
| E.3 清单比对 | OK（274 行，0 缺、0 多、0 字节差异、0 SHA-1 差异） |
| RISK-T1 CRLF | OK：**文本**文件含 CRLF 的 0 个（53 个图片文件的 `0D 0A` 不算；判断依据是字节/blob SHA-1 与 E.3 一致） |
| E.4-EF-1 js/css/ads 不含 `{maccms` | OK（106 个文件命中 0） |
| E.4-EF-2 已知 6 文件齐全 / 无新增 PHP 文件 | 均 OK |
| E.4-EF-3 标签计数（`default_pc`/`default_wap`） | 均 OK |
| E.4-EF-5 其他语法计数 | 均 OK（include 234、if 210、volist 35、else 35、elseif 18、empty 10、foreach 4、switch 1） |
| **F.5-规则2-归属** | **`UNATTRIBUTED`：22 处**，全部是 `|mac_day` 过滤器，等架构师归属 |

### 两个曾误报为 MISMATCH 的口径问题（已查清，不是数据错误）

修工具时发现附表 E.4 的**取证命令**本身会丢信息。列在这里，供架构师在 S3-04 确认扫描口径时一并定：

| 现象 | 根因（已实测确认） | 处理 |
| --- | --- | --- |
| EF-3 期望 `link` 6，而按完整标签名只得 `link` 1 + `link_gbook` 1 + `link_map_{baidu,google,rss,vod}` 4 | 附表 E.4 的取证命令是 `grep -rhoE '\{maccms:[a-z]+'`，**只取小写字母**，`link_gbook` 被截成 `link`。按该命令复跑真包，得到的正是 PRD 那组数字 | 扫描器同时输出两套统计：`maccms_tags`（完整标签名，实现要用）与 `maccms_tags_prd_prefix`（复刻 PRD 口径，用于核对附表 E）。EF-3 比对用后者 |
| EF-5 期望 `{else` 35，而 `grep -rhoE '\{else\b'` 得 77 | 真包里 `{else` 共三种写法：`{else/}` 42、`{else}` 23、`{else /}` 12。**35 = `{else}` 23 + `{else /}` 12**（ThinkPHP 块 else 的两种写法），不含 `{elseif`，也不含无空格的 `{else/}` | 扫描器按“块 else”口径计 35；无空格 `{else/}` 不计入该数值，但仍**逐处登记**到 JSON 的 `think_syntax_other`，避免“少算了却不说” |

两处都是**附表 E.4 取证命令的口径**问题，不是模板包数据错误，也不是实现缺陷，因此**不需要**提缺陷单，也不该因此让 PRD 版本 +1——除非架构师认为 APP 文档需要改成更精确的口径。

## 5.3 PRE-5 定稿还缺什么

| PRE-5 要求 | 真包数据下的现状 | 谁补 |
| --- | --- | --- |
| 168 个 `.html` 各占一行 | 已满足（第 8 节 168 行） | — |
| 每处 PHP 代码有实现说明 | **缺**：8 处（`art/detail.html:33`、`public/jump.html:18`×2/目录、`vod/copyright.html:19`/目录、`vod/plot.html:27`），说明列空 | 架构师（S3-04） |
| 时钟/随机/会话/token 逐处归属 | **缺 22 处** `|mac_day`（`default_pc/html/user/*` 为主）。另有 58 处 `|date='…',###` 已按“用数据时间戳”记 DATA-1（依赖 DB 构数据，不是当前时钟） | 架构师（S3-04） |
| 没有“归属未定”行 | **不满足**（就是上面 22 处） | 架构师 |

**一条要提前说清的风险**：真包里 `{$maccms.date}` 出现 **0 次**，`by="rnd"` 也是 **0 次**。
也就是说，动工时如果只按 PRD 举例的 `$maccms.date` → EX-1、`by="rnd"` → DATA-2 去建环境变量，
**覆盖不到真包里的时间来源**；真正的来源是 `|date` / `|mac_day` 过滤器 + 会员页的时间字段。
这条应在 S3-04 定归属时一并处理，否则“EX-1 已覆盖”会是假覆盖。

## 6. 复核人可以照着做的复现步骤

```bash
# 1) 自测：23 项应全部 PASS，返回码 0
python tests/compare/APPK-2/selftest_offline.py

# 2) 清单副本与 PRD 附表 E.3 是否逐行一致（PRD 附表 E 需先检出到可读路径）
python tests/compare/APPK-2/build_template_manifest.py \
    --prd <附表 E 文件> --check docs/test/APPK-2/fixtures/template-package-manifest.tsv

# 3) S3-01 入库后，对真包跑 C-9（返回 0 = 一致；2 = 不符，逐条看输出）
python tests/compare/APPK-2/verify_template_package.py \
    --target third_party/maccms-template-v2020.1000.1035 \
    --manifest docs/test/APPK-2/fixtures/template-package-manifest.tsv \
    --prd <附表 E 文件>

# 4) 出 PRE-5 数据（返回 2 是正常的：还有“归属未定”行要架构师填）
python tests/compare/APPK-2/scan_templates.py \
    --package third_party/maccms-template-v2020.1000.1035/template \
    --manifest docs/test/APPK-2/fixtures/template-package-manifest.tsv \
    --out-dir .compare/APPK-2/scan-out \
    --markdown .compare/APPK-2/scan-out/template-scan-draft.md
```

`.compare/` 由技术设计 C-6 加入 `.gitignore`（不在本交付里改 `.gitignore`：C-6 不是 S3-03 的条目，
现有 `.gitignore` 已忽略 `.compare/` 之外的运行产物；若 C-6 尚未落地，跑完请自行删除 `.compare/`）。

## 7. 不做什么（防止越界）

* 不修改 `tests/api/**`（接口用例与脚本归接口用例阶段，派工说明明确禁止）。
* 不修改 PRD、技术设计、API 契约、已通过的 T1 用例（`docs/test/APPK-2/t1-cases.md` 一行未动）。
* 不导入模板包（S3-01 的活），不改 `.gitattributes`/`.gitignore`（C-5/C-6 的活），不动 `deploy` 分支。
* 不因为“工具自测通过”就写 AC-1 通过——AC-1 的证据必须是真包上的返回码 0。
