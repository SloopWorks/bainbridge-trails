# Shipyard Deploy — owner runbook (dev builds to enrolled phones)

Config: [`app/.shipyard-deploy.yaml`](../../app/.shipyard-deploy.yaml) (project `bainbridge-trails`, two packages, channel
`main` for `main`, `dev-{branch_slug}` otherwise). Agent rules: [`app/AGENTS.md`](../../app/AGENTS.md). Control plane:
the one `shipyard-deploy login` recorded (today `shipyard-deploy-dev.patjackson52.workers.dev`).

## One-time, admin token only (done 2026-10-21; kept for new servers)

`shipyard-deploy onboard --dir app` reported the project does not exist and the publisher token cannot create it. With
`SHIPYARD_DEPLOY_SERVER` and an admin `ADMIN_TOKEN` in the environment:

```sh
# 1. the project
curl -sS -X POST "$SHIPYARD_DEPLOY_SERVER/v1/projects" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'content-type: application/json' -d '{"slug":"bainbridge-trails","display_name":"Bainbridge Trails"}'

# 2. the trails app (debug signing key; fingerprint from `apksigner verify --print-certs`)
curl -sS -X POST "$SHIPYARD_DEPLOY_SERVER/v1/projects/bainbridge-trails/packages" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'content-type: application/json' -d '{"application_id":"com.bainbridgetrails.app.dev","display_name":"Bainbridge Trails (dev)","expected_signer_sha256":["15a066e7bf2507cb0e4d5c24defcc97506eeff19b106cb7f76dfc0e92300876b"]}'

# 3. the roads app (same dev key)
curl -sS -X POST "$SHIPYARD_DEPLOY_SERVER/v1/projects/bainbridge-trails/packages" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'content-type: application/json' -d '{"application_id":"com.bainbridgetrails.roads.dev","display_name":"Bainbridge Roads (dev)","expected_signer_sha256":["15a066e7bf2507cb0e4d5c24defcc97506eeff19b106cb7f76dfc0e92300876b"]}'
```

The fingerprint is this machine's `~/.android/debug.keystore`; another builder adds its own fingerprint with `PATCH
…/packages/{application_id}` (the list is replaced wholesale). Enrolling phones (`shipyard-deploy enroll-code`) and
creating channels by hand also belong to the owner.

## Every publish (agents may run this)

Use the owner's launcher `/Users/patrick/.local/bin/shipyard-deploy-bainbridge` in place of `shipyard-deploy` (it pins the
server and loads the Bainbridge publisher token; the plain CLI's saved login belongs to another project). First publish:
2026-10-21, both apps on channel `main`, version 1.0 (1).

```sh
cd app
./gradlew -PshipyardDeployPluginRepository=file://$HOME/.m2/repository :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug
shipyard-deploy publish androidApp/build/outputs/apk/trails/debug/androidApp-trails-debug.apk --dry-run   # shows the channel
shipyard-deploy publish androidApp/build/outputs/apk/trails/debug/androidApp-trails-debug.apk
shipyard-deploy publish androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk
shipyard-deploy status --json                                                                              # heads per channel and package
```

Without the plugin repository property the APK carries no Shipyard metadata and `publish` refuses it (exit 4). Only the
variants named under `packages:` get the metadata, so a package added to the file needs a rebuild.
