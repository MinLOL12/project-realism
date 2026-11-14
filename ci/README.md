# CI

`build.yml` is the GitHub Actions workflow for this project, parked here because the automation
that created this branch could not push into `.github/workflows/` (that path needs GitHub's
`workflows` permission).

## Install it (30 seconds, web UI)

1. GitHub → the repo → **Add file → Create new file**.
2. Name it exactly `.github/workflows/build.yml`.
3. Paste the whole contents of `ci/build.yml` from this folder.
4. Commit. The **Actions** tab starts a build immediately.

Or from a local clone:

```sh
mkdir -p .github/workflows && cp ci/build.yml .github/workflows/build.yml
git add .github/workflows/build.yml && git commit -m "Add build workflow" && git push
```

(`git` from your own clone has the permission; a bot token without the `workflows` scope does not.)

## What it does

- JDK 21 (Temurin), `./gradlew build` — compiles, runs the unit tests, produces the jar.
- Caches `~/.gradle` and `.gradle/loom-cache`, so repeat builds skip the few-hundred-MB
  Minecraft/Fabric download.
- Uploads `build/libs/project-realism-0.1.0.jar` as the artifact **project-realism-jar** on every run.
- On a `v*` tag, attaches that jar to a GitHub Release, giving you a permanent download URL.

## Getting the jar

- Any run → *Artifacts* → **project-realism-jar** (zip containing the jar).
- Or tag it: `git tag v0.1.0 && git push origin v0.1.0` → the jar appears on the Releases page.
