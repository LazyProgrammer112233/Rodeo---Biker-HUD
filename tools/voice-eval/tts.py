"""Records the clean speech for the voice evaluation: many synthetic riders (Indian-accented ones
first) saying wake phrases, commands, SOS words and everyday chatter. Noise is mixed in later by
the evaluation itself, so this only has to run when the lines or voices change.

Writes 16 kHz mono WAV files and lines.json into the output folder.

    python3 tts.py OUT_DIR PIPER_VOICES_DIR
"""
import json
import os
import subprocess
import sys
import wave

import numpy as np

RATE = 16000
OUT = sys.argv[1]
VOICES_DIR = sys.argv[2] if len(sys.argv) > 2 else ""

WAKES = ["Hey Rodeo", "Hey Doku", "Okay Rodeo"]

# (kind, expected command, words). Commands are said after a wake phrase.
LINES = [
    ("sos", "SOS", "emergency"),
    ("sos", "SOS", "emergency emergency"),
    ("sos", "SOS", "call an ambulance"),
    ("sos", "SOS", "I have had an accident"),
    ("sos", "SOS", "help me"),
    ("sos", "SOS", "send SOS"),
    ("cmd", "NEXT", "next song"),
    ("cmd", "NEXT", "play the next song"),
    ("cmd", "NEXT", "skip this song"),
    ("cmd", "PREVIOUS", "previous song"),
    ("cmd", "PREVIOUS", "go back to the last song"),
    ("cmd", "PAUSE", "pause the music"),
    ("cmd", "PAUSE", "stop the music"),
    ("cmd", "PLAY", "play music"),
    ("cmd", "PLAY", "resume the song"),
    ("cmd", "VOLUME_UP", "volume up"),
    ("cmd", "VOLUME_UP", "turn the volume up"),
    ("cmd", "VOLUME_DOWN", "volume down"),
    ("cmd", "VOLUME_DOWN", "make it quieter"),
    ("cmd", "NAVIGATE_HOME", "take me home"),
    ("cmd", "NAVIGATE_TO", "navigate to Nandi Hills"),
    ("cmd", "NAVIGATE_TO", "take me to MG Road"),
    ("cmd", "NAVIGATE_TO", "navigate to the nearest petrol pump"),
    ("cmd", "NAVIGATE_TO", "directions to Lalbagh"),
    ("cmd", "STOP_NAVIGATION", "stop navigation"),
    ("cmd", "SPEED", "what is my speed"),
    ("cmd", "ETA", "how far is it"),
    ("cmd", "TIME", "what time is it"),
    ("cmd", "START_RIDE", "start the ride"),
    ("cmd", "END_RIDE", "end the ride"),
]
# Said with no wake phrase: must not wake or send SOS (except the repeated SOS words).
CHATTER = [
    "so I told him to take a look at the engine",
    "the doctor said it is fine",
    "turn left after the signal and then go straight",
    "hey there, how are you doing",
    "okay, let's stop at the petrol pump",
    "did you see that road",
    "hello, can you hear me",
    "the radio is playing old songs",
    "that was an emergency exit sign",
    "hey, do you know the way",
    "I need some help with the bike",
    "we will reach Mysore by lunch",
    "hey, that rodeo show was good",
    "the next song on the radio is nice",
    "okay, I will call you back",
    "hey brother, ride slowly",
    "the road ahead is under construction",
    "let us go to the rally on Sunday",
    "my phone battery is low",
    "the volume of traffic is high today",
]
REPEAT_SOS = ["emergency emergency", "emergency, emergency, emergency", "help help help", "accident accident"]

TEXTS = sorted({w for w in WAKES} | {l for _, _, l in LINES} | set(CHATTER) | set(REPEAT_SOS))


def to_rate(x, rate):
    if rate == RATE:
        return x
    n = int(round(len(x) * RATE / rate))
    spec = np.fft.rfft(x)
    keep = n // 2 + 1
    spec = spec[:keep] if keep <= len(spec) else np.concatenate([spec, np.zeros(keep - len(spec))])
    return np.fft.irfft(spec, n) * (n / len(x))


def read_wav(path):
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
        if w.getnchannels() > 1:
            data = data.reshape(-1, w.getnchannels()).mean(axis=1)
    return to_rate(data / 32768.0, rate)


def trim(x):
    a = np.abs(x)
    on = np.where(a > 0.01 * a.max())[0]
    return x if len(on) == 0 else x[max(0, on[0] - 400): on[-1] + 400]


def save(path, x):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(x / max(1e-9, np.abs(x).max()) * 0.8, -1, 1) * 32767).astype(np.int16).tobytes())


class Piper:
    def __init__(self, onnx):
        from piper import PiperVoice  # noqa
        self.voice = PiperVoice.load(onnx)
        self.speakers = json.load(open(onnx + ".json")).get("speaker_id_map", {})

    def say(self, text, speaker, speed, noise, path):
        sid = self.speakers.get(speaker) if speaker else None
        with wave.open(path, "wb") as w:
            if hasattr(self.voice, "synthesize_wav"):
                from piper import SynthesisConfig  # noqa
                self.voice.synthesize_wav(text, w, syn_config=SynthesisConfig(speaker_id=sid, length_scale=speed, noise_scale=noise))
            else:
                self.voice.synthesize(text, w, speaker_id=sid, length_scale=speed, noise_scale=noise)
        return read_wav(path)


def espeak(text, voice, speed, pitch, path):
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(int(165 / speed)), "-p", str(pitch), "-w", path, text], check=True)
    return read_wav(path)


def voices():
    """(name, say(text, speed) -> samples). Indian-accented VCTK speakers first."""
    out = []
    tmp = os.path.join(OUT, "tmp.wav")
    vctk = os.path.join(VOICES_DIR, "en_GB-vctk-medium.onnx")
    if os.path.exists(vctk):
        p = Piper(vctk)
        indian = ["p248", "p251", "p376"]
        others = ["p225", "p226", "p231", "p237", "p245", "p254", "p260", "p270", "p283", "p302"]
        for spk in indian + others:
            if spk in p.speakers:
                tag = "indian" if spk in indian else "uk"
                out.append((f"vctk-{spk}-{tag}", lambda t, s, spk=spk, p=p: p.say(t, spk, s, 0.667, tmp)))
    libri = os.path.join(VOICES_DIR, "en_US-libritts_r-medium.onnx")
    if os.path.exists(libri):
        p = Piper(libri)
        for spk in list(p.speakers)[::90][:10]:
            out.append((f"libritts-{spk}-us", lambda t, s, spk=spk, p=p: p.say(t, spk, s, 0.667, tmp)))
    for name in ["en_US-lessac-medium", "en_GB-alan-medium"]:
        onnx = os.path.join(VOICES_DIR, name + ".onnx")
        if os.path.exists(onnx):
            p = Piper(onnx)
            out.append((name.split("-")[1], lambda t, s, p=p: p.say(t, None, s, 0.667, tmp)))
    for v, pitch in [("en-us", 50), ("en-gb-x-rp", 35), ("en-us+f3", 60)]:
        out.append((f"espeak-{v}", lambda t, s, v=v, pitch=pitch: espeak(t, v, s, pitch, tmp)))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    index = {"rate": RATE, "wakes": WAKES, "lines": LINES, "chatter": CHATTER, "repeatSos": REPEAT_SOS, "voices": {}}
    for vname, say in voices():
        files = {}
        try:
            for i, text in enumerate(TEXTS):
                speeds = [0.85, 1.0, 1.15] if text in WAKES else [1.0]
                for speed in speeds:
                    name = f"{vname}_{i:03d}_{int(speed * 100)}.wav"
                    save(os.path.join(OUT, name), trim(say(text, speed)))
                    files.setdefault(text, []).append(name)
        except Exception as e:  # noqa
            print(vname, "failed:", e)
            continue
        index["voices"][vname] = files
        print(vname, "done")
    json.dump(index, open(os.path.join(OUT, "lines.json"), "w"), indent=1)
    tmp = os.path.join(OUT, "tmp.wav")
    if os.path.exists(tmp):
        os.remove(tmp)


main()
