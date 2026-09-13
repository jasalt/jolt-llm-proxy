# Ebb migration progress

Updated: 2026-09-13

- [x] Milestones 0–3: Jolt/Ebb/Glimmer baseline, dashboard migration, and lossless structured event-source contract.
- [ ] Milestone 4: migrate upstream SSE acquisition/reading to `flow/with-event-source`; retain pure parser coverage.
- [ ] Milestone 5: migrate pooled WebSocket lifetime to structured ownership; preserve continuation and fallback semantics.
- [ ] Milestone 6: move proxy streaming and non-streaming request orchestration onto structured Ebb tasks.
- [ ] Milestone 7: remove obsolete `:read`/`:finalize` contract and document final architecture.

## Current working slice

Milestone 4: structured SSE acquisition is implemented and tested for normal ordered EOF completion. The legacy callback adapter remains temporarily for the Milestone 6 proxy cutover. Next: add focused close/cancellation, parser-failure, retry, and slow-consumer source tests before converting WebSocket ownership.
