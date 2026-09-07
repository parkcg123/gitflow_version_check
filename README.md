# Git Branch Version Tracker (`gitflow_version_check`)

IntelliJ IDEA plugin to track and display merge commits for `hotfix`, `release`, and `feature` branches from Git commit history.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Asynchronous Git Query**: Uses the native IntelliJ `git4idea` API (`GitLineHandler`, `Task.Backgroundable`) without freezing the IDE UI.
- **Automated Version Parsing**: Parses merge commit subjects using regex (`Merge branch '(hotfix|release|feature)/([^']+)'`) to extract:
  - Branch Type (`hotfix`, `release`, `feature`)
  - Version / Task identifier (e.g. `1.0.83-20260827`, `v2.1.0`)
  - Commit Date & Hash
  - Raw Subject
- **Interactive ToolWindow**:
  - Located on the right sidebar (`anchor="right"`).
  - Search limit selection (`500` / `1000` commits).
  - Instant branch type filtering (`ALL`, `hotfix`, `release`, `feature`).
  - Refresh button to reload Git history on demand.
  - One-click copy or double-click to copy the version string directly to the clipboard.

## Requirements

- IntelliJ IDEA 2023.3 or higher (Community or Ultimate).
- Java 17+ (IntelliJ 2023.3+ runtime requirement).
- Git integration (`git4idea`) plugin enabled.

## Building and Running

### Build the Plugin
```bash
./gradlew buildPlugin
```
The compiled plugin zip file will be generated in `build/distributions/`.

### Run in a Sandbox IDE
```bash
./gradlew runIde
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
