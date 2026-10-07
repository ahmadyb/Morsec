# Milestone 4 Part A — LAN discovery and control-session foundation

This document records the bounded discovery/session foundation only. It does **not**
announce a usable file-sharing feature and does not advance `FeatureReadiness`.
`FeatureReadiness.CURRENT_MILESTONE` remains `2`; `TRANSFER_ENGINE` and `LAN_TRANSPORT`
remain gated. The full acceptance boundary and explicit exclusions are recorded in
[`AI_HANDOFF.md`](AI_HANDOFF.md).

## Scope and module boundary

- `:core-transfer` defines transport-neutral discovery/session contracts, typed safe
  failures, an injected monotonic-clock seam, a deterministic bounded peer registry, a
  versioned capability handshake, and a fail-closed payload gate. It remains pure JVM:
  no Android types, sockets, system clock, coroutines, or crypto provider.
- `:transport-lan` implements IPv4 multicast discovery and the selected-peer TCP control
  handshake. Hilt binds `PeerDiscoveryProvider` to `LanPeerDiscoveryProvider`; constructing
  or injecting it performs no bind, permission prompt, thread start, or lock acquisition.
  Only an explicit `PeerDiscoveryProvider.start()` opens resources.
- The app has no discovery call site, discovery screen, notification, service, or worker in
  this part. Production DI wiring makes the provider available without starting it.

This is a control-only foundation. `ControlSession` exposes negotiated metadata and
`close()` only—there is no file, stream, chunk, or payload send API in Part A.

## UDP discovery wire format

The transport sends versioned multicast beacons to `239.255.33.45:33457` with TTL 1,
using the fixed discovery port as the source port. It selects an available Wi-Fi or
Ethernet interface; it does not scan Wi-Fi networks or read SSIDs. Beacons repeat every
1,200 ms while an explicit lease is active.

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 4 | magic `MSD1` |
| 4 | 1 | wire version (`1`) |
| 5 | 1 | message type (`1`, beacon) |
| 6 | 2 | payload length, including the trailing CRC32 |
| 8 | 16 | ephemeral peer-instance id, binary |
| 24 | 1 + n | app-version byte length and bounded ASCII app version (1–32 bytes) |
| … | 1 + n | display-name byte length and strict UTF-8 (1–96 bytes) |
| … | 2 + 2 | minimum and maximum protocol revisions, unsigned big-endian |
| … | 1 | supported transport bit mask (LAN bit 0, Nearby bit 1) |
| … | 2 | known `SessionFeature` bit mask, unsigned big-endian |
| … | 4 | maximum chunk-size metadata, unsigned big-endian |
| … | 1 | resume-supported boolean (`0` or `1`) |
| … | 1 | encryption capability (`0` = none) |
| final | 4 | CRC-32/ISO-HDLC over every preceding datagram byte |

The complete datagram is capped at 192 bytes. The decoder checks the datagram bound,
magic, version, type, declared length and CRC before constructing bounded profile values;
unknown bits, invalid UTF-8, inconsistent capabilities, bad booleans and trailing bytes
are rejected. `LanBeaconCodec` and tests pin the layout.

CRC32 detects accidental corruption only. It is not a MAC, signature, identity proof, or
protection against a hostile peer. A beacon's display name, app version, feature bits,
ephemeral id, source IP and CRC are all untrusted.

## Selected-peer TCP control handshake

TCP listens on `NetworkPorts.PEER_CONTROL` (`33456`). A caller must choose a fresh
`PeerInstanceId` from its current lease and invoke `connectSelected`; the transport does
not auto-connect to a discovery result. The outbound socket is bound to the same Android
`Network` that carried the selected beacon and connects to its observed numeric IPv4 source
address (no DNS lookup). A bounded connect/read timeout applies to each attempt. Incoming
connections are accepted only while the explicit lease is active and must present an
identity/profile observed from the same IPv4 address, with the hello addressed to the
current local ephemeral id. Network changes clear stale routes and peer candidates. These
consistency checks are not authentication.

The control envelope is independent of the existing 44-byte file-transfer frame format:

| Header offset | Size | Field |
| --- | ---: | --- |
| 0 | 4 | magic `MSH1` |
| 4 | 1 | envelope version (`1`) |
| 5 | 1 | type: hello `1`, accept `2`, reject `3` |
| 6 | 2 | payload size, unsigned big-endian |

The full frame is limited to 512 bytes. The receiver validates the fixed 8-byte header and
length before allocating the payload. Hello carries a bounded attempt id, sender and target
ephemeral ids, and a bounded profile. Accept echoes the attempt/ids, includes the responder
profile, selected protocol revision and the negotiated capabilities. Reject carries the
same addressing fields and a stable numeric rejection code. No peer-supplied free-form text
is reflected in a failure.

Both sides verify the selected target id, sender id, attempt id and negotiated range. The
initiator validates that the accept sender matches the peer the user selected. The
responder also requires a current discovery candidate from the same IPv4 address. These
checks detect stale/mismatched discovery records; they do **not** authenticate the device.
An on-path or same-LAN attacker can spoof/replace discovery and control messages.

This revision has no key exchange, signatures, pairing secret, certificate pin, secure
channel, or encryption. `EncryptionCapability.NONE` is the only supported value. The
negotiator forcibly removes `FILE_PAYLOAD`, `RESUME`, and `SECURE_SESSION`, even if a remote
profile advertises them. Every `NegotiatedSession` is explicitly
`ControlOnlyUnauthenticated`. `PayloadTransferGate` returns
`SECURE_SESSION_REQUIRED`; there is no Part A path that can issue the internal
`ApprovedSecureSession` evidence. Do not treat TCP, a matching ephemeral id, or CRC32 as
security.

## Lease lifecycle, bounds, and ownership

`LanPeerDiscoveryProvider` construction is inert. `start()` is explicit and lease-scoped:

1. Validate a bounded display name/version and a control-only LAN capability profile.
2. Acquire the Wi-Fi multicast lock and bind the fixed TCP control listener.
3. Register a connectivity callback for Wi-Fi/Ethernet networks (including local-only
   networks without Internet capability).
4. Bind one bounded multicast socket to the chosen network/interface and publish/receive
   beacons. Without a suitable network, the lease remains `WAITING_FOR_NETWORK`.
5. Stop at five minutes even if its owner forgets to close it; all explicit close/failure
   paths are idempotent and release sockets, lock, network callback, operation sockets and
   worker queues.

The lease owns the entire lifetime. Cancelling an outbound operation closes its in-flight
socket. Closing a lease cancels in-flight attempts and closes established control sockets.
No service, notification, wake lock, WorkManager task, startup discovery, or automatic
retry exists.

| Resource or work | Bound |
| --- | ---: |
| Beacon datagram | 192 bytes maximum (receive buffer is 193 bytes to detect truncation) |
| Peer registry | 32 entries; stale at 4,800 ms by injected monotonic time |
| Peer ordering | lowercase display name, then ephemeral id; deterministic oldest-first eviction with id tie-break |
| Active discovery lease | 300,000 ms maximum |
| Control sessions + in-flight handshakes | 4 total per lease |
| Control executor | 4 workers and a 4-item bounded queue |
| Listener callbacks | 1 worker and a 32-item bounded queue; overload increments a drop counter |
| TCP connect and handshake read | 5,000 ms per operation |
| UDP receive poll | 250 ms, allowing prompt cancellation/network rebinding |

The registry receives time only through `MonotonicClock`; it reads neither wall-clock time nor
the Android clock itself. The Android adapter supplies `SystemClock.elapsedRealtime()`.
Out-of-order time is rejected by the core registry. Diagnostics contain only phase,
aggregate counters, peer count and the last stable failure code—never packet bytes, IP
addresses, peer names, raw socket exceptions or exception messages.

## Permissions and readiness

The `:transport-lan` manifest owns install-time `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`. These do not produce a runtime
permission dialog. `PermissionMatrix.lanDiscovery()` is empty for API 23 through current
Android; LAN discovery does not scan Wi-Fi or read SSIDs. Nearby's separate future
permission matrix is unchanged. The lease does not request location, change Wi-Fi state,
acquire a Wi-Fi lock, or hold a wake lock.

The DI binding and module source do not make `LAN_TRANSPORT` available. `CURRENT_MILESTONE`
remains `2`; `TRANSFER_ENGINE` remains unavailable; Room database v1/v2 and their exports
remain untouched. This Part A foundation is not permission to ship discovery UI or transfer
payloads.

## Tests and verification

Pure JVM tests cover deterministic registry ordering/expiry/capacity, handshake round trips,
malformed and oversized envelopes, selected-peer identity mismatch, capability intersection,
and payload refusal. `:transport-lan` codec tests cover UTF-8, lengths, unknown types,
versions and CRC rejection. Permission-matrix tests cover API 23–36 and prove LAN requests
no runtime permission. Android compilation, Hilt/KSP, lint, APK packaging, and full-suite
results are reported only from exact-SHA hosted CI in the current handoff.