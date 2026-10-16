# Task: Iranian IPv4 list generated in CI (role `iran-ip-list`)

Branch `antigravity/iran-ip-list-2026-10-16` from `origin/integrate/v3.1`. Draft PR into
`ccr-65b98b7a-8b3t20`, title `[antigravity] iran-ip-list`. Follow `AGENTS.md`. Design: ADR-0001
(slice 5). `BypassPolicy` takes an `Ipv4Ranges`; today it is always `Ipv4Ranges.EMPTY`.

## Do
1. A small script (Python 3 standard library only, `tools/iran-ip-list/generate.py`) that reads the
   RIPE NCC delegated stats file (`https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-extended-latest`),
   keeps `ipv4` records with country `IR` and status `allocated`/`assigned`, converts start+count to
   CIDRs, merges them, and writes one CIDR per line with a header comment (source, date, count).
   Unit-test the conversion with a tiny fixture file (no network in tests).
2. CI: generate the list in the APK job before the Android build and put it in the app assets
   (`android/app/src/main/assets/iran-ipv4.txt`); fail the build if it has fewer than 500 ranges.
   Do not commit the generated list (add it to `.gitignore`); commit only a fixture.
3. Android: load the asset at service start into `Ipv4Ranges` and pass it to the `BypassPolicy` the
   relay uses. Add the status field `"iranRanges"` (count). Only add fields.
4. Kotlin test: parsing the file format (comments, blank lines, bad lines skipped).

## Do not
- No network access at runtime on the phone for this list (it ships with the APK).
- Do not change routing decisions in `BypassPolicy` beyond feeding it the ranges.

## Done
CI green; PR says how many ranges the CI run produced. This laptop has no gradle: CI is your compiler.
