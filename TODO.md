# Ebb migration progress

Updated: 2026-09-13

- [x] Milestones 0–3: Jolt/Ebb/Glimmer baseline, dashboard migration, and lossless structured event-source contract.
- [ ] Milestone 4: migrate upstream SSE acquisition/reading to `flow/with-event-source`; retain pure parser coverage.
- [ ] Milestone 5: structured WebSocket acquisition/reading is implemented; add direct pool/continuation cancellation coverage and complete the proxy cutover.
- [ ] Milestone 6: move proxy streaming and non-streaming request orchestration onto structured Ebb tasks.
- [ ] Milestone 7: remove obsolete `:read`/`:finalize` contract and document final architecture.

## Current working slice

Milestones 4–5: structured SSE and WebSocket acquisition APIs exist. Legacy callback adapters remain temporarily for the Milestone 6 proxy cutover. Next: factor collector consumers to pull Ebb events, then convert streaming and non-streaming proxy handlers and remove adapters.
