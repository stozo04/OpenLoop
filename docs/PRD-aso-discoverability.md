# PRD — ASO & Discoverability

**Status:** Research complete, awaiting sign-off
**Author:** Research swarm (4 agents), August 2026
**Owner action required:** Yes — most of the high-impact work happens in Play Console, not in this repo.

---

## 1. Problem statement

Typing `boom` or `OpenLoop` into Google Play does not surface the app. The user must spell out the
full title to find it.

Research confirms the symptom and identifies two **separate** causes that are often conflated:

1. **`OpenLoop` fails because the brand token is not in the app title.** It lives only in the
   developer name — the weakest field Play indexes. This is cheap to fix.
2. **`boom` fails because the app has ~10 installs.** Play ranks on installs, ratings, retention and
   engagement. No metadata change beats Supercell and Boomplay from a standing start. This is not
   fixable by copywriting, and anyone who says otherwise is selling something.

Two unrelated problems were found during the investigation and are **higher priority than the ASO
work**: a store-listing accuracy contradiction, and a live trademark exposure. Both are in §3.

---

## 2. Evidence baseline (measured 2026-08-09)

All Play data below came from **unauthenticated server-side fetches**, so these are clean
signed-out samples, not personalised results.

**Provenance matters here, so it is labelled per row.** Play listing pages are JavaScript-rendered
and do not survive text extraction, so some fields could only be read from a third-party mirror
(apkcombo). That mirror **demonstrably mangled one field during this research** — it concatenated the
developer name into the title and returned `OpenLoop Boomerang Video Maker`, which the Play search
results disproved. Treat every ⚠️ row below as *indicative, not verbatim*.

- ✅ = read directly from `play.google.com` (listing page or search results)
- ⚠️ = read from the apkcombo mirror — **verify verbatim in Play Console before acting**

### 2.1 Live listing vs. repo source-of-truth — they have drifted

| Field | `docs/play-store/store-listing.md` (repo) | **Live on Play** | Source |
|---|---|---|---|
| Title | `OpenLoop: Video Loop Maker` | **`Boomerang Video Maker`** (21 chars) | ✅ |
| Developer name | — | **`OpenLoop`** | ✅ |
| Short description | `Speed-controlled video loops. 100% on-device. No ads, no signup, open source.` | **`Ad Free and Open Source Boomerang Loop Video Maker`** | ⚠️ |
| Full description | Discloses Firebase Analytics/Crashlytics | **Claims "no tracking"** | ⚠️ |
| Version | 1.0.36 | **1.0.25 (Jun 23, 2026)** | ⚠️ |
| Installs | — | **10+** | ⚠️ |
| Ratings | — | **None** | ⚠️ |

> The repo doc is **not** what is live. Neither document is obviously "right" — the owner must decide
> which is the source of truth and reconcile. See §3.1.
>
> **First Console action should be to read the four ⚠️ fields verbatim** and correct this table. The
> title and developer name — the two fields the entire ASO diagnosis rests on — are ✅ and confirmed
> by two independent `play.google.com` fetches, so §4 and §5 do not depend on the mirror.

### 2.2 Search rank baseline

| Query | Rank | Notes |
|---|---|---|
| `openloop` | **Absent from top 27** | #1 is `Openloop Connect` (Haudi Crypto); #27 is by `OpenLoop Health, Inc.` |
| `openloop boomerang` | **#1** | Proves the listing **is** indexed and available in the US |
| `boom` | **Absent from top 25** | Owned by Ultimate Ears, Boom bass booster, Supercell, Boomplay |
| `boomerang video maker` | **#7** | **#4 is a *different* app with the identical title**, by Tiger Lily Technologies |

Two conclusions follow directly:

- **The app is indexed.** `openloop boomerang` at #1 rules out every Google-documented cause of
  invisibility — wrong track, country availability, device exclusion, indexing delay. This is a
  ranking-weight deficit, not a visibility defect.
- **The title is not merely generic, it is not unique.** An exact-title twin outranks it.

---

## 3. Priority-zero findings (not ASO — fix these first)

### 3.1 The live listing contradicts the app's own data-safety declaration

The live full description says *"No cloud uploads, no accounts, **no tracking**"* — ⚠️ **mirror-sourced,
verify verbatim in Console.** `README.md` says *"**Zero network calls. Zero tracking.**"* — ✅ **verified
in this repo**, and false on both counts.

> The repo half of this contradiction is confirmed regardless of what the live listing says, so this
> finding stands on its own. Confirming the Console wording only determines whether **one** surface
> needs fixing or **two**.

But [`docs/play-store/data-safety.md`](play-store/data-safety.md) declares, as collected: **App
interactions, Approximate location, Device or other IDs, Crash logs, Diagnostics** — via Firebase
Analytics and Crashlytics. That file states explicitly that the earlier "no data collected"
declaration is *"no longer accurate and must not be re-used."*

The repo's own `store-listing.md` gets this right and discloses Firebase. **The live listing is
running copy that the repo already corrected.**

Play's [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842)
prohibits "misleading … metadata," and a store listing that denies telemetry the Data safety form
declares is a direct inconsistency between two Console surfaces.

**Action:** reconcile the live description, the README, and `data-safety.md` to one truthful claim.
The honest framing already exists in `store-listing.md`: video never leaves the device; limited
pseudonymous usage/crash diagnostics do.

### 3.2 Trademark exposure on the current title

**USPTO Reg. No. 5030673 — mark `BOOMERANG`, owner Instagram, LLC. LIVE, International Class 009.**
Goods: *"Downloadable computer software for modifying video appearance and enabling transmission of
video content as part of a mobile application."*
([USPTO TSDR, Serial 86868955](https://tsdr.uspto.gov/statusview/sn86868955))

That goods description is a near-verbatim description of this app, in the same class.

The timeline is what makes this material. Meta removed the standalone Boomerang app from Play on
**1 March 2022** ([TechCrunch](https://techcrunch.com/2022/03/07/instagrams-boomerang-and-hyperlapse-apps-disappear-from-app-stores/));
`play.google.com/store/apps/details?id=com.instagram.boomerang` now returns **HTTP 404**. Thirteen
months later, Meta filed a combined **Sections 8 and 15 declaration, accepted 2023-04-10**. Section 15
renders the registration **incontestable**. They hardened the mark in this exact class *after* killing
the product.

Play's [Intellectual Property policy](https://support.google.com/googleplay/android-developer/answer/9888072)
defines infringement as "improper or unauthorized use of an identical or similar trademark in a way
that is likely to cause confusion as to the source of that product," and states: "your app **may be
suspended**." Enforcement is one rights-holder complaint form away.

**How the market prices this risk:** competitor Sarafan Mobile registered **`BOOM LOOP`, Class 009**
([Reg. 8169612](https://tsdr.uspto.gov/statusview/rn8169612)) *instead of* using "Boomerang", and
ships an explicit disclaimer: *"BoomLoop is an independent app and is not affiliated with, endorsed
by, or associated with Instagram, Reels, or Boomerang."* A competitor sophisticated enough to
prosecute a mark to registration deliberately branded around the word.

**Risk verdict: low probability, high severity, badly asymmetric.** Five of eight direct competitors
use "Boomerang" in-title and are tolerated, so enforcement is clearly not systematic. But the current
title is **the bare mark, unmodified, in first position** — the least defensible configuration
available. Competitors at least qualify it (`Boomerit Boomerang…`, `Clip Loop: Boomerang Video`).

**Recommendation: do not strip the word** — it is the only relevance anchor that currently ranks, and
removing it forfeits the #1 position on `openloop boomerang`. **Do** stop using it as the app's
standalone name. A title where a coined brand carries the identity and "Boomerang" is descriptive is
materially better posture at zero cost.

> **Not legal advice.** If the owner wants certainty rather than a risk assessment, this is the one
> item worth an hour of a trademark attorney's time.

### 3.3 Minor: "Ad Free" in the short description is grey-area

The live short description opens `Ad Free and Open Source…` (⚠️ mirror-sourced — confirm in Console
before acting on this item). Google's
[store-listing best practices](https://support.google.com/googleplay/android-developer/answer/13393723)
say "Words like 'Free' and 'No Ads' promote deals and don't belong in **app titles**," and the
Metadata policy bans "text that indicate price and promotional information" — but every example given
is a *time-limited* promo ("free for limited time only," "10% off").

A permanently free, genuinely ad-free app stating a durable fact is not obviously the prohibited case.
**Assessment: grey area, low risk, not urgent.** Safer phrasing expresses the same value as
functionality — "no ads, ever" reads as a promo; "works offline, no account" does not.

---

## 4. Research findings

### 4.1 How Play search actually ranks — and why the developer name can't save you

Google confirms which text fields feed search, in exactly one sentence
([App visibility and discovery issues](https://support.google.com/googleplay/android-developer/answer/9042516)):

> "Google Play search takes multiple factors into account, such as **app titles, developer names, and
> app descriptions**."

This is the **only** official confirmation that the developer name is indexed at all. Google publishes
no weighting — [Get discovered on Google Play search](https://support.google.com/googleplay/android-developer/answer/4448378)
states the weights are "a **proprietary part of the Google search algorithm**."

Tellingly, of five major ASO vendor guides surveyed, **two do not list the developer name as an
indexed field at all**. Industry consensus ranks the fields: **title (highest) → short description →
full description**, with the developer name a weak tiebreaker or absent entirely.

**Why the two observed queries diverge:**

| Query | Mechanism |
|---|---|
| `openloop boomerang` → #1 | `boomerang` matches the **title** (highest weight), collapsing the candidate pool to a handful of apps. `openloop` then disambiguates against the developer name. A weak-field match is decisive when the pool is tiny. |
| `openloop` → absent | **Zero** title match, zero short-description match, zero full-description match. The only matching field is the weakest one, and the candidate pool is now everything Play's NLP considers semantically near `open` + `loop`. |

Play uses **semantic/NLP matching, not literal string matching**, so the unspaced compound `openloop`
plausibly decomposes toward two extremely generic English words. There is no separate keyword field on
Play — the indexed corpus is title + short description + full description.

**There is no new-app sandbox.** No Google source — Play Console Help, developer.android.com, Play
Academy, the Android Developers Blog — documents a cold-start penalty, probation, or ranking
suppression for new apps. Waiting fixes nothing; there is no clock running.

**Published quality gates that do bite:**

[Android vitals](https://developer.android.com/topic/performance/vitals) (updated 2026-05-19) — exceed
these and "Play **may reduce the visibility of your title**":

| Metric | Overall | Per phone model |
|---|---|---|
| User-perceived crash rate | **1.09%** | 8% |
| User-perceived ANR rate | **0.47%** | 8% |

Checked daily on a **28-day rolling average**, and evaluated **across all users regardless of app
version** — you cannot escape a bad history by shipping a new build. A new **partial wake lock**
threshold (>5% of battery sessions exceeding 3h) has affected store visibility since **1 March 2026**,
which is directly relevant to this app's WorkManager + foreground-service render pipeline.

[User metrics](https://developer.android.com/quality/core-value/user-metrics) (updated 2026-03-06) —
the bars for quality treatments: **user loss rate <5%**, **DAU/MAU >8%**, and engagement over a
**minimum of 24 days in a 30-day period**.

> **The compounding trap:** at 10 installs the app cannot satisfy "sufficient user base," so it is
> ineligible for quality treatments and Play surface placements — which are themselves the mechanism
> that would generate installs. No installs → no treatments → no impressions → no installs.

### 4.2 Metadata: field limits, policy, and the suspension tripwire

| Field | Limit | Indexed? | Weight |
|---|---|---|---|
| App title | **30** | Yes | **Highest** |
| Short description | **80** | Yes | Second |
| Full description | **4,000** | Yes | Third |
| Developer name | Not documented | Officially yes, practically marginal | Unknown |
| Tags | **Max 5** | Not stated | Unknown |

Character limits apply identically to full-width and half-width characters, so CJK localisations get
the same budget, not a scaled one.

**The rule that carries suspension, not a ranking haircut**
([Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152)):

> "Repetitive or irrelevant use of keywords in the app name, description, or promotional description
> can create an unpleasant user experience and **result in an app being suspended on Google Play**."

**Google's own title guidance contradicts the current title.** From
[Get discovered on Google Play search](https://support.google.com/googleplay/android-developer/answer/4448378):

> "Your title should be **unique** and accessible, **avoid common terms**, and reinforce what your app
> is about."

`Boomerang Video Maker` is three common terms and zero unique tokens — and, as measured in §2.2, is
literally shared with another live app.

**Also banned in metadata:** emoji/emoticons/repeated special characters in title, icon or developer
name; ALL CAPS unless part of the brand; ranking claims ("#1", "App of the year", award icons); Play
program claims ("Editor's Choice"); price/promo text; unattributed user testimonials.

**Operationally important constraints:**

- **The title is NOT natively A/B testable.**
  [Store listing experiments](https://support.google.com/googleplay/android-developer/answer/12053285)
  cover icon, feature graphic, screenshots, and **descriptions — but text is testable only in
  *localized* experiments**, never in default graphics experiments. A title change must be shipped and
  monitored. Concurrency: one default graphics experiment **or** up to five localized experiments.
  Experiments auto-stop after 6 months.
- **Listing edits trigger review** — "a few hours or up to seven days (or longer in exceptional
  cases)." Submitting new changes while others are in review **pushes you to the back of the queue**.
  [Batch every edit into one submission.](https://support.google.com/googleplay/android-developer/answer/9859654)
  Use **managed publishing** so the change lands when you can watch it.
- **No official rule limits how often a title may be changed.** This was searched for specifically; no
  cooldown exists in any Google source.
- **Custom store listings** (up to 50) can target **Play Search keywords** — but they change *what a
  searcher sees*, not *what ranks*. They improve conversion on keywords you already rank for; they do
  not create ranking.
- **Promotional content / LiveOps** requires apps to meet **"Premium growth tools"** criteria (all
  games qualify automatically; apps do not). Almost certainly closed at this size — but costs zero
  minutes to check in Console.
- **Pre-registration is ruled out** — it requires the app to be *unavailable* in the target country.
  This app is live.

### 4.3 Brand collision — `OpenLoop` is contested, and the split matters

Four distinct entities plus a generic-language problem:

| Entity | Presence |
|---|---|
| **OpenLoop Health, Inc.** (Des Moines, IA) | Holds **USPTO Reg. 6494802, `OPENLOOP`, Class 035** (medical staffing). **Nine of ten** Google autocomplete slots for `openloop`. On Play as `Agile Telehealth`. |
| **Haudi Crypto, Inc.** (Japan) | `Openloop Connect` — hardware crypto wallet companion. **Currently #1 on Play for `openloop`.** |
| **OpenLoop** (New Zealand) | `openloop.co.nz` — nationwide EV charging, live consumer app. |
| Generic usage | "Open loop" is standard in control systems and open-loop payments. No owner to out-compete. |

**The verdict splits, and collapsing it into one answer is the common mistake:**

- **On the open web: permanently lost, and worthless.** Four-way collision on top of a common technical
  phrase. Anyone typing `openloop` into Google wants a telehealth job or a control-systems explainer.
  **Do not spend an hour on it.**
- **On Play: winnable and cheap.** The three Play competitors are a crypto wallet, a telehealth app,
  and an EV charger. None is a consumer media app; none optimises for a general audience.

**Critically, there is no legal blocker.** OpenLoop Health's mark is **Class 035 (services)**, not
Class 009 (software). A boomerang camera app is not plausibly confusable with a medical staffing
agency. **The OpenLoop brand is not legally blocked — it is attentionally blocked.** Different
problems, different fixes.

**Size the prize honestly:** `openloop` is a near-zero-volume consumer query for a camera app. Winning
it acquires almost nobody new. What it fixes is a **conversion leak** — every person who hears the
name from GitHub, a forum, or a friend, searches Play, finds a crypto wallet, and gives up. Plug the
leak. Do not call it growth.

### 4.4 Competitor title teardown — the pattern

| Title | Chars | Structure |
|---|---|---|
| `Boomerit Boomerang Video Maker` | **30** | coined brand + full category string |
| `Boom Loop Video Maker Infinity` | **30** | coined brand + category + modifier |
| `LoopyClip, Boomerang Videos` | 27 | coined brand + category |
| `Boomerang Video & GIF Maker` | 27 | pure category, no brand |
| `Boomerang Loop Video Maker` | 26 | pure category, no brand |
| `Clip Loop: Boomerang Video` | 26 | coined brand + category |
| `Loop Video Maker - Loopiq` | 25 | **category first**, brand last |
| `Zoomerang - Ai Video Maker` | 25 | **brand first**, category |
| **`Boomerang Video Maker` (yours)** | **21** | pure category, **no brand**, 9 chars unused |

Four patterns:

1. **Nobody wastes budget.** Every competitor sits at 25–30 chars. **This app ships 21 — nine
   characters of dead inventory in the highest-weighted field on the store.** Easiest fix on the list.
2. **Nobody ships a bare brand.** Even Zoomerang (25M+ claimed users) appends "- Ai Video Maker."
3. **Every successful coined brand is a keyword-bearing portmanteau:** **Boomer**it, **Loopy**Clip,
   **Loop**iq, **Zoom**erang, **Boom Loop**, **Clip Loop**. Brand search and category search reinforce
   each other; no token is wasted.
4. **Separator style carries no signal.** Comma, colon, hyphen, ampersand all appear among ranking apps.

> Measured against pattern 3, `OpenLoop` is **half-optimal**: "Loop" is a first-class category token
> doing real work; "Open" does nothing for this category *and* is the exact half that drags in the
> telehealth/EV/crypto collision. That is a permanent, small discoverability tax. It is **not** a
> reason to rename — the package ID `io.github.stozo04.openloop` cannot change without shipping a new
> app and losing everything.

### 4.5 Keyword targets — and what to abandon

**No search-volume data exists in this research.** Play autocomplete requires driving a real browser;
Google Trends does not render to a text fetcher. Google *web* autocomplete was used for **intent
contamination** analysis only — it returns ranked strings, never volumes. Competition levels below are
qualitative inference. **No numbers were invented.**

| Candidate | Competition | Intent | Verdict |
|---|---|---|---|
| `boomerang` (bare) | Extreme | **Poor** — Cartoon Network, Boomerang for Outlook, Roku and the sports projectile all outrank the video effect | **Abandon as a target.** Keep for relevance only. |
| `boomerang video maker` | Very high | Excellent | Table stakes, not opportunity. Won't rank at 10 installs. |
| `boomerang video effect` / `boomerang video loop` | Medium-high | Excellent | **Real long-tail.** Worth explicit coverage. |
| `reverse video` (bare) | Medium | **Poor** — half of autocomplete is reverse *lookup* ("find this video's origin") | **Do not target bare.** |
| `reverse video maker` / `play video backwards` | **Low** | Excellent | **Strong candidate.** Real shipped feature (`VideoReverser.kt`). |
| `video speed changer` / `speed control video` | Medium | Excellent | **Strongest functional angle** — the actual differentiator. |
| `slow motion video maker` | High | Good, diluted | Secondary — competes with the built-in camera. |
| `ping pong video` | — | **Zero** | **Cut entirely.** 10 of 10 autocomplete results are table tennis. Using it edges toward the "irrelevant keywords" prohibition. |
| `open source video editor android` | Very low | Excellent but tiny | Best differentiator owned; audience is mostly **not on Play** (see §4.6). |
| `offline` / `on-device` / `private` | Low-medium | Good | **Include.** Uncontested by ad-supported incumbents who cannot make the claim. |
| `video to gif` | Medium | Good | **Only if GIF export actually ships.** Claiming absent features violates the Metadata accuracy rule. |

**Honest summary:** there is no hidden high-volume, low-competition keyword. Mature categories don't
work that way. What exists are three angles the ad-supported incumbents structurally cannot match —
**real-time speed control, genuine on-device privacy, open source** — attached to small but clean
intent. That is niche capture, not category capture.

### 4.6 Off-Play: the landing page is the biggest free win in the repo

**`docs/index.html` is 11 lines — a `<meta http-equiv="refresh">` to the privacy policy.** The
canonical project URL, `https://stozo04.github.io/OpenLoop/`, currently bounces to a legal document.
There is no landing page, no sitemap, no robots.txt.

This matters because Google's [SEO Starter Guide](https://developers.google.com/search/docs/fundamentals/seo-starter-guide)
(updated 2025-12-10) says most pages are discovered **through links from already-indexed pages** — and
a landing page is the only web property whose title, copy and outbound links are fully controlled. It
is also the **only place a `SoftwareApplication` rich result can originate**; Google does not permit
structured data on a Play listing.

Empirical check: a web search for `"Boomerang Video Maker" OpenLoop stozo04 android app` returned
competitors' Play listings (`com.sarafan.boomerang`, `com.loopvideo.boomerangmaker`) and a dense field
of APK mirrors — **but not `io.github.stozo04.openloop` at all**.

**Structured data requirements**
([Software App structured data](https://developers.google.com/search/docs/appearance/structured-data/software-app),
updated 2025-12-10) — required: `name`, `offers.price` (use `0`), and **one of** `aggregateRating` or
`review`. Recommended: `applicationCategory` (use `MultimediaApplication`), `operatingSystem`.

> **Ship without `aggregateRating` initially.** The
> [general guidelines](https://developers.google.com/search/docs/appearance/structured-data/sd-policies)
> (updated 2026-07-10) forbid marking up content not **visibly displayed** on the page, and state
> "reviews or ratings not by actual users may result in **manual action**." With ~zero reviews, adding
> a rating obligates you to display and sync a volatile number for no gain. Forfeit rich-result
> eligibility until real ratings exist. Risk calibration: a structured-data manual action removes rich
> results but "**doesn't affect how the page ranks in Google web search**."

Good news: the self-serving-review prohibition is scoped to `LocalBusiness` and `Organization` **only**
([Review snippet](https://developers.google.com/search/docs/appearance/structured-data/review-snippet),
updated 2026-07-24). A first-party page marking up its own app's genuine Play rating is fine.

**Two GitHub Pages constraints that cannot be engineered around:**

- **robots.txt must live at the host root** — `https://stozo04.github.io/robots.txt`. That path belongs
  to a `stozo04.github.io` *user-site* repo which does not exist. **You cannot serve robots.txt for a
  project site.**
- Consequently the raw engineering markdown in `docs/` (PRDs, `lessons_learned/`, `e2e/` reports) is
  **publicly served and indexable**, and cannot be `noindex`ed or disallowed. *Lazy fix:* publish Pages
  from a separate folder or `gh-pages` branch containing only `index.html`, `privacy-policy.html` and
  images. Or accept it — it's Apache-2.0 anyway; the cost is a thin-content halo, not a policy problem.

**Deep links / App Indexing: skip entirely.** Firebase App Indexing is dead — verified by direct 301s:
`developers.google.com/app-indexing/android/app` → `firebase.google.com/support/faq#firebase-app-indexing`.
[Android App Links](https://developer.android.com/training/app-links) (updated 2026-08-07) are current
and actively maintained, but they are a **routing** technology, not a discovery one: they send a user
who is *already* visiting a URL you own into the app. Three independent reasons to skip: (1) a local
camera/editor has no content that maps to canonical `https://` URLs; (2) `assetlinks.json` must sit at
the **host root**, unavailable on a Pages project site; (3) `github.io` is shared infrastructure you
don't control. Pure YAGNI until a custom domain and shareable web content both exist.

**The In-App Review API is the highest-value engineering item.** The star rating renders at *every*
decision point — search results, category lists, listing header. With zero reviews the listing either
shows nothing or a violently volatile average. Every other item drives traffic to a listing that
converts badly.

Current dependency (the overview page's "Play Core 1.8.0" line is **stale**; use the split libraries
per the [Kotlin/Java guide](https://developer.android.com/guide/playcore/in-app-review/kotlin-java),
updated 2026-08-07):

```kotlin
implementation("com.google.android.play:review:2.0.2")
implementation("com.google.android.play:review-ktx:2.0.2")
```

**Hard rules** ([overview](https://developer.android.com/guide/playcore/in-app-review), updated
2026-01-30):

- ❌ **No "Rate us" button.** The user may have hit quota, producing a button that visibly does
  nothing. Deep-link to the Play listing instead.
- ❌ **No question before or during the card** — including "Do you like the app?". **This kills the
  classic happy-path filter.** Conditional prompting on sentiment is out.
- ❌ No modifying, overlaying, or programmatically dismissing the card.
- ❌ No surfacing errors to the user or changing app flow on error.
- ❌ Play policy ([User Ratings, Reviews, and Installs](https://support.google.com/googleplay/android-developer/answer/9898684)):
  no incentivised ratings, no manipulative pop-ups, no forcing.
- The API **never tells you** whether the dialog appeared or whether the user reviewed.

**Where to trigger in this codebase:** the `Processing → ReadyToCapture` **success** transition — the
user has just saved a boomerang and received the app's core value. Never on the
`Processing → BoomerangEditor` failure branch. Gate on **cumulative successful saves** (≥3), persisted
via the existing `UserPreferencesRepository` DataStore alongside the onboarding flag.

> That gate filters on **usage depth**, not sentiment, which is exactly what "trigger after users have
> experienced enough of your app" asks for. Gating on "did the user seem happy" would be the violation.

**F-Droid is blocked, IzzyOnDroid is not.** The
[F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) bars "Google Play Services and
Firebase and Crashlytics" by name. Current blockers: `diagnostics/` (Crashlytics + Analytics),
`update/` (Play Core), `camera/lens/FaceTracker.kt` (ML Kit), and In-App Review would be a fourth.
Entry requires a **separate FOSS product flavor** stripping all four — a multi-day project with its own
test matrix. **[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/info) is the realistic play**: it
hosts official developer binaries from GitHub releases and documents analytics as *antifeatures*
rather than rejecting them.

---

## 5. Implementation plan

Split by **who can actually do it**. Most high-impact work is Console-side and cannot be done from
this repo.

### 5.1 Owner — Play Console (highest impact, not code)

Ordered. **Batch steps 2–4 into a single submission** to avoid the back-of-queue penalty.

| # | Action | Why |
|---|---|---|
| 1 | **Record baseline ranks** for every target keyword before touching anything | Without this the result is unreadable |
| 2 | **Change the title** (see §5.2) | The only fix for the `openloop` brand-search failure; also improves trademark posture |
| 3 | **Rewrite the short description** — remove the "Ad Free" opener, add the speed-control differentiator | 80 chars, second-highest weight |
| 4 | **Fix the full description** — reconcile the "no tracking" claim with `data-safety.md` (§3.1) | **Listing accuracy — do this even if nothing else ships** |
| 5 | **Set all 5 tags** from the Console picklist | Free; taxonomy is Console-only, not published |
| 6 | **Add screenshots to ≥4** | 2 is the publish minimum; **4+ unlocks large-format promotional placements** — cheapest eligibility unlock available |
| 7 | **Check Android vitals** against 1.09% crash / 0.47% ANR, incl. per-device and the new wake-lock threshold | A media/codec app with device-specific paths is exactly the profile that trips per-device limits |
| 8 | **Glance at Promotional content eligibility** | Zero minutes to check; likely closed |
| 9 | **Hold two weeks minimum** before judging | Re-indexing is not instant |
| 10 | *Later:* free machine translation for the 10 supported languages | Zero-cost keyword surface — **only after English is settled**, since every edit re-triggers review across all locales |

### 5.2 The title decision — owner's call

All candidates verified ≤30 characters.

| Title | Chars | Tradeoff |
|---|---|---|
| **`OpenLoop: Boomerang Maker`** | **25** | **Recommended.** Brand carries the identity, "Boomerang Maker" is descriptive — the most defensible trademark posture (§3.2) and it fixes brand search. Costs some click-through vs keyword-first. |
| `Boomerang Maker - OpenLoop` | 26 | Keyword-first. Better click-through for an unknown brand, since Play truncates titles in grid views and leading tokens are what users scan. Mirrors `Loop Video Maker - Loopiq`. **Weaker trademark posture** — still leads with the mark. |
| `OpenLoop Boomerang Video Maker` | 30 | Perfect character fit, maximum token coverage. **Don't let a tidy number pick the strategy** — reads closer to keyword stuffing, no separator. |
| `Boomerang Video Maker` | 21 | **Status quo. Do not keep.** |

**Recommendation: `OpenLoop: Boomerang Maker`.** The research is genuinely split — the keyword-first
agent made the better click-through argument, the trademark evidence favours brand-first. Given that
(a) the stated complaint is brand findability and (b) there is a live incontestable mark in this exact
class, brand-first wins on both counts. Reject dropping "Boomerang" entirely: it would forfeit the #1
position on `openloop boomerang` to eliminate a risk that has not materialised against any of five
competitors.

**Rename risk is low** — there is no ranking equity to lose. Costs are operational only: up to 7 days
review, no native A/B test, two-week read window.

### 5.3 Repo work (this is the GitHub feature)

| # | Task | Effort | Files |
|---|---|---|---|
| 1 | **Replace `docs/index.html`** with a real landing page: unique `<title>`, meta description, canonical link, Open Graph tags, visible Play + GitHub links, screenshots | 1–2 h | `docs/index.html` |
| 2 | **Add `SoftwareApplication` JSON-LD** (no `aggregateRating` until real ratings exist) | 30 m | `docs/index.html` |
| 3 | **Integrate the In-App Review API** — trigger on `Processing → ReadyToCapture` success, gated on ≥3 cumulative saves via DataStore | 2–3 h | `app/build.gradle.kts`, `OpenLoopViewModel.kt`, `UserPreferencesRepository` |
| 4 | **One JVM test** with `FakeReviewManager`: asserts the trigger fires only after N successful saves and **never** on the failure path | 30 m | `app/src/test/...` |
| 5 | **Reconcile README accuracy** — "Zero network calls. Zero tracking." is false (§3.1) | 15 m | `README.md` |
| 6 | **Sync `docs/play-store/store-listing.md`** to whatever goes live, or annotate the drift | 20 m | `docs/play-store/store-listing.md` |
| 7 | **Repo as SEO asset** — Play badge in README, About→website field, topics (`android`, `kotlin`, `boomerang`, `video-editor`, `camerax`, `media3`, `open-source`), cut GitHub Releases with APKs | 15 m | `README.md`, repo settings |
| 8 | *Optional:* move Pages publishing off `docs/` so engineering markdown isn't served | 15 m | repo settings |

**Deliberately skipped:** `sitemap.xml` (near-worthless for two pages), `robots.txt` (impossible on a
Pages project site), App Links / `assetlinks.json` (§4.6), F-Droid FOSS flavor (multi-day, blocked).

### 5.4 Off-repo, free — after the landing page exists

1. **Search Console** — URL-prefix property `https://stozo04.github.io/OpenLoop/` (Domain properties
   need DNS you don't control). Verify by **HTML file upload**, which survives `index.html` rewrites.
   Then URL Inspection → Request Indexing. **Instrumentation, not growth** — if Performance shows zero
   queries after a month, that answers the brand-token question with data.
2. **IzzyOnDroid** submission via their Codeberg maintenance repo.
3. **Show HN** — an installable app qualifies ("something people can hold in their hands"); a landing
   page alone does not. Never solicit upvotes.
4. **Product Hunt** — free, personal accounts only, never ask for upvotes.
5. **Reddit** (`r/androidapps`, `r/fossdroid`) — **read each sidebar first**; self-promo rules vary and
   enforcement is often permanent.

### 5.5 Paid — noted, not recommended

- **Google Ads App campaigns:** [official guidance](https://support.google.com/google-ads/answer/9176652)
  is "set your average daily budget at **50 times your target CPI**." A $1 CPI implies **$50/day ≈
  $1,500/month** — the floor at which the optimiser exits its learning phase. Below that you are
  donating money. Out of scope at zero budget.
- **A custom domain (~$10–15/yr) is the one paid item worth buying.** It unlocks a Search Console
  Domain property, a root `robots.txt`, `/.well-known/assetlinks.json`, and a brandable URL — for ~1%
  of one month of minimum viable ad spend.

---

## 6. Measurement plan

Re-run the §2.2 baseline **from a signed-out context** at T+2 weeks and T+6 weeks:

| Query | Baseline (2026-08-09) | Target |
|---|---|---|
| `openloop` | Absent from top 27 | **Top 10** — the one genuinely winnable goal |
| `openloop boomerang` | #1 | Hold #1 |
| `boomerang video maker` | #7 | Hold or improve |
| `boom` | Absent from top 25 | **No target. Explicitly out of scope.** |

Plus: Play Console → listing conversion (visitors, clicks, CTR by traffic source); Search Console →
Performance (impressions on brand queries); rating count and average.

---

## 7. Expectation setting — read this before signing off

1. **This will not move installs, and should not be expected to.** Title work fixes *findability for
   people who already know you exist*. Play ranks on downloads, ratings and behavioural signals; a
   10-install app has none. The win is plugging a conversion leak, not growth.
2. **`boom` is out of scope permanently.** Not this year, not with any title. The term is contaminated
   four ways before you even reach the install-volume problem.
3. **The best differentiators are policy-barred from the title.** Free, no ads, no IAP, Apache 2.0,
   100% on-device — the most honest positioning in the category, and "Free"/"No Ads" are explicitly
   unwelcome in titles. All of it lives in the description and screenshots.
4. **The "Open" in OpenLoop is a permanent tax.** Not a reason to rename — the package ID cannot change
   without shipping a new app and losing everything — but go in clear-eyed.
5. **No search-volume data backs the keyword table.** Play autocomplete and Google Trends were both
   inaccessible. Competition estimates are qualitative inference, explicitly labelled. No numbers were
   invented.

---

## 8. Open questions for the owner

1. **Which is the source of truth** — the live Console listing or `docs/play-store/store-listing.md`?
   They have drifted on every field (§2.1).
2. **Why is Play on 1.0.25 when the repo is on 1.0.36?** Deliberate, or a stalled release?
3. **Title choice** — brand-first (recommended) or keyword-first? (§5.2)
4. **Is the trademark risk worth an attorney hour?** (§3.2)
5. **Move Pages publishing off `docs/`,** or accept engineering markdown being publicly indexed?

---

## 9. Sources

Every URL below was fetched and verified during research. Dates are as shown on the page.

### Google Play — official

- [App visibility and discovery issues](https://support.google.com/googleplay/android-developer/answer/9042516) — the only official confirmation that developer names are indexed
- [App Discovery and Ranking](https://support.google.com/googleplay/android-developer/answer/9958766) — the four-pillar ranking model
- [Get discovered on Google Play search](https://support.google.com/googleplay/android-developer/answer/4448378) — "unique… avoid common terms"; weights are proprietary
- [Best practices for your store listing](https://support.google.com/googleplay/android-developer/answer/13393723) — field limits; the "Free"/"No Ads" title rule
- [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152) — 30/80/4000 limits; the keyword-repetition **suspension** rule
- [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842) — the authoritative metadata policy
- [Intellectual Property policy](https://support.google.com/googleplay/android-developer/answer/9888072) — trademark standard and suspension consequence
- [Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374) — the "misled about your app's relationship" test
- [Choose a category and tags](https://support.google.com/googleplay/android-developer/answer/9859673) — five-tag max
- [Run A/B tests on your Store Listing](https://support.google.com/googleplay/android-developer/answer/12053285) — text testable only in localized experiments
- [Create custom store listings](https://support.google.com/googleplay/android-developer/answer/9867158) — 50-listing cap, Play Search keyword targeting
- [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151) — asset specs; the 4-screenshot promotional threshold
- [Control when app changes are reviewed and published](https://support.google.com/googleplay/android-developer/answer/9859654) — review times, back-of-queue penalty, managed publishing
- [Translate and localize your app](https://support.google.com/googleplay/android-developer/answer/9844778) — free MT in 10 languages
- [Understand promotional content](https://support.google.com/googleplay/android-developer/answer/12929029) / [Create promotional content](https://support.google.com/googleplay/android-developer/answer/12932541) — Premium growth tools gate
- [User Ratings, Reviews, and Installs policy](https://support.google.com/googleplay/android-developer/answer/9898684) — no incentivised or manipulated ratings
- [Build awareness with pre-registration](https://support.google.com/googleplay/android-developer/answer/9859047) — rules pre-registration out
- [Understand and grow your app's user base](https://support.google.com/googleplay/android-developer/answer/9859173) — free Grow-users reporting
- [Getting featured on Google Play](https://play.google.com/console/about/guides/featuring/) — good quality → discovery; great quality → featuring

### Android Developers — official

- [Android vitals](https://developer.android.com/topic/performance/vitals) *(2026-05-19)* — the threshold table
- [Monitor technical quality with Android vitals](https://support.google.com/googleplay/android-developer/answer/9844486) — corroborates thresholds
- [User metrics on Google Play](https://developer.android.com/quality/core-value/user-metrics) *(2026-03-06)* — quality bars
- [What great technical quality looks like](https://developer.android.com/quality/technical) *(2026-03-06)* — evaluated across all versions
- [In-app reviews overview](https://developer.android.com/guide/playcore/in-app-review) *(2026-01-30)* — design rules *(dependency line is stale)*
- [Integrate in-app reviews (Kotlin/Java)](https://developer.android.com/guide/playcore/in-app-review/kotlin-java) *(2026-08-07)* — current coordinates
- [Test in-app reviews](https://developer.android.com/guide/playcore/in-app-review/test) *(2025-07-21)* — `FakeReviewManager`
- [Handling Android App Links](https://developer.android.com/training/app-links) *(2026-08-07)*
- [Verify Android App Links](https://developer.android.com/training/app-links/verify-android-applinks) *(2026-08-07)*
- [Digital Asset Links getting started](https://developers.google.com/digital-asset-links/v1/getting-started) *(2025-08-28)*

### Google Search Central — official

- [SEO Starter Guide](https://developers.google.com/search/docs/fundamentals/seo-starter-guide) *(2025-12-10)*
- [Software App structured data](https://developers.google.com/search/docs/appearance/structured-data/software-app) *(2025-12-10)*
- [General structured data guidelines](https://developers.google.com/search/docs/appearance/structured-data/sd-policies) *(2026-07-10)*
- [Review snippet structured data](https://developers.google.com/search/docs/appearance/structured-data/review-snippet) *(2026-07-24)*
- [Build and submit a sitemap](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap) *(2026-07-08)*
- [Introduction to robots.txt](https://developers.google.com/search/docs/crawling-indexing/robots/intro) *(2025-12-10)*
- [Verify your site ownership](https://support.google.com/webmasters/answer/9008080) · [Add a website property](https://support.google.com/webmasters/answer/34592)
- [schema.org/SoftwareApplication](https://schema.org/SoftwareApplication) · [schema.org/MobileApplication](https://schema.org/MobileApplication)

### USPTO — primary trademark records

- [BOOMERANG — Serial 86868955, Reg. 5030673, Instagram LLC, Class 009, incontestable](https://tsdr.uspto.gov/statusview/sn86868955)
- [OPENLOOP — Serial 90089513, Reg. 6494802, OpenLoop Health Inc., Class 035](https://tsdr.uspto.gov/statusview/sn90089513)
- [BOOM LOOP — Reg. 8169612, Sarafan Mobile Limited, Class 009](https://tsdr.uspto.gov/statusview/rn8169612)

### Other

- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) · [Anti-Features](https://f-droid.org/docs/Anti-Features/) · [Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [IzzyOnDroid repository info](https://apt.izzysoft.de/fdroid/index/info)
- [Show HN rules](https://news.ycombinator.com/showhn.html) · [Product Hunt Launch](https://www.producthunt.com/launch)
- [About GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/about-github-pages)
- [Tips for maximizing your App campaign](https://support.google.com/google-ads/answer/9176652) — the 50× CPI budget rule
- [Instagram's Boomerang and Hyperlapse apps disappear from app stores](https://techcrunch.com/2022/03/07/instagrams-boomerang-and-hyperlapse-apps-disappear-from-app-stores/) *(2022-03-07)*

### Third-party ASO vendors — secondary, inference not policy

- [AppRadar — ASO Ranking Factors 2026](https://appradar.com/academy/app-store-ranking-factors) *(2026-04-28)*
- [AppTweak — Google Play keyword research](https://www.apptweak.com/en/aso-blog/play-store-keyword-research) *(2025-12-22)*
- [AppTweak — Top Google Play ranking factors](https://www.apptweak.com/en/aso-blog/google-play-ranking-factors) *(2025-12-22)*
- [AppFollow — ASO Ranking Factors 2026](https://appfollow.io/blog/aso-ranking-factors) *(2026-05-25)* — ⚠️ states a 50-char title limit; **Google says 30**
- [AppFollow — Google Play ASO Keywords](https://appfollow.io/blog/google-play-aso-keywords) *(2026-07-06)*
- [MobileAction — Google Play ranking factors](https://www.mobileaction.co/blog/google-play-store-ranking-factors/) *(2025-11-13)*

### Could not be verified — stated for honesty

- **Play Store autocomplete** — requires driving a real browser; inaccessible. Google *web* autocomplete
  was used for intent analysis only and is not a substitute.
- **Google Trends** — a JavaScript app that does not render to a text fetcher. No trend figure appears
  anywhere in this document.
- **No search-volume data exists in this research.** Every competition estimate is qualitative.
- **Play exclusivity** — no affirmative statement found either way on distributing to other stores.
  Check the Developer Program Policy before relying on it.
