# TiebaLite AI content analysis worker

This Worker keeps the model credential and prompt policy outside the APK. It currently uses a
Cloudflare Workers AI binding. A later Gemini provider can implement the same JSON response contract
without changing the Android UI.

## Deploy

```bash
npm ci
npm run typecheck
npm run build
npm run deploy
```

`npm run build` performs a local Wrangler dry run and writes the deployable bundle to `dist/`
without changing the remote Worker.

The Android app uses the production Worker URL by default. To override it, add this value to
`~/.gradle/gradle.properties` (or pass it with `-P`):

```properties
AI_ANALYSIS_BASE_URL=https://tiebalite-content-analysis.liop.xyz
```

The content-analysis endpoint is public and does not require an Authorization header. Keep provider
credentials and model configuration in the Worker environment; never put Cloudflare or Gemini API
keys in the Android project.
