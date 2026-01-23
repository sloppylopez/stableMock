# Publishing Quick Start Guide

## Overview

StableMock uses automated GitHub Actions workflows to publish to Maven Central:
- **SNAPSHOT versions**: Published automatically on push to `main` branch
- **Release versions**: Published when you push a git tag (e.g., `v1.1.0`)

## Prerequisites

### 1. GitHub Secrets Configuration

Add these secrets in GitHub repository settings (`Settings` → `Secrets and variables` → `Actions`):

| Secret Name | Description | Example |
|------------|-------------|---------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal username | `your-username` |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token (not login password) | `token-abc123...` |
| `SIGNING_KEY_ID` | GPG key ID | `B7CD1441480535FA` |
| `SIGNING_PASSWORD` | GPG key passphrase | `your-passphrase` |
| `SIGNING_SECRET_KEY` | Base64-encoded GPG private key | `LS0tLS1CRUdJTi...` |

### 2. Get Your GPG Key for GitHub Secrets

**Export and encode your GPG key:**

```powershell
# Export your GPG private key
gpg --armor --export-secret-keys YOUR_KEY_ID | Out-File -Encoding utf8 secret-key.txt

# Base64 encode it (for GitHub Secrets)
$content = Get-Content secret-key.txt -Raw
[Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($content))
```

Copy the base64 output and paste it as `SIGNING_SECRET_KEY` in GitHub Secrets.

**Alternative:** If your key is already in the format with `\n` for newlines, you can use it directly (Gradle will parse it).

## Publishing SNAPSHOT Versions

### How It Works

1. Push to `main` branch
2. Workflow automatically:
   - Validates version ends with `-SNAPSHOT`
   - Publishes to Maven Central snapshot repository
   - No manual steps required

### Current Version

Check `build.gradle`:
```groovy
version = '1.1.0'  // Release version
// or
version = '1.1.1-SNAPSHOT'  // Next SNAPSHOT version (patch)
// or
version = '1.2-SNAPSHOT'  // Next SNAPSHOT version (minor)
```

### Workflow

```bash
# 1. Make your changes
git add .
git commit -m "Your changes"
git push origin main

# 2. Workflow runs automatically
# 3. Check deployment: https://central.sonatype.com/deployments
```

### Verify SNAPSHOT Publication

- **Central Portal Deployments**: https://central.sonatype.com/deployments
- **Direct URL**: https://central.sonatype.com/repository/maven-snapshots/com/stablemock/stablemock/VERSION-SNAPSHOT/

## Publishing Release Versions

### How It Works

1. Update version in `build.gradle` (remove `-SNAPSHOT`)
2. Commit and push
3. Create and push git tag
4. Workflow automatically:
   - Extracts version from tag
   - Updates `build.gradle` temporarily
   - Runs tests
   - Publishes to Maven Central staging
   - Creates GitHub Release

### Step-by-Step Process

#### 1. Update Version in build.gradle

```groovy
// Change from:
version = '1.1.1-SNAPSHOT'  // Current development version (or 1.2-SNAPSHOT, etc.)

// To:
version = '1.1.1'  // Release version (or 1.2.0, 2.0.0, etc.)
```

#### 2. Commit and Push

```bash
git add build.gradle
git commit -m "Release version 1.1.0"
git push origin main
```

#### 3. Create and Push Git Tag

```bash
# Create tag (use 'v' prefix)
git tag v1.1.0

# Push tag (this triggers the release workflow)
git push origin v1.1.0
```

#### 4. Manual Step: Publish from Central Portal

After the workflow completes:

1. Go to: https://central.sonatype.com/deployments
2. Find the deployment for your version
3. Click **"Publish"** button
4. Wait 10-30 minutes for sync to Maven Central

#### 5. Update to Next SNAPSHOT Version

After successful release, update `build.gradle` for next development (choose based on release type):

```groovy
// For patch release (1.1.0 -> 1.1.1-SNAPSHOT):
version = '1.1.1-SNAPSHOT'

// For minor release (1.1.0 -> 1.2-SNAPSHOT):
// version = '1.2-SNAPSHOT'

// For major release (1.1.0 -> 2.0-SNAPSHOT):
// version = '2.0-SNAPSHOT'
```

```bash
git add build.gradle
git commit -m "Bump version to 1.1.1-SNAPSHOT"  # or 1.2-SNAPSHOT, 2.0-SNAPSHOT
git push origin main
```

### Version Tag Format

- ✅ **Correct**: `v1.1.0`, `v1.2.0`, `v2.0.0`
- ❌ **Incorrect**: `1.1.0` (missing 'v' prefix), `v1.1.0-SNAPSHOT` (should not have -SNAPSHOT)

### Verify Release Publication

After publishing from Central Portal (step 4):

- **Maven Central**: https://repo1.maven.org/maven2/com/stablemock/stablemock/1.1.0/
- **Maven Search**: https://search.maven.org/artifact/com.stablemock/stablemock/1.1.0/jar
- **GitHub Release**: Automatically created by workflow

## Troubleshooting

### SNAPSHOT Workflow Fails

**Error: "Version must end with -SNAPSHOT"**
- Check `build.gradle` - version must end with `-SNAPSHOT`
- Example: `version = '1.1-SNAPSHOT'` ✅

**Error: "GPG signing secrets are not configured"**
- Add all required secrets to GitHub Secrets
- Verify secret names match exactly

**Error: "403 Forbidden" when publishing**
- Verify `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` are correct
- Ensure you're using a **user token**, not your login password
- Check namespace is registered in Central Portal

### Release Workflow Fails

**Error: "Invalid version format"**
- Tag must match pattern `v*.*.*` (e.g., `v1.1.0`)
- Must be three numbers separated by dots
- Must start with 'v'

**Error: Tests fail**
- Fix failing tests before releasing
- Workflow runs tests before publishing

**Deployment not found in Central Portal**
- Check workflow logs for errors
- Verify credentials are correct
- Check Central Portal for deployment status

## Workflow Files

- **SNAPSHOT**: `.github/workflows/publish-snapshot.yml`
- **Release**: `.github/workflows/publish-release.yml`

## Quick Reference

### Publish SNAPSHOT
```bash
# Just push to main branch
git push origin main
```

### Publish Release
```bash
# 1. Update version in build.gradle (remove -SNAPSHOT)
# 2. Commit
git commit -am "Release 1.1.0"
git push

# 3. Tag and push
git tag v1.1.0
git push origin v1.1.0

# 4. Manually publish from Central Portal
# https://central.sonatype.com/deployments
```

### Check Published Versions

**SNAPSHOT:**
- https://central.sonatype.com/repository/maven-snapshots/com/stablemock/stablemock/

**Release:**
- https://repo1.maven.org/maven2/com/stablemock/stablemock/
- https://search.maven.org/artifact/com.stablemock/stablemock
