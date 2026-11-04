# HTTP 取证与真机点检

> **⚠️ 本方案会真实输注胰岛素。执行前须逐条符合 §5 的条件（血糖、人员、糖、中止手段），否则不要开始。**
>
> 日期：2026-11-03　状态：**进行中 · 2026-11-05 完成只读实测，写路径（-98）因 BULK 拦截暂停**
> 目标：用 App 自带的 HTTP 调试 API 一次性验证「延展大剂量（11 参 `InitiateBolusRequest`）」相关的 8 个假设；同时给出最新编译版的真机点检顺序。
> 关联：`docs/feature/大剂量功能设计-v2.md`（§9 取证清单即本方案的来源）

---

## §1 HTTP 调试接口能力（已核实）

| 项 | 结论 | 锚点 |
|---|---|---|
| 开关 | `Prefs` key `http-debug-api-enabled`，**默认 false** | `Prefs.kt :: httpDebugApiEnabled` |
| 鉴权 | HTTP **Basic Auth**；用户名默认 `admin`（key `http-debug-api-username`），密码默认空（key `http-debug-api-password`）；**密码为空则服务直接不启动** | `Prefs.kt :: httpDebugApiUsername / httpDebugApiPassword`、`HttpDebugApiService.kt :: start` |
| 监听 | `NanoHTTPD(port)` 只传端口 → 绑定 **`0.0.0.0:18282`**；同一 Wi-Fi / 热点内任意设备可访问；**无 TLS** | `HttpDebugApiService.kt :: DebugApiServer` |
| 生效时机 | 服务实例在 `CommService :: onCreate` 创建并立即 `start()` → **改开关必须重启进程/服务** | `CommService.kt :: onCreate`、`strings.xml :: debug_toggle_http_debug_api_on_port` |
| 发任意泵消息 | **可以**。POST 体 `{"cargo":"<hex>","opCode":<int>,"characteristic":"CONTROL"}`，内部 `Messages.parse(cargo, opCode, characteristic)`，**不经过 UI，也不经过 `BolusManager`** | `HttpDebugApiService.kt :: handlePumpMessagesPost`、`PumpMessageSerializer.java :: fromBytes` |
| 等待与超时 | 按 `(characteristic, responseOpCode)` 注册 Future，**30 秒超时**；同一 key 后发会覆盖先发 → **同 opcode 不能并发，必须串行** | 同上（`pendingPumpRequests[key] = future`） |
| 响应体 | JSON 数组，每项 `Message.jsonToString()`（全字段：`nackReason`、`status`、`bolusId`…）；`ErrorResponse` 或超时 → HTTP 500 + `exceptions` | `HttpDebugApiService.kt :: handlePumpMessagesPost` |
| 实时观察 | GET `/api/pump/messages` 是**流式**（逐行 JSON），`?debugMessagesOnly=true` 只看 Debug 页发出的消息 | `HttpDebugApiService.kt :: handlePumpMessagesStream` |
| 当前状态 | GET `/api/pump/current` 仅 `statusText/connectionTime/lastMessageTime/batteryPercent/iobUnits/cartridgeRemainingUnits`，**不含任何 bolus 字段** | `CommService.kt :: getCurrentPumpDataJson` |
| 读历史 | `/api/historylog/entries?format=parsed&typeId=…` 返回解析后字段，可直接查 **59 / 21 / 280** 等记录 | `HttpDebugApiService.kt :: handleHistoryLogEntries` |
| 预定义命令 | GET `/api/pump/commands` 只是 **opCode → 类名**字典，不含参数 | `HttpDebugApiService.kt :: handlePumpCommandsGet` |
| 裸 BLE 写 | POST `/api/pump/debug-write-bt-characteristic` 是 **空桩**（`// todo`，直接返回空串） | `HttpDebugApiService.kt :: handlePumpDebugWriteBtCharacteristic` |

### 1.1 能否构造 11 参 `InitiateBolusRequest`

**能，且无需改一行 App 代码。** `PumpMessageSerializer.fromBytes` 只认 `cargo` 十六进制；`InitiateBolusRequest.parse` 会先剥掉 24 字节 HMAC 再校验 37 字节，因此 PC 端自拼 37 字节 cargo 即可。

cargo 布局（`InitiateBolusRequest.java :: buildCargo`，全部小端）：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0–3 | 4 | `totalVolume`（毫单位，uint32） |
| 4–5 | 2 | `bolusID`（uint16） |
| 6–7 | 2 | 全 0 |
| 8 | 1 | `bolusTypeBitmask`（双波 / 方波抓包均为 `0x0C`） |
| 9–12 | 4 | `foodVolume` |
| 13–16 | 4 | `correctionVolume` |
| 17–18 | 2 | `bolusCarbs` |
| 19–20 | 2 | `bolusBG` |
| 21–24 | 4 | `bolusIOB` |
| 25–28 | 4 | `extendedVolume` |
| 29–32 | 4 | `extendedSeconds` |
| 33–36 | 4 | `extended3`（置 0） |

### 1.2 通用调用方式

PowerShell：

```powershell
$u = "admin"; $p = "<你的密码>"; $ip = "<手机IP>"
$h = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$u`:$p")) }
function Send-Msg($json) {
  Invoke-RestMethod -Method Post -Uri "http://$ip:18282/api/pump/messages" -Headers $h `
    -ContentType "application/json" -Body $json -TimeoutSec 40
}
```

curl：

```bash
curl -u admin:<密码> -H "Content-Type: application/json" \
     -d @body.json http://<手机IP>:18282/api/pump/messages
```

拼 cargo（本机跑，输出可直接填入 `cargo` 字段）：

```python
def cargo(total, bolus_id, bitmask, ext_vol, ext_secs,
          food=0, corr=0, carbs=0, bg=0, iob=0, ext3=0):
    b = total.to_bytes(4,'little') + bolus_id.to_bytes(2,'little') + b'\x00\x00' \
      + bytes([bitmask]) + food.to_bytes(4,'little') + corr.to_bytes(4,'little') \
      + carbs.to_bytes(2,'little',signed=True) + bg.to_bytes(2,'little',signed=True) \
      + iob.to_bytes(4,'little') + ext_vol.to_bytes(4,'little') \
      + ext_secs.to_bytes(4,'little') + ext3.to_bytes(4,'little')
    assert len(b) == 37
    return b.hex()
```

---

## §2 假设 → HTTP 调用映射表

| # | 假设 | 要发的消息与参数 | HTTP 调用（占位符） | 看什么字段 | 判定标准 |
|---|---|---|---|---|---|
| ① | X2 接受一条 11 参**双波** | `-98`，`total=500`（0.5U）、`ext=250`、`secs=1800`、`bitmask=0x0C`、其余 0 | `{"cargo":"{{CARGO_①}}","opCode":-98,"characteristic":"CONTROL"}` | `InitiateBolusResponse::status` | `0(SUCCESS)` → 接受；`2(REVOKED_PRIORITY)` / `ErrorResponse` → 确证拒绝 |
| ② | 接受**纯方波**（now=0） | `-98`，`total=0`、`ext=400`（0.4U）、`secs=1800`、`bitmask=0x0C` | 同上，`{{CARGO_②}}` | `status`；另查 `44/45` | `status=0` 且 `CurrentBolusStatusResponse::bolusId≠0` → 接受 |
| ③ | `ExtendedBolusStatus`(47) 与 V2(-73) 在 X2 可用 | **请求发 `46` / `-74`**（47、-73 是响应 opcode） | `{"cargo":"","opCode":46,"characteristic":"CURRENT_STATUS"}`，再 `{"cargo":"","opCode":-74,...}` | `ExtendedBolusStatusResponse::bolusId / duration / secondsSincePumpReset` | 有非 0 `bolusId`/`duration` → 可用；`ErrorResponse` → X2 不支持 |
| ④ | 延展运行中再申请权限的 `nackReason` | `-94`（cargo 空） | `{"cargo":"","opCode":-94,"characteristic":"CONTROL"}` | `BolusPermissionResponse::nackReason` | `3=PUMP_HAS_PERMISSION` / `1=INVALID_PUMPING_STATE` → 记真值（决定 UI 文案） |
| ⑤ | 延展运行中叠加普通大剂量是否被接受 | `-98`，`total=50`（0.05U）、`ext=0`、`secs=0`、`bitmask=0x01` | `{{CARGO_⑤}}` | `status` | 接受 / 拒绝各记一条；拒绝则说明「扩展中禁普通」是泵侧规则 |
| ⑥ | `-96` 能否中断延展 | `CancelBolusRequest(bolusId)`，cargo = `bolusId` 2 字节 LE + `00 00` | `{"cargo":"{{CARGO_⑥}}","opCode":-96,"characteristic":"CONTROL"}` | `CancelBolusResponse::wasCancelled`；再查 `44/45` | `true` 且回 Idle → 可中断 |
| ⑦ | `-16` `BolusPermissionReleaseRequest` 是否被接受 | `-16`，cargo = `bolusId` LE + `00 00` | `{"cargo":"{{CARGO_⑦}}","opCode":-16,"characteristic":"CONTROL"}` | 正常响应 / `ErrorResponse` | 有正常响应 → 接受；`ErrorResponse` → 不支持 |
| ⑧ | 时长 / 剂量上限探测（**小样本**） | `-98`，`total=0`、`ext=400`、`secs=7201`（再试 `28801`），**发出后 10s 内立刻 `-96`** | `{{CARGO_⑧}}` | `status` | 被拒不产生胰岛素；若被接受则 10s 内取消（实际 <0.001U）；**一次只试一档** |

---

## §3 两轮执行顺序

### 前置（无输注风险，全部做完再考虑碰胰岛素）

| 步 | 动作 | 间隔 | 失败即停 |
|---|---|---|---|
| 0.1 | `GET /api/pump/connected` 返回 `{"connected":true}`（否则先 `/api/pump/wait-connected`） | — | 连不上就停 |
| 0.2 | `GET /api/pump/commands` 确认含 `-94 / -98 / -96 / -16 / 46 / -74` | — | 缺哪个就跳过对应假设 |
| 0.3 | 开流：`GET /api/pump/messages?debugMessagesOnly=true` 全程录像 | 持续 | 断流即停 |
| 0.4 | 基线读取：`44`、`46`、`-74`、`LastBolusStatus`、`/api/historylog/entries` | 每步 5s | 异常 `ErrorResponse` 只记录 |
| 0.5 | **假设 ③**（Idle 下先发一遍 `46` / `-74`） | — | 两者都 `ErrorResponse` → 停 |

### 轮 A：一次真实输注覆盖 ①④⑤⑦⑥（合计约 0.30U）

| 步 | 动作 | 间隔 | 停条件 |
|---|---|---|---|
| A1 | `-94` 取 `bolusId` | 5s | 非 0 `nackReason` → 停 |
| A2 | **①** 双波 `0.5U / 50-50 / 30min`（`total=500, ext=250, secs=1800`）— **真实输注开始：立即 0.25U** | — | `status≠0` → 跳过 A3–A6，直接 A7 |
| A3 | 查 `46` / `-74` / `44`（延展在跑时取真值，同时验证 ③） | +10s | 无响应 → 记录，不停 |
| A4 | **④** 再发 `-94` | +20s | 记 `nackReason` |
| A5 | **⑤** 普通 `0.05U`（`total=50, ext=0`）— **真实 +0.05U** | +30s | 看是否被拒 |
| A6 | **⑦** 发 `-16`（bolusId） | +60s | 若被接受且延展仍在跑 → release 不取消，继续 A7；若被接受且延展消失 → ⑦ = 兼取消，跳过 A7 |
| A7 | **⑥** 发 `-96`（bolusId），+10s 后再查 `44` | +90s | `wasCancelled=false` → 记「不可中断」，立即改用**官方 App 或泵本体取消** |
| A8 | 收尾：`LastBolusStatus` + 历史日志，核对 `BolusRequestedMsg2` 的 percent / duration | +60s | — |

### 轮 B：覆盖 ②⑧（合计约 0.42U）

| 步 | 动作 | 间隔 | 停条件 |
|---|---|---|---|
| B1 | `-94` 取新 `bolusId` | 5s | — |
| B2 | **②** 纯方波 `0.4U / 0-100 / 30min`（`ext=400, total=0`）— **真实输注：0.8U/h** | — | `status≠0` → 停 |
| B3 | 查 `46` / `-74` 的 `duration` 与 `secondsSincePumpReset`，判断剩余时间可算性 | +10s | — |
| B4 | `-96` 取消 | +20s | 失败 → 手动取消 |
| B5 | **⑧** `secs=7201` 再发一次，**+10s 内必发 `-96`** | — | 被拒（无胰岛素）记「上限≈7200」；被接受则立即取消，记「X2 未拦」 |

**必须人工确认的点**：A2 与 B2 按下发送前，各停顿确认「血糖在目标范围、身边有人、有糖、泵在手边」；A7 / B4 若 30 秒内没看到 Idle，立刻改用泵本体取消。

**必然输注真实胰岛素的最小剂量**：A2 立即段 **0.25U**（+ A5 的 **0.05U**）；B2 总量 **0.4U**（提前取消则实际远小于此）。全流程最坏约 **0.7U**，正常约 **0.3U**。

---

## §4 HTTP 做不到的清单

- **应用内确认 / 通知确认链路**（`BolusManager :: confirmBolusRequest` 的阈值分支、`InitiateConfirmedBolusSerializer` 的 HMAC、60s 有效期、`BolusNotificationBroadcastReceiver`）——HTTP 直发绕过，**必须走正式 UI**。
- **开关切换行为**（`legacy` / `v2` 分派、切换时会话清理）——只能走 UI。
- **进程被杀后的对账**（`BolusSessionController :: applyMessage` + 重连四步）——需真机杀进程 + 断连。
- **断连时取消行只读 / 恢复后先对账**——需真机拔蓝牙。
- **所有 Compose 渲染类**（声音页删掩码框、X2 只读原因文案、星期选择器、Debug 暗色对比度、充电图标暗色、首页三开关）——只能肉眼 + 截图。
- **常驻通知 / Dashboard 横幅 / 通知权限**——UI。
- **预设 Room 读写与 6 条上限**——UI。
- **泵侧「时长超限」语义**：`InitiateBolusResponse::BolusResponseStatus` 只有 `SUCCESS(0)` / `REVOKED_PRIORITY(2)`，HTTP 也读不出口径 → 只能靠 ⑧ 的接受 / 拒绝二分。**待真机**。

---

## §5 安全与中止

1. **会真的打胰岛素**：A2（0.25U 立即 + 0.25U/30min）、A5（0.05U）、B2（0.4U/30min）。整轮最坏 <1U，但仍是真实输注。
2. **血糖条件**：血糖 **≥5.6 mmol/L（100 mg/dL）**、非夜间、非运动后、不在 CIQ 自动校正窗口内；不要在睡前做。
3. **人员**：全程有人在同一房间；备 15–20g 速效糖；手机与泵都在手边。
4. **中止优先级**：
   - 首选 **泵本体取消**（物理操作，最可靠）；
   - 次选官方 App / 本 App 正式 UI 的「取消」；
   - 脚本侧 `-96`：`{"cargo":"<bolusId 2字节LE>0000","opCode":-96,"characteristic":"CONTROL"}`。
5. **串行要求**：同一 `responseOpCode` 只能有一个 pending（后发覆盖先发），严格按表一步一步来，**不要并发**。
6. **⑧ 小样本规则**：只发「预期被拒」的档位 + 发出后 10s 内必取消；绝不为了观察 8h 上限而等待。
7. **`REVOKED_PRIORITY` 不重试**：`status=2` 或 `ErrorResponse` 时不要重发同一条，先保存原始 JSON 再决定（可能是权限未拿到或 X2 不接受 11 参）。

---

## §6 最新编译版真机点检顺序（风险由高到低）

**记录方式统一**：① 截图（暗色项必截）；② 全程开 `GET /api/pump/messages?debugMessagesOnly=true`；③ 关键异常同时抓 `adb logcat`（按 `controlx2` / `pumpx2` 过滤）。

| 序 | 点位 | 点哪里 | 看什么 | 异常表现 | 记录 |
|---|---|---|---|---|---|
| 1 | **大剂量旧流程**（最高风险，真实输注） | 首页 FAB / Dashboard「大剂量」→ `BolusWindow` | 开关默认 legacy；剂量输入 → 确认弹窗 → 发送 → 取消 | 弹窗叠层、`nackReason` 不显示、取消后仍显示「输注中」、发送后切窗状态丢失 | 录 `BolusPermissionResponse` / `InitiateBolusResponse` / `CancelBolusResponse` 原始 JSON；剂量用 **0.05U** 最小样本 |
| 2 | **蓝牙断连** | 走远 / 关蓝牙 / 泵断电再恢复 | 断连提示、恢复后自动重连、重连后 bolus 状态对账、是否重复通知 | 恢复后仍显示旧状态；取消行可点但发不出去；反复抖动 | logcat 分类 `GATT` / `bond` / `timeout`；只记「UI 表现」，根因另开线 |
| 3 | **声音设置页** | 设置 → 声音（`SoundSettingsActions.kt`） | 掩码框是否已删；X2 上「只读 + 原因」文案；星期选择器点选与回显 | 掩码框残留、X2 仍可点、勾选后不回显、原因缺失 | 截图 X2 只读态 + 编辑态各一张 |
| 4 | **首页三开关** | 首页三个 Toggle（CGM 图 / 传感器卡 / 历史同步条） | 切换立即生效、杀进程后保持 | 切换无效、重启回默认、只改一半 | 三态各截一张 + 杀进程后复验 |
| 5 | **Debug 页**（`Debug.kt`） | 设置 → Debug | ① 暗色可读性；② 空数据时「显示泵运行日志」；③ **清空数据库 → 历史缓存重拉是否翻倍 / 重启是否复活**；④ 过滤框 | 深色下白字白底；空数据按钮无响应；重拉条数×2；清空后重启又出现 | 清库前后各记 `/api/historylog/stats` 条数，重启再记一次 |
| 6 | **充电图标暗色** | 首页电池 / 充电指示 | 暗色主题下对比度 | 图标几乎不可见 | 暗色截图 |

**顺序理由**：1 涉及真实输注，先确认「旧流程没被这轮改动破坏」；2 断连会污染后续所有观测，排第二；3 声音配错可能掩盖报警，排第三；4–6 纯 UI，最后。

**最小样本原则**：点检 1 与 §3 的 HTTP 取证**不要同一天做**；若必须同日，先做点检 1（0.05U），**间隔 ≥2h** 再做 HTTP 轮 A。

---

## §7 待验证清单

| # | 待验证项 | 为什么不确定 | 验证手段 |
|---|---|---|---|
| V1 | HTTP 路径的 HMAC 签名是否与正式发送 **1:1** | HTTP 走 `sendPumpCommMessages`，理论上由 BLE 层按 `signed=true` 补 24 字节 HMAC，但未实测对照 | 轮 A 若 `status=0` 可反证；再与正式 UI 发送的报文逐字节比对（流式 `/api/pump/messages`） |
| V2 | `ExtendedBolusStatus`(47) / V2(-73) 在 X2 上是否可用 | 全仓 `ExtendedBolusStatusRequest` **0 引用**，从未发送过 | 前置 0.5 + A3 |
| V3 | 时长超限时泵的真实表现 | `BolusResponseStatus` 无「超时长」语义，库层面读不出 | B5（7201s 一档，预期被拒） |
| V4 | `-16` `BolusPermissionReleaseRequest` 的真实语义 | 全仓 0 引用；名字像「释放权限」，是否同时取消输注未知 | A6（若放权后延展消失即兼取消） |

---

## §8 真机实测记录（2026-11-05）

> 状态：**只读实测全部完成 · 写路径实测中断（根因已定位）**

### 8.1 连接与基线

- 设备：`192.168.8.52:18282`，Basic Auth `admin:123456`，调试开关开 + 服务重启后生效。
- `GET /` → **200**，`buildVersion = 82710ea`（buildTime `2026-11-04T01:20:11+08:00`，即 **11-04 旧包**，不含 11-05 未编译的 UI 改动），`pumpx2 1.9.0`。
- `GET /api/pump/connected` → `{"connected":true}`。
- `GET /api/pump/current` → `statusText=comm_connected_to_pump`, `connectionTime=2026-11-04T15:23:29Z`, `lastMessageTime=2026-11-04T15:38:24Z`, `batteryPercent=50`, `iobUnits=4.807`, `cartridgeRemainingUnits=240`。

### 8.2 只读命令实测（全部 ✅）

| opCode | 名称 | 实测结果 |
|---|---|---|
| 52 | CurrentBatteryV1 | ✅ `currentBatteryAbc=59`, `currentBatteryIbc=50`（`/api/pump/current` 报的 50 是 IBC 口径） |
| 86 | PumpGlobals | ✅ `quickBolusEnabledRaw=1`, `quickBolusIncrementUnits=1000`, `quickBolusIncrementCarbs=15000`, `quickBolusEntryType=0(UNITS)`, `quickBolusStatus=15` |
| 44 | CurrentBolusStatus | ✅ Idle（全 0） |
| 46 | ExtendedBolusStatus | ✅ **有响应**（Idle，全 0）→ **V2 可用已证实** |
| -74 | ExtendedBolusStatusV2 | ✅ **有响应**（Idle；`secondsSincePumpReset=4294967295` 为无效哨兵值）→ **V2 可用已证实** |
| 48 | LastBolusStatus | ✅ `bolusId=6803, deliveredVolume=5000, bolusStatusId=3, timestamp=594684556` |
| -92 | LastBolusStatusV2 | ✅ 同上 + `requestedVolume=5000` |
| 50 | LastBG | ✅ `bgSource=MANUAL, bgValue=0` |
| 84 | PumpVersion | ✅ `modelNum=1000354, partNum=1011979, armSwVer=1547050764, serialNum=90964627` |
| 114 | BolusCalcDataSnapshot | ✅ `maxBolusAmount=20000, isf=18, carbRatio=10000, targetBg=100, iob=5148, cartridgeRemainingInsulin=240, correctionFactor=0, carbEntryEnabled=false, isAutopopAllowed=false` |
| -114 | CommonSoftwareInfo | ⚠️ cargo 必须 1 字节（MCU 类型，ARM=0 → `"00"`）；空 cargo → HTTP 400；填 `"00"` 后返回 appSoftwarePartNumber=1011979, bootloaderPartNumber=65, bootloaderVersion="2" |
| -94 | BolusPermissionRequest | ✅ `bolusId=6804, nackReasonId=0, status=0(SUCCESS)`，`signed=true` |

### 8.3 历史库实测（今天 21:51→23:20）

- `GET /api/historylog/status` → pumpSid=627, totalCount=21432, oldestSeqId=2054629(2026-10-23), newestSeqId=2076508(2026-11-04T23:20:11Z)
- `GET /api/historylog/stats` → 21432 条；type 分布：219→3469, 71(IdpList)→3458, 227→3456, 279(BasalDelivery)→3450, 81(DailyBasal)→1344
- Malfunction(type 6): **仅 4 条**，全在今天之前
- Alarm(type 5): **仅 2 条**，均在 11-01
- 今天尾部 209 条（seqIdMin=2076300 & seqIdMax=2076600）：type6=**0**, type5=**0**, type4 Alert=1（21:54:28 alertId=13 触发，22:02:10 清除）
- 结论：**泵侧今天零异常记录**；用户感知的「断联」是手机端 BLE 掉线，泵本身未记异常。

### 8.4 写命令实测（2026-11-05，用户授权后执行）

| 步 | 命令 | 结果 |
|---|---|---|
| A1 | `-94` BolusPermission | ✅ `status=0(SUCCESS)`，`bolusId=6804` |
| A2 | `-98` InitiateBolus（dual wave: total=500mU / ext=250mU / secs=1800 / bitmask=3） | ❌ **500** `{"exceptions":["java.util.concurrent.TimeoutException"],"response":[]}` → **未送达、未输注** |
| A2retry | 同 A2 | ❌ 同样 500 |
| A4 | `-96` CancelBolus（cargo=`941a0000`） | ✅ 200，`reasonId=2, statusId=1` |

### 8.5 根因：大剂量写被 HTTP BULK 调试通道拦截

- `PumpCommHandler.kt :: isBolusCommand`：
  ```kotlin
  (message is InitiateBolusRequest) ||
      (message.opCode() == InitiateBolusRequest().opCode() && message.characteristic == Characteristic.CONTROL)
  ```
- `SEND_PUMP_COMMANDS_BULK` handler：
  ```kotlin
  if (isBolusCommand(it)) { Timber.e("SEND_PUMP_COMMAND blocked bolus command") }
  else if (pumpConnectedPrecondition()) { pump.command(it) }
  ```
- 结论：**HTTP debug 的 raw BULK 写通道不承载 InitiateBolus（大剂量）**；大剂量真正交付走 `SEND_PUMP_COMMAND_BOLUS`（签名校验链路）。因此 `-98` 一直超时、未发生任何输注。

### 8.6 对 §7 待验证清单的影响

| 项 | 结论 |
|---|---|
| V1 | 待验证（HTTP HMAC 1:1）— A 受 BULK 拦截阻塞，需改走 `SEND_PUMP_COMMAND_BOLUS` 或新增 debug 路径后再试 |
| V2 | ✅ **已证实可用**（46/-74 有响应） |
| V3 | 待验证（时长超限）— B5 未执行 |
| V4 | 待验证（-16 Release 语义）— A6 未执行 |
| **新增** | **-98 大剂量不可经 HTTP BULK 通道发出（设计拦截，非泵侧缺陷）** |

### 8.7 debug-write-bt-characteristic 探针（2026-11-05）

- 用户请求：再测一次，看看 debug 这条路能不能走通。
- 实测：`POST /api/pump/debug-write-bt-characteristic`（characteristicUuid=`7b83fffc-9f77-4e5c-8064-aae2c24838b9`, valuesHex=`["941a0000"]`）→ **HTTP 200，body 为空字符串**。
- 源码定位：`HttpDebugApiService.kt` `handlePumpDebugWriteBtCharacteristic`（L990）内含 `// todo`，直接返回空响应（L1002-1005），**未实现任何写入逻辑**。
- 结论：该路由**不能**走通、不能承载大剂量；探针即无效。HTTP debug 当前只有两条可写：`/api/pump/messages`（BULK，bolus 被 `isBolusCommand` 拦截）、`/api/pump/debug-write-bt-characteristic`（stub，空响应）。
- `/api/pump/current` 实测正常返回（IOB 4.807→3.591 为自然衰减）。

### 结论（§8 收尾）
**V2 已证实；V1/V3/V4 待后续；-98 大剂量经 HTTP BULK 调试通道不可发出（设计拦截 / stub 探针无效），需另行设计 debug 路径或走 `SEND_PUMP_COMMAND_BOLUS` 签名链路后复测。** 轮 A/B 因该拦截暂停，B 轮方波测试待恢复。

