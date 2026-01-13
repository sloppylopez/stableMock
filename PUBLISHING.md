# Publishing StableMock to Maven Central

This guide walks you through publishing StableMock to Maven Central (via Sonatype Central Portal).

## Publishing Methods

StableMock supports **two publishing methods**:

1. **Automated Publishing (Recommended)** - Via GitHub Actions workflows
   - SNAPSHOT versions: Auto-published on push to `main` (when code changes)
   - Release versions: Auto-published when you push a git tag (e.g., `v1.1.0`)
   - See [PUBLISHING_SNAPSHOTS.md](PUBLISHING_SNAPSHOTS.md) for details

2. **Local Publishing** - Manual publishing from your machine
   - Use `./gradlew publishToMavenCentral` locally
   - Requires credentials in `~/.gradle/gradle.properties`
   - See sections below for setup

**Note:** Both methods use the same Gradle task (`publishToMavenCentral`) and configuration, so the publishing process is identical.

## Prerequisites

### 1. Central Portal Account Setup

1. **Create account** at https://central.sonatype.com/
   - Go to "Sign Up" and create an account
   - Verify your email
   - This is the new Central Portal (replaces the old OSSRH JIRA-based process)

2. **Register namespace** (group ID)
   - Go to: https://central.sonatype.com/publish/register
   - Click "Register New Namespace"
   - Enter your namespace: `com.stablemock`
   - Choose verification method:
     - **GitHub** (if namespace matches GitHub username/org): Automatic verification
     - **DNS TXT Record** (for custom domains): Add TXT record to your domain
     - **SonaType Central Portal** (for existing accounts): Link existing account
   
3. **Verify Namespace Ownership**
   
   **If using GitHub verification:**
   - Namespace must match your GitHub username or organization
   - Grant Central Portal access to your GitHub account
   - Verification is automatic
   
   **If using DNS TXT Record verification:**
   
   Central Portal will provide a verification key (e.g., `rahnlty5uu`).
   
   **Steps to add DNS TXT record:**
   
   1. **Log into your domain registrar** (where you manage `stablemock.com`)
      - Common registrars: GoDaddy, Namecheap, Cloudflare, Google Domains, etc.
   
   2. **Navigate to DNS management**
      - Look for "DNS Settings", "DNS Management", "DNS Records", or "Advanced DNS"
   
   3. **Add a TXT record:**
      - **Record Type:** `TXT`
      - **Name/Host:** `@` or `stablemock.com` (or leave blank - depends on registrar)
      - **Value/Content:** `rahnlty5uu` (the verification key Central Portal provided)
      - **TTL:** Default (usually 3600 seconds)
   
   4. **Save the record**
   
   5. **Wait for DNS propagation** (usually 5-60 minutes, can take up to 24 hours)
      - Verify it's live: https://mxtoolbox.com/TXTLookup.aspx
      - Enter `stablemock.com` and check if the TXT record appears
   
   6. **Return to Central Portal** and click "Verify"
      - Central Portal will automatically verify the TXT record once DNS propagates
   
   **Example DNS configurations by registrar:**
   
   - **Cloudflare:** DNS → Records → Add record → Type: TXT, Name: `@`, Content: `rahnlty5uu`
   - **GoDaddy:** DNS → Records → Add → Type: TXT, Name: `@`, Value: `rahnlty5uu`
   - **Namecheap:** Advanced DNS → Add New Record → Type: TXT Record, Host: `@`, Value: `rahnlty5uu`
   
   **Note:** The exact field names vary by registrar, but you're adding a TXT record for the root domain (`@` or `stablemock.com`) with the verification key as the value.

4. **Generate User Token** (for publishing)
   - Go to: https://central.sonatype.com/publish/tokens
   - Click "Generate User Token"
   - Copy the username and password (token)
   - **Important:** This is NOT your login password - it's a special token for publishing
   - Save these credentials securely (you'll need them for `gradle.properties` or GitHub Secrets)

### 2. GPG Key Setup (for signing artifacts)

1. **Install GPG** (if not installed)
   - Windows: Download from https://www.gpg4win.org/
   - Mac: `brew install gnupg`
   - Linux: Usually pre-installed

2. **Generate GPG key**
   ```powershell
   gpg --full-generate-key
   ```
   - Choose: `(1) RSA and RSA (default)`
   - Key size: `4096`
   - Expiration: `2y` (or your preference)
   - Enter your name and email (use same email as Central Portal account)
   - Set a passphrase (remember this!)

3. **List your keys** to get the key ID
   ```powershell
   gpg --list-keys --keyid-format LONG
   ```
   - Look for a line like: `pub   rsa4096/ABC123DEF456 2024-01-01 [SC]`
   - `ABC123DEF456` is your key ID

4. **Export your private key** (base64 encoded)
   ```powershell
   gpg --armor --export-secret-keys YOUR_KEY_ID | Out-File -Encoding utf8 secret-key.txt
   ```
   - Copy the entire content (including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`)

5. **Publish public key to keyserver**
   ```powershell
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```
   - Also try: `keys.openpgp.org` or `pgp.mit.edu` if first fails

## Configuration

### 3. Create `gradle.properties` file

Create `gradle.properties` in your user home directory (`~/.gradle/gradle.properties` on Linux/Mac, `C:\Users\YourUsername\.gradle\gradle.properties` on Windows):

```properties
# Central Portal credentials (from User Token)
mavenCentralUsername=your-token-username
mavenCentralPassword=your-token-password

# GPG signing
signing.keyId=B7CD1441480535FA
signing.password=your-gpg-passphrase
signing.secretKeyRingFile=C:\Users\YourUsername\.gnupg\secring.gpg
```

**Important:**
- Use the **User Token** credentials from Central Portal (not your login password)
- For Windows: Use forward slashes or escaped backslashes in `signing.secretKeyRingFile`:
  - `C:/Users/YourUsername/.gnupg/secring.gpg` or
  - `C:\\Users\\YourUsername\\.gnupg\\secring.gpg`
- For Linux/Mac: Use standard path: `/Users/YourUsername/.gnupg/secring.gpg`
- This file is in your home directory, not the project directory (so it's not committed to git)
- Alternatively, use environment variables instead (see below)

### 4. Update Developer Info in `build.gradle`

Edit `build.gradle` and replace:
- `'Your Name'` → Your actual name
- `'your.email@example.com'` → Your email (should match Sonatype account)

### 5. Update Version

When ready to publish:
- Change `version = '1.0-SNAPSHOT'` to `version = '1.0.0'` in `build.gradle`
- For subsequent releases: `1.0.1`, `1.1.0`, `2.0.0`, etc.

## Testing Locally

### 6. Test Local Publication

Before publishing to Maven Central, test locally:

```powershell
# Publish to Maven Local only
./gradlew publishToMavenLocal

# Verify it worked
# Check: ~/.m2/repository/com/stablemock/stablemock/1.0.0/
```

### 7. Test with Example Project

Test that the local publication works:

```powershell
cd examples/spring-boot-example
./gradlew build --refresh-dependencies
```

## Publishing to Maven Central

### Automated Publishing (Recommended)

**For SNAPSHOT versions:**
- Automatically published when you push to `main` branch (if code/build.gradle changed)
- Or manually trigger via GitHub Actions → "Publish SNAPSHOT" → "Run workflow"
- See [PUBLISHING_SNAPSHOTS.md](PUBLISHING_SNAPSHOTS.md) for details

**For Release versions:**
- See detailed instructions below in "Publishing Release Versions" section

## Publishing Release Versions

### Automated Publishing (Recommended)

Release versions are published automatically via GitHub Actions when you push a git tag:

1. **Update version in `build.gradle`** (remove `-SNAPSHOT`):
   ```groovy
   version = '1.1.0'  // Remove -SNAPSHOT
   ```

2. **Commit and push**:
   ```bash
   git add build.gradle
   git commit -m "Release version 1.1.0"
   git push origin main
   ```

3. **Create and push git tag** (triggers release workflow):
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

4. **Workflow automatically**:
   - Extracts version from tag (`v1.1.0` → `1.1.0`)
   - Updates `build.gradle` temporarily
   - Runs tests
   - Publishes to Maven Central staging
   - Creates GitHub Release

5. **Manual step: Publish from Central Portal**:
   - Go to: https://central.sonatype.com/deployments
   - Find deployment for version `1.1.0`
   - Click **"Publish"** button
   - Wait 10-30 minutes for sync to Maven Central

6. **Update to next SNAPSHOT version**:
   ```groovy
   version = '1.2-SNAPSHOT'  // Next development version
   ```
   ```bash
   git add build.gradle
   git commit -m "Bump version to 1.2-SNAPSHOT"
   git push origin main
   ```

### Local Publishing (Alternative)

#### 8. Publish to Maven Central

For release versions (not SNAPSHOT):

```powershell
# Build and publish
./gradlew clean build publishToMavenCentral

# This will:
# 1. Build the project
# 2. Sign artifacts with GPG
# 3. Upload to Central Portal staging repository
```

For SNAPSHOT versions:

```powershell
# SNAPSHOTs go directly to snapshots repository (no staging)
./gradlew clean build publishToMavenCentral
```

#### 9. Release from Central Portal

After publishing a release version:

1. **Go to Central Portal Deployments**: https://central.sonatype.com/deployments
2. **Login** with your Central Portal credentials
3. **Find your deployment**: Look for your version (e.g., `1.1.0`)
4. **Click "Publish"** button
   - This validates and releases your artifacts
   - Wait for validation to complete
5. **Wait for sync**: Artifacts appear in Maven Central within 10-30 minutes
   - Check: https://repo1.maven.org/maven2/com/stablemock/stablemock/
   - Check: https://search.maven.org/artifact/com.stablemock/stablemock

### 10. Verify Publication

Check Maven Central:
- https://repo1.maven.org/maven2/com/stablemock/stablemock/
- https://search.maven.org/artifact/com.stablemock/stablemock

## Troubleshooting

### GPG Signing Issues

**Error: "No secret key"**
- Check `signing.secretKeyRingFile` path is correct in `gradle.properties`
- For Windows: Use forward slashes or escaped backslashes
- Verify the file exists at the specified path
- Ensure `signing.keyId` matches your GPG key ID

**Error: "Bad passphrase"**
- Verify `signing.password` matches your GPG key passphrase

### Central Portal Authentication Issues

**Error: "401 Unauthorized" or "403 Forbidden"**
- Verify `mavenCentralUsername` and `mavenCentralPassword` are correct
- Ensure you're using the **User Token** credentials (not your login password)
- Generate a new User Token if needed: https://central.sonatype.com/publish/tokens
- Check your Central Portal account is active
- Verify your namespace is registered and verified

### Staging Repository Issues

**Validation fails:**
- Check POM metadata (name, description, license, SCM)
- Ensure all required files are present (jar, sources, javadoc)
- Check GPG signatures are valid

**Can't find staging repository:**
- Wait a few minutes after `publish` completes
- Check "Staging Repositories" in Nexus UI
- Look for repositories starting with `comstablemock`

## Alternative: Using Environment Variables

Instead of `gradle.properties`, you can use environment variables:

**PowerShell:**
```powershell
$env:ORG_GRADLE_PROJECT_mavenCentralUsername="your-username"
$env:ORG_GRADLE_PROJECT_mavenCentralPassword="your-password"
$env:ORG_GRADLE_PROJECT_signingInMemoryKey="-----BEGIN PGP PRIVATE KEY BLOCK-----..."
$env:ORG_GRADLE_PROJECT_signingInMemoryKeyId="B7CD1441480535FA"
$env:ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="your-passphrase"
./gradlew publishToMavenCentral
```

**Bash:**
```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername="your-username"
export ORG_GRADLE_PROJECT_mavenCentralPassword="your-password"
export ORG_GRADLE_PROJECT_signingInMemoryKey="-----BEGIN PGP PRIVATE KEY BLOCK-----..."
export ORG_GRADLE_PROJECT_signingInMemoryKeyId="B7CD1441480535FA"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="your-passphrase"
./gradlew publishToMavenCentral
```

**Note:** GitHub Actions workflows use these same environment variables (configured via GitHub Secrets).

## Publishing Checklist

Before publishing:
- [ ] Sonatype account created and groupId approved
- [ ] GPG key generated and published to keyserver
- [ ] `gradle.properties` created with credentials
- [ ] Developer info updated in `build.gradle`
- [ ] Version changed from SNAPSHOT to release version
- [ ] Tests pass: `./gradlew test`
- [ ] Local publication tested: `./gradlew publishToMavenLocal`
- [ ] README updated with new version

After publishing:
- [ ] Artifacts uploaded to staging repository
- [ ] Staging repository closed and validated
- [ ] Staging repository released
- [ ] Verified artifacts appear in Maven Central
- [ ] Updated README with Maven Central coordinates
- [ ] Created git tag for release version

## Next Steps After First Release

### If Using Automated Publishing:

1. **Git tag already created** by release workflow
2. **GitHub Release already created** by release workflow
3. **Update README.md** - Change dependency from `1.0-SNAPSHOT` to `1.0.0`
4. **Update version** back to `1.1-SNAPSHOT` (or next version) for next development cycle
5. **Commit and push** the version update

### If Using Local Publishing:

1. **Update README.md** - Change dependency from `1.0-SNAPSHOT` to `1.0.0`
2. **Create git tag**: `git tag v1.0.0 && git push origin v1.0.0`
3. **Create GitHub release** with changelog
4. **Update version** back to `1.1-SNAPSHOT` for next development cycle

