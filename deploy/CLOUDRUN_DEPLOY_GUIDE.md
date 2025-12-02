# Cloud Run + OpenTelemetry Collector Sidecar 部署指南

> 在 **Google Cloud Shell** 中執行所有指令

---

## 架構圖

```
┌─────────────────────────────────────────────────────────────┐
│                      Cloud Run Service                       │
│  ┌──────────────────┐    OTLP     ┌───────────────────────┐ │
│  │   App Container  │ ──────────► │  OTel Collector       │ │
│  │   (port 8080)    │  :4317/4318 │  Sidecar              │ │
│  └──────────────────┘             └───────────┬───────────┘ │
└───────────────────────────────────────────────┼─────────────┘
                                                │
                    ┌───────────────────────────┼───────────────┐
                    │                           ▼               │
                    │   ┌─────────────────────────────────────┐ │
                    │   │         Google Cloud                 │ │
                    │   │  • Cloud Trace (追蹤)               │ │
                    │   │  • Cloud Monitoring (指標)          │ │
                    │   │  • Cloud Logging (日誌)             │ │
                    │   └─────────────────────────────────────┘ │
                    └───────────────────────────────────────────┘
```

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
| `APP_IMAGE` | 應用程式 Docker 映像 | `spike19820318/gate:0.0.2` |
| `APP_PORT` | 應用程式監聽埠 | `8080` |
| `APP_CPU` | 應用程式 CPU 限制 | `1000m` |
| `APP_MEMORY` | 應用程式記憶體限制 | `1Gi` |
| `SPRING_PROFILES` | Spring Boot profiles | `gcp,lab` |
| `OTEL_COLLECTOR_IMAGE` | OTel Collector 映像 | `us-docker.pkg.dev/.../otelcol-google:0.138.0` |
| `OTEL_CPU` | Collector CPU 限制 | `500m` |
| `OTEL_MEMORY` | Collector 記憶體限制 | `512Mi` |
| `OTEL_LOG_NAME` | Cloud Logging 日誌名稱 (用於篩選日誌) | `${SERVICE_NAME}` |
| `MAX_INSTANCES` | 最大實例數 | `1` |
| `CONTAINER_CONCURRENCY` | 容器並發請求數 | `80` |
| `SECRET_NAME` | Secret Manager 密鑰名稱 | `otel-collector-config` |
| `SERVICE_ACCOUNT_ID` | 服務帳戶 ID | `gate-sa` |

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
export SECRET_NAME="otel-collector-config"

# ==================================================
# 應用程式容器設定
# ==================================================
export APP_IMAGE="spike19820318/gate:0.0.2"
export APP_PORT="8080"
export APP_CPU="1000m"
export APP_MEMORY="1Gi"
export SPRING_PROFILES="gcp,lab"

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

# 驗證變數
echo "=========================================="
echo "部署配置確認"
echo "=========================================="
echo "PROJECT_ID:          $PROJECT_ID"
echo "REGION:              $REGION"
echo "SERVICE_NAME:        $SERVICE_NAME"
echo "APP_IMAGE:           $APP_IMAGE"
echo "OTEL_COLLECTOR:      $OTEL_COLLECTOR_IMAGE"
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
| `secretmanager.googleapis.com` | Secret Manager，安全儲存 OTel Collector 配置 |
| `cloudtrace.googleapis.com` | Cloud Trace，收集分散式追蹤資料 |
| `monitoring.googleapis.com` | Cloud Monitoring，收集指標資料 |
| `logging.googleapis.com` | Cloud Logging，收集日誌資料 |
| `iam.googleapis.com` | IAM，管理服務帳戶和權限 |

```bash
gcloud services enable \
  run.googleapis.com \
  secretmanager.googleapis.com \
  cloudtrace.googleapis.com \
  monitoring.googleapis.com \
  logging.googleapis.com \
  iam.googleapis.com
```

---

## Step 4: 建立服務帳戶並授權

### 4.1 檢查服務帳戶是否已存在

```bash
gcloud iam service-accounts describe $SERVICE_ACCOUNT
```

Output:
```bash
ERROR: (gcloud.iam.service-accounts.describe) NOT_FOUND: Unknown service account....
```

> 如果顯示 `NOT_FOUND` 錯誤，表示服務帳戶不存在，請繼續建立。

### 4.2 建立服務帳戶

```bash
gcloud iam service-accounts create $SERVICE_ACCOUNT_ID \
  --display-name="Cloud Run Service Account for ${SERVICE_NAME}" \
  --description="Service account for ${SERVICE_NAME} on Cloud Run" \
  --project=$PROJECT_ID
```

Output:
```bash
Created service account [gate-sa].
```

### 4.3 授予必要角色

```bash
# Cloud Run 執行所需的最小權限
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

echo "✅ 權限設定完成"
```

### 4.4 驗證服務帳戶

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

## Step 5: 建立工作目錄

```bash
mkdir -p ~/cloudrun-deploy && cd ~/cloudrun-deploy
```

---

## Step 6: 建立 OTel Collector 配置

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
```

---

## Step 7: 建立 Cloud Run Service YAML

建立 Cloud Run 服務定義，包含主應用容器和 OTel Collector Sidecar：

### 關鍵 Annotations 說明

**Service 層級** (`metadata.annotations`):

| Annotation | 說明 |
|------------|------|
| `run.googleapis.com/launch-stage: BETA` | 啟用多容器 sidecar 功能 |
| `run.googleapis.com/cpu-throttling: 'false'` | CPU 持續分配，確保 OTel Collector 背景運行 ([官方建議](https://cloud.google.com/run/docs/configuring/cpu-allocation)) |

**Template 層級** (`spec.template.metadata.annotations`):

| Annotation | 說明 |
|------------|------|
| `run.googleapis.com/container-dependencies` | 定義容器啟動順序，`{app:[collector]}` 表示 app 依賴 collector，collector 先啟動 |
| `run.googleapis.com/secrets` | 掛載 Secret Manager 密鑰 |
| `run.googleapis.com/execution-environment: gen2` | 使用第二代執行環境 |
| `run.googleapis.com/startup-cpu-boost` | 啟動時提供額外 CPU |
| `autoscaling.knative.dev/maxScale` | 最大實例數 |

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
    run.googleapis.com/cpu-throttling: 'false'
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/container-dependencies: "{app:[collector]}"
        run.googleapis.com/secrets: "${SECRET_NAME}:projects/${PROJECT_ID}/secrets/${SECRET_NAME}"
        autoscaling.knative.dev/maxScale: "${MAX_INSTANCES}"
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/startup-cpu-boost: "true"
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
              value: "$SPRING_PROFILES"
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
        - name: otel-config
          secret:
            secretName: $SECRET_NAME
            items:
              - key: latest
                path: config.yaml
  traffic:
    - percent: 100
      latestRevision: true
EOF
```

---

## Step 8: 建立 Secret Manager 密鑰

將 OTel Collector 配置存入 Secret Manager，供 Cloud Run 掛載使用。

### 8.1 檢查 Secret 是否存在

```bash
gcloud secrets describe $SECRET_NAME --project=$PROJECT_ID
```

### 8.2a 首次建立 (如果不存在)

```bash
gcloud secrets create $SECRET_NAME \
  --data-file=otel-collector-config.yaml \
  --replication-policy="automatic" \
  --project=$PROJECT_ID
```

### 8.2b 更新版本 (如果已存在)

```bash
gcloud secrets versions add $SECRET_NAME \
  --data-file=otel-collector-config.yaml \
  --project=$PROJECT_ID
```

### 8.3 驗證

```bash
gcloud secrets versions list $SECRET_NAME --project=$PROJECT_ID
```

Output:
```bash
NAME: 1
STATE: enabled
CREATED: 2025-12-02T06:43:31
DESTROYED: -
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
echo "https://${SERVICE_NAME}-${PROJECT_NUMBER}.${REGION}.run.app"
```

### 11.2 健康檢查

```bash
curl -s "https://${SERVICE_NAME}-${PROJECT_NUMBER}.${REGION}.run.app/actuator/health" | jq .
```

---

## 快速部署 (一鍵執行)

將 Step 1 的變數設定好後，執行以下完整腳本：

```bash
# 確保已設定所有變數後執行
cd ~/cloudrun-deploy && \

# 建立 OTel 配置
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

# 建立/更新 Secret
gcloud secrets describe $SECRET_NAME --project=$PROJECT_ID 2>/dev/null && \
  gcloud secrets versions add $SECRET_NAME --data-file=otel-collector-config.yaml --project=$PROJECT_ID || \
  gcloud secrets create $SECRET_NAME --data-file=otel-collector-config.yaml --replication-policy="automatic" --project=$PROJECT_ID

# 建立 Cloud Run 服務 YAML
cat > cloudrun-service.yaml << EOF
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: $SERVICE_NAME
  annotations:
    run.googleapis.com/launch-stage: BETA
    run.googleapis.com/cpu-throttling: 'false'
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/container-dependencies: "{app:[collector]}"
        run.googleapis.com/secrets: "${SECRET_NAME}:projects/${PROJECT_ID}/secrets/${SECRET_NAME}"
        autoscaling.knative.dev/maxScale: "${MAX_INSTANCES}"
        run.googleapis.com/execution-environment: gen2
        run.googleapis.com/startup-cpu-boost: "true"
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
              value: "$SPRING_PROFILES"
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
        - name: otel-config
          secret:
            secretName: $SECRET_NAME
            items:
              - key: latest
                path: config.yaml
  traffic:
    - percent: 100
      latestRevision: true
EOF

# 部署
gcloud run services replace cloudrun-service.yaml --region=$REGION --project=$PROJECT_ID

echo "✅ 部署完成: $(gcloud run services describe $SERVICE_NAME --region=$REGION --format='value(status.url)')"
```

---

## 常用指令

```bash
# 查看日誌
gcloud run services logs read $SERVICE_NAME --region=$REGION --limit=50

# 查看服務狀態
gcloud run services describe $SERVICE_NAME --region=$REGION

# 更新映像版本
export APP_IMAGE="spike19820318/gate:0.0.3"
# 然後重新執行 Step 6 和 Step 8

# 刪除服務
gcloud run services delete $SERVICE_NAME --region=$REGION --quiet
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
- [OpenTelemetry Collector for Cloud Run](https://docs.cloud.google.com/stackdriver/docs/instrumentation/opentelemetry-collector-cloud-run)
- [Google-built OTel Collector](https://docs.cloud.google.com/stackdriver/docs/instrumentation/google-built-otel)
- [Cloud Run Multi-Container (Sidecar)](https://docs.cloud.google.com/run/docs/deploying#sidecars)
- [Cloud Run Logging](https://docs.cloud.google.com/run/docs/logging)

### OpenTelemetry 官方資源
- [OTel Collector Releases](https://github.com/GoogleCloudPlatform/opentelemetry-operations-collector/releases)
- [Google Cloud Exporter (default_log_name)](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/googlecloudexporter/README.md)
- [OTel Security Best Practices](https://opentelemetry.io/docs/security/config-best-practices/)
