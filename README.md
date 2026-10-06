# 后端

Spring Boot 2.3.2 / Java 8 目标，交付 WAR。业务入口 core，稳定接口 ports，数据及平台适配 adapter，确定性规则 rules。配置位于 src/main/resources/application*.yml。业务校验契约位于 src/main/resources/contracts/business-contracts.schema.json；本目录可独立构建，不需要外部 docs 目录。

```sh
# 全量测试需要先配置独立 MySQL 测试库的 TEST_DB_URL/TEST_DB_USERNAME/TEST_DB_PASSWORD。
mvn clean test
mvn -Pproduction clean package
mvn spring-boot:run
```

无需数据库的平台协议、文件上传、授权和规则单元测试：

```sh
mvn -Dtest=PlatformDecoderTest,WorkflowModelAdapterTest,BankOneAgentAdapterTest,OneAgentAdapterExchangeTest,OneAgentFilesTest,RuleServiceTest test
```

## 本地联调 Mock 平台

`tools/mock-yunxia-server.js` 是零依赖（Node 8+）的行内平台 Mock，模拟 `message` SSE 与 `files` 上传接口，无需行内网络即可联调：

```sh
node tools/mock-yunxia-server.js --port 18784
```

`application.yml` 默认已指向 `http://127.0.0.1:18784/api/v1/message`，直接 `mvn spring-boot:run` 即可。发送含 `尽调`/`敏感`/`限流`/`断流`/`超时`/`慢` 的消息可分别验证业务链、策略拦截、模型限流、连接断开、读取超时与长耗时链路。开启 `diligence.platform.debug-trace` 后，后端逐帧打印 `[SSE-TRACE]` 日志（秘密打码、单帧截断）便于排障。

`DiligenceIntegrationTest` 使用 `test` profile，只读取 `TEST_DB_*`；测试会建应用表并写入测试数据，须使用隔离测试库。它不回退到 `DB_*` 或 H2。

从旧版本切换到 MySQL 基线后，首次编译和打包必须执行 `clean`，避免旧 `target/classes` 中已移除的数据库适配类参与启动。

默认 local profile 连接 MySQL（须通过 DB_URL/DB_USERNAME/DB_PASSWORD 注入连接，不内置地址和凭证），显式 demo 源，并创建一次模拟演示会话。正式环境不启用 local：不自动建表、不注入开发身份、不回退模拟源。源业务表与应用文档表分开，SQL 与账号权限由数据库负责人核对。应用文档表当前用 JDBC 短事务。v0.5.0 基线使用 POI 4.1.2、PDFBox 2.0.26 和 Spring Boot 管理的 MySQL 驱动。

- [模块索引](../../docs/design/README.md#按修改任务查入口)：数据库、业务 API、SSE及业务服务修改位置。
- [API与Schema](../../docs/design/api.md)：新 `/api/v1/diligence`、内部运行 API及五工具。
- [运行配置](../运行配置.md)：端口、密钥、Python、源映射及平台协议。

自动测试覆盖契约、归属、版本、财报确认幂等、源基准冲突、DOCX及平台终态。真实 MySQL 目标环境、SSO、行内模型发布未因此视为通过。
