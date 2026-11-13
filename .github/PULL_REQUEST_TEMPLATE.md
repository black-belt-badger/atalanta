## Summary

Describe what changed and the public Java API or contributor problem it addresses.

## Related issue

<!-- Example: Closes #14 or Related to #5. -->

## Verification

- [ ] `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn --batch-mode --no-transfer-progress clean verify` passes locally, or an equivalent JDK 17 build.
- [ ] New behavior has offline JUnit 5/AssertJ tests, or the absence of tests is explained.
- [ ] Public Java 17 compatibility and any API changes are documented.
- [ ] No credentials, account identifiers, or private Alpaca payloads are committed.

## Notes for reviewers

Discuss public API compatibility, exact decimal handling, paper-only safety and non-obvious trade-offs as applicable.
