# AI 开发工具使用指南

> GStack + Superpowers 双工具协作手册

---

## 一、工具定位对比

| | **Superpowers** | **GStack** |
|---|---|---|
| **性质** | 开发方法论 + 自动触发工作流 | 工具集合（CLI + Skills） |
| **触发方式** | **自动触发**（基于上下文检测） | **必须手动输入 `/命令`** |
| **类比** | 像 TDD/敏捷流程，嵌入每次开发 | 像瑞士军刀，按需调用 |
| **安装状态** | 已安装，会话开始自动生效 | 已安装，需显式调用 |

---

## 二、Superpowers 自动触发流程

### 完整开发周期

```
用户想法
   ↓
[brainstorming] ← 写代码前自动激活
   ↓
[using-git-worktrees] ← 设计批准后自动创建隔离工作区
   ↓
[writing-plans] ← 拆解成 2-5 分钟的小任务
   ↓
[subagent-driven-development] 或 [executing-plans] ← 执行任务
   ↓
[test-driven-development] ← TDD 循环（红-绿-重构）
   ↓
[requesting-code-review] ← 任务之间评审
   ↓
[verification-before-completion] ← 确保修复真正有效
   ↓
[finishing-a-development-branch] ← 完成时的收尾工作
```

### 触发规则

Superpowers 在检测到相关上下文时**自动触发**，不是建议，是强制流程。

---

## 三、GStack 命令分类

### 分类总览

#### 规划阶段（Plan）
| 命令 | 功能 | 常用度 |
|------|------|--------|
| `/office-hours` | 6 个尖锐问题重构产品思路 | ⭐⭐⭐ 常用 |
| `/autoplan` | CEO→设计→工程 完整规划 | ⭐⭐ 较常用 |
| `/plan-ceo-review` | 战略挑战，4 种范围模式 | ⭐ 偶尔 |
| `/plan-eng-review` | 架构/数据流/测试评审 | ⭐ 偶尔 |
| `/plan-design-review` | UI/UX 设计评审 | ⭐ 不常用 |
| `/plan-devex-review` | 开发者体验评审 | ⭐ 不常用 |

#### 评审阶段（Review）
| 命令 | 功能 | 常用度 |
|------|------|--------|
| `/review` | 自动找 bug，自动修复明显问题 | ⭐⭐⭐⭐⭐ 最常用 |
| `/codex` | OpenAI 第二意见评审 | ⭐ 偶尔 |
| `/cso` | OWASP 安全审计 | ⭐ 偶尔 |

#### 测试阶段（Test）
| 命令 | 功能 | 常用度 |
|------|------|--------|
| `/qa` | AI 控制真实浏览器测试 | ⭐⭐⭐⭐⭐ 最常用 |
| `/qa-only` | 只报告不修复 | ⭐ 偶尔 |
| `/canary` | 发布后监控 | ⭐ 偶尔 |
| `/benchmark` | 性能对比 | ⭐ 不常用 |

#### 发布阶段（Ship）
| 命令 | 功能 | 常用度 |
|------|------|--------|
| `/ship` | 同步 main、测试、推送、PR | ⭐⭐⭐⭐⭐ 最常用 |
| `/land-and-deploy` | 验证生产环境 | ⭐⭐ 较常用 |
| `/document-release` | 更新项目文档 | ⭐⭐ 较常用 |
| `/document-generate` | 生成缺失文档 | ⭐ 不常用 |

#### 浏览器操作
| 命令 | 功能 | 常用度 |
|------|------|--------|
| `/browse` | AI 控制真实浏览器 | ⭐⭐⭐⭐ 常用 |
| `/open-gstack-browser` | 启动 GStack Browser | ⭐⭐⭐ 常用 |

#### 其他工具
| 命令 | 功能 | 常用度 |
|------|------|--------|
| `/investigate` | 系统性调试方法论 | ⭐⭐ 较常用 |
| `/retro` | 每周复盘 | ⭐ 偶尔 |
| `/learn` | 管理项目知识 | ⭐ 不常用 |
| `/freeze` | 调试时锁定文件编辑范围 | ⭐ 不常用 |
| `/guard` | 安全模式（/careful + /freeze） | ⭐ 不常用 |
| `/pair-agent` | 跨代理协作 | ⭐ 不常用 |

---

## 四、GStack 常用组合

### 组合 1：代码评审 → 修复 → 发布（最常用）
```
/review      # 评审代码，找 bug
    ↓
[自动修复明显问题]
    ↓
/ship       # 测试 + 推送 + PR
```

### 组合 2：新功能完整流程
```
/office-hours     # 6 个问题理清思路
    ↓
/autoplan         # 自动跑完整规划
    ↓
[按计划实现]
    ↓
/review           # 评审代码
    ↓
/qa               # 真实浏览器测试
    ↓
/ship             # 发布
```

### 组合 3：调试问题
```
/investigate   # 系统性调试
    ↓
/review        # 检查相关代码
    ↓
/qa            # 验证修复
```

### 组合 4：发布前检查
```
/cso           # 安全审计（可选）
    ↓
/ship          # 同步 + 测试 + 推送
    ↓
/land-and-deploy  # 验证生产环境
```

---

## 五、你 90% 时间会用到的命令

| 命令 | 为什么常用 |
|------|-----------|
| `/review` | 改完代码就跑，自动找 bug |
| `/qa` | AI 控制浏览器真测，发现问题直接修 |
| `/ship` | 一键完成同步、测试、推送 |
| `/browse` | 需要浏览器操作时（截图、填表等） |
| `/office-hours` | 大功能开始前想清楚 |

---

## 六、Superpowers 技能详解

### 核心技能（自动触发）

#### brainstorming
- **何时触发**：写代码前
- **做什么**：通过 Socratic 提问精炼想法，探索替代方案，分段呈现设计供确认
- **输出**：设计文档

#### writing-plans
- **何时触发**：设计批准后
- **做什么**：拆解成 2-5 分钟的小任务，每个任务包含精确文件路径、完整代码、验证步骤
- **输出**：可执行的计划文件

#### subagent-driven-development
- **何时触发**：有计划后
- **做什么**：每个任务派生子 agent，两阶段评审（规格合规 → 代码质量）
- **特点**：可持续自主工作数小时

#### test-driven-development
- **何时触发**：实现过程中
- **做什么**：强制 RED-GREEN-REFACTOR 循环，先写测试再看失败

#### systematic-debugging
- **何时触发**：遇到 bug 时
- **做什么**：4 阶段根因分析（定位→假设→验证→修复验证）

#### verification-before-completion
- **何时触发**：修复完成后
- **做什么**：确保修复真正有效，不是表面工作

#### requesting-code-review
- **何时触发**：任务之间
- **做什么**：按严重性报告问题，严重问题阻塞进度

#### finishing-a-development-branch
- **何时触发**：任务完成时
- **做什么**：验证测试，提供合并/PR/保留/丢弃选项，清理工作区

### 支持技能

#### using-git-worktrees
- 创建隔离工作区，支持并行开发分支

#### executing-plans
- 批量执行带检查点，适合不需要子 agent 的场景

#### receiving-code-review
- 响应评审反馈

#### writing-skills
- 创建新技能（遵循最佳实践）

---

## 七、GStack vs Superpowers 选择指南

| 场景 | 推荐 |
|------|------|
| "我要开始做新功能" | Superpowers（自动走 brainstorming → plans → 执行） |
| "代码写完了帮我 review 一下" | GStack `/review` |
| "需要真实浏览器测试" | GStack `/qa` |
| "准备发布这个分支" | GStack `/ship` |
| "有个 bug 找不到原因" | Superpowers `systematic-debugging` 或 GStack `/investigate` |
| "帮我审视这个方案" | GStack `/plan-ceo-review` |
| "想清楚再做" | Superpowers `brainstorming` |
| "我要在浏览器里操作" | GStack `/browse` |
| "需要自动执行完整流程" | GStack `/autoplan` |

---

## 八、两者协作模式

```
项目开始
    ↓
Superpowers 自动介入
├── brainstorming（问清楚要做什么）
├── writing-plans（拆解任务）
└── subagent-driven-development（执行）

功能完成
    ↓
GStack 手动介入（按需）
├── /review（评审代码）
├── /qa（测试功能）
└── /ship（发布）
```

**两者互补**：Superpowers 管"什么时候做什么"，GStack 管"用什么工具做"。

---

## 九、关键区别总结

| 问 | Superpowers | GStack |
|---|---|---|
| 需要手动输入吗？ | **不需要，自动触发** | **必须输入 `/命令`** |
| 是流程还是工具？ | 流程（开发方法论） | 工具集合 |
| 谁决定做什么？ | Agent 基于上下文判断 | 用户显式调用 |
| 适合什么场景？ | 全流程管理 | 特定任务执行 |