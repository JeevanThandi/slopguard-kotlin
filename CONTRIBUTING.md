# Contributing to slopguard-kotlin

Thanks for your interest! A few things that keep this port healthy.

## Parity first

`slopguard-kotlin` is one of five sibling ports: TypeScript, Go, Python, Swift
and Kotlin. They share a contract: the wCRAP formula, the schema-2 JSON shape,
the CLI flags and exit codes, the error-envelope shape, and the `mutate`
operator ids, statuses and schema-1 mutation report. Changes to any of those must be
coordinated across the ports — please open an issue before touching shared
behaviour. Language-idiomatic internals (how Kotlin is parsed, how Gradle/JaCoCo
coverage is gathered) are free to differ; the observable contract is not.

## Building & testing

Requires a JDK 17+ (the Gradle wrapper handles Gradle itself):

```bash
./gradlew build      # compile + test everything
./gradlew test       # tests only
```

The analyzer's complexity rules are pinned by `ComplexityVisitorTest`. If you
change `ComplexityVisitor`, update those expectations deliberately and explain the
reasoning in your PR — they are the contract.

## Before opening a PR

- `./gradlew build` is green.
- New behaviour has tests.
- The regression baseline still holds:

  ```bash
  ./gradlew :app:installDist
  app/build/install/slopguard-kotlin/bin/slopguard-kotlin \
    analyze --path sample-apps/todolist/src/main/kotlin --project-dir sample-apps/todolist \
    --json --quiet | jq '{methods:.summary.methodCount, crappy:.summary.crappyMethodCount}'
  # expect {"methods":9,"crappy":0}
  ```

- The mutation baseline still holds (every sample-app mutant is killed):

  ```bash
  app/build/install/slopguard-kotlin/bin/slopguard-kotlin \
    mutate --path sample-apps/todolist/src/main/kotlin --project-dir sample-apps/todolist \
    --json --quiet | jq '{mutants:.summary.mutantCount, killed:.summary.killed, survived:.summary.survived}'
  # expect {"mutants":15,"killed":15,"survived":0}
  ```

  If you change a mutation operator, update this baseline deliberately. If a
  mutant survives, strengthen the sample app's tests, never its source.

## License

By contributing you agree your contributions are licensed under the project's MIT
license.
