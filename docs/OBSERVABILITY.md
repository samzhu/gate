# 可觀測性設計文件

## 概述

LLM Gateway 使用 Spring Boot 4.0 + OpenTelemetry 實現完整的可觀測性三大支柱：

| 信號 | 用途 | 本地開發 | GCP 生產 |
|------|------|---------|---------|
| **Traces** | 請求追蹤、延遲分析 | Tempo | Cloud Trace |
| **Metrics** | 效能監控、告警 | Prometheus | Cloud Monitoring |
| **Logs** | 除錯、稽核 | Loki | Cloud Logging |

---

## 架構

### 本地開發環境

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Docker Compose (compose.yaml)                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   ┌─────────────┐         ┌──────────────────────────────────────┐  │
│   │   Gateway   │  OTLP   │         grafana/otel-lgtm            │  │
│   │ (localhost) │ ──────▶ │  :4318 (HTTP) / :4317 (gRPC)         │  │
│   │    :8080    │         │                                      │  │
│   └─────────────┘         │  ┌────────┐ ┌────────┐ ┌──────────┐  │  │
│                           │  │ Tempo  │ │ Prom   │ │   Loki   │  │  │
│                           │  │ Traces │ │ Metrics│ │   Logs   │  │  │
│                           │  └────────┘ └────────┘ └──────────┘  │  │
│                           │                                      │  │
│                           │  Grafana UI: http://localhost:3000   │  │
│                           └──────────────────────────────────────┘  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**特點**：
- Spring Boot Docker Compose 整合**自動偵測** `grafana/otel-lgtm` 容器
- 無需手動配置 OTLP 端點，自動配置 Traces、Metrics、Logs 導出

### GCP 生產環境 (Sidecar Collector)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         GCP Cloud Run Service                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────┐      OTLP       ┌────────────────────────┐    │
│  │     Gateway      │ ──────────────→ │   OTel Collector       │    │
│  │    Container     │  localhost:4318 │   (Sidecar Container)  │    │
│  └──────────────────┘                 └───────────┬────────────┘    │
│                                                   │                 │
└───────────────────────────────────────────────────│─────────────────┘
                                                    │
                     ┌──────────────────────────────┼──────────────────────────────┐
                     ▼                              ▼                              ▼
               Cloud Trace                  Cloud Monitoring                Cloud Logging
               (Traces)                     (Metrics)                       (Logs)
```

**特點**：
- 部署 [Google-Built OTel Collector](https://cloud.google.com/stackdriver/docs/instrumentation/opentelemetry-collector-cloud-run) 作為 Sidecar
- 應用程式發送 OTLP 到 `localhost:4318` (HTTP) 或 `localhost:4317` (gRPC)
- Collector 自動導出到 Cloud Trace / Cloud Monitoring / Cloud Logging
- 統一的 OpenTelemetry 架構，本地與生產環境一致

---

## 配置

### Spring Boot 4.0 OpenTelemetry 屬性命名

Spring Boot 4.0 重新組織了 OpenTelemetry 配置屬性：

| 信號 | Spring Boot 3.x | Spring Boot 4.0 |
|------|-----------------|-----------------|
| **Tracing 端點** | `management.otlp.tracing.endpoint` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| **Tracing 啟用** | `management.otlp.tracing.export.enabled` | `management.tracing.export.otlp.enabled` |
| **Logging 端點** | `management.otlp.logging.endpoint` | `management.opentelemetry.logging.export.otlp.endpoint` |
| **Logging 啟用** | `management.otlp.logging.export.enabled` | `management.logging.export.otlp.enabled` |
| **Metrics 端點** | `management.otlp.metrics.export.url` | `management.otlp.metrics.export.url` (未變) |

### 依賴配置 (build.gradle)

```groovy
dependencies {
    // OpenTelemetry Starter (Spring Boot 4.0 新增)
    implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'

    // Micrometer Tracing
    implementation 'org.springframework.boot:spring-boot-micrometer-tracing'

    // OTLP Metrics Registry
    runtimeOnly 'io.micrometer:micrometer-registry-otlp'

    // Docker Compose 整合 (開發環境自動偵測)
    developmentOnly 'org.springframework.boot:spring-boot-docker-compose'
}
```

> **注意**：不要同時使用 `micrometer-tracing-bridge-brave` 和 `spring-boot-starter-opentelemetry`，會造成衝突。

### 本地開發配置 (application.yaml)

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% 取樣
  # 端點由 Docker Compose 自動配置，無需手動設定
```

### GCP 配置 (application-gcp.yaml)

```yaml
# 發送到 Sidecar Collector (localhost)，由 Collector 導出到 GCP 服務
management:
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/logs
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
```

### Cloud Run Sidecar 部署

在 Cloud Run service.yaml 中配置 OTel Collector Sidecar：

```yaml
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: gate
  annotations:
    run.googleapis.com/launch-stage: BETA
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/container-dependencies: "{app:[collector]}"
    spec:
      containers:
        - name: app
          image: gate:latest
          env:
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://localhost:4318"
        - name: collector
          image: us-docker.pkg.dev/cloud-ops-agents-artifacts/cloud-run-otel-collector/otel-collector:latest
          env:
            - name: GOOGLE_CLOUD_PROJECT
              value: "your-project-id"
```

---

## 追蹤 ID 設計

### ID 類型與用途

| ID 類型 | 格式 | 來源 | 用途 |
|---------|------|------|------|
| `traceId` | 32 hex chars | OpenTelemetry / Cloud Run | 端到端請求追蹤 |
| `messageId` | `msg_xxx` | Anthropic API 回應 body | 訊息識別 |
| `anthropicRequestId` | `req_xxx` | Anthropic API 回應 header | 向 Anthropic 報告問題 |

### 追蹤流程

```
Claude Code CLI                    Gateway (Cloud Run)                Anthropic API
      │                                │                                   │
      │  traceparent: 00-{traceA}-... │                                   │
      │ ─────────────────────────────→│                                   │
      │                                │  繼承 traceA                      │
      │                                │  記錄到 Cloud Trace               │
      │                                │                                   │
      │                                │  POST /v1/messages                │
      │                                │ ─────────────────────────────────→│
      │                                │                                   │
      │                                │  request-id: req_xxx              │
      │                                │  body: {"id": "msg_xxx", ...}     │
      │                                │←─────────────────────────────────│
      │                                │                                   │
      │                         UsageEvent (CloudEvents):                  │
      │                         - id: traceA (CloudEvent ID)              │
      │                         - trace_id: traceA                        │
      │                         - message_id: msg_xxx                     │
      │                         - anthropic_request_id: req_xxx           │
```

### 排查指南

| 問題類型 | 使用欄位 | 查詢位置 |
|----------|----------|----------|
| Gateway 內部問題 | `trace_id` | Cloud Trace、應用日誌 |
| Anthropic API 問題 | `anthropic_request_id` | 聯繫 Anthropic 客服 |
| 用戶特定問題 | `trace_id` + CloudEvent `subject` | BigQuery 用量資料 |
| 端到端追蹤 | `trace_id` | Client OTel + Cloud Trace |

---

## 日誌格式

### 本地開發 (人類可讀)

```
2025-11-26 16:38:50.734 [http-nio-8080-exec-1] [b3639b325cad5960d69029c3fd8f61c6,027638326a72526b] INFO ...
                                               └── traceId                    └── spanId
```

### GCP 生產 (JSON 結構化)

```json
{
  "timestamp": "2025-11-26 16:38:50.734",
  "level": "INFO",
  "logger": "io.github.samzhu.gate.handler.StreamingProxyHandler",
  "message": "Stream completed: subject=user@example.com, ...",
  "trace_id": "b3639b325cad5960d69029c3fd8f61c6",
  "span_id": "027638326a72526b"
}
```

---

## Docker Compose 設定

### compose.yaml

```yaml
services:
  grafana-lgtm:
    image: grafana/otel-lgtm:latest
    ports:
      - '3000:3000'   # Grafana UI
      - '4317:4317'   # OTLP gRPC
      - '4318:4318'   # OTLP HTTP
```

### 自動偵測機制

Spring Boot Docker Compose 整合會：
1. 讀取 `compose.yaml`
2. 識別 `grafana/otel-lgtm` 映像
3. 自動建立 `OtlpTracingConnectionDetails`、`OtlpMetricsConnectionDetails`、`OtlpLoggingConnectionDetails` beans
4. 配置對應的 OTLP 導出器

---

## 環境變數

### 本地開發

無需設定，Docker Compose 自動配置。

### GCP 生產

| 變數 | 說明 | 預設值 |
|------|------|--------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Sidecar Collector 端點 | `http://localhost:4318` |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | 取樣率 | `0.1` (10% in prod) |

### 外部 OTLP Collector (可選)

若需導出到外部 Collector (如 Grafana Cloud)，覆蓋 `OTEL_EXPORTER_OTLP_ENDPOINT`：

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.grafana.net
```

---

## Grafana 查詢

### 本地開發 Grafana UI

**URL**: http://localhost:3000

#### 查詢 Traces (Tempo)

1. 進入 Explore → 選擇 Tempo
2. 使用 TraceQL 查詢：
   ```
   {resource.service.name="gate"}
   ```

#### 查詢 Logs (Loki)

1. 進入 Explore → 選擇 Loki
2. 使用 LogQL 查詢：
   ```
   {service_name="gate"} | json | trace_id != ""
   ```

#### 查詢 Metrics (Prometheus)

1. 進入 Explore → 選擇 Prometheus
2. 使用 PromQL 查詢：
   ```promql
   rate(http_server_requests_seconds_count{application="gate"}[5m])
   ```

---

## 參考資料

- [Spring Boot 4.0 Configuration Changelog](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Configuration-Changelog)
- [Spring Boot Tracing Documentation](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [OpenTelemetry with Spring Boot](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/)
- [Cloud Run Distributed Tracing](https://cloud.google.com/run/docs/trace)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Anthropic API Errors](https://platform.claude.com/docs/en/api/errors)
