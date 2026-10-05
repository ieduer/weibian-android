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
