# OpenLoop — EU Geo-blocking Regulation (EU) 2018/302 compliance record

When you set **Play Console → Production → Countries/regions** to "the world," Play surfaces an
informational notice linking to
[Geo-blocking Regulation (EU) 2018/302](https://support.google.com/googleplay/android-developer/answer/6223646).
This file is the source-of-truth record that OpenLoop was audited against that regulation and the
basis for the "compliant" conclusion. Keep it in sync if the app ever gains location awareness,
region-gated features, or in-app payments.

> **Verdict (audited at versionCode 24 / 1.0.24):** Compliant **by construction** — no code changes
> required. The regulation bans *unjustified discrimination based on a user's nationality, place of
> residence, or place of establishment* across three axes. OpenLoop has **zero surface** on all three:
> it cannot determine where a user is, has no region-gated features, and takes no payments. You can't
> violate an anti-geo-discrimination rule with an app that has no geo-awareness and no commercial
> transaction.

---

## Code audit — three regulation axes

Swept all Kotlin source, the Gradle/version-catalog dependencies, and the merged manifest.

| Regulation axis | What was searched | Finding |
|---|---|---|
| **1. Block / redirect access by location** | `Locale`, `getCountry`, SIM/network-country, `TelephonyManager`, location permission, IP-geo, Remote Config, any HTTP client | **None.** No `ACCESS_*_LOCATION` permission in the manifest, no telephony/SIM-country reads, no networking layer. The only `https://` occurrences are doc-comment links; the only off-device traffic is Firebase Analytics/Crashlytics telemetry, which does **not** gate the app. |
| **2. Region-locked features** | feature flags, Remote Config, `fetchAndActivate`, geographic branches | **None.** No Remote Config dependency. Processing is 100% on-device. Every user receives the identical app. |
| **3. Payment discrimination** | `BillingClient`, IAP, subscription, SKU, paywall, premium, price | **None.** Zero billing surface — no Play Billing library, no in-app purchases, no paywall. The app is free everywhere. |

**The only `Locale` usage** is `Locale.US` for deterministic number/time *formatting* (e.g.
`String.format(Locale.US, "%02d:%04.1f", …)` in the trim/speed UI). That is display formatting applied
identically to every user — the opposite of region gating, and a best practice (avoids locale-dependent
decimal separators). It is intentionally retained.

**Manifest confirmation:** declared permissions are `CAMERA`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PROCESSING`, `POST_NOTIFICATIONS`, and `AD_ID` is *removed*
(`tools:node="remove"`). With no location permission, the app physically cannot read a user's country,
nationality, or residence.

---

## Non-code checklist — where this regulation actually lives

2018/302 is overwhelmingly a **distribution & commercial-terms** rule, not a code rule.

| # | Item | Status |
|---|---|---|
| 1 | **Country/region availability = "the world"** (all EU member states included equally) | ✅ Done — the single operative control, set correctly. |
| 2 | **No different general terms & conditions for EU users** by nationality/residence | ✅ Free app, no account/login, no region-specific terms. |
| 3 | **Privacy policy reachable by all EU users** | ✅ Public URL (`docs/privacy-policy.html`), accessible everywhere. |
| 4 | **No redirecting EU users** to a different listing/version without consent | ✅ N/A — single global listing. |
| 5 | **Payment-method non-discrimination** (no refusing/varying terms by payment-account location) | ⚠️ N/A today (no payments). **Future watch-item:** revisit if the app ever monetizes — payment terms must not differ by nationality, residence, or payment-account location. |

---

## Bottom line

- **Code:** nothing to change.
- **Non-code:** the only control that matters — Play Console country/region = "the world" — is already set.
- **Re-audit trigger:** revisit this record only if a future change adds (a) location awareness, (b) a
  region-gated feature / Remote Config, or (c) in-app purchases.

## Sources

- [Geo-blocking Regulation (EU) 2018/302 — Play Console Help](https://support.google.com/googleplay/android-developer/answer/6223646)
- [Regulation (EU) 2018/302 — full text (EUR-Lex)](https://eur-lex.europa.eu/eli/reg/2018/302/oj)

---

*Audited 2026-06-19 against versionCode 24 (1.0.24). Re-run the three-axis sweep above if location, region-gating, or payment surfaces are ever introduced.*
