package com.circus.engine;

import com.circus.util.Json;

import java.util.*;

/**
 * Core data models for The Butcher's Circus Java engine.
 * Exact 1:1 structural and JSON-wire parity with engine/models.py.
 */
public final class Models {
    private Models() {}

    // Dot kind constants for fast switching without string compare
    public static final int DOT_BLEED = 0;
    public static final int DOT_BLIGHT = 1;
    public static final int DOT_HORROR = 2;
    public static final int DOT_REGEN = 3;

    public static int parseDotKind(String kind) {
        return switch (kind) {
            case "bleed" -> DOT_BLEED;
            case "blight" -> DOT_BLIGHT;
            case "horror" -> DOT_HORROR;
            case "regen" -> DOT_REGEN;
            default -> throw new IllegalArgumentException("Unknown dot kind: " + kind);
        };
    }

    public static String dotKindName(int kind) {
        return switch (kind) {
            case DOT_BLEED -> "bleed";
            case DOT_BLIGHT -> "blight";
            case DOT_HORROR -> "horror";
            case DOT_REGEN -> "regen";
            default -> "bleed";
        };
    }

    public static final class BuffDef {
        public final String stat;
        public final int statId;
        public final int condBit;
        public final double value;
        public final int duration; // -1 = permanent
        public final String source;
        public final boolean isDebuff;

        public BuffDef(String stat, double value, int duration, String source, boolean isDebuff) {
            this.stat = stat == null ? "" : stat;
            this.statId = StatKey.parseStatId(this.stat);
            this.condBit = StatKey.parseCondBit(this.stat);
            this.value = value;
            this.duration = duration;
            this.source = source == null ? "" : source;
            this.isDebuff = isDebuff;
        }
    }

    public static final class ActiveBuff {
        public final String stat;
        public final int statId;
        public final int condBit;
        public final double value;
        public int roundsLeft; // -1 = battle-long
        public final String source;
        public final boolean isDebuff;
        public boolean freshThisTurn;

        public ActiveBuff(String stat, double value, int roundsLeft, String source, boolean isDebuff) {
            this.stat = stat;
            this.statId = StatKey.parseStatId(stat);
            this.condBit = StatKey.parseCondBit(stat);
            this.value = value;
            this.roundsLeft = roundsLeft;
            this.source = source;
            this.isDebuff = isDebuff;
            this.freshThisTurn = false;
        }

        public ActiveBuff(int statId, int condBit, String stat, double value, int roundsLeft, String source, boolean isDebuff) {
            this(statId, condBit, stat, value, roundsLeft, source, isDebuff, false);
        }

        public ActiveBuff(int statId, int condBit, String stat, double value, int roundsLeft, String source, boolean isDebuff, boolean freshThisTurn) {
            this.stat = stat;
            this.statId = statId;
            this.condBit = condBit;
            this.value = value;
            this.roundsLeft = roundsLeft;
            this.source = source;
            this.isDebuff = isDebuff;
            this.freshThisTurn = freshThisTurn;
        }

        public static ActiveBuff fromDef(BuffDef bd) {
            return new ActiveBuff(bd.statId, bd.condBit, bd.stat, bd.value, bd.duration, bd.source, bd.isDebuff, false);
        }

        public static ActiveBuff fromDefFresh(BuffDef bd, boolean fresh) {
            return new ActiveBuff(bd.statId, bd.condBit, bd.stat, bd.value, bd.duration, bd.source, bd.isDebuff, fresh);
        }

        public static ActiveBuff permanentFromDef(BuffDef bd) {
            return new ActiveBuff(bd.statId, bd.condBit, bd.stat, bd.value, -1, bd.source, bd.isDebuff, false);
        }

        public ActiveBuff copy() {
            return new ActiveBuff(statId, condBit, stat, value, roundsLeft, source, isDebuff, freshThisTurn);
        }
    }

    public static final class ActiveDot {
        public final int kind; // DOT_BLEED..DOT_REGEN
        public final int amount;
        public int roundsLeft;
        public final String source;

        public ActiveDot(int kind, int amount, int roundsLeft, String source) {
            this.kind = kind;
            this.amount = amount;
            this.roundsLeft = roundsLeft;
            this.source = source == null ? "" : source;
        }

        public ActiveDot(String kindStr, int amount, int roundsLeft, String source) {
            this(parseDotKind(kindStr), amount, roundsLeft, source);
        }

        public String kindName() {
            return dotKindName(kind);
        }

        public ActiveDot copy() {
            return new ActiveDot(kind, amount, roundsLeft, source);
        }
    }

    public static final class DotSpec {
        public final int chance;
        public final int amount;
        public final int rounds;

        public DotSpec(int chance, int amount, int rounds) {
            this.chance = chance;
            this.amount = amount;
            this.rounds = rounds;
        }
    }

    public static final class TargetDebuff {
        public final int chance;
        public final BuffDef buff;

        public TargetDebuff(int chance, BuffDef buff) {
            this.chance = chance;
            this.buff = buff;
        }
    }

    public static final class SkillDef {
        public final String id;
        public final String nameEn;
        public final String nameZh;
        public final String icon;
        public final String anim;
        public final String fx;
        public final String targfx;
        public final String targetAnchor;
        public final String sfxHint;

        public final int[] launchRanks;
        public final int launchRankMask; // bit i set if rank i is allowed (1..4)
        public final int[] targetRanks;
        public final int targetRankMask;
        public final String targetTeam; // "enemy", "ally", "self", "ally_other"
        public final boolean isAoe;
        public final boolean isAttack;
        public final boolean isRanged;

        public final int acc;
        public final double dmgMod;
        public final double critMod;
        public final int stressDmg;
        public final int stressHeal;
        public final int stressHealChance;
        public final double healPct;
        public final double selfHealPct;
        public final boolean selfDaze;
        public final int selfMarkRounds;
        public final boolean transferDots;
        public final boolean curesSelfDots;
        public final int selfBleedChance;
        public final boolean randomTarget;
        public final boolean partyCleanse;
        public final int healMin;
        public final int healMax;
        public final int selfHealMin;
        public final int selfHealMax;
        public final int selfStress;
        public final int[] selfBleed; // [amount, rounds] or null

        public final double deathblowBonus;
        public final boolean armorPiercing;
        public final boolean ignoresStealth;
        public final boolean deStealth;
        public final boolean ignoresGuard;
        public final boolean breaksGuard;
        public final boolean clearsCorpses;
        public final boolean noCorpseOnKill;
        public final boolean clearsMark;
        public final boolean curesBleed;
        public final boolean curesBlight;
        public final boolean curesHorror;
        public final boolean clearsStun;
        public final boolean clearsDebuffs;
        public final boolean clearsSelfDebuffs;
        public final int targetStealthRounds;

        public final double bonusDmgVsMarked;
        public final double bonusDmgVsBleed;
        public final double bonusDmgVsBlight;
        public final double bonusDmgVsStunned;
        public final double bonusDmgVsGuarded;
        public final double bonusCritVsMarked;
        public final double bonusCritVsBleed;
        public final double bonusCritVsBlight;

        public final DotSpec bleed;
        public final DotSpec blight;
        public final DotSpec horror;
        public final int[] regen; // [amount, rounds] or null
        public final int stunChance;
        public final int dazeChance;
        public final int markRounds;
        public final int stealthRounds;
        public final int guardRounds;
        public final int guardMeRounds;
        public final int riposteRounds;
        public final int aegisBlocks;

        public final int selfMove;
        public final int push;
        public final int pull;
        public final int moveChance;
        public final boolean shuffleTarget;

        public final BuffDef[] targetBuffs;
        public final TargetDebuff[] targetDebuffs;
        public final BuffDef[] selfBuffs;
        public final BuffDef[] partyBuffs;
        public final int partyStressHeal;

        public final int maxUses;
        public final int cooldown;
        public final double requiresHpBelowPct;
        public final String abomForm; // "any", "human", "beast"
        public final boolean isTransform;
        public final boolean isFinale;
        public final double finaleBuff;
        public final String descriptionEn;
        public final String descriptionZh;

        public SkillDef(
                String id, String nameEn, String nameZh, String icon, String anim,
                String fx, String targfx, String targetAnchor, String sfxHint,
                int[] launchRanks, int[] targetRanks, String targetTeam,
                boolean isAoe, boolean isAttack, boolean isRanged,
                int acc, double dmgMod, double critMod, int stressDmg, int stressHeal, int stressHealChance,
                double healPct, double selfHealPct, boolean selfDaze, int selfMarkRounds,
                boolean transferDots, boolean curesSelfDots, int selfBleedChance, boolean randomTarget, boolean partyCleanse,
                int healMin, int healMax, int selfHealMin, int selfHealMax, int selfStress, int[] selfBleed,
                double deathblowBonus, boolean armorPiercing, boolean ignoresStealth, boolean deStealth,
                boolean ignoresGuard, boolean breaksGuard, boolean clearsCorpses, boolean noCorpseOnKill,
                boolean clearsMark, boolean curesBleed, boolean curesBlight, boolean curesHorror,
                boolean clearsStun, boolean clearsDebuffs, boolean clearsSelfDebuffs, int targetStealthRounds,
                double bonusDmgVsMarked, double bonusDmgVsBleed, double bonusDmgVsBlight,
                double bonusDmgVsStunned, double bonusDmgVsGuarded,
                double bonusCritVsMarked, double bonusCritVsBleed, double bonusCritVsBlight,
                DotSpec bleed, DotSpec blight, DotSpec horror, int[] regen,
                int stunChance, int dazeChance, int markRounds, int stealthRounds,
                int guardRounds, int guardMeRounds, int riposteRounds, int aegisBlocks,
                int selfMove, int push, int pull, int moveChance, boolean shuffleTarget,
                BuffDef[] targetBuffs, TargetDebuff[] targetDebuffs, BuffDef[] selfBuffs, BuffDef[] partyBuffs,
                int partyStressHeal, int maxUses, int cooldown, double requiresHpBelowPct,
                String abomForm, boolean isTransform, boolean isFinale, double finaleBuff,
                String descriptionEn, String descriptionZh
        ) {
            this.id = id;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.icon = icon;
            this.anim = anim;
            this.fx = fx;
            this.targfx = targfx;
            this.targetAnchor = targetAnchor;
            this.sfxHint = sfxHint;
            this.launchRanks = launchRanks;
            int lm = 0;
            for (int r : launchRanks) lm |= (1 << r);
            this.launchRankMask = lm;
            this.targetRanks = targetRanks;
            int tm = 0;
            for (int r : targetRanks) tm |= (1 << r);
            this.targetRankMask = tm;
            this.targetTeam = targetTeam;
            this.isAoe = isAoe;
            this.isAttack = isAttack;
            this.isRanged = isRanged;
            this.acc = acc;
            this.dmgMod = dmgMod;
            this.critMod = critMod;
            this.stressDmg = stressDmg;
            this.stressHeal = stressHeal;
            this.stressHealChance = stressHealChance;
            this.healPct = healPct;
            this.selfHealPct = selfHealPct;
            this.selfDaze = selfDaze;
            this.selfMarkRounds = selfMarkRounds;
            this.transferDots = transferDots;
            this.curesSelfDots = curesSelfDots;
            this.selfBleedChance = selfBleedChance;
            this.randomTarget = randomTarget;
            this.partyCleanse = partyCleanse;
            this.healMin = healMin;
            this.healMax = healMax;
            this.selfHealMin = selfHealMin;
            this.selfHealMax = selfHealMax;
            this.selfStress = selfStress;
            this.selfBleed = selfBleed;
            this.deathblowBonus = deathblowBonus;
            this.armorPiercing = armorPiercing;
            this.ignoresStealth = ignoresStealth;
            this.deStealth = deStealth;
            this.ignoresGuard = ignoresGuard;
            this.breaksGuard = breaksGuard;
            this.clearsCorpses = clearsCorpses;
            this.noCorpseOnKill = noCorpseOnKill;
            this.clearsMark = clearsMark;
            this.curesBleed = curesBleed;
            this.curesBlight = curesBlight;
            this.curesHorror = curesHorror;
            this.clearsStun = clearsStun;
            this.clearsDebuffs = clearsDebuffs;
            this.clearsSelfDebuffs = clearsSelfDebuffs;
            this.targetStealthRounds = targetStealthRounds;
            this.bonusDmgVsMarked = bonusDmgVsMarked;
            this.bonusDmgVsBleed = bonusDmgVsBleed;
            this.bonusDmgVsBlight = bonusDmgVsBlight;
            this.bonusDmgVsStunned = bonusDmgVsStunned;
            this.bonusDmgVsGuarded = bonusDmgVsGuarded;
            this.bonusCritVsMarked = bonusCritVsMarked;
            this.bonusCritVsBleed = bonusCritVsBleed;
            this.bonusCritVsBlight = bonusCritVsBlight;
            this.bleed = bleed;
            this.blight = blight;
            this.horror = horror;
            this.regen = regen;
            this.stunChance = stunChance;
            this.dazeChance = dazeChance;
            this.markRounds = markRounds;
            this.stealthRounds = stealthRounds;
            this.guardRounds = guardRounds;
            this.guardMeRounds = guardMeRounds;
            this.riposteRounds = riposteRounds;
            this.aegisBlocks = aegisBlocks;
            this.selfMove = selfMove;
            this.push = push;
            this.pull = pull;
            this.moveChance = moveChance;
            this.shuffleTarget = shuffleTarget;
            this.targetBuffs = targetBuffs;
            this.targetDebuffs = targetDebuffs;
            this.selfBuffs = selfBuffs;
            this.partyBuffs = partyBuffs;
            this.partyStressHeal = partyStressHeal;
            this.maxUses = maxUses;
            this.cooldown = cooldown;
            this.requiresHpBelowPct = requiresHpBelowPct;
            this.abomForm = abomForm;
            this.isTransform = isTransform;
            this.isFinale = isFinale;
            this.finaleBuff = finaleBuff;
            this.descriptionEn = descriptionEn;
            this.descriptionZh = descriptionZh;
        }

        public boolean canLaunchFrom(int rank) {
            return rank >= 1 && rank <= 4 && (launchRankMask & (1 << rank)) != 0;
        }

        public boolean canTargetRank(int rank) {
            return rank >= 1 && rank <= 4 && (targetRankMask & (1 << rank)) != 0;
        }

        /**
         * Official Darkest Dungeon & Butcher's Circus Riposte rule:
         * Only direct-damage enemy attacks (dmg_mod > -1.0) can trigger Riposte.
         * Zero-damage utility/mark/debuff/stress skills (dmg_mod == -1.0) NEVER trigger Riposte.
         */
        public boolean triggersRiposte() {
            return isAttack && "enemy".equals(targetTeam) && dmgMod > -1.0;
        }
    }

    public static final class HeroDef {
        public final String id;
        public final String nameEn;
        public final String nameZh;
        public final int maxHp;
        public final int dodge;
        public final double prot;
        public final int dmgMin;
        public final int dmgMax;
        public final double crit;
        public final int moveForward;
        public final int moveBackward;
        public final int deathblowRes;
        public final int stunRes;
        public final int blightRes;
        public final int bleedRes;
        public final int debuffRes;
        public final int moveRes;
        public final int stressRes;
        public final double riposteDmgMod;
        public final int riposteAcc;
        public final double riposteCrit;
        public final int beastDmgMin;
        public final int beastDmgMax;
        public final double beastCrit;
        public final SkillDef[] skills;
        public final String[] defaultSkills;
        private final Map<String, SkillDef> skillMap;

        public HeroDef(
                String id, String nameEn, String nameZh, int maxHp, int dodge, double prot,
                int dmgMin, int dmgMax, double crit, int moveForward, int moveBackward,
                int deathblowRes, int stunRes, int blightRes, int bleedRes, int debuffRes,
                int moveRes, int stressRes, double riposteDmgMod, int riposteAcc, double riposteCrit,
                int beastDmgMin, int beastDmgMax, double beastCrit,
                SkillDef[] skills, String[] defaultSkills
        ) {
            this.id = id;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.maxHp = maxHp;
            this.dodge = dodge;
            this.prot = prot;
            this.dmgMin = dmgMin;
            this.dmgMax = dmgMax;
            this.crit = crit;
            this.moveForward = moveForward;
            this.moveBackward = moveBackward;
            this.deathblowRes = deathblowRes;
            this.stunRes = stunRes;
            this.blightRes = blightRes;
            this.bleedRes = bleedRes;
            this.debuffRes = debuffRes;
            this.moveRes = moveRes;
            this.stressRes = stressRes;
            this.riposteDmgMod = riposteDmgMod;
            this.riposteAcc = riposteAcc;
            this.riposteCrit = riposteCrit;
            this.beastDmgMin = beastDmgMin;
            this.beastDmgMax = beastDmgMax;
            this.beastCrit = beastCrit;
            this.skills = skills;
            this.defaultSkills = defaultSkills;
            Map<String, SkillDef> m = new HashMap<>(skills.length * 2);
            for (SkillDef s : skills) m.put(s.id, s);
            this.skillMap = m;
        }

        public SkillDef getSkill(String skillId) {
            if (skillId == null) return null;
            if ("occultist".equals(id) && "hands_from_abyss".equals(skillId)) {
                skillId = "stygian_embrace";
            }
            return skillMap.get(skillId);
        }
    }

    public static final class TrinketDef {
        public final String id;
        public final String nameEn;
        public final String nameZh;
        public final String heroClass;
        public final String tier;
        public final BuffDef[] buffs;
        public final int onHitStress;
        public final DotSpec onHitBleed;
        public final DotSpec onHitBlight;
        public final int startStealth;
        public final int startAegis;
        public final String descriptionEn;
        public final String descriptionZh;

        public TrinketDef(
                String id, String nameEn, String nameZh, String heroClass, String tier,
                BuffDef[] buffs, int onHitStress, DotSpec onHitBleed, DotSpec onHitBlight,
                int startStealth, int startAegis, String descriptionEn, String descriptionZh
        ) {
            this.id = id;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.heroClass = heroClass == null ? "" : heroClass;
            this.tier = tier == null ? "arena" : tier;
            this.buffs = buffs;
            this.onHitStress = onHitStress;
            this.onHitBleed = onHitBleed;
            this.onHitBlight = onHitBlight;
            this.startStealth = startStealth;
            this.startAegis = startAegis;
            this.descriptionEn = descriptionEn;
            this.descriptionZh = descriptionZh;
        }
    }

    public static final class TraitDef {
        public final String id;
        public final String nameEn;
        public final String nameZh;
        public final boolean isVirtue;
        public final BuffDef[] buffs;
        public final int turnStressSelf;
        public final int turnStressAllies;
        public final int turnHealSelf;
        public final String[] barksEn;
        public final String[] barksZh;

        public TraitDef(
                String id, String nameEn, String nameZh, boolean isVirtue, BuffDef[] buffs,
                int turnStressSelf, int turnStressAllies, int turnHealSelf,
                String[] barksEn, String[] barksZh
        ) {
            this.id = id;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.isVirtue = isVirtue;
            this.buffs = buffs;
            this.turnStressSelf = turnStressSelf;
            this.turnStressAllies = turnStressAllies;
            this.turnHealSelf = turnHealSelf;
            this.barksEn = barksEn;
            this.barksZh = barksZh;
        }
    }

    public static final class HeroLoadout {
        public final String heroId;
        public final List<String> skills;
        public final List<String> trinkets;
        public final String variant;
        public final String name;

        public HeroLoadout(String heroId, List<String> skills, List<String> trinkets, String variant, String name) {
            this.heroId = heroId;
            this.skills = new ArrayList<>(skills);
            this.trinkets = new ArrayList<>(trinkets);
            this.variant = (variant == null || variant.isEmpty()) ? "A" : variant;
            this.name = name == null ? "" : name;
        }

        public HeroLoadout(String heroId, List<String> skills, List<String> trinkets, String variant) {
            this(heroId, skills, trinkets, variant, "");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hero_id", heroId);
            m.put("skills", new ArrayList<>(skills));
            m.put("trinkets", new ArrayList<>(trinkets));
            m.put("variant", variant);
            m.put("name", name);
            return m;
        }

        public static HeroLoadout fromMap(Map<String, Object> d) {
            List<String> sk = new ArrayList<>();
            for (Object o : Json.listVal(d, "skills")) sk.add(String.valueOf(o));
            List<String> tr = new ArrayList<>();
            for (Object o : Json.listVal(d, "trinkets")) tr.add(String.valueOf(o));
            return new HeroLoadout(
                    Json.str(d, "hero_id", ""),
                    sk,
                    tr,
                    Json.str(d, "variant", "A"),
                    Json.str(d, "name", "")
            );
        }
    }

    public static final class TeamConfig {
        public final String name;
        public final List<HeroLoadout> heroes;

        public TeamConfig(String name, List<HeroLoadout> heroes) {
            this.name = name == null ? "Circus Party" : name;
            this.heroes = new ArrayList<>(heroes);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            List<Map<String, Object>> hl = new ArrayList<>(heroes.size());
            for (HeroLoadout h : heroes) hl.add(h.toMap());
            m.put("heroes", hl);
            return m;
        }

        public TeamConfig copy() {
            List<HeroLoadout> hl = new ArrayList<>(heroes.size());
            for (HeroLoadout h : heroes) {
                hl.add(new HeroLoadout(h.heroId, h.skills, h.trinkets, h.variant, h.name));
            }
            return new TeamConfig(name, hl);
        }

        @SuppressWarnings("unchecked")
        public static TeamConfig fromMap(Map<String, Object> d) {
            List<HeroLoadout> hl = new ArrayList<>();
            for (Object o : Json.listVal(d, "heroes")) {
                hl.add(HeroLoadout.fromMap((Map<String, Object>) o));
            }
            return new TeamConfig(Json.str(d, "name", "Circus Party"), hl);
        }
    }

    public static final class PresetDef {
        public final String id;
        public final String nameEn;
        public final String nameZh;
        public final TeamConfig team;

        public PresetDef(String id, String nameEn, String nameZh, TeamConfig team) {
            this.id = id;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.team = team;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name_en", nameEn);
            m.put("name_zh", nameZh);
            m.put("team", team.toMap());
            return m;
        }
    }

    /**
     * Runtime mutable state of a hero or corpse in combat.
     * Optimized for ultra-fast deep cloning during search.
     */
    public static final class UnitState {
        public final String uid;
        public final int team;
        public final String heroId;
        public final HeroDef heroDef;
        public final String name;
        public final String variant;
        public int rank;
        public int hp;
        public int maxHp;
        public int stress;
        public boolean alive = true;
        public boolean isCorpse = false;
        public int corpseRounds = 0;

        // Immutable per-match lists (shared safely across clones!)
        public final List<String> equippedSkills;
        public final SkillDef[] equippedSkillDefs;
        public final List<String> trinkets;
        public final TrinketDef[] trinketDefs;
        // Pre-computed special trinket flags (eliminates repeated String.contains checks!)
        public final int trinketFlags;

        public static final int TF_CROWN_OF_THORNS = 1 << 0;
        public static final int TF_TASTE_OF_GRANDEUR = 1 << 1;
        public static final int TF_INSIGNIA_OF_RANK = 1 << 2;
        public static final int TF_GAUNTLET_OF_ABSOLUTION = 1 << 3;
        public static final int TF_WELL_BALANCED_STILETTO = 1 << 4;
        public static final int TF_HARLEQUIN_MASQUE = 1 << 5;
        public static final int TF_SACRIFICIAL_KRIS = 1 << 6;
        public static final int TF_GHOUL_CLAW = 1 << 7;
        public static final int TF_VETERAN_GAUNTLET = 1 << 8;
        public static final int TF_SHIELD_SPIKE = 1 << 9;
        public static final int TF_BLEEDING_SKULL = 1 << 10;
        public static final int TF_TINCTURE_OF_IODINE = 1 << 11;

        public final Map<String, Integer> skillUses;
        public final Map<String, Integer> skillCooldowns;

        public boolean actedThisRound = false;
        public boolean stunned = false;
        public boolean dazed = false;
        public boolean atDeathsDoor = false;

        public int markedRounds = 0;
        public int stealthRounds = 0;
        public int riposteRounds = 0;
        public String guardingUid = null;
        public String guardedByUid = null;
        public int guardRounds = 0;
        public int aegisBlocks = 0;
        // Bitmask of status timers freshly applied to self on the current turn (skip same-turn decrement)
        public int freshStatusMask = 0;
        public static final int FRESH_MARK = 1 << 0;
        public static final int FRESH_STEALTH = 1 << 1;
        public static final int FRESH_RIPOSTE = 1 << 2;
        public static final int FRESH_GUARD = 1 << 3;

        public boolean isBeast = false;
        public double finaleBonusPct = 0.0;

        public String traitId = null;
        public boolean isVirtuous = false;
        public boolean isAfflicted = false;

        public ArrayList<ActiveBuff> buffs;
        public ArrayList<ActiveDot> dots;

        public UnitState(
                String uid, int team, HeroDef heroDef, String name, String variant,
                int rank, int maxHp, List<String> equippedSkills, SkillDef[] equippedSkillDefs,
                List<String> trinkets, TrinketDef[] trinketDefs, int trinketFlags,
                int startStealth, int startAegis, ArrayList<ActiveBuff> buffs
        ) {
            this.uid = uid;
            this.team = team;
            this.heroId = heroDef.id;
            this.heroDef = heroDef;
            this.name = name;
            this.variant = variant;
            this.rank = rank;
            this.hp = maxHp;
            this.maxHp = maxHp;
            this.equippedSkills = equippedSkills;
            this.equippedSkillDefs = equippedSkillDefs;
            this.trinkets = trinkets;
            this.trinketDefs = trinketDefs;
            this.trinketFlags = trinketFlags;
            this.skillUses = new HashMap<>(4);
            this.skillCooldowns = new HashMap<>(4);
            this.stealthRounds = startStealth;
            this.aegisBlocks = startAegis;
            this.buffs = buffs;
            this.dots = new ArrayList<>(4);
        }

        private UnitState(UnitState src) {
            this.uid = src.uid;
            this.team = src.team;
            this.heroId = src.heroId;
            this.heroDef = src.heroDef;
            this.name = src.name;
            this.variant = src.variant;
            this.rank = src.rank;
            this.hp = src.hp;
            this.maxHp = src.maxHp;
            this.stress = src.stress;
            this.alive = src.alive;
            this.isCorpse = src.isCorpse;
            this.corpseRounds = src.corpseRounds;
            this.equippedSkills = src.equippedSkills;
            this.equippedSkillDefs = src.equippedSkillDefs;
            this.trinkets = src.trinkets;
            this.trinketDefs = src.trinketDefs;
            this.trinketFlags = src.trinketFlags;
            this.skillUses = src.skillUses.isEmpty() ? new HashMap<>(2) : new HashMap<>(src.skillUses);
            this.skillCooldowns = src.skillCooldowns.isEmpty() ? new HashMap<>(2) : new HashMap<>(src.skillCooldowns);
            this.actedThisRound = src.actedThisRound;
            this.stunned = src.stunned;
            this.dazed = src.dazed;
            this.atDeathsDoor = src.atDeathsDoor;
            this.markedRounds = src.markedRounds;
            this.stealthRounds = src.stealthRounds;
            this.riposteRounds = src.riposteRounds;
            this.guardingUid = src.guardingUid;
            this.guardedByUid = src.guardedByUid;
            this.guardRounds = src.guardRounds;
            this.aegisBlocks = src.aegisBlocks;
            this.freshStatusMask = src.freshStatusMask;
            this.isBeast = src.isBeast;
            this.finaleBonusPct = src.finaleBonusPct;
            this.traitId = src.traitId;
            this.isVirtuous = src.isVirtuous;
            this.isAfflicted = src.isAfflicted;
            int bn = src.buffs.size();
            this.buffs = new ArrayList<>(bn + 2);
            for (int i = 0; i < bn; i++) {
                ActiveBuff b = src.buffs.get(i);
                // Permanent buffs (roundsLeft < 0) are immutable and can be shared safely!
                this.buffs.add(b.roundsLeft < 0 ? b : b.copy());
            }
            int dn = src.dots.size();
            this.dots = new ArrayList<>(dn + 2);
            for (int i = 0; i < dn; i++) {
                this.dots.add(src.dots.get(i).copy());
            }
        }

        public UnitState copy() {
            return new UnitState(this);
        }

        public boolean hasTrinketFlag(int flag) {
            return (trinketFlags & flag) != 0;
        }

        public boolean hasDotKind(int kind) {
            for (int i = 0, n = dots.size(); i < n; i++) {
                if (dots.get(i).kind == kind) return true;
            }
            return false;
        }
    }

    /**
     * Atomic player or bot decision in Butcher's Circus.
     */
    public static final class Action {
        public final String kind; // "SKILL", "MOVE", "PASS", "SURRENDER"
        public final String actorUid;
        public final String skillId;
        public final List<String> targetUids;
        public final int moveDelta;
        private final int cachedHash;

        public Action(String kind, String actorUid, String skillId, List<String> targetUids, int moveDelta) {
            this.kind = kind;
            this.actorUid = actorUid == null ? "" : actorUid;
            this.skillId = skillId == null ? "" : skillId;
            this.targetUids = targetUids == null ? Collections.emptyList() : List.copyOf(targetUids);
            this.moveDelta = moveDelta;
            // Same hash as Objects.hash, without a varargs array or boxing moveDelta.
            int hash = 31 + Objects.hashCode(this.kind);
            hash = 31 * hash + this.actorUid.hashCode();
            hash = 31 * hash + this.skillId.hashCode();
            hash = 31 * hash + this.targetUids.hashCode();
            this.cachedHash = 31 * hash + this.moveDelta;
        }

        public static Action pass(String actorUid) {
            return new Action("PASS", actorUid, "", Collections.emptyList(), 0);
        }

        public static Action move(String actorUid, int moveDelta) {
            return new Action("MOVE", actorUid, "", Collections.emptyList(), moveDelta);
        }

        public static Action skill(String actorUid, String skillId, List<String> targetUids) {
            return new Action("SKILL", actorUid, skillId, targetUids, 0);
        }

        public static Action surrender() {
            return new Action("SURRENDER", "", "", Collections.emptyList(), 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("actor_uid", actorUid);
            m.put("skill_id", skillId);
            m.put("target_uids", new ArrayList<>(targetUids));
            m.put("move_delta", moveDelta);
            return m;
        }

        public static Action fromMap(Map<String, Object> d) {
            List<String> targets = new ArrayList<>();
            for (Object o : Json.listVal(d, "target_uids")) {
                targets.add(String.valueOf(o));
            }
            return new Action(
                    Json.str(d, "kind", "PASS"),
                    Json.str(d, "actor_uid", ""),
                    Json.str(d, "skill_id", ""),
                    targets,
                    Json.intVal(d, "move_delta", 0)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Action a)) return false;
            return moveDelta == a.moveDelta
                    && kind.equals(a.kind)
                    && actorUid.equals(a.actorUid)
                    && skillId.equals(a.skillId)
                    && targetUids.equals(a.targetUids);
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }

        @Override
        public String toString() {
            if ("SKILL".equals(kind)) return actorUid + ":" + skillId + "->" + targetUids;
            if ("MOVE".equals(kind)) return actorUid + ":MOVE(" + moveDelta + ")";
            return actorUid + ":" + kind;
        }
    }

    public static final class CombatEvent {
        public final String type;
        public final Map<String, Object> data;

        public CombatEvent(String type, Map<String, Object> data) {
            this.type = type;
            this.data = data;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>(2);
            m.put("type", type);
            m.put("data", data);
            return m;
        }
    }

    public static final class GameState {
        public int roundNum;
        public int firstTeamThisRound;
        public int activeTeam;
        public final int coinflipWinner;
        public final ArrayList<UnitState>[] teams;
        public boolean finished = false;
        public Integer winner = null;
        public String winReason = "";
        public int turnCount = 0;
        public final int maxRounds;
        public final long seed;

        @SuppressWarnings("unchecked")
        public GameState(int roundNum, int firstTeamThisRound, int activeTeam, int coinflipWinner,
                         ArrayList<UnitState> team0, ArrayList<UnitState> team1, int maxRounds, long seed) {
            this.roundNum = roundNum;
            this.firstTeamThisRound = firstTeamThisRound;
            this.activeTeam = activeTeam;
            this.coinflipWinner = coinflipWinner;
            this.teams = new ArrayList[]{team0, team1};
            this.maxRounds = maxRounds;
            this.seed = seed;
        }

        public GameState copy() {
            ArrayList<UnitState> c0 = new ArrayList<>(teams[0].size());
            for (int i = 0, n = teams[0].size(); i < n; i++) c0.add(teams[0].get(i).copy());
            ArrayList<UnitState> c1 = new ArrayList<>(teams[1].size());
            for (int i = 0, n = teams[1].size(); i < n; i++) c1.add(teams[1].get(i).copy());
            GameState g = new GameState(roundNum, firstTeamThisRound, activeTeam, coinflipWinner, c0, c1, maxRounds, seed);
            g.finished = finished;
            g.winner = winner;
            g.winReason = winReason;
            g.turnCount = turnCount;
            return g;
        }
    }

    public static final class Observation {
        public final int viewerTeam;
        public final int roundNum;
        public final int activeTeam;
        public final boolean isMyTurn;
        public final boolean finished;
        public final Integer winner;
        public final String winReason;
        public final List<Map<String, Object>> myUnits;
        public final List<Map<String, Object>> enemyUnits;
        public final List<Action> legalActions;

        public Observation(
                int viewerTeam, int roundNum, int activeTeam, boolean isMyTurn,
                boolean finished, Integer winner, String winReason,
                List<Map<String, Object>> myUnits, List<Map<String, Object>> enemyUnits,
                List<Action> legalActions
        ) {
            this.viewerTeam = viewerTeam;
            this.roundNum = roundNum;
            this.activeTeam = activeTeam;
            this.isMyTurn = isMyTurn;
            this.finished = finished;
            this.winner = winner;
            this.winReason = winReason;
            this.myUnits = myUnits;
            this.enemyUnits = enemyUnits;
            this.legalActions = legalActions;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("viewer_team", viewerTeam);
            m.put("round_num", roundNum);
            m.put("active_team", activeTeam);
            m.put("is_my_turn", isMyTurn);
            m.put("finished", finished);
            m.put("winner", winner);
            m.put("win_reason", winReason);
            m.put("my_units", myUnits);
            m.put("enemy_units", enemyUnits);
            List<Map<String, Object>> acts = new ArrayList<>(legalActions.size());
            for (Action a : legalActions) acts.add(a.toMap());
            m.put("legal_actions", acts);
            return m;
        }
    }
}
