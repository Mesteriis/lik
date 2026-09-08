# Optional AiGate integration contract — Task 10

Task 10 implements the opt-in loopback consumer described here. The built-in Compact/Balanced/Extended profiles remain offline and independent of AiGate. Lik stores no provider credentials and does not start AiGate's private service.

The inspected local source is `/Users/avm/projects/Personal/router` (AiGate), commit `c50efb5f5690295ebaccc1fdcd3b139930d404be`. Tracked source was clean during review. Evidence is in `app/src/main/java/com/aigate/router/gateway/GatewayService.kt` (`startChecked`, `/health`, `/v1/models`, `validateApiKey`, image-content detection and auto routing), `gateway/ModelCapabilityManager.kt`, `service/GatewayForegroundService.kt`, and `app/src/main/AndroidManifest.xml`. This is protocol research; no AiGate implementation is copied or built by Lik.

| Boundary | Observed provider contract / Lik requirement |
| --- | --- |
| Address | Same-device `http://127.0.0.1:<port>`. Default 8889; AiGate can select the next available port through default+20. Lik supports configured port and bounded loopback discovery after opt-in; no LAN, hostname, URL or redirect to a non-loopback address. |
| Health | `GET /health`; inspect `service=aigate`, `running`, `port`, version and the router's `models_count` field (`model_count` remains a legacy-compatible fallback). A listener accepting TCP is not a healthy router. Health is not a cryptographic identity proof for the owning Android process. |
| Models | `GET /v1/models` includes enabled model IDs and virtual `auto`. Current response omits vision capabilities. Do not infer image support from an ID, display name or presence in this list. |
| Inference | `POST /v1/chat/completions`, model `auto`, OpenAI-style messages. For images, use explicit `image_url` content with a data URL, alongside the user's text. AiGate can choose an enabled vision-capable route; unsupported/no-vision responses must be surfaced. |
| Authentication | AiGate bypasses API-key checks for loopback clients. Lik stores no upstream provider secret and must not scrape AiGate settings/credentials. Authentication for remote providers is AiGate's responsibility. |
| Lifecycle | AiGate's foreground service is non-exported; there is no public cross-app start-service or ContentProvider discovery API in the inspected manifest. Lik may offer an explicit launch of the AiGate app and reconnect after the user starts it. |

Settings provides a separate EN/RU opt-in, loopback port configuration/discovery, health state and Open AiGate action, visibly separated from on-device profiles. AiGate may forward content to a cloud provider; a loopback first hop does not make the complete operation offline.

No image is sent by enabling the setting, opening a photo, starting indexing or finding the router. Each photo transfer requires a deliberate user action with the selected photo/prompt and router destination visible. Produce a resized, re-encoded image from decoded pixels, stripping EXIF/GPS/filename metadata; never send the original URI, private file path, original file bytes or an unselected photo. Decode samples toward the target size before allocation, then applies orientation and the bounded pixel/payload budget. A successful encode does not authorize an automatic upload.

Transport uses bounded connection/read timeouts plus one deadline for the complete request sequence, including chunked request-body writes; its watchdog disconnects a stalled socket. Response size limits, cancellation and stale-request identity checks apply. A request requires `health.running == true` and a successful `/v1/models` probe before chat. Settings retains and cancels the configured or discovery client on opt-out/destroy. The future viewer action must do the same on media revision and stop; disabling the feature or cancelling a photo request prevents later response publication. Loopback is verified on every redirect/reconnect and cleartext is permitted only for the exact loopback host. A photo POST is never retried automatically after an uncertain delivery outcome. Router errors and response text are untrusted display data.

Router results remain a separate per-photo response. They do not silently enter local embeddings, OCR text, face identities, tags or search indexes; saving any such result is a separate explicit action. Router failure must leave all built-in profile settings, indexes and local photo access operational.

API 37 instrumentation covers a local fake HTTP server for health/models/chat/error/timeout/cancellation/redirect and the one-use photo token/no-URI payload boundary. A real configured AiGate/cloud-provider run remains separate acceptance. Check that metadata is absent from the actual encoded image and no request occurs on opt-in/discovery alone. Neither the fake-server test nor protocol inspection counts as a real cloud-provider acceptance run. No real AiGate or cloud-provider transfer was run in Task 10.

## Sensitive-photo constraint added during Task 9

The Task 13 visibility policy in [SENSITIVE_MEDIA.md](SENSITIVE_MEDIA.md) applies before router access. Unclassified photos remain quarantined. Hidden/sensitive photos require a current in-memory BIOMETRIC_STRONG reveal plus a separate per-photo send action; neither enabling AiGate nor authenticating reveal alone authorizes transfer. Re-lock cancels pending transfers and removes transient resized image buffers.
