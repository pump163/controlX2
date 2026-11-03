# Bug：泵蓝牙断连后长时间无法恢复

- 记录日期：2026-11-03
- 分支：pumppan（工作树 `D:\Programming\controlX2-i18n_pumppan`）
- 结论：**上游链路既有缺陷**，非本轮 i18n/UI 改动引入；本轮只记录、不改代码
- 依赖版本：`build.gradle` 中 `pumpx2_version = "1.9.0"`，`use_local_pumpx2` 默认 `false`（使用 aar，非本地源码）

## 0. 一句话结论

现有自动重连的退避是 **250ms × 1.5 且无上限**，`discovered → connected` 阶段**没有应用层超时**，`onConnectionFailed` **只上报不重新扫描**；三者叠加就是 2026-09-28 那轮「卡在第 5 步、6h+ 不动」问题的现代版本。用户手动关开一次系统蓝牙仍是目前最可靠的人工恢复手段。

## 1. 症状与历史记录

### 1.1 用户描述（2026-11-03）

- 官方 App 或他人发起的大剂量不成立：泵连接需要蓝牙配对 code，每个新 App 连接都要重新生成；泵本体发起的事件可以读到。
- 蓝牙断联影响很大、很烦人，是下一步要修的问题。
- 上游 dev 版给了一个「开关」，断连后手动关开一次蓝牙开关即可恢复；可见上游也没有根治办法。

### 1.2 历史记录原文要点

来源：`D:\Programming\controlX2-main-zh\docs\工作记录-2026-09-28.md`，小节「BLE 连接卡死问题分析（仅记录思路，未改代码）」。

- 现象：“泵发现成功但 GATT 连接卡在‘第 5 步，共 7 步’（PUMPX2_PUMP_DISCOVERED）”；“持续 6h+ 无超时无重试”；“开关一次蓝牙即可恢复”。
- 卡住位置：“`onPumpDiscovered`（step 5）成功但 `onInitialPumpConnection`（step 6）从未触发——BLE GATT 连接静默失败。”
- 当时三个根因：
  1. 无连接超时：`onPumpDiscovered` 之后等待 `onInitialPumpConnection` 没有任何超时；
  2. `BleChangeReceiver` 只记日志不动作；
  3. 断线后无自动重连：收到 `PUMP_DISCONNECTED` 只切 UI 文案。
- 当时四条方向：30s 连接看门狗；`BleChangeReceiver` 变主动；断线指数退避（10s/20s/40s/60s）；UI「开关蓝牙」快捷按钮。
- 当时结论：“pumpcomm 是本地模块（非 pumpX2 只读库），可以修改。暂不实施。”

### 1.3 历史思路，部分已被实现推翻

> 本节为 2026-09-28 的历史判断，禁止直接当作现状结论引用。

| 历史判断 | 现状 | 说明 |
|---|---|---|
| 「断线后无自动重连」 | **已推翻** | pumpx2 1.9.0 的 `TandemBluetoothHandler :: onDisconnectedPeripheral` 会自动调度重连；缺的是「有界」与「可恢复」 |
| 「`BleChangeReceiver` 只记日志 → 关开蓝牙无法自动恢复」 | **半推翻** | App 侧 [BleChangeReceiver.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/BleChangeReceiver.kt) :: `onReceive` 确实只打日志；但库内 `TandemBluetoothHandler :: onBluetoothAdapterStateChanged` 在 `STATE_ON` 时调用 `startScan()`，所以关开蓝牙有效 |
| 「无连接超时」 | **部分推翻** | 连接建立后 5s 有 `InitialPumpConnectionChecker`；但 `discovered → connected` 之间仍无应用层超时，且 `onConnectionFailed` 后不重扫 |
| 「指数退避 10s→20s→40s→60s」 | **未实现** | 实际是 250ms×1.5 无上限、无抖动 |
| 「UI 按钮直接 `BluetoothAdapter.disable()/enable()`」 | **不可行** | `targetSdk 35` 下普通应用无法程序化关开蓝牙，详见 §4 |

## 2. 现状事实（pumpx2 1.9.0 aar + pumppan 树）

### 2.1 连接与重连由谁发起

- 入口：[CommService.kt](../mobile/src/main/java/com/jwoglom/controlx2/CommService.kt) :: `onStartCommand`（返回 `START_STICKY`）创建 `PumpCommHandler`，并发送 `INIT_PUMP_COMM`。
- 初始化：[PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `handleMessage` 分支 `INIT_PUMP_COMM`：若 `pumpConnectedPrecondition(checkConnected = false)` 为真（`pump.lastPeripheral != null`）则直接 “init_pump_comm already run, ignoring”；否则构造 `Pump(TandemConfig)` 并 `TandemBluetoothHandler.getInstance(...)`，循环调用 `startScan()`（遇到 `SecurityException` 每 500ms 重试）。
- 扫描与连接（库内）：`TandemBluetoothHandler :: startScan` → 1s 后 `immediateConnectToPeripheral`（已配对泵走 `autoConnectPeripheral`，否则按 `PUMP_SERVICE_UUID` 扫描）→ `onDiscoveredPeripheral` 满足条件时 `connectPeripheral`。
- 断连回调（App 侧）：[PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `Pump.onPumpDisconnected` 关闭 session、停止历史同步、清 `pumpCommState`、发送 `FROM_PUMP_PUMP_DISCONNECTED`、toast，并返回 `super.onPumpDisconnected`（库默认返回 `true`，即允许重连）。

### 2.2 退避无上限

`TandemBluetoothHandler :: onDisconnectedPeripheral`：

- `reconnectDelay` 初值 250ms；每次调度后 `reconnectDelay *= 1.5`。
- 仅在 `onConnectedPeripheral` 中重置为 250ms。
- **没有最大间隔、没有抖动、没有次数上限**。第 10 次约 9.6s，第 20 次约 14 分钟，第 30 次已到小时级，等价于“永久放弃”。

### 2.3 两类 `ReconnectPaused` 会永久放弃

同一回调内按断开原因分类：

- `PAIRING_INCOMPLETE_OR_MISSED_PROMPT` → 记录失败次数、回调 `onPairingPromptNotAcceptedYet`，日志 `ReconnectPaused event=pairing_incomplete_or_missed_prompt action=await_user`，**直接 return，不再重连**。
- `INITIAL_AUTH_NO_REPLY` → 120s 窗口内累计达到 `defaultInitialDisconnectRetryMaxCount = 3` 时，日志 `ReconnectPaused event=initial_auth_no_reply action=manual_reinit_required`，**直接 return，不再重连**。
- 其余原因才进入 `handler.postDelayed(immediateConnectToPeripheral, reconnectDelay)`。

### 2.4 连接建立后的 5s 检查

`TandemBluetoothHandler :: onInitialPumpConnection` 之后：

- 5s 后执行 `InitialPumpConnectionChecker`。
- 仅在已进入初始化步骤（`ALREADY_INITIALIZED`）时检查；若 `requestsSent > 0 && repliesReceived == 0`，标记 `INITIAL_AUTH_NO_REPLY` 并 `peripheral.cancelConnection()`。
- 若同时满足硬认证失败、无授权进行中等条件且达到配置阈值，会额外 `central.removeBond(...)`（**待验证：本项目是否配置了该阈值；默认未配置**）。

### 2.5 `onConnectionFailed` 只上报，不重扫

`TandemBluetoothHandler :: onConnectionFailed` 仅打印 `TandemBluetoothHandler: connection '<name>' failed with status <HciStatus>` 并回调 `TandemPump :: onPumpCriticalError(BT_CONNECTION_FAILED)`。

由于连接前已经 `central.stopScan()`，该回调之后**没有任何代码重新 `startScan()`**；App 侧 [PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `onPumpCriticalError` 只做通知/上报。结果就是停在“已发现但连不上”的状态直到外部干预。

### 2.6 `discovered → connected` 无超时

pumppan 与 pumpx2 1.9.0 代码中，`onDiscoveredPeripheral` 调 `connectPeripheral` 之后没有应用层计时器；5s 检查只在 `onInitialPumpConnection`（即 GATT 已建立）之后才启动。blessed 内部的连接超时行为不在本仓库可审计范围（**待验证**）。这正是 09-28「第 5 步卡 6h」对应的现代空洞。

### 2.7 `getInstance` 静态单例与 `resetInstance` 零调用

- `TandemBluetoothHandler :: getInstance(Context, TandemPump, Timber.Tree)` 为 `static synchronized`；`instance != null` 时**直接返回旧实例，忽略新传入的 `TandemPump`**。
- pumpx2 提供 `TandemBluetoothHandler :: resetInstance`，但 pumppan 树全树检索**没有调用点**。
- [PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `handleMessage` 的 `STOP_PUMP_COMM` 只调用 `tandemBTHandler.stop()`（即 `central.stopScan(); central.close()`），不 reset 单例。
- 推论：服务内“重新初始化”无法真正换掉 handler 与其绑定的 pump；`central.close()` 后复用同一实例是否可用**待验证**。

### 2.8 `shutdownAndAwaitDisconnect` 与「配对码无效」

- [PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `shutdownAndAwaitDisconnect` 调 `peripheral.cancelConnection()` 并等待断连回调 latch，供重启流程优雅释放链路。
- [AppReload.kt](../shared/src/main/java/com/jwoglom/controlx2/shared/util/AppReload.kt) :: `triggerAppReload` 的注释明确记录：若在 GATT 仍打开时 `Runtime.exit(0)`，泵端会进入 “peer vanished” 状态，下一次重连协商成高延迟链路并静默丢弃 JPAKE3，表现为**“配对码无效”**的假错误。
- App 的缓解：[CommService.kt](../mobile/src/main/java/com/jwoglom/controlx2/CommService.kt) :: `appReloadShutdownHook`（`onDestroy` 中注销）在退出前尽力断开。

### 2.9 典型日志信号

| 日志片段 | 含义 |
|---|---|
| `TandemBluetoothHandler: disconnected '<name>' with status <HciStatus> (reconnectDelay: ... reason=...)` | 断连及当前退避值；库输出的是枚举名，Android 常见的 133 通常显示为 `UNKNOWN`（映射**待验证**） |
| `ReconnectBackoff event=initial_auth_no_reply action=retry failuresInWindow=...` | 认证无回复，仍在重试 |
| `ReconnectPaused event=initial_auth_no_reply action=manual_reinit_required` | **已永久放弃**，需人工干预 |
| `ReconnectPaused event=pairing_incomplete_or_missed_prompt action=await_user` | 等待用户完成配对弹窗 |
| `InitialPumpConnectionStuck event=no_response_window action=disconnect` | 已连接但请求无回复，主动断开 |
| `InitialPumpConnectionChecker ... bondState=...` | 5s 检查现场值 |
| `TandemBluetoothHandler: connection '<name>' failed with status ...` | GATT 连接失败（此后不重扫） |
| `service onPumpDisconnected: isConnected=false` | App 侧断连处理 |
| `BleChangeReceiver STATE_CHANGED: off/on` | App 侧仅记录蓝牙开关 |
| `PUMP-DISCOVERED` / `PUMP-CONNECT` / `PUMP-READY-STATE` | 扫描阶段与连接时刻 |

## 3. 「关开蓝牙」为什么有效

1. 系统关蓝牙会强制拆除所有 GATT/ACL 链路并清空 Android 蓝牙栈内部状态（应用无权直接触发）。
2. 系统重新开蓝牙后，pumpx2 库内的 `TandemBluetoothHandler :: onBluetoothAdapterStateChanged` 收到 `STATE_ON` 并调用 `startScan()`，重新扫描并重连。
3. App 自己的 [BleChangeReceiver.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/BleChangeReceiver.kt) :: `onReceive` 对 `BOND_STATE_CHANGED` / `STATE_CHANGED` **只打日志，不触发任何重连**，因此“手动关开”之所以管用完全依赖库内回调。

### 3.1 澄清：上游 dev 的「开关」不是蓝牙开关

dev 树新增（pumppan 继承）的是分级错误卡片，**不是程序化蓝牙开关**：

- [PumpCriticalErrorClassifier.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCriticalErrorClassifier.kt)：按 `TRANSIENT` / `ACTIONABLE` / `FATAL` 分级，`TRANSIENT` 还需同时满足次数阈值与时间阈值才展示（短抖动默认不打扰）。
- [PumpSetupStageDescription.kt](../mobile/src/main/java/com/jwoglom/controlx2/presentation/components/PumpSetupStageDescription.kt) :: `TieredCriticalErrorCard` / `actionLabel` / `runErrorAction`，五个动作：

| 动作 | 实现 | 实际效果 |
|---|---|---|
| `Retry` | 发送 `TO_SERVER_FORCE_RELOAD` → [CommService.kt](../mobile/src/main/java/com/jwoglom/controlx2/CommService.kt) :: `handleMessageReceived` → `triggerAppReload`（`Runtime.exit(0)` + 冷启） | **杀进程重启** |
| `RepairPump` | 清 `pumpSetupComplete` / `pumpFinderPumpMac` / `pumpFinderPairingCodeType` / `currentPumpSid`，`PumpState.resetState`，再 `TO_SERVER_APP_RELOAD` | 重新走配对配置（可能触发解绑） |
| `OpenBluetoothSettings` | `Settings.ACTION_BLUETOOTH_SETTINGS` | **引导用户手动关开蓝牙**（上游现状） |
| `EnableConnectionSharing` | 写 `connectionSharingEnabled = true` 后强制重载 | t:connect 共享连接 |
| `CopyDiagnostics` | 组装诊断文本复制 | 取证 |

全仓检索**无任何 `BluetoothAdapter.disable()` / `enable()` 调用**；手机侧只有 [MainActivity.kt](../mobile/src/main/java/com/jwoglom/controlx2/MainActivity.kt) :: `getBluetoothManager` 中的 `ACTION_REQUEST_ENABLE` 首次启动提示。

另：`BTHostSwitch`（[FeatureFlag.kt](../shared/src/main/java/com/jwoglom/controlx2/shared/FeatureFlag.kt) :: `BTHostSwitch`）是手机/手表“蓝牙主机”角色切换，走 `removeBond` + 重启进程，main/dev 均存在，与本问题无关，且会要求重新配对。

## 4. Android 平台限制

本仓库实际值：`mobile/build.gradle` → `targetSdk 35`、`minSdk 26`；`wear/build.gradle` → `targetSdk 33`。Manifest 已声明 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、定位权限与 `FOREGROUND_SERVICE_CONNECTED_DEVICE`。

| 能力 | 结论 |
|---|---|
| `BluetoothAdapter.enable()` | **不可用**。API 33+ 仅系统应用或持 `BLUETOOTH_PRIVILEGED` 生效；普通应用调用无效（可能返回 false 或抛 `SecurityException`），还需要 `BLUETOOTH_CONNECT` |
| `BluetoothAdapter.disable()` | **不可用**，同上；且没有面向普通应用的 `ACTION_REQUEST_DISABLE` |
| `ACTION_REQUEST_ENABLE` | **可用**，但必须系统弹窗由用户确认（现有首启流程即如此） |
| 打开系统蓝牙设置 | **可用**（`Settings.ACTION_BLUETOOTH_SETTINGS`），由用户手动关开 |
| `BluetoothGatt.refresh()` | 隐藏 API（非 SDK 接口）；部分系统需 `BLUETOOTH_PRIVILEGED`，OEM 可能移除；blessed 是否暴露可反射的 `BluetoothGatt` **待验证** |
| 进程内重启蓝牙栈 | **不可做**，需 root / 系统签名 / Shizuku；`Runtime.exit(0)` 只重启 App 进程，不等于重启蓝牙栈 |

## 5. 候选方案对比

| 方案 | 做法 | 改动面 | 成功率预期 | 风险 | 需要 UI |
|---|---|---|---|---|---|
| ① 看门狗 + 有界退避 + 重扫 | `onPumpDiscovered` 后 20–30s 定时，超时 `cancelConnection()` + `startScan()`；`onPumpDisconnected` 自管 5/10/20/40/60s（上限+抖动）；`onPumpCriticalError(BT_CONNECTION_FAILED)` 触发重扫；`STATE_ON` 重置退避 | `pumpcomm/PumpCommHandler.kt`、`pumpcomm/BleChangeReceiver.kt` | **较高**：覆盖“卡在第 5 步”“连接失败不重试”“退避无上限”三类软故障 | 与库内重连叠加导致双连接；重连后 txId/opcode 重同步；扫描耗电 | 失败进入等待态时显示状态 + “打开蓝牙设置” |
| ② 拆 GATT + `resetInstance` 重建 | `shutdownAndAwaitDisconnect` → `tandemBTHandler.stop()` → `TandemBluetoothHandler.resetInstance()` → 重建 `Pump(cfg)` → `getInstance` → `startScan()`；bond 保留、不重配 | `pumpcomm/PumpCommHandler.kt`（可能 `shared`） | **中高**：等价于“关一次蓝牙”，但清不掉 Android 蓝牙栈 | 静态 `PumpState` / `Packetize.txId` 残留；泵端会话未正常结束 → peer vanished / JPAKE3 失败；旧 `central.close()` 后重建是否干净**待验证** | 自动第 N 次失败触发或恢复按钮 |
| ③ 反射 `refresh()` + 重新发现服务 | 反射取 blessed 内部 `BluetoothGatt` → `refresh()` → `discoverServices()` 后重走初始化 | 新反射 helper + `pumpcomm` | **中低**：ROM/版本相关 | 非 SDK 接口被拦、OEM 无此方法、blessed 私有字段随版本变化；重发现会重新授权/同步，可能诱发“配对码无效” | 否 |
| ④ 引导用户关开蓝牙 | 失败阈值后弹卡/手表提示；手机 `ACTION_BLUETOOTH_SETTINGS`；禁止调用 `disable()` | `PumpSetupStageDescription.kt`（dev 已有入口，补正式入口）+ wear 一处 | **高**（用户实测有效），但依赖人工 | 夜间/睡眠不可用；治标不治本；用户明确“很烦人” | **必须** |
| ⑤ 强制重载 | 复用 `triggerAppReload`（`Runtime.exit(0)` + 冷启），现有 shutdown hook 先断开 | 已有（dev `ErrorAction.Retry`），零新增 | **高**（彻底清进程/静态态） | 冷启耗时；退出瞬间 GATT 未清 → 假“配对码无效”；断连窗口内无法发命令 | 可做最后手段按钮 |

不建议：`removeBond` / 重新配对。每次新 App 连接都要泵端重新生成配对 code，代价最大，且可能把可恢复断连升级为“必须重新配对”。

## 6. 建议路线

1. **先做 ①**：改动只在本地 `pumpcomm`，直接命中 09-28 的三个洞；把“永久放弃”改为“有界退避 + 可恢复”，不引入 GATT 重建风险。
2. **同步把 ④ 的产品化入口做上**：把 dev 的 `OpenBluetoothSettings` 下沉为正式恢复路径（手机 + 手表），作为 ① 失败后的兜底。
3. **用日志决定是否上 ②**：若卡点集中在 `PUMP-DISCOVERED → PUMP-CONNECT` 且 `onConnectionFailed` 频发，① 只能反复重扫，此时加 ② 做 handler/central 级拆除。
4. **③、⑤ 不作第一线**：③ 破坏面大且不可移植；⑤ 已有能力，适合作“最后一次”兜底。
5. **pumpx2 升级单独立项**：本地 dev 树（1.9.2 方向）有更多连接硬化，但从 1.9.0 aar 升级会改动整条 BLE 链，需单独回归，不夹带在本次修复里。
6. **任何方案都不得触发 `removeBond`**（配对 code 约束）。

## 7. 真机取证方法

### 7.1 logcat

```powershell
adb logcat -v time | Select-String "PUMP-DISCOVERED|PUMP-CONNECT|PUMP-READY-STATE|TandemBluetoothHandler|TandemPump|ReconnectBackoff|ReconnectPaused|InitialPumpConnectionStuck|InitialPumpConnectionChecker|BleChangeReceiver|service onPumpDisconnected|pumpCommHandler|force-reload"
```

```powershell
adb logcat -b all -v threadtime > bt-disconnect.log
```

- Timber 的 `DebugTree` 默认把 `d/i/w/e` 全部输出到 logcat，无需额外开级别。
- 断连前后各抓一次 `adb shell dumpsys bluetooth_manager > bt-before.txt` / `bt-after.txt`；`adb shell dumpsys activity services com.jwoglom.controlx2` 确认服务存活与 `START_STICKY` 重启行为。

### 7.2 HCI / 协议层

- 开发者选项开启「启用蓝牙 HCI 嗅探日志」，取 `/data/misc/bluetooth/logs/btsnoop_hci.log`（通常需 root 或 `adb bugreport`）。
- 该日志用于区分：链路丢失、本地主机主动断开、GATT 错误（常见 133）三类。

### 7.3 Timber 文件日志

- 路径：`/data/user/0/com.jwoglom.controlx2/files/debugLog-*.txt`（由 [TimberUtil.kt](../shared/src/main/java/com/jwoglom/controlx2/shared/util/TimberUtil.kt) :: `setupTimber` + `DebugTree` 写入，受文件日志开关控制）。
- 仅调试包可用 `run-as` 拉取：

```powershell
adb shell run-as com.jwoglom.controlx2 ls files
adb exec-out run-as com.jwoglom.controlx2 cat files/debugLog-xxx.txt > debugLog.txt
```

- release 包（当前日常出包方式）不是 debuggable，`run-as` 不可用，改用 logcat + 诊断复制动作。

### 7.4 需要记录的现场信息

断连准确时间、`HciStatus` 值、当时 `reconnectDelay`、是否出现 `ReconnectPaused`、之后多久恢复、用何种手段恢复（等待 / 强制重载 / 关开蓝牙）、手表端是否同时断。

## 8. 动手前必须确认（清单）

1. 断连形态占比：初始 `step5→step6` 卡死、会话中掉线、`INITIAL_AUTH_NO_REPLY` 三类各占多少？需至少 3 次完整真机日志分类。
2. 日志中是否出现 `ReconnectPaused ... manual_reinit_required`？出现后“强制重载”能否恢复，还是只有关开蓝牙有效？这决定 ① 是否足够。
3. `HciStatus` 的具体值（133/`UNKNOWN`，还是 8/19/22/62 等）及是否伴随适配器 `STATE_OFF`；不同值指向不同层（链路丢失 / 本地终止 / GATT 错误）。
4. 目标机型与系统版本、省电与后台限制策略；`targetSdk 35` 下前台服务是否被降级或杀掉（`FOREGROUND_SERVICE_CONNECTED_DEVICE` 是否足够）。
5. 断连发生在手机侧还是手表侧（`WearPumpCommService` 路径不同），是否两端同时断，`BTHostSwitch` 角色是否被改过。
6. 产品策略：重连期间是否允许继续下发大剂量等命令；最多自动重试几次后转人工；夜间无人值守如何提示（手表振动 / 手机通知）。
7. pumpx2 是升级到 1.9.2 还是只在 pumppan 本地打补丁；升级后 `resetInstance` 语义与接口是否变化。
8. 所有候选方案均不得触发 `removeBond`：确认现有偏好与恢复流程中不存在隐式解绑，且重连后不会要求重新输入泵端配对 code。
9. ① 与库内重连的优先级：是禁用库内重连（`onPumpDisconnected` 返回 `false`）由 App 全权接管，还是保留库重连只补看门狗；两种方案的日志指标需先定义。
</content>
