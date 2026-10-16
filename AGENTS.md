# AGENTS.md

面向长视频内容理解的 Video Agent。三块代码：`server/`（Java 21 + Spring Boot 3.5.9 API 与 Agent）、`client/`（Vue 3 + Vite 工作台）、`extension/`（MV3 Chrome 扩展）。README、注释、提交信息以中文为主，改动保持一致。

## 运行架构（容易踩错）

- `docker-compose.yml` **只编排中间件**（MySQL / Redis / MinIO / Qdrant / RocketMQ），全部绑定 `127.0.0.1`。后端与前端在宿主机上跑，不在容器里。
- 后端配置**全部来自环境变量**（见 `server/src/main/resources/application.properties`），`.env` 被 gitignore。启动前必须 `set -a; source .env; set +a`，否则必填项（`DB_PASSWORD` 等）直接启动失败。
- 前端 Vite 用 `envDir: '..'` + `loadEnv(mode, '..', '')` 从**仓库根**读 `.env`，也吃其中的 `MINIO_ENDPOINT` 等非 `VITE_` 变量。
- `client` 的 `vite` 被 `overrides` 成 `rolldown-vite@7.2.5`，不要替换回标准 vite。

## 常用命令

```bash
cp .env.example .env                 # 首次：填密钥（gitignore 内的 .env）
./scripts/dev-up.sh                  # 中间件：preflight + 修 RocketMQ 卷属主 + 显式建主题

set -a; source .env; set +a          # 后端 / 前端都必须先 source
cd server && ./mvnw spring-boot:run  # http://localhost:9090/health
cd client && npm ci && npm run dev   # http://localhost:5173（加 ?demo 用内置示例数据，不依赖后端）
```

测试（CI 同样是 backend / frontend / extension 三个独立 job）：

```bash
cd server    && ./mvnw test                                    # JUnit + Mockito，无需中间件
cd server    && ./mvnw -Dtest=AgentExecutionBudgetTest test    # 单个后端测试
cd client    && npm test                                       # node:test src/*.test.js（不是 vitest）
cd client    && node --test src/api.test.js                    # 单个前端测试
cd extension && node --test
```

## 改动时需要知道

- **表结构改动走 Flyway**：新增 `server/src/main/resources/db/migration/V<n>__*.sql`，当前最高 `V3`。`spring.sql.init.mode=never`，不要手写建表。
- **RocketMQ 主题不会自动创建**，消费者订阅不存在的主题会静默空转；`dev-up.sh` 已显式 `updateTopic`，新增主题要同步加进去。
- **Agent 终止预算**在 `.env`：`AGENT_MAX_ROUNDS=2`、`AGENT_MAX_DURATION_MS`、`AGENT_MAX_ESTIMATED_TOKENS`。小时级视频光构建 `VideoContext` 就可能数分钟，默认值容易触发 `agent_analysis_failed`。
- **MinIO 预签名地址**：客户端可见地址由 `MINIO_PUBLIC_BASE`（如 `/minio`）拼接，`vite.config.js` 有 `/minio` 代理并剥前缀，保证签名里的 path/host 仍匹配。
- **CORS**：隧道/外部 Origin 会被后端白名单拒绝，Vite 代理在转发前 `removeHeader('origin')`。新增 API 前缀时，要同时加进 `vite.config.js` 的代理列表。
- 默认 LLM `deepseek-ai/DeepSeek-V3.2`；示例里的 `DeepSeek-R1-Distill-Qwen-32B` 已被硅基流动禁用。模型或超时改了要重启后端。
- Maven 报 `maven-default-http-blocker` 时用 `./mvnw -s .mvn/central-settings.xml ...` 绕过本机镜像。
- 在线视频链接下载依赖 yt-dlp（`YTDLP_PATH` / cookie）。B 站等站点在**机房 IP** 上会因 `www.bilibili.com/video/` 网页 412 失败（cookie 无效，但 `api.bilibili.com` 正常）。

## 目录速览

- `server/src/main/java/com/example/server/`：`controller/`、`service/`（Agent 主体在 `AgentLoopService`/`AiService`，多模态上下文在 `VideoContextService`/`LongVideoContextService`）、`consumer/`（RocketMQ）、`mapper/`、`entity/`、`utils/`。
- `client/src/`：纯函数模块各带同名 `*.test.js`（`api`、`chunkUpload`、`taskEvents`、`videoTimestamps`、`useAnalysisWorkspace`）。
- `docs/`：设计与 review 记录（`review-2026-*.md` 记录已修问题及原因），不是规范来源。
- 若本机存在 `DEPLOY.local.md`（未入库），是该机器的部署/运维备注，排查环境问题先看它。
