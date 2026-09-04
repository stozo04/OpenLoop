# E2E proof — Vampire lens

**Feature:** [`PRD-camera-lenses.md` §17](../PRD-camera-lenses.md#17-2026-09-03--prd-vampire-the-spooky-lens)

| Run           | Date       | Device                                        |
| ------------- | ---------- | --------------------------------------------- |
| Installed APK | 2026-09-03 | Pixel_8 AVD, Android 16 / API 37, back camera |

## What passed

| Check                               | Result                                                                                                          |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Focused geometry tests              | `LensAnchorTest`: hidden rest state, full mouth-open reveal and fixed fang roots                                |
| Full JVM suite before final sweep   | 647 tests, 0 failures, 0 errors, 0 skipped                                                                      |
| Connected instrumentation preflight | 123 tests, 0 failures, 0 errors                                                                                 |
| Installed catalogue loop            | `PASS ... lens=Vampire drawer=open pick->clear took=39s`                                                        |
| Encoded art in APK                  | SHA-256 matched the three repository WebPs byte-for-byte                                                        |
| Live closed-mouth bind              | Public-domain Mona Lisa fixture: pre-hardware `0.40` fang scale, retained as iteration evidence                 |
| Mouth-open response                 | Original fictional generated portrait: long fangs, roots at the upper mouth, eyes/brows/nose/expression visible |
| Recording bake                      | 5 s capture reached Trim as a 9.20 s virtual-camera clip with Vampire in preview and every filmstrip tile       |

The AVD reported the same-shape geometry expected by the lens renderer:

```text
OpenLoopLens: Lens output targets=3 size=1280x960 inputDet=-1.0 outputDet=-1.0
```

## Screenshots

| File                                                                                                           | What it proves                                                                                                                                     |
| -------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`2026-09-03-vampire-closed-fixture.webp`](2026-09-03-vampire-closed-fixture.webp)                             | Superseded `restFraction = 0.40` emulator pass; owner hardware later showed the closed-mouth fangs were still too prominent                        |
| [`2026-09-03-vampire-open-mouth-generated-fixture.webp`](2026-09-03-vampire-open-mouth-generated-fixture.webp) | Wide-open mouth drives the full fang reveal without covering the eyes, brows, nose, or expression; the portrait is fictional and verification-only |
| [`2026-09-03-vampire-baked-trim.webp`](2026-09-03-vampire-baked-trim.webp)                                     | The effect is baked into the captured clip and its Trim filmstrip, not limited to the live preview                                                 |

Owner front-camera QA on 2026-09-04 confirmed the costume placement and full-open fang treatment
on a real face. It also rejected the `0.40` closed-mouth state as visibly always-on, so the follow-up
sets `restFraction = 0`. The personal QA photos were reviewed locally and are intentionally not
committed to this public repository; the zero-rest behavior still requires one owner retest.

The closed fixture is a flat public-domain painting already committed as
`app/src/androidTest/assets/face_fixture.jpg`. The open-mouth fixture was generated specifically for
this run and was not shipped or committed. Both are static posters on the emulator's virtual-scene
wall; neither is evidence of real-person motion quality.

## Honest residual hardware QA

The emulator proves catalogue reachability, decode, tracking on static portrait-like images,
mouth-state response, CameraEffect attachment, and recording bake. It does **not** prove the
following, which needs a real person on a real phone:

- [ ] front-camera mirroring and rear-camera placement on a live face;
- [ ] steadiness during head turns, tilt, motion blur, and changing light;
- [ ] two live faces at once and a third face not stealing a slot;
- [ ] sustained frame rate on mid-range hardware;
- [ ] subjective shareability across different face shapes and skin tones.

No physical phone was attached, and the host webcam was not activated without the owner's explicit
camera consent. These checks remain the PR's human QA checklist rather than being overstated as
automated evidence.
