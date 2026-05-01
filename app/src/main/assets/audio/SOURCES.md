# Audio Source Provenance

## Current state (2026-04-30, hackathon Day 1)

All five materials now ship with distinct real foley clips, each ffmpeg-processed
to 3 s mono 44.1 kHz 16-bit PCM via the project recipe (compressor + alimiter +
loudnorm + 150 ms fades). All clips are CC0 licensed.

| Material | File | Source |
| --- | --- | --- |
| Sand   | `sand/loop.wav`   | freesound community pack `freesound_community-sand-various-68938.mp3`, offset 36 s. |
| Wood   | `wood/loop.wav`   | `https://freesound.org/people/RutgerMuller/sounds/104013/` (Wood Plank Scraping Close-Mic Long, 4.14 s), offset 0.5 s. CDN preview: `https://cdn.freesound.org/previews/104/104013_179538-hq.mp3`. |
| Glass  | `glass/loop.wav`  | `https://freesound.org/people/ani_music/sounds/198403/` (Wine glass Rubbing 1a, 3.15 s sustained ring), offset 0.05 s. CDN preview: `https://cdn.freesound.org/previews/198/198403_3008343-hq.mp3`. |
| Rocks  | `rocks/loop.wav`  | `https://freesound.org/people/alegemaate/sounds/364687/` (Gravel being disturbed, 4.47 s), offset 0.5 s. CDN preview: `https://cdn.freesound.org/previews/364/364687_2531187-hq.mp3`. |
| Fabric | `fabric/loop.wav` | `https://freesound.org/people/Sonicquinn/sounds/435830/` (Fabric-Sofa-Rubbing-Movements_Mono_192000, 68.5 s), offset 30 s (middle of clip, uniform rubbing region). CDN preview: `https://cdn.freesound.org/previews/435/435830_8913961-hq.mp3`. |

The CDN preview URLs are publicly fetchable and match what the freesound web
player serves to its `<audio>` tag — they are how this build was sourced. For
higher-fidelity replacements, log into freesound and download the original WAV
from the page link, then re-run the ffmpeg recipe below.

## ffmpeg recipe to reuse

Once the source clip is downloaded:

```bash
ffmpeg -y -ss <OFFSET> -t 3.0 -i <SOURCE> -ac 1 -ar 44100 \
  -af "acompressor=threshold=-22dB:ratio=8:attack=3:release=80,alimiter=limit=0.7:level=disabled,loudnorm=I=-18:TP=-3:LRA=5,afade=t=in:st=0:d=0.15,afade=t=out:st=2.85:d=0.15" \
  -c:a pcm_s16le app/src/main/assets/audio/<material>/loop.wav
```

Tune `<OFFSET>` to a uniform region of the source clip (use ffprobe + RMS analysis if not obvious by ear). Drop the limiter chain if the source is already clean.

## Source guidance

Pick clips described as a single continuous gesture (e.g. "drag", "rub", "scrape", "sweep"). Files labeled "various" or stitched from multiple takes will have internal silences and impacts that scrubbing through is bad for; even with native-rate looping the wrap point will sound artificial if start/end aren't matched. The 150 ms fades at start/end of the ffmpeg recipe help mask the loop wrap on most material clips.
