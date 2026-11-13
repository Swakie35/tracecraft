# Tracecraft

[![CI](https://github.com/Swakie35/tracecraft/actions/workflows/ci.yml/badge.svg)](https://github.com/Swakie35/tracecraft/actions/workflows/ci.yml)

**A bounded telemetry collector that makes overload measurable.**

Tracecraft explores a common backend trade-off: doing expensive metrics aggregation on the request thread increases latency, but an unbounded background queue can exhaust memory. This Java 21 service places a fixed-size queue between the ingestion endpoint and a virtual-thread consumer, and reports both processed events and dropped events.

## Features
- Java 21 record-based event validation and bounded `ArrayBlockingQueue`
- Async consumer with per-service rolling latency windows (p50/p95/p99), failure counts
- Explicit backpressure behaviour (`HTTP 429` when the queue is full)
- Health/ingest/metrics HTTP endpoints and end-to-end smoke tests
- Java standard library only; no broker or external deployment required

## Run
```bash
./run-tests.sh
java -cp build/classes dev.swaraj.tracecraft.Server 8081
```
```bash
curl -X POST 'http://127.0.0.1:8081/ingest?service=checkout&latencyMs=120&success=true'
curl 'http://127.0.0.1:8081/metrics'
```

## Operational notes
This prototype reports exact percentiles over a **bounded recent sample**, not globally accurate historical percentiles. It deliberately drops traffic on overload instead of blocking request threads; a real system would choose a loss policy by event criticality, implement durable spooling where needed, expose OpenTelemetry/Prometheus exporters, enforce auth/TLS and apply stronger resource controls. Data exists only in memory.

## Container build
`docker build -t tracecraft .` then `docker run --rm -p 8081:8081 tracecraft`. The local default binds to 127.0.0.1; the Docker image explicitly sets `BIND_HOST=0.0.0.0` for container networking. Do not expose an unauthenticated demo on a public port.
