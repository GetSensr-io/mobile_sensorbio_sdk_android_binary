# Licence gate (MSA 6.6B)

Checks that nothing this repo ships carries a licence that would oblige a
customer to disclose the source of their own application, or that would limit
what they charge for it.

SB-2055/SB-2056 put equivalent gates on backend, web_platform and
infrastructure. SB-2204 extends them to the mobile SDKs, the apps and firmware,
which is where the warranty bites hardest: customers link this code into their
own applications and inherit our transitive licences directly.

## Running it

```bash
python3 scripts/license_gate/gate.py                  # human-readable
python3 scripts/license_gate/gate.py --json           # machine-readable
python3 scripts/license_gate/gate.py --csv reg.csv    # component register
python3 scripts/license_gate/gate.py --blocking       # exit 1 on a finding
python3 scripts/license_gate/test_policy.py           # classifier tests
```

No dependencies: Python 3 standard library only, no toolchain, no SDK, no
credentials. `GITHUB_TOKEN` is optional and only lifts the API rate limit.

## Advisory, for now

The gate reports findings and still exits 0. Per ADR-002 and the SB-2204
rollout, it does not block PRs on licence debt that predates it. Once the
backlog is cleared, add `--blocking` to the gate step in
`.github/workflows/license-gate.yml` and a new forbidden licence fails the
build.

## How it decides

`policy.py` holds the whole policy: a forbidden list, an allowed list, and the
rule that anything in neither is a finding. It **fails closed** — a licence the
policy does not recognise needs a human to classify it deliberately, rather
than passing silently.

Classification reads the **declared** licence identifier, never licence body
text. MPL-2.0 names GPL/LGPL/AGPL in its "Secondary License" definition and
GPL-3.0 section 13 references the AGPL, so scanners that grep licence text
report copyleft on components that are not copyleft at all. This is the same
reasoning as `go-tools/licensescan/classify.go` in backend and
`scripts/check-licenses.mjs` in web_platform.

Only what **ships** is gated. A test-only or annotation-processor dependency
never reaches a customer application, so its licence imposes nothing on them;
those are still inventoried in the register but do not fail the gate.

## Recording an exception

Some components are legitimately fine but cannot be classified automatically —
a dual licence where we elect the permissive branch, or a vendor licence that
has been reviewed. Record the decision in `exceptions.json`:

```json
{
  "exceptions": {
    "org.example:thing": {
      "reason": "dual EPL-2.0 or EDL-1.0; Sensor Bio elects EDL-1.0 (BSD-3-Clause)"
    }
  }
}
```

Every entry needs a reason a reviewer can check — the loader rejects one
without it. An exception is a documented legal election, so it belongs in the
audit trail rather than in someone's memory.

## Adding a licence to the policy

Edit the `FORBIDDEN` or `ALLOWED` map in `policy.py` and add a case to
`test_policy.py`. Deciding a licence is acceptable is a legal call, not a
tidying-up exercise: do not add one to `ALLOWED` to make CI green.
