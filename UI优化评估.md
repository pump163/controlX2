# ControlX2 UI 优化评估

## 现状

**框架**：Jetpack Compose + Material 3（谷歌官方最新方案）
**问题**：布局设计粗糙，间距不统一，暗黑模式适配不全，组件样式不一致

## 文件统计

总共 45 个 kt 文件，分 4 类：

| 类别 | 数量 | 代表文件 |
|------|------|----------|
| 主页面 | 7 | Landing, Dashboard, Settings, Debug, AppSetup, FirstLaunch, PumpSetup |
| 操作页 | 6 | Actions, BolusWindow, TempRateWindow, CartridgeActions, ProfileActions, CGMActions |
| 设置页 | 7 | SoundSettings, ControlIQSettings, QuickBolusSettings, SafetyLimits, FeatureFlags, NightscoutSettings, XdripSettings |
| 组件 | 25+ | cards, dialogs, icons 等 |

## 优化方案

### 1. 统一设计系统（1-2 天）
- 定义统一的颜色、间距、字体、圆角
- 提取通用组件（统一的 ListItem、Card、Dialog 样式）

### 2. 设置类页面统一风格（2-3 天）
- 7 个设置页，都是列表 + 开关 + 点击跳转
- 可以用同一个模板，只改内容

### 3. 操作类页面统一风格（2-3 天）
- 6 个操作页，都是按钮 + 状态显示
- 可以用同一个模板

### 4. 首页单独设计（2-3 天）
- Dashboard 是最常用的，需要特殊设计
- CGM 图表、IOB 卡片、泵状态条

### 5. 组件统一（1-2 天）
- cards, dialogs, icons 统一样式

### 6. 暗黑模式适配（1-2 天）
- 检查所有硬编码颜色，改成随主题

### 7. 测试调整（2-3 天）

## 总工作量

**11-16 天，约 2-3 周**

## 难度

中等，因为是在现有代码上优化，不用重写。

## 风险

改动大，可能引入新 bug，需要仔细测试。

## 建议

1. 先从设置类页面开始（最简单，统一模板）
2. 再做操作类页面
3. 最后做首页（最复杂，最常用）
