# Google sign-in setup (Android)

Status: **pending**. The OAuth clients have not been created yet. Fill in the table below when they are.

## What exists where

| Item | Value | Stored in | Secret? |
|---|---|---|---|
| Google Cloud project | _TBD_ | this doc | no |
| OAuth consent screen | External, **Testing**; test user `patjackson52@gmail.com` | Cloud Console | no |
| Web client ID (the `aud` of ID tokens) | _TBD_ | `app/gradle.properties` → `googleWebClientId`; `backend/wrangler.jsonc` → `GOOGLE_CLIENT_IDS` | no (public identifier) |
| Android client, debug/dev | package `com.bainbridgetrails.app.dev`, SHA-1 `E2:3C:BB:C8:93:30:EB:66:CA:73:02:B3:7B:BA:6F:D9:3B:79:33:A2` (debug keystore on the build Mac; also signs Shipyard dev builds) | Cloud Console only | no |
| Android client, release | package `com.bainbridgetrails.app`, SHA-1 of the release key (or of the Play App Signing key) | Cloud Console only | no |
| Web client **secret** | not used: Credential Manager and the Worker only need the client ID | **never commit**; if it is ever needed, `~/.config/bainbridge-trails/google-web-client-secret` (0600) | **yes** |

Client IDs are public: they ship inside the APK and appear in every ID token. Committing them is normal.
The only secret Google creates is the web client secret, and this app doesn't use it.

## How sign-in flows

1. Android Credential Manager (`GetSignInWithGoogleOption(serverClientId = webClientId)`) returns a Google ID
   token whose `aud` is the **web** client ID. Google only issues it if the calling app's package and SHA-1 match
   an Android OAuth client in the **same project**.
2. The app sends the ID token to `POST /v1/auth/google`. The Worker checks the signature (Google JWKS), `iss`,
   `exp`, and `aud ∈ GOOGLE_CLIENT_IDS`. It stores only the `sub`, then returns its own session token.

## Create the clients (Cloud Console)

1. Pick or create the project (e.g. `bainbridge-trails`), then go to **APIs & Services → OAuth consent screen**:
   - user type External, app name "Bainbridge Trails", support email
   - scopes: `openid`, `email`, `profile` (defaults only)
   - status **Testing**; add test users
2. **Credentials → Create credentials → OAuth client ID → Web application**, named "Bainbridge Trails server".
   No origins or redirects. Copy the client ID.
3. **Create credentials → OAuth client ID → Android**:
   - package `com.bainbridgetrails.app.dev`
   - SHA-1 above
4. When a release key exists, add a second Android client for `com.bainbridgetrails.app` with its SHA-1. With Play
   App Signing, use the SHA-1 Play Console shows under **App integrity**.
5. Before a public launch, move the consent screen to **In production**. With only basic scopes, Google doesn't
   require verification.

## Wire it in

```sh
# app: public client id, committed
echo "googleWebClientId=<WEB_CLIENT_ID>" >> app/gradle.properties
# backend: accepted audiences (comma list), committed in wrangler.jsonc vars
#   "GOOGLE_CLIENT_IDS": "<WEB_CLIENT_ID>"
cd backend && npm run deploy
```

## Verify

- `./gradlew :androidApp:assembleDebug`, install, then Profile → **Continue with Google** (as a test user).
  You should be signed in, the backfill sync should return 200, and you should appear on the leaderboard after
  `POST /v1/admin/snapshot` (admin token in `~/.config/bainbridge-trails/admin-token`).
- `npx wrangler tail` shows `POST /v1/auth/google 200`.
- If you get `[16] Account reauth failed` or `No credentials available`, the package or SHA-1 doesn't match
  an Android client, or the account isn't a test user.
