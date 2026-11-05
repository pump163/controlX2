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

---

### 9. 发送链逐层扫描与「CONTROL 是否也拦」（2026-11-05，仅核实）

> **状态：代码层核实完成，真机未验证。** 本节不含任何代码改动。

#### 9.1 一句话结论

**CONTROL 也拦。** 判定不依赖 characteristic：`isBolusCommand` 的第一条分支是 `message is InitiateBolusRequest`（类型判定，任何 Characteristic 都为真），位置 `PumpCommHandler.kt:653-655`；实际丢弃发生在 4 个 bulk 发送点（`:727`、`:737`、`:752`、`:778`）。HTTP 只有 `/api/pump/messages` 一条写路径，固定走 `SEND_PUMP_COMMANDS_BULK`，所以 **HTTP 现在发不出 -98，把 `characteristic` 改成 `CONTROL` 也没用**。

#### 9.2 分层表

| 层 | 文件:行 | 判定条件 | 拦 -98？ | 性质 |
|---|---|---|---|---|
| ① Debug 命令列表枚举 | `Debug.kt:372-379` | `filter`：排除 `authentication.*`、`historyLog.*`、`FactoryResetRequest`、`FactoryResetBRequest`、`Nonexistent` | **否**（-98 不在名单里） | **UI 隐藏**（只影响下拉列表） |
| ② Debug 点击分支 | `Debug.kt:402-407` | `className == InitiateBolusRequest` → 专用对话框 | 否 | UI 有专用入口 |
| ③ Debug → 发送 | `MainActivity.sendPumpCommands` | `MessagePaths.PREFIX_TO_PUMP + SendType.slug`，无判定 | 否 | 无判定 |
| ④ CommService 路由 | `CommService.kt`（`TO_PUMP_DEBUG_COMMANDS` 分支） | `-> sendPumpCommDebugMessages()` | 否 | 无判定 |
| ⑤ `SEND_PUMP_COMMANDS_BULK` | `PumpCommHandler.kt:737-738` | `if (isBolusCommand(it)) Timber.e(...)` | **是** | **发送层拒绝：静默丢弃，UI 无提示，不抛异常** |
| ⑥ `SEND_PUMP_COMMAND`（单条） | `PumpCommHandler.kt:727-728` | 同上 | 是 | 发送层拒绝 |
| ⑦ `SEND_PUMP_COMMANDS_BUST_CACHE_BULK` | `PumpCommHandler.kt:752-753` | 同上 | 是 | 发送层拒绝 |
| ⑧ `CACHED_PUMP_COMMANDS_BULK` | `PumpCommHandler.kt:778-779` | 同上 | 是 | 发送层拒绝 |
| ⑨ `SEND_PUMP_COMMAND_BOLUS`（正式链路） | `PumpCommHandler.kt:786-812` | 必须 `isBolusCommand == true` + HMAC 有效 + `prefInsulinDeliveryActions()` 开 | **否（唯一放行路径）** | 白名单 + 三重前置 |
| ⑩ pumpx2 `Packetize.packetize` | `Packetize.java:63-65` | `modifiesInsulinDelivery() && !actionsAffectingInsulinDeliveryEnabled` | 是（任何通道） | **抛运行时异常** |
| ⑪ HTTP `POST /api/pump/messages` | `HttpDebugApiService.kt:793→851-852` | 解析 → `toBulkBytes` → `sendPumpMessagesCallback`（即层⑤） | **是** | 复用层⑤ |
| ⑫ HTTP `POST /api/pump/debug-write-bt-characteristic` | `HttpDebugApiService.kt:990-1005` | `// todo`，返回空 body | 无法发 | 未实现（不是拒绝） |

#### 9.3 Debug 黑名单的准确构成（用户所说「3 条」）

`Debug.kt:372-379` 是**列表枚举时的 `filter`（UI 隐藏）**，不是发送层。按 pumpx2 1.9.0 的请求类实测计数：

| 被过滤的类 | 数量 | 性质 |
|---|---|---|
| `authentication.*` | 8 | 配对握手用，隐藏图省事 |
| `historyLog.*` | 1 | 历史读取用 |
| `FactoryResetRequest`（opcode **-24**） | 1 | **真正危险** |
| `FactoryResetBRequest`（opcode **124**） | 1 | **真正危险** |
| `Nonexistent*` | 9 | 占位/未实现，泵上不存在 |
| **合计** | **20** | |

所以「3 条」与实测不符：**真正危险的只有 2 条**（两个工厂复位），9 条是未实现占位，其余 9 条属于整包隐藏。**`InitiateBolusRequest`(-98) 不在这个名单里**——它一直显示在列表里、也一直有对话框，**是发送层把它丢掉的**（层⑤），不是 UI 藏起来了。

| 命令 | 列表可见 | 发送层 |
|---|---|---|
| `InitiateBolusRequest` (-98) | ✅ 可见、有对话框 | ❌ 被 `isBolusCommand` 丢弃 |
| `CancelBolusRequest` (-96) | ✅ 可见 | ✅ 不拦（A4 实测 200 成功） |
| `BolusPermissionRequest` (-94) | ✅ 可见 | ✅ 不拦（A1 实测成功） |

#### 9.4 HTTP 调用样例（**当前不可用**，需先做 §9.5 方案 A）

现状原样调用 → 30 秒超时、未输注：

```bash
curl -s -u user:pass -H 'Content-Type: application/json' \
  -d '{"cargo":"00000000e80300000800000000000000000000000000000000000000000000000000000000","opCode":-98,"characteristic":"CONTROL"}' \
  http://<手机IP>:18282/api/pump/messages
```

> `characteristic` 写 `CONTROL` 或 `BULK` 结果相同：判定在类型上，不在通道上（§9.1）。
> `bolusId` 必须换成**刚由 -94 `BolusPermissionRequest` 拿到的值**，否则泵会拒绝。

三组 37 字节 cargo（`bolusId` 占位 1000 = 0x03E8，实际要替换）：

| 场景 | 参数 | cargo hex |
|---|---|---|
| 干跑 0/0/0 | total=0, ext=0, secs=0, bitmask=8 | `00000000e80300000800000000000000000000000000000000000000000000000000000000` |
| 0.05 U 立即 | total=50, ext=0, secs=0, bitmask=8 | `32000000e80300000800000000000000000000000000000000000000000000000000000000` |
| 方波 0:100（0 立即 + 0.4 U 延后 / 2 h） | total=0, ext=400, secs=7200, bitmask=12 | `00000000e80300000c0000000000000000000000000000000090010000201c000000000000` |

> **bitmask 以 1.9.0 为准**：`FOOD1=1`、`CORRECTION=2`、`EXTENDED=4`、`FOOD2=8`；方波 = `EXTENDED|FOOD2 = 12`。
> 旧 §8 中 A2 用的 `bitmask=3` 是错的（3 = FOOD1|CORRECTION），作废。

响应读取：返回体 `response` 数组是 `Message.jsonToString()`，关注 `opCode`、`bolusId`、`statusId`/`reasonId`、`totalVolume`、`extendedVolume`、`extendedSeconds`；超时体现在 `exceptions[].TimeoutException`。44/46 需另发 `CurrentBolusStatusRequest` / `ExtendedBolusStatusRequest`（不会随本批自动带回），在 Debug 页响应列表或 logcat 里看是否出现**同一个 `bolusId`**。

#### 9.5 三种绕过办法与推荐

| # | 改法 | 改哪些文件 | 影响面 | 风险 | 评价 |
|---|---|---|---|---|---|
| **A（推荐）** | HTTP 显式 `allowBolus` → 新增 `SEND_PUMP_COMMANDS_BULK_ALLOW_BOLUS`，仅该分支跳过 `isBolusCommand`；其余前置不变（`prefInsulinDeliveryActions` + 有效 `bolusId` + packetize 的 `modifiesInsulinDelivery` 闸门） | `HttpDebugApiService.kt`、`CommService.kt`、`CommServiceCodes.kt`、`PumpCommHandler.kt`（约 30 行） | 只影响显式带标记的调试请求；正式流程 0 影响 | 调试接口本就在同网段 + Basic Auth 下，已能发 -96/工厂复位；此处只多开 -98 | **推荐：可测、可回滚、不碰生产链路** |
| B | Debug 页改走 `MessagePaths.TO_SERVER_BOLUS_REQUEST_PHONE`（正式签名链路） | `Debug.kt`、`CommService.kt`，复用 `BolusManager` 确认流程 | 触发正式确认对话框与 `initiateBolusSecret` 签名 | 与正式会话共用状态，调试时可能干扰正在进行的输注 | 备选（A 的 packetize 闸门实测过不去再用） |
| C | 实现 `debug-write-bt-characteristic`（现为 stub），直接写 CONTROL 原始包 | `HttpDebugApiService.kt`、`PumpCommHandler.kt` | 需自行拼完整 61 字节带 HMAC 包、维护 `txId` 与泵时间；拿不到请求-响应绑定 | 风险最高，易写坏泵状态 | **不推荐** |

**推荐结论：先做 A（HTTP 优先），失败再上 B。**

#### 9.6 本轮已确认 / 未确认

- **已确认（代码层）**：`-98` 有 4 个 bulk 发送点会丢弃；HTTP 只走 bulk；`CONTROL` 不改变结果；`debug-write-bt-characteristic` 是 stub；Debug 列表过滤只影响可见性且不含 -98。
- **已确认（2026-11-06 真机 HTTP）**：接口可用且 Basic Auth 生效；只读命令 44/46 正常往返；`-98` 干跑无任何响应（客户端 65 s 超时，零字节），而同为 CONTROL 的 `-96` 0.47 s 正常回包 → 发送层静默丢弃成立。
- **未确认（需真机）**：`-98` 带有效 `bolusId` 与 37 字节 cargo 时泵是否接受；开启 `prefInsulinDeliveryActions()` 后 `Packetize` 闸门是否放行；44/46 是否回同一 `bolusId`（11-06 无活动大剂量，两者均为 0，未验证）；-98 的响应 opcode 与 `statusId` 语义。

#### 9.7 2026-11-06 实测记录（真机 HTTP）

环境：手机 `192.168.8.52:18282`，Basic Auth `admin:123456`。`GET /` 返回 `version 1.0.0-20260504`、`buildVersion fe8a887`、`buildTime 2026-11-05T00:21:17+08:00`、`pumpx2 1.9.0`。

| 步骤 | 请求 | 结果 |
|---|---|---|
| 鉴权 | `GET /api/pump/connected`，不带 Authorization | **401**（0.37 s） |
| 鉴权 | 同上，带 Basic Auth | **200**（0.23 s），`pumpConnected=true` |
| 只读 44 | `{"cargo":"","opCode":44,"characteristic":"CURRENT_STATUS"}` | **200**（0.40 s）；`bolusId=0`、`requestedVolume=0`、`statusId=0`、`bolusTypeBitmask=0` |
| 只读 46 | `{"cargo":"","opCode":46,"characteristic":"CURRENT_STATUS"}` | **200**（1.09 s）；`bolusId=0`、`requestedVolume=0`、`duration=0`、`bolusStatus=0` |
| **-98 干跑** | 37 B cargo（total=0/ext=0/secs=0，bitmask 8，bolusId 占位 1000），`characteristic=CONTROL` | **HTTP 000，65.0 s 客户端超时，零字节响应**（无 header、无 body）。共 3 次（45 s / 120 s / 65 s）均零响应 |
| -96 对照 | `{"cargo":"e8030000","opCode":-96,"characteristic":"CONTROL"}` | **200**（0.47 s）；`CancelBolusResponse: bolusId=1000, statusId=1, reasonId=2` |
| -98 解析对照 | 同上但 cargo 只有 1 字节 `"00"` | **400**（13.7 s），说明请求能被解析层正常拒绝，不是路由问题 |

三句结论：

1. **接口可用**：Basic Auth 生效（无凭据 401、带凭据 200），只读命令稳定往返，`POST /api/pump/messages` 的 body 格式与 §9.4 一致。
2. **44/46 的 `bolusId` 同源性未验证**：实测两次都是 `0`（当时无进行中的大剂量），只能确认两命令都能回包，不能确认「同一笔」。
3. **`-98` 确认为 App 发送层静默丢弃**：同一 CONTROL 通道下 `-96` 0.47 s 就回包，而 `-98` 永远无响应（65 s 零字节）——泵未收到该请求；与 §9.1 的 `isBolusCommand` 代码结论一致。

> 本记录仅为 HTTP 取证，未发送任何带剂量的大剂量命令；`-98` 仍未在真机被泵接受，不得写成"已验证"。

> **例外说明（2026-11-06）**：`mobile-release_pumppan_probe.apk` 是按用户明确请求出的正式签名包（`:mobile:assembleRelease`，沿用仓库 `debug.keystore`，`applicationId` 与 `versionCode` 均未改），仅用于 S6-prep 探针取证。**这是用户明确的一次例外，不是惯例**；后续出包仍按常规命名与流程执行。

