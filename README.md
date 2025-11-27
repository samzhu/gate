# Gate - Enterprise LLM API Gateway

An enterprise-grade LLM API Gateway service designed for Claude Code CLI and other Anthropic Claude API clients, providing proxy, authentication, usage tracking, and observability capabilities.

## Overview

**Gate** acts as an intermediary layer between your organization and Anthropic's Claude API, offering:

- **Unified Access Control**: OAuth2 authentication with JWKS validation
- **Token Usage Tracking**: Capture input/output tokens for every API call, published via CloudEvents to message queues
- **API Key Rotation**: Multiple Anthropic API keys with Round Robin load distribution
- **Full Observability**: OpenTelemetry integration for distributed tracing and metrics
- **Resilience**: Circuit breaker pattern to prevent cascade failures
- **Streaming Support**: Full SSE streaming support for Claude API responses

## Tech Stack

| Component | Technology |
|-----------|------------|
| Framework | Spring Boot 4.0.0 |
| Gateway | Spring Cloud Gateway Server Web MVC |
| Language | Java 25 |
| Runtime | GraalVM Native Image |
| Security | OAuth2 Resource Server (JWKS) |
| Messaging | Spring Cloud Stream (RabbitMQ / GCP Pub/Sub) |
| Resilience | Resilience4j Circuit Breaker |
| Observability | OpenTelemetry, Micrometer |
| Event Format | CloudEvents v1.0 |

## Architecture

```
┌─────────────┐     ┌─────────────────────────────────┐     ┌─────────────────┐
│ Claude Code │     │           Gate                  │     │  Anthropic API  │
│    CLI      │────▶│  ┌──────────────────────────┐   │────▶│                 │
│             │     │  │ 1. OAuth2 JWT Validation │   │     │ api.anthropic.  │
└─────────────┘     │  │ 2. API Key Injection     │   │     │     com         │
                    │  │ 3. Proxy Request         │   │     └─────────────────┘
                    │  │ 4. Extract Token Usage   │   │
                    │  │ 5. Publish Usage Event   │   │
                    │  └──────────────────────────┘   │
                    └───────────────┬─────────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────────┐
                    │     Message Queue                 │
                    │  (RabbitMQ / GCP Pub/Sub)         │
                    │                                   │
                    │   Topic: llm-gateway-usage        │
                    │   Format: CloudEvents v1.0        │
                    └───────────────────────────────────┘
```

## Features

### API Proxy

- **Endpoint**: `POST /v1/messages`
- **Streaming**: Full SSE streaming support (~99% of Claude Code traffic)
- **Non-streaming**: JSON response support
- **Transparent proxy**: Maintains Anthropic API compatibility

### Authentication

- OAuth2 Resource Server with JWKS validation
- JWT `sub` claim used for user identification in usage tracking
- No issuer or audience validation required - only signature verification

### API Key Management

- Multiple Anthropic API keys support
- Round Robin rotation strategy
- Key alias for tracking without exposing actual keys
- Thread-safe key selection with AtomicInteger

### Usage Tracking

Captures token usage from every request and publishes CloudEvents:

```json
{
  "specversion": "1.0",
  "id": "4c71578c899ae6249e5b70d07900fc93",
  "type": "io.github.samzhu.gate.usage.v1",
  "source": "/gate/messages",
  "subject": "user-uuid-12345",
  "time": "2025-11-26T10:30:00.000Z",
  "datacontenttype": "application/json",
  "data": {
    "model": "claude-sonnet-4-5-20250929",
    "message_id": "msg_016pGU1jGmczbq7p4JTfAqmn",
    "input_tokens": 30,
    "output_tokens": 148,
    "cache_creation_tokens": 0,
    "cache_read_tokens": 0,
    "total_tokens": 178,
    "latency_ms": 7257,
    "stream": true,
    "stop_reason": "end_turn",
    "status": "success",
    "key_alias": "primary",
    "trace_id": "4c71578c899ae6249e5b70d07900fc93",
    "anthropic_request_id": "req_018EeWyXxfu5pfWkrYcMdjWG"
  }
}
```

### Observability

| Signal | Local Development | GCP Production |
|--------|-------------------|----------------|
| Traces | Tempo | Cloud Trace |
| Metrics | Prometheus | Cloud Monitoring |
| Logs | Loki | Cloud Logging |

Automatic Docker Compose integration detects `grafana/otel-lgtm` and configures OTLP exporters.

### Circuit Breaker

- Failure rate threshold: 50%
- Slow call threshold: 100% (>30s)
- Open state duration: 60s
- Half-open permitted calls: 10

## Quick Start

### Prerequisites

- Java 25
- Docker & Docker Compose
- Anthropic API Key(s)
- OAuth2 Authorization Server with JWKS endpoint

### Local Development

1. **Clone and setup**:
   ```bash
   git clone https://github.com/samzhu/gate.git
   cd gate
   ```

2. **Configure secrets**:
   ```bash
   cp config/application-secrets.yaml.example config/application-secrets.yaml
   # Edit config/application-secrets.yaml with your actual values
   ```

3. **Start observability stack**:
   ```bash
   docker compose up -d
   ```

4. **Run the application**:
   ```bash
   ./gradlew bootRun
   ```

5. **Access**:
   - Gateway: http://localhost:8080
   - Grafana: http://localhost:3000

### Configure Claude Code CLI

```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "https://your-gate-instance.com",
    "ANTHROPIC_AUTH_TOKEN": "your-jwt-token"
  }
}
```

## Configuration

### Profile Architecture

Gate uses a multi-dimensional profile design:

```
gate/
├── src/main/resources/           # Packaged in Docker Image
│   ├── application.yaml          # Base shared configuration
│   ├── application-local.yaml    # Infrastructure: Local (RabbitMQ)
│   └── application-gcp.yaml      # Infrastructure: GCP (Pub/Sub)
│
└── config/                       # External config (K8s ConfigMap)
    ├── application-lab.yaml      # Environment: LAB
    ├── application-sit.yaml      # Environment: SIT
    ├── application-uat.yaml      # Environment: UAT
    ├── application-prod.yaml     # Environment: PROD
    └── application-secrets.yaml  # Local secrets (gitignore)
```

**Activation Examples**:
```bash
# Local development (default)
SPRING_PROFILES_ACTIVE=local

# Local + LAB environment values
SPRING_PROFILES_ACTIVE=local,lab

# GCP + PROD environment
SPRING_PROFILES_ACTIVE=gcp,prod
```

### Key Configuration Properties

**application.yaml**:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://your-auth-server/.well-known/jwks.json

anthropic:
  api:
    base-url: https://api.anthropic.com
    keys:
      - alias: "primary"
        value: ${ANTHROPIC_KEY_PRIMARY:}
      - alias: "secondary"
        value: ${ANTHROPIC_KEY_SECONDARY:}
```

### Environment Variables

| Variable | Description | Convention |
|----------|-------------|------------|
| `spring.profiles.active` | Active profiles | Spring property |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | JWKS endpoint | Spring property |
| `anthropic.api.keys[0].value` | API key value | Spring property |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint | OTel standard |

## API Reference

### Messages API

**Endpoint**: `POST /v1/messages`

**Headers**:
| Header | Required | Description |
|--------|----------|-------------|
| `Authorization` | Yes | Bearer JWT token |
| `Content-Type` | Yes | `application/json` |

**Request Body**: Same as [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create)

**Response**: Proxied from Anthropic API (streaming or non-streaming)

### Health Check

**Endpoint**: `GET /actuator/health`

**Response**:
```json
{
  "status": "UP",
  "components": {
    "circuitBreaker": {"status": "UP", "details": {"state": "CLOSED"}},
    "pubsub": {"status": "UP"},
    "apiKeys": {"status": "UP", "details": {"count": 2}}
  }
}
```

### Error Responses

Errors follow Anthropic API format for client compatibility:

```json
{
  "type": "error",
  "error": {
    "type": "authentication_error",
    "message": "Invalid or expired access token"
  }
}
```

| Error Type | HTTP Status | Description |
|------------|-------------|-------------|
| `authentication_error` | 401 | Invalid/expired JWT |
| `overloaded_error` | 503 | Circuit breaker open |
| `api_error` | 502 | Upstream error |

## Deployment

### Docker

```dockerfile
FROM eclipse-temurin:25-jre-alpine
COPY build/libs/gate.jar /app/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gate.jar"]
```

### GraalVM Native

```bash
./gradlew nativeCompile
# Output: build/native/nativeCompile/gate
```

Benefits:
- Startup time < 100ms
- 70% reduced memory footprint
- Ideal for Kubernetes/Cloud Run

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gate
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: gate
        image: gate:latest
        ports:
        - containerPort: 8080
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
        env:
        - name: spring.profiles.active
          value: "gcp,prod"
        - name: spring.security.oauth2.resourceserver.jwt.jwk-set-uri
          value: "https://your-auth-server/oauth2/jwks"
        - name: anthropic.api.keys[0].alias
          value: "primary"
        - name: anthropic.api.keys[0].value
          valueFrom:
            secretKeyRef:
              name: anthropic-secrets
              key: api-key-primary
```

### Cloud Run with OTel Sidecar

```yaml
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: gate
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
```

## Observability

### Local Development

Start the Grafana LGTM stack:

```bash
docker compose up -d
```

Access Grafana at http://localhost:3000

**Query Examples**:

- **Traces (Tempo)**: `{resource.service.name="gate"}`
- **Logs (Loki)**: `{service_name="gate"} | json | trace_id != ""`
- **Metrics (Prometheus)**: `rate(http_server_requests_seconds_count{application="gate"}[5m])`

### Trace ID Design

| ID Type | Format | Source | Purpose |
|---------|--------|--------|---------|
| `traceId` | 32 hex chars | OpenTelemetry | End-to-end request tracing |
| `messageId` | `msg_xxx` | Anthropic response body | Message identification |
| `anthropicRequestId` | `req_xxx` | Anthropic response header | Report issues to Anthropic |

### Troubleshooting Guide

| Issue Type | Use Field | Where to Query |
|------------|-----------|----------------|
| Gateway internal | `trace_id` | Cloud Trace, application logs |
| Anthropic API issue | `anthropic_request_id` | Contact Anthropic support |
| User-specific issue | `trace_id` + `subject` | Usage event data (BigQuery) |

## Development

### Build

```bash
# Compile
./gradlew compileJava

# Build JAR
./gradlew build

# Build native image
./gradlew nativeCompile

# Run tests
./gradlew test
```

### Project Structure

```
src/main/java/io/github/samzhu/gate/
├── GateApplication.java
├── config/
│   ├── AnthropicProperties.java
│   ├── GatewayConfig.java
│   ├── SecurityConfig.java
│   └── Resilience4jConfig.java
├── filter/
│   ├── ApiKeyInjectionFilter.java
│   └── RequestLoggingFilter.java
├── handler/
│   ├── StreamingProxyHandler.java
│   └── NonStreamingProxyHandler.java
├── service/
│   ├── ApiKeyRotationService.java
│   └── UsageEventPublisher.java
├── model/
│   ├── UsageEventData.java
│   └── StreamEvent.java
├── util/
│   ├── SseParser.java
│   └── TokenExtractor.java
└── exception/
    └── GlobalExceptionHandler.java
```

## Performance

| Metric | Target |
|--------|--------|
| Proxy latency overhead | < 50ms |
| Throughput | 1000 RPS |
| Streaming first byte | < 100ms |
| Memory footprint | < 512MB |

## Security Considerations

- API keys stored in Kubernetes Secrets or Cloud Secret Manager
- Keys injected via environment variables, never in config files
- API keys automatically redacted in logs
- Request/response content not logged (only metadata)
- GDPR compliant - minimal data collection

## Related Projects

- **Gate-CLI**: Client CLI tool for OAuth2 login and Claude Code configuration (separate project)

## References

- [Spring Cloud Gateway Server Web MVC](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html)
- [Spring Cloud GCP Pub/Sub](https://googlecloudplatform.github.io/spring-cloud-gcp/7.4.1/reference/html/index.html#spring-cloud-stream)
- [CloudEvents Specification](https://cloudevents.io/)
- [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [Anthropic Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [Claude Code Configuration](https://code.claude.com/docs/en/network-config)

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
