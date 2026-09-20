# Mobi 独有命令清单（pumpx2 v1.9.0）

> 从 `pumpx2-messages-v1.9.0.jar` 扫描 `supportedDevices=MOBI_ONLY` 的 Request 类
> 共 13 个，X2 泵不支持

## 胰岛素输注控制

### 1. SuspendPumpingRequest（op -100）
- 暂停胰岛素输注
- X2 上显示"此型号的设备不支持暂停输注"

### 2. ResumePumpingRequest（op -102）
- 恢复胰岛素输注
- X2 上显示"此型号的设备不支持恢复输注"

### 3. SetTempRateRequest（op -92）
- 设置临时基础率
- X2 上显示"此型号的设备不支持设置临时基础率"

### 4. StopTempRateRequest（op -90）
- 停止临时基础率
- X2 上显示"此型号的设备不支持停止临时基础率"

## 储药器/管路

### 5. FillCannulaRequest（op -104）
- 填充软管（Fill Cannula）
- X2 上显示"此型号的设备不支持填充软管"

## 用户模式/计划

### 6. SetModesRequest（op -52）
- 设置用户模式（运动/睡眠等）
- X2 上运动/睡眠开关灰色不可点

### 7. SetSleepScheduleRequest（op -50）
- 设置睡眠计划
- X2 上不支持

## IDP（胰岛素输送计划？）

### 8. CreateIDPRequest（op -26）
- 创建 IDP

### 9. DeleteIDPRequest（op -82）
- 删除 IDP

### 10. RenameIDPRequest（op -88）
- 重命名 IDP

### 11. SetActiveIDPRequest（op -20）
- 设置当前激活的 IDP

## CGM

### 12. CgmStatusV2Request（op -66）
- CGM 状态 V2 版本
- X2 用 V1 版本（CgmStatusRequest）

## 其他

### 13. UnknownMobiOpcode110Request（op 110）
- 探测占位命令，未公开功能

---

## 说明
- 这些命令在 Mobi 泵上支持，X2 泵固件不执行
- 代码里 `supportedDevices=MOBI_ONLY`，App 自动检测并禁用对应 UI
- 对应汉化那 5 处"此型号的设备不支持 XXX"文案
