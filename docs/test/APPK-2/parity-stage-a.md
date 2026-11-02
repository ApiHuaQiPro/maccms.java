# APPK-2 T-FR12 阶段 A：附表 C 判定台账与缺口状态

| 项 | 内容 |
| --- | --- |
| Issue | APPK-10（APPK-2 子单 5：对照验收与测试收尾） |
| 阶段 | T-FR12 阶段 A（**只写 / 只读**：不连数据库、不发请求、不跑被测页面） |
| 追溯 | PRD `AC-14`（阶段 A 完成条件）、`AC-19`（阶段 B，本文件不含其判定） |
| 分母 | 附表 C 登记簿 **379 行** = 376 个真实功能行 + 3 个无 public 方法的类占位行 |
| 上游 PRD | `docs/prd/APPK-2.md` @ `9dd36a73febe8bac4c5d02b2638dd57c3e642a49`（v10） |
| 附表 C 副本 | `docs/prd/APPK-2/C-parity-registry.md`（从 PRD 分支原样取入，作为唯一分母来源） |
| 台账生成器 | `tests/compare/APPK-2/gen_parity_stage_a.py` |
| 379 行台账（生成产物） | `docs/test/APPK-2/parity-stage-a-rows.md` |
| 技术设计 | `docs/design/APPK-2/tech-design.md` @ `9671aae615450647d75647eb6a0e1c549c99001e`（v15，G1 增量 PASS） |
| 本文件结论 | **阶段 A 未完成（BLOCKED）**：台账已机械生成且 379 行齐全，但 376 行的四值判定**不能**由本轮给出，原因见 §3 |

## 0. 本文件做了什么、没做什么（不虚增声明）

本文件提供的是阶段 A 可机械验证的那一层：

1. **分母与行号已锁定**：从附表 C 表格机械读取 379 行（R-001～R-379），行数与 PRD 的
   「379 = 376 + 3」一致；脚本内置断言，行数不等于 379 直接返回 2。
2. **旧版方法存在性已核对**：376 个真实功能行中，373 个（index 143 + api 20 + admin 208 + 2 个
   占位复核）逐行在 `php/application/{index,api,admin}/controller/*.php` 里找到了对应的
   `public function` 并记录了源码行号；安装向导 5 行对应 `install/controller/Index.php` 的
   `index($step)` 单方法按步骤拆分（附表 C 第 4 条规则）。**找不到方法的行数 = 0**。
3. **台账逐行输出**（生成产物：`docs/test/APPK-2/parity-stage-a-rows.md`，见 §2 的生成命令）：每行带 `旧源码` 列
   （文件:行号）与 `台账状态=needs-review`。

本文件**没有**做、也**不能**在本轮做的，是 AC-14 真正要求的四值判定
（`✅已对齐` / `旧版无功能` / `排除-<编号>` / `缺口`）。理由见 §3：`✅已对齐` 的定义是
「附 Java 类/方法与测试」——那是**行为一致性**的证据，本轮既没有 Java 侧逐行的实现与测试映射，
也没有任何执行结果。把「PHP 源码里有这个方法」写成 `✅已对齐`，就是用源码存在性冒充行为等价，
属于 AC-14 明确禁止的虚增，故本文件不写。

**因此本文件不宣称 AC-14 通过，也不宣称 AC-19 有任何进展。**

## 1. 台账总览

| 端 | 登记簿行数 | 占位行（预填「旧版无功能」） | 待判定（真实功能行） | 旧源码方法已核实 | 已给四值判定 |
| --- | --- | --- | --- | --- | --- |
| index（前台） | 143 | 0 | 143 | 143 | 0 |
| api | 21 | 1（R-144 `api/Base.php`） | 20 | 20 | 0 |
| admin（后台） | 210 | 2（R-294 `admin/Safety.php`、R-334 `admin/Update.php`） | 208 | 208 | 0 |
| install（安装向导） | 5 | 0 | 5 | 5（`index($step)` 拆行） | 0 |
| **合计** | **379** | **3** | **376** | **376** | **0** |

> 3 个占位行（R-144、R-294、R-334）在本轮按 AC-14「只复核源码证据」的要求复核：
> `php/application/api/controller/Base.php`、`…/admin/controller/Safety.php`、
> `…/admin/controller/Update.php` 三个文件均无 `public function`，与预填判定一致。

## 2. 复现命令（只读，工作目录 = 仓库根）

```bash
# 生成台账（stdout），行数/方法核对失败会返回 2
python tests/compare/APPK-2/gen_parity_stage_a.py

# 落盘（本仓库已提交的生成产物就是这条命令的输出）
python tests/compare/APPK-2/gen_parity_stage_a.py --out docs/test/APPK-2/parity-stage-a-rows.md
```

复核「生成产物与源码同步」（应无差异）：

```bash
python tests/compare/APPK-2/gen_parity_stage_a.py --out /tmp/rows.md \
  && git diff --exit-code --no-index docs/test/APPK-2/parity-stage-a-rows.md /tmp/rows.md
```

独立复核源码口径（与附表 C 第 2 条提取规则一致，剔除 `__construct`）：

```bash
for d in index api admin; do
  echo "$d: $(grep -hcE '^\s*public\s+function\s+\w+' php/application/$d/controller/*.php | paste -sd+ | bc)"
done
# 实测：index 143、api 20、admin 208（另有 Base/Safety/Update 三个空类）
```

## 3. 为什么 376 行的四值判定在本轮无法给出（BLOCKED 依据）

AC-14 允许的四个取值里，三个都需要本轮拿不到的材料：

| 取值 | AC-14/PRD 的定义要求 | 本轮为什么拿不到 |
| --- | --- | --- |
| `✅已对齐` | **附 Java 类/方法与测试名** | 需要逐行把「旧控制器方法」映射到「Java 类/方法」**并**指出覆盖它的测试。后台侧 `admin/application/AdminPermissions.java` 的 `CONTROLLER_BATCH`/`ACTION_BATCH` 能给出「由哪个批次（B12～B29）实现」，但批次号不是「Java 类/方法与测试名」，且前台 143 行分散在 `site/web/*` 的多个控制器与渲染器中，没有与登记簿逐行对齐的现成映射。凭空填类名 = 编造证据。 |
| `旧版无功能` | 附 PHP 源码行证据（证明该方法不产生功能输出） | 只对 3 个占位行成立（已复核）。其余 376 行在源码里都有实体 `public function`，要证明「有方法但无功能」需逐方法读源码并判定，属阶段 A 的实际工作量，不是本轮能机械产出的部分。 |
| `排除-<OP-n 或 D 编号>` | 该 OP- 须已「移出本期」，或 D 编号是已确认的历史决定 | 需要逐个 OP-/D 编号的关闭状态与适用范围（PRD §10/§11 与决策评论），并逐行判定该行是否落在其范围内。本轮没有做这项比对，不能把未核对的 OP- 拿来当排除依据。 |
| `缺口` | 必须同时在缺口登记簿（PRD §9.1）建 GAP- | `docs/test/APPK-2/gap-register.md` 尚未存在，且建 GAP- 的 Owner 是产品经理（PRD §9.1 的登记簿 Owner），不是测试。测试单方面写「缺口」而不建 GAP-，会直接违反 AC-14「每个缺口行有 GAP-」。 |

**结论：** 阶段 A 的剩余工作是 376 行的逐行人工判定（需要 Java 实现/测试映射、OP-/D 关闭状态表、
以及 GAP- 登记簿配合），超出本轮运行预算。台账已把这件事变成「每行只需填一列」的机械工作，
但填值本身必须有证据，本轮不代填。

## 4. 缺口 / 待补清单（交智能调度）

| 待补项 | 谁提供 | 用途 | 为什么必需 |
| --- | --- | --- | --- |
| 登记簿 → Java 类/方法与测试的逐行映射 | 后端开发（按批次）或架构师 | 填 `✅已对齐` 列 | AC-14 原文要求「附 Java 类/方法与测试名」 |
| OP-1～OP-16 与已确认 D 编号的「移出本期 / 历史决定」清单及其覆盖行 | 产品经理 + 智能调度 | 填 `排除-<编号>` 列 | 排除必须有已关闭的决策来源，不能自行推断 |
| `docs/test/APPK-2/gap-register.md`（缺口登记簿） | 产品经理（PRD §9.1 Owner） | 为每个 `缺口` 行建 GAP- | AC-14 要求缺口行必有 GAP- |
| 阶段 B 迁移项 Issue | 智能调度 | AC-19 判定对象 | AC-19 当前无可判定对象 |

## 5. 与 S3-22（对照执行器）的关系

S3-22（`C-64` `tests/compare/APPK-2/run_compare.py`）在 Issue 描述里写的是「依赖 S3-03 对照环境
（`7cf9cbc0…` 已放行）」。核对结果：

- 提交 `7cf9cbc085d79a8aac61b10190c6615005ab7b5e` 的实际内容是 **S3-01 模板包校验工具 +
  PRE-5 扫描工具**（`verify_template_package.py`、`scan_templates.py`、
  `build_template_manifest.py`、`make_synthetic_package.py`、`selftest_offline.py`），
  **不是** S3-03 的对照环境。
- S3-03 按技术设计 S3-03 行应交付 `C-6～C-10`、`C-62`（`data_snapshot.py`）、
  `C-63`（`assemble_site.py`）、`C-66`（`schema_snapshot.py`）。逐个检索全部远端分支：
  **`data_snapshot.py`、`assemble_site.py`、`schema_snapshot.py`、`run_compare.py` 在任何一个
  分支上都不存在**。

因此 S3-22 的上游（对照环境装配 + 快照恢复 + 结构快照）实际未交付，`run_compare.py` 无法按
附表 F 的 CMP-2（每单元恢复 + `CHECKSUM TABLE` 校验 + 双行恢复记录）实现，本轮不写它——
写一个没有快照恢复能力的 `run_compare.py` 会在 CMP-2 第一步就产不出合规报告，属于假交付。
**S3-22 判 BLOCKED，缺 S3-03 的实际交付。**

## 6. 自检

| 检查 | 依据 | 结果 |
| --- | --- | --- |
| 分母 379 行、376 待判定、3 占位 | 本文 §1；生成器内置断言 | 是 |
| 逐行旧源码方法已核实、缺失为 0 | 生成器返回 0，`PROBLEM` 行数 0 | 是 |
| 未把源码存在性写成 `✅已对齐` | 本文 §0、§3；台账全部为 `needs-review` | 是 |
| 未宣称 AC-14 通过 | 本文「本文件结论」、「§0」末句 | 是 |
| 未宣称 AC-19 有进展 | 本文「本文件结论」、§4 | 是 |
| 未执行任何环境动作 | 脚本不发 HTTP、不连库、不读凭据；本轮未启动被测服务 | 是 |
| 未改 `src/main`、未改 PRD/设计/契约 | 本提交文件清单（见提交信息） | 是 |
| S3-22 依赖结论有仓库证据 | 本文 §5：`7cf9cbc` 文件清单 + 四个文件名全分支检索为空 | 是 |
