# Releasing `xgboost-javaclient-linux64` to Maven Central

Coordinates: `com.merusphere.devops.xgboost:xgboost-javaclient-linux64`
Portal: <https://central.sonatype.com> (Central Publisher Portal — the OSSRH
`oss.sonatype.org` staging UI is retired, don't look for it).

Publishing happens from a **Linux x64 box with `libxgboost.so` installed**,
because `mvn -Prelease deploy` compiles and runs the tests. The bindings in
`src/generated/java` are committed, so no jextract run is needed for a release.

---

## Part 1 — one-time setup (per machine / per person)

### 1.1 Namespace

`com.merusphere.devops` is **already a verified namespace** on the Portal.
Verification covers every subgroup, so `com.merusphere.devops.xgboost` is
publishable as-is — nothing to register, no DNS TXT record, no new ticket.

### 1.2 Portal user token

Portal → click your username (top right) → **View Account** → **Generate User
Token**. You get a `<username>` / `<password>` pair — *not* your login
credentials, and it is shown only once.

Put it in `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>central</id>                       <!-- matches publishingServerId in pom.xml -->
      <username>PASTE_TOKEN_USERNAME</username>
      <password>PASTE_TOKEN_PASSWORD</password>
    </server>
  </servers>

  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
        <gpg.passphrase>YOUR_KEY_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>gpg</activeProfile></activeProfiles>
</settings>
```

`chmod 600 ~/.m2/settings.xml`. In CI, inject the token as
`-Dcentral.username`/`-Dcentral.password`-style secrets instead of a file.

### 1.3 GPG signing key

Central rejects anything unsigned.

```bash
sudo apt-get install -y gnupg
gpg --full-generate-key            # RSA 4096, no expiry (or 2y and remember to renew)

gpg --list-secret-keys --keyid-format=long     # note the long key id, e.g. A1B2C3D4E5F6A7B8
gpg --keyserver keyserver.ubuntu.com --send-keys A1B2C3D4E5F6A7B8
gpg --keyserver keys.openpgp.org    --send-keys A1B2C3D4E5F6A7B8
```

The Portal validator fetches the public key from those keyservers — if it isn't
published, validation fails with *"No public key"*. Propagation takes a few
minutes.

Back up the private key (`gpg --export-secret-keys --armor A1B2... > key.asc`)
somewhere safe and offline. Losing it doesn't break published artifacts, but you
would have to publish new releases under a new key.

---

## Part 2 — cutting a release

### Step 0 — preflight

```bash
cd ~/git/msoss_xgboost_javaclient_linux64
git status                          # clean tree
ls src/generated/java/com           # bindings committed and present
java -version                       # 25.x
ls /opt/xgboost/lib/libxgboost.so   # native library present
./scripts/selfcheck.sh              # 36 checks, no Maven needed
mvn -B clean verify -Dxgboost.library.path=/opt/xgboost/lib
```

Nothing gets signed until the tests are green.

### Step 1 — set the version

Central versions are **immutable**: once `1.0.0` is published it can never be
replaced, only superseded. Never publish `-SNAPSHOT` (the Portal rejects it).

```bash
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
```

Then update the version references in `README.md` (Maven/Gradle snippets, jar
filenames) and in `scripts/build-without-maven.sh` so the docs and the offline
build match what's on Central.

### Step 2 — build, sign and upload

```bash
mvn -Prelease clean deploy -Dxgboost.library.path=/opt/xgboost/lib
./scripts/publish-bundle.sh 1.0.0
```

The second line is a safety net: `central-publishing-maven-plugin` sometimes
reports BUILD SUCCESS having staged the artifacts without uploading them.
`publish-bundle.sh` assembles the same bundle from `~/.m2/repository`, adds the
`.md5`/`.sha1` files Central wants, POSTs it and polls until it validates. If
the plugin already uploaded, you will see the deployment twice on the Portal —
drop the extra one.

Check the bundle first if you want:

```bash
DRY_RUN=1 ./scripts/publish-bundle.sh 1.0.0
```

### Step 3 — publish from the Portal

`autoPublish` is **false**, so the deployment stops in a `VALIDATED` state and
waits for you.

1. <https://central.sonatype.com/publishing/deployments>
2. Find the deployment (named
   `com.merusphere.devops.xgboost:xgboost-javaclient-linux64:1.0.0`).
3. `VALIDATING` → `VALIDATED` usually takes under a minute. If it goes to
   `FAILED`, expand it — the message names the exact missing field or bad
   signature. Fix, re-run step 2; nothing was published, so the version is still
   free to reuse.
4. Press **Publish**. State goes `PUBLISHING` → `PUBLISHED`.

`PUBLISH=1 ./scripts/publish-bundle.sh 1.0.0` skips the button. Keep it manual
until the first release has gone through cleanly.

### Step 4 — verify it landed

`repo1.maven.org` normally has it within ~10 minutes; the search index can lag a
few hours (that lag is cosmetic).

```bash
curl -sI https://repo1.maven.org/maven2/com/merusphere/devops/xgboost/xgboost-javaclient-linux64/1.0.0/xgboost-javaclient-linux64-1.0.0.jar | head -1

# real consumer test, from an empty local repo:
mvn -q dependency:get \
  -Dartifact=com.merusphere.devops.xgboost:xgboost-javaclient-linux64:1.0.0
```

Then the end-to-end check on a clean Linux VM — exactly what a user of the
library does:

```bash
./scripts/01-install-prereqs-ubuntu.sh    # installs libxgboost.so under /opt/xgboost/lib
./scripts/run-demo.sh
```

### Step 5 — tag and announce

```bash
git commit -am "release 1.0.0"
git tag -a v1.0.0 -m "xgboost-javaclient-linux64 1.0.0"
git push origin main --tags
```

Optionally open a GitHub release on the tag.

### Step 6 — open the next version

```bash
mvn versions:set -DnewVersion=1.0.1-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "back to snapshot"
```

(Snapshots are not published to Central from this project — the version bump is
just bookkeeping so nobody builds an artifact that collides with a release.)

---

## Subsequent releases — the short version

```bash
mvn -B clean verify -Dxgboost.library.path=/opt/xgboost/lib     # green
mvn versions:set -DnewVersion=1.1.0 -DgenerateBackupPoms=false
# update README + build-without-maven.sh version strings
mvn -Prelease clean deploy -Dxgboost.library.path=/opt/xgboost/lib
./scripts/publish-bundle.sh 1.1.0
# https://central.sonatype.com/publishing/deployments -> Publish
git tag -a v1.1.0 -m "xgboost-javaclient-linux64 1.1.0" && git push --tags
```

---

## What Central requires, and where this project provides it

| Requirement | Where |
|---|---|
| `groupId`, `artifactId`, `version`, `packaging` | `pom.xml` top block |
| `name`, `description`, `url` | `pom.xml` |
| `<licenses>` | `pom.xml` — Apache 2.0 |
| `<developers>` | `pom.xml` |
| `<scm>` connection/developerConnection/url | `pom.xml` |
| `-sources.jar` | `maven-source-plugin`, default build |
| `-javadoc.jar` | `maven-javadoc-plugin`, `release` profile |
| `.asc` signature per file | `maven-gpg-plugin`, `release` profile |
| Upload | `central-publishing-maven-plugin` / `scripts/publish-bundle.sh` |

---

## Things that bite

* **Immutability.** A published version is permanent. A bad `1.0.0` is fixed by
  releasing `1.0.1`, never by re-uploading.
* **Missing javadoc.** The generated `...linux64.capi` package is excluded from
  javadoc on purpose; if you remove that exclusion, expect a wall of warnings
  from jextract output. `doclint` is already off.
* **`gpg: signing failed: Inappropriate ioctl for device`** — headless shell
  without a pinentry. The pom already passes `--pinentry-mode loopback`; make
  sure `gpg.passphrase` is set (settings.xml or `-Dgpg.passphrase=`).
* **`401 Unauthorized` on deploy** — the `<server><id>` in settings.xml must be
  exactly `central`, and the token must be a *user token*, not your password.
* **`No public key`** during validation — the key wasn't pushed to a keyserver,
  or hasn't propagated yet. Wait a few minutes and re-deploy.
* **Tests fail during `deploy` because libxgboost.so isn't found.** The release
  profile still runs surefire. Pass
  `-Dxgboost.library.path=/opt/xgboost/lib`, or `-DskipTests` if you have
  already run them (not recommended for a release).
* **Deploying from macOS.** The upload itself works, but you would be signing a
  jar whose tests never ran against a Linux `libxgboost.so`. Use the Linux build
  box for anything you sign.
