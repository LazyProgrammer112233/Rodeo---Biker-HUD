#!/bin/bash
# Fonts (SIL Open Font License) and the offline speech models, fetched at build time.
set -e
D=app/src/main/res/font
mkdir -p $D
G=https://github.com/google/fonts/raw/main/ofl/barlowcondensed
for p in SemiBold:semibold Bold:bold ExtraBold:extrabold Black:black BoldItalic:bold_italic ExtraBoldItalic:extrabold_italic BlackItalic:black_italic; do
  curl -sSLf --retry 3 -o "$D/barlow_condensed_${p#*:}.ttf" "$G/BarlowCondensed-${p%%:*}.ttf"
done
curl -sSLf --retry 3 -o /tmp/inter.zip https://github.com/rsms/inter/releases/download/v4.0/Inter-4.0.zip
unzip -q -o /tmp/inter.zip 'extras/ttf/Inter-Regular.ttf' 'extras/ttf/Inter-Medium.ttf' 'extras/ttf/Inter-SemiBold.ttf' 'extras/ttf/Inter-Bold.ttf' -d /tmp/inter
for p in Regular:regular Medium:medium SemiBold:semibold Bold:bold; do
  cp "/tmp/inter/extras/ttf/Inter-${p%%:*}.ttf" "$D/inter_${p#*:}.ttf"
done
ls -la $D

A=app/src/main/assets
mkdir -p $A
if [ ! -f $A/vosk-model/am/final.mdl ]; then
  curl -sSLf --retry 3 -o /tmp/vosk.zip https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip
  rm -rf /tmp/vosk && unzip -q /tmp/vosk.zip -d /tmp/vosk
  rm -rf $A/vosk-model && mv /tmp/vosk/*/ $A/vosk-model
fi
rm -f $A/gesture_recognizer.task
du -sh $A/*
