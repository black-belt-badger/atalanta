# Contributing to Atalanta

Thanks for contributing to **Atalanta**, an unofficial open-source Java library for Alpaca APIs. It is independent of Alpaca Markets and of the private Metis project.

## Before coding

- Check existing [issues](https://github.com/black-belt-badger/atalanta/issues) and the shared [Alpaca project](https://github.com/orgs/black-belt-badger/projects/7).
- Use the **Bug report**, **Feature request**, or **Experiment proposal** template for new work. Open one focused PR per issue when practical.
- Discuss public Java API and architecture decisions before implementation. M3 includes a [public API refinement ticket](https://github.com/black-belt-badger/atalanta/issues/5).
- Atalanta is a **library**, not a CLI. Match provider behavior with Kirke and Medea while keeping idiomatic Java 17 design.

## Build and test

Use JDK 17 and Maven 3.8.7 or newer:

```sh
mvn --batch-mode --no-transfer-progress clean verify
```

On Debian/Hoopoe, force Maven to use JDK 17:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn --batch-mode --no-transfer-progress clean verify
```

The compiler uses `--release 17`. Pull requests must pass the **Java 17 · Maven verify** GitHub Actions check. Prefer deterministic offline JUnit 5 and AssertJ tests; do not require real Alpaca credentials in CI.

## Open a pull request

Explain why the change is needed, link its issue, and complete the PR template. Keep changes reviewable and prefer squash merging. The `main` branch is protected.

## Privacy and security

Never commit Alpaca credentials, tokens, account identifiers, or unredacted account/market payloads. Use synthetic or sanitized fixtures, and keep local secrets out of version control. See [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## Release status

Atalanta has a **Java 17 Maven foundation and read-only paper account/assets endpoints**; it is not yet a published package and other Alpaca endpoints remain unimplemented. Publication and versioning will be refined under [#13](https://github.com/black-belt-badger/atalanta/issues/13). Do not claim a Maven Central release or API support before it exists.
