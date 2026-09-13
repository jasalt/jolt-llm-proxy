# Ebb migration progress

Updated: 2026-09-13

- [x] Milestones 0–3: Jolt/Ebb/Glimmer baseline, dashboard migration, and lossless structured event-source contract.
- [x] Milestone 4: SSE acquisition/reading uses `flow/with-event-source`; pure parser coverage remains.
- [x] Milestone 5: WebSocket acquisition/reading has structured ownership and continuation-safe release.
- [x] Milestone 6: proxy streaming and non-streaming orchestration uses structured Ebb tasks.
- [x] Milestone 7: final architecture documented; obsolete source contract removed and deterministic verification complete.
- [ ] External verification: credentialed live SSE/WebSocket continuation and mid-stream client-abort checks require a user-provided authenticated environment.

## Current working slice

Implementation milestones are complete. Final deterministic checks: full tests, LSP, flake evaluation, standalone AOT build, namespace load, and diff check. Credentialed live transport checks remain explicitly external.
