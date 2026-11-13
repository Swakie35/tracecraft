# Design notes — tracecraft

**Why this exists.** A bounded telemetry pipeline with async ingestion, latency summaries and an HTTP metrics endpoint.

The default implementation is intentionally self-contained so reviewers can run it without private services, paid accounts or unpublished data. The README names its limitations; this project is an engineering reference, not a claim about production deployment.
