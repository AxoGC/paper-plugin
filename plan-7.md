# 游戏社区平台 — 项目计划书

- 首要原则：为了降低人类审阅压力，同时降低AI的token消耗量，计划书不准说废话，保持语言清晰简明。
- 此计划书为唯一真理，而且在不断更新。如果存在与代码不一致的情况，大概率是代码过时了。此时你可以先斩后奏，修改代码。

## 一、技术栈

- **后端**：Go 1.22+ / Gin / GORM / JWT / gomail（SMTP）/ bcrypt
- **前端**：pnpm + Nuxt 4（SSR）+ TypeScript / Pinia / TailwindCSS / ECharts / Reka-UI + Lucide / markdown-it + highlight.js + DOMPurify / vee-validate + zod / @nuxtjs/i18n
- **数据库**：PostgreSQL 16（启用 `pg_trgm` 扩展用于轻量搜索）+ Redis 7（启用持久化 AOF，保证会话/refresh 不因重启丢失）
- **部署**：Docker Compose / Nginx 反代 / Let's Encrypt
- **附件存储**：本地文件 + Nginx 静态服务（量大再换 MinIO）
- **邮件**：第三方 SMTP（域名邮箱 / 阿里云邮件推送 / SendGrid 任选）
- **定时任务**：宿主机 cron + Python 脚本
- **代码生成**：Claude Code 主导实现，人工 review 关键路径
- **游戏插件**：MC Java = Kotlin（Paper API + Gradle Kotlin DSL）/ MC Bedrock = TypeScript（Script API）/ DST = Lua 5.1 / Terraria = C#（TShock）
- **环境**：不分开发/生产，单一"开发及生产"环境（MVP 阶段不完全开放）

## 二、架构总览

### 模式

**模块化单体（Modular Monolith）+ 统一 HTTP 接入协议**

- 业务逻辑全部在 core 单进程内，模块间走函数调用
- 游戏服务器全部通过同一套 HTTP 协议直连 core
- 无 bridge / 无适配器中间层（HTTP 是所有游戏环境的最大公约数）

### 容器清单

- nginx
- web（Nuxt 4 Node 进程，承担 SSR；Docker 内网名 `web:3000`）
- core（Go 单体主应用）
- postgres
- redis
- 各游戏服务器容器（独立编排）

### 架构图

```
                ┌──────────────┐
                │    Nginx     │  TLS + 反代
                └──┬───────────┘
                   │
       ┌───────────┴───────────┐
       │ /api/* + /uploads/*   │ 其余路径
       ▼                       ▼
 ┌──────────────┐        ┌──────────────┐
 │     core     │◄───────│     web      │  Nuxt 4 SSR
 │  Go 单体     │  SSR   │  Node 进程   │
 │              │  fetch │              │
 │  user        │        └──────────────┘
 │  forum       │
 │  checkin     │
 │  donation    │
 │  server      │
 │  player/pk   │
 │  admin       │
 │  log         │
 │  search      │  ← PG ILIKE+trigram 轻搜索
 │  serverlink  │  ← 服务器接入层
 └──┬───────────┘
    │
   ┌┴──────┐
   ▼       ▼
┌─────┐ ┌─────┐
│ PG  │ │Redis│
└─────┘ └─────┘

游戏服务器接入（全部直连 core，无中间层）：
   MC Java 插件   ┐
   基岩版 Addon   │  HTTP (/api/srv/poll, /api/srv/reply, /api/srv/*)
   饥荒 Lua mod  ├──────────────────────────────────────────────────→ core
   Terraria mod  │
   ...           ┘

旁路（宿主机 cron 调度）：
   Python 脚本：online_poll / backup / cleanup_logs
```

### 不做的事

不引入 Casbin / asynq / WebSocket / Prometheus / 全文搜索引擎 / MinIO（MVP 阶段）/ API Gateway / 微服务拆分。

## 三、Core 项目结构

```
core/
├── cmd/server/main.go
├── internal/
│   ├── modules/
│   │   ├── auth/                // 注册 / 登录 / 双令牌 / 邮箱验证码
│   │   │   ├── domain.go
│   │   │   ├── ports.go
│   │   │   ├── service.go       // 含 token 颁发、refresh 轮换、邮件验证码核验
│   │   │   ├── handler.go
│   │   │   └── routes.go
│   │   ├── user/
│   │   │   ├── domain.go         // 实体定义
│   │   │   ├── ports.go          // 本模块对外提供的接口 + 本模块依赖的接口
│   │   │   ├── repository.go     // Repository interface
│   │   │   ├── repo_pg.go        // Repository 的 Postgres 实现
│   │   │   ├── service.go        // 业务逻辑，只持有接口字段
│   │   │   ├── handler.go        // HTTP handler
│   │   │   └── routes.go         // 路由注册函数
│   │   ├── forum/
│   │   ├── checkin/
│   │   ├── donation/
│   │   ├── attachment/           // 附件上传 + 元数据查询
│   │   ├── server/
│   │   ├── player/               // 含 PK、排行
│   │   ├── admin/
│   │   ├── log/
│   │   ├── audit/                // Bedrock 行为日志（PG）+ 管理员查询
│   │   ├── config/               // 运行时配置（Redis hash + 热更）
│   │   └── serverlink/           // 服务器接入层
│   ├── shared/
│   │   ├── db/                   // GORM 实例、迁移加载
│   │   ├── redis/                // go-redis 客户端封装
│   │   ├── auth/                 // JWT 签发/解析、bcrypt
│   │   ├── email/                // SMTP 客户端 + 模板（gomail + embed）
│   │   ├── middleware/           // AuthRequired / AdminRequired / RateLimit / ServerTokenAuth
│   │   └── response/             // 统一响应 envelope helper
│   └── app/
│       └── app.go                // 在此 import 所有模块，组装并注入依赖
├── migrations/
├── configs/                      // 默认配置 yaml（兜底，主要走环境变量）
├── Dockerfile
└── go.mod
```

### 模块间通信规则（依赖倒置）

- **模块之间不互相 import 包**
- 每个模块在自己的 `ports.go` 里：
  - 声明**本模块对外暴露的接口**（如 `user.UserLookup`）
  - 声明**本模块需要依赖的接口**（如 donation 模块声明 `UserLookup interface`，不依赖 user 包）
- 具体接口实现的注入只在 `app/app.go` 里发生：
  ```go
  userSvc := user.NewService(userRepo)
  donationSvc := donation.NewService(donationRepo, userSvc /* 作为 UserLookup */)
  ```
- 单元测试时直接 mock 接口，不需要起其他模块

**好处**：
- 编译期防止循环依赖（模块根本不允许互相 import）
- 任一模块未来拆出去成独立服务，只需把接口实现换成 HTTP client，调用方零改动

### 模块自注册路由

每个模块在 `routes.go` 中暴露 `RegisterRoutes(g *gin.RouterGroup, h *Handler)`，由 `app/app.go` 集中挂载。

### Nginx 配置

```nginx
upstream core { server core:8080; }
upstream web  { server web:3000; }

server {
    listen 443 ssl http2;
    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;

    # 后端 API（含 /api/ 和 /api/srv/）
    location /api/ {
        proxy_pass http://core;
        include /etc/nginx/proxy_params;
        # 长轮询要点：proxy_read_timeout 必须 > poll 挂起时长 + 缓冲
        proxy_read_timeout 60s;
        proxy_buffering off;
    }

    # 用户上传的附件（DB 只存相对路径，由 Nginx 直接出静态）
    location /uploads/ {
        alias /data/uploads/;
        autoindex off;
        # 防止可执行类 MIME 在浏览器中作恶
        add_header X-Content-Type-Options nosniff;
        expires 30d;
    }

    # 前端：所有其他请求反代到 Nuxt Node 进程（SSR）
    location / {
        proxy_pass http://web;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    gzip on;
    gzip_types text/css application/javascript application/json;
}
```

## 四、服务器接入协议（HTTP 长轮询）

### 4.1 设计要点

- **路径分组**：所有服务器接入接口在 `/api/srv/*` 下，与前端使用的 `/api/*`（JWT 鉴权）分开。Gin 用两个路由组挂不同中间件，互不冲突。
- **方向**：游戏服务器是客户端，主动调用 core
- **认证**：每台服务器一个 long-lived token，**通过 query 参数 `?token=<server_token>` 传递**（饥荒 Lua `TheSim:QueryServer()` API 不支持自定义请求头，统一走 query 是唯一可行方案）。token 创建时由 core 生成 32 字节随机串，DB 存 bcrypt 哈希，明文只在创建/重置时返回一次
- **接口分两类**：
  - `/api/srv/poll` 和 `/api/srv/reply`：长轮询和回复（命名独占）
  - `/api/srv/<其他>`：游戏端主动上报、查询数据等（直接挂在 `/api/srv/*` 下，不加 `event` 中间路径）
- **异步语义**:`/poll` 拿到事件后无需等处理完即可再 poll；处理逻辑在插件自己的 goroutine/线程里跑
- **一次一个事件**:`/poll` 一次返回一个，`/reply` 一次回复一个，对称，模仿 Go 通道心智模型
- **事件总是带 id**:每个下行事件平台都给一个 `id`。**是否需要 reply 由游戏端插件根据 command 类型自行决定**；平台是否读取 reply 也由 core 内部调用方决定，形成隐式约定。协议层不区分"通知 / 请求"
- **不做确认 / 不做重试**:`/poll` 返回即视为投递完成，事件可能丢失，业务层容忍
- **不保证顺序**:同一服务器的事件可能被并发处理；需要顺序的由 core 在发送端控制（等上一个 reply 回来再下发下一个）

### 4.2 接口集

```
GET  /api/srv/poll?token=xxx              拉取下行事件（长轮询，挂起最多 25-30s）
POST /api/srv/reply?token=xxx             回复事件
POST /api/srv/<action>?token=xxx          游戏端主动上报 / 业务请求（见 4.5 上行表）
GET  /api/srv/<resource>?token=xxx        游戏端拉取数据（见 4.5 上行表）
```

### 4.3 消息格式

```json
// /api/srv/poll 返回 — 任何事件都带 id
{
  "id": "evt_abc123",
  "command": "player.kick",
  "data": { "name": "Steve", "reason": "afk" }
}

// /api/srv/poll 超时无事件
HTTP 204 No Content

// /api/srv/reply 请求体 — 成功
{ "id": "evt_abc123", "ok": true, "data": { ... } }

// /api/srv/reply 请求体 — 失败
{ "id": "evt_abc123", "ok": false, "error": { "code": "PLAYER_NOT_FOUND" } }
```

错误对象只含 `code`（不含 `message`），与平台对前端响应规范保持一致（详见 8.2）。

### 4.4 命名规范

下行 command 和上行 action 名都按 `领域.动作` 风格命名，全小写点分隔。

- 下行 command：`player.kick` / `player.notify` / `player.whitelist.add` ...
- 上行 action：`heartbeat` / `player.joined` / `binding.request` / `leaderboard.update` ...

注意上行 action 直接作为 URL 路径段，例如 `POST /api/srv/player.joined`。点号在 URL 里合法，无需转义。

### 4.5 MVP 范围 command/event 完整表

**下行 command（core → 游戏，通过 `/api/srv/poll` 派发）**

| command | data schema | 需要 reply | 说明 |
|---|---|---|---|
| `player.kick` | `{name: string, reason: string}` | 否 | 踢出玩家 |
| `player.notify` | `{name: string, message: string}` | 否 | 游戏内私聊通知玩家（绑定结果、签到提示等） |
| `player.whitelist.add` | `{name: string}` | 是 `{added: bool}` | 加入白名单（Java/Bedrock 支持） |
| `player.whitelist.remove` | `{name: string}` | 是 `{removed: bool}` | 移出白名单 |
| `player.stats.fetch` | `{name: string}` | 是 `{stats: object}` | 按需查玩家 stats（MC Java 用） |
| `server.broadcast` | `{message: string}` | 否 | 全服广播 |

**上行（游戏 → core，通过 `POST /api/srv/<action>` 主动调用）**

| 路径 | request schema | response data | 说明 |
|---|---|---|---|
| `POST /api/srv/heartbeat` | `{online: int, max: int, players?: string[]}` | `null` | 心跳，刷新 server:status/online/max TTL |
| `POST /api/srv/player.joined` | `{name: string, external_id?: string}` | `null` | 玩家加入通知 |
| `POST /api/srv/player.left` | `{name: string}` | `null` | 玩家离开通知 |
| `POST /api/srv/binding.request` | `{code: string, player: {name: string, external_id?: string}}` | `{user_id: uint}` | 绑定验证码核验，触发 player.notify 回写结果 |
| `POST /api/srv/leaderboard.update` | `{metric: string, entries: [{name: string, score: number}, ...]}` | `null` | 批量上报排行（重建 ZSET） |
| `POST /api/srv/audit.log` | `{logs: [{player: string, action: string, target?: string, pos?: {x,y,z}, detail?: object, ts: int64}, ...]}` | `null` | Bedrock 行为日志（高价值事件） |
| `GET /api/srv/config` | — | `{server_id: uint, name: string, type: string, meta: object}` | 游戏端启动时拉取本服配置 |

响应 envelope 复用前端规范（详见 8.2）：成功 `{"code":"OK","data":...}`，失败 `{"code":"...","data":null}`。

### 4.6 运行参数

- `/api/srv/poll` 阻塞时间：25-30s
- core 的 HTTP `WriteTimeout` > 30s（建议 35s）
- 游戏端 HTTP client `Timeout` > 30s（建议 35s）
- 游戏端必须开启 HTTP keep-alive，复用同一 client
- 队列实现：Redis list `events:server:<id>`，用 `BLPOP` 阻塞拉取
- 单条消息上限：1MB

### 4.7 serverlink 模块职责

- 维护 token → server_id 映射（启动时从 DB 加载到内存，增删改时刷新）
- 暴露 `/api/srv/poll`、`/api/srv/reply` HTTP 端点
- 提供 `ServerTokenAuth` Gin 中间件供 `/api/srv/*` 全组使用
- 对内提供 `SendCommand(serverID, command, data) (reply, error)` 接口给其他模块调用，是否等待 reply 由调用方决定（带超时 / 立即返回）
- 把上行事件（通过 `/api/srv/*`）分发给订阅模块

## 五、前端 UI 规范

### 5.1 技术栈与组件策略

- **框架**：**Nuxt 4 + TypeScript + Pinia + TailwindCSS + ECharts**（与第一章一致）
- **HTTP**：Nuxt 内置 `$fetch` / `useFetch`，不再引入 Axios。自封装 `composables/useApi.ts` 处理响应 envelope、错误码映射、SSR 期间 cookie 透传
- **路由**：Nuxt 文件路由（`pages/` 目录），不再引入 Vue Router
- **组件原语**：**Reka-UI**（unstyled，只管行为与可访问性，样式自撸）。**不**采用 NuxtUI——NuxtUI 自带设计语言会和本计划书的 token 系统冲突
- **图标**：**Lucide**（`lucide-vue-next`），全站只用这一套图标库
- **Markdown**：`markdown-it` + `highlight.js`（代码高亮）+ DOMPurify（净化）
- **表单**：vee-validate + zod
- **国际化**：`@nuxtjs/i18n`
- **不引入**：Element Plus / Ant Design Vue / Naive UI / NuxtUI——避免设计语言打架

Reka-UI 给的是 `<DialogRoot>` / `<PopoverTrigger>` 这种无样式原子，自己包一层带 Tailwind 样式的业务组件（Button / Modal / Toast 等）。所有组件代码归项目所有，可任意改造。**正因为 Reka-UI 是 unstyled 的，组件最小完备集（5.7 节）必须保留**，作为我们自封装组件的清单依据。

### 5.2 设计哲学（五条铁律）

1. **内容优先，UI 退场**：用户产生的内容是主角，容器和装饰能弱化就弱化。
2. **一致性 > 个性**：同一种东西在不同页面长一个样，不为"页面太空"破坏规范。
3. **信息密度分层**：列表页中密度、详情页低密度、管理后台高密度，不全站一个密度。
4. **反馈即时且明确**：任何操作 200ms 内必须有视觉反馈。
5. **深浅双主题，跟随系统**：默认跟随 OS 偏好，用户可手动切换。

### 5.3 美学方向

**现代游戏平台为主 + 萌系插画为辅。**

- **占 95% 面积的常规界面**（导航、卡片、按钮、表单、列表、详情页正文）：严格遵守现代游戏平台规范——冷静、克制、深色为主、强调色高饱和。
- **萌系点缀只允许出现在三处**：
  - 吉祥物 / 空状态插画 / 404 页面
  - 成就和勋章图标（区别于 Lucide 的极简线条）
  - 节日皮肤 Banner（春节、夏日活动等限时主题）
- **插画风格**：清晰净爵类，简单色块（Doro / Capoo 风），不走复杂光影或像素风。
- **色彩约束**：萌系插画不影响色彩系统——颜色规范守住技术感，萌系靠插画形式呈现，两套美学不在同一空间打架。

### 5.4 设计令牌（Design Tokens）

#### 5.4.1 颜色系统

组件**只引用语义层 token**（`--bg-elevated` / `--text-primary` 等），不直接写色值。

```css
/* ========== 深色主题（默认） ========== */
:root[data-theme="dark"] {
  /* 背景层级（从底到顶） */
  --bg-base:       #0f1115;
  --bg-elevated:   #181b22;
  --bg-overlay:    #232730;
  --bg-hover:      #2a2f3a;

  /* 文字层级 */
  --text-primary:   #e8eaed;
  --text-secondary: #a8adb8;
  --text-tertiary:  #6b7280;
  --text-disabled:  #4b5563;

  /* 边框 */
  --border-subtle:  #232730;
  --border-default: #2f343f;
  --border-strong:  #3f4654;

  /* 品牌色（强调色，组织传统色） */
  --brand-500: #28abce;     /* 主色 */
  --brand-400: #4dc0df;     /* 悬浮 */
  --brand-600: #1f8aa8;     /* 按下 */
  --brand-soft: #0e2a35;    /* 主色背景态（选中行底、tag 底）*/
  --brand-on:   #ffffff;    /* 主色背景上的文字 */

  /* 状态色 */
  --success:  #10b981;
  --warning:  #f59e0b;
  --danger:   #ef4444;
  --info:     #3b82f6;      /* 与 brand 同族但更蓝，专用于纯信息提示 */

  /* 阴影（深色主题极弱，主要靠边框区分层级） */
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.4);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.5);
}

/* ========== 浅色主题 ========== */
:root[data-theme="light"] {
  --bg-base:       #f6f7f9;
  --bg-elevated:   #ffffff;
  --bg-overlay:    #f1f3f5;
  --bg-hover:      #eceef2;

  --text-primary:   #1a1d23;
  --text-secondary: #4b5563;
  --text-tertiary:  #6b7280;
  --text-disabled:  #9ca3af;

  --border-subtle:  #eef0f3;
  --border-default: #e1e4e9;
  --border-strong:  #c8ccd3;

  /* 浅色下主色加深一档保证按钮文字对比度 */
  --brand-500: #1f8aa8;
  --brand-400: #28abce;
  --brand-600: #176d85;
  --brand-soft: #e0f2f8;
  --brand-on:   #ffffff;

  /* 浅色状态色全部加深 */
  --success:  #059669;
  --warning:  #d97706;
  --danger:   #dc2626;
  --info:     #2563eb;

  --shadow-sm: 0 1px 2px rgba(0,0,0,0.06);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.08);
  --shadow-lg: 0 12px 24px rgba(0,0,0,0.10);
}
```

**关键规则**：
- 全站**只一个品牌色**（`#28abce`），强调色不泛滥
- `--info` 与品牌色刻意区分（一个偏青、一个偏蓝），避免"系统消息"和"主操作"撞色
- 浅色不是深色反相——浅色底用 `#f6f7f9` 浅灰而不是纯白，文字用 `#1a1d23` 而不是纯黑

#### 5.4.2 间距系统（4px 基础单位）

```
--space-1: 4px    --space-2: 8px    --space-3: 12px
--space-4: 16px   --space-5: 20px   --space-6: 24px
--space-8: 32px   --space-10: 40px  --space-12: 48px
--space-16: 64px  --space-20: 80px
```

Tailwind 默认就是这套，直接用 `p-4 gap-6`，**禁止 `padding: 13px` 这种魔法数字**。

#### 5.4.3 圆角（全站只这 5 个）

```
--radius-sm: 4px      /* tag、小标签 */
--radius-md: 8px      /* 按钮、输入框 */
--radius-lg: 12px     /* 卡片 */
--radius-xl: 16px     /* 弹窗、大块容器 */
--radius-full: 9999px /* 头像、圆形按钮 */
```

#### 5.4.4 字号与行高

```
--text-xs:   12px / 16px   /* 标签、时间戳 */
--text-sm:   14px / 20px   /* 副文、说明 */
--text-base: 16px / 24px   /* 正文（默认）*/
--text-lg:   18px / 28px   /* 副标题 */
--text-xl:   20px / 28px   /* 卡片标题 */
--text-2xl:  24px / 32px   /* 页面小标题 */
--text-3xl:  30px / 36px   /* 页面大标题 */
```

正文坚持 16px，长期阅读不累眼。

#### 5.4.5 字体栈

```css
--font-sans: 'Noto Sans SC', 'Source Han Sans SC', -apple-system,
             BlinkMacSystemFont, 'Segoe UI', sans-serif;
--font-mono: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
```

中文主字体：**思源黑体（Noto Sans SC）**。通过 CDN 或本地 woff2 引入，按子集裁剪。

### 5.5 布局框架

#### 5.5.1 三种顶层布局

**A. 桌面 / 平板 — 内容站布局**（首页、论坛、服务器列表、个人主页）

```
┌─────────────────────────────────────────┐
│         顶部副栏（固定，64px 高）         │   Logo、搜索、用户头像、通知
├──────┬──────────────────────────────────┤
│ 主栏 │                                  │
│ 240px│         主内容区                  │   最大宽度 1280px 居中
│ 固定 │                                  │
│ 在左 │                                  │
└──────┴──────────────────────────────────┘
```

**B. 移动端 — 内容站布局**

```
┌─────────────────────────────────────────┐
│       顶部副栏(48px) - Logo + 搜索        │
├─────────────────────────────────────────┤
│                                         │
│            主内容区                      │
│                                         │
├─────────────────────────────────────────┤
│       底部主栏(56px) - Tab Bar           │   首页 / 论坛 / 服务器 / 我的
└─────────────────────────────────────────┘
```

**C. 详情页布局**（特定用户、特定帖子、特定服务器）

- **移动端**：完全沉浸式。顶栏只有"返回按钮 + 页面标题（可选右上角操作菜单）"，**底部主栏隐藏**。参考 Twitter / 小红书 / Bilibili App。
- **桌面 / 平板**：**全局顶栏保留**（Logo、搜索、用户头像不可消失），主侧栏消失，主内容区变宽。返回按钮放在内容区左上角。

**D. 管理后台布局**

```
┌─────────────────────────────────────────┐
│ Logo │     顶部信息（用户、通知）         │
├──────┼─────────────────────────────────┤
│ 左侧 │                                  │
│ 菜单 │    主内容区（宽度铺满，不限 1280）│
│ 240px│                                  │
└──────┴─────────────────────────────────┘
```

管理后台数据多，内容宽度铺满。< 640px 直接给"请在桌面端使用"提示，不硬做移动端管理。

#### 5.5.2 响应式断点

```
sm:  640px  /* 手机横屏 */
md:  768px  /* 平板 */
lg:  1024px /* 小笔记本 */
xl:  1280px /* 标准桌面 */
```

- `< 768px`：内容站切到布局 B（底部 Tab Bar）
- `>= 768px`：内容站切到布局 A（左主栏）
- 进入详情页：按 5.5.1 C 切换

#### 5.5.3 栅格

不用 12 栅格，直接 flex / grid + 间距 token。

#### 5.5.4 导航栏内容定义

**顶部副栏（A 布局，从左到右）**：

```
[Logo→/] [主页] [论坛] [服务器] [捐赠]   ......   [搜索框] [头像下拉]
```

- **左侧**：Logo（点击回首页）+ 主导航项（主页 / 论坛 / 服务器 / 捐赠）
- **右侧**：搜索框 + 用户头像下拉（未登录显示"登录/注册"按钮）
- **通知图标 MVP 不放**——通知中心排在 P1，做出来再放右侧（搜索框左边）

**搜索框行为**：调用 `GET /api/search?q=...&type=post|user|server`，使用 PG `ILIKE` + `pg_trgm` 扩展实现，覆盖**帖子标题 / 用户名 / 服务器名**三类（不覆盖帖子正文、评论；MVP 阶段不上独立搜索引擎容器）。

**左主栏（A 布局，桌面/平板）**：A 布局中左主栏与顶栏分担导航职责。MVP 阶段左主栏**只承载二级/上下文导航**（例如论坛分区列表、我的 → 签到/绑定/设置），主导航仍在顶栏，避免重复。详情页（C 布局）左主栏隐藏。

**移动端底部 Tab Bar（B 布局）**：

```
[首页]  [论坛]  [服务器]  [我的]
```

捐赠榜在移动端通过"首页"或"我的"页内入口进入，不占 Tab Bar 槽位。

**ADMIN 左侧菜单**：

```
▸ 数据概览
▸ 用户管理
▸ 服务器管理
▸ 捐赠录入
▸ 操作日志
▸ Bedrock 行为日志
▸ 运行时配置
```

### 5.6 主题切换规则

- **三档**：`light` / `dark` / `system`（默认 `system`）
- **存储**：用户偏好持久化到 `localStorage`，key 为 `theme-preference`
- **应用**：在 `<html>` 标签上设置 `data-theme="dark|light"`
- **跟随系统**：`system` 档监听 `prefers-color-scheme` 媒体查询并响应切换
- **避免闪屏**：`index.html` 内联一段同步脚本，在 Vue 启动前就设好 `data-theme`

实现写在 `composables/useTheme.ts`，由 `app.vue` 顶层调用一次。

### 5.7 组件最小完备集

只做这些，每加新组件先问"现有的能不能满足"：

| 组件 | 变体 |
|---|---|
| Button | primary / secondary / ghost / danger，尺寸 sm/md/lg |
| Input / Textarea | default / error |
| Select | default |
| Card | default |
| Modal | default / confirm |
| Toast | success / error / info / warning |
| Tag / Badge | default / 状态色 |
| Avatar | sm / md / lg |
| Tabs | default |
| Table | default |
| Skeleton | line / block / card |
| Empty | default |
| Pagination | default |
| Dropdown / Menu | default |
| Tooltip | default |

**铁律**：要"特殊"就在规范里加 variant 让全站可复用，禁止在某个页面写一次性样式。

**列表呈现优先级**：**卡片网格 > 表格**。列表型数据（论坛列表、服务器列表、用户列表、捐赠榜等）优先用卡片网格。Table 组件主要用于**管理后台**（信息密度高、需要多列对齐、批量操作场景）。

### 5.8 反馈规范

| 场景 | 形式 |
|---|---|
| 成功操作 | Toast 右上角，2 秒，绿色 |
| 失败操作 | Toast 右上角，4 秒，红色 |
| 危险操作前 | Modal 确认（删除、解绑、踢人） |
| 耗时操作 | 按钮内置 loading，不弹全屏遮罩 |
| 页面加载 | Skeleton 占位，不用 spinner |
| 空数据 | Empty 组件 + 说明 + 引导操作 |

### 5.9 目录结构（Nuxt 4）

```
web/
├── app/
│   ├── pages/                  # 文件路由（替代 Vue Router）
│   ├── layouts/                # 布局组件（content/detail/auth/admin）
│   ├── components/
│   │   ├── ui/                 # 基础组件（Button / Card / Input ...）
│   │   ├── layout/             # 布局子组件（AppHeader / AppTabBar / AppSidebar ...）
│   │   └── feature/            # 业务组件（PostCard / ServerCard / PlayerStatsRadar ...）
│   ├── composables/
│   │   ├── useApi.ts           # 统一 envelope/错误码/SSR cookie 透传
│   │   ├── useAuth.ts          # access 管理 + 401 拦截 + 刷新队列
│   │   ├── usePolling.ts       # 长轮询封装 + visibilitychange
│   │   └── useTheme.ts
│   ├── stores/                 # Pinia
│   ├── middleware/             # 路由中间件（auth / admin）
│   └── app.vue
├── i18n/
│   ├── locales/                # 11 种语言 JSON
│   │   ├── en/{common,errors}.json
│   │   ├── zh-CN/{common,errors}.json
│   │   └── ... (zh-TW/ja/ko/ru/fr/es/pt/de/eo)
│   └── i18n.config.ts
├── styles/
│   ├── tokens.css              # 全部 CSS 变量（5.4 节）
│   ├── themes.css
│   └── globals.css
├── public/                     # 静态资源（favicon / 萌系插画 PNG/SVG）
├── nuxt.config.ts
├── tailwind.config.ts
├── package.json
└── Dockerfile
```

**铁律**：
- `ui/` 组件**禁止包含业务逻辑**（不 fetch、不引 store）
- `feature/` 组件**只能由 ui/ 组合而成**，不从零写样式
- `pages/` 页面**只组合 layout + feature**，几乎不写样式

### 5.10 落地顺序

不要现在把所有令牌、组件写完——过度设计。按这个顺序：

1. 先把 5.4 节的 token 全部写进 `styles/tokens.css`（约 100 行）+ Tailwind config 接入
2. 写 5 个核心组件：Button、Card、Input、Modal、Toast
3. 跑通登录、发帖两个页面
4. 回看：哪些组件确实复用了、哪些 token 用得最多，再补
5. 建一个 `/dev/components` 路由展示所有组件（防 UI 走样的最强工具）

### 5.11 前端路由表（Nuxt 文件路由）

Nuxt 4 通过 `app/pages/` 目录推导路由；每页用 `definePageMeta({ layout, middleware, ssr })` 声明布局、鉴权和渲染模式。

布局列指引用 5.5.1 节中的布局类型：
- **A**：桌面/平板左主栏 + 顶部副栏（移动端自动切到 B）
- **B**：移动端底部 Tab Bar（桌面端自动切到 A）
- **C-D**：详情页（移动端沉浸式、桌面端保留全局顶栏，无主侧栏）
- **AUTH**：极简居中布局，无任何主副栏
- **ADMIN**：管理后台布局（左侧菜单，宽度铺满）

**渲染列**（详见 5.12 节）：
- **SSR**：服务端渲染，对 SEO 友好。**按匿名用户渲染**——SSR 期间不读 cookie 不调 auth，登录态相关 UI 由客户端 hydration 后再补
- **CSR**：纯客户端渲染（`ssr: false`），不参与首屏 HTML 生成

| 路径 | name | 布局 | 渲染 | 鉴权 | 说明 |
|---|---|---|---|---|---|
| `/` | `home` | A/B | SSR | 公开 | 首页（公告、热帖、在线服务器概览） |
| `/login` | `login` | AUTH | CSR | 公开 | 登录 |
| `/register` | `register` | AUTH | CSR | 公开 | 邮箱注册（含验证码步骤） |
| `/forums` | `forums` | A/B | SSR | 公开 | 分区列表（只展示分区 + 统计：今日新增/7日活跃/总帖数） |
| `/forums/:slug` | `forum.posts` | A/B | SSR | 公开 | 分区详情（论坛信息 + 帖子流） |
| `/posts/new?forum_id=` | `post.new` | A/B | CSR | 登录 | 发帖 |
| `/posts/:id` | `post.detail` | C-D | SSR | 公开 | 帖子详情（详情页布局） |
| `/servers` | `servers` | A/B | SSR | 公开 | 服务器列表（卡片网格） |
| `/servers/:id` | `server.detail` | C-D | SSR | 公开 | 服务器详情 + 在线统计图 |
| `/servers/:id/players/:name` | `player.stats` | C-D | SSR | 公开 | 单玩家统计详情 |
| `/servers/:id/leaderboard` | `server.leaderboard` | C-D | SSR | 公开 | 服务器排行榜 |
| `/pk?game=&a=&b=` | `pk` | C-D | SSR | 公开 | 玩家 PK 对比页（雷达图） |
| `/users/:id` | `user.profile` | C-D | SSR | 公开 | 用户主页 |
| `/me` | `me` | A/B | CSR | 登录 | 我的（移动端 Tab） |
| `/me/checkin` | `me.checkin` | A/B | CSR | 登录 | 签到日历 |
| `/me/bindings` | `me.bindings` | A/B | CSR | 登录 | 已绑定游戏号管理 |
| `/me/settings` | `me.settings` | A/B | CSR | 登录 | 个人设置（头像、签名、主题偏好、语言） |
| `/donations` | `donations` | A/B | SSR | 公开 | 捐赠榜 |
| `/search?q=&type=` | `search` | A/B | CSR | 公开 | 搜索结果页 |
| `/admin` | `admin.dashboard` | ADMIN | CSR | 管理员 | 后台首页（数据概览） |
| `/admin/users` | `admin.users` | ADMIN | CSR | 管理员 | 用户管理 |
| `/admin/servers` | `admin.servers` | ADMIN | CSR | 管理员 | 服务器管理（含 token 重置） |
| `/admin/donations` | `admin.donations` | ADMIN | CSR | 管理员 | 捐赠录入 |
| `/admin/logs` | `admin.logs` | ADMIN | CSR | 管理员 | 操作日志查询 |
| `/admin/audit` | `admin.audit` | ADMIN | CSR | 管理员 | Bedrock 行为日志查询 |
| `/admin/config` | `admin.config` | ADMIN | CSR | 管理员 | 运行时配置编辑 |
| `/dev/components` | `dev.components` | AUTH | CSR | 公开 | 组件展示页（仅开发用） |
| `/:pathMatch(.*)*` | `not-found` | AUTH | CSR | 公开 | 404 页（带萌系插画） |

#### 路由守卫规则（Nuxt middleware）

- **`auth` middleware**：进入前检查内存 access token；无则跳 `/login?redirect=<current>`。SSR 页面在客户端 hydration 后才执行（SSR 期间按匿名）
- **`admin` middleware**：检查 `useUserStore().role === 'admin'`，否则 403 页
- **布局切换**：用 `definePageMeta({ layout: 'content' | 'detail' | 'auth' | 'admin' })`
- **detail 布局**：在 `<768px` 时隐藏底部 Tab Bar 实现沉浸；在 `>=768px` 时只隐藏左主栏，保留顶栏

### 5.12 Nuxt 渲染策略

**核心原则**：公开内容页 SSR，鉴权/管理页 CSR。

| 渲染模式 | 适用 | 理由 |
|---|---|---|
| **SSR** | 首页、论坛、帖子详情、服务器列表/详情、玩家统计、PK、用户主页、捐赠榜 | 对搜索引擎可见 + 未登录访客首屏即看到内容（不注册即可浏览是核心要求） |
| **CSR** | 登录、注册、`/me/*`、`/admin/*`、搜索结果、`/dev/components`、404 | 不需要 SEO，省 Node 资源，规避 SSR 期间 auth 复杂度 |

**SSR 按匿名用户渲染**（关键决策）：

- SSR 期间**不读 cookie、不调 `/api/auth/refresh`、不带 Authorization 头**
- 服务端只调取公开数据（帖子、服务器、用户信息等公开接口）渲染 HTML
- 登录态相关 UI（顶栏头像、"我已签到"标记、个性化推荐）在浏览器 hydration 后由客户端补拉
- 好处：架构简单（Node 容器不需要参与认证），TTFB 快，未登录访客是首屏目标用户

**Docker 内网通信**：

- `web` 容器调 `core` 通过 Docker 内网 `http://core:8080`，不走公网
- 通过环境变量 `NUXT_PUBLIC_API_BASE`（浏览器侧）和 `API_BASE_INTERNAL`（SSR 侧）分别配置
- `useApi` composable 用 `import.meta.server` 区分两种环境

**SSR 错误处理**：

- core 返回非 OK envelope 时，公开接口当作"无数据"渲染（例如帖子被删返回 404 → Nuxt `createError({ statusCode: 404 })`）
- core 不可达时 SSR 退化为骨架屏 + 客户端重试（不让整个页面 5xx）

**Nuxt 4 → 5 升级**：Nuxt 5 在开发中，官方承诺平滑升级。本项目**不做主动升级规划**，上线后视稳定性评估。

### 5.13 前端实现规范

#### 5.13.1 Auth 流程

- **access token**：存 Pinia store（**关闭持久化**），刷新即丢
- **refresh token**：HttpOnly Cookie，浏览器自动管理
- **静默刷新**：`useApi` 拦截 401 → 调 `/api/auth/refresh` → 拿到新 access → 重试原请求
- **并发请求队列**：刷新进行时其他请求挂起，刷新成功后统一释放
- **SSR 不参与刷新**：见 5.12 节

#### 5.13.2 API client（`useApi`）

- 统一调用入口：`const data = await useApi('/api/posts')`
- 自动解包 envelope：`code === "OK"` 时返回 `data`，否则抛 `ApiError { code }`
- 错误码通过 `useI18n().t('errors.' + code)` 映射为本地化文案
- 业务层用 `try/catch` 处理，UI 层统一 Toast

#### 5.13.3 错误码与文案映射

- 翻译文件：`i18n/locales/<lang>/errors.json`
- 键名 = 错误码（如 `USER_NOT_FOUND`、`AUTH_TOKEN_EXPIRED`）
- 兜底：未注册错误码显示通用文案 `errors.UNKNOWN`
- 后端**永不返回 message 字段**（计划书 8.2 节铁律），所有提示文案在前端

#### 5.13.4 轮询封装（`usePolling`）

- 服务器在线状态、绑定状态等场景**统一用前端轮询**（计划书附录决策"实时推送：前端轮询，不上 WebSocket"）
- **默认间隔 25 秒**（与服务器接入协议 `/api/srv/poll` 的 25-30s 时长口径一致）
- 监听 `document.visibilitychange`：页面隐藏时暂停轮询，恢复时立即拉一次
- 组件 `onUnmounted` 自动清理

#### 5.13.5 Markdown 渲染

- 库：`markdown-it`
- 代码高亮：`highlight.js`（不上 Shiki，包体过大）
- XSS 净化：渲染后过 DOMPurify（前端净化）；后端入库前用 bluemonday 净化（双重防护，与 12.1 节一致）
- 不支持上传 HTML，markdown-it 关闭 `html: true`

#### 5.13.6 表单校验

- 库：vee-validate + zod
- Schema 与后端接口规范对齐：`username` 3-32、`password` 8-64 含字母和数字（计划书 8.5 节）
- 错误提示同样走 i18n

#### 5.13.7 ECharts 雷达图（PK 页）

数据 schema：

```ts
{
  indicator: [{ name: 'play_time', max: ... }, ...],
  series: [
    { name: 'PlayerA', value: [...] },
    { name: 'PlayerB', value: [...] }
  ]
}
```

后端 `/api/server/pk` 返回原始 stats，前端按指标白名单选取并归一化。

### 5.14 国际化

#### 5.14.1 支持语言

11 种：英语 (`en`) / 简体中文 (`zh-CN`) / 繁体中文 (`zh-TW`) / 日语 (`ja`) / 韩语 (`ko`) / 俄语 (`ru`) / 法语 (`fr`) / 西班牙语 (`es`) / 葡萄牙语 (`pt`) / 德语 (`de`) / 世界语 (`eo`)。

**默认语言**：简体中文。**翻译质量**由 AI 兜底，欢迎社区 PR 修正——小语种翻译可能存在生硬之处，文档中标注此情况。

#### 5.14.2 实现

- 库：`@nuxtjs/i18n`
- 文件结构：`i18n/locales/<lang>/<namespace>.json`（namespace 拆分为 `common` / `errors` / `pages` 等）
- 语言切换：用户设置页选择；默认按浏览器 `navigator.language` 推断；持久化到 `localStorage.locale-preference`
- URL 策略：**不**在 URL 路径前缀加语言（`/zh-CN/forums`），保持 URL 简洁；语言切换不刷新页面

#### 5.14.3 服务端不参与国际化

- 后端 envelope 不含 message（计划书 8.2 节铁律不动）
- **邮件模板统一中文**，不做 i18n（注册时用户未登录无法读偏好，不为此在 User 表加 `language` 字段）

## 六、数据模型

GORM 标签规范：用 `type:` 而非 `size:` 明确数据类型；复合唯一约束用 `uniqueIndex:<同名>`（单列也保持一致用 `uniqueIndex`，不用 `unique` 标签）。

```go
type Base struct {
    ID        uint           `gorm:"primaryKey"`
    CreatedAt time.Time
    UpdatedAt time.Time
    DeletedAt gorm.DeletedAt `gorm:"index"`
}

type User struct {
    Base
    Username      string `gorm:"type:varchar(32);uniqueIndex;not null"`
    Email         string `gorm:"type:varchar(128);uniqueIndex;not null"`
    EmailVerified bool   `gorm:"default:false"`                          // 注册时邮箱验证码核验通过即 true
    Password      string `gorm:"type:varchar(128);not null"`             // bcrypt 哈希
    Avatar        string `gorm:"type:varchar(255)"`
    Bio           string `gorm:"type:varchar(500)"`
    Role          string `gorm:"type:varchar(16);default:user;index"`    // user/mod/admin
    Status        string `gorm:"type:varchar(16);default:active;index"`  // active/banned
    LastLoginAt   *time.Time
    LastLoginIP   string `gorm:"type:varchar(64)"`
}

type Donation struct {
    Base
    UserID    *uint   `gorm:"index"`                          // 指针：注销保留 + 匿名捐赠
    Amount    float64 `gorm:"type:decimal(10,2);not null"`
    Message   string  `gorm:"type:varchar(255)"`
    Public    bool    `gorm:"default:true"`                   // 是否公开显示
    DonatedAt time.Time                                       // 管理员录入实际捐赠时间
}
// Public 与 UserID 正交：
//   UserID=nil, Public=true    → 榜上显示"匿名玩家 X 元"
//   UserID=X,   Public=false   → 实名但隐藏
//   UserID=X,   Public=true    → 实名公开
//   UserID=nil, Public=false   → 完全隐藏

type CheckIn struct {
    Base
    UserID    uint   `gorm:"uniqueIndex:idx_user_date;not null"`
    CheckDate string `gorm:"type:varchar(10);uniqueIndex:idx_user_date;not null"` // YYYY-MM-DD
    Streak    int    `gorm:"default:1"`                                            // 连续签到天数
}
// Streak 计算：今天签到时检查昨天签了没，签了 Streak++，没签重置 1。
// 奖励不入库，签到时按 Streak 派发固定/里程碑奖励，由 service 计算后即时发放。

type Server struct {
    Base
    Name        string         `gorm:"type:varchar(64);not null"`
    Type        string         `gorm:"type:varchar(32);index;not null"`
    Host        string         `gorm:"type:varchar(128);not null"`
    Port        int            `gorm:"not null"`
    Description string         `gorm:"type:varchar(500)"`
    Icon        string         `gorm:"type:varchar(255)"`
    Meta        datatypes.JSON `gorm:"type:jsonb"`             // 仅展示用，不作筛选条件
    TokenHash   string         `gorm:"type:varchar(128);not null"`  // bcrypt 哈希；明文只在创建/重置时返回一次
    // 运行时状态（status / online / max_players）放 Redis，不入库
}

type OnlineStat struct {
    ID         uint64         `gorm:"primaryKey"`
    ServerID   uint           `gorm:"index:idx_server_time;not null"`
    Online     int            `gorm:"not null"`
    Players    datatypes.JSON `gorm:"type:jsonb"`              // ["Steve","Alex",...]，可空
    RecordedAt time.Time      `gorm:"index:idx_server_time;not null"`
}
// 采样策略：每 5-10 分钟一条；Players 字段对人数 > 50 的服务器只记数量不记名单（控制存储）

type Player struct {
    Base
    UserID      uint       `gorm:"uniqueIndex:idx_user_server;not null"`
    ServerID    uint       `gorm:"uniqueIndex:idx_user_server;not null"`
    GameName    string     `gorm:"uniqueIndex:idx_server_gamename;type:varchar(64);not null"`
    ExternalID  string     `gorm:"type:varchar(64)"`        // 游戏内部 id（如 MC 的 UUID），仅用于数据关联，不参与身份判定
    PlayTime    int64      `gorm:"default:0"`
    LastSeenAt  *time.Time
    Whitelisted bool       `gorm:"default:false"`
    BoundAt     *time.Time
}
// 身份判定完全使用 GameName。改名视为新身份，需要重新走绑定流程；
// 平台不负责追踪改名，玩家自己承担。

type Forum struct {
    Base
    Name        string `gorm:"type:varchar(64);not null"`
    Slug        string `gorm:"type:varchar(64);uniqueIndex;not null"`
    Description string `gorm:"type:varchar(255)"`
    Icon        string `gorm:"type:varchar(255)"`
    Sort        int    `gorm:"default:0;index"`
    PostCount   int    `gorm:"default:0"`
}

type Post struct {
    Base
    UserID       uint
    ForumID      uint
    Title        string     `gorm:"type:varchar(200);not null"`
    Content      string     `gorm:"type:text;not null"`
    Pinned       bool       `gorm:"default:false;index"`
    Locked       bool       `gorm:"default:false"`
    ViewCount    int
    CommentCount int
    LikeCount    int
    LastReplyAt  *time.Time `gorm:"index"`
}

type Comment struct {
    Base
    PostID    uint   `gorm:"index;not null"`
    UserID    uint   `gorm:"index;not null"`
    ParentID  *uint  `gorm:"index"`                          // 楼中楼
    Content   string `gorm:"type:text;not null"`
    LikeCount int
}

type Tag struct {
    Base
    Name  string `gorm:"type:varchar(32);uniqueIndex;not null"`
    Color string `gorm:"type:varchar(16);default:#3b82f6"`
}
// 标签热度用 SQL COUNT(post_tags) 查询，不冗余字段

type PostAttachment struct {
    Base
    PostID   uint
    Filename string `gorm:"type:varchar(255);not null"`
    URL      string `gorm:"type:varchar(500);not null"`
    MimeType string `gorm:"type:varchar(64)"`
    Size     int64
}

type Log struct {
    ID         uint64         `gorm:"primaryKey"`
    ActorID    *uint          `gorm:"index"`
    ActorName  string         `gorm:"type:varchar(64)"`
    Action     string         `gorm:"type:varchar(64);index;not null"`
    TargetType string         `gorm:"type:varchar(32)"`
    TargetID   string         `gorm:"type:varchar(64)"`
    IP         string         `gorm:"type:varchar(64)"`
    UserAgent  string         `gorm:"type:varchar(255)"`
    Detail     datatypes.JSON `gorm:"type:jsonb"`
    CreatedAt  time.Time      `gorm:"index"`
}

// Bedrock 行为日志（仅 Bedrock 用，MC Java 由专用插件如 CoreProtect 承担，平台不管）
// cron 每天清理 3 天前数据
type AuditLog struct {
    ID         uint64    `gorm:"primaryKey"`
    ServerID   uint      `gorm:"index:idx_server_player_time;not null"`
    PlayerName string    `gorm:"type:varchar(64);index:idx_server_player_time;not null"`
    Action     string    `gorm:"type:varchar(32);index;not null"`  // chest_open / pvp_kill / death / break_valuable
    TargetName string    `gorm:"type:varchar(64)"`
    PosX       int
    PosY       int
    PosZ       int
    Detail     datatypes.JSON `gorm:"type:jsonb"`
    Timestamp  time.Time `gorm:"index:idx_server_player_time;not null"`
}

// 中间表
type PostTag     struct { PostID  uint `gorm:"primaryKey"`; TagID    uint `gorm:"primaryKey"` }
type ForumTag    struct { ForumID uint `gorm:"primaryKey"`; TagID    uint `gorm:"primaryKey"` }
type ForumServer struct { ForumID uint `gorm:"primaryKey"`; ServerID uint `gorm:"primaryKey"` }
```

### Server.Meta JSON 示例

```json
// MC Java
{"mode":"survival","seed":"12345","whitelist":true,"motd":"§a欢迎"}
// 基岩版
{"world_name":"world","gamemode":"creative"}
// DST
{"game_mode":"survival","season":"autumn","mods":["Global Positions"]}
// Terraria
{"world":"Hardmode","difficulty":"expert"}
// Stardew
{"farm_type":"standard","cabins":4}
```

## 七、Redis Key 规划

| Key | 用途 | TTL |
|---|---|---|
| `auth:refresh:<token>` | 刷新令牌 → `user_id`（双令牌机制，登出删除） | 7d |
| `auth:blacklist:access:<jti>` | access token 主动登出黑名单（按需，可选） | 15min |
| `auth:email:verify:<email>` | 邮箱注册验证码（6 位数字） | 5min |
| `auth:email:cooldown:<email>` | 验证码发送冷却（防刷） | 60s |
| `config:runtime` | 运行时可改的非启动配置（hash） | 无 TTL |
| `events:server:<sid>` | 服务器下行事件队列（BLPOP） | — |
| `server:status:<sid>` | 服务器运行时状态 online/offline/maintenance | 90s（心跳续期） |
| `server:online:<sid>` | 实时在线人数 | 90s（心跳续期） |
| `server:max:<sid>` | 最大人数 | 90s（心跳续期） |
| `server:online:all` | 全服总览缓存 | 1min |
| `checkin:<uid>:<date>` | 签到去重锁 | 1d |
| `ratelimit:<key>` | 限流计数 | 60s |
| `forum:hot` | 热帖 ZSET | 1h |
| `post:view:<pid>` | 浏览数缓冲（异步刷库） | — |
| `hub:rank:<game>:<metric>` | 排行 ZSET（Java/Bedrock 插件定期重建） | — |
| `hub:stats:<sid>:<gamename>` | 单玩家 stats 查询缓存（Java/Bedrock 共用，按需查后写入） | 5min |
| `bind:code:<CODE>` | 绑定验证码 → `{user_id, server_id}` | 5min |
| `bind:user:<uid>:<sid>` | 反向索引，防重复申请 | 5min |

服务器心跳（`POST /api/srv/heartbeat`）来一次就刷新 `server:status:*`、`server:online:*`、`server:max:*` 的 TTL。超时未续期视为离线。

## 八、REST API（用户侧）

### 8.1 鉴权机制（双令牌）

采用标准双令牌方案：

| 令牌 | 用途 | 形式 | 存储位置（前端） | TTL | 算法 |
|---|---|---|---|---|---|
| **Access Token** | 每次 API 请求携带 | JWT (HS256)，载荷只含 `{user_id, jti, exp}` | **内存**（页面刷新即丢，需静默刷新） | 15min | HS256 |
| **Refresh Token** | 静默换发新 access | 32 字节随机字符串（不是 JWT） | **HttpOnly + Secure + SameSite=Strict Cookie** | 7d | — |

**关键约定**：
- access 走 `Authorization: Bearer <token>` 头
- refresh 仅在 `POST /api/auth/refresh` 上由浏览器自动带 cookie；后端取 cookie 校验
- **refresh 一次性使用**：每次刷新颁发新 refresh + 删除旧 refresh（Redis `auth:refresh:<token>`），防泄漏后被反复利用
- access JWT 载荷**不含角色**，每次请求由中间件根据 `user_id` 从 DB/缓存查 Role，避免角色变更生效延迟
- 登出：删除 Redis 中的 refresh + 清除 cookie + 前端丢弃内存中的 access

### 8.2 响应统一格式

所有 `/api/*` 接口（含 `/api/srv/*` reply）使用统一 envelope：

```json
// 成功
{ "code": "OK", "data": { ... } | null }

// 失败
{ "code": "USER_NOT_FOUND", "data": null }
```

- **`code` 是字符串**，不是数字；成功永远是 `"OK"`，失败是业务错误码
- **不返回 `message`**——后端不返回任何可读字符串，提示文案完全由前端按 `code` 映射，便于国际化
- HTTP 状态码语义对应：200 成功、400 请求格式错、401 未登录、403 无权限、404 资源不存在、429 限流、500 服务器错

### 8.3 错误码命名规则

格式：`{DOMAIN}_{REASON}`，全大写下划线连接。`DOMAIN` 取自接口所属业务域。每个接口的错误码字典自行约定（不维护全局字典），下表中接口规格里直接列出该接口可能返回的错误码。

示例：`USER_NOT_FOUND` / `AUTH_TOKEN_EXPIRED` / `EMAIL_CODE_INVALID` / `BIND_CODE_INVALID` / `POST_LOCKED` / `RATE_LIMITED`。

### 8.4 限流通用规则

- 失败计数器走 Redis key `ratelimit:<key>`，60s 滑窗
- 触发返回 `429 + {"code":"RATE_LIMITED"}`
- 具体限流策略见每个接口

### 8.5 接口详表

> 表格列：方法、路径、权限、请求体（Req）、成功响应 data（Resp）、可能错误码（Err）。
> Req/Resp 用 TypeScript 风格速记。`-` 表示无 body 或无 data。

#### 认证（Auth）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/email/send_code` | 公开 | 发送邮箱注册验证码 |
| POST | `/api/auth/register` | 公开 | 邮箱验证码注册 |
| POST | `/api/auth/login` | 公开 | 登录，颁发双令牌 |
| POST | `/api/auth/refresh` | cookie | 静默刷新 access，轮换 refresh |
| POST | `/api/auth/logout` | 登录 | 登出，删除 refresh、清 cookie |

```
POST /api/auth/email/send_code
  Req:  { email: string }
  Resp: -
  Err:  EMAIL_INVALID_FORMAT / EMAIL_ALREADY_REGISTERED / EMAIL_RATE_LIMITED
  限流: 同 email 60s 一次

POST /api/auth/register
  Req:  { email: string, code: string, username: string, password: string }
  Resp: { access_token: string }        // 同时 Set-Cookie: refresh_token=...
  Err:  EMAIL_CODE_INVALID / EMAIL_CODE_EXPIRED / EMAIL_ALREADY_REGISTERED
        USERNAME_TAKEN / USERNAME_INVALID / PASSWORD_TOO_WEAK
  规则: username 3-32 / password 8-64 含字母和数字

POST /api/auth/login
  Req:  { email: string, password: string }
  Resp: { access_token: string }        // 同时 Set-Cookie: refresh_token=...
  Err:  AUTH_INVALID_CREDENTIALS / USER_BANNED / RATE_LIMITED
  限流: 同 email 5 次/分钟

POST /api/auth/refresh
  Req:  -                                // refresh_token 从 cookie 自动获取
  Resp: { access_token: string }        // 同时 Set-Cookie: 轮换后的 refresh_token
  Err:  AUTH_REFRESH_INVALID / AUTH_REFRESH_EXPIRED / USER_BANNED

POST /api/auth/logout
  Req:  -
  Resp: -
  Err:  -
```

#### 用户（User）

```
GET /api/users/me
  权限: 登录
  Resp: { id, username, email, email_verified, avatar, bio, role, created_at }
  Err:  -

PATCH /api/users/me
  权限: 登录
  Req:  { avatar?: string, bio?: string }     // 后续按需扩字段
  Resp: { id, username, email, ... }
  Err:  BIO_TOO_LONG

GET /api/users/:id
  权限: 公开
  Resp: { id, username, avatar, bio, role, created_at }    // 不含 email
  Err:  USER_NOT_FOUND
```

#### 签到（Check-in）

```
POST /api/checkins
  权限: 登录
  Req:  -
  Resp: { streak: int, reward: { type: string, amount: int } }
  Err:  CHECKIN_ALREADY_TODAY

GET /api/checkins/calendar?month=YYYY-MM
  权限: 登录
  Resp: { month, days: [{ date: "YYYY-MM-DD", checked: bool }, ...], streak: int }
  Err:  -
```

#### 捐赠（Donation）

```
POST /api/donations
  权限: 管理员
  Req:  { user_id?: uint, amount: number, message?: string, public: bool, donated_at: int64 }
  Resp: { id }
  Err:  USER_NOT_FOUND / AMOUNT_INVALID

GET /api/donations/ranking?limit=20
  权限: 公开
  Resp: { items: [{ display_name: string, amount: number, public: bool, donated_at: int64 }, ...] }
  Err:  -
  备注: display_name 由后端按公开/匿名规则计算（详见 Donation 模型注释）
```

#### 服务器（Server）

```
GET /api/servers
  权限: 公开
  Resp: { items: [{ id, name, type, host, port, icon, status, online, max, meta }, ...] }
  Err:  -

GET /api/servers/:id
  权限: 公开
  Resp: { id, name, type, host, port, description, icon, meta, status, online, max }
  Err:  SERVER_NOT_FOUND

GET /api/servers/:id/stats?range=24h|7d|30d
  权限: 公开
  Resp: { points: [{ recorded_at: int64, online: int }, ...] }
  Err:  SERVER_NOT_FOUND

POST /api/servers/:id/bind/code
  权限: 登录
  Req:  -
  Resp: { code: string, expires_at: int64 }
  Err:  SERVER_NOT_FOUND / BIND_ALREADY_BOUND / RATE_LIMITED

GET /api/servers/:id/bind/status
  权限: 登录
  Resp: { bound: bool, player?: { name, bound_at: int64 } }
  Err:  SERVER_NOT_FOUND

DELETE /api/servers/:id/bind
  权限: 登录
  Resp: -
  Err:  BIND_NOT_FOUND

GET /api/servers/:id/players/:name/stats
  权限: 公开
  Resp: { name, stats: object, cached_at: int64 }
  Err:  SERVER_NOT_FOUND / PLAYER_NOT_FOUND / SERVER_OFFLINE
  备注: Java/Bedrock 均走 player.stats.fetch 按需查（3s 超时）；
        命中走 hub:stats:<sid>:<gamename> 5min 缓存；
        服务器离线一律返回 SERVER_OFFLINE（不读缓存，与 10.1 节"离线不兜底"一致）

GET /api/servers/:id/leaderboard?metric=play_time&limit=50
  权限: 公开
  Resp: { metric, items: [{ rank, name, score }, ...] }
  Err:  SERVER_NOT_FOUND / METRIC_INVALID

GET /api/server/pk?game=mc-java&a=Steve&b=Alex
  权限: 公开
  Resp: { a: { name, stats }, b: { name, stats } }
  Err:  PLAYER_NOT_FOUND
```

#### 论坛（Forum / Post / Comment / Tag）

```
GET /api/forums
  权限: 公开
  Resp: { items: [{ id, name, slug, description, icon,
                    post_count, today_new_count, active_users_7d }, ...] }
  备注: 论坛列表页只展示分区 + 统计信息，不展示帖子流；
        today_new_count = 该分区今日新增帖子数；
        active_users_7d = 该分区近 7 日有过发帖/评论行为的不重复用户数

GET /api/forums/:id/posts?page=1&size=20&sort=latest|hot
  权限: 公开
  Resp: { items: [{ id, title, author, comment_count, like_count, view_count,
                    pinned, last_reply_at, tags: [...] }, ...], total: int }
  Err:  FORUM_NOT_FOUND

POST /api/posts
  权限: 登录
  Req:  { forum_id: uint, title: string, content: string, tag_ids?: uint[] }
  Resp: { id }
  Err:  FORUM_NOT_FOUND / TITLE_INVALID / CONTENT_INVALID / RATE_LIMITED
  限流: 同 user 30s 一次

GET /api/posts/:id
  权限: 公开
  Resp: { id, title, content, author, forum, tags, comment_count, like_count,
          view_count, pinned, locked, created_at, last_reply_at,
          attachments: [{ id, filename, url, mime_type, size }, ...] }
  Err:  POST_NOT_FOUND

POST /api/posts/:id/comments
  权限: 登录
  Req:  { content: string, parent_id?: uint }
  Resp: { id }
  Err:  POST_NOT_FOUND / POST_LOCKED / CONTENT_INVALID / PARENT_NOT_FOUND

POST /api/posts/:id/attachments
  权限: 登录
  Req:  multipart/form-data, field "file"
  Resp: { id, filename, url, mime_type, size }
  Err:  POST_NOT_FOUND / FILE_TOO_LARGE / FILE_TYPE_FORBIDDEN
  规则: 单文件 ≤10MB，白名单 image/jpeg|png|gif|webp + application/zip + application/pdf
        存储 /uploads/<yyyy>/<mm>/<sha256>.<ext>，DB 存相对路径，Nginx 直接暴露 /uploads/

GET /api/tags
  权限: 公开
  Resp: { items: [{ id, name, color, post_count }, ...] }
```

#### 搜索（Search）

```
GET /api/search?q=<keyword>&type=post|user|server&limit=20
  权限: 公开
  Resp: {
    posts?:   [{ id, title, forum_slug, author, created_at }, ...],
    users?:   [{ id, username, avatar }, ...],
    servers?: [{ id, name, type, icon }, ...]
  }
  Err:  QUERY_TOO_SHORT (q 长度 < 2) / QUERY_TOO_LONG (q 长度 > 64)
  实现: PostgreSQL ILIKE + pg_trgm GIN 索引；
        覆盖范围：post.title / user.username / server.name；
        不覆盖：post.content（量大且对 SEO 价值低）、comment.content；
        type 缺省时返回三类合并结果（各取前 limit/3 条）
  限流: 同 IP 10 次/分钟
```

#### 管理（Admin）

```
GET /api/admin/users?page=1&size=20&q=&status=
  权限: 管理员
  Resp: { items: [...User], total }

PATCH /api/admin/users/:id
  权限: 管理员
  Req:  { role?: "user"|"mod"|"admin", status?: "active"|"banned" }
  Resp: { id, ... }
  Err:  USER_NOT_FOUND

GET /api/admin/logs?action=&actor_id=&from=&to=&page=1
  权限: 管理员
  Resp: { items: [...Log], total }

GET /api/admin/audit?server_id=&player=&action=&from=&to=&page=1
  权限: 管理员
  Resp: { items: [...AuditLog], total }
  备注: Bedrock 行为日志查询

POST /api/admin/servers
  权限: 管理员
  Req:  { name, type, host, port, description?, icon?, meta? }
  Resp: { id, token: string }      // token 明文只在此返回一次
  Err:  SERVER_NAME_TAKEN

PATCH /api/admin/servers/:id
  权限: 管理员
  Req:  { name?, host?, port?, description?, icon?, meta? }
  Resp: { id, ... }

POST /api/admin/servers/:id/token/reset
  权限: 管理员
  Resp: { token: string }          // 重新生成 token，明文返回一次
  Err:  SERVER_NOT_FOUND

DELETE /api/admin/servers/:id
  权限: 管理员
  Resp: -
  Err:  SERVER_NOT_FOUND
```

## 九、玩家身份绑定流程

带外验证模式（Out-of-Band Verification）。

```
1. 用户在网页登录后进入"绑定账号"页面，选择服务器
2. 点击"生成验证码"
   → core 生成 6 位码（字符集 ABCDEFGHJKLMNPQRSTUVWXYZ3456789，移除易混淆字符）
   → 存 Redis: bind:code:<CODE> = {user_id, server_id, ts}, TTL 5min
   → 存反向索引 bind:user:<uid>:<sid> = <CODE>（重复申请返回同一个码，不重新生成）
3. 网页显示"5 分钟内进入服务器输入 /bind <CODE>"
4. 玩家进入游戏输入 /bind <CODE>
5. 服务器插件通过 POST /api/srv/binding.request 上报：
   { code, player: { name, external_id? } }
   ← 关键：游戏名由服务器报告，不接受用户预填
6. core 处理：
   a. 取 Redis 中验证码记录；不存在或过期 → 拒绝
   b. 校验 server_id 匹配（验证码所属服务器必须等于发起请求的服务器）
   c. 检查 (server_id, game_name) 未被占用
   d. 写 Player 记录
   e. 删除验证码记录（一次性）
   f. 通过 serverlink.SendCommand 下发 player.notify，让游戏内告诉玩家结果
7. 网页轮询绑定状态，显示绑定成功
```

### 规则

- 一个用户在同一服务器**最多绑定 1 个游戏号**（`Player.idx_user_server`）
- 一个游戏号**绝对不允许**被多个用户绑定（`Player.idx_server_gamename`）
- 一个用户**允许跨服务器**绑定不同游戏号
- 服务器插件侧：玩家 `/bind` 连续失败 3 次禁用 10 分钟
- core 侧：每个 server_id 每分钟绑定请求做限流
- **解绑**：网页一键解绑，**直接删除 Player 记录**（软删除由 GORM `DeletedAt` 处理）
- **改名**：身份判定完全基于 GameName，改名 = 新身份，需重新走绑定流程。玩家自己承担改名成本

## 十、玩家数据采集与 PK

### 10.1 总原则

**两边对称设计**：单玩家数据由游戏服自管，平台只负责汇总（排行）和归档（行为日志）。

| 游戏 | 单玩家详情 | 排行汇总 | 行为日志 |
|---|---|---|---|
| MC Java | 服务器按需查（`/poll` 下发 `player.stats.fetch`） | 插件定期 POST 推 Redis ZSET | 专用插件（CoreProtect 等）兜底，平台不管 |
| MC Bedrock | 服务器按需查（同上），数据存 Dynamic Property | 插件定期 POST 推 Redis ZSET | 插件主动 POST 到平台 PG，保留 3 天 |

**单玩家查询统一缓存层**：core 在 `hub:stats:<sid>:<gamename>` 5min 缓存按需查结果，Java/Bedrock 共用，防频繁请求穿透到游戏服。PK 接口复用此缓存路径。

服务器离线时单玩家详情接口直接返回 `SERVER_OFFLINE`，不读缓存兜底——有人玩的服务器常年开，没人玩的服务器没人查。

### 10.2 MC Java：插件主动读 + 平台按需查

Spigot/Paper/Fabric 提供 `Statistic` API 直接读取玩家统计数据（无需访问文件系统），插件运行在 JVM 内即可。

**单玩家详情（按需查模式）**：

```
用户在网站点"我的统计"
  → core 先查 hub:stats:<sid>:<gamename> 缓存（5min TTL），命中直接返回
  → 未命中 → serverlink.SendCommand(sid, "player.stats.fetch", {name})，等 reply（超时 3s）
  → 插件 /poll 拉到 command → Statistic API 读数据 → /reply 返回
  → core 写入缓存，返回前端
```

**排行汇总（主动推模式）**：

```
插件每 5 分钟扫所有玩家（在线 + 离线，离线通过 OfflinePlayer API）
  → POST /api/srv/leaderboard.update {metric: "play_time", entries: [{name, score}, ...]}
  → core 全量重建 hub:rank:<game>:<metric> ZSET
```

全量重建好处：天然处理"删除玩家"，状态简单。

**行为日志**：平台不介入。服务器自行安装 CoreProtect 等专用插件，管理员通过游戏内 `/co lookup` 命令查询。

### 10.3 MC Bedrock：Dynamic Property 自管 + 平台按需查

Bedrock 无 Statistic API 等价物，但 Script API 提供 **Dynamic Property** 持久化能力。经实测性能宽松（早期版本对全量 dump 敏感，1.21.x 后已优化），可作为玩家 stats 的本地主存储——**与 Java"玩家数据在游戏服自管"语义对齐**。

**stats 存储**：

- 用 **world-scope dynamic property**，每玩家一个键 `stats:<gamename>`，值为 JSON 字符串
- 单键容量 32KB（Script API 上限），实际单玩家 stats ≤1KB（仅保留高价值聚合指标，见下文 schema）
- Addon 启动时 Dynamic Property 由 Script API 自动加载，开发者无感（无需"启动拉回"）
- 离线玩家也可查——`world.getDynamicProperty("stats:<gamename>")` 不要求玩家在线

**stats schema（字段白名单，固化在 Addon 内）**：

约 30-50 个聚合指标，避免按 mob/物品/方块全量分桶导致 key 膨胀。覆盖：

- 生命周期：`first_join_at` / `last_seen_at` / `play_time`
- 移动：`distance_walked` / `distance_sprinted` / `distance_flown`
- 战斗：`mob_kills_total` / `pvp_kills` / `deaths` / `damage_dealt` / `damage_taken`
- 建造：`blocks_broken_total` / `blocks_placed_total` / `valuable_broken`（钻石/绿宝石/远古残骸合计）
- 其他：`chests_opened` / `items_crafted_total` / `enchants_applied`

PK 雷达图轴 = 此表中可比较的数值字段子集。schema 变更需 Addon 版本升级。

**flush 策略（写 Dynamic Property 的时机）**：

- 内存累加事件 → 每 60 秒批量 flush 一次到 dynamic property
- 玩家离开服务器时立即 flush 该玩家这一个键
- 监听 `system.beforeEvents.shutdown` 等关服事件（如可获取），收到立即 flush 全部
- 极端异常关服（kill -9 / 崩溃）接受丢失最近 ≤60 秒内的累加增量

**单玩家详情（按需查模式，与 Java 对称）**：

```
网站请求 → core 查 hub:stats:<sid>:<gamename> 缓存（5min TTL）
  未命中 → serverlink.SendCommand(sid, "player.stats.fetch", {name})
  Addon /poll 拉到 command → world.getDynamicProperty("stats:<name>") → /reply 返回
  core 写缓存 → 返回前端
```

**排行汇总（主动推模式，与 Java 对称）**：

```
Addon 每小时全量扫 dynamic property（按 stats:* 前缀枚举键）
  → 选取若干 metric → POST /api/srv/leaderboard.update
  → core 全量重建 hub:rank:bedrock:<metric> ZSET
```

**老玩家清理（防 world property 总容量膨胀）**：

- Addon 每周扫一次所有 `stats:*` 键，对比 `last_seen_at`
- 90 天未登录 → `setDynamicProperty(key, undefined)` 删除
- 被清理玩家如回归则从 0 重新累加（接受）

**行为日志（独立路径，仍走平台 PG）**：

插件本地内存维护 ~2 小时短期缓冲，**高价值事件**异步 POST 到 `/api/srv/audit.log` 落 PG，保留 3 天（cron 每日清理）。

高价值事件白名单：

- 容器交互：`chest_open` / `barrel_open` / `shulker_open` / `dispenser_open`
- 死亡（带位置 + 原因）
- PvP 击杀
- 重要方块的 break / place：钻石矿、信标、End Portal Frame、领地核心块（可配置）

不上传：普通方块破坏/放置、普通移动、普通战斗——这些走 stats 累加即可。

POST 失败不重试，本地短期缓冲作为兜底（管理员可游戏内查近 2 小时）。

**领域化 HTTP 接口（对内）**：

| 接口 | 用途 |
|---|---|
| `POST /api/srv/leaderboard.update` | 批量上报排行指标，写 Redis ZSET |
| `POST /api/srv/audit.log` | 单条/批量上报行为日志，写 PG AuditLog |

### 10.4 PK 接口

```
GET /api/server/pk?game=mc-java&a=<gamename_a>&b=<gamename_b>
```

后端内部对 a、b 各调一次单玩家查询逻辑（**复用 10.1 缓存路径**：先查 `hub:stats:<sid>:<gamename>` 5min 缓存，未命中再走 `player.stats.fetch`）。Java、Bedrock 走同一套代码，由 server 的 `type` 决定下发到哪个游戏端。**前端用 ECharts 雷达图渲染对比**。

服务器离线时该玩家侧直接返 `SERVER_OFFLINE`，整个 PK 接口失败。

### 10.5 Bedrock 状态数据生命周期

Bedrock Addon 处理的数据分两类，生命周期完全不同：

| 类型 | 例子 | 主存储 | Addon 启动时 | Addon 运行期 | Addon 关服 / 重启 |
|---|---|---|---|---|---|
| **累加型 stats** | 在线时长、击杀数、破坏方块数 | **Dynamic Property（world-scope，每玩家一键 `stats:<gamename>`）** | **Script API 自动加载，开发者无感**（无需外部拉回） | 内存累加 → 每 60s flush 到 dynamic property；玩家离开立即 flush 该玩家键；排行每小时全量重扫 dynamic property 推 Redis ZSET | 正常关服（如有 shutdown hook）立即 flush 全量；异常关服接受丢失 ≤60s 增量 |
| **状态型** | 领地、Addon 内置 ACL | **平台 PG**（Addon 是唯一写入方） | **必须拉回**，启动时 `GET /api/srv/claims?server_id=<sid>` 加载到内存（rbush 索引等） | 内存内 CRUD，同步 push 到 core 落 PG | push 失败有内存缓冲 + 关服 hook 兜底 push |

**关键区别**：

- 累加型主存储**在游戏端**（Dynamic Property），平台只有"按需查缓存 + 排行 ZSET"两份派生数据，重启重建即可
- 状态型主存储**在平台**（PG），Addon 内存是工作副本，需要启动拉回

#### Addon 唯一写入方原则

**状态型数据必须由 Addon 作为唯一写入方**（避免分布式状态竞态）：

- 平台管理员对状态型数据的修改（例如管理后台删除某个领地）**不直接改 PG**
- 改为下发 command 到 Addon：`POST /api/srv/* command: claim.delete`
- Addon 收到 command → 改内存 → push 平台 → reply 成功
- 这样平台 PG 始终是 Addon 内存的"快照"，不会反向覆盖 Addon 的新状态

#### 关服 hook

Bedrock Script API 的 `system.beforeEvents.shutdown`（如版本支持）在 graceful shutdown 时可触发。兜底策略：

- 累加型：60s 定期 flush + 玩家离开立即 flush + 关服 hook（如可获取）全量 flush
- 状态型：每次状态变更立即触发 push（不批量），保证落盘；监听玩家离开事件，最后一个玩家离开后立即 push 一次全量；配合关服 hook 兜底
- 接受"极端崩溃（kill -9 / 容器强停）时丢失最近 ≤60s 内的累加增量"

#### 上行/下行接口补充

```
GET  /api/srv/claims?token=xxx              拉取本服全部领地快照（启动时调用）
POST /api/srv/claim.upsert?token=xxx        领地创建/更新
POST /api/srv/claim.delete?token=xxx        领地删除
```

下行新增 command：

| command | data schema | 需要 reply | 说明 |
|---|---|---|---|
| `claim.delete` | `{claim_id: string}` | 是 `{deleted: bool}` | 管理员后台触发，Addon 改内存并 push |
| `claim.modify` | `{claim_id: string, ...}` | 是 `{modified: bool}` | 同上，修改成员/权限 |

具体领地数据 schema 待 15.4 节细则确定时定稿。


## 十一、Python 旁路脚本

宿主机 cron 调度（备份脚本通常运行在游戏服务器所在的机器上）：

```cron
0 4 * * * gameops python3 /scripts/backup.py
0 3 * * 0 gameops python3 /scripts/cleanup_logs.py
```

每个脚本用 `fcntl.flock` 文件锁防止叠加运行。

### 备份策略（按游戏区分）

- **MC Java（热备份，不停服）**：
  RCON `save-off` → `save-all flush` → tar 打包 → `save-on`
- **DST / Terraria / Stardew（停服备份）**：
  `docker stop` → tar 打包 → `docker start`
- **保留 7 天滚动**

## 十二、安全与运维

### 12.1 基础安全

- **密码**：bcrypt cost=12；强度规则 8-64 字符，至少含字母和数字（最宽松底线）
- **鉴权**：双令牌机制（见 8.1）。access 15min JWT (HS256)，refresh 7d 随机串存 Redis `auth:refresh:<token>`
- **限流**：登录 5 次/分钟、发帖 1 次/30 秒、签到 1 次/天、邮件验证码 60s/email、绑定每服务器每分钟 10 次
- **XSS**：前端 DOMPurify + 后端 bluemonday 双重净化
- **CSRF**：access 走 `Authorization` 头不受影响；refresh cookie 用 `SameSite=Strict` 防跨站
- **SQL 注入**：GORM 参数化，禁 Raw SQL
- **附件**：单文件 ≤10MB；MIME 白名单 `image/jpeg|png|gif|webp` + `application/zip` + `application/pdf`；存储路径 `/uploads/<yyyy>/<mm>/<sha256>.<ext>`；DB 存相对路径；Nginx 直接暴露 `/uploads/`，禁止目录列出
- **数据库扩展**：PostgreSQL 启用 `pg_trgm` 扩展，为 `post.title` / `user.username` / `server.name` 建 GIN 三元组索引，支持搜索接口的 ILIKE 查询
- **日志**：API 请求日志写文件 + 关键操作写 `logs` 表
- **监控**：`docker stats` + `journalctl`（不上 Prometheus）

### 12.2 配置管理

**单环境部署**：不分 dev/prod，统一一个"开发及生产"环境（MVP 阶段不完全开放）。

**配置分两层**：

| 层 | 来源 | 修改方式 | 内容 |
|---|---|---|---|
| 启动必要配置 | Docker 环境变量 | 改 compose 文件后重启 | 数据库地址、Redis 地址、JWT 密钥、SMTP 凭据 |
| 运行时配置 | Redis hash `config:runtime` | 管理后台编辑即时生效 | 站点名、Logo、注册开关、捐赠目标、公告等 |

#### 环境变量清单（前缀 `PLATFORM_`）

```
# 服务
PLATFORM_LISTEN_ADDR=:8080
PLATFORM_BASE_URL=https://example.com

# 数据库
PLATFORM_DB_DSN=postgres://user:pass@pg:5432/platform?sslmode=disable
PLATFORM_REDIS_ADDR=redis:6379
PLATFORM_REDIS_PASSWORD=

# 鉴权（双令牌）
PLATFORM_JWT_ACCESS_SECRET=<32 字节随机串>     # 必填，HS256 签名密钥
PLATFORM_JWT_ACCESS_TTL=15m
PLATFORM_REFRESH_TTL=168h                       # 7 天
PLATFORM_COOKIE_DOMAIN=example.com
PLATFORM_COOKIE_SECURE=true

# 邮件 SMTP
PLATFORM_SMTP_HOST=smtp.example.com
PLATFORM_SMTP_PORT=465
PLATFORM_SMTP_USER=no-reply@example.com
PLATFORM_SMTP_PASSWORD=<smtp 密码或 App Password>
PLATFORM_SMTP_FROM=no-reply@example.com
PLATFORM_SMTP_FROM_NAME=游戏服务平台

# 附件
PLATFORM_UPLOAD_DIR=/data/uploads               # 容器内挂载点
PLATFORM_UPLOAD_MAX_SIZE=10485760               # 10 MiB

# 服务器接入协议
PLATFORM_POLL_TIMEOUT=28s                       # /api/srv/poll 长轮询挂起时长
```

#### 运行时配置（`config:runtime` Redis hash）

字段示例：`site_name` / `site_logo_url` / `registration_open` / `donation_goal_amount` / `announcement` / `forum_post_min_interval` ...

启动时 core 加载 `config:runtime` 到内存（带变更订阅），管理后台 PATCH 后通过 Redis Pub/Sub 通知所有 core 实例热更。

### 12.3 邮件模板

注册验证码邮件用简单 HTML 模板：标题"游戏服务平台 - 邮箱验证"，正文显示 6 位数字验证码 + "5 分钟内有效"。模板放 `internal/email/templates/`，Go embed 编译进二进制。

**邮件统一中文，不做国际化**——注册时用户未登录无法读语言偏好；不为此在 User 表加 `language` 字段。前端 UI 国际化（11 种语言）见 5.14 节，与邮件解耦。

## 十三、功能优先级（MVP → 长期）

### P0（必上线）

用户系统、登录、服务器列表、实时在线、ECharts 在线统计图、论坛（帖子+评论+楼中楼+标签）、签到、捐赠（管理员录入）、玩家绑定、MC Java 玩家数据 PK、管理后台、日志

### P1（一个月内）

通知中心、举报系统、白名单申请、捐赠目标进度、服务器订阅、签到补签卡

### P2（半年内）

成就系统、玩家年度报告、活动中心、各游戏特色页（地图、boss 进度等）、移动端 PWA、Discord/Telegram 机器人通知

### P3（长期）

跨游戏荣誉墙、AI 攻略助手 (RAG)、API 开放平台、公会战、赛季制

## 十四、推进顺序（6 周）

| 周 | 内容 |
|---|---|
| 1 | core 项目骨架 + docker-compose（nginx/web/core/postgres/redis 启用 AOF）+ user 模块 + Nuxt 4 前端骨架（layout + tokens + 5 个核心组件） |
| 2 | forum + checkin + donation + 搜索（pg_trgm）+ 11 语言 i18n 接入 + 管理后台 |
| 3 | server + serverlink 模块（`/api/srv/poll`、`/api/srv/reply`、`/api/srv/*` 上行接口）+ MC Java 插件骨架（Kotlin + Paper） |
| 4 | MC Java 插件 stats 推送（Statistic API） + 按需查（`player.stats.fetch`） + PK + ECharts 雷达图 + `/docs` 命令 + Python 备份脚本 |
| 5 | Bedrock Addon 接入（含 stats Dynamic Property 自管 + 状态型启动拉回 + AuditLog + 领地插件框架）+ 玩家绑定流程 |
| 6 | 饥荒 Lua mod 接入 + 压测 + 安全审计 + 上线 |

Terraria / Stardew 排到 Week 7+，等社区有真实需求再做。

---

## 十五、游戏侧接入插件 / 模组

每种游戏一节，定方向不写代码。**两边设计对称**：Java 借力 Statistic API（玩家数据由服务端文件系统管）+ CoreProtect 兜底行为日志；Bedrock 借力 Dynamic Property（玩家数据由 world 自管）+ 平台 PG 兜底行为日志。两边都"玩家数据在游戏服自管、汇总上报平台、平台只缓存按需查结果"。

### 15.1 通用必做功能（A 组）

所有游戏的插件都必须实现：

1. 长轮询循环（`/api/srv/poll`）+ 事件分发到本地 handler
2. `/api/srv/reply` 回写处理结果
3. 玩家加入/离开事件上报（`/api/srv/player.joined|left`）
4. 心跳 + 在线数（每 30s `/api/srv/heartbeat`）
5. `/bind <CODE>` 指令处理 + `player.notify` 反馈
6. 核心下行 command：`player.kick` / `player.notify` / `player.whitelist.add|remove` / `server.broadcast` / `server.shutdown_notice`

### 15.2 MC Java（Paper 首选）

**技术栈**：Kotlin + Paper API + Gradle Kotlin DSL + `paper-plugin.yml` + Java 21 target

**亮点功能（B 组）**：

- **`/web` 命令**：游戏内弹出网站可点击链接
- **登录欢迎横幅**：玩家加入时 Bossbar / 聊天框显示"上次离开 X 天前 / 今日捐赠进度 X%"
- **stats 推送 + 按需查**：详见第 10.2 节
- **每次进服提示 `/docs`**：游戏内文档命令（见下文）
- **`/docs` 命令**：按文件系统目录组织文档，从 VitePress 同源 Markdown 加载，转换为 Minecraft 富文本（颜色码 + 点击事件）

**不做**：聊天桥（P1）、死亡/成就播报（P1）、首次进服引导（每次进服提示 `/docs` 已经覆盖）、行为日志（CoreProtect 等专用插件兜底）

**跨版本策略**：
- 只用 Bukkit/Paper 稳定 API 子集，不碰 NMS / CraftBukkit 内部类
- Adventure API 处理聊天、Bossbar、Title
- Mojang Mappings（1.20.5+）用 `paperweight-userdev` 处理
- 不兼容 1.20 以下版本，目标 1.20.5 ~ 1.21.x 单份代码运行

**Fabric 不在 MVP**：Fabric 用户基础小，写两套 1.5 倍工作量，排 P2。

### 15.3 MC Bedrock

**技术栈**：TypeScript + `@minecraft/server` + `@minecraft/server-net`（beta，固定 module version）+ `@minecraft/server-admin`

**亮点功能**：

- **完整数据采集**：stats（在线时长、移动、战斗、建造、生命周期）存 Dynamic Property 本地自管 + 排行汇总上报 + 行为日志上报（见第 10.3 节）
- **领地系统**（自写，详见 15.4）
- **平台 → 游戏的消息通知**（`world.sendMessage` / `player.sendMessage`）

**插件结构（observation 层共享）**：

```
bedrock-addon/
├── scripts/
│   ├── main.ts
│   ├── transport/{poll.ts, http.ts}    // 长轮询 + HTTP 封装
│   ├── observation/events.ts            // 事件单一订阅源，分发到 stats / audit
│   ├── storage/
│   │   ├── stats.ts                     // Dynamic Property 读写 + 内存累加 + 60s flush
│   │   ├── audit.ts                     // 行为日志短期缓冲 + 高价值事件上报
│   │   └── cleanup.ts                   // 每周扫 stats:* 清理 90 天未登录
│   ├── claim/                           // 领地（见 15.4）
│   ├── commands/{bind.ts}
│   └── handlers/{kick.ts, broadcast.ts, notify.ts, stats_fetch.ts}
└── manifest.json
```

**stats 存储约束**（与 10.3 节呼应，编码时铁律）：

- Dynamic Property key 命名：`stats:<gamename>`，world-scope
- 单键 ≤1KB（实测高价值聚合字段约 30-50 项，远低于 32KB 上限）
- 字段白名单固化在 Addon 内，schema 变更需版本升级
- 每周 cron 扫所有 `stats:*` 键，90 天未登录玩家 `setDynamicProperty(key, undefined)` 清理

**采用**：Dynamic Property 作为累加型 stats 的本地主存储（性能担忧经实测可控，1.21.x 后小量读写无瓶颈）

**不做**：死亡/advancement 播报（Script API 受限）

**跨版本策略**：接受"每个大版本可能小改一下"。`manifest.json` 固定 module version，不用 `"version": "beta"`；插件做薄，每次 MC 更新 review changelog。

**部署提醒**：Bedrock 默认禁止访问 localhost，2025/2 起需 `-allow_ioopenwrite_sandbox_escape` 启动参数解禁。同机部署用机器实际 IP / 公网域名规避。

### 15.4 Bedrock 领地插件（自写）

Bedrock Addons 生态缺成熟圈地插件，自实现一个。

**框架（细则后定）**：

- 用 **R 树**索引领地矩形/立方体，O(log n) 查询点是否落在领地内
- 引入开源 JS 库 [rbush](https://github.com/mourner/rbush)
- 监听方块破坏/放置/容器交互/PvP 等事件 → R 树查询 → 非领主则拦截
- 领地 CRUD 暴露为游戏内命令（`/land claim` / `/land trust` / `/land info` 等）
- 数据存储位置、权限模型（成员/管理员/访客）、Z 轴是否分层、跨服共享等细则**待后续任务确定**

### 15.5 DST

**技术栈**：Lua 5.1 + `TheSim:QueryServer` + modmain.lua

**功能集**：

- 通用必做功能（A 组全部）
- 天数 / 季节 / 当前 boss 状态上报 → POST 到平台展示

**关键限制**：
- `TheSim:QueryServer` 不支持自定义 Header → token 走 query string（与协议设计一致）
- 不能并发多个 `QueryServer` → **`/poll` 串行**，成功一次后立即发下一次
- 默认禁止 localhost（同 Bedrock，用实际 IP 规避）

**跨版本**：Klei 几乎不动 modding API，写完几年不用动。

### 15.6 Terraria（TShock）

**技术栈**：C# + TShock plugin API + .NET

**功能集**：
- 通用必做功能
- boss 进度上报（Eye of Cthulhu / Wall of Flesh / Moon Lord 等关键节点）

排到 Week 7+，等社区有真实诉求再做。

---

## 十六、网络架构与部署

### 16.1 机器与角色分配

| 机器 | 配置 | 网络 | 承载 |
|---|---|---|---|
| 本地服务器 | 12 核 / 64G | 公网 IP / 上行 50Mbps / 家宽动态 IP / 单线 / 无防御 | 游戏服务器（MC Java / Bedrock / DST / Terraria） |
| 腾讯云服务器 | 2 核 / 8G | 公网 IP / 上行 7Mbps / BGP / 薄防御 | 平台全栈（Nginx + web + core + PostgreSQL + Redis）+ 游戏四层中转 |

云服务器**一机两用**——既跑平台 HTTP 服务，又用 Nginx stream 模块为游戏流量做四层中转，零额外机器成本。

### 16.2 流量路径（平时）

**玩家连游戏**：

```
玩家 ──直连 50Mbps──→ 本地服务器 :游戏端口（首选）
玩家 ──云中转 7Mbps──→ 云 Nginx stream → 本地服务器 :游戏端口（兜底）
```

切换策略：**玩家自行判断**——不卡走直连，卡了试试云中转。两个地址都在网站服务器列表里明示，告诉玩家"感觉差就换另一个"。

走云中转的玩家共享 7Mbps 上限，故云 Nginx 对**跑图玩家 / 首次进服玩家**用 `proxy_upload_rate` / `proxy_download_rate` 限速，避免占满全部带宽影响其他玩家。视距不要开得过夸张即可控制单玩家带宽消耗。

**玩家访问平台 Web（Nuxt SSR）**：

```
浏览器 ──→ 云 Nginx :443 ──→ web (Nuxt SSR) ──→ core (Go API)
                                  │
                                  ▼
                              PG / Redis
```

SSR 期间 `web` 容器通过 Docker 内网 `http://core:8080` 直连 core，不出公网；浏览器后续 API 请求由 Nginx 反代直达 core。

**游戏服务器 ↔ 平台**：

```
本地（HTTP client）──→ 备用私有域名 ──→ 云服务器（core）
```

通过**备用私有域名**（不公开）连接，DDoS 期间本地切换公网 IP 后通信不中断（域名解析即可恢复）。

### 16.3 DDoS 应急预案

DDoS 不常有但有先例，故预案不进常态架构，按需启用：

**触发条件**：本地或云服务器遭遇持续异常流量。

**应急步骤**：

1. **本地服务器**：重启路由器换 IP（家宽动态 IP）→ 隐藏本地公网 IP
2. **云服务器**：根据攻击强度，先沿用腾讯云薄防御观察；不够再申请换 IP
3. **临时租 BGP 高防**（35 元/月，按月计费）→ 配置 DNAT 转发到本地新 IP
4. **游戏端口非标化**：高防服务限 10 端口、不开放 25565 / 80 等知名端口，游戏端口改为非标（如 19132、25566），网站发布新端口号
5. **平台域名**：优先沿用腾讯云薄防御不换端口；若必须走高防则接受非标 HTTPS 端口（如 `:8443`）的体验代价

**长轮询不受影响**：游戏服务器作 HTTP client 主动连云，方向是 本地 → 云，云不需要找到本地。本地只要能出网即可。

### 16.4 Nginx stream 配置要点

```nginx
stream {
    upstream mc_java { server <local-private>:25565; }

    server {
        listen 25565 proxy_protocol;     # 推荐启用 PROXY protocol，让 MC 服务端识别真实玩家 IP
        proxy_pass mc_java;
        proxy_upload_rate   200k;        # 限速（按需调整 / 按玩家分组）
        proxy_download_rate 500k;
        proxy_protocol on;
    }
}
```

**PROXY protocol**：推荐启用，MC 服务端开 `paper-global.yml` 的 `proxy-protocol: true` 配合。未启用时走云玩家 IP 会被统一显示为云服务器 IP，影响登录日志和封禁粒度，可接受但不推荐。

### 16.5 容量与扩展

- **7Mbps 是否够**：实测 MC 玩家稳态带宽 50-200 kbps / 人，跑图峰值短时 500 kbps，限速后云中转可同时承载 10+ 玩家
- **储备**：两位运维朋友的云服务器作为应急资源池，不进常态架构
- **真不够时**：扩展云端配置或追加云节点，由 Nginx stream upstream 横向扩展

### 16.6 不引入 k8s

理由：

1. **模块化单体 + 单进程**，没有多副本调度需求——k8s 最大价值点用不上
2. **节点数太少**（1 本地 + 1 云），k8s 反而是负担
3. **异地异构**（本地 vs 云、家宽 vs BGP），k8s 也协调不了
4. **团队都会 Docker 无人会 k8s**，学习成本与首要原则冲突
5. **Docker Compose + 一份 Nginx 配置完全够**

后续真有调度需求（节点 ≥ 5 / 多副本 / 滚动发布刚需），再评估。

---

## 附录：关键设计决策回顾

| 决策 | 取舍 |
|---|---|
| 数据库 | **PostgreSQL**（之前 MySQL，迁移到 PG 利用 jsonb 等特性） |
| 单体 vs 微服务 | **模块化单体**，避免分布式复杂度 |
| 模块间通信 | **依赖倒置 + 显式注入**，模块互不导入包，依赖接口由 app 层组装 |
| 接入协议 | **HTTP 长轮询统一接入**，弃用 WebSocket，消除 bridge 中间层 |
| 接入接口路径 | 全部在 `/api/srv/*` 下，与前端 `/api/*` 共享前缀但路由组分流 |
| 接入鉴权 | token 走 query 参数（饥荒 Lua API 不支持自定义 Header） |
| `/poll` 异步、一次一个 | 模仿 Go 通道，心智模型最简单 |
| 事件 id | 所有事件都带 id，是否 reply 由游戏端插件自决，平台是否读 reply 由 core 内部调用方决定 |
| 不做事件确认/重传 | 接受丢失，业务层容忍 |
| 不保证事件顺序 | 顺序需求由 core 在发送端控制 |
| 服务器运行时状态 | Status / Online / MaxPlayers 存 Redis 不入库，靠心跳续期 TTL |
| Server.Meta JSON | 仅展示用，不作筛选条件 |
| OnlineStat 玩家列表 | JSON 字段记录在线名单，大服务器（>50 人）只记数量 |
| 玩家身份判定 | 只用 GameName，取消 UUID；改名 = 新身份需重绑 |
| Donation.UserID | 指针类型，支持注销保留 + 匿名捐赠 |
| Donation.Public | 是否公开显示，与 UserID 是否为 nil 正交 |
| CheckIn | 仅记录 Streak，奖励不入库 |
| Player 绑定状态 | 不存 BindStatus / BindMethod，解绑即软删除 |
| Tag 热度 | 用 SQL COUNT 查询，不冗余 Usage 字段 |
| 玩家绑定 | 带外验证，游戏名由服务器报告 |
| 捐赠 | 管理员手动录入，不接支付 |
| 定时任务 | 宿主机 cron + Python，不引入 asynq |
| 实时推送 | 前端轮询，不上 WebSocket |
| 备份策略 | MC 热备份，其他游戏停服备份 |
| **MC Java 单玩家详情** | **按需查（`/poll` 下发 `player.stats.fetch`），插件用 Statistic API 读取，不读文件系统** |
| **MC Java 排行** | **插件每 5 分钟主动 POST 推 Redis ZSET（全量重建）** |
| **MC Java 行为日志** | **专用插件兜底（CoreProtect 等），平台不介入** |
| **MC Bedrock 数据存储** | **累加型 stats 存 Dynamic Property（world-scope，每玩家一键，60s flush，与 Java 语义对齐——游戏服自管）；不再做平台 Redis 主存储** |
| **Bedrock 行为日志** | **高价值事件 POST 到平台 PG AuditLog，保留 3 天；放弃环形缓冲 + 分块设计** |
| **Bedrock 平台存储接口** | **领域化接口：leaderboard_update（写 Redis ZSET）+ audit_log（写 PG）；stats 不再上报平台** |
| **Bedrock 领地** | **自写插件，R 树索引（rbush），细则后定** |
| **服务器离线兜底** | **不做——单玩家详情接口直接返 SERVER_OFFLINE，不读缓存兜底；常开服务器无此问题** |
| **单玩家 stats 缓存** | **`hub:stats:<sid>:<gamename>` 5min 缓存，Java/Bedrock 共用；PK 接口复用此路径** |
| **插件语言选型** | **Java = Kotlin（Paper 一等公民）/ Bedrock = TypeScript / DST = Lua / Terraria = C#** |
| **Fabric 支持** | **MVP 不做，排 P2** |
| **机器分配** | **平台跑云服务器（BGP 优）/ 游戏跑本地服务器（性能强带宽足），云一机两用（平台 + 四层中转）** |
| **玩家连接策略** | **直连优先，玩家自行判断切换云中转；不做自动切换** |
| **云中转限速** | **Nginx stream `proxy_*_rate`，对跑图/首次进服玩家限速，保 7Mbps 不被独占** |
| **PROXY protocol** | **推荐启用让 MC 识别真实 IP；未启用接受走云玩家 IP 统一** |
| **DDoS 应急** | **35 元/月 BGP 高防按需租用；本地换 IP 隐藏 + 游戏端口非标化；平台先靠腾讯云薄防御扛** |
| **平台 ↔ 游戏服 通信** | **备用私有域名，DDoS 换 IP 无需改架构** |
| **不引入 k8s** | **单体单进程 + 节点少 + 异地异构 + 团队不熟，Docker Compose 足够** |
| **鉴权** | **双令牌：access JWT 15min 走 Authorization 头存内存；refresh 7d 随机串走 HttpOnly Cookie；refresh 一次性轮换** |
| **JWT 载荷** | **只含 user_id + jti + exp，不含角色——角色每请求查 DB/缓存，避免变更生效延迟** |
| **注册** | **必须邮箱验证码（5min 有效，6 位数字，60s 发送冷却）** |
| **错误码** | **`{DOMAIN}_{REASON}` 全大写字符串；不返回 message；按接口自治字典，不维护全局表** |
| **响应包装** | **`{"code":"OK"\|"XXX_YYY", "data": ... \| null}`，统一前后端和 srv reply** |
| **服务器 token** | **入 DB 存 bcrypt 哈希，明文只创建/重置时返回一次；不放配置文件，加服务器无需重启** |
| **配置分层** | **启动必要项走环境变量；运行时项放 Redis `config:runtime` hash，后台编辑即时生效** |
| **附件** | **本地存 `/uploads/<yyyy>/<mm>/<sha256>.<ext>`；≤10MB；image/zip/pdf 白名单；Nginx 直出** |
| **环境** | **不分 dev/prod，单一"开发及生产"环境（MVP 阶段不完全开放）** |
| **接入路径命名** | **`/api/srv/<action>` 直接挂载，不再有 `/event/` 中间路径；action 用 `领域.动作` 风格（如 `player.joined`）** |
| **前端框架** | **Nuxt 4 SSR；公开内容页 SSR、鉴权/管理页 CSR；Reka-UI（unstyled）保留，不用 NuxtUI 避免设计语言冲突** |
| **SSR 渲染策略** | **SSR 按匿名用户渲染——不读 cookie 不调 auth；登录态 UI 客户端 hydration 后补；TTFB 优先，不注册可看是核心需求** |
| **前端选型** | **markdown-it + highlight.js + DOMPurify / vee-validate + zod / Pinia 不持久化 access / `usePolling` 默认 25s 间隔 + visibilitychange 暂停** |
| **顶栏搜索** | **PG `ILIKE` + `pg_trgm` GIN 索引；覆盖 post.title / user.username / server.name；不覆盖正文与评论；MVP 不上独立搜索引擎** |
| **论坛架构** | **列表页只展示分区 + 统计（今日新增/7日活跃/总帖数）；帖子流在 `/forums/:slug` 详情页** |
| **列表呈现** | **卡片网格优先于表格；Table 仅用于管理后台** |
| **导航栏** | **顶栏左侧 Logo/主页/论坛/服务器/捐赠；右侧搜索框/头像；通知图标 MVP 不放（P1 再加）** |
| **国际化** | **11 种语言（en/zh-CN/zh-TW/ja/ko/ru/fr/es/pt/de/eo）；前端错误码映射；URL 不带语言前缀；翻译质量 AI 兜底欢迎 PR** |
| **邮件 i18n** | **不做——统一中文；不在 User 表加 language 字段** |
| **Bedrock 数据生命周期** | **累加型 stats：Dynamic Property 自动加载无需拉回 / 60s flush + 玩家离开 flush / 90 天未登录清理；状态型：启动拉回 + Addon 唯一写入方 + 管理操作走 command 下发** |
| **Nuxt 5 升级** | **不主动规划，上线后视稳定性评估；官方承诺 4→5 平滑升级** |
