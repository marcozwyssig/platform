# src/java

Java modules of the shared core land here in Phase 2 (mirrors netctl's `src/java` layout):
`kernel`, `consensus`, `persistence`, `security`, `sidecar-grpc`, `web-ui`, `telemetry` (Maven group
`info.zwyssig.platform.*`). Consumed by the products via a Gradle composite build
(`includeBuild("../platform")`) in dev, published artefacts later. Empty for now (Phase 0/1 is
Python-only, under `src/python`).
