# APPK-2 附表 C 对齐报告（S3-22 阶段 A）

- 用例：TC-F-PAR-001（376 行逐行判定，KPI-3a=0）、TC-F-PAR-002（缺口行必有 `GAP-`）
- 判定口径：`docs/test/APPK-2/parity-method.md`
- 取证工具：`tests/compare/APPK-2/parity_scan.py`（离线；不联网、不连库、不读凭据）
- 上游：PRD v9 `8fa362c`、技术设计 v8 `20490951`、附表 C 登记簿（PRD 分支）
- 结论：**评估 BLOCKED**（原因见第 5 节，不是「全部对齐」，也不是「有 N 个缺口」）

## 1. 已完成的取证（不依赖环境、可复现）

| 核对项 | 期望（登记簿） | 实测（扫描器） | 结果 |
|---|---|---|---|
| `index` 控制器文件 / 方法行 | 23 / 143 | 23 / 143 | 一致 |
| `api` 控制器文件 / 方法行 | 6 / 20 | 6 / 20 | 一致 |
| `admin` 控制器文件 / 方法行 | 39 / 208 | 39 / 208 | 一致 |
| 安装向导步骤行 | 5（I-1～I-5） | 1 个 public 方法按规则第 4 条拆 5 行 | 一致 |
| 真实功能行合计（分母） | 376 | **376** | 一致 |
| 无 public 方法的类 | 3（`api/Base`、`admin/Safety`、`admin/Update`） | 3，且就是注册簿预填的三行 | 一致 |
| 登记簿总行数 | 379 = 376 + 3 | 主干工作树无登记簿文件，**无法核对** | 待复核 |

分母层面 376 与登记簿完全一致，说明分母口径可复现，逐行判定有一个可靠的比对基准。

复现命令与自检：

```bash
PYTHONIOENCODING=utf-8 python tests/compare/APPK-2/parity_scan.py --self-test      # SELF-TEST PASSED（4 项）
PYTHONIOENCODING=utf-8 python tests/compare/APPK-2/parity_scan.py --json .compare/tmp/parity.json
```

## 2. 逐行候选承接位置（扫描器输出，**不是判定**）

| 证据等级 | 行数 | 分布 | 含义 |
|---|---|---|---|
| E1 | 45 | index 40、api 5 | 新版有与旧方法同名的处理器方法 |
| E2 | 101 | index 85、api 15、install 1 | 新版有同名包/控制器类整体承接 |
| E3 | 205 | admin 205 | 后台动作有实现批次登记（`AdminPermissions.batchOf`） |
| E4 | 1 | admin 1（`Collect::timing`） | 新版登记为不提供/旧版无此方法 |
| E5 | 20 | index 18、admin 2 | 新版无任何承接线索，**最可能是缺口** |
| 合计 | 372 | — | 加安装向导另 4 行（I-2～I-5）= 376 |

E5 的 20 行（逐行判定时的重点核对对象）：

```text
index Base.php   _empty
index Comment.php ajax, saveData, report, digg
index Gbook.php   ajax, report, saveData
index MyError.php _empty
index Plot.php    ajax_search, ajax_detail, rss
index Rss.php     baidu, google, so, sogou, bing, sm
admin Base.php    check_auth, _cache_clear
```

这些行**不能**凭 E5 直接写 `缺口`：`Base::_empty` / `MyError::_empty` 是框架错误页钩子，`Rss::*` 在批次表里属 B06，`Comment`/`Gbook` 的真实入口可能是别的路径。要写 `GAP-` 必须先在 PRD 分支上逐行读两处源码确认，并核对批次进度。

## 3. 未完成的判定（为什么不能现在下结论）

1. **登记簿不在本次工作树**。附表 C 在 PRD 分支 `feature/APPK-2-prd-php-migration`（`docs/prd/APPK-2/C-parity-registry.md`），当前分支没有这个文件。逐行判定的行号（R-001～R-379）与文件/方法列的对应关系必须对着登记簿落表，否则可能把「登记簿里根本没有的行」或「登记簿有但源码里改了名的行」判成对齐。
2. **E3 的 205 行依赖批次进度**。后台动作由 `AdminPermissions.batchOf` 承接，但 `batchOf` 返回的批次号里只有已合并批次的代码才存在；未合并批次（B25、B26、B24 等文档已在 `docs/architecture/`，但代码未合）现在仍是「该功能未在新系统中提供」的固定提示。判 `✅已对齐` 必须逐个确认该批次**已合并**，这需要 G2 的合入提交号作为依据——本批 B25/B26/B24 的合并状态在 Issue 里尚未有结论。
3. **测试名核对未做**。判定要求「Java 类 + 方法 + 测试名」三要素，测试名要能在仓库里 `grep` 到。阶段 A 的 376 行里 205 行的测试覆盖分散在后台各批次的测试类里，需要按批次归集后才能逐行引用。

## 4. 已可确定的口径结论（不依赖上述缺失材料）

- 分母 376 可复现，`KPI-3a` 的分母就是 376，不是 379（379 里 3 行是预填「旧版无功能」）。
- 扫描器自检 4 项全过，分母核对会阻断不一致（不会假通过）。
- 阶段 B（App 端未迁移项）本批不判定，报告不得计入。

## 5. 阻断清单

| 缺什么 | 谁提供 | 解除条件 |
|---|---|---|
| 附表 C 登记簿文件（`docs/prd/APPK-2/C-parity-registry.md`，PRD 分支 `feature/APPK-2-prd-php-migration`） | 产品经理-海外（PRD 分支维护方）；或由 智能调度 指定把该文件合入本测试分支 | 文件出现在本工作树，扫描器登记簿核对项从「不可核对」变为「一致」 |
| 后台各批次的合并状态（B25/B26/B24/B22 等）——决定 E3 的 205 行能否判 `✅已对齐` | 智能调度（G2 合入提交号与变更文件清单） | Issue 里能查到各批次的合入提交号 |
| 测试环境地址与版本探针 | 运营打包-海外（回传地址）、智能调度（写 G2.5 PASS 行） | T3 的前置条件，与本报告 T2 阶段无关，但阶段 A 的「页面级证据」需要它 |

前两项不齐 → 本报告维持 **评估 BLOCKED**；只有分母层面的取证结论可用（第 1、4 节）。
