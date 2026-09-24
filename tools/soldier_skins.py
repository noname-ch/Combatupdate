#!/usr/bin/env python3
"""Draws the Gipfaeli soldier's uniforms.

Seventeen squad uniforms (one per dye, and the camouflage a recruit starts in) and the commander's
black dress uniform with the squad colour on it, for each. Then the same again in every field
pattern a squad can be put in (woodland, snow, desert, jungle, urban, night): the whole uniform in
the pattern, the squad's colour only on the cap band, the shoulders and a chest patch. Run from the repository root; it writes
straight into the mod's entity textures. Deterministic, so re-running it changes nothing unless the
drawing below does.

The layout is the standard 64x64 skin. The soldier's model is vanilla's plain humanoid, which reads
the head, hat, body, right arm and right leg and mirrors the limbs, but the left limbs are painted
too so the file is a complete skin whichever model ends up wearing it.
"""
import os
import random

from PIL import Image

OUT = "Gipfeliarmy/src/main/resources/assets/gipfeliarmy/textures/entity/gipfaeli_soldier"

# Minecraft's own dye colours, muted a little towards grey: a uniform is cloth, not paint.
DYES = {
    "white": 0xF9FFFE, "orange": 0xF9801D, "magenta": 0xC74EBD, "light_blue": 0x3AB3DA,
    "yellow": 0xFED83D, "lime": 0x80C71F, "pink": 0xF38BAA, "gray": 0x474F52,
    "light_gray": 0x9D9D97, "cyan": 0x169C9C, "purple": 0x8932B8, "blue": 0x3C44AA,
    "brown": 0x835432, "green": 0x5E7C16, "red": 0xB02E26, "black": 0x1D1D21,
}

SKIN = (0xE8, 0xB9, 0x8E)
SKIN_SHADE = (0xC9, 0x97, 0x6C)
HAIR = (0x4A, 0x2E, 0x17)
EYE_WHITE = (0xF4, 0xF4, 0xF4)
PUPIL = (0x3A, 0x4A, 0x7A)
MOUTH = (0xA8, 0x6C, 0x58)
BOOT = (0x2B, 0x22, 0x1B)
BOOT_TOP = (0x3E, 0x31, 0x25)
BELT = (0x5A, 0x3B, 0x1F)
BUCKLE = (0xD9, 0xB1, 0x3F)
BADGE = (0xD9, 0xB1, 0x3F)
BUTTON = (0x2A, 0x2A, 0x2E)
FLAG_RED = (0xC8, 0x2A, 0x2A)
FLAG_WHITE = (0xF6, 0xF6, 0xF6)
COMMANDER_CLOTH = (0x25, 0x27, 0x2C)

# How much light each face of a box gets: lit from above and a little from the front.
SHADE = {"top": 1.0, "front": 0.93, "left": 0.84, "right": 0.84, "back": 0.74, "bottom": 0.62}

# The boxes of the skin: origin, width, height, depth.
PARTS = {
    "head": (0, 0, 8, 8, 8),
    "hat": (32, 0, 8, 8, 8),
    "body": (16, 16, 8, 12, 4),
    "rarm": (40, 16, 4, 12, 4),
    "rleg": (0, 16, 4, 12, 4),
    "lleg": (16, 48, 4, 12, 4),
    "larm": (32, 48, 4, 12, 4),
}


def faces(part):
    u, v, w, h, d = PARTS[part]
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def rgb(value):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def mix(a, b, t):
    return tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3))


def scale(c, f):
    return tuple(max(0, min(255, round(v * f))) for v in c)


def luminance(c):
    return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255


def grain(x, y):
    # A touch of weave, the same every run: cloth and skin are not flat.
    h = (x * 73856093 ^ y * 19349663) & 0xFFFF
    return 1.0 + ((h % 9) - 4) / 100.0


class Cloth:
    """A material that knows its own colour at every pixel, so camouflage can be one too."""

    def __init__(self, solid=None, pattern=None):
        self.solid = solid
        self.pattern = pattern

    def at(self, x, y):
        return self.solid if self.pattern is None else self.pattern[y][x]

    def darker(self, f=0.72):
        if self.pattern is None:
            return Cloth(scale(self.solid, f))
        return Cloth(pattern=[[scale(c, f) for c in row] for row in self.pattern])

    def lighter(self, f=1.18):
        return self.darker(f)


WOODLAND = [(0x5B, 0x6B, 0x3A), (0x6B, 0x4F, 0x2E), (0x2F, 0x3F, 0x22), (0x9A, 0x8B, 0x5C)]

# The field patterns a squad can be put in, as the game names them (see GipfaeliCamo): the ground
# tone first, then the blotches over it.
PATTERNS = {
    "woodland": (WOODLAND, 7),
    "snow": ([(0xE8, 0xEC, 0xEE), (0xC4, 0xCB, 0xD0), (0xA3, 0xAC, 0xB3), (0xFA, 0xFB, 0xFC)], 11),
    "desert": ([(0xC9, 0xAE, 0x7C), (0xA8, 0x85, 0x55), (0xDD, 0xC9, 0x9E), (0x8A, 0x6B, 0x44)], 13),
    "jungle": ([(0x3F, 0x5E, 0x2A), (0x24, 0x3B, 0x1A), (0x6B, 0x8A, 0x35), (0x4A, 0x36, 0x22)], 17),
    "urban": ([(0x80, 0x82, 0x84), (0x5A, 0x5C, 0x60), (0xA8, 0xAA, 0xAC), (0x3A, 0x3C, 0x40)], 19),
    "night": ([(0x2A, 0x2F, 0x3A), (0x1A, 0x1E, 0x26), (0x3E, 0x46, 0x55), (0x12, 0x14, 0x1A)], 23),
}


def camo_pattern(seed=7, tones=WOODLAND):
    """Blotches of four field tones over a 64x64 sheet, the same blotches every run."""
    rnd = random.Random(seed)
    sheet = [[tones[0]] * 64 for _ in range(64)]
    for _ in range(140):
        tone = rnd.choice(tones[1:])
        cx, cy = rnd.randrange(64), rnd.randrange(64)
        rx, ry = rnd.randint(1, 4), rnd.randint(1, 3)
        for y in range(64):
            for x in range(64):
                dx = min(abs(x - cx), 64 - abs(x - cx)) / rx
                dy = min(abs(y - cy), 64 - abs(y - cy)) / ry
                if dx * dx + dy * dy <= 1.0:
                    sheet[y][x] = tone
    return sheet


class Skin:
    def __init__(self):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.px = self.img.load()

    def put(self, part, face, x, y, colour, shaded=True):
        fx, fy, fw, fh = faces(part)[face]
        if not (0 <= x < fw and 0 <= y < fh):
            return
        gx, gy = fx + x, fy + y
        c = colour.at(x, y) if isinstance(colour, Cloth) else colour
        if shaded:
            c = scale(c, SHADE[face] * grain(gx, gy))
        self.px[gx, gy] = (c[0], c[1], c[2], 255)

    def fill(self, part, face, colour, rows=None, cols=None):
        fx, fy, fw, fh = faces(part)[face]
        for y in (rows if rows is not None else range(fh)):
            for x in (cols if cols is not None else range(fw)):
                self.put(part, face, x, y, colour)

    def fill_all(self, part, colour, rows=None, sides=("front", "back", "left", "right")):
        for face in sides:
            self.fill(part, face, colour, rows=rows)

    def save(self, path):
        self.img.save(path)


def draw_head(skin, cloth, band, commander):
    for face in ("top", "bottom", "front", "back", "left", "right"):
        skin.fill("head", face, SKIN)
    skin.fill("head", "bottom", SKIN_SHADE)
    # Hair: over the top and down the back and sides to the ears, and a fringe under the cap.
    skin.fill("head", "top", HAIR)
    for face in ("back", "left", "right"):
        skin.fill("head", face, HAIR, rows=range(0, 3))
    skin.fill("head", "front", HAIR, rows=range(0, 2))
    # The face: brows, eyes looking straight ahead, a nose and a mouth, and a shadow under the chin.
    for x in (2, 3, 5, 6):
        skin.put("head", "front", x, 3, HAIR)
    skin.put("head", "front", 2, 4, EYE_WHITE)
    skin.put("head", "front", 3, 4, PUPIL)
    skin.put("head", "front", 4, 4, PUPIL)
    skin.put("head", "front", 5, 4, EYE_WHITE)
    skin.put("head", "front", 3, 5, SKIN_SHADE)
    skin.put("head", "front", 4, 5, SKIN_SHADE)
    skin.put("head", "front", 3, 6, MOUTH)
    skin.put("head", "front", 4, 6, MOUTH)
    skin.fill("head", "front", SKIN_SHADE, rows=[7])
    # Ears, a shade darker where they stand off the head.
    for face in ("left", "right"):
        skin.put("head", face, 3 if face == "left" else 4, 4, SKIN_SHADE)

    # The cap, on the hat layer: a field cap in the squad's cloth with a darker band, or the
    # commander's in black with the squad colour round it and a badge at the front.
    skin.fill("hat", "top", cloth)
    for face in ("front", "back", "left", "right"):
        skin.fill("hat", face, cloth, rows=range(0, 2))
        skin.fill("hat", face, band, rows=[2])
    if commander:
        skin.put("hat", "front", 3, 1, BADGE)
        skin.put("hat", "front", 4, 1, BADGE)


def draw_body(skin, cloth, trousers, accent, commander):
    dark = cloth.darker()
    skin.fill("body", "top", dark)
    skin.fill("body", "bottom", trousers.darker())
    for face in ("front", "back", "left", "right"):
        skin.fill("body", face, cloth, rows=range(0, 9))
        skin.fill("body", face, BELT, rows=[9])
        skin.fill("body", face, trousers, rows=range(10, 12))
    # The jacket's front: a collar, a row of buttons down the middle and a pocket either side.
    skin.fill("body", "front", dark, rows=[0], cols=range(2, 6))
    for y in (2, 4, 6):
        skin.put("body", "front", 4, y, BUTTON)
    for x0 in (0, 5):
        skin.fill("body", "front", dark, rows=[4], cols=range(x0, x0 + 3))
        skin.fill("body", "front", cloth.lighter(1.06), rows=range(5, 7), cols=range(x0, x0 + 3))
    skin.fill("body", "front", dark, rows=[8])
    skin.fill("body", "back", dark, rows=[1])
    skin.fill("body", "back", dark, rows=[8])
    # The buckle.
    skin.put("body", "front", 3, 9, BUCKLE)
    skin.put("body", "front", 4, 9, BUCKLE)
    if commander:
        # Lapels in the squad's colour down the front of the black jacket.
        for x in (2, 5):
            skin.fill("body", "front", accent, rows=range(1, 8), cols=[x])


def draw_arm(skin, part, cloth, accent, commander, armband):
    dark = cloth.darker()
    skin.fill(part, "top", cloth)
    skin.fill(part, "bottom", SKIN_SHADE)
    for face in ("front", "back", "left", "right"):
        skin.fill(part, face, cloth, rows=range(0, 9))
        skin.fill(part, face, cloth.lighter(1.08), rows=[0])
        skin.fill(part, face, dark, rows=[8])
        skin.fill(part, face, SKIN, rows=range(9, 12))
    if commander:
        # Epaulettes and a rank stripe in the squad's colour.
        skin.fill(part, "top", accent)
        for face in ("front", "back", "left", "right"):
            skin.fill(part, face, accent, rows=[0])
            skin.fill(part, face, accent, rows=[6])
    if armband:
        # The army's mark on the upper arm: a red band with a white cross on its outer face.
        for face in ("front", "back", "left", "right"):
            skin.fill(part, face, FLAG_RED, rows=range(3, 6))
        outer = "right" if part == "rarm" else "left"
        for face in (outer, "front"):
            skin.put(part, face, 1, 3, FLAG_WHITE)
            skin.put(part, face, 2, 3, FLAG_WHITE)
            skin.fill(part, face, FLAG_WHITE, rows=[4])
            skin.put(part, face, 1, 5, FLAG_WHITE)
            skin.put(part, face, 2, 5, FLAG_WHITE)


def draw_leg(skin, part, trousers, accent, commander):
    skin.fill(part, "top", trousers)
    skin.fill(part, "bottom", BOOT)
    for face in ("front", "back", "left", "right"):
        skin.fill(part, face, trousers, rows=range(0, 9))
        skin.fill(part, face, trousers.darker(0.85), rows=[4])
        skin.fill(part, face, BOOT_TOP, rows=[9])
        skin.fill(part, face, BOOT, rows=range(10, 12))
    if commander:
        # A stripe down the outside seam.
        outer = "right" if part == "rleg" else "left"
        skin.fill(part, outer, accent, rows=range(0, 9), cols=[0])


def uniform(cloth, accent, commander):
    skin = Skin()
    jacket = Cloth(COMMANDER_CLOTH) if commander else cloth
    trousers = jacket.darker(0.78)
    band = accent if commander else cloth.darker()
    draw_head(skin, jacket, band, commander)
    draw_body(skin, jacket, trousers, accent, commander)
    draw_arm(skin, "rarm", jacket, accent, commander, armband=True)
    draw_arm(skin, "larm", jacket, accent, commander, armband=False)
    draw_leg(skin, "rleg", trousers, accent, commander)
    draw_leg(skin, "lleg", trousers, accent, commander)
    return skin


def field_uniform(cloth, accent, commander):
    """A uniform in a field pattern: the pattern everywhere, the squad colour in a few places."""
    skin = Skin()
    trousers = cloth.darker(0.9)
    draw_head(skin, cloth, accent, commander)
    draw_body(skin, cloth, trousers, accent, commander)
    draw_arm(skin, "rarm", cloth, accent, commander, armband=True)
    draw_arm(skin, "larm", cloth, accent, commander, armband=False)
    draw_leg(skin, "rleg", trousers, accent, commander)
    draw_leg(skin, "lleg", trousers, accent, commander)
    if not commander:
        # The squad's colour: across the shoulders and a patch on the chest pocket.
        for part in ("rarm", "larm"):
            skin.fill(part, "top", accent)
            for face in ("front", "back", "left", "right"):
                skin.fill(part, face, accent, rows=[0])
        skin.fill("body", "front", accent, rows=range(2, 4), cols=range(5, 7))
    return skin


def main():
    os.makedirs(OUT, exist_ok=True)
    cloths = {name: Cloth(mix(rgb(value), (0x80, 0x80, 0x80), 0.18)) for name, value in DYES.items()}
    cloths["camo"] = Cloth(pattern=camo_pattern())
    for name, cloth in cloths.items():
        # The commander's accent has to show on black cloth, so a dark squad colour is lifted.
        accent = cloth
        if cloth.solid is not None and luminance(cloth.solid) < 0.25:
            accent = Cloth(mix(cloth.solid, (0xFF, 0xFF, 0xFF), 0.35))
        uniform(cloth, accent, False).save(os.path.join(OUT, name + ".png"))
        uniform(cloth, accent, True).save(os.path.join(OUT, "commander_" + name + ".png"))
    written = 2 * len(cloths)

    for pattern, (tones, seed) in PATTERNS.items():
        folder = os.path.join(OUT, "camo", pattern)
        os.makedirs(folder, exist_ok=True)
        cloth = Cloth(pattern=camo_pattern(seed, tones))
        # The reserve has no squad colour yet; its band is the pattern's own darkest tone.
        accents = {"plain": Cloth(scale(tones[2], 0.8))}
        for name, dyed in cloths.items():
            if name != "camo":
                accents[name] = dyed
        for name, accent in accents.items():
            field_uniform(cloth, accent, False).save(os.path.join(folder, name + ".png"))
            field_uniform(cloth, accent, True).save(os.path.join(folder, "commander_" + name + ".png"))
            written += 2
    print("wrote", written, "uniforms to", OUT)


if __name__ == "__main__":
    main()
