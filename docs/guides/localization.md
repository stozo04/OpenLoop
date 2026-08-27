# Localization — how OpenLoop ships in more than English

> Source: [New tools and programs to accelerate your growth on Google Play](https://android-developers.googleblog.com/2025/10/new-tools-and-programs-to-accelerate.html)
> (Android Developers Blog, 30 Oct 2025).

## The short version

Play Console can translate OpenLoop's UI into 100+ languages **at no cost**, using Gemini, with no
code, no build change, and no APK-size cost. It does that by reading `strings.xml` out of each
uploaded app bundle, generating the localized resources, and injecting them back into the bundle
Play serves.

The catch is the input. Play reads **`strings.xml`** — nothing else. A user-facing string written as
a Kotlin literal inside a composable is invisible to the service and stays English in every locale,
forever, silently. So in this repo:

> **A hardcoded user-facing string is a shipping bug, not a style nit.**

## What this repo did about it

Before the localization-readiness pass, ~55 user-facing strings lived as Kotlin literals — screen
titles, dialog copy, every button label on the editor toolbar, and most `contentDescription`s (which
are what TalkBack speaks). All of them are now resources. The two patterns that came up:

**1. Literal inside a composable → `stringResource`.** The ordinary case.

```kotlin
Text(text = stringResource(R.string.trim_title))
```

**2. Literal in a non-composable scope → hold the resource *id*, resolve at the call site.**
`stringResource()` is `@Composable`; a `Modifier.semantics {}` lambda, a top-level `val` table, and
an `enum class` body are not composable scopes. Hoist a `val` in composable scope and capture it:

```kotlin
// Hoisted: stringResource needs a composable scope, and the semantics {} block below is not one.
val saveLabel = stringResource(R.string.editor_save)
Box(modifier = Modifier.semantics { contentDescription = saveLabel })
```

For data held outside the UI — `EditorLoadingKind`, `LoopModeChip`, `OnboardingPage` — the field is
an `@param:StringRes Int`, never a `String`. That is the same rule as
[lesson 004](../lessons_learned/004-viewmodel-no-context-parameters.md) (no `Context` in the state
layer) arriving from a different direction: the state layer names the copy, the composable resolves
it.

**When the string interpolates a live value** that must stay a deferred read
([lesson 016](../lessons_learned/016-compose-defer-high-frequency-state-reads.md)), `stringResource`
can't be used inside the lambda. Capture a `Context` instead and call `getString(id, arg)` — it
resolves against the configuration's locale, so the format string still comes from `strings.xml`:

```kotlin
val context = LocalContext.current
Modifier.semantics { contentDescription = context.getString(R.string.camera_zoom_level_content_description, text()) }
```

## What is deliberately *not* translated

| Thing                                                | Why                                                                                                                |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `app_name`, `share_subject`                          | Brand nouns — marked `translatable="false"` so Gemini leaves them alone                                            |
| `VideoFilter.label` (`B&W`, `Warm`, `Pop`, …)        | Product-style look names, like Instagram's filters. Also keeps the diff out of the JVM-pure `media/` package       |
| `Lens.displayName` (`Broccoli`, `Twisted Tongue`, …) | Same — lens names are product identity, and `camera/lens/Lens.kt` is the one file that names them                  |
| Debug-report **subject** lines                       | They route a support mail to the maintainer; they are not app UI. The chooser *title* beside them **is** localized |
| Numeric readouts (`2.30s`, `2.3x`, `48%`)            | Values, not copy. The phrasing around them (`"%1$s times speed"`) is a resource                                    |

## Turning translation on (Play Console — owner action, not a code change)

1. **Grow users → Translations → App strings → Get started.**
2. **Add languages** — pick the target locales. Play manages every selected language from then on.
3. Upload a bundle to a draft release. Translations are generated and merged into that bundle
   automatically; nothing in the repo changes.
4. Preview in Play Console's built-in emulator, and **edit or disable** any translation before it
   goes live. Review is not optional — an auto-translated CTA can come back longer than the button.

Not available in Armenian, Raeto-Romance, Tagalog, or Zulu.

**Before enabling any RTL locale (Arabic, Hebrew, Persian, Urdu):** run the Samsung RTL lane in
[`samsung-rtl-steps.md`](samsung-rtl-steps.md). Play's own guidance is that selecting an RTL language
requires the app to be RTL-ready — layout mirroring is on the app, not the translator.

## Deliberately deferred

- **`android:localeConfig` / AGP `generateLocaleConfig`** (the Android 13+ per-app language picker).
  Skipped on purpose: the locale list would be generated **at build time**, when the bundle still
  contains only `values/`. Play injects the translations *after* that, so a generated config would
  advertise English only. Revisit once translations are live and the shipped locale set is known.
- **`resourceConfigurations`** — same reason; it would strip the injected locales.

## Checklist for any new UI string

- [ ] It is in `app/src/main/res/values/strings.xml`, not a Kotlin literal.
- [ ] `contentDescription`s count — TalkBack copy is user-facing copy.
- [ ] Interpolated values use a positional placeholder (`%1$s`), never string concatenation — word
      order changes between languages.
- [ ] Counts use `<plurals>`, not `"$n loops"` (see `gallery_loops_deleted`).
- [ ] Brand nouns get `translatable="false"`.

## Reference

- [Translate and localize your app](https://support.google.com/googleplay/android-developer/answer/9844778) — Play Console Help
- [Localize your app](https://developer.android.com/guide/topics/resources/localization) — Android developer guide
- [Support different languages and cultures](https://developer.android.com/training/basics/supporting-devices/languages)
