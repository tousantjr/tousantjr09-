# MPTV's own build of OwnTV core

`tv.own.owntv:core` and `:player-core` version **1.0.69.2-mptv** = upstream core-1.0.69 plus
upstream commit 4048d4c1 ("live decoder retries", OwnTV #321): a live channel whose ExoPlayer
decoder fails to start is rebuilt up to three times (0.5 s, 1.5 s, 3 s) instead of once.
It also carries that commit's phone call/notification audio-focus fix and the FairPlay message.

1.0.69.2-mptv adds: Dolby Vision profile 7 files play on the HEVC (HDR10 base layer) decoder, with an
HEVC profile instead of the Dolby Vision one Media3 wrote (vendor decoders rejected it with err
0xffffffea); and a 4K file the TV still can't hardware-decode says "choose the HD version" instead of
"the file may be corrupted".

Built from https://github.com/ahXN00/OwnTV_Core (branch core-1.0.69 + cherry-pick 4048d4c1).
When upstream publishes core 1.0.70 or later, pin that in gradle/libs.versions.toml and
mobile/gradle/libs.versions.toml and delete this folder.
