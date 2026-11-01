#!/bin/sh
# prepares tag releases on github: creates the release keystore once, then stores the four signing
# secrets in the repository. safe to rerun; an existing keystore and its passwords are reused.
#
#   scripts/release-setup.sh             # repository from the git remote
#   REPO=owner/name scripts/release-setup.sh
#
# the keystore and its password live outside the repo, in $BOOSTER_RELEASE_DIR
# (default ~/.local/share/secrets/booster). back that directory up: android only installs
# updates signed with the same key.
set -eu

DIR="${BOOSTER_RELEASE_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/secrets/booster}"
KEYSTORE="$DIR/release.jks"
PASSFILE="$DIR/password"
ALIAS=booster
DNAME="CN=booster"
VALIDITY_DAYS=10000

REPO="${REPO:-$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)}"
if [ -z "$REPO" ]; then
    echo "no github repository found; set REPO=owner/name" >&2
    exit 1
fi
if ! gh repo view "$REPO" --json name > /dev/null 2>&1; then
    echo "github repository $REPO does not exist or is not reachable; create it first, for example:" >&2
    echo "  gh repo create $REPO --private --source . --remote origin" >&2
    exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
if [ -f "$KEYSTORE" ]; then
    echo "reusing keystore $KEYSTORE"
else
    # pkcs12 keystores use one password for the store and the key
    umask 077
    # no trailing newline, so the secret holds exactly the password
    python3 -c 'import secrets; print(secrets.token_urlsafe(32), end="")' > "$PASSFILE"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" \
        -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity "$VALIDITY_DAYS" -dname "$DNAME"
    echo "created keystore $KEYSTORE; back up $DIR"
fi

echo "setting secrets on $REPO"
base64 -w0 "$KEYSTORE" | gh secret set BOOSTER_KEYSTORE_BASE64 --repo "$REPO"
gh secret set BOOSTER_KEYSTORE_PASSWORD --repo "$REPO" < "$PASSFILE"
gh secret set BOOSTER_KEY_PASSWORD --repo "$REPO" < "$PASSFILE"
printf %s "$ALIAS" | gh secret set BOOSTER_KEY_ALIAS --repo "$REPO"
gh secret list --repo "$REPO"
