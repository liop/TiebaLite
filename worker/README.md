# TiebaLite AI content analysis worker

This Worker keeps the model credential and prompt policy outside the APK. It currently uses a
Cloudflare Workers AI binding. A later Gemini provider can implement the same JSON response contract
without changing the Android UI.

## Deploy

```bash
npm ci
npm run typecheck
npm run build
npx wrangler secret put APP_TOKEN
npm run deploy
```

`APP_TOKEN` must be stored with `wrangler secret put`; do not add it to `vars` or commit it.

`npm run build` performs a local Wrangler dry run and writes the deployable bundle to `dist/`
without changing the remote Worker.

Then add these values to `~/.gradle/gradle.properties` (or pass them with `-P`):

```properties
AI_ANALYSIS_BASE_URL=https://tiebalite-content-analysis.liop.xyz
AI_ANALYSIS_TOKEN=<the same APP_TOKEN>
```

`APP_TOKEN` only limits casual abuse; a token embedded in an APK is extractable. For a public release,
replace it with user authentication plus server-side rate limiting. Never put a Cloudflare or Gemini
API key in the Android project.
