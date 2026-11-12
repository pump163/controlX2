# pumppan docs 索引

## 目录结构

| 路径 | 放什么 |
|---|---|
| `HANDOFF-pumppan.md` | 当前状态、下一步、环境与产物（接手先读这个） |
| `pumppan-审查修改意见.md` | 外部审查意见与阻断项（B1–B7） |
| `upstream-pr-candidates.md` | 可提上游的英文通用改动候选 |
| `组件目录规范.md` | UI 组件目录与命名规范 |
| `feature/` | 未完成 / 部分完成的功能设计 |
| `bug/` | 未修复 / 待验证的缺陷 |
| `old/` | 已完成、被取代或意义不大的历史文档 |
| `文件位置表.md` | 所有自建 md 的位置索引（跨 5 棵树，找不到文件先看这里） |

## 归档规则（2026-11-04 用户定稿）

1. **已完成 / 意义不大** → 各树自己的 `docs/old/`。
2. **未完成 / 部分完成** → 统一收进本目录（pumppan），按 `feature/`、`bug/` 分类。
3. **文件名重复**时加来源后缀：`_main`（来自 `controlX2-i18n`）、`_dev`（来自 `controlX2-i18n-dev`）、`_lab`（来自 `controlX2-i18n-lab`）、`_zh`（来自 `controlX2-main-zh`）；pumppan 原生的不加。
4. **待办只有一个账本**：lab 的 `i18n/WORKLOG.md`。本目录只写「怎么设计、怎么验证」，不写进度流水。

## 已移入的 `_main` 文档（来自 `controlX2-i18n/docs`）

| 文档 | 原路径 | 原因 |
|---|---|---|
| `feature/泵能力收敛与自建挡板方案_main.md` | `docs/feature/泵能力收敛与自建挡板方案.md` | 建议稿，未实施 |
| `old/UI定制设计_main.md` | `docs/feature/UI定制设计.md` | 旧设计，已过时归档（2026-11-12） |
| `old/UI迁移清单_main.md` | `docs/feature/UI迁移清单-controlX2-main-zh-to-i18n.md` | 部分完成，已归档（2026-11-12） |
| `feature/状态索引_main.md` | `docs/feature/README.md` | 状态索引（已完成 / 否决 / 冲突裁决） |
| `组件目录规范.md` | `docs/组件目录规范.md` | 规范，长期有效 |

`controlX2-i18n/docs` 现在只保留 `i18n/编译避坑清单.md` 与 `i18n/i18n-batch-table.md`（常查手册），其余全部归档到该树 `docs/old/`。
