## Summary
<!-- Describe the change and link any related alpha testing issue -->

## Module Scope
- [ ] `:app`
- [ ] `:core:*` (model / designsystem / database / network / extension-api / extension-loader / anilist)
- [ ] `:feature:*` (home / shelf / browse / detail / reader / downloads / settings)

## Alpha Verification Checklist
- [ ] Unit tests pass (`gradle test testDebugUnitTest`)
- [ ] Android Lint passes (`gradle :app:lintDebug`)
- [ ] Privacy isolation respected (Incognito mode & Local-Only AniList policy)
- [ ] Material 3 Expressive design & edge-to-edge insets preserved
