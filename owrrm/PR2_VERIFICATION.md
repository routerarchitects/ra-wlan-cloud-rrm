# PR #2 verification

Verified on 2026-11-05 on branch `mcp_rrm_wifi_scan`. The remote PR head at the
time of inspection was `dde6265e671ed01048d29fafb489a56b34632e3c`:
[PR #2](https://github.com/routerarchitects/ra-wlan-cloud-rrm/pull/2).
Results below cover the local changes on top of that commit. They do not attest
to a new remote commit or GitHub Actions run.

## Changes and regression coverage

- `ConfigManager.java`: bandwidth application reports its own change result, so
  invalid bandwidth cannot erase channel or transmit-power modifications.
  Validation reads the configuration before applying width. Null, HT80, policy,
  regulatory and capability incompatibilities leave the width untouched.
- Permanent bandwidth validation failures retain the override without queuing
  another iteration. A later event or periodic evaluation can revalidate it;
  tests cover restored capability support and correction from HT40 to HT20.
- Missing capability responses, capability retrieval exceptions, null/nonzero
  delivery acknowledgments and delivery transport exceptions retain work.
  Per-device monotonic deadlines limit automatic retries to the existing loop
  interval. No new thread or scheduler is introduced. Tests advance an injected
  clock and verify repeated early iterations make no extra retry requests.
- Configuration acknowledgments continue to accept a non-null response with
  `errorCode == 0`, including minimal metadata and nonstandard status strings.
  Scan acceptance validation is unchanged.
- Channel and transmit-power tests verify user values override automatic values,
  valid updates are delivered despite invalid bandwidth, other radio settings
  survive, and invalid width is not applied. Existing inheritance, persistence,
  scan history, security, optimization and authentication tests also run.

## Maven results

Maven was absent from PATH, so Maven 3.9.9 was downloaded to `/tmp`. Existing
checkout build outputs cannot be overwritten by this user, so verification used
an isolated tracked-source copy at `/tmp/rrm-pr2-verify-4k_jygxo`, including all
new tests and fixtures. The host JDK is OpenJDK 17; the project compiles for Java
11. Docker validation below also runs SDK and RRM tests on JDK 11.

Complete verification command:

```bash
/tmp/apache-maven-3.9.9/bin/mvn -B -Dmaven.repo.local=/tmp/rrm-maven-repository clean verify
```

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| lib-cloudsdk | 13 | 0 | 0 | 0 |
| lib-rca | 1 | 0 | 0 | 0 |
| owrrm | 109 | 0 | 0 | 0 |
| Total | 123 | 0 | 0 | 0 |

`BUILD SUCCESS`. The repository's default `spotless.check.skip=true` applies to
`verify`; changed Java files were separately formatted with the configured
Spotless formatter.

Focused module commands (same Maven executable, working directory and local
repository; `-o` uses the dependencies already downloaded):

```bash
mvn -B -o -Dmaven.repo.local=/tmp/rrm-maven-repository -pl lib-cloudsdk -Dtest=WifiSecurityTest test
mvn -B -o -Dmaven.repo.local=/tmp/rrm-maven-repository -pl owrrm -am -Dtest=ConfigManagerWifiTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -o -Dmaven.repo.local=/tmp/rrm-maven-repository -pl owrrm -am -Dtest=ModelerWifiTest,ApiServerTest,OWGWCommandIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

All focused commands passed after formatting: `WifiSecurityTest` 9 tests,
`ConfigManagerWifiTest` 21, `ModelerWifiTest` 3, `ApiServerTest` 28 and
`OWGWCommandIntegrationTest` 1 (16 fixture cases). No failures, errors or skips.

An initial full run failed one newly added test because it reassigned an AP
setting without using the configuration setter to invalidate the cached merged
config. The test now uses that setter. The corrected full run passed. Both the
initial failure log and passing full-run log, along with JUnit XML/text reports,
are retained under `/tmp/rrm-pr2-verification-results` on this workstation.

## Integration scope

- Bandwidth: internal HTTP request → RRM validation → persisted override →
  ConfigManager → real UCentralClient serialization/HTTP → local mock OWGW.
  Inventories and capabilities are test data. The mock receives the correct
  serial, numeric width and preserved channel/power.
- Scan execution: internal HTTP endpoint → real UCentralClient → local mock OWGW
  → deserialized acknowledgment → unchanged validator. Tests verify acceptance,
  errors, null responses and `timedout` status mapping.
- `OWGWCommandIntegrationTest` sends 16 synthetic response cases through the
  actual SDK HTTP transport. They cover accepted/pending/completed commands,
  rejected commands, top-level/embedded errors, omitted metadata, mismatched
  identity, null/empty responses and HTTP/gateway timeouts. A gateway HTTP error
  yields the SDK's existing null result; a `timedout` command status is distinct.
- Results: supplied KafkaRecord objects → Modeler → scan parser/security
  classification → RRM scan-result HTTP endpoint. Tests cover device isolation,
  empty and stale batches, timestamp ordering, snapshot publication and copied
  results. No live Kafka broker is used.

These are mocked component/HTTP integration tests, not full end-to-end tests
against deployed OWGW or MCP. The `gw_wifi_scan_run` and `gw_wifi_scan_result`
HTTP mappings are unchanged; no external MCP server was exercised.

## Docker and CI/CD

The existing default Dockerfile was built without publishing:

```bash
docker build --progress=plain --tag rrm-pr2-verification:local /tmp/rrm-pr2-verify-4k_jygxo
```

Build succeeded, image ID
`sha256:e455df9213d55995888e5397884b65ad8502dcc3aff94194c7856847d09cd1ae`.
Its existing `mvn clean package -pl owrrm -am` command ran 122 SDK/RRM tests on
the `maven:3-eclipse-temurin-11` build image, with no failures/errors/skips.
`lib-rca` is outside that Docker command's reactor and is covered by the full
123-test verification above. No image was pushed and no deployment was started.

Inspected all five local workflows: Docker CI, Jira linkage, chart release,
GitHub Pages and PR image cleanup. Only Docker CI applies to this main-targeted
PR; the others are restricted to release PRs, tag pushes, main pushes or closed
PRs. Docker CI previously had no explicit Maven job. Its external composite
action builds the Dockerfile (which runs SDK/RRM tests indirectly), but attempts
a registry login and image push on PRs and requires registry credentials.

Updated `.github/workflows/ci.yml` adds **Maven verification** on JDK 11, running
`mvn -B clean verify` across all modules. It retains Surefire/Failsafe reports
using `if: always()` and does not mask Maven's failure status. Docker now depends
on that job and uses `ubuntu-latest` instead of `ubuntu-20.04`. Existing
main-only deployment conditions are preserved. YAML and job/artifact/deployment
conditions were checked locally.

For head `dde6265e671ed01048d29fafb489a56b34632e3c`, GitHub returned zero
workflow runs, zero check runs and no commit statuses in the upstream repository;
the fork's PR workflow lookup also returned no runs. Therefore there is no run
URL or final remote CI status to report. The workflow changes are local and
still need execution by GitHub Actions after publication to the PR branch.
Branch-protection requirements and secret availability were not verified.
Local tests and Docker success must not be described as remote CI success.

## Unresolved OWGW integration evidence

No nonproduction gateway endpoint, supported gateway version matrix or captured
acknowledgment responses were supplied or found in this checkout. Real OWGW
integration remains blocked. `owgw-command-responses.json` is explicitly
synthetic and cannot demonstrate deployed compatibility. No successful real
integration is claimed, and the scan validator was not loosened speculatively.

For each supported gateway version, capture sanitized `configure` and `wifiscan`
HTTP responses with their HTTP status, version and provenance. Verify command
name, serial number, UUID, status vocabulary, submitted/executed/completed
timestamps, top-level/embedded errors and optional fields. Add those captures
as versioned fixtures and run them through the SDK and endpoint before asserting
compatibility. Current tests characterize the existing strict scan contract;
they do not establish that every supported gateway implements it.
