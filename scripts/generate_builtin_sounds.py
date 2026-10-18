#!/usr/bin/env python3
"""Generate original, short PCM WAV UI effects using simple deterministic synthesis."""
from pathlib import Path
import math, random, struct, wave
import argparse

OUT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/sounds'
RATE = 48000
SOUNDS = [
    ('soft-click', 'sine', 1150, .055), ('soft-pop', 'sine', 720, .075), ('key-tap', 'triangle', 1500, .045),
    ('key-tap-low', 'triangle', 920, .055), ('wood-click', 'sine', 540, .075), ('wood-knock', 'sine', 360, .10),
    ('mechanical-tick', 'square', 2100, .035), ('mechanical-clack', 'noise', 1400, .06), ('wheel-tick', 'sine', 1850, .032),
    ('wheel-soft', 'triangle', 1250, .04), ('bass-thump', 'sine', 68, .16), ('bass-pulse', 'sine', 92, .13),
    ('bass-knock', 'sine', 115, .12), ('electronic-blip', 'sine', 880, .075), ('electronic-high', 'sine', 1320, .06),
    ('electronic-low', 'sine', 440, .09), ('chirp-up', 'chirp', 550, .12), ('chirp-down', 'chirp-down', 1350, .12),
    ('notification-one', 'sine', 660, .18), ('notification-two', 'sine', 880, .22), ('notification-three', 'triangle', 990, .25),
    ('subtle-confirm', 'triangle', 540, .11), ('subtle-cancel', 'triangle', 260, .13), ('soft-alert', 'sine', 740, .20),
    ('wooden-tick', 'sine', 780, .06), ('wooden-pop', 'sine', 610, .085), ('ceramic-click', 'sine', 2200, .04),
    ('rubber-tap', 'sine', 400, .06), ('glass-tick', 'sine', 1750, .05), ('paper-tap', 'noise', 2800, .045),
    ('click-round', 'sine', 1000, .05), ('click-crisp', 'square', 1650, .035), ('muted-thud', 'sine', 125, .10),
    ('short-impulse', 'impulse', 900, .025), ('rise-sweep', 'sweep', 300, .18), ('fall-sweep', 'sweep-down', 1100, .18),
    ('octine-function', 'sine', 155, .038),
    ('octine-technical', 'sine', 205, .028), ('octine-super', 'chirp', 95, .065),
    ('octine-delete', 'triangle', 82, .048),
    ('octine-workspace', 'sweep', 65, .120), ('octine-notification', 'sine', 230, .085),
]

def sample(kind, i, total, hz, rng):
    t = i / RATE
    progress = i / max(1, total - 1)
    if kind in ('chirp', 'chirp-down', 'sweep', 'sweep-down'):
        end = hz * (2.0 if kind in ('chirp','sweep') else .5)
        phase = 2 * math.pi * (hz * t + .5 * (end - hz) * t * t / max(total / RATE, .001))
        value = math.sin(phase)
    elif kind == 'sine': value = math.sin(2 * math.pi * hz * t)
    elif kind == 'triangle': value = 2 * abs(2 * ((hz*t) % 1) - 1) - 1
    elif kind == 'square': value = 1 if math.sin(2 * math.pi * hz * t) >= 0 else -1
    elif kind == 'noise': value = rng.uniform(-1,1)
    elif kind == 'impulse': value = (1 - progress * 2) if i < total//2 else -0.15 * (progress)
    else: value = 0
    attack = min(1, i / max(1, RATE * .002))
    decay = math.exp(-progress * (4.2 if 'bass' in kind else 7.2))
    release = min(1, (total-i) / max(1, RATE*.006))
    envelope = attack * decay * release
    return value * envelope

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--only', nargs='+', help='Generate only the named assets; leave all others untouched.')
args = parser.parse_args()
selected = [sound for sound in SOUNDS if not args.only or sound[0] in args.only]
if args.only and set(args.only) - {sound[0] for sound in SOUNDS}:
    parser.error('Unknown sound ID in --only')
OUT.mkdir(parents=True, exist_ok=True)
for name, kind, hz, secs in selected:
    rng = random.Random(name)
    total = max(1, round(RATE*secs))
    values = [sample(kind, i, total, hz, rng) for i in range(total)]
    peak = max(map(abs, values)) or 1
    scale = (.92 if name.startswith('octine-') else .38) / peak
    pcm = b''.join(struct.pack('<h', int(max(-1,min(1,v*scale))*32767)) for v in values)
    with wave.open(str(OUT / f'{name}.wav'), 'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(RATE); f.writeframes(pcm)
print(f'Generated {len(selected)} original mono PCM WAV assets in {OUT}')
