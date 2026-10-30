#!/bin/bash
# Fonts (SIL Open Font License) and the offline speech engine and model, fetched at build time.
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
# Offline speech: sherpa-onnx (Apache 2.0) and the Kroko streaming English model (CC-BY-SA 4.0).
SHERPA=2.0.1
mkdir -p app/libs
[ -s app/libs/sherpa-onnx.aar ] || curl -sSLf --retry 3 -o app/libs/sherpa-onnx.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar
if [ ! -s $A/speech/encoder.onnx ]; then
  K=sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06
  curl -sSLf --retry 3 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$K.tar.bz2 | tar xj -C /tmp
  mkdir -p $A/speech && cp /tmp/$K/encoder.onnx /tmp/$K/decoder.onnx /tmp/$K/joiner.onnx /tmp/$K/tokens.txt $A/speech/
fi
rm -rf $A/vosk-model
rm -f $A/gesture_recognizer.task
du -sh $A/*
