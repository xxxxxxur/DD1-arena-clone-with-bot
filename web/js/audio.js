/**
 * Butcher's Circus Web Audio Engine.
 * Streams real peak-normalized 16-bit PCM WAV samples decoded from Darkest Dungeon's
 * FMOD .bank files on demand, with a master soft-limiter to prevent clipping.
 */

const AUDIO_CACHE_BUST = "pcm16_v2";

// Hero-specific preferred shared weapon impact sample when a skill has no dedicated *_imp sample
const HERO_DEFAULT_IMPACT = {
  abomination: ["hero_hellion", "char_share_imp_general"],
  antiquarian: ["hero_antiquarian", "char_share_imp_knife"],
  arbalest: ["hero_arbalest", "char_al_arb_sniper_imp"],
  bounty_hunter: ["hero_bounty_hunter", "char_share_imp_axe"],
  crusader: ["hero_crusader", "char_share_imp_sword"],
  flagellant: ["hero_jester", "sfx_bleed_03_heavy_sweetener"],
  grave_robber: ["hero_grave_robber", "char_share_imp_knife"],
  hellion: ["hero_hellion", "char_share_imp_axe"],
  highwayman: ["hero_highwayman", "char_share_imp_knife"],
  houndmaster: ["hero_bounty_hunter", "sfx_bleed_01_heavy_sweetener"],
  jester: ["hero_jester", "sfx_bleed_03_heavy_sweetener"],
  leper: ["hero_crusader", "char_share_imp_sword"],
  man_at_arms: ["hero_crusader", "char_share_imp_hammer"],
  musketeer: ["hero_musketeer", "char_share_imp_gun"],
  occultist: ["hero_occultist", "char_share_imp_magic_dark"],
  plague_doctor: ["hero_plague_doctor", "char_share_imp_plaguegrenade"],
  shieldbreaker: ["hero_musketeer", "char_share_imp_spear"],
  vestal: ["hero_vestal", "char_share_imp_hammer"],
};

export class CircusAudio {
  constructor() {
    this.ctx = null;
    this.manifest = {};
    this.buffers = new Map();
    this.bufferBytes = 0;
    this.loading = new Map();
    this.muted = false;
    this.musicVolume = 0.25;
    this.sfxVolume = 0.62;
    this.musicSource = null;
    this.musicInput = null;
    this.masterLimiter = null;
    this.musicGain = null;
    this.sfxGain = null;
    this.musicStarted = false;
  }

  setManifest(manifest) {
    this.manifest = manifest || {};
  }

  _ensureContext() {
    if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return false;
      this.ctx = new AC();

      // Master soft-limiter prevents hard digital clipping when AoE hits + crits + music overlap
      this.masterLimiter = this.ctx.createDynamicsCompressor();
      this.masterLimiter.threshold.value = -4;
      this.masterLimiter.knee.value = 8;
      this.masterLimiter.ratio.value = 12;
      this.masterLimiter.attack.value = 0.002;
      this.masterLimiter.release.value = 0.12;
      this.masterLimiter.connect(this.ctx.destination);

      this.musicGain = this.ctx.createGain();
      this.musicGain.gain.value = this.muted ? 0 : this.musicVolume;
      this.musicGain.connect(this.masterLimiter);

      this.sfxGain = this.ctx.createGain();
      this.sfxGain.gain.value = this.muted ? 0 : this.sfxVolume;
      this.sfxGain.connect(this.masterLimiter);
    }
    if (this.ctx.state === "suspended") {
      this.ctx.resume().catch(() => {});
    }
    return true;
  }

  unlock() {
    this._ensureContext();
  }

  setMuted(muted) {
    this.muted = !!muted;
    if (this.musicGain) this.musicGain.gain.value = this.muted ? 0 : this.musicVolume;
    if (this.sfxGain) this.sfxGain.gain.value = this.muted ? 0 : this.sfxVolume;
  }

  setMusicVolume(v) {
    this.musicVolume = Math.max(0, Math.min(1, v));
    if (this.musicGain && !this.muted) this.musicGain.gain.value = this.musicVolume;
  }

  setSfxVolume(v) {
    this.sfxVolume = Math.max(0, Math.min(1, v));
    if (this.sfxGain && !this.muted) this.sfxGain.gain.value = this.sfxVolume;
  }

  async _loadBuffer(bank, sampleName) {
    const key = `${bank}/${sampleName}`;
    if (this.buffers.has(key)) {
      const cached = this.buffers.get(key);
      this.buffers.delete(key);
      this.buffers.set(key, cached);
      return cached;
    }
    if (this.loading.has(key)) return this.loading.get(key);
    if (!this._ensureContext()) return null;

    const p = (async () => {
      try {
        const url = `/api/audio/${encodeURIComponent(bank)}/${encodeURIComponent(sampleName)}?v=${AUDIO_CACHE_BUST}`;
        const res = await fetch(url);
        if (!res.ok) return null;
        const arr = await res.arrayBuffer();
        const buf = await this.ctx.decodeAudioData(arr);
        this.buffers.set(key, buf);
        this.bufferBytes += buf.length * buf.numberOfChannels * 4;
        while (this.bufferBytes > 32 * 1024 * 1024 && this.buffers.size > 1) {
          const [oldKey, oldest] = this.buffers.entries().next().value;
          this.bufferBytes -= oldest.length * oldest.numberOfChannels * 4;
          this.buffers.delete(oldKey);
        }
        return buf;
      } catch {
        return null;
      } finally {
        this.loading.delete(key);
      }
    })();
    this.loading.set(key, p);
    return p;
  }

  _hasSample(bank, exactName) {
    const list = this.manifest[bank];
    return !!(list && exactName && list.includes(exactName));
  }

  _findSample(bank, substrings) {
    const list = this.manifest[bank];
    if (!list || !list.length) return null;
    for (const sub of substrings) {
      if (!sub) continue;
      const q = sub.toLowerCase();
      const exact = list.find((s) => s.toLowerCase() === q);
      if (exact) return exact;
      const matches = list.filter((s) => s.toLowerCase().includes(q));
      if (matches.length) {
        return matches[Math.floor(Math.random() * matches.length)];
      }
    }
    return null;
  }

  async playSample(bank, sampleName, volumeScale = 1.0, delayMs = 0) {
    if (this.muted || !sampleName) return;
    if (!this._ensureContext()) return;
    const buf = await this._loadBuffer(bank, sampleName);
    if (!buf || !this.ctx || this.muted || this.ctx.state !== "running") return;
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    const gain = this.ctx.createGain();
    gain.gain.value = volumeScale;
    src.connect(gain);
    gain.connect(this.sfxGain);
    src.onended = () => {
      src.disconnect();
      gain.disconnect();
    };
    const startAt = this.ctx.currentTime + Math.max(0, delayMs) / 1000;
    src.start(startAt);
  }

  async startArenaMusic() {
    if (this.musicStarted && this._currentMusicTrack === "circus_combat") return;
    if (!this._ensureContext()) return;
    if (this.musicSource) this.stopArenaMusic();
    this.musicStarted = true;
    this._currentMusicTrack = "circus_combat";
    // Stream the full 8-stem mixed Butcher's Circus Combat theme (Drums+Bass+Brass+Strings+Choir+Keys+Synths+Atmo)
    const player = new Audio(`/api/audio_mix/circus_combat?v=${AUDIO_CACHE_BUST}`);
    player.loop = true;
    this.musicSource = player;
    const input = this.ctx.createMediaElementSource(player);
    input.connect(this.musicGain);
    this.musicInput = input;
    try {
      await player.play();
    } catch {
      if (this.musicSource === player) this.stopArenaMusic();
    }
  }

  stopArenaMusic() {
    if (this.musicSource) {
      this.musicSource.pause();
      this.musicSource.removeAttribute("src");
      this.musicSource.load();
      this.musicInput.disconnect();
      this.musicSource = null;
      this.musicInput = null;
    }
    this.musicStarted = false;
    this._currentMusicTrack = null;
  }

  playUiClick() {
    // Crisp 0.29s Darkest Dungeon UI click (avoid 2.08s ui_dun_pit_click!)
    if (this._hasSample("ui_shared", "ui_town_char_skill_equip")) {
      this.playSample("ui_shared", "ui_town_char_skill_equip", 0.45);
      return;
    }
    if (this._hasSample("ui_shared", "ui_shr_button_click_enemy")) {
      this.playSample("ui_shared", "ui_shr_button_click_enemy", 0.42);
      return;
    }
    const s = this._findSample("ui_shared", ["ui_button_scroll_up", "ui_shr_button_click"]);
    if (s) this.playSample("ui_shared", s, 0.42);
  }

  playSkillTrigger() {
    // Short 0.42s crisp combat target-confirm tick played the instant a skill/target is clicked
    if (this._hasSample("ui_shared", "ui_shr_button_click_enemy")) {
      this.playSample("ui_shared", "ui_shr_button_click_enemy", 0.48);
    } else {
      this.playUiClick();
    }
  }

  playLockIn() {
    const s = this._findSample("dlc_thepit_sfx", ["ui_town_pit_enterring", "enterring_door", "ui_town_pit_fight"]);
    if (s) this.playSample("dlc_thepit_sfx", s, 0.75);
  }

  playCoinflip() {
    const s = this._findSample("dlc_thepit_sfx", ["ui_town_pit_coin_flip", "ui_town_pit_coin_lights"]);
    if (s) this.playSample("dlc_thepit_sfx", s, 0.80);
  }

  playRoundStart() {
    if (this._hasSample("general", "gen_combat_round")) {
      this.playSample("general", "gen_combat_round", 0.55);
    }
  }

  playMove() {
    if (this._hasSample("general", "gen_party_combatmove")) {
      this.playSample("general", "gen_party_combatmove", 0.60);
    }
  }

  playPass() {
    if (this._hasSample("general", "gen_char_pass")) {
      this.playSample("general", "gen_char_pass", 0.55);
    }
  }

  playHeroSkill(heroId, sfxHint, skillId) {
    if (this._hasSample("dlc_thepit_sfx", sfxHint)) {
      this.playSample("dlc_thepit_sfx", sfxHint, 0.78);
      return;
    }
    const bank = `hero_${heroId}`;
    const list = this.manifest[bank];
    if (!list || !list.length) return;
    const normalize = (name) => name.toLowerCase().replace(/[^a-z0-9]/g, "");
    const castClips = list.filter((name) => !/_miss$|_imp$|camp_skill|^char_share_|^sfx_/i.test(name));
    if (castClips.includes(sfxHint)) {
      this.playSample(bank, sfxHint, 0.78);
      return;
    }
    for (const hint of [sfxHint, skillId]) {
      if (!hint) continue;
      const matches = castClips.filter((name) => normalize(name).includes(normalize(hint)));
      if (matches.length) {
        this.playSample(bank, matches[Math.floor(Math.random() * matches.length)], 0.78);
        return;
      }
    }
  }

  /**
   * Authentic Darkest Dungeon hit impact + marked accent + Crit sting + Circus crowd cheer!
   */
  playSkillHit(heroId, sfxHint, skillId, isCrit = false, isAllyAttacker = true, isMarked = false) {
    const bank = `hero_${heroId}`;
    const list = this.manifest[bank] || [];
    const normalize = (name) => (name || "").toLowerCase().replace(/[^a-z0-9]/g, "");

    // 1. Skill-specific impact clip (e.g. char_al_arb_sniper_imp, char_al_bhr_flashbang_imp, char_al_maa_rampart_imp)
    let impactBank = null;
    let impactSample = null;
    if (sfxHint && list.includes(`${sfxHint}_imp`)) {
      impactBank = bank;
      impactSample = `${sfxHint}_imp`;
    } else {
      const impClips = list.filter((n) => /_imp$/i.test(n));
      for (const hint of [sfxHint, skillId]) {
        if (!hint) continue;
        const nh = normalize(hint);
        const m = impClips.find((n) => normalize(n).includes(nh) || nh.includes(normalize(n.replace(/_imp$/i, ""))));
        if (m) {
          impactBank = bank;
          impactSample = m;
          break;
        }
      }
    }

    // 2. Skill-weapon-matched shared impact sample (ranged gun/arrow/magic/explosion vs blade/hammer/axe)
    if (!impactSample) {
      const sid = (skillId || "").toLowerCase();
      let preferred = null;
      if (/pistol|grapeshot|point_blank|buckshot|aimed|sidearm|smokescreen/.test(sid)) {
        preferred = ["hero_highwayman", "char_share_imp_gun"];
      } else if (/sniper|blindfire|bola|suppressing/.test(sid)) {
        preferred = ["hero_highwayman", "char_share_imp_arrow"];
      } else if (/stunning_blow|rampart|mace|bash|shield/.test(sid)) {
        preferred = ["hero_crusader", "char_share_imp_shield"];
      } else if (/judgement|dazzling|zealous|holy|hand_of_light|illumination/.test(sid)) {
        preferred = ["hero_crusader", "char_share_imp_magic_light"];
      } else if (/abyssal|sacrificial|ceiling|daemon|weakening|vulnerability/.test(sid)) {
        preferred = ["hero_occultist", "char_share_imp_magic_dark"];
      } else if (/plague_grenade|blinding_gas|disorienting|flashbang|noxious/.test(sid)) {
        preferred = ["hero_plague_doctor", "char_share_imp_plaguegrenade"];
      } else if (/dagger|dirk|lunge|pick|slice|wicked_slice|open_vein|incision|nervous_stab/.test(sid)) {
        preferred = ["hero_grave_robber", "char_share_imp_knife"];
      } else if (/chop|hew|smite|iron_swan|wicked_hack|if_it_bleeds|collect_bounty|finish_him/.test(sid)) {
        preferred = ["hero_crusader", "char_share_imp_sword"];
      } else if (HERO_DEFAULT_IMPACT[heroId]) {
        preferred = HERO_DEFAULT_IMPACT[heroId];
      }

      if (preferred && this._hasSample(preferred[0], preferred[1])) {
        [impactBank, impactSample] = preferred;
      } else {
        const fallbackInBank = list.find((n) => n.startsWith("char_share_imp_") || n.includes("org_imp_"));
        if (fallbackInBank) {
          impactBank = bank;
          impactSample = fallbackInBank;
        } else if (this._hasSample("hero_hellion", "char_share_imp_general")) {
          impactBank = "hero_hellion";
          impactSample = "char_share_imp_general";
        }
      }
    }

    if (impactBank && impactSample) {
      this.playSample(impactBank, impactSample, isCrit ? 0.85 : 0.70);
    }

    // 3. Marked target hit accent
    if (isMarked && this._hasSample("general", "char_share_marked_hit")) {
      this.playSample("general", "char_share_marked_hit", 0.55, 25);
    }

    // 4. Iconic Darkest Dungeon CRIT sting + Butcher's Circus crowd cheer!
    if (isCrit) {
      this.playCrit(isAllyAttacker);
    }
  }

  playSkillMiss(heroId, sfxHint, skillId) {
    const bank = `hero_${heroId}`;
    const list = this.manifest[bank] || [];
    const normalize = (name) => (name || "").toLowerCase().replace(/[^a-z0-9]/g, "");
    const misses = list.filter((n) => /_miss$/i.test(n));
    if (sfxHint && misses.includes(`${sfxHint}_miss`)) {
      this.playSample(bank, `${sfxHint}_miss`, 0.65);
      return;
    }
    for (const hint of [sfxHint, skillId]) {
      if (!hint) continue;
      const nh = normalize(hint);
      const m = misses.find((n) => normalize(n).includes(nh) || nh.includes(normalize(n.replace(/_miss$/i, ""))));
      if (m) {
        this.playSample(bank, m, 0.65);
        return;
      }
    }
    if (misses.length) {
      this.playSample(bank, misses[Math.floor(Math.random() * misses.length)], 0.60);
    } else if (this._hasSample("general", "gen_status_resist")) {
      this.playSample("general", "gen_status_resist", 0.55);
    }
  }

  playCrit(isAllyAttacker = true) {
    // Authentic Darkest Dungeon Crit impact sting (hero vs enemy)!
    const critClip = isAllyAttacker ? "char_share_crit_hero" : "char_share_crit_enemy";
    if (this._hasSample("general", critClip)) {
      this.playSample("general", critClip, 0.90);
    }
    // Layer Butcher's Circus arena crowd cheer slightly after the crit slam!
    const crowd = this._findSample("dlc_thepit_sfx", ["amb_dun_pit_os_crit_lrg", "amb_dun_pit_os_crit_med", "amb_dun_pit_os_crit_sm"]);
    if (crowd) {
      this.playSample("dlc_thepit_sfx", crowd, 0.58, 90);
    }
  }

  playDeathsDoor() {
    if (this._hasSample("general", "gen_combat_deathsdoor")) {
      this.playSample("general", "gen_combat_deathsdoor", 0.82);
    }
  }

  playHeartAttack() {
    if (this._hasSample("general", "gen_char_heartattack")) {
      this.playSample("general", "gen_char_heartattack", 0.88);
    }
  }

  playResolveCheck(isVirtue) {
    const clip = isVirtue ? "gen_char_resolve_virtue" : "gen_char_resolve_afflict";
    if (this._hasSample("general", clip)) {
      this.playSample("general", clip, 0.85);
    }
  }

  playDeathblow(isAllyVictim = false) {
    const deathClip = isAllyVictim ? "gen_char_death_ally" : "gen_char_death_enemy";
    if (this._hasSample("general", deathClip)) {
      this.playSample("general", deathClip, 0.85);
    }
    const crowd = this._findSample("dlc_thepit_sfx", ["amb_dun_pit_os_death_lrg", "amb_dun_pit_os_death_med", "amb_dun_pit_os_death_sm"]);
    if (crowd) this.playSample("dlc_thepit_sfx", crowd, 0.68, 120);
    const vo = this._findSample("dlc_thepit_vo", ["death", "kill", "finish", "blood"]);
    if (vo) this.playSample("dlc_thepit_vo", vo, 0.78, 380);
  }

  playMatchEnd() {
    const s = this._findSample("dlc_thepit_sfx", ["amb_dun_pit_os_finish_lrg", "amb_dun_pit_os_finish_med", "gen_raid_pit_prestige_nextlevel"]);
    if (s) this.playSample("dlc_thepit_sfx", s, 0.85);
  }

  playStress() {
    if (this._hasSample("general", "gen_status_stress_onset")) {
      this.playSample("general", "gen_status_stress_onset", 0.52);
    }
  }

  playStressHeal() {
    if (this._hasSample("general", "gen_status_stressdown")) {
      this.playSample("general", "gen_status_stressdown", 0.52);
    }
  }

  playHeal(isAlly = true, isCrit = false) {
    const clip = isAlly
      ? (isCrit ? "gen_status_heal_ally_crit" : "gen_status_heal_ally")
      : (isCrit ? "gen_status_heal_enemy_crit" : "gen_status_heal_enemy");
    if (this._hasSample("general", clip)) {
      this.playSample("general", clip, 0.58);
    }
  }

  playStatus(status) {
    if (status === "daze" && this._hasSample("dlc_thepit_sfx", "gen_status_daze")) {
      this.playSample("dlc_thepit_sfx", "gen_status_daze", 0.65);
      return;
    }
    const map = {
      stun: "gen_status_stun_onset",
      bleed: "gen_status_bleed_onset",
      blight: "gen_status_poison_onset",
      mark: "gen_status_marked",
      guard: "gen_status_guard",
      riposte: "gen_status_riposte",
      aegis: "gen_status_block_onset",
      block: "gen_status_block",
      resist: "gen_status_resist",
      buff: "gen_status_buff",
      debuff: "gen_status_debuff",
    };
    const clip = map[status];
    if (clip && this._hasSample("general", clip)) {
      this.playSample("general", clip, 0.58);
    }
  }
}
