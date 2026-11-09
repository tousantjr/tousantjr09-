# MPTV's own build of OwnTV core

`tv.own.owntv:core` and `:player-core` version **1.0.71.1-mptv** = upstream core-1.0.71 plus MPTV's changes below (first made on 1.0.69:
upstream commit 4048d4c1 ("live decoder retries", OwnTV #321): a live channel whose ExoPlayer
decoder fails to start is rebuilt up to three times (0.5 s, 1.5 s, 3 s) instead of once.
It also carries that commit's phone call/notification audio-focus fix and the FairPlay message — upstream has since shipped these in 1.0.71).

1.0.69.4-mptv adds: Dolby Vision profile 7 files play on the HEVC (HDR10 base layer) decoder, with an
HEVC profile instead of the Dolby Vision one Media3 wrote (vendor decoders rejected it with err
0xffffffea); and a 4K file the TV still can't hardware-decode says "choose the HD version" instead of
"the file may be corrupted".

Built from https://github.com/ahXN00/OwnTV_Core tag core-1.0.71 merged with MPTV's core branch (Dolby Vision fallback,
clearer 4K message, libmpv 2026.11.0). Each upstream core release is merged the same way into a new x.y.z.1-mptv build.

1.0.69.4-mptv: the Dolby Vision fallback actually reaches profile 7. Media3 only offers the HEVC decoder
for Dolby Vision profiles 4 and 8, so 1.0.69.2's reorder had nothing to reorder for profile 7. The codec
selector now adds the HEVC decoders to every Dolby Vision request, the video renderer judges a profile-7
track by its HEVC base layer, and profile 5 (no HDR10 base layer) is kept off HEVC.

1.0.69.4-mptv: mpv engine (libmpv) 2026.10.4 -> 2026.11.0 — mpv 914a11c30, Dolby Vision profile 5 opens on the
device's Dolby Vision decoder in mpv (no more green/purple picture), libplacebo/harfbuzz/freetype/fribidi bumps.
FFmpeg stays n9.0.2.

The phone app (mobile/) still pins 1.0.69.4-mptv until its own upstream update is merged, so both builds are kept here.
