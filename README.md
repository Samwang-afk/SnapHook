# Snap Hook

Paper 1.21 服务端钩索插件 — 绊线钩材质、附魔光效、4 阶段状态机、弹性物理、Strike 俯冲打击。

## Demo

https://github.com/user-attachments/assets/d4d7537f-c224-4b39-977c-9e76a91725ea8

## 特性

- **4 阶段状态机**：FLYING（钩索飞行）→ ANCHORED（3 秒锁定窗口）→ LAUNCHING（线性加速拉拽）/ STRIKE（鞘翅俯冲打击）
- **距离 HUD**：手持 Snap Hook 时 ActionBar 实时显示准星目标距离，绿色可达/红色超距
- **鞘翅 Strike**：鞘翅滑翔中俯角 >20° 钩中目标 → 高空陨落撞击 + AOE 爆炸
- **Space/跳跃脱钩**：飞行中按 Space 脱离，保留惯性 + 返还 70% 冷却
- **实体追踪**：钩中实体后每 tick 更新锚点位置，命中造成 1❤ 伤害
- **边缘弹升**：拉拽遇方块边缘自动弹起防卡墙
- **耐久系统**：50 次使用，每次 -1 耐久，Strike 额外 -1，耗尽消失
- **20 个进度**：从首次合成到陨石杀玩家自动触发
- **合成配方**：线×2 + 铁块×2 + 绊线钩×1 + 烈焰粉×1

## 需求

- Paper 1.21+
- Java 21
- `server.properties` 中 `allow-flight=true`（用于检测 Space 脱钩）

## 安装

1. 将 `SnapHook-1.0.0.jar` 放入 `plugins/`
2. 在 `server.properties` 设置 `allow-flight=true`
3. 重启服务器

## 操作指南

| 操作 | 阶段 | 效果 |
|---|---|---|
| **右键** | 空闲 | 发射钩索，进入 FLYING |
| **左键** | ANCHORED | 立即拉拽，进入 LAUNCHING |
| **Shift** | ANCHORED | 取消钩索，无冷却 |
| **Shift** | LAUNCHING / STRIKE | 取消拉拽，刹车 + 满冷却 |
| **Space / 跳跃** | LAUNCHING / STRIKE | 脱钩，保留惯性，返还 70% 冷却 |
| **双击 Space** | 飞行中 | 同 Space 脱钩 |

## 状态机

```
右键命中
  │
  ▼
FLYING ─────────────────────────────┐
 钩索飞行，绳子从手向目标伸长        │
 totalFlyingTicks 走完 → 自动转入    │
                                    │
 ∟ 鞘翅滑翔 + 俯角 >20°             │
   → 跳过 ANCHORED/LAUNCHING         │
   → 直入 STRIKE                     │
                                    │
           ▼                        │
ANCHORED ────────────────────────────┘
 3 秒窗口，倒计时显示
 左键 → LAUNCHING
 超时/Shift/F键 → 取消

           ▼
LAUNCHING
 3 tick 锚定 + 叮音效
 线性加速：speed = 0.65 + tick×0.22, max 3.25
 着陆区 <4 格：smoothstep 减速
 到达：弹跳 + 防摔

           ▼
STRIKE
 每 tick 沿钩索方向加速 0.55
 落地：AOE 半径 4.5，伤害 1.2×速度
```

## 距离 HUD

空闲时手持 Snap Hook 的 ActionBar：

| 情况 | 显示 |
|---|---|
| 无目标 | `距离: 无目标`（红） |
| ≤ 300 格 | `距离: 12.3格`（绿） |
| > 300 格 | `超距: 350.0格`（红） |

## 取消机制

| 方式 | 刹车 | 冷却 | 惯性 |
|---|---|---|---|
| Shift（ANCHORED） | 否 | 无 | 保留 |
| Shift（LAUNCHING） | 是 | 3.5s | 否 |
| Space/跳跃 | 否 | 1.05s | ✅ + Y≥0.35 |

Space 检测：临时给予 `allowFlight=true`，双击空格触发 `PlayerToggleFlightEvent`。

## 物理参数

| 参数 | 值 |
|---|---|
| 最大距离 | 300 格 |
| 拉拽起步速度 | 0.65 m/s |
| 拉拽加速度 | 0.22 m/s²/tick |
| 拉拽极速 | 3.25 m/s |
| 鞘翅加速度 | 0.42 m/s²/tick |
| 鞘翅极速 | 4.8 m/s |
| Strike 加速度 | 0.55 m/s²/tick |
| Strike 极速 | 5.6 m/s |
| 着陆减速区 | 4 格 |

## 实现架构

```
src/main/java/com/snaphook/
├── SnapHookPlugin.java    (180 行) — 主类、PDC 标记、耐久、配方、进度
└── SnapHookListener.java  (784 行) — 全游戏逻辑
```

### SnapHookPlugin

- `isSnapHook(ItemStack)` — 通过 PDC key `snap_hook` → INTEGER → 1 识别物品
- `createSnapHook()` — 物品工厂：绊线钩 + 附魔光效 + 耐久 50 + Lore
- `damageSnapHook()` — 耐久扣减，耗尽则销毁
- `registerRecipe()` — 合成配方注册
- `registerAdvancement()` — 20 个进度程序化注册

### SnapHookListener — 核心模块

| 模块 | 方法 | 功能 |
|---|---|---|
| **Tick 驱动** | `tick()` | 冷却倒计时、状态机调度、距离 HUD |
| **状态机** | `tickFlying / tickAnchored / tickLaunching / tickStrike` | 4 阶段逐 tick 处理 |
| **射线检测** | `onRightClick` → `rayTraceBlocks + rayTraceEntities` | 最远 300 格，方块优先 |
| **实体钩索** | `refreshEntityTarget` | 每 tick 更新实体锚点位置 |
| **鞘翅物理** | `buildPullVelocity` | 叠加现有速度而非覆盖 |
| **边缘弹升** | `liftOverEdge` | 前方有方块 → Y 弹 1.05 |
| **Strike** | `strikeImpact` | AOE 爆炸 + 冲击波粒子 |
| **取消** | `cancelPull / releasePull / cancelAnchor` | 三种退出策略 |
| **飞行检测** | `enableHookFlight / restoreHookFlight` | 临时 allowFlight 实现 Space 检测 |
| **渲染** | `spawnRopeBeam / spawnRopeLine / spawnAnchorPulse` | 绳子起点为手持位置，动态粒度 ≤48 粒子 |
| **FOV** | `applyFovBoost` | 根据速度动态 Speed 等级 |

## 合成配方

```
S B S
B H B
S P S

S = 线 (String)
B = 铁块 (Iron Block)
H = 绊线钩 (Tripwire Hook)
P = 烈焰粉 (Blaze Powder)
```

## License

MIT
