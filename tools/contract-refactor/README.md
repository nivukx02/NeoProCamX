# Contract-Locked Refactoring Engine 2.0

This engine reduces oversized working files without authorizing changes to camera quality, recording
transport, codecs, resolution, bit depth, metadata, or unrelated dirty-worktree files.

## High-end safeguards

1. **Local dependency graph** maps Kotlin/Java imports and native includes, then ranks extraction
   candidates by size, function density, branch signals, fan-in, and outgoing dependencies.
2. **Quality fingerprints** SHA-256 lock declared camera, encoder, MOV, NPV10, and quality-policy files.
3. **Baseline-first execution** runs preflight tests before an automated mutation is permitted.
4. **Bounded transactions** limit changed, added, and removed files to the manifest's declared budget.
5. **Measurable reduction budgets** require the host file to shrink by a minimum amount and keep new
   coordinators below a maximum size.
6. **Behavior contracts** retain required quality/orientation symbols and forbid downgrade controls in
   extracted modules.
7. **Automatic rollback** restores declared mutable files and any attempted changes to declared
   quality-protected files; it never resets Git or touches unrelated user changes.
8. **Audit journals** record manifest hash, checkpoint, timings, gates, rollback, and final status as JSON.
9. **Fast checkpoints** hash the complete source boundary but copy only mutable transaction files.

## Commands

```powershell
# Build the source inventory, dependency graph, ranked candidates, and extraction plan.
.\tools\contract-refactor\contract-refactor.ps1 -Mode Analyze

# Validate engine invariants without building the app.
.\tools\contract-refactor\contract-refactor.ps1 -Mode SelfTest

# Prove a synthetic failed mutation restores its host and preserves its protected file.
.\tools\contract-refactor\test-scoped-rollback.ps1

# Save a scoped checkpoint before a manual extraction.
.\tools\contract-refactor\contract-refactor.ps1 -Mode Checkpoint

# Verify scope, fingerprints, reduction, contracts, tests, APK, and lint.
.\tools\contract-refactor\contract-refactor.ps1 -Mode Verify `
  -Checkpoint .contract-refactor/checkpoints/<checkpoint-id>

# Preflight, mutate, verify, and automatically roll back on any failure.
.\tools\contract-refactor\contract-refactor.ps1 -Mode Run `
  -MutationScript tools/contract-refactor/mutations/<bounded-mutation>.ps1
```

Reports are written to `build/contract-refactor/`; transaction snapshots stay under
`.contract-refactor/checkpoints/`.
