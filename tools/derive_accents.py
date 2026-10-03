#!/usr/bin/env python3
"""Derive the light-mode AccentRoles for ui/theme/Theme.kt from the dark presets' hues.

Same method that produced the dark set: work in OKLCh, keep the hue, take ~90% of the
chroma the sRGB gamut allows at the chosen lightness (binary-searched, never clipped
silently). Light primaries are additionally forced to clear 4.5:1 against the light
surface because they double as text/icon tint; on* colors pick white or near-dark,
whichever passes WCAG AA. Prints ready-to-paste Kotlin Color(...) literals and asserts
every fg/bg pair, dark and light, so regenerating can never ship an unreadable accent.

Usage:  python tools/derive_accents.py
"""
import math

# ---------------------------------------------------------------- OKLCh <-> sRGB
#
# The forward conversions are the *computed* inverses of the backward matrices below,
# so a roundtrip (hex -> oklch -> hex) is exact and hues can never scramble.

M1 = (  # linear sRGB -> LMS cone responses (pre-cube-root)
    (0.4122214708, 0.5363325363, 0.0514459929),
    (0.2119034982, 0.6806995451, 0.1073969566),
    (0.0883024619, 0.2817188376, 0.6299787005),
)
M2 = (  # cube-rooted LMS -> OkLab L,a,b
    (0.2104542553, 0.7936177850, -0.0040720468),
    (1.9779984951, -2.4285922050, 0.4505937099),
    (0.0259040371, 0.7827717662, -0.8086757660),
)


def mat3vec(m, v):
    return tuple(m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] for i in range(3))


def inv3(m):
    a, b, c = m[0]
    d, e, f = m[1]
    g, h, i = m[2]
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return (
        ((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
        ((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
        ((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
    )


M1_INV = inv3(M1)
M2_INV = inv3(M2)


def oklch_to_linear_srgb(L, C, h):
    lab = M2_INV[0][0] * L + M2_INV[0][1] * C * math.cos(h) + M2_INV[0][2] * C * math.sin(h), \
          M2_INV[1][0] * L + M2_INV[1][1] * C * math.cos(h) + M2_INV[1][2] * C * math.sin(h), \
          M2_INV[2][0] * L + M2_INV[2][1] * C * math.cos(h) + M2_INV[2][2] * C * math.sin(h)
    lms = [x ** 3 for x in lab]
    return mat3vec(M1_INV, lms)


def linear_srgb_to_oklch(r, g, b):
    l, m, s = mat3vec(M1, (r, g, b))
    l, m, s = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    L = mat3vec(M2, (l, m, s))
    return L[0], math.hypot(L[1], L[2]), math.atan2(L[2], L[1])


def gamma(c):
    c = max(0.0012100, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def ungamm(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def in_gamut(L, C, h):
    r, g, b = oklch_to_linear_srgb(L, C, h)
    return all(-0.0005 <= v <= 1.0005 for v in (r, g, b))


def max_chroma(L, h):
    lo, hi = 0.0, 0.5
    while hi - lo > 1e-4:
        mid = (lo + hi) / 2
        if in_gamut(L, mid, h):
            lo = mid
        else:
            hi = mid
    return lo


def hex_of(L, C, h):
    r, g, b = oklch_to_linear_srgb(L, C, h)
    vals = [round(gamma(v) * 255) for v in (r, g, b)]
    return "#FF%02X%02X%02X" % tuple(vals)


def lum(hexcolor):
    ch = [ungamm(int(hexcolor[i:i + 2], 16) / 255) for i in (3, 5, 7)]
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]


def contrast(fg, bg):
    f, g = lum(fg), lum(bg)
    hi, lo = max(f, g), min(f, g)
    return (hi + 0.05) / (lo + 0.05)


def hue_of(hexcolor):
    ch = [ungamm(int(hexcolor[i:i + 2], 16) / 255) for i in (3, 5, 7)]
    return linear_srgb_to_oklch(*ch)[2]


def parse(s):
    s = s.lstrip("#")
    if len(s) == 8:  # already carries an FF alpha pair
        s = s[2:]
    return "#FF" + s

# ---------------------------------------------------------------- presets (dark, from Theme.kt)

DARK = [
    ("default", "FFBB9FF8", "FFC1ACE0", "FFEF9BB5", "FF7322CC", "FFE8E6EF"),
    ("teal",    "FF39C7D1", "FF7DC6CC", "FF9EB7EE", "FF1A6A70", "FFD6EDEF"),
    ("amber",   "FFD9A932", "FFCFB475", "FFD2A1EE", "FF745916", "FFEFE7D7"),
    ("magenta", "FFF884C2", "FFE2A2BB", "FFDDB055", "FFA01C6E", "FFEFE5E9"),
    ("blue",    "FF8BB1F7", "FF9DBADF", "FFC6A7EE", "FF114ED2", "FFE4E8EF"),
    ("green",   "FF5ECF33", "FF94C976", "FF5ECAD3", "FF2F6F17", "FFDAEFD4"),
    ("coral",   "FFF89082", "FFE1A79D", "FFEF9ABA", "FFAB1E19", "FFEFE5E4"),
    ("violet",  "FFB1A3F8", "FFBAAFE0", "FFEF95D2", "FF6624D9", "FFE7E7EF"),
]

SURFACE_LIGHT = "#FFFFFBFE"      # the light scheme surface the primary must clear on
ON_LIGHT = "#FFF8F5FA"           # near-white on* candidate
ON_DARK_TEXT = "#FF1D1B20"       # near-black on* fallback


def pick_with_on(start_l, hue, chroma_frac):
    """Descend L from start_l until near-white or near-black text clears 4.6:1 on the role."""
    L = start_l
    hx = ""
    while L > 0.34:
        c = chroma_frac * max_chroma(L, hue)
        hx = hex_of(L, c, hue)
        if contrast(hx, ON_LIGHT) >= 4.6:
            return hx, ON_LIGHT
        if contrast(hx, ON_DARK_TEXT) >= 4.6:
            return hx, ON_DARK_TEXT
        L -= 0.01
    return hx, (ON_LIGHT if contrast(hx, ON_LIGHT) >= contrast(hx, ON_DARK_TEXT) else ON_DARK_TEXT)


def light_for(name, p_hex, s_hex, t_hex):
    hp, hs, ht = hue_of(p_hex), hue_of(s_hex), hue_of(t_hex)
    # primary: darkest-needed L (from 0.52 down) that clears 4.6:1 BOTH as text on the
    # light surface and under near-white button text — the two roles primary plays.
    L = 0.52
    while L > 0.30:
        c = 0.9 * max_chroma(L, hp)
        hx = hex_of(L, c, hp)
        if contrast(hx, SURFACE_LIGHT) >= 4.6 and contrast(hx, ON_LIGHT) >= 4.6:
            primary = hx
            break
        L -= 0.01
    else:
        primary = hx
    on_primary = ON_LIGHT if contrast(primary, ON_LIGHT) >= 4.5 else ON_DARK_TEXT
    container = hex_of(0.90, min(0.10, max_chroma(0.90, hp)), hp)
    on_container = hex_of(0.30, min(0.08, max_chroma(0.30, hp)), hp)
    secondary, on_secondary = pick_with_on(0.55, hs, 0.5)
    tertiary, on_tertiary = pick_with_on(0.52, ht, 0.9)
    return name, primary, on_primary, container, on_container, secondary, on_secondary, tertiary, on_tertiary


def check(label, fg, bg):
    r = contrast(fg, bg)
    assert r >= 4.5, f"{label} contrast {r:.2f} < 4.5"
    return r


print("== light presets (paste into Theme.kt) ==")
lines = []
for name, p, s, t, _pc, _oc in DARK:
    out = light_for(name, parse(p), parse(s), parse(t))
    _, primary, onp, cont, oncont, sec, onsec, ter, ont = out
    check(f"{name}.light primary/on", primary, onp)
    check(f"{name}.light container/on", cont, oncont)
    check(f"{name}.light secondary/on", sec, onsec)
    check(f"{name}.light tertiary/on", ter, ont)
    check(f"{name}.light primary-on-surface", primary, SURFACE_LIGHT)
    lines.append(
        f"{name}: primary {primary} onPrimary {onp} container {cont} onContainer {oncont} "
        f"secondary {sec} onSecondary {onsec} tertiary {ter} onTertiary {ont}"
    )
    print(lines[-1])

print()
print("== kotlin ==")
for name, p, s, t, _pc, _oc in DARK:
    _, primary, onp, cont, oncont, sec, onsec, ter, ont = light_for(name, parse(p), parse(s), parse(t))
    print(f"// {name}")
    print(f"AccentRoles(primary = Color({primary.replace('#', '0x')}), onPrimary = Color({onp.replace('#', '0x')}),")
    print(f"    primaryContainer = Color({cont.replace('#', '0x')}), onPrimaryContainer = Color({oncont.replace('#', '0x')}),")
    print(f"    secondary = Color({sec.replace('#', '0x')}), onSecondary = Color({onsec.replace('#', '0x')}),")
    print(f"    tertiary = Color({ter.replace('#', '0x')}), onTertiary = Color({ont.replace('#', '0x')})),")
print("ALL DERIVED LIGHT PAIRS PASS 4.5:1")
