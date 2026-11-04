# feature 文档状态索引

> 校核日期：2026-11-03　代码基准：pumppan 工作树（`D:\Programming\controlX2-i18n_pumppan`）
> **锚点一律用「文件 + 符号名」，不用行号**：行号每轮 diff 都会漂移（HANDOFF 已出现 4 处失效）。
> 本文件只做状态索引，不替代正文；正文仍是方案/提案，冲突以本索引第三节为准。

## 一、状态表

| 条目 | 状态 | 代码锚点 | 备注 |
|---|---|---|---|
| 首页三卡片开关 + 全局状态 | 已完成 | `FeatureFlag.kt :: cgmChartVisibleState / sensorInfoCardVisibleState / historyLogSyncBarVisibleState`；`Dashboard.kt` 三个消费点；`Settings.kt`「首页显示」区 | 同步写 Prefs，启动由 `PumppanApplication :: FeatureFlag.initState` 初始化 |
| 同步完成不占位 | 已完成 | `HistoryLogSyncProgressBar.kt`：同步完成后 `return`，50dp 仅未完成时占用 | HANDOFF 旧描述「固定 50dp 空白」已过时 |
| Landing 两处 BackHandler | 已完成 | `Landing.kt :: BackHandler`（合并为一处复合条件） | |
| 控制页激活态加粗 | 已完成 | `Actions.kt` 行组件 + `RowStyle.kt :: emphasized` | |
| 挂起/恢复行 `isMobi` 守卫 | 已完成 | `Actions.kt` 挂起行 `readOnly` + `SupportedDevicesUtil.kt :: canControlPump` | |
| 子页面「可进、可看、保存灰」 | 已完成 | `CGMActions / CartridgeActions / ProfileActions / SoundSettingsActions / SafetyLimitsActions / QuickBolusSettingsActions / ControlIQSettingsActions / Notifications` 的 `readOnly / reasonText`；`dialogs/AddProfileDialog.kt` 等 `enabled` + `reasonText` | 声音设置 2026-11-03 补型号门控 |
| 同步泵时间 / 查找泵移控制页底部 | 已完成 | `Actions.kt` 的 `settings_sync_pump_time` 两个 `ListItem` + 对应 Dialog | 设置页原位置仅留注释 |
| 目录规范化 | 已完成 | `presentation/{components,navigation,preview,screens,theme,util}` | |
| U3 Actions 13 行组件化 | 已完成 | `Actions.kt` 13 处 `ActionRow / ToggleRow / NavigationRow` | 见下「冲突 10」 |
| TIR（迁移清单 B5） | 已完成 | `TherapyMetricsCard.kt :: TherapyMetricsCardFromDataStore`（最近 288 条 CGM 读数实算） | 文档原写「未开始」 |
| 充电图标挖空矢量 | **已完成**（原「禁带」已解除） | 6 × `battery_horiz_*_charging.xml`；`HorizBatteryIcon.kt :: getPainterForBatteryPercent` | 2026-11-03 `ce4db28` |
| 声音设置位掩码 / 校验 / 反馈 | 已完成 | `SoundSettingsActions.kt`：掩码内部推导、下拉选择、发送前校验、Toast、非 Mobi 禁用 | 2026-11-03 多个提交 |
| 睡眠计划星期选择器 | 已完成 | `ControlIQSettingsActions.kt :: SleepDaysSelector` | 位运算自实现，未用 `MultiDay.fromBitmask` |
| U1 元素组件 | 部分完成 | `com.pumppan.controlx2.ui` 下 6 件：`ActionRow / ToggleRow / NavigationRow / StatusRow / RowStyle / SettingsGroup(已删)` | 设计 7 件，缺 `DangerLevel` / `ActionConfirmDialog`；落点与文档不同 |
| `SettingsGroup` | **已否决并删除** | 无（2026-11-03 删除 `ui/SettingsGroup.kt`） | 实现的是已否决的折叠方案 |
| `StatusRow` | 仅定义、未使用 | `com.pumppan.controlx2.ui/StatusRow.kt` | 0 调用，预留给状态行展示 |
| U2 Settings 迁移 | 仅设计未实施 | `Settings.kt` 全页仍为 `ListItem` | |
| U4 Debug（伪开关 / Capability Lab / 开发者菜单） | 仅设计未实施 | `Debug.kt` 伪开关块；`DebugPopupSurface` 已完成但与 U4 无关 | |
| U5 Dashboard 卡片元素化 | 仅设计未实施 | `Dashboard.kt` 沿用上游卡片 | |
| 三层 `ControlElement` DSL | 仅设计未实施 | 无 | |
| 通知 tab 开关化 + 三个联动点 | 仅设计未实施 | `Landing.kt` 的 `showInNav` 静态；`NotificationBundle.allRequests()` | |
| CapabilityRegistry / L0-L2 / 固件维度 / 实测黑名单 | 仅设计未实施 | `SupportedDevicesUtil.kt` 仅型号级判定 | |
| 泵能力收敛与自建挡板方案 | **建议 / 未实施** | `docs/feature/泵能力收敛与自建挡板方案.md`；现状锚点 `SupportedDevicesUtil.kt :: canControlPump` | 2026-11-03 新增；含上游事实、升级决策、观察清单、待决事项 |
| 可拖动 FAB / `pump_secondary.xml` / 泵消息 Toast 开关 / tab 状态保存 | 仅设计未实施 | `Landing.kt`、`Prefs.kt` | |
| 大剂量全屏流程 | 仅设计未实施 | 无 | 另见「大剂量功能设计」 |
| App 内语言切换 | 已完成（**超出原定范围**） | `PumppanLocale.kt`；`PumppanApplication.kt :: attachBaseContext`；`PumppanSettingsRows.kt` 语言行 | D1 原写「不做」，需补登记 |
| `:l10n` 模块 | 已否决 / 作废 | `settings.gradle` 无该模块，目录不存在 | |

## 二、否决与禁带清单

| 项 | 结论 | 依据 |
|---|---|---|
| 子菜单折叠 / 就地展开方案 | **否决**（2026-09-28） | `SettingsGroup.kt` 曾实现该方案，2026-11-03 整文件删除 |
| 72dp 固定行高 | **禁带**（回归项） | `Actions.kt` 无 `height(72.dp)` |
| `Color.Red` / `Color.Green` 硬编码 | **禁带**（回归项） | `Actions.kt` 无硬编码色，改用语义色 |
| `:l10n` 模块 | **作废** | `settings.gradle` 仅 6 个模块 |
| 「充电图标禁带」 | **已解除**（2026-11-03） | 改为挖空矢量，`ce4db28` |
| `SettingsGroup` | **删除** | 零调用 + 实现被否决方案 |

## 三、冲突区（文档说 X / 代码是 Y / 裁决）

| # | 文档说 | 代码是 | 裁决 |
|---|---|---|---|
| 1 | 充电图标「不迁移 / 禁带」 | 已实现挖空矢量（`HorizBatteryIcon` + 6 drawable） | **已裁决：采用**（`ce4db28`，用户批准） |
| 2 | TIR「B5 未开始」 | `TherapyMetricsCard :: TherapyMetricsCardFromDataStore` 已实算 | **已裁决：已完成** |
| 3 | U3「冻结，等 i18n」 | `Actions.kt` 13 行组件 + `readOnly` + `heightIn` 已落地 | **已裁决：已完成**（仅确认框组件缺） |
| 4 | `SettingsGroup`「已定稿」 | 零调用，且实现折叠方案 | **已裁决：删除** |
| 5 | 9.3「放弃 CONTROL 默认全禁」 | `canControlPump = model == MOBI`，X2 全灰 | **已裁决：搁置（2026-11-03，用户拍板）**——真机实测 X2 控制页**全部只读、仅大剂量可执行**，当前实现与实测一致，**不是缺陷，勿擅自放开**。长期等 pumpx2 库按固件版本号把各项能力收敛清楚后再评（重评触发条件：pumpx2 提供固件/型号级能力矩阵，或官方固件升级改变实测表现）；在此之前遵从 main/dev 的手动控制现状 |
| 6 | D1「不做 App 内语言切换」 | `PumppanLocale` AUTO/ZH/EN 已实现 | **已裁决：已实现（超范围）**，需补登记 |
| 7 | 资源目录 `values-zh`、key 集合一致 | 实际 `values-zh-rCN`；mobile 1089/1083、wear 348/347 | **已裁决：登记白名单（2026-11-03，用户拍板）**——`values-zh-rCN` 为唯一正式目录（`values-zh` 写法作废）；差额为语言名等**刻意例外**，mobile 差 6 条、wear 差 1 条，登记白名单放行；白名单只覆盖这 7 条，**差额再扩大即报警**（超出现值需逐条复核并补登） |
| 8 | 蓝牙监听 `Debug.kt:796-838` | 实际注释块 840-882；`CommService.kt` 仍读 `onlySnoopBluetoothEnabled` | **已裁决：UI 停用 ≠ 能力停用**，文档已改为符号锚 |
| 9 | HANDOFF 多处 `文件:行号` | 已漂移 4 处 | **已裁决：交接文档改用符号锚** |
| 10 | 「13 行组件化 → 组件体系完成」 | 13 处在用，但 `StatusRow` / `SettingsGroup` 0 调用 | **已裁决：组件体系未闭环**（`SettingsGroup` 已删，`StatusRow` 保留待用） |
| 11 | U1 落点 `presentation/components/controls/` | 实际 `com/pumppan/controlx2/ui/`，6 件（非 7） | **已裁决：以代码为准**，文档已改 |
