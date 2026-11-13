# Security policy

**Atalanta** is an unofficial Java 17 Alpaca client library under development. It is not affiliated with Alpaca Markets.

## Supported versions

No Atalanta artifact has been published yet. We currently review potential security fixes against `main` on a best-effort basis. No released version is supported or guaranteed a remediation timeline.

## Report vulnerabilities privately

**Do not post vulnerabilities, exploits, credentials, financial account identifiers, or private account data in public issues or pull requests.**

Use [GitHub private vulnerability reporting](https://github.com/black-belt-badger/atalanta/security/advisories) and choose **Report a vulnerability**. Supply the impact, reproduction steps, and a sanitized proof of concept. Do not include working Alpaca API keys or full customer responses.

For ordinary defects, use the [public bug template](https://github.com/black-belt-badger/atalanta/issues/new/choose).

## Credential and data boundaries

Future integrations must keep credentials out of source control, exceptions, logs and CI output. Until Alpaca endpoints are implemented, the repository contains only a Maven library skeleton with build and test infrastructure.
