# Capability packs

Labs accepts an explicitly chosen trusted HTTPS catalog containing an array of metadata objects:

~~~json
[{
  "id": "approved-model-id",
  "version": "1.0.0",
  "sha256": "EXACT_64_CHARACTER_SHA256_FROM_RELEASE_PIPELINE",
  "downloadSize": 123456,
  "installedSize": 123456,
  "requiredAppVersion": 1,
  "requiredHardware": ["camera"],
  "license": "License identifier and model terms URL",
  "source": "https://your-trusted-host.example/model.bin",
  "runtime": "documented-runtime"
}]
~~~

This is documentation, not an installed or simulated pack. Set real size/hash/license/provenance before publishing a catalog. Current packs are single-file artifacts (no archive extraction); installedSize must equal downloadSize. Required hardware values currently supported are camera, microphone and uwb. Unknown hardware requirements fail closed.

Downloads enforce declared size and SHA-256, delete temporary files on failure/cancel, expose progress and can be retried. verifiedFile rehashes before handing a model to any future runtime. Installing arbitrary bytes does not enable a feature: a reviewed processor/runtime integration is still necessary. Catalog trust is a separate decision from byte integrity; use a pinned publisher and signed release process for production distribution.
