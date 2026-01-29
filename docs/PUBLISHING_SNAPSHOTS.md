# Publishing SNAPSHOT Versions to Maven Central

## Overview

SNAPSHOT versions are published to the Central Portal snapshot repository, **not** directly to Maven Central. They are available immediately after publishing and can be consumed by other projects.

## Publishing Methods

### Automated Publishing (Recommended)

StableMock uses **GitHub Actions** to automatically publish SNAPSHOT versions:

**Automatic Publishing:**
- Triggers on push to `main` branch
- Only publishes when `build.gradle`, `src/`, or `gradle/` files change
- Prevents unnecessary republishing of the same SNAPSHOT version
- Uses the same Gradle task (`publishToMavenCentral`) as local publishing

**Manual Publishing:**
- Go to GitHub Actions → "Publish SNAPSHOT" → "Run workflow"
- Useful when you want to republish the same SNAPSHOT version without code changes

**Workflow:**
1. Work on feature branch → no publishing
2. Merge to `main` with code changes → automatically publishes SNAPSHOT
3. Push to `main` with only docs/markdown → no publishing (skipped)
4. Need to republish same version? → use manual trigger

### Local Publishing (Alternative)

You can also publish manually from your local machine:

```powershell
./gradlew publishToMavenCentral
```

This uses the same Gradle task and configuration as the automated workflow.

## Important Notes

### SNAPSHOT vs Release Versions

**SNAPSHOT versions:**
- ✅ Published immediately to Central Portal snapshot repository
- ✅ Available right after `./gradlew publishToMavenCentral` completes
- ✅ Accessible via direct URL or Central Portal Deployments page
- ❌ **Do NOT appear in Central Portal search box** (searches only Maven Central releases)
- ❌ **Do NOT appear in Maven Central search** (search.maven.org)
- ❌ Cannot be consumed from `mavenCentral()` repository without adding snapshot repository

**Release versions (non-SNAPSHOT):**
- Published to Central Portal staging
- Must be manually published from Central Portal Deployments page
- Sync to Maven Central takes 10-30 minutes
- ✅ Appear in Maven Central search after sync completes

## Where to Find Your SNAPSHOT

### 1. Central Portal Deployments Page

Visit: **https://central.sonatype.com/deployments**

- You should see a deployment with status "PUBLISHED" or "VALIDATED"
- Click on the deployment to see details and artifacts

### 2. Direct Snapshot Repository URL

Your SNAPSHOT artifacts are available at:
```
https://central.sonatype.com/repository/maven-snapshots/com/stablemock/stablemock/1.0-SNAPSHOT/
```

Replace `1.0-SNAPSHOT` with your actual version.

## How to Use SNAPSHOT Dependencies

To consume a SNAPSHOT version in another project, you need to add the snapshot repository to your `build.gradle`:

```groovy
repositories {
    // Add Central Portal snapshot repository
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
    mavenCentral()
}

dependencies {
    implementation 'com.stablemock:stablemock:1.0-SNAPSHOT'
}
```


## Current Project Configuration

- **Group ID**: `com.stablemock`
- **Artifact ID**: `stablemock`
- **Current Version**: `1.1.0` (release version)

## Troubleshooting

### Can't find SNAPSHOT using Central Portal search box?

This is **normal and expected**. The Central Portal search box only searches Maven Central (release versions), not the snapshot repository. SNAPSHOTs are intentionally not indexed for search because they're development versions.

To find your SNAPSHOT:
- ✅ Use the **Central Portal Deployments page**: https://central.sonatype.com/deployments
- ✅ Use the **direct snapshot repository URL**: https://central.sonatype.com/repository/maven-snapshots/com/stablemock/stablemock/VERSION-SNAPSHOT/
- ✅ Make sure you added the snapshot repository to your consuming project's `build.gradle`

### Build says "BUILD SUCCESSFUL" but can't find artifacts?

1. Check Central Portal Deployments page for the deployment status
2. Verify the deployment shows "PUBLISHED" or "VALIDATED"
3. Try accessing the direct snapshot repository URL

### Want to publish a release version?

See [PUBLISHING.md](PUBLISHING.md) for complete instructions on publishing release versions (both automated and local methods).

