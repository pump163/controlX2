# pumppan 分支审查修改意见（交接文档）

> 更新日期：2026-11-02 ｜ 适用对象：接手 pumppan 分支的开发者
> 全文基于对提交 `28a5095` 的逐文件静态审查、两棵 i18n 树的交叉核对，以及 2026-11-02 最终定稿的 UI 设计原则（见第 5 节决策记录，以本版细化为准），可独立执行，无需其他背景材料。

---

## 1. 背景与范围

| 项 | 值 |
|----|----|
| 被审工作树 | `D:\Programming\controlX2-i18n_pumppan`（分支 `pumppan`，跟踪 `i18n-origin/pumppan`，本地 ahead 19 / behind 93，远端为旧基线） |
| 主仓库（main 工作树） | `D:\Programming\controlX2-i18n`（分支 `main`，HEAD `3458741`） |
| dev 工作树 | `D:\Programming\controlX2-i18n-dev`（分支 `dev`，HEAD `73824fb` = pumppan 基点） |
| lab 资产树 | `D:\Programming\controlX2-i18n-lab`（TM 工具链在 `i18n/`；pumppan 专用 TM 在 `i18n-pumppan/`） |
| 基点提交 | `73824fb`（dev HEAD） |
| 被审提交 | `28a5095` feat(pumppan): 型号门控全覆盖 + 控制页组件化 + 子菜单灰显 |
| 改动规模 | 31 文件，+1067 / -276 |
| 工作区状态 | 干净（审查时无未提交改动） |
| 与 main 的分叉点 | `45e3795b` |

**要解决的问题（一句话）**：X2 泵无法被 App 远程控制（仅大剂量例外），控制页与各子菜单需要清晰表达「只读」，同时保持泵侧操作后回传的状态与数值始终可读。

**只读表达的最终规则（第 5 节决策 2，与此冲突的旧实现一律按本条调整）**：所有子菜单入口、子页里的操作行保持正常颜色、可点击（用于查看状态、说明和弹窗内容），不做行级置灰、不做透明度降级；真正的禁用落在**弹窗内的确认/执行按钮**（`enabled=false` + 视觉降级）并附原因文案（X2 不支持该操作）；仅「点击即执行、无确认弹窗」的写操作保留行级置灰，更优做法是补确认弹窗；取消/关闭按钮始终可用。

---

## 2. 已实现功能清单

| 功能 | 主要文件 | 实现要点 | 对照迁移清单 |
|------|---------|---------|-------------|
| 型号门控数据层 | `shared/src/main/java/com/jwoglom/controlx2/shared/util/SupportedDevicesUtil.kt`、`PumpDeviceNameUtil.kt` | `canControlPump(model)` = 仅 Mobi 可控；`supportsDevice(model, messageClass)` 读 pumpx2 `@MessageProps` 二次收窄；`pumppanModelOverride` 进程级调试覆盖 | 附录 9.3（CapabilityRegistry 简化版） |
| 控制页 13 行组件化 | `mobile/src/main/java/com/pumppan/controlx2/ui/`（ActionRow / NavigationRow / ToggleRow / StatusRow / SettingsGroup / RowStyle）、`sections/Actions.kt` | 语义色、`heightIn(min=56dp)`、readOnly 38% 透明度、组件零文案；删除上游红绿底色（`Color.Red/Green.copy(alpha=0.5F)`），激活态改 `emphasized` 加粗。控制页 7 个 `NavigationRow` 子菜单入口全部可点、不置灰 | 第四节 U1 变体（新包，非清单建议的 `presentation/components/controls` 落点） |
| 子菜单写操作禁用（当前实现为**行级**禁用，待按最终规则改为**按钮级**，见 §4.1） | `ControlIQSettingsActions.kt`（L238/241、L284/287）、`SafetyLimitsActions.kt`（L207/210、L238/241）、`ProfileActions.kt`（L358/361、L387/390、L410/413、L444/447、L502/505）、`CGMActions.kt`（L218、L421）、`QuickBolusSettingsActions.kt`（L187）均为行级 `clickable(enabled=canControl)`（部分带 `rowTitleColor` 灰显）；`CartridgeActions.kt` 已是按钮级（workflow 按钮 enabled + disabledReason） | 入口正常、操作项禁用；药筒页复用既有 key `cartridge_insulin_control_unsupported_model` | 9.3 子集 |
| 通知临基按钮门控 | `CommService.kt` L900-907 | 临时基础率按钮仅 `canControlPump(..., SetTempRateRequest)` 时显示 | 附录 9.2 方向 |
| 返回键逐级返回 | `presentation/screens/Landing.kt`（BackHandler + `parentSection()`，L625-644） | 枚举 15 值穷举：先收 bottom sheet，再按层级回退（子菜单→ACTIONS，DEBUG→SETTINGS，FEATURE_FLAGS→DEBUG），首页才交系统退出 | 清单 #5（B1 批次）增强版 |
| 同步时间/查找泵搬迁 | `Settings.kt`（-96 行）、`Actions.kt`（控制页底部重建两行 + 两对话框，带 `canPumpSettings` 门控） | 资源 key 复用（`settings_sync_pump_time` 等），无孤儿 | 9.7 完整版 |
| 语言切换（清单外新增） | `com/pumppan/controlx2/PumppanLocale.kt`、`PumppanApplication.kt`、`PumppanPrefs.kt`；`AndroidManifest.xml`；`MainActivity.kt` / `CommService.kt` / `MobileClientService.kt` 各自 `attachBaseContext` wrap | AUTO/ZH/EN；设置页 `PumppanLanguageRow` 选择后 `recreate()`；manifest 注册 `PumppanApplication` | 无对应项 |
| 泵型号覆盖调试（清单外新增） | `PumppanPumpModel.kt`、`PumppanSettingsRows.kt`（PumppanPumpModelRow / PumppanModelOverrideBanner） | Debug 页强制 UI 按另一型号渲染；强制与实际不符时 `errorContainer` 警告条（含超长安全文案 `pumppan_model_mismatch_warning`） | 9.3 配套调试工具 |

**资源与 TM**：9 条新 key（`pumppan_` 家族）在 `values/strings.xml` 与 `values-zh-rCN/strings.xml` 两侧齐全；TM 登记在 lab 树 `i18n-pumppan/tm/translations_cn.csv`（status=applied）。**dev 主 TM `i18n/tm/translations_cn.csv` 尚无这 9 条**（见 B4）。

**回归项核查**：三个已知回归（见第 6 节）均未带入。✓

---

## 3. 必须修（阻断）

### A1 `dataStore` 未解析引用 → mobile 模块编译失败

- **证据**：`mobile/src/main/java/com/jwoglom/controlx2/CommService.kt:905`
  ```kotlin
  dataStore.setupDeviceName.value?.let { determinePumpModel(it) },
  ```
  CommService.kt 全文仅此一处 `dataStore`：无属性定义、无 import；`CommServiceCallbacks` 接口（`pumpcomm/src/main/java/com/jwoglom/controlx2/pump/CommServiceCallbacks.kt`）也无此属性。Kotlin unresolved reference，`compileDebugKotlin` 必失败。
- **前置条件**：确认 `Prefs.pumpModelName()` 的写入时机——连接流程是否已把泵蓝牙名持久化（`Prefs.kt:264/268`）。若已写入，直接用下面的修复；若未写入，需在连接建立处补 `setPumpModelName(...)`，或改从 `pumpState` 取值。
- **修复代码**（替换 L903-907 的门控计算）：
  ```kotlin
  val canTempRate = canControlPump(
      Prefs(applicationContext).pumpModelName()?.let { determinePumpModel(it) },
      SetTempRateRequest::class.java
  )
  if (Prefs(applicationContext).showTempRateNotificationButton() && canTempRate) {
  ```

---

## 4. 建议修改（B1–B7）

| # | 问题 | 证据（文件:行号） | 为什么 | 怎么改 |
|---|------|------------------|--------|--------|
| B1 | 行级禁用需按最终规则改为**按钮级禁用**（详见 §4.1 处置表） | 行级禁用现状：`ControlIQSettingsActions.kt` L241/L287、`SafetyLimitsActions.kt` L210/L241、`ProfileActions.kt` L361/390/505（这 5 处有确认弹窗）；`CGMActions.kt` L218/L421、`QuickBolusSettingsActions.kt` L187（有弹窗但当前行级 `enabled=false` 连弹窗都打不开） | 最终规则：入口与操作行永不置灰、保持可点（可查看状态与弹窗说明），禁用落在弹窗确认按钮上。行级灰显让用户连「这个操作是干什么的」都看不到，且各弹窗本就有取消按钮，把禁用移到按钮不损失安全性 | 见 §4.1 逐页「禁用落点」列：删行上的 `enabled = canControl` 与 `rowTitleColor(!canControl)`，在对应 `AlertDialog` 的确认 `TextButton` 上加 `enabled = canControl`，弹窗正文追加原因文案 |
| B2 | 语言切换后运行中的 Service 通知文案滞后；PumppanApplication 注释与实际机制不符 | `PumppanApplication.kt` L7-8；`CommService.kt` L86-89 | Service 不重建，`attachBaseContext` 不会重跑；实际是靠逐个手动 wrap 三处 + Application，注释声称「由 Application 覆盖 Service」误导后来者 | `PumppanChoiceRow` 选择语言后补提示「部分通知文案将在重启应用后生效」（新 key 走资源 + TM）；修正该注释为「各组件通过 attachBaseContext 手动 wrap」 |
| B3 | 型号覆盖改动依赖页面重组才生效 | `SupportedDevicesUtil.kt` L10-11（`@Volatile var` 非 State）；`Notifications.kt` L88（`canDismissNotifications` 组合时求值一次） | Debug 页改型号后，已打开页面的门控不刷新，须切页触发重组；行为不直观且无说明 | 二选一：把 override 改为 `mutableStateOf`（放 shared 层，两模块可见）；或保持现状但注释「需切换页面后生效」。改动前评估 shared 模块对 Compose 的依赖 |
| B4 | pumppan 的 9 条 key 未并入 dev 主 TM | lab `i18n/tm/translations_cn.csv` grep `pumppan` 0 命中；`i18n-pumppan/tm/translations_cn.csv` 9 条 | 合并回 dev 后 res-audit/verify 会把已落盘译文判为「TM 外孤儿」，工具链口径漂移 | 合并前将 9 条并入主 TM；或正式确认 `i18n-pumppan/` 为第三 TM 并更新工具链配置（见待确认 7） |
| B5 | 型号覆盖下通知按钮跟着出现，通知栏不受 banner 警告覆盖 | `CommService.kt` L905（`determinePumpModel` 受 override 影响）+ `SupportedDevicesUtil.kt` L26-27 | override=Mobi 时 X2 真机通知栏出现临基按钮，`PumppanModelOverrideBanner` 只盖 UI 面盖不住通知 | 真机确认 X2 对 `SetTempRateRequest` 的实际反应；若泵报错，门控改用 `determinePumpModelFromName`（绕过 override） |
| B6 | `heightIn(min = 88.dp)` 魔法数字 ×3 | `Actions.kt`（停止/启动胰岛素行、临时基础率行）、`SafetyLimitsActions.kt` | 同一语义（危险行加高）散落三处，调一处漏两处 | `RowStyle.kt` 增加 `val DangerousRowMinHeight = 88.dp`，三处引用 |
| B7 | 缩进异常 | `Actions.kt` L255 附近 `val canPumpControl = ...` 顶格 | 破坏代码块缩进，读代码易误判作用域 | 恢复一层缩进 |

### 4.1 子菜单处置表（按最终规则逐页核对：行/按钮落点）

核对方法：逐行读各子菜单 `clickable(enabled = canControl)` 的点击体，确认其触发的是否为 `AlertDialog` 确认弹窗。

| 子菜单 | 操作点（行号） | 是否有确认弹窗 | 禁用落点 | 原因文案来源 | X2 下预期 | Mobi 下预期 |
|--------|---------------|----------------|----------|--------------|-----------|-------------|
| CGMActions | G6 启动/停止传感器行（L218→DropdownMenu+AlertDialog L235+）；G7 同构（L421→L432+，共 4 个 AlertDialog） | **有**（4 个） | 行恢复可点（去掉 `enabled`）；4 个弹窗确认按钮 `enabled = canControl` + 弹窗内原因文案 | 复用 `cartridge_insulin_control_unsupported_model`（含 %s 型号占位） | 行可点→弹窗正常打开→确认灰且无效→取消可退出；传感器状态可读 | 确认按钮可执行 |
| QuickBolusSettingsActions | 设置写入行（L187→`showQuickBolusDialog`，AlertDialog） | **有** | 行恢复可点；弹窗确认按钮 `enabled = canControl` + 原因文案 | 同上 | 同上 | 确认按钮可执行 |
| ControlIQSettingsActions | 「更改设置」行（L241→`showChangeControlIQDialog`→AlertDialog L328）；「编辑睡眠时段」行（L287→`showEditSleepScheduleDialog`→AlertDialog L390） | **有**（2 个） | 删行级 `rowTitleColor(!canControl)` + `enabled`（L238-241、L284-287 回退）；2 个弹窗确认按钮 `enabled = canControl` + 原因文案 | 同上 | 摘要与设置值恢复正常颜色可读；弹窗打开、确认灰、取消可退 | 确认按钮可执行 |
| SafetyLimitsActions | Max Bolus 行（L210→`showMaxBolusDialog`→AlertDialog L264）；Max Basal 行（L241→`showMaxBasalDialog`→AlertDialog L311） | **有**（2 个） | 删行级灰显（L207-210、L238-241 回退）；2 个弹窗确认按钮 `enabled = canControl` + 原因文案 | 同上 | 当前限制值正常可读；弹窗打开、确认灰、取消可退 | 确认按钮可执行 |
| ProfileActions | 编辑时段行（L361→`showEditSegmentDialog`）、添加时段行（L390→`showAddSegmentDialog`）、添加配置行（L505→`showAddProfileDialog`）——3 处有弹窗 | **有**（3 个） | 删这 3 处行级灰显（L358-361、L387-390、L502-505 回退）；弹窗确认按钮 `enabled = canControl` + 原因文案 | 同上 | 配置列表正常可读；3 个弹窗打开、确认灰、取消可退 | 确认按钮可执行 |
| ProfileActions（无弹窗操作） | 刷新缓存行（L413→`sendPumpCommands(SendType.BUST_CACHE, ...)` **点击即执行**）；激活配置行（L447→`sendPumpCommands(...)` **点击即执行**） | **无**（2 个） | 保留行级置灰（`rowTitleColor(!canControl)` + `enabled`，L410/444 现状不动）——符合「点击即执行的写操作保留行级灰」规则；激活配置行（L447）可选优化：补确认弹窗把禁用移到按钮 | 行灰无需文案；若补弹窗则复用同一原因 key | 刷新/激活行灰显不可点；其余可看 | 可执行 |
| CartridgeActions | 更换药筒/填充管路/填充针管 3 个 workflow 按钮（L253-258 `menuAvailability.can*`） | 按钮**本身即执行键** | **保持按钮级置灰 + disabledReason**（范本，不动） | 复用 `cartridge_insulin_control_unsupported_model` | 3 按钮禁用 + 原因；状态展示可读 | 按实际状态启用 |
| SoundSettingsActions | 无门控（X2 实测可远程修改，本分支有意回滚不处理） | — | — | — | — | 两机型一致 |

**改动量小结**：
- **行级灰显回退**（恢复正常色 + 可点）：ControlIQ 2 处、SafetyLimits 2 处、ProfileActions 3 处（弹窗型）；另 CGMActions 2 处、QuickBolus 1 处去掉 `enabled=false`（它们本来无灰显，只是行不可点）。
- **保留行级灰**：ProfileActions L410/444 两处（无弹窗的点击即执行操作）。
- **新增按钮禁用**：约 12 个 AlertDialog 的确认按钮（CGM 4 + QuickBolus 1 + ControlIQ 2 + SafetyLimits 2 + Profile 3）加 `enabled = canControl` + 弹窗内原因文案行。
- **原因文案**：统一复用既有 key `cartridge_insulin_control_unsupported_model`（含型号占位），**无需新增 strings/TM 条目**。弹窗内取文案：`stringResource(R.string.cartridge_insulin_control_unsupported_model, model)`（model 从 `determinePumpModel` 取）。
- **取消按钮**：各弹窗 dismissButton 现状已可用，不动；验收时确认弹窗均可正常退出。

---

## 5. 决策记录（重要，勿重开讨论）

以下为已定稿的设计与方案决策，后续不再重新讨论，除非底层事实发生变化：

**决策 1：子菜单折叠方案否决（2026-11-02）。** 结论：保留只读表达，采用「入口正常 + 操作项禁用」。

1. **折叠在 main-zh 从未落地**：`controlX2-main-zh` 的 `presentation/components/controls/SettingsGroup.kt`（L37-91）是零调用方的设计原型（wip 快照 U1 备料，`git grep 'SettingsGroup('` 仅命中定义文件）。pumppan 树 `com/pumppan/controlx2/ui/SettingsGroup.kt` 是其逐字移植，同样零调用。沿用它等于全新实现并引入未验证的交互。
2. **「两树子菜单机制不同」前提不成立**：main 树与 dev 树的 `Actions.kt` 同用上游 `ListItem`（main 10 处 / dev 11 处，无 NavigationRow/SettingsGroup）。两树真实差异是内容性漂移（dev 多 Quick Bolus 入口行、dev 已 stringResource 化而 main 仍是 `context.getString`、组件 import 路径不同，合计 57 行 diff）——换折叠不能消除漂移，反而把结构性改动叠加进更大的冲突面。
3. **折叠有安全与可用性缺点**：收起状态会把「正在输注/已挂起/临基进行中」等关键状态藏进可选的 summary 参数；X2 用户需多点一次展开才发现不能操作。

**决策 2：入口与操作行永不置灰；禁用只落在弹窗确认按钮（2026-11-02 最终规则，细化并替代早先「子页操作行级灰显」表述）。**

规则内容：

1. 所有子菜单入口（控制页 `NavigationRow`）与子页里的操作行：**保持正常颜色、可点击**——用于查看状态、阅读说明、打开弹窗查看内容；不做行级置灰、不做透明度降级。
2. 真正的禁用落在**弹窗内的确认/执行按钮**：按钮 `enabled=false` + 视觉降级（颜色/透明度），并在弹窗内给出原因文案（X2 不支持该操作）。
3. 仅「点击即执行、没有确认弹窗」的写操作保留行级置灰；更优做法是补一个确认弹窗、把禁用放到按钮上（ProfileActions 激活配置行 L447 为候选）。
4. 取消/关闭按钮始终可用；弹窗必须能正常退出。

理由：行级灰显会连「这个操作是干什么的」一起灰掉，用户既看不清说明也学不到交互路径；按钮级禁用保住了状态可读、说明可读与可发现性，且全部弹窗交互统一（能点开 → 看到原因 → 确认不可用 → 取消退出），视觉语义单一（灰 = 此处不可执行，而非此处不可看）。CartridgeActions 的 workflow 按钮是既有范本。

落地结论见 §4.1 处置表：12 个弹窗确认按钮改为禁用落点；7 处行级禁用回退为可点；2 处无弹窗操作保留行级灰。

---

## 6. 不得引入项（三个已知回归，明确禁止带回）

| 回归项 | 说明 | 现状 |
|--------|------|------|
| 72dp 固定行高 ×2 | zh 分支曾把挂起/临基行写死 `height(72.dp)`，裁掉中文第三行文本 | 未带入 ✓；本分支用 `heightIn(min=88.dp)` 替代（注意与 B6 统一常量） |
| Color.Red 硬编码红字 ×2 | zh 分支曾给停止胰岛素对话框正文/确认按钮加 `Color.Red` | 未带入 ✓；危险语义一律走 `MaterialTheme.colorScheme.error` 系槽位 |
| 充电图标镂空矢量改造 | 6 个 `battery_horiz_*_charging.xml` 替代上游 ⚡ 文本，有渲染缺陷且已被定性搁置 | 未带入 ✓；`HorizBatteryIcon.kt` 保持上游原样，改进方向是另提纯英文上游 PR |

---

## 7. 验收标准（逐条可判定）

1. `.\gradlew :mobile:compileDebugKotlin` 与 `.\gradlew :mobile:assembleDebug` 通过（A1 修复后）。
2. res-audit 结果为 0（无孤儿 key、无 TM 外落盘、values 与 values-zh-rCN key 数一致；B4 完成后口径含 pumppan 9 条；原因文案复用既有 key，不新增）。
3. **X2 下核心路径**：点击 CGM「启动传感器」行 → 弹窗正常打开、确认按钮灰且点击无效、取消可退出、弹窗内可见 X2 不支持的原因文案、CGM 状态文本可读；G7/快速大剂量/ControlIQ 更改设置/睡眠时段/Max Bolus/Max Basal/配置编辑/添加时段/添加配置共 12 个弹窗同规则。
4. **X2 下无弹窗操作**：Profile 刷新缓存行与激活配置行保持行级置灰、点击无效；其余行（含控制页 7 个入口）正常颜色可点击。
5. **Mobi 下**：所有弹窗确认按钮可执行，无任何置灰，行为与上游一致。
6. 语言切换：AUTO/ZH/EN 三档切换后 Activity 立即生效（recreate）；通知栏文案接受滞后（B2 提示已显示）。
7. 返回键：任意子菜单按返回逐级回退（CGM/药筒/配置文件/快速大剂量/ControlIQ/安全限制→控制页；DEBUG/Nightscout/xDrip→设置页；FEATURE_FLAGS→DEBUG），无直接退出 App 的路径。

---

## 8. 验证步骤

**编译（工作树 `D:\Programming\controlX2-i18n_pumppan`）：**

```powershell
cd D:\Programming\controlX2-i18n_pumppan
.\gradlew :mobile:compileDebugKotlin
.\gradlew :mobile:processDebugResources
.\gradlew :mobile:assembleDebug
```

**资源审计（lab 树工具链，按 lab 既有入口执行 res-audit / verify_apply）：**

```powershell
cd D:\Programming\controlX2-i18n-lab
# 按 lab 的 README/工具脚本实际入口执行，核对：
# 1) pumppan_ 9 条 key 在 values 与 values-zh-rCN 均存在
# 2) 无孤儿资源；3) TM 合并口径下 0 缺失
```

**真机点检清单（X2 机 + Mobi 机各一轮）：**

| # | 点检项 | 预期 |
|---|--------|------|
| 1 | 控制页入口行（X2） | 7 个 `NavigationRow` 全部正常可点、不置灰 |
| 2 | CGMActions（X2） | 点击「启动传感器」行 → 弹窗打开、确认按钮灰且无效、原因文案可见、取消可退出；传感器状态可读；停止分支与 G7 同规则 |
| 3 | 各弹窗统一走查（X2） | QuickBolus/ControlIQ×2/SafetyLimits×2/Profile×3 共 9 个弹窗：打开正常、确认灰、原因文案在、取消可用 |
| 4 | 无弹窗操作（X2） | Profile 刷新缓存行、激活配置行置灰不可点；其余行正常色可点 |
| 5 | CartridgeActions（X2） | 3 个 workflow 按钮禁用 + 原因文案；状态展示正常 |
| 6 | 控制页（Mobi） | 全部可操作；激活态行加粗放大；所有弹窗确认按钮可执行 |
| 7 | Debug 页改型号覆盖 → 回控制页/各子菜单 | 门控随页面重组更新；不符时顶部警告条出现 |
| 8 | 语言切换 ZH↔EN | Activity 即时切换；通知栏文案滞后需重启 |
| 9 | 返回键逐级 | 按第 7 节第 7 条路径逐级回退 |
| 10 | 通知栏临基按钮 | Mobi 有、X2 无；override 状态下行为按 B5 结论 |
| 11 | 同步时间/查找泵 | 控制页底部可触发对话框；X2 上灰显 |

---

## 9. 待确认事项

| # | 事项 | 谁确认 / 怎么确认 |
|---|------|------------------|
| 1 | `Prefs.pumpModelName()` 写入时机（A1 修复前提） | 开发者：grep `setPumpModelName` 调用点，确认连接建立流程已持久化；否则补写 |
| 2 | X2 对 `SetTempRateRequest` 的真实反应（B5） | X2 真机 + override=Mobi：观察泵屏/通信日志，决定门控是否绕过 override |
| 3 | TIR 数据源（迁移清单遗留，本分支未涉及） | 后续做 TIR 批次前：读 main-zh `TherapyMetricsCard.kt` L120-160 确认 DataStore vs 历史库口径 |
| 4 | Android 13 以下语言切换表现（自建 wrap 不依赖 per-app locale，理论全版本可用） | 真机覆盖 API 26-32 各一台：切换/重启/通知场景 |
| 5 | Mobi 连接前通知按钮缺失（deviceName=null → 门控 false，连接后恢复） | Mobi 真机：确认通知更新时机覆盖冷启动窗口 |
| 6 | X2 用户对「连接前全灰控制页」的接受度 | 真机走查 + 用户反馈；必要时连接前显示「未连接」提示行 |
| 7 | `i18n-pumppan` TM 目录是否转正为第三 TM（B4 的前置决策） | 维护者拍板：并入主 TM 或更新工具链白名单 |
| 8 | `SettingsGroup`/`StatusRow` 当前零调用方 | 若未来做导航组减负再评估接线；近期可加注释防误删 |
| 9 | ProfileActions 激活配置行（L447，点击即执行且无确认）是否补确认弹窗 | 规则允许保留行级灰；因「激活配置」是高影响写泵操作，建议补确认弹窗（按钮落灰）——由开发者按第 10 节步骤 4 决定 |
| 10 | 弹窗内原因文案复用 `cartridge_insulin_control_unsupported_model` 的措辞是否通顺（该 key 原为药筒场景设计） | 开发者目测各弹窗文案；若不通顺再议新 key（需 TM 登记） |

---

## 10. 建议施工顺序与工作量

| 步 | 内容 | 预估 |
|----|------|------|
| 1 | 修 A1（含确认 `pumpModelName` 写入时机） | 10-30 分钟 |
| 2 | `:mobile:compileDebugKotlin` + `assembleDebug` 验证 | 10 分钟 |
| 3 | B1a 行级回退：ControlIQ L238-241/L284-287、SafetyLimits L207-210/L238-241、Profile L358-361/L387-390/L502-505 删灰显与 enabled，恢复可点 | 30 分钟 |
| 4 | B1b 按钮落灰：CGM×4、QuickBolus×1、ControlIQ×2、SafetyLimits×2、Profile×3 共 12 个 AlertDialog——行去掉 `enabled`、确认按钮加 `enabled = canControl` + 弹窗正文原因文案（复用 `cartridge_insulin_control_unsupported_model`）；同步骤顺带决定 Profile L447 是否补弹窗 | 1.5-2 小时 |
| 5 | B4 TM 9 条并入主 TM（或决策 7）+ res-audit | 30 分钟 |
| 6 | B2 语言切换提示 + 注释修正（新 key 走资源 + 三处 TM 登记） | 20 分钟 |
| 7 | B3 型号覆盖刷新策略（含 B5 真机结论输入） | 30 分钟-1 小时 |
| 8 | B6 常量提取 + B7 缩进 | 10 分钟 |
| 9 | 真机双机型全量点检（第 8 节清单） | 半天 |

**代码改动合计约 3-4 小时**（步骤 1-8；较「行级灰显」方案多约 1 小时，增量在 12 个弹窗的按钮改造与原因文案）。无需新增 strings/TM 条目（原因文案复用既有 key）。
