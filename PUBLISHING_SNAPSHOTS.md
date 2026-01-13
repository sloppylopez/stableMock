# Publishing SNAPSHOT Versions to Maven Central

## Overview

SNAPSHOT versions are published to the Central Portal snapshot repository, **not** directly to Maven Central. They are available immediately after publishing and can be consumed by other projects.

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

## Publishing a Release Version

When you're ready to publish a release version (non-SNAPSHOT):

1. **Change the version** in `build.gradle`:
   ```groovy
   version = '1.0.0'  // Remove -SNAPSHOT
   ```

2. **Publish**:
   ```powershell
   ./gradlew publishToMavenCentral
   ```

3. **Go to Central Portal Deployments**: https://central.sonatype.com/deployments

4. **Click "Publish"** on your deployment

5. **Wait 10-30 minutes** for sync to Maven Central

6. **Verify** it appears in Maven Central:
   - https://repo1.maven.org/maven2/com/stablemock/stablemock/
   - https://search.maven.org/artifact/com.stablemock/stablemock

## Current Project Configuration

- **Group ID**: `com.stablemock`
- **Artifact ID**: `stablemock`
- **Current Version**: `1.1-SNAPSHOT`

## Troubleshooting

### Can't find SNAPSHOT using Central Portal search box?

This is **normal and expected**. The Central Portal search box only searches Maven Central (release versions), not the snapshot repository. SNAPSHOTs are intentionally not indexed for search because they're development versions.

To find your SNAPSHOT:
- ✅ Use the **Central Portal Deployments page**: https://central.sonatype.com/deployments
- ✅ Use the **direct snapshot repository URL**: https://central.sonatype.com/repository/maven-snapshots/com/stablemock/stablemock/1.1-SNAPSHOT/
- ✅ Make sure you added the snapshot repository to your consuming project's `build.gradle`

### Build says "BUILD SUCCESSFUL" but can't find artifacts?

1. Check Central Portal Deployments page for the deployment status
2. Verify the deployment shows "PUBLISHED" or "VALIDATED"
3. Try accessing the direct snapshot repository URL

### Want to publish a release version?

1. Change version from `1.0-SNAPSHOT` to `1.0.0` in `build.gradle`
2. Run `./gradlew publishToMavenCentral`
3. Go to Central Portal Deployments and click "Publish"
4. Wait for sync to Maven Central (10-30 minutes)

