# Publishing StableMock to Maven Central

This guide walks you through publishing StableMock to Maven Central (via Sonatype OSSRH).

## Prerequisites

### 1. Sonatype Account Setup

1. **Create account** at https://issues.sonatype.org/
   - Go to "Sign Up" and create an account
   - Verify your email

2. **Request groupId namespace** (if not already done)
   - Create a new issue: https://issues.sonatype.org/secure/CreateIssue.jspa?issuetype=21&pid=10134
   - Summary: `Create repository for com.stablemock`
   - Description: 
     ```
     I would like to publish artifacts under the groupId com.stablemock
     
     Project: StableMock
     Description: JUnit 5 extension for WireMock-based HTTP mocking
     GitHub: https://github.com/sloppylopez/stablemock
     License: MIT
     ```
   - Wait for approval (usually 1-2 business days)
   - You'll get a ticket number (e.g., `OSSRH-12345`)

3. **Verify Namespace Ownership** (DNS TXT Record)
   
   Sonatype requires DNS verification to prove you own `stablemock.com`. You'll receive a verification key (e.g., `rahnlty5uu`).
   
   **Steps to add DNS TXT record:**
   
   1. **Log into your domain registrar** (where you manage `stablemock.com`)
      - Common registrars: GoDaddy, Namecheap, Cloudflare, Google Domains, etc.
   
   2. **Navigate to DNS management**
      - Look for "DNS Settings", "DNS Management", "DNS Records", or "Advanced DNS"
   
   3. **Add a TXT record:**
      - **Record Type:** `TXT`
      - **Name/Host:** `@` or `stablemock.com` (or leave blank - depends on registrar)
      - **Value/Content:** `rahnlty5uu` (the verification key Sonatype provided)
      - **TTL:** Default (usually 3600 seconds)
   
   4. **Save the record**
   
   5. **Wait for DNS propagation** (usually 5-60 minutes, can take up to 24 hours)
      - Verify it's live: https://mxtoolbox.com/TXTLookup.aspx
      - Enter `stablemock.com` and check if the TXT record appears
   
   6. **Reply to Sonatype ticket** confirming the TXT record is added
      - Sonatype will verify automatically once DNS propagates
   
   **Example DNS configurations by registrar:**
   
   - **Cloudflare:** DNS → Records → Add record → Type: TXT, Name: `@`, Content: `rahnlty5uu`
   - **GoDaddy:** DNS → Records → Add → Type: TXT, Name: `@`, Value: `rahnlty5uu`
   - **Namecheap:** Advanced DNS → Add New Record → Type: TXT Record, Host: `@`, Value: `rahnlty5uu`
   
   **Note:** The exact field names vary by registrar, but you're adding a TXT record for the root domain (`@` or `stablemock.com`) with the verification key as the value.

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
   - Enter your name and email (use same email as Sonatype account)
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

Create `gradle.properties` in the project root (same directory as `build.gradle`):

```properties
# Sonatype OSSRH credentials
ossrhUsername=your-sonatype-username
ossrhPassword=your-sonatype-password

# GPG signing
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...your full key...\n-----END PGP PRIVATE KEY BLOCK-----
signingPassword=your-gpg-passphrase
```

**Important:**
- Replace `\n` with actual newlines in `signingKey` (paste the full key block)
- Add `gradle.properties` to `.gitignore` to avoid committing credentials
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

### 8. Publish to Staging Repository

For release versions (not SNAPSHOT):

```powershell
# Build and publish
./gradlew clean build publish

# This will:
# 1. Build the project
# 2. Sign artifacts with GPG
# 3. Upload to Sonatype staging repository
```

For SNAPSHOT versions:

```powershell
# SNAPSHOTs go directly to snapshots repository (no staging)
./gradlew clean build publish
```

### 9. Release from Staging

After publishing a release version:

1. **Go to Sonatype Nexus**: https://s01.oss.sonatype.org/
2. **Login** with your Sonatype credentials
3. **Navigate to**: "Staging Repositories"
4. **Find your repository**: Look for `comstablemock-XXXX`
5. **Select it** and click "Close"
   - This validates your artifacts
   - Wait for validation to complete (check "Activity" tab)
6. **If validation passes**: Click "Release"
   - Confirm the release
7. **Wait for sync**: Artifacts appear in Maven Central within ~10 minutes
   - Check: https://repo1.maven.org/maven2/com/stablemock/stablemock/

### 10. Verify Publication

Check Maven Central:
- https://repo1.maven.org/maven2/com/stablemock/stablemock/
- https://search.maven.org/artifact/com.stablemock/stablemock

## Troubleshooting

### GPG Signing Issues

**Error: "No secret key"**
- Check `signingKey` in `gradle.properties` includes full key block
- Ensure newlines are preserved (use actual newlines, not `\n`)

**Error: "Bad passphrase"**
- Verify `signingPassword` matches your GPG key passphrase

### Sonatype Authentication Issues

**Error: "401 Unauthorized"**
- Verify `ossrhUsername` and `ossrhPassword` are correct
- Check your Sonatype account is active

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
$env:OSSRH_USERNAME="your-username"
$env:OSSRH_PASSWORD="your-password"
$env:SIGNING_KEY="-----BEGIN PGP PRIVATE KEY BLOCK-----..."
$env:SIGNING_PASSWORD="your-passphrase"
./gradlew publish
```

**Bash:**
```bash
export OSSRH_USERNAME="your-username"
export OSSRH_PASSWORD="your-password"
export SIGNING_KEY="-----BEGIN PGP PRIVATE KEY BLOCK-----..."
export SIGNING_PASSWORD="your-passphrase"
./gradlew publish
```

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

1. **Update README.md** - Change dependency from `1.0-SNAPSHOT` to `1.0.0`
2. **Create git tag**: `git tag v1.0.0 && git push origin v1.0.0`
3. **Create GitHub release** with changelog
4. **Update version** back to `1.0.1-SNAPSHOT` for next development cycle

