# UI 迁移清单：controlX2-main-zh → i18n 体系

> 生成日期：2026-10-30
> 源：`D:\Programming\controlX2-main-zh`（当前 HEAD = wip 4272493f；zh = d30e81c1；两分支分叉于 576e2563）
> 目标：`D:\Programming\controlX2-i18n`（main 工作树）、`D:\Programming\controlX2-i18n-dev`（dev 工作树）、`D:\Programming\controlX2-i18n-lab`（assets 树，工具与 TM 在 lab 的 `i18n/` 下）
> 范围：**只迁移 UI 行为/布局/视觉代码**。不迁移汉化本身（values/values-zh 资源、TM 行），不带入 wip 的 i18n 落盘资源。
> 本文所有「i18n 树现状」均为 2026-10-30 对两棵树实际 grep 核实的结果，非从 main-zh 文档推断。

---

## 一、通用规则（i18n 侧硬约束，所有迁移项适用）

1. **新增 UI 文案一律走字符串资源**：`mobile`/`wear` 各自 `values/strings.xml` 放英文原文，中文进 TM 登记（主 TM 与 dev TM 分别登记）；禁止把硬编码英文/中文带进 i18n 树。
2. **渲染上下文**：Composable 内用 `stringResource(R.string.x)`；Service/Receiver/Manager/普通辅助函数用 `context.getString(R.string.x)`；**禁止在 lambda / try-catch 内直接调 `stringResource`**（`LocalContext.current` 需先收为局部 val 再用）。
3. **资源改动后验证**：manifest/资源改动必须跑 `:mobile:processDebugResources`（和 wear 对应任务）确认链接无误。
4. **跨模块引用**：pumpcomm/shared 等非 res 属主模块引用资源时用**全限定 R**（`com.jwoglom.controlx2.R.string.x`），并确认该模块对 mobile 资源的依赖方向合法；不合法的改为参数注入。
5. **FeatureFlag 新增一律英文 slug**（如 `cgm_chart_visible`），不写旧中文 slug 的状态迁移（main-zh 已实测 3 个中文 slug defaultValue 全 true，切换代价可接受）。
6. **一行一事**：每个迁移项独立 commit，commit message 用英文（i18n 树纪律），不夹带无关改动。
7. 同步要求：同一改动如需 main/dev 两树都落地，分别提交并在 lab 的 TM 双侧登记，避免 drift。

---

## 二、逐项迁移清单

### 总表

| # | 改动 | main-zh 证据 | i18n main 树现状 | i18n dev 树现状 | 处置 | 依赖 | 风险 |
|---|------|-------------|-----------------|-----------------|------|------|------|
| 1 | 首页三卡片功能开关（CGM 图表/传感器卡/历史同步条）+ 全局状态实时响应 + 同步完成隐藏不占位 | 提交 f869d492/6335c01c/6049ec74；`shared/FeatureFlag.kt` 4 枚举；Dashboard.kt L93/229/243 | **无**：FeatureFlag.kt 仅 `BTHostSwitch`（上游原样）；Dashboard 无开关门控 | 同 main | **可直接移植** | 无 | 低 |
| 2 | 可拖动 FAB（范围限制、位置持久化、`fabEnabled` 开关） | 提交 0deaf602→6335c01c→23cea6d3；Landing.kt L568-600；Prefs.fabOffsetX/Y | **无**：仅上游 ExtendedFloatingActionButton（L496），Prefs 无 fab* | 同 main（FAB 在 L510） | **可直接移植** | 无 | 低 |
| 3 | 「功能开关」入口从 Debug 上移为设置页一级入口 | 提交 da329e65；Settings.kt L193 | **无**：入口仍在 Debug.kt L1036（`navigateToFeatureFlags`） | 同 main（Debug.kt L1040） | **可直接移植** | 无 | 低 |
| 4 | 导航栏/泵图标颜色：`pump_secondary.xml` + 图标着色引用 | 提交 da329e65；drawable 存在 | **无**：drawable 不存在；AppSetup.kt 无 tint 相关代码 | 同 main | **可直接移植** | 无 | 低 |
| 5 | Landing 两处 BackHandler（非首页 tab 按返回回 Dashboard；bottom sheet 展开时返回先收起 sheet） | 工作记录-2026-09-28；Landing.kt L157/179 | **无**：全 mobile 模块 grep `BackHandler` 零命中 | 同 main（零命中） | **可直接移植** | 无 | 低 |
| 6 | 调试页「泵消息小弹窗」开关（CommService showToast 按 Prefs 门控） | 汉化修改清单 §三；Debug.kt L960-966；Prefs.pumpToastsEnabled | **无**：Prefs 仅 `qualifyingEventToastsEnabled`（上游）；CommService.showToast 无门控 | 同 main | **可直接移植** | 无 | 低 |
| 7 | 调试页「自动获取历史记录」开关（zh 增项） | 提交 2c490baf；Debug.kt L579-592 | **无**：Debug.kt grep「自动获取/autoFetch」零命中 | 未核实（同 main 概率高） | **可直接移植** | 无 | 低 |
| 8 | tab 状态保存（`rememberSaveable` 记住选中 tab） | 提交 2c490baf；Landing.kt 用 `rememberSaveable` | **无**：Landing.kt 无 `rememberSaveable` | 未核实 | **可直接移植** | 无 | 低 |
| 9 | 控制页激活态文字加粗（运动/睡眠/临基/挂起行 FontWeight.Bold） | 汉化修改清单 §二；Actions.kt L242/412/498/586 | **无**：Actions.kt 无 `FontWeight.Bold` | 同 main | **可直接移植** | 无 | 低 |
| 10 | TIR 显示（24h、70–180 mg/dL）+ 删除 COB 半成品 stub | 汉化修改清单 §二；TherapyMetricsCard.kt L87/128/148 | **半成品**：`showTIR: Boolean = false` 参数存在，但整段 TIR/COB 计算被注释（L149-180）；zh 版是上游 stub 的完成版 | **无**：dev 树 TherapyMetricsCard 无 TIR 痕迹 | **已完成**（〔2026-11-03 复核〕pumppan 已接入 TIR：`TherapyMetricsCard :: TherapyMetricsCardFromDataStore`） | 历史库 CGM 数据可读 | 中 |
| 11 | 调试窗口底色统一、CGM 图表副标题修改、功能开关文字修改、睡眠计划空格删除、AppSetup 文案微调 | 提交 23cea6d3/6049ec74/6335c01c/da329e65 | 上游原样 | 上游原样 | **不建议迁移**（见 §2.11） | — | — |
| 12 | 电池图标预加载 `preloadBatteryIcons()` | 提交 517ebd3d；工作记录-2026-09-26 §2 | 上游原样（无预加载） | 上游原样 | **不建议迁移**（上游评审明确反对，见 §2.12） | — | — |
| 13 | 电池充电图标 6 矢量图镂空方案（替代上游 ⚡ 文本） | 提交 a65d0261/e0531509；bug-汉化引入的UI回归.md Bug 1 | 上游 ⚡ 8sp 原样（HorizBatteryIcon.kt L49-67） | 同 main | **已改为本地采用**（〔2026-11-03 复核〕pumppan 已实现挖空矢量，`ce4db28`；原「不迁移」裁定作废） | — | — |
| 14 | Actions.kt 72dp 固定行高 ×2、Color.Red 红字 ×2 | 汉化修改清单 §二；bug 文档 Bug 2/3 | 上游原样：无 height(72)、无 zh 红字（仅上游既有 L260 alpha 底色） | 同 main（L262） | **不迁移**（回归项，见第三节） | — | — |
| 15 | U1 控件库 / U2 Settings 迁移 / 组件目录规范化（wip 快照 aded91b3） | wip 提交；components/controls/ 7 文件、29 组件归位 | **无**：无 controls/ 目录；Settings 仍上游 ListItem 风格 | 同 main | **不继承代码，设计重做**（见第四节） | 见第四节 | 中 |
| 16 | PR #162「Back 按钮统一」（已 merged 上游） | 工作记录-2026-09-24 §1.5 | 已随上游包含（细节未逐点核实） | 已随上游包含 | **已存在，无需动作** | — | — |

> 说明：「同 main」表示已对该树 grep 核实结果一致；标「未核实」处未单独跑 grep，迁移前需补核。

### 2.1 首页三卡片功能开关（可直接移植）

- **源实现**：`shared/FeatureFlag.kt` 增 3 枚举（CgmChartVisible/SensorInfoCardVisible/HistoryLogSyncBarVisible）+ 3 个全局 `mutableStateOf` + `initState(context)`；Dashboard.kt 用全局状态门控三个 `item{}`；HistoryLogSyncProgressBar 完成后隐藏不占位。
- **移植要点**：slug 用英文（`cgm_chart_visible` / `sensor_info_card_visible` / `history_log_sync_bar_visible`）；FeatureFlags 页的展示名走 strings.xml + TM 登记；`initState` 调用点在 main-zh 的 MainActivity（未核实 i18n 树对应初始化位置，迁移时找 `onCreate`/首组合点）。
- **验证**：编译 + 开关切换后首页即时增删卡片 + 杀进程重启状态保持。

### 2.2 可拖动 FAB（可直接移植）

- **源实现**：Landing.kt L568-600（offset 持久化、只能向左/向上拖、顶部限制）；Prefs 增 `fabEnabled/fabOffsetX/fabOffsetY`。
- **移植要点**：i18n main 树 FAB 在 Landing.kt L496（ExtendedFloatingActionButton），dev 在 L510；外层包 `onGloballyPositioned` + `pointerInput` 拖动逻辑照抄即可；无新增文案（设置项文案若有则走资源）。
- **风险**：低；纯加法。

### 2.3 功能开关入口上移（可直接移植）

- **源实现**：Settings.kt 加 NavigationRow「功能开关」→ `navigateToFeatureFlags()`；Debug.kt 删原入口（-19 行）。
- **移植要点**：i18n 树入口在 Debug.kt L1036（main）/L1040（dev），Settings 签名里 main-zh 加了 `navigateToFeatureFlags` 参数——i18n main 树 Settings.kt 当前无此参数（grep 仅见 BTHostSwitch 用例），需同步改 Settings 调用处（Landing.kt L444 已有该参数的传递模式可参照）。
- **文案**：「功能开关」英文原文进 strings.xml + TM。

### 2.4 导航栏/泵图标颜色（可直接移植）

- **源实现**：新增 `mobile/src/main/res/drawable/pump_secondary.xml`（1608B，30 行）；Landing.kt 导航栏图标 tint 逻辑（da329e65，+10 行）。
- **移植要点**：纯 drawable + tint 引用，无文案；资源改动后跑 `processDebugResources`。

### 2.5 Landing BackHandler ×2（可直接移植）

- **源实现**：L157（`selectedItem != DASHBOARD` 时返回键回 Dashboard）、L179（sheet 展开时返回先 collapse）。
- **移植要点**：两处均为纯加法；注意 i18n 树 Landing.kt 行号不同（main 树 Landing 约比 zh 短），按符号（`selectedItem`、`bottomSheetState`）定位而非行号。
- **风险**：低；与上游未来导航改动冲突面小。

### 2.6 泵消息小弹窗开关（可直接移植）

- **源实现**：Prefs 增 `pumpToastsEnabled()`；Debug.kt L960-966 开关行；CommService.showToast 增加 Prefs 判断。
- **移植要点**：CommService 是 Service 上下文，Toast 文案用 `this.getString(R.string.x)`（i18n main 树已有 `comm_ctrl_x2_service_starting` 等先例，CommService.kt L563/623）；开关行文案进 strings + TM。

### 2.7 自动获取历史记录开关（可直接移植）

- **源实现**：Debug.kt L579-592 开关行 + 启停 Toast；MainActivity 侧 +8 行（2c490baf，自动拉取逻辑）。
- **移植要点**：MainActivity 部分属逻辑层，随 UI 开关一起搬；文案走资源。dev 树未核实，迁移前补 grep。

### 2.8 tab 状态保存（可直接移植）

- **源实现**：Landing.kt 用 `rememberSaveable` 持久化 `selectedItem`（2c490baf，+6 行）。
- **移植要点**：i18n main 树 `selectedItem` 是普通状态（L189 等多处赋值），改声明即可；纯加法。

### 2.9 控制页激活态加粗（可直接移植）

- **源实现**：Actions.kt 四行 `fontWeight = if (<激活条件>) FontWeight.Bold else FontWeight.Normal`（挂起 L242、运动 L412、睡眠 L498、临基 L586）。
- **移植要点**：i18n 树 Actions.kt 无 `FontWeight.Bold`，按符号找对应行（`basalStatus`、`controlIQMode`、`tempRateActive`）逐行加；无文案改动。
- **注意**：只搬加粗，**不搬** 72dp 行高与红字（见第三节）。

### 2.10 TIR 显示（需适配）

- **现状差异**：i18n main 树 TherapyMetricsCard.kt L138-143 有 `TherapyMetricsCardFromDataStore(showTIR: Boolean = false)` 签名，但 TIR 计算整段注释（L149-174），COB 同样注释——即上游留了半成品 stub；zh 版把它做完了（`label = "TIR"`、L148 `it in 70f..180f` 实算）。dev 树该文件无 TIR 痕迹。
- **适配点**：
  1. main 树：取消注释段 vs 直接替换为 zh 实现——建议**对照 zh 实现重写**，因为注释段引用的类型（DexcomG6/G7/CgmDataGxHistoryLog）与 zh 版数据源口径可能不同（zh 用 `readings.count { it in 70f..180f }`，数据源未核实是 DataStore 还是历史库，迁移前先读 zh 文件 L120-160 确认）。
  2. dev 树：无 stub，需整体加入卡片行；dev 的 TherapyMetricsCard 结构未核实，先读文件再定插入点。
  3. 「TIR」标签文案进 strings + TM（双 TM）。
  4. 是否默认开启：zh 版直接显示；i18n 侧建议挂 `showTIR` 参数默认 true 或接功能开关（与 #1 同机制）。
- **风险**：中（数据口径未核实；medical 数据展示需谨慎）。

### 2.11 文案/底色微调类（不建议迁移）

调试窗口底色统一、CGM 图表副标题修改、功能开关文字修改、睡眠计划空格删除、AppSetup「开启后才能在首页绘制 CGM 图形」文案——均属文案措辞或极低价值样式微调。i18n 树的文案权威是 strings.xml + TM，这类措辞若确需保留，应通过 TM 改译文实现，**不搬代码**。

### 2.12 电池图标预加载（不建议迁移）

上游维护者在 PR #163 评审中明确反对 `preloadBatteryIcons()`（Compose 不需要预加载、浪费内存；工作记录-2026-09-26 §2 已认可该意见成立——瓶颈是 BLE 往返而非 drawable 解析）。不带入。

---

## 三、已知回归项：明确不迁移

以下三项在 i18n 两树中均已核实为**上游原样、不带病**，保持原样即可：

| 回归项 | i18n 树现状（已核实） | 正确做法 |
|--------|----------------------|---------|
| Actions.kt 固定行高 72dp ×2（挂起行、临基行，裁掉中文第三行） | main/dev 均无 `height(72.dp)`，上游原样无固定高 | **回退到上游 = 保持 i18n 树现状**。将来若做防误触间距，用 `heightIn(min = 72.dp)`（UI定制设计 §9.6 危险操作规则 5） |
| Actions.kt 两处 `Color.Red` 硬编码（对话框正文 L348、确认按钮 L381） | main/dev 均无这两处红字；仅上游既有 L260/L262 行底 alpha 红绿（非 zh 引入） | **回退到上游 = 保持现状**。若将来要危险语义色，用 `MaterialTheme.colorScheme.error`，且只放确认按钮 |
| 电池充电图标镂空矢量方案（6 个 `battery_horiz_*_charging.xml`） | main/dev 均无这些 drawable；HorizBatteryIcon.kt L49-67 为上游 ⚡ 8sp 文本原样 | **保持上游原样**。⚡ 太小是上游已知视觉缺陷（工作记录-2026-09-28 Bug 1 深度分析），改进方向是「⚡ 放大到 10-12sp + 对比色」的**纯英文上游 PR**，不在 i18n 树本地改；PR #163 的镂空实现有渲染缺陷且已搁置，不复活 |

---

## 四、wip 原型处置（U1 控件库 / U2 Settings 迁移 / 组件目录规范化）

**定性**（STATUS.md §二、UI 美化 README）：wip 快照 aded91b3 是「恢复点而非合并候选」，代码不继承。i18n 两树已核实无 `components/controls/` 目录、Settings 仍上游 ListItem 风格。

### 值得保留的设计元素（重做时按这些规格，而非抄代码）

1. **控件职责划分**：StatusRow（只读状态）/ ToggleRow（开关）/ NavigationRow（导航）/ ActionRow（操作）/ ActionConfirmDialog（确认对话框，DangerLevel 分级）/ SettingsGroup（组容器）——六类覆盖 Settings/Actions/Debug 全部行形态。
2. **语义色纪律**：一切颜色走 `MaterialTheme.colorScheme` 槽位，禁止 `Color.Red/Green` 硬编码。
3. **readOnly 统一外观**：文字 onSurface alpha 0.38 + 交互禁用，作为 X2 只读策略（9.3）的视觉底座。
4. **行高 `heightIn(min = 56.dp)`**：最小触控目标 + 允许中文撑高，根治裁行。
5. **组件目录规范**（mobile 六子目录 / wear 五子目录）：与 i18n 工具链的文件扫描/模块归属兼容，重做时沿用。

### 不该照搬的部分

1. **不直接复制 wip 文件**：wip 代码基于 zh（含硬编码中文文案），直接搬会污染 i18n 树的资源纪律；控件文本必须全部参数化、由调用方传 `stringResource`。
2. **U2 对 Settings 的迁移方式不照搬**：wip 版 U2 顺手把「同步胰岛素泵时间/查找我的胰岛素泵」从 Settings 移除了——i18n 树 Settings 仍有这两项（未逐一核实行号），重做时**只做组件替换，不做条目搬迁**；条目搬迁属 9.7，单独评估。
3. **不追求一次性迁移整页**：上游 dev 高频改 UI，重做仍按「一屏一屏、只做加法、新旧共存」推进。

### 重做落点建议

- 落点（〔2026-11-03 复核〕实际为）：`mobile/src/main/java/com/pumppan/controlx2/ui/`（pumppan 树），共 6 件，缺 `DangerLevel`/`ActionConfirmDialog`。
- 时机：排在第一批「独立开关/入口类」之后、9.3 只读策略之前（9.3 的置灰依赖 readOnly 外观）。
- 工作量：控件库本体约 1 天（7 个文件，有 wip 快照作规格参照）；Settings 迁移另计。

---

## 五、附录：9.x 设计稿的实现前置条件（非本次迁移范围）

| 设计项 | 前置条件 | 主要依赖 |
|--------|---------|---------|
| 9.1 开关覆盖其余首页卡片 / DashboardCard 封装 | 本清单 #1 已落地 | U5（Dashboard 卡片元素化） |
| 9.2 通知 tab 开关化（Mobi 专有） | FeatureFlag 机制（#1 已带） | 联动：NotificationBundle 请求跳过、badge、selectedItem 回退、大剂量按钮中位 |
| 9.3 控制页 X2 只读策略（置灰/只读子页/灰禁） | **CapabilityRegistry**（数据层判定预算）+ U1 控件 readOnly 外观 + L0/L1/L2 分层事实表 | pumpX2 注解 + 实测黑名单 + VersionsA 固件版本解析 |
| 9.4 临基起始时间显示 | 历史库 opcode 2（TempRateActivatedHistoryLog）查询 | HistoryLogFetcher/Room 已有数据；UI 侧 heightIn 修复 |
| 9.5 挂起行 isMobi 守卫 + 挂起时长 | 无硬依赖（安全修复，可最早做） | 历史库 opcode 11（PumpingSuspendedHistoryLog） |
| 9.6 U3 Actions 迁移 | **已完成（2026-11-03 复核）**：13 行组件 / readOnly / heightIn 已落地 | 含 9.5 守卫、语义色、heightIn |
| 9.6 U4 Debug 迁移 + 开发者功能二级菜单 + Mobi 模拟模式 + 能力实验室 | U3 + CapabilityRegistry | 发送器三层分类（Tier 1/2/3 badge）、警告/二次确认机制 |
| 9.6 U5 Dashboard 卡片元素化 | U3 | 9.1 开关接同一机制 |
| 9.6 U6 皮肤/第二 renderer | U1-U5 全部完成 | 远期，当前不做 |
| 9.7 同步时间/查找泵移到控制页底部；调试页加「清空历史记录缓存」 | U3/U4 页面迁移顺带 | 缓存清理需 pumpcomm/shared 新消息码（bug-调试页历史缓存 §7 五处） |
| 9.8 大剂量全屏流程页（流式状态机/输入态/预设 chips） | 无 i18n 依赖，可独立排期 | 阶段 1：BolusDraft + 全屏页 + Room 预设表；阶段 3 扩展大剂量需真机取证（opcode 59/21 只读取证先行） |
| BLE「开关蓝牙」快捷按钮 | 无 | BluetoothAdapter.disable/enable（仅思路记录，未排期） |

---

## 六、建议批次与排期

> 粒度为单人粗估；每批独立 commit、两树分别落地。

| 批次 | 内容 | 预估 | 风险 | 验证手段 |
|------|------|------|------|---------|
| **B1（独立、零依赖）** | #5 BackHandler ×2 + #8 tab 状态保存 + #4 图标颜色/drawable | 半天 | 低 | `:mobile:compileDebugKotlin` + `processDebugResources`；真机点检：返回键行为、导航栏图标色 |
| **B2（开关/入口类）** | #1 首页三开关（含英文 slug + TM 登记）+ #3 功能开关入口上移 + #6 泵消息小弹窗开关 + #7 自动获取历史开关 | 1 天 | 低-中（动 Prefs/CommService） | 编译 + 真机点检：开关即时生效、杀进程状态保持、Debug 页四项开关均在；TM 双侧登记核对 |
| **B3（视觉小项）** | #2 可拖动 FAB + #9 控制页加粗 | 半天 | 低 | 编译 + 真机点检：FAB 拖动边界/位置持久化、激活行加粗 |
| **B4（控件库重做）** | 第四节 U1 控件按规格重写（不抄 wip） | 1 天 | 中 | 编译 + Roborazzi 截图（EN/ZH 双语）；控件 Gallery/Preview 自查 |
| **B5（需适配项）** | #10 TIR 显示 | 半天-1 天 | 中（数据口径未核实） | 先读 zh 实现确认数据源；编译 + 真机对泵读数抽验 TIR 百分比合理性 |
| 不做 | #11 文案微调、#12 预加载、#13/#14 回归项 | — | — | — |

**两树同步点**：B1-B5 每批在 main 树落地后，dev 树需单独评估——dev 的 Landing/Debug/Settings 与 main 有漂移（如 dev 已有「各设置页 Back 按钮」等上游改动），同一改动在 dev 树的插入点/行号需重新定位，文案在 dev TM 单独登记。

---

## 七、待确认问题

1. **#7 自动获取历史开关、#8 tab 状态保存在 dev 树的现状未核实**（main 树已确认无），迁移前补 grep。
2. **#10 TIR 的 zh 版数据源未核实**（读 DataStore 还是历史库、是否含 dev 需要的类型），迁移前先读 `controlX2-main-zh` 的 TherapyMetricsCard.kt L120-160。
3. **「同步时间/查找泵」在 i18n 树 Settings 中的行号未逐一核实**，第四节 U2 重做前补查。
4. **PR #162 在 i18n 树中的具体内容未逐点核实**（已 merged 上游，理论上随 main/dev 基线包含）。
5. 早期提交 517ebd3d 中的「Divider 位置调整」「返回横线」未单独列项（属微调且与上游结构耦合），如需保留请在 B3 批次顺带 diff 确认。
