# Release scripts — `scripts/`

Plain Bash, configured by environment variables, so the same scripts run from a shell, mise, an agent or
a CI pipeline. Each carries a `#MISE description` header, so the directory can be included as mise
tasks. Run them from the application's directory.

| Script           | What it does                                                                                  |
|------------------|-----------------------------------------------------------------------------------------------|
| `preflight`      | Fails when the infra repo has incoming changes; asks before releasing uncommitted app changes |
| `bump-image-tag` | Rewrites `image: <image>:<tag>` lines in compose files and Kubernetes manifests               |
| `release`        | `preflight`, the Gradle build and push, `bump-image-tag`                                      |

| Variable               | Used by                     | Meaning                                                             |
|------------------------|-----------------------------|---------------------------------------------------------------------|
| `VAADMIN_INFRA_DIR`    | all                         | The repo holding the deployment files                               |
| `VAADMIN_IMAGE`        | `bump-image-tag`            | The image without a tag                                             |
| `VAADMIN_IMAGE_FILES`  | `bump-image-tag`            | Space-separated files to update, relative to `VAADMIN_INFRA_DIR`    |
| `VAADMIN_TAG`          | `bump-image-tag`            | The tag; by default `build.docker.tag` from the last build          |
| `VAADMIN_ALLOW_DIRTY`  | `preflight`                 | `1` to release uncommitted changes without asking                   |
| `VAADMIN_GRADLE_TASKS` | `release`                   | The build and push tasks; default `jib`                             |
| `DRY_RUN`              | `release`, `bump-image-tag` | `1` to skip the build and print the changes instead of writing them |

The scripts release: build, push, and point the deployment files at the new tag. To deploy as well — sync to a
host, restart, wait for readiness, commit the deployment — see [wannabe](https://github.com/gnagy/wannabe),
which covers the same release in small steps.
