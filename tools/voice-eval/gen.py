"""Makes test speech for the voice evaluation: riders saying the wake phrase, commands, SOS and
everyday chatter, in several voices (Indian-accented ones first), mixed with motorcycle engine and
wind noise at several loudness levels.

Writes 16 kHz mono WAV files and manifest.json into the output folder.

    python3 gen.py OUT_DIR PIPER_VOICES_DIR
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
PHRASES = ["Hey Rodeo", "Hey Doku"]
SNRS = [None, 10, 5, 0]  # None = clean

# (kind, expected command, text after the wake phrase)
WAKE_LINES = [
    ("sos", "SOS", "emergency"),
    ("sos", "SOS", "call an ambulance"),
    ("sos", "SOS", "I have had an accident"),
    ("sos", "SOS", "help me"),
    ("cmd", "NEXT", "next song"),
    ("cmd", "PREVIOUS", "previous song"),
    ("cmd", "PAUSE", "pause the music"),
    ("cmd", "NAVIGATE_HOME", "take me home"),
    ("cmd", "NAVIGATE_TO", "navigate to Nandi Hills"),
    ("cmd", "SPEED", "what is my speed"),
]
# The wake phrase alone, a pause, then the command.
PAUSED = [("sos", "SOS", "emergency"), ("cmd", "NEXT", "next song")]
NO_WAKE_SOS = ["emergency emergency", "emergency, emergency, emergency"]
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
]


def to_rate(x, rate):
    """FFT resample to 16 kHz."""
    if rate == RATE:
        return x
    n = int(round(len(x) * RATE / rate))
    spec = np.fft.rfft(x)
    keep = n // 2 + 1
    if keep <= len(spec):
        spec = spec[:keep]
    else:
        spec = np.concatenate([spec, np.zeros(keep - len(spec))])
    return np.fft.irfft(spec, n) * (n / len(x))


def read_wav(path):
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
        if w.getnchannels() > 1:
            data = data.reshape(-1, w.getnchannels()).mean(axis=1)
    return to_rate(data / 32768.0, rate)


def trim(x):
    """Cut leading and trailing silence."""
    a = np.abs(x)
    on = np.where(a > 0.01 * a.max())[0]
    if len(on) == 0:
        return x
    return x[max(0, on[0] - 400): on[-1] + 400]


class Piper:
    def __init__(self, onnx):
        from piper import PiperVoice  # noqa
        self.voice = PiperVoice.load(onnx)
        cfg = json.load(open(onnx + ".json"))
        self.speakers = cfg.get("speaker_id_map", {})

    def say(self, text, speaker, speed, path):
        sid = self.speakers.get(speaker) if speaker else None
        with wave.open(path, "wb") as w:
            if hasattr(self.voice, "synthesize_wav"):
                from piper import SynthesisConfig  # noqa
                self.voice.synthesize_wav(text, w, syn_config=SynthesisConfig(speaker_id=sid, length_scale=speed))
            else:
                self.voice.synthesize(text, w, speaker_id=sid, length_scale=speed)
        return read_wav(path)


def espeak(text, voice, speed, path):
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(int(160 / speed)), "-w", path, text], check=True)
    return read_wav(path)


def voices():
    """(name, say(text, speed) -> samples). Indian-accented VCTK speakers first."""
    out = []
    tmp = os.path.join(OUT, "tmp.wav")
    vctk = os.path.join(VOICES_DIR, "en_GB-vctk-medium.onnx")
    if os.path.exists(vctk):
        try:
            p = Piper(vctk)
            for spk in ["p248", "p251", "p376"]:
                if spk in p.speakers:
                    out.append((f"vctk-{spk}-indian", lambda t, s, spk=spk, p=p: p.say(t, spk, s, tmp)))
        except Exception as e:  # noqa
            print("vctk failed:", e)
    for name in ["en_US-lessac-medium", "en_GB-alan-medium"]:
        onnx = os.path.join(VOICES_DIR, name + ".onnx")
        if os.path.exists(onnx):
            try:
                p = Piper(onnx)
                out.append((name.split("-")[1], lambda t, s, p=p: p.say(t, None, s, tmp)))
            except Exception as e:  # noqa
                print(name, "failed:", e)
    try:
        subprocess.run(["espeak-ng", "--version"], check=True, capture_output=True)
        out.append(("espeak-en-us", lambda t, s: espeak(t, "en-us", s, tmp)))
    except Exception:  # noqa
        pass
    return out


def engine_and_wind(n, rng):
    """Single-cylinder engine thump and its harmonics, exhaust rasp, and gusty helmet wind."""
    t = np.arange(n) / RATE
    f0 = rng.uniform(28, 55)
    wobble = 1 + 0.01 * np.sin(2 * np.pi * rng.uniform(0.3, 1.0) * t)
    phase = 2 * np.pi * np.cumsum(f0 * wobble) / RATE
    eng = sum(np.sin(k * phase + rng.uniform(0, 6.28)) / k ** 0.7 for k in range(1, 40))
    rasp = np.fft.irfft(np.fft.rfft(rng.standard_normal(n)) * band(n, 150, 2500), n)
    white = rng.standard_normal(n)
    brown = np.cumsum(white)
    brown -= np.convolve(brown, np.ones(2000) / 2000, mode="same")
    wind = np.fft.irfft(np.fft.rfft(brown) * band(n, 20, 700), n)
    gust = 0.55 + 0.45 * np.sin(2 * np.pi * rng.uniform(0.2, 0.6) * t + rng.uniform(0, 6.28))
    parts = [eng, rasp, wind * gust]
    parts = [p / (np.sqrt(np.mean(p ** 2)) + 1e-9) for p in parts]
    return 0.45 * parts[0] + 0.2 * parts[1] + 0.6 * parts[2]


def band(n, lo, hi):
    f = np.fft.rfftfreq(n, 1 / RATE)
    return ((f >= lo) & (f <= hi)).astype(float)


def mix(speech, snr, rng):
    lead, tail = int(0.8 * RATE), int(1.6 * RATE)
    x = np.concatenate([np.zeros(lead), speech, np.zeros(tail)])
    if snr is not None:
        active = speech[np.abs(speech) > 0.02 * np.abs(speech).max()]
        s_rms = np.sqrt(np.mean(active ** 2))
        noise = engine_and_wind(len(x), rng)
        x = x + noise / np.sqrt(np.mean(noise ** 2)) * s_rms / (10 ** (snr / 20))
    peak = np.abs(x).max()
    if peak > 0.95:
        x *= 0.95 / peak
    return x, lead / RATE, (lead + len(speech)) / RATE


def save(path, x):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())


def main():
    os.makedirs(OUT, exist_ok=True)
    rng = np.random.default_rng(7)
    manifest = []
    vs = voices()
    print("voices:", [v[0] for v in vs])
    gap = np.zeros(int(0.12 * RATE))
    pause = np.zeros(int(1.3 * RATE))
    for vname, say in vs:
        cache = {}

        def speech(text, speed=1.0):
            key = (text, speed)
            if key not in cache:
                cache[key] = trim(say(text, speed))
            return cache[key]

        clips = []  # (id, phrase, kind, expect, text, samples, wake_end_offset)
        for phrase in PHRASES:
            pk = phrase.split()[-1].lower()
            for i, speed in enumerate([0.9, 1.0, 1.12]):
                clips.append((f"train-{pk}-{i}", phrase, "train", "", phrase, speech(phrase, speed), None))
            for j, (kind, expect, line) in enumerate(WAKE_LINES):
                # Said in one breath, the way people talk to assistants.
                text = f"{phrase}, {line}"
                w = speech(phrase)
                clips.append((f"{pk}-{kind}-{j}", phrase, kind, expect, text, np.concatenate([w, gap, speech(line)]), len(w)))
            for j, (kind, expect, line) in enumerate(PAUSED):
                w = speech(phrase)
                clips.append((f"{pk}-pause-{j}", phrase, kind, expect, f"{phrase} ... {line}", np.concatenate([w, pause, speech(line)]), len(w)))
        for j, line in enumerate(NO_WAKE_SOS):
            clips.append((f"repeat-{j}", "", "repeat", "SOS", line, speech(line), None))
        for j, line in enumerate(CHATTER):
            clips.append((f"chatter-{j}", "", "chatter", "", line, speech(line), None))

        for cid, phrase, kind, expect, text, samples, wake_end in clips:
            for snr in ([None] if kind == "train" else SNRS):
                tag = "clean" if snr is None else f"{snr}dB"
                x, start, end = mix(samples, snr, rng)
                name = f"{vname}_{cid}_{tag}.wav"
                save(os.path.join(OUT, name), x)
                manifest.append({
                    "file": name, "voice": vname, "id": cid, "phrase": phrase, "kind": kind,
                    "expect": expect, "text": text, "snr": tag, "speechStart": start, "speechEnd": end,
                    "wakeEnd": None if wake_end is None else start + wake_end / RATE,
                })
        print(vname, "done", len(manifest))
    json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
    tmp = os.path.join(OUT, "tmp.wav")
    if os.path.exists(tmp):
        os.remove(tmp)


main()
