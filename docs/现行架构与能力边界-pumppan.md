# pumppan 现行架构与能力边界

> 状态：**现行正本**（2026-11-13 起）
> 取代：`docs/old/泵能力收敛与自建挡板方案_main.md`（2026-11-04，定位为“上游补丁 + 挡一下”，已被现状推翻）
> 锚点：一律写「文件 + 符号名」，不写行号。阈值/时长/次数一律以配置为准，本文只写默认值。
> 事实基准：pumppan 分支（2026-11-13），pumpx2 `1.9.3`；上游 controlX2 主线停在 `45e3795b`（2026-05-04）。

---

## 一、一句话现状

pumppan 已经不是“上游 + 几个独立功能”，而是**以上游 UI 为壳、以 pumpx2 为协议库的独立客户端**：自 11-03 起 113 个提交、`com.pumppan` 87 个 Kotlin 文件、`pumpcomm` 13 个重度改写文件；上游半年多零提交，等不到能力门控，也不该再按“补丁分支”管理。

真正的风险不再是“上游有没有能力表”，而是**层与层之间没有显式契约**：UI 直接拼消息路径、直接读 DataStore 字段；产品域直接 import 协议层内部类；能力判断散落 18 处。

---

## 二、四层结构

| 层 | 位置 | 职责 | 归属 |
|---|---|---|---|
| 1 上游壳 | `mobile` / `wear` 的 `com.jwoglom.controlx2.presentation`、i18n 资源、`MainActivity` | 页面、导航、Compose 组件、多语言 | 上游为主，逐页改造 |
| 2 契约面 | `shared`：`MessagePaths`、`CommServiceCodes`、序列化器、`PumpCommandPort`；`pumpcomm`：`CommServiceCallbacks` | 设备间消息、进程间命令、发送闸门、回调边界 | **我们** |
| 3 协议链路 | `pumpcomm`：`PumpSession`、`PumpCommHandler`、`PumpCommState`、`BleLinkPolicy`、`BleLinkClock`、`CommandRateLimiter`、`PumpCriticalErrorClassifier`、`PumpHistoryLogFetcher` | 连接/认证/重连、命令节流、错误分类、历史日志 | **我们**（包在库外） |
| 4 产品域 | `mobile` 的 `com.pumppan.controlx2`：`query`、`bolus`、`ledger`、`diag`、`scheduled`、`ui` | 查询调度、大剂量 V2、流水账、诊断、定时大剂量 | **我们** |

依赖方向应当是 4 → 2 → 3，且 4 不得 import 3 的内部类。现状有越界：`com.pumppan.bolus.*` 直接使用 `pumpcomm` 的 `PumpCommHandler` 暴露对象与消息路径字符串；`pumpcomm` 反过来 import `shared` 的 `PumpCommandPort`（这一条是允许的，port 属契约面）。

---

## 三、契约面现状（四张表）

### 3.1 消息路径 `MessagePaths`

按角色分四组，共 40+ 常量：

| 前缀 | 语义 | 典型用途 |
|---|---|---|
| `/to-server/` | 发给“管泵那台设备”的命令 | 启动/停止服务、配对码、发现器、重连重启、大剂量请求 |
| `/to-client/` | 发给“非管泵那台设备”的事件 | 连接态、血糖单位、大剂量被拒、向导同步 |
| `/to-pump/` | 发给泵的命令 | 普通命令、批量、绕缓存、调试裸发 |
| `/from-pump/` | 来自泵的事件 | 连接/断开、型号、配对码、严重错误、历史日志 |

**稳定面**：上述路径字符串本身（手机与手表两版 APK 通过路径通信，改动等于协议变更）。
**内部实现**：payload 的序列化格式（`PumpMessageSerializer` 等），以及 `ErrorPresentation` 这类 JSON 结构的字段。

### 3.2 服务码 `CommServiceCodes`

枚举**只能追加、不能插队**：手机与手表两版通过 ordinal 交换，插队会让旧版把新码解成别的操作。已有注释写明，新增码一律加在末尾。

### 3.3 状态中枢 `DataStore`（mobile / wear 各一份）

`MutableLiveData` 集合，按域分组：

| 组 | 字段举例 |
|---|---|
| 连接 | `pumpConnected`、`pumpLastConnectionTimestamp`、`watchConnected` |
| 配对 | `pumpSetupStage`、`pumpFinderPumps`、`setupPairingCodeType`、`pumpSid`、`setupDeviceModel` |
| 泵状态 | `batteryPercent`、`iobUnits`、`cartridgeRemainingUnits`、`notificationBundle` |
| 大剂量 | `bolusDelivering`、`extendedBolusActive`、`lastBolusStatus`、`iobUpdatedAtMillis` |
| 错误 | `pumpCriticalError` |

**稳定面**：字段名与类型（UI 与手表镜像都依赖）。
**内部实现**：写入时机与刷新频率（归查询层管）。

### 3.4 服务回调 `CommServiceCallbacks`

`pumpcomm` 与 mobile 的唯一反向出口，按用途分组：通知/Toast、偏好读写、外部分发（Nightscout/xDrip/表盘）、调试 API、大剂量广播、历史日志工厂。

**稳定面**：接口方法签名。
**内部实现**：mobile 侧的实现类（`CommService` 内部匿名实现）。

### 3.5 发送闸门 `PumpCommandPort`（已存在，属契约面）

统一回答“发不发”：单飞（同 opCode 在飞拒绝）、超时重发、节流、去重窗口、全局在飞上限、重连静默窗、链路三态（`LIVE`/`SUSPECT`/`EDGE`）。**不管“发什么”**——请求内容仍由调用方决定。

---

## 四、链路状态机（现行）

| 阶段 | 触发 | 库侧行为 | 我们的动作 | UI |
|---|---|---|---|---|
| 正常 | 已连接 | — | 命令经 `PumpCommandPort` 下发 | 正常 |
| 断开 0–2 min | `onDisconnectedPeripheral` | 仅排**一次** `immediateConnectToPeripheral`（延迟 `reconnectDelay`，随后 ×1.5、无上限）；同时挂 OS 级 autoConnect + 定向扫描 | 起看门狗，写 `BleLinkClock` 断联起点 | 沿用 “Disconnected, reconnecting…” |
| 2–15 min | 看门狗 `hintMs`（默认 120 s） | autoConnect 从注册到接通的实测窗口 **122 s–928 s**，可能一直不 latch | 只记录事件 `reconnect_hint stage=2m`，**不干预** | “已 N 分钟无法连接泵，正在自动重连（该方式最长约 M 分钟）” |
| 15–30 min | 看门狗 `hintMs`→`giveUpMs` 之间的 `reconnectHintMs`（默认 15 min） | 库无超时、无加码；原 autoConnect 仍可能接通 | 补**一次** `startScan()`（1 s 后重跑 autoConnect + 定向扫描） | “泵已 M 分钟无法连接，请开关一次手机蓝牙…” |
| ≥30 min | `giveUpMs`（默认 30 min） | 无 | 停看门狗、不再自动重试，等用户 | “自动重试已停止…按「继续」” + 按钮 |
| 用户点「继续」 | `RESTART_PUMP_RECONNECT` | — | `restartReconnectFromUser()`：重置 `BleLinkClock`、重新起表、立即 `startScan()` | 立刻回到“正在自动重连” |

三条必须记住的事实（源码依据 `TandemBluetoothHandler`）：

1. `onDisconnectedPeripheral` 只 `postDelayed` **一次**重连；泵不可达且无新断链事件时，**不会再排下一次**——这是 11 分钟零动作的机制。
2. `immediateConnectToPeripheral` 对已绑定泵**同时**挂 autoConnect 与定向扫描，谁先到用谁；注释实测 122 s–928 s 是**等待区间**，不是“每 2–15 分钟重试一次”。
3. 每次重连成功都跑 JPAKE；泵索码时用已存配对码自动配对，成功后通信密钥轮换为 JPAKE 派生值，**配对码本身不变**。

阈值全部来自 `BleLinkPolicy`（外置 JSON 优先，代码默认兜底；`hintMs` 下限 60 s、`giveUpMs` 下限 300 s）。

---

## 五、查询域与节流

- **唯一登记处**：`QueryDomainRequests.forDomain(domain)`。只登记只读请求；控制/签名/取消类不进表。未登记的域返回空，协调器跳过，不发空包。
- **协调器**：`QueryCoordinator`（mobile）——到期判定、合并批次、按优先级下发、链路不可用时冻结；`Mode.SHADOW` 只算不发，`ACTIVE` 真发；`activeDomains` / `dispatchedDomains` 逐域迁移，默认仅 `PUMP_VITALS`、`IOB`。
- **策略**：`shared/assets/query-policy/`，加载顺序 `base → <platform>/<model>` 覆盖层，缺层/坏层回落 base，最后代码兜底。策略只描述“多久发/保底多久/超了怎么办”，不表达“发什么、能不能发”。
- **域清单**：保底 `PUMP_VITALS`、`IOB`、`BASAL`、`BOLUS_LAST`、`CGM`、`CONTROL_IQ`、`HISTORY`；页面域 `DASHBOARD_DETAIL`、`DASHBOARD_CGM`、`PUMP_STATE`、`NOTIFICATIONS`、`CARTRIDGE`、`SAFETY_LIMITS`、`PUMP_GLOBALS`、`CGM_STATUS`、`CONTROL_IQ_SETTINGS`、`PROFILE`；一次性 `BOLUS_PRECHECK`、`BOLUS_OUTCOME`。

---

## 六、能力边界

### 6.1 现状（三层判断，互不知情）

| 层 | 机制 | 覆盖面 |
|---|---|---|
| 总闸 | `SupportedDevicesUtil.canControlPump(model)` = `model == MOBI` | X2 全部控制关闭（产品决定，非缺陷） |
| 消息级 | `supportsDevice(model, Message::class.java)` 读 `@MessageProps.supportedDevices` | 逐请求收窄，约 26 处 `MOBI_ONLY` |
| 数据级 | `assets/pump-capabilities.json` + `PumpCapabilities` | 大剂量阈值/限制/编码，服务 BolusLimits、WizardConfig、ScheduledBolusConfig |

**缺口**：没有统一的 `(model, apiVersion) → capability` 表；18 处 `canControlPump` 调用各自判断；`apiVersion` 虽已取到（`PumpState.getPumpAPIVersion`），但不参与门控。

### 6.2 目标形态（B 阶段落地）

```kotlin
// 形状示意，非最终签名
enum class Capability { SUSPEND_RESUME, SET_TEMP_RATE, SET_MODES, SET_SOUNDS,
                        SET_CONTROL_IQ, SET_SAFETY_LIMITS, CARTRIDGE_MODE,
                        CGM_MANAGEMENT, PROFILE_EDIT, NOTIFICATION_DISMISS }

object CapabilityTable {
    fun allows(model: KnownDeviceModel?, apiVersion: ApiVersion?, cap: Capability): Boolean
}
```

规则：

1. **默认拒绝**：表里没有的组合一律 `false`。
2. **只收紧不放开**：`canControlPump(model)` 仍是总闸，表只能在总闸之内再收。
3. **首版不纳 apiVersion**：X2 全禁、MOBI 按现状放开；`apiVersion` 参数保留但不参与判定，等出现“同型号不同 API 版本行为不同”的实测反例再启用。
4. 放开某一项时**只改表**，不改 UI；回退同理。

### 6.3 验证要求

任何从 `false` 改成 `true` 的能力，必须真机发一次写请求并**读取响应**（`SetPumpSoundsResponse` 目前全程未被读取，是“泵拒绝但界面无反馈”的静默拒绝样本）。X2 与 Mobi 各跑一轮并留档。

---

## 七、已知缺口（按风险排序）

1. **UI 直连路径字符串**：mobile 内 `MessagePaths` 引用 132 处，页面拿 `(String, ByteArray) -> Unit` 直接拼路径，改一个路径要全仓搜。
2. **能力判断散落**：18 处 `canControlPump`，新增页面会再长一处。
3. **冷启动即断链**：没有 `onDisconnected` 回调时后台看门狗不起表（UI 三档仍可用，因为 `BleLinkClock` 持久化兜底），但 15/30 分钟的后台补挂与停手不会发生。
4. **库结构性缺陷**：单次重连、退避无上限、autoConnect 空等——我们在 App 层用四件套（policy / clock / watchdog / diag）包住它，成本会继续增长。
5. **无 BLE 仪器化测试**：383 条 JVM 单测全绿，但重连时序只有压缩阈值的真机验收，没有可重复的自动化。

---

## 八、路线

### B｜立契约层（先做）

| 步骤 | 内容 | 判据 |
|---|---|---|
| B1 ✅ 2026-11-13 | `CapabilityTable` + `canControlPump(model, cap)` 重载，迁移 18 处调用点 | 行为零变化：X2 仍全禁、Mobi 仍按现状；`CapabilityTableTest` 5 条通过，`:mobile:assembleRelease` 通过；真机 X2 复验控制页、储药器、配置文件三处仍为“不支持”，点击无响应 |
| B2 | `PumpGateway`：UI 只经它发命令（`sendCommands` / 发配对码 / 重连重启 / 服务启停 / 发现器），不再自己拼路径 | 页面只依赖网关方法名；新增路径不改 UI |
| B3 | `QueryPort` 门面：页面只 `subscribe(domain)` / 读快照，不碰 `QueryCoordinator` 与策略 | 页面不再出现 interval 字面量 |

先做 B1 的理由：改动面最小、可单测、能立刻止住“能力判断继续扩散”；B2 需要逐页改签名，按页面推进，不一次性铺开。

### C｜把重连调度抽成可注入策略（B 之后）

目标不是重写库，而是把 `TandemBluetoothHandler` 里“断链后怎么排下一次”从写死逻辑变成策略接口，默认实现与当前行为完全一致：

- `ReconnectPolicy`：`nextAttempt(attempt, lastDelayMs)` 返回 `Decision`（延迟、是否继续）。
- 默认实现 `ReconnectPolicy.LEGACY` = 现有单次 `postDelayed` + `reconnectDelay *= 1.5`（行为不变，`ReconnectPolicyTest` 锁定）。
- 注入点：`TandemBluetoothHandler.setReconnectPolicy(policy)`；连接成功后 `reconnectAttempt` 归零。
- pumppan 侧实现（待做）= 有界退避 + 周期性 `startScan()` 重挂，与 `BleLinkPolicy` 的 2/15/30 分钟对齐；到时 30 分钟的“停手”由策略返回 `stop()` 表达，App 层看门狗只保留 UI 与诊断。
- 采用源码依赖（`use_local_pumpx2`）只维护这一小块，其余仍用上游发布物。

状态（2026-11-13）：库侧接口与默认策略已落地并通过单测；**App 尚未切到源码依赖，也未注入自定义策略**——因此线上行为与改动前完全一致。

前置条件：先与上游提 PR 意向；若上游不接受，再走本地源码依赖，且把差异压到单文件级别。

### A｜维持补丁式分叉

仅作为兜底，不作为路线。每次库/上游变动都要人肉对账，18 处能力判断会继续长。

---

## 九、变更纪律

1. 提交消息中英各一句；未经请求不提交、不推送。
2. 阈值/时长/次数一律可配（prefs + JSON 默认），业务层禁止硬编码字面量。
3. 文档锚点写符号名不写行号；取证与临时产物放 `D:\Programming\temp`，仓库内只留结论性文档。
4. 任何“放开能力”的改动，必须附真机响应证据；任何“改链路时序”的改动，必须附压缩阈值或长时观测证据。
