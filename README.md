# FlowTags (`gitflow_version_check`)

IntelliJ IDEA plugin for managing GitFlow branching lifecycles and tracking Semantic Versioning (SemVer) from commit history, tags, and active branches.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Status Bar Widget**: Quick access to the current branch, active GitFlow branches, version history, and one-click clipboard copy.
- **Next Version Suggestion**: Automatically calculates and suggests next Hotfix (`+patch`) and Release (`+minor`) versions with date suffixes from both commit/tag history and active branches.
- **Full GitFlow Operations**: Built-in Init, Start (Hotfix / Release / Feature), and Finish workflows with automatic branch merging, tagging, and local/remote branch cleanup.
- **Safety Checks**: Prevents branch operations when tracked uncommitted changes exist and warns on merge conflicts.

## Requirements

- IntelliJ IDEA `2023.3` or higher (`sinceBuild = 233`, open-ended compatibility).
- Java `17+` runtime (JDK `21` / `25` supported for building).
- Bundled `Git4Idea` plugin enabled.

## Building and Verifying

### Build the Plugin
```bash
./gradlew buildPlugin
```
The distribution zip archive is generated under `build/distributions/`.

### Verify Plugin Structure and IDE Compatibility
```bash
./gradlew verifyPluginStructure verifyPlugin
```

### Run in a Sandbox IDE
```bash
./gradlew runIde
```

## Automated Release & Deployment

GitHub Actions workflows are configured under `.github/workflows/`:
- [build.yml](.github/workflows/build.yml): Runs `buildPlugin`, `verifyPluginStructure`, and `verifyPlugin` on `main` pushes and pull requests.
- [release.yml](.github/workflows/release.yml): Runs verification, publishes to JetBrains Marketplace (`publishPlugin`), and creates a GitHub Release when a `v*` tag is pushed (or via manual `workflow_dispatch`).

### Required GitHub Repository Secrets
- `PUBLISH_TOKEN` (required): JetBrains Marketplace permanent upload token.
- `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` (optional): Plugin signing credentials; signing is skipped automatically when omitted.

### Release Checklist
1. Update `pluginVersion` in `gradle.properties` (e.g., `1.0.10`).
2. Add release notes in `src/main/resources/META-INF/plugin.xml`.
3. Commit changes, tag the release matching `v<pluginVersion>`, and push:
   ```bash
   git tag v1.0.10
   git push origin main v1.0.10
   ```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
