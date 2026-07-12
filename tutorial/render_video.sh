#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/tutorial/work"
FINAL="$ROOT/tutorial/final"
PYTHON="/Users/heng/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
OLD_MASTER="$WORK/corealign_video_master.mp4"
NEW_MASTER="$WORK/corealign_validated_video_master_v2.mp4"
OUTPUT="$FINAL/CoreAlign-TMA-validated-tutorial-v2-1080p.mp4"

mkdir -p "$WORK" "$FINAL"
"$PYTHON" "$ROOT/tutorial/render_overlays.py"

if [[ ! -f "$OLD_MASTER" ]]; then
  echo "Missing local tutorial/work/corealign_video_master.mp4. Restore the local v1 render source before rebuilding v2." >&2
  exit 1
fi

ffmpeg -y -hide_banner \
  -i "$OLD_MASTER" \
  -loop 1 -t 15 -i "$WORK/preflight_guard.png" \
  -filter_complex "[0:v]trim=start=0:end=55.928,setpts=PTS-STARTPTS[v0];[1:v]scale=1920:1080,fps=30,setsar=1,trim=duration=15,setpts=PTS-STARTPTS[v1];[0:v]trim=start=55.928:end=234.423,setpts=PTS-STARTPTS[v2];[v0][v1][v2]concat=n=3:v=1:a=0[v]" \
  -map "[v]" -an -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p -r 30 \
  -movflags +faststart "$NEW_MASTER"

ffmpeg -y -hide_banner \
  -i "$NEW_MASTER" \
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
  -stream_loop -1 -i "$ROOT/tutorial/audio/background_music.mp3" \
  -i "$ROOT/tutorial/subtitles_en.srt" \
  -i "$ROOT/tutorial/subtitles_th.srt" \
  -f ffmetadata -i "$ROOT/tutorial/chapters.ffmeta" \
  -filter_complex "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n1];[2:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n2];[3:a]atrim=end=12.2,apad=pad_dur=4.831813,atrim=duration=17.031813,aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n3];anullsrc=r=48000:cl=stereo:d=15[silence];[4:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n4];[5:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n5];[6:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n6];[7:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n7];[8:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n8];[9:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n9];[10:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[n10];[n1][n2][n3][silence][n4][n5][n6][n7][n8][n9][n10]concat=n=11:v=0:a=1,loudnorm=I=-16:LRA=7:TP=-1.5[voice];[voice]asplit=2[voiceMain][voiceSide];[11:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=0.10,afade=t=in:st=0:d=1.5,afade=t=out:st=246.4:d=3,atrim=duration=249.423[music];[music][voiceSide]sidechaincompress=threshold=0.018:ratio=8:attack=20:release=700[musicDuck];[voiceMain][musicDuck]amix=inputs=2:duration=first:weights='1 1',alimiter=limit=0.95,aresample=48000[mix]" \
  -map 0:v:0 -map "[mix]" -map 12:0 -map 13:0 \
  -map_metadata 14 -map_chapters 14 \
  -c:v copy -c:a aac -b:a 192k -c:s mov_text \
  -metadata title="CoreAlign TMA Validated Tutorial Version 2" \
  -metadata comment="ElevenLabs narration and original ElevenLabs Music soundtrack" \
  -metadata:s:s:0 language=eng -metadata:s:s:0 title="English" \
  -metadata:s:s:1 language=tha -metadata:s:s:1 title="Thai" \
  -disposition:s:0 0 -disposition:s:1 default \
  -t 249.423 -movflags +faststart "$OUTPUT"

ffprobe -v error \
  -show_entries format=filename,duration,size:stream=index,codec_name,width,height,r_frame_rate,channels:chapter=start_time,end_time:stream_tags=language,title \
  -of json "$OUTPUT" > "$FINAL/CoreAlign-TMA-validated-tutorial-v2-1080p.probe.json"

echo "Rendered: $OUTPUT"
