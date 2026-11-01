# APPK-2 前台模板迁移 UI 设计 v1

| 字段 | 值 |
| --- | --- |
| 创建者 | @UI设计师-海外 |
| 版本 | v1 |
| 范围 | 只覆盖前台 PHP 模板迁移的视觉、页面状态、URL、响应式与可访问性边界；不扩展产品范围，不定技术实现 |
| PRD | `docs/prd/APPK-2.md` + A～F @ `8fa362c25b62b52ed5b16afd9eca7f6ccfcafb9f`（v9，G0 已确认） |
| OP-15 | `docs/design/APPK-2/tech-design.md` @ `ebd7aa60ae968229997221d3086b45ddb4fa67e1`（v2，已评审） |
| 源码基线 | `ApiHuaQiPro/maccms.java` @ `a57e9cb2b94d3e500bea7dfe2c579daee093d969`；deploy `release/APPK-2-php-template-migration` 同头 |
| 额外上游 | OP-16=a：桌面缺 41、手机缺 57 个页面本期不补；按 PHP“模板文件不存在”处理 |
| 稿件与截图 | `template-migration.html`；`shot-390.png`、`shot-768.png`、`shot-1280.png` |

## 1. 设计原则：保真迁移，不重新设计官方模板

1. **唯一视觉来源是官方包与 PHP 输出。** 使用 `v2020.1000.1035` 的 `default_pc`（147 文件）和 `default_wap`（127 文件），274 文件逐字节不动。模板的颜色、尺寸、DOM 顺序、响应式方式及交互沿用原包，不能把本稿示意线框当作新皮肤。
2. **HTML 稿件是状态/契约图，不是验收像素图。** 稿件中的“站点名称”“示例影片 A～D”只用于显示拓扑；正式验收禁止用示例文案替代合成库和 PHP 输出。
3. **不做新旧视觉折中。** 不把现有 JTE 的布局、组件、样式带入官方模板；也不因旧模板不符合现代审美而补 CSS、改交互或补缺失页面。
4. **验收优先级**：PRD BR-3/BR-7 的 PHP 对照 > 本设计状态契约 > 稿件线框。线框与 PHP 实际输出有任何不同，以 PHP 为准。

## 2. 页面清单与呈现来源

完整固定页面名及 N/E/P 取样以 PRD 附表 B.1.1 的 F-001～F-120 为唯一清单；动态模板名以 B.1.2 为准。下表给出 UI 交付索引，不新增、删除或重编号需求样本。

| 页面/模块 | 桌面来源与状态 | 手机来源与状态 | 主要设计项 |
| --- | --- | --- | --- |
| 首页 `index/index` | `default_pc/html/index/index.html`，有；N | `default_wap/html/index/index.html`，有；N | D-01、D-02 |
| 视频 `vod/*` | 固定 20 个模板名；19 有，仅 `vod/role` 缺；按 B.1.1 取 N/E/P | 19 有，`vod/role` 缺；每固定名 N | D-01、D-03～D-11 |
| 文章 `art/*` | 11 个固定名；10 有，`art/show` 缺 | 8 有；`index/show/type` 缺 | D-03～D-11 |
| 专题 `topic/*` | 6 个固定名；`detail/index` 有，其余 4 缺 | 同桌面 | D-01、D-11 |
| 演员 `actor/*` | 10 个固定名；`detail/index/rss/show` 有，其余 6 缺 | 全缺 | D-01、D-11 |
| 角色 `role/*` | 8 个固定名；`detail/index/show` 有，其余 5 缺 | 全缺 | D-01、D-11 |
| 网址 `website/*` | 10 个固定名；`detail/index` 有，其余 8 缺 | 全缺 | D-01、D-11 |
| 剧情 `plot/*` | 6 个固定名；`detail/index` 有，其余 4 缺 | `detail/index` 有，其余 4 缺 | D-01、D-11 |
| 评论/留言 | `comment/ajax`、`comment/index`、`gbook/index`、`gbook/report` 有；`gbook/ajax` 缺 | 同桌面 | D-03～D-10 |
| 地图/RSS | `map/index` 有；`rss/*` 7 个均有 | 同桌面 | D-01、D-04 |
| 会员中心 | F-063～F-090 共 28 个；22 有，`comment/gbook/order_info/portrait/ulog/unbind` 6 缺 | 同桌面 | D-03～D-11 |
| 搜索聚合、帮助、公告 | `search/index`、`help/index`、`note/index` 均缺 | 均缺 | D-11 |
| 公共提示 | `browser/close/jump` 有，`verify` 缺 | 同桌面 | D-05、D-07、D-11 |
| 动态名 | `label/rank`；`public/<page_404>`；16 个 `*_tpl` 覆盖点 | 按模板选择使用对应包 | D-01、D-11、D-13 |
| 内置视图 | PHP 程序内置 `index/index`、`public/jump`、`public/msg`，不在 KPI-1 分母；按附表 C 验收，不能误当官方模板改动 | 同左 | D-13 |

计数核对：桌面 79 有/41 缺，固定样本 188（N120+E41+P27）+ F-D1 2 + F-D2 2 + F-D3 32 = 224；手机 63 有/57 缺，页面样本 120，另有模板选择 12、语言键 3546。KPI-1 总分母 356，不在本设计重算或更改。

## 3. 模板路径与静态资源 URL

| 项 | 桌面 | 手机 |
| --- | --- | --- |
| 模板读取根 PATH-1 | `template/default_pc/html/` | `template/default_wap/html/` |
| `{$maccms.path_tpl}` PATH-2 | `/template/default_pc/` | `/template/default_wap/` |
| `MAC_PATH_TPL` PATH-3，只供缺失消息 | `/template/default_pc/html/` | `/template/default_wap/html/` |
| `{$maccms.path_ads}` PATH-4 | `/template/default_pc/ads/` | `/template/default_wap/ads/`；目录不存在，不创建 |

- CSS 示例必须为 `/template/default_pc/css/style.css`、`/template/default_wap/css/style.css`，禁止出现 `/html/css/`。JS、图片和广告同理。
- 同时保留模板对 `{$maccms.path}static/...` 的既有引用；不把这类 URL 错改为 `/template/`。
- 资源由 Java 还是 nginx 提供是 OP-T1 的技术决定，本设计只固定用户可见 URL，不替架构师选型。
- 手机 `ads/` 缺失不能靠新建空目录、复制桌面广告或提供默认图片“修好”。404 与 PHP 一致。

## 4. 六态与错误页规则

| 状态 | 用户可见契约 | 禁止做法 | 对应需求 |
| --- | --- | --- | --- |
| 正常 | 按所选模板、页面变量及标签输出；桌面与手机各自完整 | 混入 JTE 或另一模板包 | FR-1～FR-5、FR-8；AC-1～AC-6、AC-10 |
| 加载 | 仅原模板已有 Ajax 区域保留原加载行为；没有时不新增加载层 | 首屏统一骨架屏、全局 spinner、延迟动画 | FR-5；AC-5 |
| 空 | 标签循环无结果即不输出循环体；外框/标题/分页是否出现全按 PHP | 新增插画、“暂无数据”卡片、伪造推荐 | FR-3；AC-3、AC-5 |
| 错误 | 未支持语法/函数返回 500；页面按 PHP 对照；日志含模板名和行号 | 静默吞错、只渲染半页、用友好新错误页替代 | FR-2、FR-4；AC-2、AC-4 |
| 禁用 | 仅原表单已有提交行为可加防重复禁用；不改变可用性业务规则 | 新增全局按钮体系、改变表单字段或权限 | FR-5；AC-5 |
| 无权限 | `popedom` 提示/跳转；观看密码用 `*_pwd`；登录/找回错误不泄露账号存在性 | 显示内容详情、绕到 JTE、改会员组规则 | FR-5；AC-5；BR-4 |
| 模板缺失 | 同请求 PHP 的“模板文件不存在”输出；桌面 41、手机 57 个固定名只取 N | 回退桌面/手机/JTE、补模板、隐藏错误 | BR-12；AC-1、AC-5、AC-10、AC-20 |
| 站点关闭 | `public/close` + `site_close_tip` | 新维护页或自动跳首页 | FR-5；AC-5 |

公共跳转、404 配置页、观看密码和 Ajax 片段不是可自由发挥的通用组件；其 Content-Type、状态码、Location 和 HTML 都按附表 F 对照。

## 5. 响应式与可访问性边界

1. **桌面/手机不是 CSS 断点切换。** 先按旧 `mob_status`、UA、Host、站群规则选 `default_pc` 或 `default_wap`，再按该模板原 CSS 呈现。对照环境仅 `mob_status=2`。
2. **不强制官方模板响应式化。** 桌面 1000px 固定布局、手机独立布局均保留；不得为 390/768/1280 截图“好看”而改官方文件。
3. **本设计稿三档**：390/768/1280 均无横向溢出，自动对比度 0 违规，详见 `self-review.md`。这只证明设计说明稿，不冒充实际 PHP 页面的 AA 认证。
4. **实现审查边界**：不全局 `outline:none`；图标链接保留文字或 aria 名称；表单 label、错误关联及可见焦点按原 DOM 可修正程度记录。官方模板若存在对比度、40px 点击区或语义问题，记录为现状差异，不在 BR-11 下擅自修改；需要修复必须另走需求确认。
5. **动效**：沿用官方 JS；不新增页面过渡、滚动动画、悬停位移或自动播放。键盘焦点顺序遵循原 DOM。

## 6. JTE 下线边界

| 范围 | 决定 |
| --- | --- |
| `src/main/jte/site/**` 全树 | 前台全部下线，不留后备。包括 `layout/`、`pages/`、`member/`、`ajax/`、`fragments/`、`interaction/`、`components/`、`player/` 及播放器相关前台视图；不能再作为缺页、错误或低版本回退 |
| `src/main/jte-admin/**` | 保留；OP-2 明确后台不做像素级模板迁移。后台只把模板管理对象改为 PHP 模板文件，标签向导输出 MacCMS 语法 |
| `src/main/jte-install/**` | 保留；不属于前台模板下线 |
| 官方模板缺失时 | 不保留任何 JTE 页面作兜底；直接按 PHP 模板缺失输出 |
| 前台 JTE 在线编辑 D4 | 作废；后台模板管理只处理白名单 PHP 模板/广告文件，不生成 JTE 片段 |

具体类、测试和资源删除顺序由完整技术设计决定，本设计只固定用户可见边界，不写实现方案。

## 7. 给前端与验收的实现说明

- 先验证首页与 vod 主路径，再按 PRD 顺序覆盖文章/专题/演员/角色/网址/剧情、评论留言、会员、地图/RSS/公共页；不根据“页面好看程度”调整范围。
- 不以浏览器截图替代 AC-5/AC-10。每个样本仍比较状态码、完整 Content-Type、3xx Location、响应字节；只允许 EX-1/EX-2 占位替换，禁止空白折叠。
- 缺页必须落入模板缺失路径，不得让通用 404、空页或旧 JTE 抢先处理。
- 资源回归至少断言正确 URL、错误 `/html/css/` 前缀不存在、手机不生成 `ads/`。
- 静态生成与动态请求使用同一模板呈现；输出路径与文件字节按 K-01～K-12 对照。失败页入失败列表，不中断整批。
- 不修改 Java、`php/`、数据库或官方模板来满足本设计；发现必须改模板才能呈现时停止并新开 OP。

## 8. 验收基线

| 项 | 基线 |
| --- | --- |
| 视觉/页面 | PHP 2023.1000.3051 @ PHP 7.3.33 + 同一官方模板包、同一合成数据；附表 F 为唯一比较规则 |
| 页面覆盖 | 附表 B.1.1 全 120 固定名与 B.1.2 动态名；桌面 224 行样本清单；手机 120 页 + 12 选择 + 3546 语言键 |
| 缺失页面 | OP-16=a；只取 N；PHP 模板缺失输出，不补页 |
| 静态资源 | PATH-2/PATH-4；无 `/html/css/`；手机 `ads/` 不创建 |
| 状态 | 本文件 §4；空、错、无权限均不另造 UI |
| 响应式/AA | 官方模板原样；本说明稿三档截图与自动 AA 仅作设计文档质量证据 |
| 下线 | `src/main/jte/site/**` 无回退；admin/install JTE 不动 |

本文件是设计交付，不代表实现或验收已经完成。
