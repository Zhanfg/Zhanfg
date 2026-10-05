# AMTool Next V3 Architecture

## Non-negotiable invariants

1. **Playback plane is read-only.** Account storefront, entitlement, playParams, availability,
   playback URLs and Apple Music native requests are never rewritten.
2. **Localized metadata is side-channel only.** Module-owned requests carry a private token and
   are routed to CN only at the exact Apple Music 6.5.3 catalog request executors.
3. **Cross-storefront identity uses recording identity.** The account song is resolved to ISRC,
   then the CN catalog is queried by ISRC. Adam IDs are not assumed stable across storefronts.
4. **Display overlay is projection, not mutation.** Only title/artist/album getter return values are
   projected. Canonical model IDs, playParams and underlying fields stay unchanged.
5. **Lyrics language is a separate domain.** Preferred translation/pronunciation tags and
   SongInfo language aliases are handled independently from metadata localization.
6. **Provider has one track authority.** Provider Runtime V3 owns a single generation state
   machine. Every request, retry, callback and publication is bound to that generation.
7. **Track transition clears module-owned lyrics immediately.** No-lyrics tracks therefore cannot
   inherit the previous song's lyricInfo. Late callbacks from old generations are rejected.
8. **Legacy provider coordinator is disabled.** The user's APK shell, settings, parser and low-level
   publisher remain available, but ApplePlayerHooker no longer runs a second competing lifecycle.

## Runtime planes

### Playback plane

Observed only. MediaSession metadata and PlaybackItem raw backing fields provide canonical identity.

### Localization plane

Account ID -> account catalog -> ISRC -> tokenized CN catalog request -> localized alias cache ->
UI getter overlay.

The request router hooks only:

- v8.D.d / v8.D.b
- A5.l.d / A5.l.c
- Ic.n.d / Ic.n.e

A request is rewritten only if the private AMTool token is present. The token is removed before
network egress.

### Lyrics plane

AMTool controls preferred language and official translation tag compatibility. It does not own
ColorOS lyric publication.

### Provider plane

Provider Runtime V3 states:

- EMPTY
- REQUESTING
- READY
- NO_LYRICS

Track change increments generation, cancels the old request epoch, clears pending publication and
removes module-owned lyricInfo from live sessions. Request timeout/retry and lyrics callbacks check
the same generation before doing anything.

## Versioning

This is an architectural rewrite, so the AMTool version is 3.0.0-alpha1 rather than another
2.0.0 hotfix.


## Alpha3 corrections

- Localized metadata is coalesced into short batches instead of issuing account+CN requests from
  every title getter.
- CN lookup tries all stable Apple catalog identifiers first. ISRC is fallback-only.
- Artist Top Songs has a final visible-row projection because 6.5.3 can copy the original title
  back through Epoxy/DataBinding after model construction.
- Floating bottom chrome owns only presentation. The full-width native backing layers are removed,
  mini-player and navigation form one segmented shell, and no window-focus polling is used.
