# Atalanta release plan (maintainers)

Atalanta is an **unpublished Java 17 library under development**. Five paper Trading API GET endpoints exist (account, assets, positions, orders and order-by-ID), but the first release remains outstanding.

## Before the first release

1. Refine and implement the public API and read-only Alpaca endpoint contracts on the shared M3 board.
2. Decide the Java library's versioning, Maven coordinates, artifact publication route (GitHub Packages vs Maven Central), authentication and signing needs under [#13](https://github.com/black-belt-badger/atalanta/issues/13).
3. Confirm the exact commit on `main` passes the required **Java 17 · Maven verify** check and local `mvn clean verify` under JDK 17.
4. Document the supported endpoints, paper-only limitations, credential handling, licensing, and consumer usage examples.
5. Verify the [versioned portable contract pack](contracts/alpaca-trading/v1/README.md) integrity hashes and `SharedTradingContractTest` on JDK 17. The fixture-set revision is **independent** of Atalanta's Maven artifact version and does not imply synchronized Kirke/Medea releases.
5. Prepare accurate release notes in `CHANGELOG.md` and tag only the tested `main` commit **after explicit approval**.

Do not create a release, tag, package registry publication or signing credentials until the first release's scope is decided. Kirke and Medea are released independently; Atalanta's future versions need not match theirs.
