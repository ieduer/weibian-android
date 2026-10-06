## 2026-10-06 — Immutable content staged for v1.2.0

Exact content `0b3170748035504b` (899729 bytes) is now staged at its immutable R2 URL and public full-byte readback matches the locked SHA256. The public bootstrap lock and append-only Worker release map adopt that object; the old fc684 object remains supported. This is preparation only: production Worker content and App pointers remain at fc684 / v1.1.3. Current physical-device acceptance is owner-waived, not tested. Signing and controlled pointer/traffic promotion remain pending. Evidence: CF `reports/operations/analects-consolidation-20261004/serial10/content-stage-public-readback.json`.

## 2026-10-05 — Candidate and published-content test compatibility

The native source review tests now run against both the exact reviewed candidate and the currently published bundle. A test-only review projection exercises the new parsing and feedback rules when CI bootstraps the supported old bundle; it is never an application content source. Packaging assertions reject a generated candidate with missing reviews, require the exact reviewed digest when present, and accept the old bundle only under its published lock. Each content scenario passes161 tests in each distribution channel; the actual candidate assets were restored afterward. Runtime source, candidate content, lint and APK hashes are unchanged from086327a.

The inherited event-v2 source-only workflow has a separate existing conflict: its frozen allowlist already rejects14 paths on current GitHub main921d368. Its code/contract and inactive status were not changed or bypassed. This is an outstanding CI/release governance disposition, not a passed check. Evidence: `serial7/weibian-ci-content-compatibility.json` and `serial7/weibian-inherited-ci-boundary.json` in the Analects consolidation report.

# Source-reviewed exam adoption — serial7 local candidate

This section supersedes the unchanged-exam statement in the retained serial6 history below. Transaction `20261005-weibian-exam-source-review` pins the additive GK review source `e0dcee540ad340901ec48112292e8c6cd19d5e21`; the other66 upstream blobs remain unchanged. The previous GK record fields are identical to0b3258f. Fifteen publisher file references were visually reviewed and retained with byte sizes and hashes; these are reproductions, not exam-authority documents.

`exam-review-map.json` maps12 reviewed subparts to all23 historical question IDs. Duplicate candidates remain separate and keep their prompts and scores. Exact question/material hashes and each target prompt are checked before adding optional `sourceReview` objects. All seven pre-existing exam objects are unchanged after removing only the new group/question review fields. No history-contract relaxation or attempt merge is allowed.

The native reader displays corrected material and source scope, links the source images, and separates reviewed answers from retained historical references. Future AI feedback receives the corrected material and per-question answer. Unknown printed subpart scoring (2019 and2023) yields comments only: no fabricated six-point denominator and no numeric grade persistence. Known scores must have a matching denominator and range. This affects new attempts only; no historical score, record, outbox, owner binding or Room migration changes.

Additive fields keep old bundles readable by the new App and preserve every key read by existing consumers. Old Apps ignore the new fields and therefore do not implement the correction; publishing a content pointer alone cannot satisfy acceptance. KZ currently reads chapters only; Fuzi must explicitly adopt the review before claiming corrected exam content. LY's pinned KZ corpus is unchanged. Source scanning covers137 registered roots, four exact candidate trees, Direct/Play, old immutable URLs and anonymous external readers. Live manifest remainsfc68413c7b70da0e; neither mutable pointers nor old objects have changed.

Nine Python source/history tests and content validation pass. Both native variants pass160 tests each, lint (zero errors; nine warnings each) and debug assembly offline. Both APKs contain the exact899729-byte content and its matching manifest. Removing only the added review fields reproduces the entire prior2b6ffd83aff1a564 bundle digest, not just ID counts. The first native attempt stopped at a sandbox cache lock; its failed receipt is retained. The successful bounded offline run used the existing shared cache with the required filesystem permission.

Local rollback is the retained3bf9923 source branch ancestor and publishedfc684 content. Required next gates: device and update compatibility, Fuzi/Web adoption, authenticated new-attempt/readback without provider-budget overrun, governed exact-source release, then Status stored/public/RSS. Keep the original learner data and all immutable objects.

Evidence: `/Users/ylsuen/CF/reports/operations/analects-consolidation-20261004/serial7/`; controlling receipt: `/Users/ylsuen/CF/reports/operations/shared_hub_changes/2026-10-05-weibian-exam-source-review.json`.

---

# Fixed-source content generation

2026-10-05 local candidate, not published. Parent source is `921d368`; the
published content remains `fc68413c7b70da0e`. No APK, content pointer, immutable
object, user record, source site or shared service was changed.

`content/source-input-lock.json` fixes 67 upstream Git blobs from four accepted
source revisions: KZ owns the corpus; LY owns concepts, figures and the practice
bank; GK and GKS own the retained exam inputs. `source_inputs.py` validates each
repository identity, exact commit and file SHA256 before parsing. It reads Git
objects, never a mutable upstream checkout. Unknown inputs, missing blobs and
bad JSON fail instead of silently removing a source. Existing CF source
repositories are required for this offline workstation pipeline.

The prior builder read only a normalized `questions` array. Existing GK inputs
retain numbered `question1` through `questionN` fields, so a nominally successful
rebuild omitted 12 historical question IDs and the 2018 Analects microessay.
The importer now accepts the explicit structured schema or the numbered legacy
schema, preserving field numbers as IDs and only reading a score stated in the
question itself. Missing scores remain null. It does not merge the historical
duplicate candidates or reinterpret answers.

`content/history-contract.json` derives from the hash-verified published bundle.
It protects ordered chapter, bank, concept and figure IDs, all 29 aliases, and
all seven exam groups / 23 question IDs, prompts and scores. The check runs
before any output is written. A future correction or extension must review and
update this contract with original-paper and history evidence; changing a count
alone is insufficient.

The previous concept/figure adapter expected fields the LY source does not
define, silently producing empty explanations and references. It now maps
concept `label/gist/question/pitfall/keyPassages` and figure
`name/aka/kind/trait/note/keyPassages` into the existing Android schema. Alias
references resolve to canonical chapters; references remain ordered and unique.
No pronunciation is invented. Missing required text or invalid references fail.

The candidate is `2b6ffd83aff1a564`, SHA256
`2b6ffd83aff1a564343adde96330747a802a7d294b013d848926c070cc10cba5`,
879,464 bytes. Whole collections `chapters/books/aliases/bank/gaokao` are exactly
equal to the published bundle; only `concepts/figures` differ. Preserving the
exam bytes is not original-paper verification: known 2015 answer conflicts,
historical answer-source labels and duplicate IDs remain pending review.

## Local verification and regeneration

Run the seven source/history regression tests and the guarded check:

```sh
PYTHONDONTWRITEBYTECODE=1 /Users/ylsuen/.venv/bin/python -m unittest discover -s content -p 'test_build_content.py'
PYTHONDONTWRITEBYTECODE=1 /Users/ylsuen/.venv/bin/python content/build_content.py --check
```

After reviewing the exact lock and history diff, normal generation writes only
`content/dist/`. For local native acceptance, copy its `content.json` and
`manifest.json` into the task-owned `app/src/main/assets/` as `content.json` and
`content-manifest.json` respectively; these generated data
files remain ignored and must never enter public Git. The generated manifest
records `sourceInputLockSha256`. Run all six existing native gates in
`docs/VERIFICATION_STANDARD.md` against these actual candidate assets.

The native content test also checks the exact bundled manifest filename, version,
size and digest against the bytes it parses, preventing a build with a missing
version manifest from passing on corpus counts alone.
Both assets are declared Gradle test inputs, so changing only content cannot
reuse a test result from another content version.

Serial6 evidence: seven Python tests passed; both native variants executed 154
tests with zero failures/errors/skips, both lint and debug assembly tasks passed
offline. Both built APKs contain the exact candidate content hash. Evidence:
`/Users/ylsuen/CF/reports/operations/analects-consolidation-20261004/serial6/weibian-native-check-result.json`
and `weibian-native-assets-and-tests.json`. No device was touched; debug APKs
are disposable test derivatives and are not signed release artifacts.

Do not update `public-content-lock.json`, the Worker manifest/release map or
downstream KZ/Fuzi locks until the coordinated exact-source release and Web/App
acceptance gates pass. The public bootstrap still restores the old accepted
bundle; it must not be used to validate this unpublished candidate. Eventual
publication follows `docs/DEPLOYMENT.md`, including a new immutable object and
explicit App compatibility disposition. Restore parent source and the unchanged
published lock for local rollback; preserve all forward learning data.
