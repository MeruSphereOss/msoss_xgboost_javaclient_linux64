#!/usr/bin/env bash
#
# Uploads an already-built, already-signed release straight to the Central
# Publisher Portal, bypassing central-publishing-maven-plugin entirely.
#
# Use this when `mvn -Prelease deploy` says BUILD SUCCESS but no deployment
# shows up at https://central.sonatype.com/publishing/deployments - the plugin
# staged the artifacts and never uploaded them. Everything it would have sent
# is already sitting in your local repository after that build.
#
# It assembles the Maven-layout bundle zip, generates the .md5/.sha1 files
# Central requires, POSTs it, and polls until the deployment validates.
#
# Usage:
#   ./scripts/publish-bundle.sh 1.0.0                  # upload + wait for VALIDATED
#   PUBLISH=1 ./scripts/publish-bundle.sh 1.0.0        # ...and press Publish for you
#   DRY_RUN=1 ./scripts/publish-bundle.sh 1.0.0        # build the zip, upload nothing
#
# Credentials, in order of precedence:
#   CENTRAL_AUTH=<base64 of user:pass>   pre-encoded bearer value
#   CENTRAL_USER + CENTRAL_PASS          portal *user token*, not your login
#   ~/.m2/settings.xml                   <server><id>central</id> (read automatically)
#
set -euo pipefail

GROUP_ID="${GROUP_ID:-com.merusphere.devops.xgboost}"
ARTIFACT_ID="${ARTIFACT_ID:-xgboost-javaclient-linux64}"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
SETTINGS="${SETTINGS:-$HOME/.m2/settings.xml}"
SERVER_ID="${SERVER_ID:-central}"
API="https://central.sonatype.com/api/v1/publisher"
PUBLISH="${PUBLISH:-0}"
DRY_RUN="${DRY_RUN:-0}"
# Maven 4 also installs a "-build.pom"; Central does not expect it. 1 to keep it.
INCLUDE_BUILD_POM="${INCLUDE_BUILD_POM:-0}"

VERSION="${1:-${VERSION:-}}"

log()  { printf '==> %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ -n "$VERSION" ] || die "usage: $0 <version>    e.g. $0 1.0.0"

# macOS ships shasum/md5, Linux ships sha1sum/md5sum.
if command -v sha1sum >/dev/null 2>&1; then SHA1() { sha1sum "$1" | cut -d' ' -f1; }
else                                        SHA1() { shasum -a 1 "$1" | cut -d' ' -f1; }; fi
if command -v md5sum  >/dev/null 2>&1; then MD5()  { md5sum  "$1" | cut -d' ' -f1; }
else                                        MD5()  { md5 -q "$1"; }; fi

# ------------------------------------------------------------ credentials ---
if [ -z "${CENTRAL_AUTH:-}" ]; then
    if [ -z "${CENTRAL_USER:-}" ] || [ -z "${CENTRAL_PASS:-}" ]; then
        [ -f "$SETTINGS" ] || die "no credentials: set CENTRAL_USER/CENTRAL_PASS or create $SETTINGS"
        log "Reading server '$SERVER_ID' from $SETTINGS"
        if ! command -v python3 >/dev/null 2>&1; then
            # fallback: flatten the XML and pull the <server> block with this id
            creds="$(tr -d '\n\r\t' < "$SETTINGS" \
                | sed 's:<server>:\n<server>:g' \
                | grep "<id>$SERVER_ID</id>" \
                | sed -n 's:.*<username>\(.*\)</username>.*<password>\(.*\)</password>.*:\1\n\2:p' \
                | head -2)"
            [ -n "$creds" ] || die "server id '$SERVER_ID' not found in $SETTINGS (and python3 is unavailable for a proper parse)"
        else
        creds="$(python3 - "$SETTINGS" "$SERVER_ID" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
ns = {'m': root.tag[1:root.tag.index('}')]} if root.tag.startswith('{') else {}
def find(node, path):
    return node.find('/'.join('m:'+p for p in path.split('/')), ns) if ns else node.find(path)
for s in (find(root, 'servers') or []):
    sid = find(s, 'id')
    if sid is not None and sid.text == sys.argv[2]:
        u, p = find(s, 'username'), find(s, 'password')
        if u is not None and p is not None:
            print(u.text.strip()); print(p.text.strip()); break
else:
    sys.exit(1)
PY
)" || die "server id '$SERVER_ID' not found in $SETTINGS"
        fi
        CENTRAL_USER="$(printf '%s' "$creds" | sed -n 1p)"
        CENTRAL_PASS="$(printf '%s' "$creds" | sed -n 2p)"
    fi
    CENTRAL_AUTH="$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASS" | base64 | tr -d '\n')"
fi

# --------------------------------------------------------------- assemble ---
GROUP_PATH="${GROUP_ID//.//}"
SRC="$M2_REPO/$GROUP_PATH/$ARTIFACT_ID/$VERSION"
[ -d "$SRC" ] || die "not in the local repository: $SRC (run 'mvn -Prelease clean install' first)"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/central-bundle.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
DEST="$STAGE/$GROUP_PATH/$ARTIFACT_ID/$VERSION"
mkdir -p "$DEST"

log "Collecting $GROUP_ID:$ARTIFACT_ID:$VERSION from $SRC"
copied=0
for f in "$SRC"/*; do
    b="$(basename "$f")"
    [ -f "$f" ] || continue
    case "$b" in
        _remote.repositories|*.lastUpdated|*.md5|*.sha1|*.sha256|*.sha512) continue ;;
        *-build.pom|*-build.pom.asc)
            [ "$INCLUDE_BUILD_POM" = "1" ] || { warn "skipping Maven 4 artifact $b (Central does not expect it)"; continue; } ;;
    esac
    cp "$f" "$DEST/$b"; copied=$((copied+1))
done
[ "$copied" -gt 0 ] || die "nothing to bundle in $SRC"

# ----------------------------------------------------------- sanity check ---
missing=""
for required in \
    "$ARTIFACT_ID-$VERSION.pom" \
    "$ARTIFACT_ID-$VERSION.jar" \
    "$ARTIFACT_ID-$VERSION-sources.jar" \
    "$ARTIFACT_ID-$VERSION-javadoc.jar"
do
    [ -f "$DEST/$required" ]      || missing="$missing $required"
    [ -f "$DEST/$required.asc" ]  || missing="$missing $required.asc"
done
[ -z "$missing" ] || die "bundle is incomplete, Central will reject it:$missing
  -> re-run 'mvn -Prelease clean install' (the release profile builds javadoc and signs everything)"

# Central wants an .md5 and .sha1 next to every artifact (not needed for .asc).
for f in "$DEST"/*; do
    case "$(basename "$f")" in *.asc|*.md5|*.sha1) continue ;; esac
    MD5  "$f" > "$f.md5"
    SHA1 "$f" > "$f.sha1"
done

BUNDLE="${BUNDLE:-$PWD/target/central-bundle-$VERSION.zip}"
mkdir -p "$(dirname "$BUNDLE")"; rm -f "$BUNDLE"
( cd "$STAGE" && zip -qr "$BUNDLE" . )
log "Bundle: $BUNDLE"
unzip -l "$BUNDLE" | sed -n '4,$p' | awk '{print "    " $4}' | grep -v '^    $' || true

if [ "$DRY_RUN" = "1" ]; then
    log "DRY_RUN=1 - not uploading. Inspect the zip above, then re-run without DRY_RUN."
    trap - EXIT; rm -rf "$STAGE"
    exit 0
fi

# ----------------------------------------------------------------- upload ---
PUBLISHING_TYPE="USER_MANAGED"
[ "$PUBLISH" = "1" ] && PUBLISHING_TYPE="AUTOMATIC"

log "Uploading to the Central Publisher Portal (publishingType=$PUBLISHING_TYPE)"
HTTP_BODY="$(mktemp)"; trap 'rm -rf "$STAGE" "$HTTP_BODY"' EXIT
code="$(curl -sS -o "$HTTP_BODY" -w '%{http_code}' \
    -X POST "$API/upload?name=$ARTIFACT_ID-$VERSION&publishingType=$PUBLISHING_TYPE" \
    -H "Authorization: Bearer $CENTRAL_AUTH" \
    -F "bundle=@$BUNDLE")"

DEPLOYMENT_ID="$(cat "$HTTP_BODY")"
case "$code" in
    201|200) : ;;
    401|403) die "$code from Central - the token is wrong or expired. It must be a *user token* (Portal -> View Account -> Generate User Token), not your login password." ;;
    *)       die "upload failed with HTTP $code: $DEPLOYMENT_ID" ;;
esac
[ -n "$DEPLOYMENT_ID" ] || die "upload returned no deployment id"
log "deploymentId: $DEPLOYMENT_ID"

# ----------------------------------------------------------------- status ---
state=""
for i in $(seq 1 60); do
    resp="$(curl -sS -X POST "$API/status?id=$DEPLOYMENT_ID" -H "Authorization: Bearer $CENTRAL_AUTH")"
    state="$(printf '%s' "$resp" | sed -n 's/.*"deploymentState"[[:space:]]*:[[:space:]]*"\([A-Z_]*\)".*/\1/p')"
    printf '\r    state: %-12s (%ds)' "${state:-?}" "$((i*5))"
    case "$state" in
        VALIDATED|PUBLISHED) echo; break ;;
        FAILED) echo; echo "$resp"; die "validation FAILED - the errors above name the exact file or field. Nothing was published, so version $VERSION is still free to reuse." ;;
    esac
    sleep 5
done
echo

case "$state" in
    PUBLISHED) log "PUBLISHED. It reaches repo1.maven.org within ~10 minutes." ;;
    VALIDATED)
        log "VALIDATED and waiting for you."
        echo "    Press Publish at https://central.sonatype.com/publishing/deployments"
        echo "    or from here:  curl -X POST '$API/deployment/$DEPLOYMENT_ID' -H \"Authorization: Bearer \$CENTRAL_AUTH\""
        ;;
    *) warn "still $state after 5 minutes - check https://central.sonatype.com/publishing/deployments" ;;
esac
