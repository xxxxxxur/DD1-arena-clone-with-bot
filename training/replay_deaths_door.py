"""Find observed rescue decisions against ready finishers in tournament recordings."""
import json
import sys
from pathlib import Path


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def threats(enemy, victim, skills):
    for u in enemy:
        if not u["alive"] or u["is_corpse"] or u["stunned"] or u["acted_this_round"]:
            continue
        for skill in u["equipped_skills"]:
            s = skills[u["hero_id"], skill]
            bonus = s["deathblow_bonus"] + u["stats"].get("deathblow_chance", 0)
            if not (s["target_team"] == "enemy" and s["is_attack"] and s["dmg_mod"] > -1 and bonus >= 20):
                continue
            if u["rank"] not in s["launch_ranks"] or victim["rank"] not in s["target_ranks"]:
                continue
            if not s["is_aoe"] and victim["guarded_by_uid"] and not s["ignores_guard"]:
                continue
            if victim["stealth_rounds"] and not (s["is_aoe"] or s["ignores_stealth"] or s["de_stealth"]):
                continue
            if u["skill_cooldowns"].get(skill, 0) or (s["max_uses"] > 0 and u["skill_uses"].get(skill, 0) >= s["max_uses"]):
                continue
            yield f"{u['hero_id']}/{skill}(+{bonus:g})"


def review(path, skills):
    r = load(path)
    side = next((i for i in (0, 1) if "deepsets_neural" in r[f"bot{i}"]), None)
    if side is None:
        return
    own, enemy = ("my_units", "enemy_units") if side == 0 else ("enemy_units", "my_units")
    for before, after in zip(r["frames"], r["frames"][1:]):
        obs, act = before["obs0"], after["action"]
        if obs["active_team"] != side:
            continue
        for u in obs[own]:
            if not u["alive"] or u["is_corpse"] or not u["at_deaths_door"]:
                continue
            hits = list(threats(obs[enemy], u, skills))
            if not hits:
                continue
            following = next(v for v in after["obs0"][own] if v["uid"] == u["uid"])
            yield dict(replay=path.name, seed=r["seed"], step=after["step"], round=obs["round_num"],
                       unit=u["hero_id"], threats=hits, action=act["skill_id"] or act["kind"],
                       actor=act["actor_uid"], targets=act["target_uids"], hp_after=following["hp"],
                       guarded_after=following["guarded_by_uid"])


if __name__ == "__main__":
    skills = {(h["id"], s["id"]): s for h in load("java/resources/game_data.json")["heroes"] for s in h["skills"]}
    for directory in sys.argv[1:]:
        for path in sorted(Path(directory).glob("*.json")):
            for event in review(path, skills):
                print(json.dumps(event))

