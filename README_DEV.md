# ReplayMod Development Notes

## Hardware H.264 Export

ReplayMod can use FFmpeg hardware H.264 encoders when the selected MP4 preset contains `%HARDWARE_H264%`.

Encoder priority is:

1. `h264_nvenc`
2. `h264_vaapi`
3. `h264_qsv`
4. `h264_amf`
5. `h264_videotoolbox`
6. `libx264` fallback

NVENC requires a working NVIDIA driver and an FFmpeg build compiled with NVENC support. The Nix dev shell provides FFmpeg, but it cannot provide the host NVIDIA kernel driver.

VAAPI requires `/dev/dri/renderD128` and an FFmpeg build with `h264_vaapi`. On Linux, ensure the user running Minecraft has access to the render device.

### Quality vs speed trade-off

By default the FFmpeg hardware path now uses quality-tuned encoder settings for offline file rendering (NVENC `p5 -tune hq -rc vbr -spatial-aq 1`, QSV `slower`, AMF `balanced`, VAAPI VBR). The old streaming-tuned defaults (`p1` / `veryfast` / `quality speed`) produced visibly blockier video than `libx264 -preset veryfast` at the same bitrate, which is what users compared against when they said the optimised export "looks rougher" than the regular export.

Temporal AQ and NVENC B-frames are intentionally not enabled by default: at 4K@120 fps on 6 GiB-class Turing parts (e.g. GTX 1660 SUPER) the combination starves NVENC's look-ahead buffer and hangs the FFmpeg filter graph after a handful of frames. Spatial AQ alone is the safe quality knob across the NVENC range.

If you need maximum encoding throughput and accept lower visual quality (e.g. for very long renders on a slow GPU), restore the old behaviour with:

```
-Dreplaymod.ffmpeg.hwProfile=speed
# or
REPLAYMOD_FFMPEG_HW_PROFILE=speed
```

The native NVENC encoder (`replaymod_native_encoder.dll`) uses the same quality-oriented rate-control unconditionally; it has no separate "speed" knob because P4/P5 high-quality presets already saturate the NVENC pipeline at offline render rates.

## GPUオフロード方針

RAM依存処理とGPU依存処理の切り分け方針は `docs/gpu-offload-feasibility.md` を参照してください。

## Project Panama 計画

JVMからネイティブHWアクセスへ段階移行する計画は `docs/panama-native-hardware-plan.md` を参照してください。
