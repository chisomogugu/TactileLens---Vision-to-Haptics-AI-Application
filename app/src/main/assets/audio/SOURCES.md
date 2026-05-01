# Audio Source Provenance

## Current state (2026-05-01)

| Material | File | Status | Source |
| --- | --- | --- | --- |
| Sand | `sand/loop.wav` | Real | freesound community pack `freesound_community-sand-various-68938.mp3`, offset 36 s, ffmpeg-processed (compressor + alimiter + loudnorm + 150 ms fades) to 3 s mono 44.1 kHz 16-bit PCM. CC0. |
| Wood | `wood/loop.wav` | **Placeholder (copy of sand)** | Source TBD. Strongest curated lead: `https://freesound.org/people/RutgerMuller/sounds/104013/` (Wood Plank Scraping Close-Mic Long, CC0, 4 s, stereo XY). |
| Glass | `glass/loop.wav` | **Placeholder (copy of sand)** | Source TBD. Curated leads: `https://freesound.org/people/mickdow/sounds/320912/` (Squeak Finger on Glass) or `https://freesound.org/people/ani_music/sounds/198403/` (Wine glass Rubbing 1a, sustained ring). |
| Rocks | `rocks/loop.wav` | **Placeholder (copy of sand)** | Source TBD. Curated lead: `https://freesound.org/people/alegemaate/sounds/364687/` (Gravel being disturbed, hand running through gravel). |

## Why placeholders for wood / glass / rocks

The `SamplePackAudioRenderer` requires a `loop.wav` in each material's asset folder or the engine logs a warning and renders silence for that material. To make the material picker dropdown demoable end-to-end without sound dropouts, all four materials currently load the sand loop as their audio source. The haptic recipes are still per-material distinct, so switching materials in the dropdown produces different vibration even though audio sounds the same.

When real wood / glass / rocks loops are sourced, drop the WAV at `app/src/main/assets/audio/<material>/loop.wav` and rebuild. No code change needed.

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
