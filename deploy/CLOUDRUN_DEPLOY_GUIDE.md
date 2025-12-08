# Cloud Run + OpenTelemetry Collector Sidecar 部署指南

> 在 **Google Cloud Shell** 中執行所有指令

---

## 架構圖

```
                            ┌─────────────────────────────────────────────────────────────┐
         Ingress            │                      Cloud Run Service                       │
      ────────────────────► │  ┌──────────────────┐    OTLP     ┌───────────────────────┐ │
                            │  │   App Container  │ ──────────► │  OTel Collector       │ │
                            │  │   (port 8080)    │  :4317/4318 │  Sidecar              │ │
                            │  └────────┬─────────┘             └───────────┬───────────┘ │
                            └───────────┼───────────────────────────────────┼─────────────┘
                                        │                                   │
                                        │ Direct VPC Egress (選用)          │
                                        ▼                                   │
                            ┌───────────────────────────────────┐           │
                            │         VPC Network               │           │
                            │   ┌───────────────────────────┐   │           │
                            │   │  Subnet (100.64.0.0/24)   │   │           │
                            │   └─────────────┬─────────────┘   │           │
                            └─────────────────┼─────────────────┘           │
                                              │                             │
                            ┌─────────────────┼─────────────────┐           │
                            │  Cloud Router + Cloud NAT        │           │
                            │  (Static IP: gate-egress-ip)     │           │
                            └─────────────────┬─────────────────┘           │
                                              │ 固定 IP 出口                │
                    ┌─────────────────────────┼─────────────────────────────┼───────────────┐
                    │                         ▼                             ▼               │
                    │   ┌────────────────────────────┐  ┌─────────────────────────────────┐ │
                    │   │     External APIs          │  │         Google Cloud            │ │
                    │   │  (需要 IP 白名單的服務)    │  │  • Cloud Trace (追蹤)           │ │
                    │   │                            │  │  • Cloud Monitoring (指標)      │ │
                    │   │                            │  │  • Cloud Logging (日誌)         │ │
                    │   └────────────────────────────┘  └─────────────────────────────────┘ │
                    └─────────────────────────────────────────────────────────────────────────┘
```

---

## 部署流程總覽

| 階段 | 步驟 | 說明 |
|------|------|------|
| **環境設定** | Step 0-3 | 確認環境、設定變數、啟用 API |
| **Pub/Sub 設定** | Step 3-A | 建立 Pub/Sub Topic (用量事件) |
| **服務帳戶** | Step 4 | 建立服務帳戶並授權 (含 Pub/Sub Publisher) |
| **固定出口 IP** | Step 4-A | (選用) 建立 VPC、Subnet、Cloud NAT 與靜態 IP |
| **準備檔案** | Step 5-6 | 建立工作目錄、準備 OTel 與應用程式配置檔 |
| **建立 Secrets** | Step 7 | 將所有配置檔與機敏值存入 Secret Manager |
| **建立部署檔** | Step 8 | 產生 Cloud Run Service YAML |
| **部署與驗證** | Step 9-11 | 部署服務、設定存取權限、驗證結果 |

---

## Step 0: 確認 gcloud 環境

```bash
# 查看 gcloud 版本
gcloud version

# 查看目前登入帳號
gcloud auth list

# 查看目前所在專案
gcloud config get-value project

# 列出可用專案
gcloud projects list

# 切換專案 (如需要)
# gcloud config set project YOUR_PROJECT_ID
```

---

## Step 1: 定義環境變數

### 可配置變數一覽

| 變數名稱 | 說明 | 範例值 |
|---------|------|--------|
| `PROJECT_ID` | GCP 專案 ID | (自動取得) |
| `REGION` | Cloud Run 部署區域 | `asia-east1` |
| `SERVICE_NAME` | Cloud Run 服務名稱 | `gate` |
| `ENV_PROFILE` | 環境 profile 名稱 | `lab` 或 `prod` |
| `APP_IMAGE` | 應用程式 Docker 映像 | `spike19820318/gate:0.0.8` |
| `APP_PORT` | 應用程式監聽埠 | `8080` |
| `APP_CPU` | 應用程式 CPU 限制 | `1000m` |
| `APP_MEMORY` | 應用程式記憶體限制 | `1Gi` |
| `OTEL_COLLECTOR_IMAGE` | OTel Collector 映像 | `us-docker.pkg.dev/.../otelcol-google:0.138.0` |
| `OTEL_CPU` | Collector CPU 限制 | `500m` |
| `OTEL_MEMORY` | Collector 記憶體限制 | `512Mi` |
| `OTEL_LOG_NAME` | Cloud Logging 日誌名稱 (用於篩選日誌) | `${SERVICE_NAME}` |
| `MAX_INSTANCES` | 最大實例數 | `1` |
| `CONTAINER_CONCURRENCY` | 容器並發請求數 | `80` |
| `OTEL_SECRET_NAME` | OTel Collector 配置密鑰名稱 | `otel-collector-config` |
| `CONFIG_SECRET_NAME` | 應用程式配置密鑰名稱 | `gate-config` |
| `SERVICE_ACCOUNT_ID` | 服務帳戶 ID | `gate-sa` |
| `VPC_NETWORK` | (選用) VPC 網路名稱 | `gate-vpc` |
| `SUBNET_NAME` | (選用) 子網路名稱 | `gate-subnet` |
| `SUBNET_RANGE` | (選用) 子網路 CIDR | `100.64.0.0/24` |
| `ROUTER_NAME` | (選用) Cloud Router 名稱 | `gate-router` |
| `NAT_NAME` | (選用) Cloud NAT 名稱 | `gate-nat` |
| `STATIC_IP_NAME` | (選用) 靜態 IP 名稱 | `gate-egress-ip` |

### 設定變數

```bash
# ==================================================
# GCP 專案設定 (自動從 gcloud 取得)
# ==================================================
export PROJECT_ID=$(gcloud config get-value project)
export REGION="asia-east1"

# ==================================================
# 服務設定
# ==================================================
export SERVICE_NAME="gate"
# 環境 profile: lab, prod 等 (每個 GCP Project 為獨立環境)
export ENV_PROFILE="lab"

# ==================================================
# Secret Manager 設定 (名稱不需環境後綴，因為每個專案獨立)
# ==================================================
export OTEL_SECRET_NAME="otel-collector-config"
export CONFIG_SECRET_NAME="gate-config"

# ==================================================
# 應用程式容器設定
# ==================================================
export APP_IMAGE="spike19820318/gate:0.0.11"
export APP_PORT="8080"
export APP_CPU="1000m"
export APP_MEMORY="1Gi"

# ==================================================
# OpenTelemetry Collector 設定
# ==================================================
# 映像版本參考: https://github.com/GoogleCloudPlatform/opentelemetry-operations-collector/releases
export OTEL_COLLECTOR_IMAGE="us-docker.pkg.dev/cloud-ops-agents-artifacts/google-cloud-opentelemetry-collector/otelcol-google:0.138.0"
export OTEL_CPU="500m"
export OTEL_MEMORY="512Mi"
# OTEL_LOG_NAME: 設定 Cloud Logging 中的日誌名稱 (googlecloud exporter 的 default_log_name)
# - 官方預設值: "opentelemetry-collector"
# - 我們使用服務名稱，方便在 Cloud Logging 識別來源
# - 篩選方式: logName="projects/${PROJECT_ID}/logs/${OTEL_LOG_NAME}"
# - 參考: https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/googlecloudexporter/README.md
export OTEL_LOG_NAME="${SERVICE_NAME}"

# ==================================================
# 擴展設定
# ==================================================
export MAX_INSTANCES="1"
export CONTAINER_CONCURRENCY="80"

# ==================================================
# 服務帳戶設定
# ==================================================
export SERVICE_ACCOUNT_ID="gate-sa"
export SERVICE_ACCOUNT="${SERVICE_ACCOUNT_ID}@${PROJECT_ID}.iam.gserviceaccount.com"

# ==================================================
# (選用) 固定出口 IP 設定 - 如需要固定 IP 才設定
# ==================================================
# VPC 網路設定
export VPC_NETWORK="gate-vpc"
export SUBNET_NAME="gate-subnet"
# Subnet CIDR: 使用 RFC 6598 範圍，/24 提供 252 個可用 IP
# - /26 (60 IP): 最小需求，支援約 25 instances
# - /24 (252 IP): 推薦，支援約 120 instances
export SUBNET_RANGE="100.64.0.0/24"
# Cloud Router + NAT 設定
export ROUTER_NAME="gate-router"
export NAT_NAME="gate-nat"
export STATIC_IP_NAME="gate-egress-ip"

# 驗證變數
echo "=========================================="
echo "部署配置確認"
echo "=========================================="
echo "PROJECT_ID:          $PROJECT_ID"
echo "REGION:              $REGION"
echo "SERVICE_NAME:        $SERVICE_NAME"
echo "ENV_PROFILE:         $ENV_PROFILE"
echo "APP_IMAGE:           $APP_IMAGE"
echo "CONFIG_SECRET:       $CONFIG_SECRET_NAME"
echo "OTEL_SECRET:         $OTEL_SECRET_NAME"
echo "SERVICE_ACCOUNT:     $SERVICE_ACCOUNT"
echo "=========================================="
```

---

## Step 2: 設定 GCP 專案 (如需切換)

> **Cloud Shell 用戶**：如果命令列已顯示正確專案 (如 `(vibe-lab)`)，可跳過此步驟。

```bash
# 僅在需要切換專案時執行
gcloud config set project $PROJECT_ID
```

---

## Step 3: 啟用 API

啟用 Cloud Run 部署和可觀測性所需的 Google Cloud API：

| API | 說明 |
|-----|------|
| `run.googleapis.com` | Cloud Run 服務，用於部署和執行容器 |
| `secretmanager.googleapis.com` | Secret Manager，安全儲存配置檔與機敏值 |
| `cloudtrace.googleapis.com` | Cloud Trace，收集分散式追蹤資料 |
| `monitoring.googleapis.com` | Cloud Monitoring，收集指標資料 |
| `logging.googleapis.com` | Cloud Logging，收集日誌資料 |
| `iam.googleapis.com` | IAM，管理服務帳戶和權限 |
| `pubsub.googleapis.com` | Pub/Sub，用於發送用量事件訊息 |
| `compute.googleapis.com` | (選用) Compute Engine，用於 VPC/NAT 設定 |

```bash
gcloud services enable \
  run.googleapis.com \
  secretmanager.googleapis.com \
  cloudtrace.googleapis.com \
  monitoring.googleapis.com \
  logging.googleapis.com \
  iam.googleapis.com \
  pubsub.googleapis.com \
  compute.googleapis.com
```

---

## Step 3-A: Pub/Sub Topic 說明

應用程式使用 Spring Cloud GCP Pub/Sub Stream Binder 發送用量事件到 Topic `llm-gateway-usage`。

### 自動建立 vs 手動建立

| 方式 | 說明 | 所需權限 | 建議環境 |
|------|------|----------|----------|
| **選項 A: 自動建立** | 應用程式首次發送訊息時自動建立 Topic | `roles/pubsub.editor` | 開發/測試 |
| **選項 B: 手動建立** | 預先建立 Topic，應用程式只負責發送 | `roles/pubsub.publisher` | 生產環境 |

### 選項 A: 自動建立

不需要手動建立 Topic，應用程式會在首次發送訊息時自動建立。

→ 權限設定請見 **Step 4.4**（選擇 `roles/pubsub.editor`）

### 選項 B: 手動建立 (建議生產環境)

預先建立 Topic，符合最小權限原則：

```bash
# 建立 Pub/Sub Topic
gcloud pubsub topics create llm-gateway-usage --project=$PROJECT_ID

echo "✅ Pub/Sub Topic 已建立: llm-gateway-usage"
```

→ 權限設定請見 **Step 4.4**（選擇 `roles/pubsub.publisher`）

> **Topic 名稱說明**:
> - 名稱 `llm-gateway-usage` 定義在 `application.yaml` 的 `spring.cloud.stream.bindings.usageEvent-out-0.destination`
> - 應用程式啟動時會自動連接此 Topic

---

## Step 4: 建立服務帳戶並授權

### 4.1 檢查服務帳戶是否已存在

```bash
gcloud iam service-accounts describe $SERVICE_ACCOUNT 2>/dev/null || echo "服務帳戶不存在，將建立新的"
```

### 4.2 建立服務帳戶

```bash
gcloud iam service-accounts create $SERVICE_ACCOUNT_ID \
  --display-name="Cloud Run Service Account for ${SERVICE_NAME}" \
  --description="Service account for ${SERVICE_NAME} on Cloud Run" \
  --project=$PROJECT_ID
```

### 4.3 授予必要角色

```bash
# Cloud Run 執行所需的基本權限
for ROLE in \
  "roles/logging.logWriter" \
  "roles/monitoring.metricWriter" \
  "roles/cloudtrace.agent" \
  "roles/secretmanager.secretAccessor"
do
  echo "授予角色: $ROLE"
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SERVICE_ACCOUNT" \
    --role="$ROLE" \
    --condition=None \
    --quiet
done

echo "✅ 基本權限設定完成"
```

### 4.4 授予 Pub/Sub 權限

根據 Step 3-A 選擇的方式授予對應權限：

```bash
# 選項 A: 自動建立 Topic (開發/測試環境)
PUBSUB_ROLE="roles/pubsub.editor"

# 選項 B: 手動建立 Topic (生產環境，最小權限)
# PUBSUB_ROLE="roles/pubsub.publisher"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="$PUBSUB_ROLE" \
  --condition=None \
  --quiet

echo "✅ Pub/Sub 權限設定完成: $PUBSUB_ROLE"
```

### 4.5 驗證服務帳戶

```bash
# 查看服務帳戶資訊
gcloud iam service-accounts describe $SERVICE_ACCOUNT

# 查看已授予的角色
gcloud projects get-iam-policy $PROJECT_ID \
  --flatten="bindings[].members" \
  --filter="bindings.members:$SERVICE_ACCOUNT" \
  --format="table(bindings.role)"
```

---

## Step 4-A: 設定固定出口 IP (選用)

> **此步驟為選用**：僅當你的服務需要固定出口 IP (例如呼叫需要 IP 白名單的外部 API) 時才需執行。

### 架構說明

使用 Direct VPC Egress + Cloud NAT 實現固定出口 IP：

| 元件 | 用途 |
|------|------|
| VPC Network | 虛擬私有網路 |
| Subnet | 子網路，Cloud Run 從此分配 IP |
| Cloud Router | 路由控制器 |
| Cloud NAT | 網路位址轉換，綁定靜態 IP |
| Static IP | 固定的外部 IP 位址 |

### Subnet 大小建議

| Subnet | 可用 IP | 支援 Max Instances | 建議場景 |
|--------|---------|-------------------|----------|
| `/26` | 60 | ~25 | 開發測試 |
| `/24` | 252 | ~120 | **推薦生產環境** |
| `/23` | 508 | ~250 | 大型服務 |

> **IP 消耗公式**：Cloud Run 使用 `2 × 實例數` 的 IP，版本更新時需要雙倍 (舊版 + 新版同時存在)

### 4-A.1 建立 VPC 網路

```bash
# 建立 VPC 網路 (custom mode)
gcloud compute networks create $VPC_NETWORK \
  --subnet-mode=custom \
  --project=$PROJECT_ID

echo "✅ VPC 網路已建立: $VPC_NETWORK"
```

### 4-A.2 建立子網路

```bash
# 建立子網路 (使用 RFC 6598 範圍，避免與常見私有 IP 衝突)
gcloud compute networks subnets create $SUBNET_NAME \
  --network=$VPC_NETWORK \
  --range=$SUBNET_RANGE \
  --region=$REGION \
  --project=$PROJECT_ID

echo "✅ 子網路已建立: $SUBNET_NAME ($SUBNET_RANGE)"
```

### 4-A.3 保留靜態 IP

```bash
# 保留靜態外部 IP
gcloud compute addresses create $STATIC_IP_NAME \
  --region=$REGION \
  --project=$PROJECT_ID

# 查看保留的 IP 位址
STATIC_IP=$(gcloud compute addresses describe $STATIC_IP_NAME \
  --region=$REGION \
  --format='value(address)' \
  --project=$PROJECT_ID)

echo "✅ 靜態 IP 已保留: $STATIC_IP"
```

### 4-A.4 建立 Cloud Router

```bash
gcloud compute routers create $ROUTER_NAME \
  --network=$VPC_NETWORK \
  --region=$REGION \
  --project=$PROJECT_ID

echo "✅ Cloud Router 已建立: $ROUTER_NAME"
```

### 4-A.5 建立 Cloud NAT

```bash
gcloud compute routers nats create $NAT_NAME \
  --router=$ROUTER_NAME \
  --region=$REGION \
  --nat-custom-subnet-ip-ranges=$SUBNET_NAME \
  --nat-external-ip-pool=$STATIC_IP_NAME \
  --project=$PROJECT_ID

echo "✅ Cloud NAT 已建立: $NAT_NAME (使用靜態 IP: $STATIC_IP)"
```

### 4-A.6 驗證設定

```bash
echo "=== VPC 網路 ==="
gcloud compute networks describe $VPC_NETWORK --project=$PROJECT_ID --format="table(name,selfLink)"

echo ""
echo "=== 子網路 ==="
gcloud compute networks subnets describe $SUBNET_NAME --region=$REGION --project=$PROJECT_ID --format="table(name,ipCidrRange,region)"

echo ""
echo "=== 靜態 IP ==="
gcloud compute addresses describe $STATIC_IP_NAME --region=$REGION --project=$PROJECT_ID --format="table(name,address,status)"

echo ""
echo "=== Cloud NAT ==="
gcloud compute routers nats describe $NAT_NAME --router=$ROUTER_NAME --region=$REGION --project=$PROJECT_ID
```

---

## Step 5: 建立工作目錄

```bash
mkdir -p ~/cloudrun-deploy && cd ~/cloudrun-deploy
```

---

## Step 6: 準備配置檔

在此步驟準備所有配置檔，下一步會統一存入 Secret Manager。

### 6.1 建立 OTel Collector 配置檔

建立 OpenTelemetry Collector 配置檔，定義如何接收、處理和導出遙測資料：

| 區塊 | 說明 |
|------|------|
| `receivers.otlp` | 接收 OTLP 協議資料 (gRPC: 4317, HTTP: 4318) |
| `processors.batch` | 批次處理，減少 API 呼叫次數 |
| `processors.memory_limiter` | 記憶體限制，防止 OOM |
| `processors.resourcedetection` | 自動偵測 GCP 資源屬性 |
| `exporters.googlecloud` | 導出 Traces 和 Logs 到 Cloud Trace / Cloud Logging |
| `exporters.googlemanagedprometheus` | 導出 Metrics 到 Cloud Monitoring |
| `extensions.health_check` | 健康檢查端點 (port 13133) |

```bash
cat > otel-collector-config.yaml << EOF
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
processors:
  batch:
    send_batch_size: 200
    timeout: 5s
  memory_limiter:
    check_interval: 1s
    limit_percentage: 65
    spike_limit_percentage: 20
  resourcedetection:
    detectors: [env, gcp]
    timeout: 2s
    override: false
exporters:
  googlecloud:
    log:
      default_log_name: $OTEL_LOG_NAME
  googlemanagedprometheus:
extensions:
  health_check:
    endpoint: 0.0.0.0:13133
service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlemanagedprometheus]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
EOF

echo "✅ 已建立 otel-collector-config.yaml"
```

### 6.2 建立應用程式配置檔

此配置檔會被 Cloud Run 掛載到 `/config/application-{profile}.yaml`，Spring Boot 透過 `spring.config.additional-location` 自動載入。

> **注意**: 配置檔中使用 `${sm@secret-name}` 語法，讓 Spring Cloud GCP 在執行時從 Secret Manager 讀取機敏值。

```bash
cat > application-${ENV_PROFILE}.yaml << 'APPEOF'
# =============================================================================
# 應用程式配置 - 環境特定設定
# =============================================================================
# 此檔案透過 Secret Manager 掛載到 /config/
# 機敏值使用 ${sm@secret-name} 語法，由 Spring Cloud GCP 在執行時解析

# 機敏屬性來源 - 從 Secret Manager 讀取
gate-jwt-jwk-set-uri: ${sm@gate-jwt-jwk-set-uri}
gate-anthropic-api-key-primary: ${sm@gate-anthropic-api-key-primary}

# Anthropic API 配置
anthropic:
  api:
    keys:
      - alias: "primary"
        value: ${gate-anthropic-api-key-primary}

# 可觀測性
management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus

# 日誌
logging:
  level:
    root: INFO
    io.github.samzhu.gate: DEBUG
APPEOF

echo "✅ 已建立 application-${ENV_PROFILE}.yaml"
```

### 6.3 確認配置檔已建立

```bash
ls -la *.yaml
```

---

## Step 7: 建立 Secret Manager 密鑰

將所有配置檔與機敏值存入 Secret Manager。

### 7.1 建立機敏值 Secrets

這些 secrets 包含實際的機敏資料，會被應用程式配置檔透過 `${sm@secret-name}` 語法引用。

```bash
# 建立 JWT JWKS URL secret (請替換為實際值)
echo -n "https://your-auth-server.com/.well-known/jwks.json" | \
  gcloud secrets create gate-jwt-jwk-set-uri \
    --data-file=- \
    --replication-policy="automatic" \
    --project=$PROJECT_ID

# 建立 Anthropic API Key secret (請替換為實際值)
echo -n "sk-ant-api03-your-api-key" | \
  gcloud secrets create gate-anthropic-api-key-primary \
    --data-file=- \
    --replication-policy="automatic" \
    --project=$PROJECT_ID

echo "✅ 機敏值 Secrets 已建立"
```

完成後可驗證：

```bash
gcloud secrets list
```

### 7.2 建立 OTel Collector 配置 Secret

```bash
gcloud secrets describe $OTEL_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --project=$PROJECT_ID || \
  gcloud secrets create $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --replication-policy="automatic" --project=$PROJECT_ID
```

Output:
```
createTime: '2025-12-02T06:43:28.310330Z'
etag: '"1644f269e76f76"'
name: projects/644359853825/secrets/otel-collector-config
replication:
  automatic: {}
Created version [2] of the secret [otel-collector-config].
```

### 7.3 建立應用程式配置 Secret

```bash
gcloud secrets describe $CONFIG_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --project=$PROJECT_ID || \
  gcloud secrets create $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --replication-policy="automatic" --project=$PROJECT_ID
```

Output:
```
Created version [1] of the secret [gate-config].
```

### 7.4 驗證所有 Secrets

```bash
echo "=== Secrets 清單 ==="
gcloud secrets list --project=$PROJECT_ID --filter="name~gate"

echo ""
echo "=== OTel 配置版本 ==="
gcloud secrets versions list $OTEL_SECRET_NAME --project=$PROJECT_ID

echo ""
echo "=== 應用程式配置版本 ==="
gcloud secrets versions list $CONFIG_SECRET_NAME --project=$PROJECT_ID
```

---

## Step 8: 建立 Cloud Run Service YAML

建立 Cloud Run 服務定義，包含主應用容器和 OTel Collector Sidecar。

### 關鍵 Annotations 說明

**Service 層級** (`metadata.annotations`):

| Annotation | 說明 |
|------------|------|
| `run.googleapis.com/launch-stage: BETA` | 啟用多容器 sidecar 功能 |

**Template 層級** (`spec.template.metadata.annotations`):

| Annotation | 說明 |
|------------|------|
| `run.googleapis.com/cpu-throttling: 'false'` | CPU 持續分配，確保 OTel Collector 背景運行 ([官方文件](https://docs.cloud.google.com/run/docs/configuring/cpu-allocation)) |
| `run.googleapis.com/container-dependencies` | 定義容器啟動順序，`{app:[collector]}` 表示 app 依賴 collector，collector 先啟動 |
| `run.googleapis.com/secrets` | 掛載 Secret Manager 密鑰 |
| `run.googleapis.com/execution-environment: gen2` | 使用第二代執行環境 |
| `run.googleapis.com/startup-cpu-boost` | 啟動時提供額外 CPU |
| `autoscaling.knative.dev/maxScale` | 最大實例數 |
| `run.googleapis.com/network-interfaces` | (選用) Direct VPC Egress 網路設定 |
| `run.googleapis.com/vpc-access-egress` | (選用) VPC 出口流量設定：`all-traffic` 或 `private-ranges-only` |

### Health Probes 說明

**App 容器** (Spring Boot Actuator):

| Probe | 路徑 | 用途 |
|-------|------|------|
| `startupProbe` | `/actuator/health/readiness` | 判斷容器是否啟動完成 (最長等待 155 秒) |
| `livenessProbe` | `/actuator/health/liveness` | 判斷容器是否正常運行 |

**Collector 容器** (OTel health_check extension):

| Probe | 路徑 | 用途 |
|-------|------|------|
| `startupProbe` | `/` (port 13133) | 判斷 Collector 是否啟動完成 |
| `livenessProbe` | `/` (port 13133) | 判斷 Collector 是否正常運行 |

```bash
cat > cloudrun-service.yaml << EOF
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: $SERVICE_NAME
  annotations:
    run.googleapis.com/launch-stage: BETA
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/cpu-throttling: 'false'
        run.googleapis.com/container-dependencies: "{app:[collector]}"
        run.googleapis.com/secrets: "${OTEL_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${OTEL_SECRET_NAME},${CONFIG_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${CONFIG_SECRET_NAME}"
        autoscaling.knative.dev/maxScale: "${MAX_INSTANCES}"
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/startup-cpu-boost: "true"
        # (選用) 固定出口 IP - 如已完成 Step 4-A，取消下兩行註解
        # run.googleapis.com/network-interfaces: '[{"network":"${VPC_NETWORK}","subnetwork":"${SUBNET_NAME}"}]'
        # run.googleapis.com/vpc-access-egress: all-traffic
    spec:
      containerConcurrency: $CONTAINER_CONCURRENCY
      timeoutSeconds: 300
      serviceAccountName: $SERVICE_ACCOUNT
      containers:
        - name: app
          image: $APP_IMAGE
          ports:
            - name: http1
              containerPort: $APP_PORT
          env:
            - name: spring.profiles.active
              value: "gcp,$ENV_PROFILE"
            - name: spring.config.additional-location
              value: "optional:file:/config/"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://localhost:4318"
          resources:
            limits:
              cpu: $APP_CPU
              memory: $APP_MEMORY
          startupProbe:
            httpGet:
              path: /actuator/health/readiness
              port: $APP_PORT
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 30
            timeoutSeconds: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: $APP_PORT
            periodSeconds: 30
            failureThreshold: 3
            timeoutSeconds: 3
          volumeMounts:
            - name: app-config
              mountPath: /config
              readOnly: true
        - name: collector
          image: $OTEL_COLLECTOR_IMAGE
          args:
            - --config=/etc/otelcol-google/config.yaml
          resources:
            limits:
              cpu: $OTEL_CPU
              memory: $OTEL_MEMORY
          startupProbe:
            httpGet:
              path: /
              port: 13133
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 12
          livenessProbe:
            httpGet:
              path: /
              port: 13133
            periodSeconds: 30
            timeoutSeconds: 30
          volumeMounts:
            - name: otel-config
              mountPath: /etc/otelcol-google/
              readOnly: true
      volumes:
        - name: app-config
          secret:
            secretName: $CONFIG_SECRET_NAME
            items:
              - key: latest
                path: application-${ENV_PROFILE}.yaml
        - name: otel-config
          secret:
            secretName: $OTEL_SECRET_NAME
            items:
              - key: latest
                path: config.yaml
  traffic:
    - percent: 100
      latestRevision: true
EOF
```

---

## Step 9: 部署 Cloud Run 服務

使用 `gcloud run services replace` 部署或更新服務：

```bash
gcloud run services replace cloudrun-service.yaml \
  --region=$REGION \
  --project=$PROJECT_ID
```

---

## Step 10: 設定公開存取 (選用)

允許未經驗證的請求存取服務 (適用於公開 API)：

```bash
gcloud run services add-iam-policy-binding $SERVICE_NAME \
  --region=$REGION \
  --member="allUsers" \
  --role="roles/run.invoker"
```

> 若需要驗證存取，請跳過此步驟。

---

## Step 11: 驗證部署

### 11.1 取得服務 URL

Cloud Run 提供兩種 URL 格式：

| 格式 | 說明 | 範例 |
|------|------|------|
| Deterministic | 可預測，包含 Project Number | `SERVICE-PROJECT_NUMBER.REGION.run.app` |
| Legacy | 包含隨機 hash | `SERVICE-HASH.a.run.app` |

```bash
# Deterministic URL (與 Console 顯示一致)
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
SERVICE_URL="https://${SERVICE_NAME}-${PROJECT_NUMBER}.${REGION}.run.app"
echo "Service URL: $SERVICE_URL"
```

### 11.2 健康檢查

```bash
curl -s "${SERVICE_URL}/actuator/health" | jq .
```

---

## 快速部署 (一鍵執行)

將 Step 1 的變數設定好後，執行以下完整腳本：

```bash
# 確保已設定所有變數後執行
cd ~/cloudrun-deploy && \

# ========== Step 6: 建立配置檔 ==========
# OTel Collector 配置
cat > otel-collector-config.yaml << EOF
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
processors:
  batch:
    send_batch_size: 200
    timeout: 5s
  memory_limiter:
    check_interval: 1s
    limit_percentage: 65
    spike_limit_percentage: 20
  resourcedetection:
    detectors: [env, gcp]
    timeout: 2s
    override: false
exporters:
  googlecloud:
    log:
      default_log_name: $OTEL_LOG_NAME
  googlemanagedprometheus:
extensions:
  health_check:
    endpoint: 0.0.0.0:13133
service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlemanagedprometheus]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, batch]
      exporters: [googlecloud]
EOF

# 應用程式配置
cat > application-${ENV_PROFILE}.yaml << 'APPEOF'
gate-jwt-jwk-set-uri: ${sm@gate-jwt-jwk-set-uri}
gate-anthropic-api-key-primary: ${sm@gate-anthropic-api-key-primary}

anthropic:
  api:
    keys:
      - alias: "primary"
        value: ${gate-anthropic-api-key-primary}

management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus

logging:
  level:
    root: INFO
    io.github.samzhu.gate: DEBUG
APPEOF

# ========== Step 7: 建立 Secrets ==========
# OTel Collector 配置 Secret
gcloud secrets describe $OTEL_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --project=$PROJECT_ID || \
  gcloud secrets create $OTEL_SECRET_NAME --data-file=otel-collector-config.yaml --replication-policy="automatic" --project=$PROJECT_ID

# 應用程式配置 Secret
gcloud secrets describe $CONFIG_SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --project=$PROJECT_ID || \
  gcloud secrets create $CONFIG_SECRET_NAME --data-file=application-${ENV_PROFILE}.yaml --replication-policy="automatic" --project=$PROJECT_ID

# ========== Step 8: 建立 Cloud Run 服務 YAML ==========
cat > cloudrun-service.yaml << EOF
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: $SERVICE_NAME
  annotations:
    run.googleapis.com/launch-stage: BETA
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/cpu-throttling: 'false'
        run.googleapis.com/container-dependencies: "{app:[collector]}"
        run.googleapis.com/secrets: "${OTEL_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${OTEL_SECRET_NAME},${CONFIG_SECRET_NAME}:projects/${PROJECT_ID}/secrets/${CONFIG_SECRET_NAME}"
        autoscaling.knative.dev/maxScale: "${MAX_INSTANCES}"
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/startup-cpu-boost: "true"
        # (選用) 固定出口 IP - 如已完成 Step 4-A，取消下兩行註解
        # run.googleapis.com/network-interfaces: '[{"network":"${VPC_NETWORK}","subnetwork":"${SUBNET_NAME}"}]'
        # run.googleapis.com/vpc-access-egress: all-traffic
    spec:
      containerConcurrency: $CONTAINER_CONCURRENCY
      timeoutSeconds: 300
      serviceAccountName: $SERVICE_ACCOUNT
      containers:
        - name: app
          image: $APP_IMAGE
          ports:
            - name: http1
              containerPort: $APP_PORT
          env:
            - name: spring.profiles.active
              value: "gcp,$ENV_PROFILE"
            - name: spring.config.additional-location
              value: "optional:file:/config/"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://localhost:4318"
          resources:
            limits:
              cpu: $APP_CPU
              memory: $APP_MEMORY
          startupProbe:
            httpGet:
              path: /actuator/health/readiness
              port: $APP_PORT
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 30
            timeoutSeconds: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: $APP_PORT
            periodSeconds: 30
            failureThreshold: 3
            timeoutSeconds: 3
          volumeMounts:
            - name: app-config
              mountPath: /config
              readOnly: true
        - name: collector
          image: $OTEL_COLLECTOR_IMAGE
          args:
            - --config=/etc/otelcol-google/config.yaml
          resources:
            limits:
              cpu: $OTEL_CPU
              memory: $OTEL_MEMORY
          startupProbe:
            httpGet:
              path: /
              port: 13133
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 12
          livenessProbe:
            httpGet:
              path: /
              port: 13133
            periodSeconds: 30
            timeoutSeconds: 30
          volumeMounts:
            - name: otel-config
              mountPath: /etc/otelcol-google/
              readOnly: true
      volumes:
        - name: app-config
          secret:
            secretName: $CONFIG_SECRET_NAME
            items:
              - key: latest
                path: application-${ENV_PROFILE}.yaml
        - name: otel-config
          secret:
            secretName: $OTEL_SECRET_NAME
            items:
              - key: latest
                path: config.yaml
  traffic:
    - percent: 100
      latestRevision: true
EOF

# ========== Step 9: 部署 ==========
gcloud run services replace cloudrun-service.yaml --region=$REGION --project=$PROJECT_ID

echo "✅ 部署完成: $(gcloud run services describe $SERVICE_NAME --region=$REGION --format='value(status.url)')"
```

---

## 常用指令

### Cloud Run 服務管理

```bash
# 查看日誌
gcloud run services logs read $SERVICE_NAME --region=$REGION --limit=50

# 查看服務狀態
gcloud run services describe $SERVICE_NAME --region=$REGION

# 更新映像版本
export APP_IMAGE="spike19820318/gate:0.0.8"
# 然後重新執行 Step 8 (Cloud Run YAML) 和 Step 9 (部署)

# 刪除服務
gcloud run services delete $SERVICE_NAME --region=$REGION --quiet
```

### VPC / NAT 管理 (如已設定固定出口 IP)

```bash
# 查看靜態 IP
gcloud compute addresses describe $STATIC_IP_NAME \
  --region=$REGION \
  --format='table(name,address,status)' \
  --project=$PROJECT_ID

# 查看 NAT 設定
gcloud compute routers nats describe $NAT_NAME \
  --router=$ROUTER_NAME \
  --region=$REGION \
  --project=$PROJECT_ID

# 查看 NAT 映射 (確認流量是否經過 NAT)
gcloud compute routers get-nat-mapping-info $ROUTER_NAME \
  --region=$REGION \
  --project=$PROJECT_ID

# 更新 Cloud Run 服務啟用 VPC Egress (CLI 方式)
gcloud run services update $SERVICE_NAME \
  --network=$VPC_NETWORK \
  --subnet=$SUBNET_NAME \
  --vpc-egress=all-traffic \
  --region=$REGION \
  --project=$PROJECT_ID

# 刪除 VPC 相關資源 (按順序)
gcloud compute routers nats delete $NAT_NAME --router=$ROUTER_NAME --region=$REGION --quiet
gcloud compute routers delete $ROUTER_NAME --region=$REGION --quiet
gcloud compute addresses delete $STATIC_IP_NAME --region=$REGION --quiet
gcloud compute networks subnets delete $SUBNET_NAME --region=$REGION --quiet
gcloud compute networks delete $VPC_NETWORK --quiet
```

---

## 觀測連結

```bash
echo "Cloud Trace:      https://console.cloud.google.com/traces/list?project=$PROJECT_ID"
echo "Cloud Monitoring: https://console.cloud.google.com/monitoring?project=$PROJECT_ID"
echo "Cloud Logging:    https://console.cloud.google.com/logs?project=$PROJECT_ID"
```

---

## 參考資料

### Google Cloud 官方文件
- [Cloud Run Secrets (掛載 Secret 為檔案)](https://docs.cloud.google.com/run/docs/configuring/services/secrets)
- [OpenTelemetry Collector for Cloud Run](https://docs.cloud.google.com/stackdriver/docs/instrumentation/opentelemetry-collector-cloud-run)
- [Google-built OTel Collector](https://docs.cloud.google.com/stackdriver/docs/instrumentation/google-built-otel)
- [Cloud Run Multi-Container (Sidecar)](https://docs.cloud.google.com/run/docs/deploying#sidecars)
- [Cloud Run Logging](https://docs.cloud.google.com/run/docs/logging)

### Spring Boot 配置
- [Spring Boot External Config](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Spring Cloud GCP Secret Manager](https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#secret-manager)

### OpenTelemetry 官方資源
- [OTel Collector Releases](https://github.com/GoogleCloudPlatform/opentelemetry-operations-collector/releases)
- [Google Cloud Exporter (default_log_name)](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/googlecloudexporter/README.md)
- [OTel Security Best Practices](https://opentelemetry.io/docs/security/config-best-practices/)

### VPC 與網路 (固定出口 IP)
- [Static Outbound IP Address](https://docs.cloud.google.com/run/docs/configuring/static-outbound-ip) - 設定固定出口 IP
- [Direct VPC Egress](https://docs.cloud.google.com/run/docs/configuring/vpc-direct-vpc) - Direct VPC Egress 設定
- [Compare VPC Egress Options](https://docs.cloud.google.com/run/docs/configuring/connecting-vpc) - VPC 連線方式比較
- [Cloud Run Networking Best Practices](https://docs.cloud.google.com/run/docs/configuring/networking-best-practices) - 網路最佳實踐
- [Subnets](https://docs.cloud.google.com/vpc/docs/subnets) - Subnet 規劃指南
