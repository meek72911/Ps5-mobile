# MobilePlay iPadOS — provenance and licensing

Licence: GNU AGPL-3.0 (same as the Android app; full text in `/COPYING`).

## Derived from
- **iPlay5** — https://github.com/reefbarman/iPlay5, commit `116860f9f5e2849bae74bcce55faf5dd1e2b97e1`, AGPL-3.0
  (copy of its licence: `LICENSE-iPlay5-AGPL-3.0`). The Swift app, C bridge (`ChiakiKit/`), VideoToolbox decoder,
  Metal renderer, audio output, controller input and build scripts start from this code.
  Changes made for MobilePlay: renamed; iPad-only target family; iPad orientations; foldable detection disabled on iPad;
  virtual-controls layout scaled for iPad; CI workflow and tests added.
- **chiaki-ng** (streetpea fork) pinned at `43728af2e639fecb8a7af1cd68c29820a40de973` (AGPL-3.0, OpenSSL exception as upstream)
- **mbedTLS** `82e69999f91126589a28b70dd75b69fbcabe2711` (Apache-2.0 / GPL-2.0+)
- **Opus** `22244de5a79bd1d6d623c32e72bf1954b56235be` (BSD-3-Clause)

Anyone receiving a built app must be able to get the corresponding source (AGPL §13). Keep this folder public with the build.
PlayStation, PS5 and DualSense are trademarks of Sony Interactive Entertainment Inc.; MobilePlay is not affiliated with Sony.
