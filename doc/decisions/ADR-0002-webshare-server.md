# ADR-0002 — The embedded WebShare server is a pure-JVM module on `java.net`

- **Status:** accepted (2026-09-29)
- **Applies to:** `:webshare-server` (module), `:core-model` (`WebShareSession`, `BrowserSession`, `WebTransferRecord`, `NetworkPorts`), `:core-data` (`WebShareRepository`), `:app` (service lifetime, asset hosting)
- **Delivery:** contracts and persistence are in place at Milestone 1; the server itself is Milestone 11 and the TypeScript browser client Milestone 12 (`core-model/FeatureReadiness.kt` gates both, and the UI refuses to claim otherwise)

## Context

The master prompt (§8, §12) requires a receiving path that needs **no app on the other
device**: the phone runs an embedded HTTP server, the peer opens `http://<phone>:33455`
in any browser, pairs with a code, and downloads/uploads over the LAN. That imposes four
hard constraints:

1. **It must run on API 23.** A server library that quietly requires `java.time`,
   `java.util.Base64`, `java.nio.file` or `InputStream.transferTo` compiles fine — those
   are JDK 8/9 APIs and the modules build against JDK 17 — and then throws
   `NoClassDefFoundError` at runtime on an Android 6 phone. This is the single biggest
   risk in the feature.
2. **No third-party HTTP stack.** Every candidate (NanoHTTPD, Ktor server, an OkHttp-based
   mock server) adds a dependency whose own API floor, threading model and transitive
   footprint this project would have to audit for API 23 anyway.
3. **It must be testable without a phone.** Session handshakes, `Range` requests, chunked
   uploads and token expiry are exactly the kind of logic that needs deterministic tests.
4. **It must survive the process being killed.** Downloads resume; browser sessions and
   partial uploads are persisted, and only a token *digest* is ever stored.

## Decision

`:webshare-server` is a **Kotlin/JVM module** that speaks HTTP/1.1 over
`java.net.ServerSocket` and `java.io` streams, with coroutines for concurrency and
`kotlinx.serialization` for the JSON API. Android-specific concerns are injected through
small interfaces implemented in `:app`.

**HTTP surface**

- `ServerSocket` accept loop → one coroutine per connection on a bounded dispatcher;
  keep-alive with an idle timeout, `Connection: close` honoured.
- Response bodies streamed from `FileInputStream` in a fixed buffer with a hand-rolled
  copy loop — never `InputStream.transferTo` (Java 9).
- `Range: bytes=` support so `<video>`/`<audio>` in the browser can seek (Media3 on the
  phone side does the same for received files).
- Chunked transfer encoding for uploads whose length the browser does not send, plus
  `multipart/form-data` parsing for the browser→phone direction; uploads land in the
  app's private `incoming/` directory and are verified before they are published.
- GZIP for text/JSON responses via `java.util.zip.GZIPOutputStream`.

**API 23 allowlist (the proof, not a hope)**

Only APIs that have existed since API 1 are permitted inside the module:
`java.net.ServerSocket`, `java.net.Socket`, `java.net.SocketTimeoutException`,
`java.net.URLDecoder`/`URLEncoder`, `java.io.*` streams and `File`, `java.util.zip.*`,
`java.security.MessageDigest`, `java.util.Locale`, `java.util.concurrent.atomic.*`.
Explicitly forbidden: `java.time.*` (API 26), `java.util.Base64` (API 26),
`java.nio.file.*` (API 26), `InputStream.transferTo`/`Reader.transferTo` (Java 9),
`java.net.InetSocketAddress.getHostString` variants added later, and any `javax.net.ssl`
configuration that assumes TLS 1.3 (API 29). HTTP dates (RFC 7231 IMF-fixdate) are
formatted from epoch millis with a `SimpleDateFormat` pinned to `Locale.US` and GMT;
base64, where a payload needs it, is a small pure-Kotlin codec in `:core-model`.

Because the compiler cannot enforce this (JVM modules compile against JDK 17), the
allowlist is enforced two ways: `:app`'s lint `NewApi` check stays enabled and covers
everything reachable from the app module, and the server's own JVM tests run the real
socket paths — a forbidden API shows up as a test that cannot be written against the
module's public surface.

**Android boundary (SPIs implemented in `:app`)**

- `AssetSource` — serves the compiled `webshare-ui` bundle from the APK's assets, so the
  browser client ships inside the app and needs no network fetch.
- `ServerHostPorts` — resolves the bind address and the user-selected port
  (`NetworkPorts.WEBSHARE_HTTP` = 33455 default, `USER_PORT_RANGE` = 1024..65535), and
  reports a port conflict to the Connection Doctor instead of crashing.
- Lifetime is owned by a foreground service in `:app` (Milestone 8's service
  infrastructure), never by the module itself.

**Rejected alternatives**

| Option | Why rejected |
| --- | --- |
| NanoHTTPD | Extra dependency to audit for API 23; its own thread-per-connection model and `TempFileManager` assumptions collide with scoped storage |
| Ktor server (CIO) | Pulls `kotlinx-io` + a coroutine engine whose Android floor and APK cost are not justified by a single-purpose LAN server |
| `MockWebServer` / OkHttp server | Test-scoped tooling, not a production server |
| Serving from a `WebView` bridge | No browser on the peer device — defeats the entire requirement |
| Implementing inside `:app` | Untestable without instrumentation; the API 23 argument becomes unauditable |

## Consequences

- The whole server can be integration-tested on the JVM with real sockets, real files and
  a real clock injection — the same code that runs on the phone.
- No new runtime dependency, so no new API-23 audit surface and no APK growth beyond our
  own code.
- Everything HTTP has to be written by hand: request-line and header parsing, chunked
  decoding, multipart boundaries, `Range` arithmetic, keep-alive and timeout handling.
  That work is the milestone, and it is covered by tests rather than by a library's
  reputation.
- No TLS. The server binds to the LAN interface for the duration of a session, is
  reachable only while the foreground service runs, and every request must carry a
  session token whose digest — never the token — is persisted. This is recorded here as an
  explicit product decision, not an oversight.
