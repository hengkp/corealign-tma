#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/tutorial/work"
FINAL="$ROOT/tutorial/final"
PYTHON="/Users/heng/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"

mkdir -p "$WORK" "$FINAL"
"$PYTHON" "$ROOT/tutorial/render_overlays.py"

if [[ ! -f "$WORK/corealign_video_master.mp4" ]]; then
ffmpeg -y -hide_banner \
  -loop 1 -t 15.490563 -i "$ROOT/tutorial/qc/home_new_1920.png" \
  -loop 1 -t 15.490563 -i "$WORK/overlay_01.png" \
  -loop 1 -t 23.405688 -i "$ROOT/tutorial/qc/github_top_1920.png" \
  -loop 1 -t 23.405688 -i "$WORK/overlay_02.png" \
  -ss 0 -t 17.031813 -i "$ROOT/tutorial/raw/11_github_chrome.mov" \
  -loop 1 -t 17.031813 -i "$WORK/overlay_03.png" \
  -loop 1 -t 35.004063 -i "$ROOT/tutorial/qc/config_apphub_1920.png" \
  -loop 1 -t 35.004063 -i "$WORK/overlay_04.png" \
  -ss 0 -t 28.604063 -i "$ROOT/tutorial/raw/03_qupath_slide.mov" \
  -loop 1 -t 28.604063 -i "$WORK/overlay_05.png" \
  -ss 20 -t 24.894688 -i "$ROOT/tutorial/raw/06_grid_qc.mov" \
  -loop 1 -t 24.894688 -i "$WORK/overlay_06.png" \
  -ss 15 -t 27.480813 -i "$ROOT/tutorial/raw/07_human_grid_approval.mov" \
  -loop 1 -t 27.480813 -i "$WORK/overlay_07.png" \
  -loop 1 -t 28.368938 -i "$ROOT/tutorial/qc/review_top_1920.png" \
  -loop 1 -t 28.368938 -i "$WORK/overlay_08.png" \
  -loop 1 -t 22.831000 -i "$ROOT/tutorial/qc/contact_sheet_1920.png" \
  -loop 1 -t 22.831000 -i "$WORK/overlay_09.png" \
  -loop 1 -t 11.311000 -i "$ROOT/public/images/corealign-hero-dark.webp" \
  -loop 1 -t 11.311000 -i "$WORK/overlay_10.png" \
  -filter_complex "\
[0:v]scale=2048:1152,crop=1920:1080:x='64+32*t/15.490563':y=36,fps=30,setsar=1[bg0];[1:v]format=rgba[ov0];[bg0][ov0]overlay=0:0:enable='lt(t,4.5)',trim=duration=15.490563,setpts=PTS-STARTPTS[v0];\
[2:v]scale=2048:1152,crop=1920:1080:x='64+32*t/23.405688':y=36,fps=30,setsar=1[bg1];[3:v]format=rgba[ov1];[bg1][ov1]overlay=0:0:enable='lt(t,4.5)',trim=duration=23.405688,setpts=PTS-STARTPTS[v1];\
[4:v]scale=1920:1080,setsar=1,fps=30[bg2];[5:v]format=rgba[ov2];[bg2][ov2]overlay=0:0:enable='lt(t,4.5)',trim=duration=17.031813,setpts=PTS-STARTPTS[v2];\
[6:v]scale=2048:1152,crop=1920:1080:x='96-32*t/35.004063':y=36,fps=30,setsar=1[bg3];[7:v]format=rgba[ov3];[bg3][ov3]overlay=0:0:enable='lt(t,4.5)',trim=duration=35.004063,setpts=PTS-STARTPTS[v3];\
[8:v]scale=1920:1080,setsar=1,fps=30[bg4];[9:v]format=rgba[ov4];[bg4][ov4]overlay=0:0:enable='lt(t,4.5)',trim=duration=28.604063,setpts=PTS-STARTPTS[v4];\
[10:v]scale=1920:1080,setsar=1,fps=30[bg5];[11:v]format=rgba[ov5];[bg5][ov5]overlay=0:0:enable='lt(t,4.5)',trim=duration=24.894688,setpts=PTS-STARTPTS[v5];\
[12:v]scale=1920:1080,setsar=1,fps=30[bg6];[13:v]format=rgba[ov6];[bg6][ov6]overlay=0:0:enable='lt(t,4.5)',trim=duration=27.480813,setpts=PTS-STARTPTS[v6];\
[14:v]scale=2048:1152,crop=1920:1080:x='64+32*t/28.368938':y=36,fps=30,setsar=1[bg7];[15:v]format=rgba[ov7];[bg7][ov7]overlay=0:0:enable='lt(t,4.5)',trim=duration=28.368938,setpts=PTS-STARTPTS[v7];\
[16:v]scale=2048:1152,crop=1920:1080:x='96-32*t/22.831':y=36,fps=30,setsar=1[bg8];[17:v]format=rgba[ov8];[bg8][ov8]overlay=0:0:enable='lt(t,4.5)',trim=duration=22.831,setpts=PTS-STARTPTS[v8];\
[18:v]scale=1920:-2,pad=1920:1080:0:(oh-ih)/2:color=0x07101d,fps=30,setsar=1[bg9];[19:v]format=rgba[ov9];[bg9][ov9]overlay=0:0:enable='lt(t,4.5)',trim=duration=11.311,setpts=PTS-STARTPTS[v9];\
[v0][v1][v2][v3][v4][v5][v6][v7][v8][v9]concat=n=10:v=1:a=0[v]" \
  -map "[v]" -an -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p -r 30 \
  -movflags +faststart "$WORK/corealign_video_master.mp4"
fi

ffmpeg -y -hide_banner \
  -i "$WORK/corealign_video_master.mp4" \
  -i "$ROOT/tutorial/audio/narration_01.mp3" \
  -i "$ROOT/tutorial/audio/narration_02.mp3" \
  -i "$ROOT/tutorial/audio/narration_03.mp3" \
  -i "$ROOT/tutorial/audio/narration_04.mp3" \
  -i "$ROOT/tutorial/audio/narration_05.mp3" \
  -i "$ROOT/tutorial/audio/narration_06.mp3" \
  -i "$ROOT/tutorial/audio/narration_07.mp3" \
  -i "$ROOT/tutorial/audio/narration_08.mp3" \
  -i "$ROOT/tutorial/audio/narration_09.mp3" \
  -i "$ROOT/tutorial/audio/narration_10.mp3" \
  -i "$ROOT/tutorial/audio/background_music.mp3" \
  -i "$ROOT/tutorial/subtitles_en.srt" \
  -i "$ROOT/tutorial/subtitles_th.srt" \
  -f ffmetadata -i "$ROOT/tutorial/chapters.ffmeta" \
  -filter_complex "\
[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n1];\
[2:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n2];\
[3:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n3];\
[4:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n4];\
[5:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n5];\
[6:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n6];\
[7:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n7];\
[8:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n8];\
[9:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n9];\
[10:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n10];\
[n1][n2][n3][n4][n5][n6][n7][n8][n9][n10]concat=n=10:v=0:a=1,loudnorm=I=-16:LRA=7:TP=-1.5[voice];\
[voice]asplit=2[voiceMain][voiceSide];\
[11:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=0.10,afade=t=in:st=0:d=1.5,afade=t=out:st=231.4:d=3,atrim=duration=234.423[music];\
[music][voiceSide]sidechaincompress=threshold=0.018:ratio=8:attack=20:release=700[musicDuck];\
[voiceMain][musicDuck]amix=inputs=2:duration=first:weights='1 1',alimiter=limit=0.95,aresample=48000[mix]" \
  -map 0:v:0 -map "[mix]" -map 12:0 -map 13:0 \
  -map_metadata 14 -map_chapters 14 \
  -c:v copy -c:a aac -b:a 192k -c:s mov_text \
  -metadata title="CoreAlign TMA Complete Tutorial" \
  -metadata comment="ElevenLabs narration and original ElevenLabs Music soundtrack" \
  -metadata:s:s:0 language=eng -metadata:s:s:0 title="English" \
  -metadata:s:s:1 language=tha -metadata:s:s:1 title="Thai" \
  -disposition:s:0 0 -disposition:s:1 default \
  -t 234.423 -movflags +faststart \
  "$FINAL/CoreAlign-TMA-complete-tutorial-1080p.mp4"

ffprobe -v error \
  -show_entries format=filename,duration,size:stream=index,codec_name,width,height,r_frame_rate,channels:chapter=start_time,end_time:stream_tags=language,title \
  -of json "$FINAL/CoreAlign-TMA-complete-tutorial-1080p.mp4" \
  > "$FINAL/CoreAlign-TMA-complete-tutorial-1080p.probe.json"

echo "Rendered: $FINAL/CoreAlign-TMA-complete-tutorial-1080p.mp4"
