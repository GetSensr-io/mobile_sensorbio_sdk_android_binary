# Security Policy

## Reporting a vulnerability

Report suspected vulnerabilities privately to **security@sensorbio.com**. Do not
open a public issue, and do not include live credentials in the report.

We aim to acknowledge within **2 business days** and to give a remediation
timeline within **10 business days**, in line with the Vulnerability Management
Standards held in the `infrastructure` repository under `compliance/`.

## Scope

This repository is part of the Sensor Bio platform, which handles personal and
health data and is in scope for SOC 2 and HIPAA. Anything that could expose user
data, bypass authentication, or leak credentials is in scope.

## If you find a committed secret

Treat it as live. Report it as above and **do not** open a pull request that
merely deletes it: removing a secret from the tip of a branch does not remove it
from history, and disclosure via a public diff makes it worse. Rotation at the
provider comes first, per ADR-004 in the `infrastructure` repository.
