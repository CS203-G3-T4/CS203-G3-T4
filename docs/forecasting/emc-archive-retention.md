# EMC snapshot archive retention decision

Decision recorded 2 November 2026 for PR48. Applies to the private
`archive/<snapshot-sha256>.json` beside the collector's configured `--output`
file, plus private copies of those receipts. It does not grant collection,
storage or redistribution permission; the actual agreement controls those uses.
See [permission record](emc-collection-permission.md).

## Current hold and normal retention

- Keep **all existing EMC snapshot archives on evidence hold** while the solar
  trial, registered demand-candidate experiment, frozen model provenance or
  their final review still depends on them. This includes first receipts,
  incomplete/malformed source responses retained as evidence, boundary periods
  and required feature-history inputs. A file is not disposable because the
  current price-only model no longer uses solar.
- Outside a hold, routine receipts have a **30-day working retention target**.
  Age alone never authorises deletion. A receipt must first be demonstrably
  unreferenced by every model/input digest, manifest, registered cohort,
  pending outcome, training attempt and reviewer evidence record.
- Release a hold only after the last dependent trial/experiment has closed,
  pending outcomes are resolved or documented, the final evidence is privately
  backed up and verified, and the owner records the release. Keep the released
  evidence for **90 further days**, or through the applicable project assessment
  and review period if later, subject to the agreement's permitted duration.
- Agreement-imposed storage/deletion restrictions take precedence. Its terms
  and expiry have not been inspected by this work. The owner must resolve any
  conflict before continuing retention or making a deletion decision; no expiry
  or storage permission is inferred from public page access.

The active solar and price-only trial windows end in late November 2026; those
dates are not automatic archive-deletion dates. Registered cohort records and
model manifests determine the actual dependencies. The current price-only
trial itself does not need solar, but concurrent historical/issued-demand
work can still depend on the same receipts.

## Safe release and storage procedure

1. Inventory the archive privately: snapshot hash, exact response/receipt times,
   bytes and all known references/holds. Include uncertainty rather than assuming
   an unrecognised reference is absent. Do not use file modification time as a
   replacement for source or receipt time.
2. Keep original receipt-stamped bytes immutable. Equal market values with
   different `receivedAt` times are different availability evidence, not redundant
   history. Do not normalise timestamps or deduplicate away first receipts.
3. Keep owner-only local permissions and a private verified backup. Any future
   cold-archive compression must preserve original uncompressed SHA256 identities
   and an explicit recovery manifest; it must not break currently referenced paths.
4. Produce a dry-run list containing only verified unreferenced, expired files.
   Reconcile all references and test recovery before any separately authorised
   deletion. Unknown references, active holds or an unclear agreement block cleanup.
5. Review counts/bytes and available disk space weekly while collection runs.
   If capacity is insufficient, arrange approved storage or an explicit collection
   decision with the owner. Do not silently drop held receipts or fabricate gaps.

**Implementation boundary:** PR48's collector currently archives every successful
receipt; it has no automatic retention cleanup. This change documents the missing
decision and procedure. No file is deleted, no collector or schedule is changed,
and no cleanup timer is installed or claimed to be active. Retention bookkeeping
and source receipts stay outside Git; share only the policy and non-sensitive
aggregate evidence. The actual permission agreement remains a private team record.
