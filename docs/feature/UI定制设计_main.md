# ControlX2 UI 定制设计

> 版本：V1.0（2026-09-29 从《多语与UI分离实施方案.md》第 9 章拆出）
> 归属：**local/customizations 层**，不 PR 上游
> 状态：**只讨论不动手**，待 i18n 主线完成后实施

---

## 9.1 首页功能框开关化

现状：首页 7 个 item（Dashboard.kt:182-259），已有 FeatureFlag 机制（SharedPreferences `WearX2` + `mutableStateOf` 全局响应，shared/FeatureFlag.kt），仅 CGM 图表、传感器卡、历史同步条 3 项接入。

决策：
1. 开关覆盖其余可隐藏卡片，建议封装 `DashboardCard(flag=…){}` 消除重复 `if (state.value){item{}}`
2. 血糖大数字 + IOB/COB 那一行（GlucoseHeroCard/TherapyMetricsCard 并排）**不做开关**（半行空白无法处理）
3. **不做"无 CGM 自动隐藏"**：CGM 短暂丢数据会导致卡片闪烁；原则是"开关决定布局（手动），卡内 null 显示占位（自动）"
4. 通知 tab 开关化：见 9.2
5. 开关页入口从 设置→Debug→Feature Flags 上移为设置页一级入口"功能开关"
6. 技术债（**2026-09-29 决策翻案**）：~~旧决策"现有 FeatureFlag 的中文 slug 即持久化 key，i18n 时绝不改名（改名=用户开关状态全丢），需写一次性迁移"~~ 已作废。实测 `shared/FeatureFlag.kt:24-36` 三个中文 slug 的 `defaultValue` 全为 `true`，新基线**一律换英文 slug、不写状态迁移**：代价仅"手动关闭过这 3 个开关的用户开关复位为默认开"，旧中文 key 成孤儿 SharedPreferences 数据、不清理无害；`BTHostSwitch` 本就用英文 `name` 不受影响。新增 flag 一律英文 slug，展示名走 strings。对照见 STATUS.md §一 FeatureFlag 迁移代价表。

---

## 9.2 通知页（Mobi 专有）开关化

证据：Notifications.kt:174-178 对 X2 显示"此型号设备无法显示和清除通知"，通知请求按 API version 过滤后 X2 空返。X2 用户永远看到无用 tab。

决策：
- 形态：FeatureFlag 控制 NOTIFICATIONS 是否出现在底部导航；过滤点仅 Landing.kt:656 一处（`showInNav` 常量 → 常量 && flag 运行时判定）
- **默认开**（无 Mobi 实测，默认关会误伤），X2 用户手动关
- 不做首页通知框（列表内容多、有刷新/清除操作）；不按设备型号自动判定（配对前 setupDeviceName 为空会闪，且自动判定属于改行为）
- 联动点（实现时勿漏）：tab 关闭后 dashboardCommands 中 `NotificationBundle.allRequests()` 一并跳过（减 BLE 负载）、底部 badge 跳过、`selectedItem` 停在通知页时回退（仿 BackHandler 映射）、中间大剂量按钮位置依赖 `navItems.size/2` 需验证

---

## 9.3 控制页 X2 只读策略

实测背景（X2，2026-09-26）：泵状态几乎全部可读；控制写指令仅"确认式大剂量"可用；停止/恢复胰岛素、临时基础率、睡眠/运动模式、配置文件(IDP)、声音、Control-IQ、安全限制均**能读状态不能控制**。

PumpX2 协议事实（D:\Programming\pumpX2，v1.9.1）：
- 每条消息有 `supportedDevices`（ALL / MOBI_ONLY / X2_ONLY，默认 ALL）、`characteristic`（CONTROL 写 / currentStatus 读）、`signed` 三元数据；判定 API：`request.props().supportedDevices().supports(model)`（SupportedDevices.java:25）
- **读与写是独立消息**：操控禁用不影响状态。停止/恢复 ← HomeScreenMirror/CurrentBasalStatus（读，ALL）；临基状态 ← currentStatus 包 TempRateRequest（读，ALL）；模式 ← ControlIQInfo（读，ALL）。actionsCommands 轮询列表全是读取消息
- MOBI_ONLY 写指令（14 个文件）：Suspend/Resume、SetModes、SetTempRate、StopTempRate、SetSleepSchedule、IDP 增删/激活/Rename、FillCannula 等
- **上游注解漏标**：SetPumpSounds、SetMaxBolusLimit、SetQuickBolusSettings、SetPumpAlertSnooze 等在 control 包、CONTROL+signed，但注解默认 ALL（实测 X2 不可用）
- 大剂量（InitiateBolus/BolusPermission 流）在 control 包但 X2 实测可用，是唯一例外

**最终策略（按"实测太少不臆断"原则，放弃"CONTROL 默认全禁"方案）：**
〔2026-11-03 复核〕实现仍是 `canControlPump = model == MOBI`（`SupportedDevicesUtil.kt`），X2 上 CONTROL 全禁，与本定稿不符；待真机实测后按 MOBI_ONLY ∪ 黑名单收窄。

```
X2 上禁用操控 = 满足任一：
  ① 上游注解 supportedDevices == MOBI_ONLY
  ② 实测失败黑名单（仅录亲手验证项；上游补正注解后可删条目）
读取（currentStatus 消息）：永不限制
Mobi：全部不限
```

**固件版本维度（2026-09-26 补充，三点前提）：**
1. pumpX2 基于 t:connect 官方 app 反编译，`supportedDevices` 注解是代码解读推断、非官方数据库，疏漏结构性难免（已实证 5 条漏标）→ 注解语义只作"先验假设"，真机实测才是事实
2. 泵固件有多个版本，当前不支持的指令升级后可能支持 → 能力判定键必须是 **`(泵型号, 固件版本范围, 指令)`** 而非 `(型号, 指令)`；版本未知时按当前实测结论失败关闭；固件升级后需回归重测黑名单
3. 上游主要精力在 pumpX2、缺真机 → 每条实测按统一证据格式记录，将来可直接作为 pumpX2 issue/PR 素材：
   `泵型号 + armSwVersion + bleSwVersion + 请求名/opCode + 时间 + 泵响应(执行/拒绝/静默) + 响应原始 cargo`

**固件版本数据源（协议已有、App 未接）：**
- 历史日志 `VersionsAHistoryLog`：armPartNumber、**armSwVersion（泵主控固件）**、blePartNumber、**bleSwVersion（BLE 固件）**——fetcher 本就在拉，零新增 BLE 请求
- 状态通道 `BleSoftwareInfoResponse`：BLE soft device 信息
- mobile 现状：全代码无 firmware/version 引用，解析后未存未显
- 待办（归 U4 Debug 迁移顺带做）：调试页显示泵固件版本（取 VersionsA 最近一条），作为实测取证工具；能力判定对象读取同一来源
- 当前黑名单 5 条先统一标注"固件版本未知，待补"；下次实测时从 VersionsA 记录补全

置灰后表现（以 Actions.kt 现状为准）：行标题/副文本/图标/底色/Switch 的 checked 全部继续绑定读取字段实时变化，仅点击/拖动/保存禁用。例：泵本体挂起后 App 显示绿色"启动胰岛素"但不可点；临基激活时显示"进行中：80%，持续…"但不可停止。

实测黑名单（截至 2026-09-26，X2 验证失败）：
- SetPumpSounds / SetPumpAlertSnooze（声音设置）
- SetMaxBolusLimit / SetMaxBasalLimit（安全限制）
- SetQuickBolusSettings（快捷大剂量设置）
- ChangeTimeDateRequest（同步胰岛素泵时间，opCode -42，CONTROL+signed，注解漏标 ALL）
  - 历史日志铁证（2026-09-26 20:44 实测）：指令后泵历史无 TimeChangedHistoryLog(op13)/DateChangeHistoryLog(op14)，确认被拒、时间未改
- PlaySoundRequest（查找我的胰岛素泵，opCode -12，CONTROL+signed，注解漏标 ALL；X2 无发声硬件）
  - 位置：Settings.kt:366-398 两行菜单，对话框 490-513/589-612；上游英文原版同样无设备区分
  - UX 问题：Toast 发射即遗忘（"已发送"/"正在播放声音"），泵拒绝也弹成功提示；9.3 改造时 X2 置灰不发
  - 待解：2026-09-26 20:44:35-45 出现未知事件簇 typeId 17×2/22/10/23（PumpX2 未解析），疑似与两次控制写尝试相关，待更多样本；另 20:30:42-44 typeId 193/146 两条一次性未知事件

待实测（注解 ALL，未验证前保持可点）：CGM 会话开始/停止、G6/G7 配对码（CGMActions）；进入/退出换储药器模式（CartridgeActions，FillCannula 已确认 MOBI_ONLY）；提醒类设置。

实现形态（仅设计，预计触碰 Actions.kt + 5 个子页面 + shared）：
1. shared 增能力判定纯函数：`canSend(model, firmwareVersion, message)`、`isReadOnlyDevice(model)`，判据=注解 ∪ 版本化黑名单（黑名单表含固件版本范围列）
2. 统一组件（如 `StatusRow(title,status,editable,onClick)`）收敛 Actions.kt 现有 3 处重复 isMobi/置灰逻辑（L398/L484/L568）；子页面"可进、可看状态、保存按钮置灰"，顶部统一说明复用 Actions 头部提示
3. 后置美化：Actions 行副文本直接显示当前值
4. 发送侧兜底拦截（PumpCommHandler 出口共用 canSend）**暂不做**，等实测面扩大后再议；做时须独立 commit（仅收紧）；若将来实施，必须同时认 L2 覆盖，否则测试模式无法发指令

**分层可覆盖判定模型（2026-09-26 定稿，解决"上游 PR 慢 + 固件升级 + 他人真机测试"问题）：**

| 层级 | 内容 | 维护者 | 性质 |
|------|------|--------|------|
| L0 | pumpX2 库注解 supportedDevices | 上游，随库版本 | 反编译推断的先验假设 |
| L1 | App 内置实测事实表（单文件，含固件版本区间、证据、`pumpx2FixedIn` 字段） | 本项目 | 版本区间内的事实 |
| L2 | 本机测试覆盖（prefs，**不随 App 分发**） | 测试者本人 | 临时、显式、留痕 |

判定优先级：**L2 > L1 > L0**。两个安全阀：
- L1 事实只在固件版本区间内压过注解；区间外（新固件未测）自动失效回退 L0，UI 显示"未验证"，黑名单不永久化
- L2 强制启用仅在调试页"能力测试模式"内可用，普通用户界面无入口

**能力实验室（Capability Lab，归 U4 调试页，不提前做）：**
- 列出全部 CONTROL 指令，每行：指令名 / 当前判定来源（L0/L1/L2）/ 适用固件版本 / [强制测试]
- 强制测试：显式警告"未经验证，泵可能拒绝或产生意外行为"；挂起/恢复等直接影响输注的指令升级为二次确认
- 全程自动记录证据（即 9.3 统一格式：型号+armSwVersion+bleSwVersion+pumpx2 版本+opCode+时间+泵响应+原始 cargo），结果先落本机，**不做静默遥测**
- 任何用户升级新固件后可自行验证，无需改代码、无需等维护者

**毛坯现状（2026-09-26 代码核查）：调试页"发送胰岛素泵消息"就是能力实验室的毛坯，不从零新建页面。**
- 列表来源：`MessageHelpers.getAllPumpRequestMessages()`（pumpX2 MessageHelpers.java:12-20）遍历 Messages 注册表全量请求类，仅硬编码过滤 `authentication.`/`historyLog.`/`FactoryReset(Request|BRequest)`/`Nonexistent`，**零设备维度、零版本维度**
- 发送链路：Debug.kt:402-411 `Class.forName` 反射构造 → triggerGenericPumpMessageDialog → sendPumpCommands → PumpCommHandler 直发；全 mobile 模块搜索 `supportedDevices`/`supports(` **0 处引用**，处理器发送任意消息不读注解不判型号——挂起/恢复、临基、声音、同步时间在 X2 上全部能点能发，被泵静默拒绝
- 更底层后门：CommService.kt:793 `sendDebugWriteBtCharacteristic`（DEBUG_WRITE_BT_CHARACTERISTIC）直接写 BLE 特征值原始字节，绕过消息类封装
- 查看缓存：Debug.kt:432 仅展示 lastResponseMessage 30 秒内存缓存，与版本无关
- 现有安全意识：仅排除 FactoryReset 两类；无发送前警告、无输注类二次确认
- 缺口对照（U4 需补四要素）：①设备/固件版本标注 ②判定来源 L0/L1/L2 显示 ③发送前分级警告（输注类二次确认）④证据自动捕获落报告（现状响应只能眼看，不自动留证）
- 关键设计含义：9.3 的 L0/L1 UI 置灰即使全部完成，**永远拦不住调试发送器这条 L2 通道——这是故意保留的测试逃生口**；但它当前对普通用户裸奔，U4 至少先补警告+输注类二次确认，再逐步补齐另两要素

**开发者功能二级菜单（2026-09-29 定稿，归 U4）：**

原调试页 8 项功能重分配——将"向泵发指令"和"调协议管道"类移入 Settings → 开发者功能子页，"看泵数据"和"管连接"留在原调试 tab：

| 移入开发者功能子页 | 留在原调试页 |
|-------------------|-------------|
| ② 发送胰岛素泵消息（能力实验室毛坯） | ① 禁用服务（Settings 已有等价项） |
| ③ 查看已接收消息缓存 | ④ 获取历史记录 + 自动获取（zh 增项不动） |
| ⑧ 调试通知与 HTTP API（Toast 开关 + HTTP API + 账号密码） | ⑤ 查看历史记录消息（④的伴侣） |
| **Mobi 泵模拟模式（新增）** | ⑥ 仅监听蓝牙（连接共享调试） |
| | ⑦ 查看泵状态 / 清除调试日志 / 清空数据库 |

分界线：向泵发指令和调协议管道进开发者菜单；看泵数据和管连接留原位。

子页结构（用 U1 组件）：
```
设置 → NavigationRow("开发者功能") → 开发者功能子页
  ├─ ToggleRow: Mobi 泵模拟模式 [关]     ← 顶部最显眼
  │   （ON 时全屏横幅警告）
  ├─ ─── 分隔线 ───
  ├─ ToggleRow: 合格事件 Toast           ┐ 原⑧拆开
  ├─ ToggleRow: 泵消息 Toast              │
  ├─ ToggleRow: HTTP Debug API           │
  │   └─（启用后展开）账号/密码输入       ┘
  ├─ NavigationRow: 发送胰岛素泵消息 → 原② dialog 流程
  └─ NavigationRow: 查看已接收消息缓存 → 原③ popup
```

设计判断：
1. 不隐藏入口——App 用户本身就是泵深度使用者，开发者功能对半数人有用，"开发者功能"明确命名即可
2. 发送泵消息移入后补安全护栏（9.3 四要素）归属更自然：路径"设置 → 开发者功能 → 发送泵消息"本身就传达"这不是日常操作"
3. HTTP Debug API 跟着移——本质是"暴露内部消息管道供外部工具调用"，和发送/缓存同一类能力
4. 原调试页只减重不拆——①④⑤⑥⑦ 的重新分配是以后的事

**Mobi 泵模拟模式（开发者功能子页首项，2026-09-29 定稿）：**

目的：在 X2 上调试所有 Mobi 专属功能的 UI 表现，无需 Mobi 泵。

核心设计——一个 Prefs 开关 `debugMobiSimulation`，作用于 CapabilityRegistry 最高层：
```
canSend(opcode)
   ↓
debugMobiSimulation == true? → return true（跳过 L0/L1/L2 全部判定）
   ↓ 否
L0 库注解 → L1 版本事实表 → L2 本地测试覆盖
```
开关 ON 时所有 MOBI_ONLY 指令在 UI 上显示可用——控制页暂停/恢复不置灰、设置页 Mobi 专属项可进可改、同步时间/查找泵不灰禁。

视觉警告（防忘记关闭）：
- 开关行用 warning 色调（非 error 色）
- Dashboard 顶部横幅："调试模式：模拟 Mobi 泵。实际连接的泵仍为 X2，发送 Mobi 专属指令将被泵拒绝。"
- 横幅只在开关 ON 且有泵连接时显示

发送行为：允许发但发送前弹警告"Mobi 专属指令，X2 将拒绝"——开发者需走完整发送流程测试 UI，但要有提醒。与 9.3 能力实验室"发送前警告"共用同一机制。

通知页模拟：Mobi 通知/annunciation 体系与 X2 可能有差异（不同 opcode、不同告警类型）。模拟模式只能做 UI 层面——显示"Mobi 专属通知占位项"测试渲染，不能伪造泵端实时推送数据。

实施依赖：
- 依赖 9.3 CapabilityRegistry——没有能力判定层，开关没有注入点
- 应成为 CapabilityRegistry 的第一个消费者和验证场景
- 同时是 9.3 黑名单测试工具：打开模拟后逐个发 MOBI_ONLY 指令，看 X2 实际响应（拒绝/静默/接受），结果直接喂回 L2 本地覆盖表——比"真机实测"更安全，因为清楚知道在模拟模式下

**发送器指令三层分类（2026-09-29 定稿）：**

当前硬编码隐藏 FactoryReset 两类，改为按注解分三层展示+分级警告。52 个 CONTROL 请求完整分类（注解来源：pumpX2 `@MessageProps.supportedDevices`）：

| 层 | 条件 | 数量 | UI 呈现 | 发送前提示 |
|----|------|------|---------|-----------|
| Tier 1 常规 | supportedDevices=ALL（默认） | 38 | 无标注 | 无 |
| Tier 2 Mobi 专用 | supportedDevices=MOBI_ONLY | 12 | badge "Mobi 专用" | 无（badge 即提示） |
| Tier 3 超级危险 | 硬编码危险名单（FactoryReset×2） | 2 | 红色 badge [⚠] + 默认折叠 | "此操作有损坏胰岛素泵的风险，无法撤销。后果自负。" |

Tier 1 完整清单（38）：ActivateShelfMode, AdditionalBolus, BolusPermission, BolusPermissionRelease, CancelBolus, CgmHighLowAlert, CgmOutOfRangeAlert, CgmRiseFallAlert, ChangeControlIQSettings, ChangeTimeDate, DisconnectPump, DismissNotification, EnterChangeCartridgeMode, EnterFillTubingMode, ExitChangeCartridgeMode, ExitFillTubingMode, InitiateBolusRequest, PlaySound, PrimeTubingSuspend, RemoteBgEntry, RemoteCarbEntry, SendTipsControlGenericTest, SetAutoOffAlert, SetBgReminder, SetDexcomG7PairingCode, SetG6TransmitterId, SetIDPSegment, SetIDPSettings, SetLowInsulinAlert, SetMaxBasalLimit, SetMaxBolusLimit, SetMissedMealBolusReminder, SetPumpAlertSnooze, SetPumpSounds, SetQuickBolusSettings, SetSensorType, SetSiteChangeReminder, StartDexcomG6SensorSession, StopDexcomCGMSensorSession, StreamDataPreflight, UserInteraction

Tier 2 完整清单（12）：CreateIDP, DeleteIDP, FillCannula, RenameIDP, ResumePumping, SetActiveIDP, SetModes, SetSleepSchedule, SetTempRate, StopTempRate, SuspendPumping

Tier 3 完整清单（2）：FactoryResetRequest, FactoryResetBRequest

UI 结构（分组折叠，用 SettingsGroup）：〔2026-11-03 复核〕`SettingsGroup` 已删除（零调用，且实现的是 09-28 已否决的折叠方案）。
```
发送胰岛素泵消息
 ├─ 常规指令 (38)          ← Tier 1，无标注
 ├─ Mobi 专用 (12)          ← Tier 2，badge 标注；模拟模式 ON 时 badge 变灰
 └─ ⚠ 危险操作 (2)          ← Tier 3，红色 badge + 默认折叠
```

设计原则：
1. **不再隐藏任何指令**——隐藏是 security through obscurity，开发者需要完整可见性；改用分级警告
2. **三层分类是发送器 UI 的静态展示**，来自库注解 L0；9.3 CapabilityRegistry 的 L1/L2 动态判定是另一维度——两者正交：发送器列表不做运行时过滤（否则模拟模式下 Tier 2 消失就看不到了），只在 badge 上反映模拟状态
3. **Tier 2 badge 不弹警告**——badge 本身就是提示；Mobi 模拟模式 ON 时 badge 变灰表示"模拟下可用"，OFF 时 badge 醒目表示"X2 将拒绝"
4. **Tier 3 是唯一弹警告的层**——"有损坏泵的风险，后果自负"，确认后才进入参数 dialog
5. X2 黑名单（ChangeTimeDate/PlaySound/SetPumpSounds/SetMaxBolusLimit/SetQuickBolusSettings）注解是 ALL→Tier 1，发送器不拦（发送后泵静默拒绝，用户自然知道）；运行时拦截由 9.3 CapabilityRegistry 在正常 UI 路径做

**证据中转与提交：**
- App 内生成标准化报告（Markdown/JSON，预填 pumpX2 issue 模板），走系统分享（微信/邮件/复制）；有网络条件者可一键跳 pumpX2 issue
- 用户报告发给本项目后：证据充分的整理为 pumpX2 issue/PR 上游；确认结论进下一版 L1 事实表
- 维护者定位=证据聚合者：多台不同固件的 X2 即数据集

**升级 pumpX2 依赖时的 L1 对账（人工 diff，约 10 分钟）：**

| 情况 | 处理 |
|------|------|
| 上游注解已改 ∧ 与实测一致 | 删 L1 条目，交回 L0 |
| 上游注解已改 ∧ 与实测冲突 | 保留 L1，带证据找上游（反编译推断≠真机） |
| 新固件超出 L1 区间 | 条目自动失效，显示"未验证"待测 |

L1 单文件数据表 + L2 仅 prefs，均不散落业务代码，与上游合并冲突面最小；L1 字段结构首期即按本模型建（版本区间/证据/pumpx2FixedIn/obsolesceAction），避免返工。

安全边界：测试模式显式开关+每次警告，不做常年开启的开发者选项；普通用户永远走 L0/L1 安全默认；L2 只存活于测试者本机。

**上游现状核查（2026-09-26，本地仓库 main=dad3eea / dev=ab53ef1）：**
1. **VersionsA 字段在 v1.9.1 刚落地**：commit 9cdea743（fix #79）解析 armPartNumber/armSwVersion/blePartNumber/bleSwVersion——本项目构建用的正是 1.9.1，U4 固件版本显示数据源开箱可用；v1.9.1 还批量补了告警/大剂量/CGM 会话等历史日志字段（fix #37–#85，Claude 辅助），dev 在继续做历史日志+JPAKE 快速配对
2. **README 官方背书**：明文 "Non-bolus control features: these are not supported in current t:slim X2 firmware"——X2 非大剂量控制失败关闭是与上游一致的正确默认，非 workaround；漏标 ALL 的注解在此口径下确属注解 bug，将来提 PR 可直接引用此句。社区渠道已改为 Discord **#tslim-x2-dev**（旧 #tandem-talk 废弃）
3. **上报前先查最新库**：dev 有大量 "add Mobi fixtures from observed BLE capture" 类提交，我们抓到的未知 typeId（219/227/263/264）可能已在新版解析；证据中转流程强制第一步=升级最新 pumpx2 确认仍未知，再报上游，避免重复 issue

**普通用户三级参与模型（2026-09-26 定稿）：**
上游接受贡献的最小单位是"observed BLE capture"原始报文（用户供数据、上游/Claude 解码，#37–#85 全此模式），普通用户不需懂协议。

| 级别 | 用户动作 | 门槛 | 产出 |
|------|---------|------|------|
| L1 零门槛 | Debug 页一键打包诊断包（型号+armSwVersion+bleSwVersion+App/pumpx2 版本+未知消息原始字节+能力测试结果），系统分享发给维护者 | 点一下 | 维护者聚合翻译后提 pumpX2 issue/Discord |
| L2 进阶 | 用预填英文模板自行提 GitHub issue 或 Discord（附诊断包） | 需梯子 | 直接对话上游 |
| L3 测试者 | 能力实验室 L2 通道验证新固件控制项支持情况 | 显式警告+知情同意 | 版本化能力事实，驱动 L1 表更新 |

- 维护者定位=中文 X2 用户社区的**证据聚合者+翻译层**，用户零英文零 GitHub 要求
- 全部被动收集（数据本在用户设备内），不做静默上传/遥测；L3 控制测试永远警告+输注类二次确认
- 反馈闭环：更新日志注明贡献者（"感谢 XX 提供 X2 固件 x.x 报文"），上游 Claude 批量解码反馈快，参与者有成就感是社区转动的关键

远期 PR 素材：实测坐实后给 PumpX2 提纯英文 PR 补正漏标的 MOBI_ONLY 注解（带实测证据+引用 README X2 条款，比 UI PR 易接受）。

---

## 9.4 专项：临基起始时间不显示（X2，已定位）

现象：临基激活时 Actions 行显示"进行中：80%，持续 2h30m，开始于"——"开始于"后为空。

排查结论（2026-09-26，App/库/历史日志三方核对）：
- 非 App 映射问题：MainActivity.kt:944-946 整个 TempRateResponse 直存
- 库事实：TempRateResponse（opCode 43，10B）`getStartTimeInstant()` 对 startTimeRaw 做固定纪元换算（2008-01-01 起），**永不返回 null**；raw 为 0 时应输出 `2008-01-01T00:00:00Z`
- UI 问题（确定存在）：Actions 临基行 ListItem 用 `Modifier.height(72.dp)` 固定高度，中文副文本折 3 行后第 3 行（时间）被裁；英文原文短不触发，属汉化宽度问题
- 数据问题（历史日志佐证）：X2 在 opCode 43 不填起始时间；但历史日志有 `TempRateActivatedHistoryLog`（opCode 2，"Temp Basal Rate Activated"），含 pumpTimeSec（=启动时刻）、percent、duration、tempRateId。2026-09-26 抓取的 50 条窗口（11:38–12:23）内临基全程激活（BasalDelivery 每 5 分钟：tempRate=200%，commandedRateSource=2；12:00:23 跨基础率时段 profile 0.925→0.85、临基跟随 1.85→1.70，BasalRateChange changeTypeId=1=TIMED_SEGMENT），启动事件在窗口外更早
- App 已有 HistoryLogFetcher + Room 全量历史，数据现成

修复方案（未实施）：
1. 临基 active 时从历史库查最近一条 opcode 2，pumpTimeSec 作启动时间；43 消息 startTimeRaw 非 0 时优先用消息值（Mobi 路径），为 0 回退历史库
2. `height(72.dp)` → `heightIn(min = 72.dp)`（停止胰岛素行同样检查）
3. 时间用现有 `pumpTimeToLocalTz`/`shortTime` 转本地格式，不显示原始 UTC `...Z`

---

## 9.5 专项：停止胰岛素（X2，截图确认 2026-09-26）

实测截图结论（tslim X2 ***627）：
- 正常输注：红底"停止胰岛素"；泵本体挂起后：绿底"启动胰岛素"——**挂起状态识别/映射正常**（basalStatusIcon=SUSPEND 链路工作），无需修映射
- 缺陷 1（安全，只读策略漏网）：Actions.kt:268 挂起/恢复行 `.clickable{}` **无 isMobi 守卫**（运动/睡眠/临基三行都有，唯独此行漏）；确认按钮只看 resumeGuidance.canResume 不看设备。X2 上实际可：点行 → LoadStatusRequest（读取，成功）→ 对话框解禁 → 点"恢复胰岛素"真发出 ResumePumpingRequest（MOBI_ONLY）→ 泵静默拒绝。9.3 统一改造时此行必须加 enabled=isMobi，且安全相关应优先处理
- 缺陷 2（信息缺失）：挂起后绿行无挂起时长/时刻。数据源：历史库 opcode 11 PumpingSuspendedHistoryLog（pumpTimeSec=挂起时刻、reasonId：USER_ABORTED/ALARM/MALFUNCTION/AUTO_SUSPEND_PREDICTIVE_LOW_GLUCOSE）+ PumpingResumedHistoryLog（恢复时刻，可算总时长），方案同 9.4
- 历史日志链路（已核库）：PumpingSuspendedHistoryLog opCode 11、PumpingResumedHistoryLog 对应；2026-09-26 的 50 条窗口内无挂起事件（输注全程未中断），事件在窗口外

---

## 9.6 UI 统一与皮肤架构（2026-09-26 讨论定稿，未实施）

**动机**：Settings / Actions / Debug 三页对同一语义用了 3+ 种写法，且 Actions 内部也不统一。

现状不一致硬伤（实证）：

| 语义 | Settings | Actions | Debug |
|------|----------|---------|-------|
| 开关 | 无 Switch，点整行翻转 | 行尾真 Switch（运动/睡眠 L422/508） | 行尾 Check/Close 图标伪装开关 |
| 导航子页 | ListItem+clickable，默认行高 | ListItem+clickable，72dp | ListItem，80dp/默认混用 |
| 危险操作 | AlertDialog | 红绿整行染色（Color.Red/Green.copy(0.5) 硬编码 L264-265）+ AlertDialog 套 DropdownMenu | — |
| 禁用态 | 无概念 | 手写 alpha=0.38 染文字图标 与 Switch(enabled=false) 并存 | 无 |

其他：激活态用 14sp↔18sp 字号编码（L242/412）；颜色不走 MaterialTheme 语义槽（深色/换肤必崩）；Divider 各行手写有无不一。

**架构定稿：三层分离**

```
元素规格（what，Kotlin DSL，类型安全）   渲染器（how）              皮肤/布局（look）
sealed ControlElement                   Material3 列表 renderer    主题语义槽（禁 Color.Red 等硬编码）
 · Toggle                               大卡片 renderer（远期）     行高/间距/分隔线统一配置
 · Navigation                           紧凑 renderer（wear 远期）
 · Action(dangerLevel)
 · Status(只读值)
 · Group
首页卡片 = 同模型下另一族 CardElement（9.1 开关接同一机制）
可见性/能力：FeatureFlag（9.1/9.2）谓词 + X2 只读策略（9.3）canSend 谓词
```

决策记录：
- 元素声明用 **Kotlin DSL，不用 JSON 配置**（该规模下 JSON 驱动属过度设计）。旁证：上游 wear 端 WearAppTheme 已内置多套调色板（default/green/red 等），原作者本就有多皮肤意图——语义槽 + renderer 分离与之同源，手机端是把这套思路补上
- 页面 = 元素清单 + 处理函数映射，不再手写散 ListItem
- 换布局风格 = 换 renderer；按泵订制 = 挂可见性/能力谓词（同一份页面定义，X2 得只读行、Mobi 得可操作行），**不靠 fork 页面**
- 元素标签**第一天起走 stringResource**；组件层必须与 i18n 同批或其后建，避免返工（具体写法随多语 Issue 回复定）
- **U6 皮肤切换不做**：U1-U5 只保证颜色全语义化、结构可换；第二套 renderer/皮肤待全部迁移完成后
- 归层：**local/customizations**（改上游 UI 结构，是将来上游同步冲突主战场）

风险与对策：上游高频改 UI（dev 近期提交几乎全是 UI 调整）→ 组件层做薄、只做加法（新文件放 mobile 内部包〔2026-11-03 复核〕实际为 `com/pumppan/controlx2/ui/`，旧页面原样保留）、一屏一屏迁移、新旧长期共存，禁止大重写。验证：mobile 编译 + Roborazzi EN/ZH 双截图 + X2 日常使用。

分期：

| 期 | 内容 | 依赖 |
|----|------|------|
| U1 | mobile 内部包元素组件（〔2026-11-03 复核〕实际落点 `com/pumppan/controlx2/ui/`，6 件）：ActionRow/ToggleRow/NavigationRow/StatusRow（0 调用）/RowStyle + 统一 enabled/readOnly 外观、语义色、统一行高；l10n 只放共享字符串+枚举映射 | 无 |
| U2 | Settings 页迁移（纯机械，立样板） | U1 |
| U3 | 9.3 只读能力对象接 enabled 谓词；Actions 迁移（顺手消灭手写 alpha、红绿硬编码、72dp 裁剪；含 9.5 挂起行守卫，安全项最优先） | **已完成（2026-11-03 复核）**（原依赖 U2；仅 ActionConfirmDialog/DangerLevel 未做） |
| U4 | Debug 伪开关全部转 ToggleRow；统一 80dp；**开发者功能二级菜单（移入②③⑧+Mobi 模拟模式，见 9.3 U4 细则）** | U3 |
| U5 | Dashboard 卡片元素化 + 9.1 开关接入 | U3 |
| U6 | 第二套 renderer/皮肤（远期，触发条件：U1-U5 完成） | U5 |

迁移顺序理由：Settings（纯导航最简）→ Debug（学伪开关转正）→ Actions（Switch/危险操作最复杂）→ Dashboard 卡片。

**危险操作呈现规则（停止胰岛素/临时基础率，2026-09-26 定稿）**

背景：上游把"状态指示"和"动作召唤"焊在同一行——红色/绿色整行大色块常年挂着，红色在视觉语义上是 CTA，诱导误点（用户原话：醒目红底忍不住想点）。

定稿规则：
1. **X2 上这两项就是纯提醒**：App 无法激活/停止（MOBI_ONLY 写），行只承担状态展示，不给任何操作暗示；未激活=灰色常规，激活=正常色加粗+副文本显示状态数值，不靠红绿色块
2. **列表行永远中性色**（Mobi 同样适用）：可点有 ripple 即可，危险感不提前到列表层；**红色只用在确认对话框的危险确认按钮**——用户已进入"我要执行"上下文时红色才正确
3. **激活态统一为"进行中"语义**：加粗标题 + 副文本数值/时长（+远期小状态徽标），消灭挂起绿底、临基 Cancel 图标各搞各的现状
4. **三套属性正交**：readOnly 谓词管能不能点（9.3）、dangerLevel 管确认时多严肃（对话框）、激活态 Status 管怎么显示——互不串味
5. **防误触间距保留但改法**：zh 已把两行高度从上游默认 ~56dp 加宽到 72dp（Actions.kt:268、604），方向保留；U3 落地时用 `heightIn(min = 72.dp)` 替代固定 `height(72.dp)`——既保留肥大触控目标防误触，又解决 9.4 中文三行被裁剪问题
6. 9.5 的挂起行 isMobi 守卫是本规则的前置安全修复，U3 最优先

**能力注册表（CapabilityRegistry，9.3 落地前置件，2026-09-26 定稿）**

解决"每个子菜单各自重复执行 canSend 判定"：判定提升到数据层、预算一次。
- 形态：`CapabilityRegistry.snapshot: Map<指令, 判定结果(来源L0/L1/L2 + 可用否)>`；型号/固件版本变化（连接成功、VersionsA 到达）事件驱动重算，非每屏重算
- 各页面/展开组/子页只做 Map 查找，保证同一指令跨页显示一致，消除 A 页灰 B 页可点
- 无论子项就地展开还是全屏子页，消费同一份预算结果；9.3 落地前必须先有它

**设置组容器形态（2026-09-28 讨论定稿，修正 2026-09-26 "X2 就地展开 / Mobi 进子页"割裂方案）**

**核心原则：交互模式由内容复杂度决定，不由设备能力切换。** 同一个功能在 X2 和 Mobi 上交互方式相同，差异只在"可操作 vs 只读"。泵不会凭空新增功能，2 种主要交互（就地展示 + 子页）足以覆盖全部设置项。

三类处理：

| 类别 | 交互 | X2 表现 | Mobi 表现 | 包含项 |
|------|------|---------|-----------|--------|
| **简单开关/状态类** | 就地展示（不跳转） | 只读显示当前状态，控件置灰 | 可操作 | 启停胰岛素、运动模式、睡眠模式、临时基础率 |
| **复杂设置类** | 统一进子页 | 子页内所有设置项置灰，顶部提示"仅支持查看" | 可编辑 | 声音、安全限制、basal 时间表、Control-IQ、CGM 会话、换储药器、IDP 管理 |
| **只发不管类** | 灰色禁用行（无开关、不进子页） | 置灰，不可点 | 可点执行 | 同步胰岛素泵时间、查找我的胰岛素泵 |

设计理由：
- **简单类就地展示**：启停/模式/临基是用户最关心的实时状态，一进控制页就该看到，不需要跳转
- **复杂类进子页**：声音/安全限制/时间表等本身就是多字段配置，Mobi 已有子页流程，X2 复用同一子页加只读分支即可，不另造"就地展开只读"形态
- **只发不管类灰禁**：同步时间、查找泵是典型 CONTROL 指令"发射即遗忘"，X2 不支持且无状态可展示，连开关都不需要做，灰色禁用最合理

DSL：`SettingsGroup(title, capability, children, flowContent)` 一份声明，renderer 按**内容类别**选形态（而非按设备）：
- 简单类 → 就地渲染 ToggleRow/StatusRow（readOnly 由能力谓词驱动）
- 复杂类 → NavigationRow 进子页（子页内容由 flowContent 提供，readOnly 控制置灰）
- 只发不管类 → NavigationRow(disabled=true) 置灰

容器/内容分离原则：上游改子页具体内容时只更新 children 元素，容器（就地/跳转）是本地一层声明，冲突收敛在清单文件。归入 local/customizations，排 U3 之后（依赖 U1 元素组件 + CapabilityRegistry）。

---

## 9.7 本地 UI 调整清单（2026-09-26 定，未实施，先不动上游）

| # | 调整 | 从 | 到 | 备注 |
|---|------|----|----|------|
| 1 | "同步胰岛素泵时间"、"查找我的胰岛素泵"两项 | 设置页（Settings.kt:366-398） | **控制页底部、返回键上方** | X2 上本就只读（9.3 黑名单），归入控制语义更合理；迁移时按 9.6 ActionRow/NavigationRow 统一元素呈现 |
| 2 | 新增"清空历史记录缓存"项 | — | **调试页，置于"清空数据库"上方** | 对应 bug-调试页历史缓存未随数据库清空.md 修复设计：清 PumpCommState.historyLogCache + fetcher.reset() + UI cache；清空数据库项本身也要补齐清缓存（或两项合并语义，实施时定） |

约束：两项都是 zh 本地调整（local/customizations 层），不向上游提；实施时机随 U2/U3/U4 页面迁移，不单独立刻改。
