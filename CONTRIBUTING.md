# Contributing

Keep one implementation per concern. Capability detection belongs in CapabilityService, all server requests in Backend, camera binding in CameraPipeline, and audio ownership in the feature lifecycle. Do not add automatically activated demos or invent direction, recognition results, latency or presence.

Use Kotlin and Compose for Android; the backend uses Node built-ins. Add focused tests when changing domain rules, security or persistence. Run the build, unit tests, lint, server integration tests and native audit before a pull request. Include device model/Android version for sensor changes. Test cancellation, permission revocation and background transitions.

Room schemas and server user_version migrations must preserve data. Models need provenance, license review, exact SHA-256, explicit hardware/runtime requirements and verification before use. Keep training samples and media out of issues, logs and fixtures.

Original contributions are licensed Apache-2.0. Do not relicense third-party binaries or models. Track notices and license changes separately.
