#!/usr/bin/env python3
"""
Generates the player animations in common/src/main/resources/assets/crossblades/player_animations/.

The files use the Blockbench/GeckoLib (Bedrock) format that Player Animation Library reads, so you
can also open and tweak them in Blockbench. Editing the poses here and re-running this script is
usually easier, though.

Rotations are in degrees and map straight onto Minecraft's model parts:
  right_arm x: negative raises the arm forward/up (-90 = pointing forward, -180 = straight up)
  right_arm y: turns the arm around its own length; with the arm forward, negative swings it to the left
  right_arm z: positive swings the right arm out to the right side
  body y:      turns the whole player; positive turns the right shoulder back (winding up)
  right_leg x: positive swings the leg back
Positions are in pixels; right_arm z +3 pulls the arm 3 pixels back.

Lengths are the default timings from the config. The mod speeds the animations up or down to match
the server's actual wind-up and recovery times, so they stay in sync if you change the config.
"""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "common", "src", "main", "resources",
                   "assets", "crossblades", "player_animations")

REST = [0, 0, 0]

# Right-arm poses (see the notes above). Chosen so the arm and blade point where you'd expect.
OVERHEAD_CHAMBER = [-195, 0, 10]   # hand high above the head, blade pointing back
OVERHEAD_TENSE = [-205, 0, 10]
OVERHEAD_RELEASE = [-120, 0, 5]    # coming over the top
OVERHEAD_END = [-40, 0, 0]         # hand low in front

RIGHT_CHAMBER = [-20, 90, 90]      # arm out to the right, blade up
RIGHT_TENSE = [-15, 95, 95]
RIGHT_RELEASE = [-70, 45, 45]      # sweeping in from the right
RIGHT_END = [-90, -45, 0]          # finished across to the left

LEFT_CHAMBER = [-150, -10, 50]     # hand up by the left shoulder, blade back
LEFT_TENSE = [-155, -10, 55]
LEFT_RELEASE = [-110, 20, 25]      # sweeping in from the left
LEFT_END = [-90, 45, 0]            # finished across to the right

POKE_CHAMBER = [20, 0, 0]          # arm drawn back low, blade level and pointing forward
POKE_TENSE = [25, 0, 0]
POKE_RELEASE = [-5, 0, 0]
POKE_END = [-25, 0, 0]             # arm driven forward, blade still pointing forward

GUARD_UP = [-90, 55, 90]           # blade held flat above the head
GUARD_LEFT = [-70, -35, 0]         # blade upright, off to the left
GUARD_RIGHT = [-70, 35, 0]         # blade upright, off to the right

STAGGER = [35, 0, 35]              # arm knocked back and out


def frames(pairs):
    return {f"{t:.2f}": {"vector": list(v)} for t, v in pairs}


def anim(name, length, loop, bones):
    data = {"animation_length": length, "loop": loop, "bones": {}}
    for bone, channels in bones.items():
        data["bones"][bone] = {channel: frames(keys) for channel, keys in channels.items()}
    return name, data


def write(name, data):
    doc = {"format_version": "1.8.0", "animations": {name: data}}
    with open(os.path.join(OUT, f"{name}.json"), "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


def windup(name, length, chamber, tense, release, body=None, arm_pos=None, leg=None):
    """Raise into the chamber pose early (the tell), hold it, and start the swing at the very end."""
    t_chamber = round(length * 0.5, 2)
    t_tense = round(length * 0.8, 2)
    bones = {"right_arm": {"rotation": [(0, REST), (t_chamber, chamber), (t_tense, tense), (length, release)]}}
    if arm_pos:
        bones["right_arm"]["position"] = [(0, [0, 0, 0]), (t_chamber, arm_pos[0]), (t_tense, arm_pos[1]), (length, arm_pos[2])]
    if body:
        bones["body"] = {"rotation": [(0, [0, 0, 0]), (t_chamber, [0, body[0], 0]), (t_tense, [0, body[1], 0]), (length, [0, body[2], 0])]}
    if leg:
        bones["right_leg"] = {"rotation": [(0, [0, 0, 0]), (t_chamber, [leg[0], 0, 0]), (t_tense, [leg[1], 0, 0]), (length, [leg[2], 0, 0])]}
    return anim(name, length, "hold_on_last_frame", bones)


def strike(name, length, release, end, body=None, arm_pos=None, leg=None):
    """Follow through fast, hold briefly, then return to rest by the end of recovery."""
    t_hit = round(min(0.08, length * 0.25), 2)
    t_hold = round(length * 0.5, 2)
    bones = {"right_arm": {"rotation": [(0, release), (t_hit, end), (t_hold, end), (length, REST)]}}
    if arm_pos:
        bones["right_arm"]["position"] = [(0, arm_pos[0]), (t_hit, arm_pos[1]), (t_hold, arm_pos[1]), (length, [0, 0, 0])]
    if body:
        bones["body"] = {"rotation": [(0, [0, body[0], 0]), (t_hit, [0, body[1], 0]), (t_hold, [0, body[1], 0]), (length, [0, 0, 0])]}
    if leg:
        bones["right_leg"] = {"rotation": [(0, [leg[0], 0, 0]), (t_hit, [leg[1], 0, 0]), (t_hold, [leg[1], 0, 0]), (length, [0, 0, 0])]}
    return anim(name, length, False, bones)


def guard(name, pose):
    return anim(name, 0.1, "hold_on_last_frame", {"right_arm": {"rotation": [(0, REST), (0.1, pose)]}})


ANIMATIONS = [
    windup("windup_overhead", 0.6, OVERHEAD_CHAMBER, OVERHEAD_TENSE, OVERHEAD_RELEASE),
    strike("strike_overhead", 0.4, OVERHEAD_RELEASE, OVERHEAD_END),
    windup("windup_right", 0.45, RIGHT_CHAMBER, RIGHT_TENSE, RIGHT_RELEASE, body=(20, 25, 8)),
    strike("strike_right", 0.35, RIGHT_RELEASE, RIGHT_END, body=(8, -15)),
    windup("windup_left", 0.45, LEFT_CHAMBER, LEFT_TENSE, LEFT_RELEASE, body=(-20, -25, -8)),
    strike("strike_left", 0.35, LEFT_RELEASE, LEFT_END, body=(-8, 15)),
    windup("windup_poke", 0.35, POKE_CHAMBER, POKE_TENSE, POKE_RELEASE, body=(15, 18, 5),
           arm_pos=([0, 0, 4], [0, 0, 4.5], [0, 0, 0]), leg=(20, 25, 5)),
    strike("strike_poke", 0.3, POKE_RELEASE, POKE_END, body=(5, -5),
           arm_pos=([0, 0, 0], [0, 0, -5]), leg=(5, 0)),
    guard("guard_up", GUARD_UP),
    guard("guard_left", GUARD_LEFT),
    guard("guard_right", GUARD_RIGHT),
    anim("stagger", 0.6, False, {"right_arm": {"rotation": [(0, OVERHEAD_END), (0.08, STAGGER), (0.35, [30, 0, 30]), (0.6, REST)]}}),
    # An empty animation: fading into it blends back to the normal vanilla pose.
    ("rest", {"animation_length": 0.1, "loop": False, "bones": {}}),
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, data in ANIMATIONS:
        write(name, data)
    print(f"Wrote {len(ANIMATIONS)} animations to {os.path.normpath(OUT)}")
