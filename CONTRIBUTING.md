# contributing

## workflow

- requirements live in `REQUIREMENTS.md`; do not regress them without the
  product owner's approval.
- active work is tracked in `WORKING.md`.
- architecture is described in `DESIGN.md`; update it with structural changes.
- user-visible changes go in `README.md`.
- after visible ui changes, refresh `docs/screenshots/` with `make
  screenshots` (captured from the real launcher on the project emulator).

## build and check

```
make            # debug apks
make test-e2e   # end-to-end suite
make precommit  # build, android lint, ruff check and format check
```

## releases

pushing a tag like `v0.1.0` runs `.github/workflows/release.yml`: it runs
`make precommit`, builds the release apk with that version (`make release
VERSION=0.1.0`), signs it, and publishes `booster-v0.1.0.apk` and its sha256
as a github release. the version code is `major * 10000 + minor * 100 +
patch`, so minor and patch stay below 100.

signing needs four repository secrets: `BOOSTER_KEYSTORE_BASE64` (the
keystore file, `base64 -w0 release.jks`), `BOOSTER_KEYSTORE_PASSWORD`,
`BOOSTER_KEY_ALIAS` and `BOOSTER_KEY_PASSWORD`. without them the workflow
fails rather than publish an unsigned apk. `make release-setup` does this
once: it creates the keystore and a random password in
`~/.config/booster/release` (reused on later runs) and sets the secrets with
`gh` on the repository of the git remote, or `REPO=owner/name`. locally, `make release` signs when
`BOOSTER_KEYSTORE` and the same password and alias variables are set, and
otherwise leaves the apk unsigned.

keep the keystore safe: android only installs updates signed with the same
key.

## code

- kotlin with plain android views. no androidx, no compose, no new
  dependencies without a strong reason.
- layout changes are pure functions in `Model.kt`; keep side effects in the
  activity.
- every size comes from `Scale` (golden ratio ladder) and every color from
  `Palette`. no literal dp, px or color values in views.
- strings go in `res/values/strings.xml`, lowercase.
- ascii only in code, comments and docs.

## tests

- prefer end-to-end tests in `e2e/` (python, standard library only).
- bug fixes start with a failing test.
- find views by content description or resource id (`res/values/ids.xml`)
  and assert effects through the saved layout json.
- never run bare `adb`; use `e2e/device.py`, which pins the project's adb
  server, serial and emulator.
