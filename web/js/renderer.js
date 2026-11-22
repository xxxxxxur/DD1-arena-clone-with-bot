/**
 * Authentic Darkest Dungeon: The Butcher's Circus Arena Stage Renderer.
 * Pure visualization layer — zero game math/rules!
 * Faithfully reproduces the original game's frontend visual pipeline:
 *  - Exact formation & status bar geometry (arena.screen.raid.darkest, screen.raid.status_bars.darkest)
 *  - Real overlay PNGs: selected_1, target_1/target_h_1, plus/plus_h, turnticker, stress_pip_*, tray_*, poptext_*, movename_frame
 *  - Real bone-attached skill FX (fxskill / fxhead / fxchest) following live actor/target lunge & close-up transforms
 *  - Real persistent Spine fx/halo overhead loops (stun_loop, daze_loop, deaths_door, afflicted, heroic)
 *  - Toggleable Darkest Dungeon Combat Close-Up Spotlight Camera ("技能特写镜头")
 */
import { SpineAssetCache } from "./spine21.js";

const STAGE_W = 1920;
const STAGE_H = 720;
// Exact perspective dirt pit floor line in arena.entrance_room_wall_*.png (1920x720)
const GROUND_Y = 636;

// Exact 4v4 formation coordinates matching arena.screen.raid.darkest (spacing 168px)
const LEFT_RANK_X = { 4: 284, 3: 452, 2: 620, 1: 788 };
const RIGHT_RANK_X = { 1: 1132, 2: 1300, 3: 1468, 4: 1636 };

// Real Spine 2.1 hero rigs are ~310-415px tall -> 0.86 matches Darkest Dungeon proportions
const HERO_SCALE = 0.86;

const OVERLAY_ASSETS = {
  selected: "overlays/selected_1.png",
  selected_noaction: "overlays/selected_noaction_1.png",
  target: "overlays/target_1.png",
  target_h: "overlays/target_h_1.png",
  plus: "overlays/plus.png",
  plus_h: "overlays/plus_h.png",
  turnticker: "overlays/turnticker.png",
  movename_frame: "overlays/movename_frame.png",
  stress_empty: "overlays/stress_pip_empty.png",
  stress_full: "overlays/stress_pip_full.png",
  stress_over: "overlays/stress_pip_full_overstressed.png",
  // Tray 24x24 status icons
  tray_bleed: "overlays/tray_bleed.png",
  tray_poison: "overlays/tray_poison.png",
  tray_stun: "overlays/tray_stun.png",
  tray_daze: "overlays/tray_daze.png",
  tray_tag: "overlays/tray_tag.png",
  tray_stealth: "overlays/tray_stealth.png",
  tray_riposte: "overlays/tray_riposte.png",
  tray_guard: "overlays/tray_guard.png",
  tray_deathsdoor: "overlays/tray_deathsdoor.png",
  tray_block: "overlays/tray_health_damage_block.png",
  tray_regen: "overlays/tray_dot_hp_heal.png",
  tray_horror: "overlays/tray_dot_stress.png",
  tray_afflicted: "overlays/tray_afflicted.png",
  tray_virtued: "overlays/tray_virtued.png",
  tray_buff: "overlays/tray_buff.png",
  tray_debuff: "overlays/tray_debuff.png",
  // Poptext icons
  pop_bleed: "overlays/poptext_bleed.png",
  pop_bleed_resist: "overlays/poptext_bleed_resist.png",
  pop_poison: "overlays/poptext_poison.png",
  pop_poison_resist: "overlays/poptext_poison_resist.png",
  pop_daze: "overlays/poptext_daze.png",
  pop_daze_resist: "overlays/poptext_daze_resist.png",
  pop_stun_resist: "overlays/poptext_stun_resist.png",
  pop_tagged: "overlays/poptext_tagged.png",
  pop_riposte: "overlays/poptext_riposte.png",
  pop_guard: "overlays/poptext_guard.png",
  pop_guard_break: "overlays/poptext_guard_break.png",
  pop_death_avoided: "overlays/poptext_death_avoided.png",
  pop_block: "overlays/poptext_health_damage_block.png",
  pop_buff: "overlays/poptext_buff.png",
  pop_debuff: "overlays/poptext_debuff.png",
  pop_move_resist: "overlays/poptext_move_resist.png",
  pop_regen: "overlays/poptext_hp_heal_dot_onset.png",
};

export class ArenaRenderer {
  constructor(canvas, audio) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.audio = audio;
    this.spineCache = new SpineAssetCache();

    this.arenaTier = "darkest";
    this.bgImage = null;
    this.loadArenaBackground("darkest");

    // Preload real Darkest Dungeon overlay PNG textures
    this.overlays = {};
    this._preloadOverlays();

    // Toggleable Darkest Dungeon Combat Close-Up Camera option
    this.closeupEnabled = localStorage.getItem("bc_closeup") !== "false";

    // Global Spine FX
    this.torchSpine = null;
    this._torchMode = "neutral";
    this.coinflipSpine = null;
    this.coinflipTimer = 0;
    this.coinflipWinnerText = "";
    this._initGlobalFx();

    // Runtime unit visual states keyed by uid
    this.unitVisuals = new Map();
    this.latestObs = null;
    this.selectedActorUid = null;
    this.armedSkill = null;
    this.hoveredUid = null;
    this.hoveredIcon = null;
    this.iconHitboxes = [];
    this.inspectedEnemyUid = null;

    // Active bone-attached & world FX, poptexts, move banner, dramatic banner
    this.activeFx = [];
    this.poptexts = [];
    this.moveBanner = null; // { text, age, life }
    this.banner = null;

    // Camera & Close-Up Spotlight state
    this.camZoom = 1.0;
    this.camTargetZoom = 1.0;
    this.camTilt = 0.0;
    this.camTargetTilt = 0.0;
    this.camX = STAGE_W * 0.5;
    this.camY = STAGE_H * 0.5;
    this.camTargetX = STAGE_W * 0.5;
    this.camTargetY = STAGE_H * 0.5;
    this.shakeTime = 0;
    this.shakeMag = 0;
    this.flashColor = null;
    this.flashAlpha = 0;

    this.spotlightAlpha = 0;
    this.spotlightTargetAlpha = 0;
    this.spotlightUids = new Set();
    this.closeupActive = false;
    this.closeupAge = 0;
    this.closeupCrit = false;
    this.hitStopTimer = 0;
    this._preloadedRosterSig = "";

    this.playbackId = 0;
    this.playbackSpeed = 1.0;
    this.lang = "zh";

    this._lastTs = performance.now();
    this._raf = requestAnimationFrame((ts) => this._loop(ts));
  }

  _preloadOverlays() {
    for (const [key, rel] of Object.entries(OVERLAY_ASSETS)) {
      const img = new Image();
      img.src = `/api/game_asset/${rel}`;
      this.overlays[key] = img;
    }
  }

  _imgReady(key) {
    const im = this.overlays[key];
    return im && im.complete && im.naturalWidth > 0 ? im : null;
  }

  async _initGlobalFx() {
    this.torchSpine = await this.spineCache.createGlobalFx("arena_torch");
    if (this.torchSpine) {
      this.torchSpine.setAnimation("neutral", true);
    }
    this._torchMode = "neutral";
  }

  setCloseupEnabled(enabled) {
    this.closeupEnabled = !!enabled;
    localStorage.setItem("bc_closeup", this.closeupEnabled ? "true" : "false");
    if (!this.closeupEnabled) {
      this._resetCamera();
    }
  }

  loadArenaBackground(tier) {
    if (tier === this.arenaTier && this.bgImage) return;
    this.arenaTier = tier || "darkest";
    const requestedTier = this.arenaTier;
    const img = new Image();
    img.src = `/api/arena_bg/${this.arenaTier}`;
    img.onload = () => {
      if (this.arenaTier === requestedTier) this.bgImage = img;
    };
  }

  setLanguage(lang) {
    this.lang = lang;
  }

  setPlaybackSpeed(spd) {
    this.playbackSpeed = Math.max(0.5, Math.min(4.0, spd));
  }

  syncObservation(obs) {
    this.latestObs = obs;
    if (!obs) return;

    if (this.torchSpine) {
      const wantTorch = obs.finished ? "neutral" : obs.is_my_turn ? "player_turn" : "enemy_turn";
      if (this._torchMode !== wantTorch) {
        this._torchMode = wantTorch;
        this.torchSpine.setAnimation(wantTorch, true);
      }
    }

    const seen = new Set();
    const syncList = (units, isEnemy) => {
      for (const u of units) {
        seen.add(u.uid);
        const formX = u.alive
          ? (isEnemy ? RIGHT_RANK_X[u.rank] || 1400 : LEFT_RANK_X[u.rank] || 500)
          : (isEnemy ? 1860 : 60);

        let vis = this.unitVisuals.get(u.uid);
        if (!vis) {
          vis = {
            uid: u.uid,
            unit: u,
            isEnemy,
            x: formX,
            y: GROUND_Y,
            formationX: formX,
            targetX: formX,
            targetY: GROUND_Y,
            scaleMul: 1.0,
            targetScaleMul: 1.0,
            lungeOffsetX: 0,
            liveHp: u.hp,
            displayHp: u.hp,
            hpBufferHold: 0,
            liveStress: u.stress,
            spine: null,
            animName: "",
            overrideTimer: 0,
            hitFlash: 0,
            deathDissolve: -1,
            alpha: u.alive ? 1 : 0,
            loadingAnim: null,
            // Persistent overhead Spine halo (stun_loop / daze_loop / deaths_door)
            haloSpine: null,
            haloAnim: "",
            haloLoading: false,
          };
          this.unitVisuals.set(u.uid, vis);
        }
        if (vis.unit.hero_id !== u.hero_id || vis.unit.variant !== u.variant || vis.unit.is_beast !== u.is_beast) {
          vis.animName = "";
          vis.loadingAnim = null;
          vis.overrideTimer = 0;
          vis.spine = null;
        }
        vis.unit = u;
        vis.liveHp = u.hp;
        vis.liveStress = u.stress;
        if (Math.abs(vis.displayHp - u.hp) > u.max_hp) vis.displayHp = u.hp;
        vis.isEnemy = isEnemy;
        vis.formationX = formX;
        if (!this.closeupActive || !this.spotlightUids.has(u.uid)) {
          vis.targetX = formX;
          vis.targetY = GROUND_Y;
          vis.targetScaleMul = 1.0;
        }

        const desiredBase = u.is_corpse ? "dead" : "combat";
        if (vis.overrideTimer <= 0 && vis.animName !== desiredBase) {
          this._loadUnitAnim(vis, desiredBase, true);
        }

        // Sync persistent overhead Spine halo (Stunned -> stun_loop, Dazed -> daze_loop, Death's Door -> deaths_door)
        this._syncUnitHalo(vis);
      }
    };

    syncList(obs.my_units || [], false);
    syncList(obs.enemy_units || [], true);

    for (const uid of Array.from(this.unitVisuals.keys())) {
      if (!seen.has(uid)) this.unitVisuals.delete(uid);
    }
  }

  async _syncUnitHalo(vis) {
    const u = vis.unit;
    let wantHalo = "";
    if (u.alive && !u.is_corpse) {
      if (u.stunned) wantHalo = "stun_loop";
      else if (u.dazed) wantHalo = "daze_loop";
      else if (u.at_deaths_door) wantHalo = "deaths_door";
    }

    if (!wantHalo) {
      vis.haloAnim = "";
      return;
    }
    if (vis.haloAnim === wantHalo && vis.haloSpine) return;
    vis.haloAnim = wantHalo;
    if (!vis.haloSpine && !vis.haloLoading) {
      vis.haloLoading = true;
      const inst = await this.spineCache.createGlobalFx("halo");
      vis.haloLoading = false;
      if (inst) {
        vis.haloSpine = inst;
        if (vis.haloAnim) vis.haloSpine.setAnimation(vis.haloAnim, true);
      }
    } else if (vis.haloSpine) {
      vis.haloSpine.setAnimation(wantHalo, true);
    }
  }

  /**
   * Background-preload all combat Spine skeletons & FX for all 8 heroes so every skill click
   * swaps attacker/target poses & FX SYNCHRONOUSLY at 0ms with zero network stall!
   */
  preloadCombatAssets(obs, heroesCatalog) {
    if (!obs || !heroesCatalog) return;
    const allUnits = [...(obs.my_units || []), ...(obs.enemy_units || [])];
    const sig = allUnits.map((u) => `${u.hero_id}:${u.variant}:${(u.equipped_skills || []).join(",")}`).join("|");
    if (sig === this._preloadedRosterSig) return;
    this._preloadedRosterSig = sig;

    for (const fx of ["blood_splatter", "death_medium", "death_corpse_medium", "halo", "stress_cloud", "mark_target"]) {
      this.spineCache.createGlobalFx(fx).catch(() => {});
    }
    for (const u of allUnits) {
      const hdef = heroesCatalog[u.hero_id];
      const anims = new Set(["combat", "defend", "dead", "afflicted", "heroic"]);
      const fxs = new Set();
      if (hdef && hdef.skills) {
        for (const sid of u.equipped_skills || []) {
          const sd = hdef.skills.find((s) => s.id === sid);
          if (!sd) continue;
          if (sd.anim) anims.add(sd.anim);
          if (sd.fx) fxs.add(sd.fx);
          if (sd.targfx && sd.targfx !== "mark_target") fxs.add(sd.targfx);
        }
      }
      for (const a of anims) {
        this.spineCache.createHeroAnim(u.hero_id, a, u.variant).catch(() => {});
      }
      for (const fx of fxs) {
        this.spineCache.createHeroFx(u.hero_id, fx).catch(() => {});
      }
    }
  }

  _loadUnitAnim(vis, animName, loop = true, track = animName) {
    const u = vis.unit;
    if (u.hero_id === "abomination") {
      const beast = animName === "attack_transform" ? !u.is_beast : u.is_beast;
      track += beast ? "_beast" : "_human";
    }
    const reqKey = `${u.hero_id}:${u.variant}:${animName}:${track}`;
    if (loop && vis.loadingAnim === reqKey) return;
    vis.loadingAnim = reqKey;

    // 0ms SYNCHRONOUS fast path when preloaded in cache!
    const syncInst = this.spineCache.createHeroAnimSync(u.hero_id, animName, u.variant);
    if (syncInst) {
      syncInst.setAnimation(syncInst.animations[track] ? track : animName, loop);
      vis.spine = syncInst;
      vis.animName = animName;
      return;
    }

    this.spineCache.createHeroAnim(u.hero_id, animName, u.variant).then((inst) => {
      if (vis.loadingAnim !== reqKey || !inst) return;
      inst.setAnimation(inst.animations[track] ? track : animName, loop);
      vis.spine = inst;
      vis.animName = animName;
    });
  }

  /**
   * Compute live world coordinates (x, y, scale, flipX) for an anchor on a unit.
   * Uses the hero's REAL Spine bone coordinates (fxskill / fxchest / fxhead)!
   */
  _resolveUnitAnchor(vis, anchor = "feet", extraOffsetY = 0) {
    const baseX = vis.x + vis.lungeOffsetX;
    const baseY = vis.y;
    const sc = HERO_SCALE * vis.scaleMul;
    const dir = vis.isEnemy ? -1 : 1;

    if (anchor === "feet" || anchor === "fxskill") {
      return { x: baseX, y: baseY + extraOffsetY * sc, scale: sc, flipX: vis.isEnemy };
    }

    const boneName = anchor === "head" || anchor === "fxhead" ? "fxhead" : "fxchest";
    const bonePos = vis.spine?.getBonePos(boneName);
    if (bonePos) {
      return {
        x: baseX + bonePos.x * sc * dir,
        y: baseY + (bonePos.y + extraOffsetY) * sc,
        scale: sc,
        flipX: vis.isEnemy,
      };
    }

    const fallbackY = boneName === "fxhead" ? -260 : -178;
    return {
      x: baseX,
      y: baseY + (fallbackY + extraOffsetY) * sc,
      scale: sc,
      flipX: vis.isEnemy,
    };
  }

  _beginSkillCloseup(actorVis, targets, isAttack) {
    if (!this.closeupEnabled) return;
    const wasActive = this.closeupActive;
    this.closeupActive = true;
    this.closeupAge = 0;
    this.closeupCrit = false;
    this.spotlightUids.clear();
    if (actorVis) this.spotlightUids.add(actorVis.uid);
    for (const tv of targets) this.spotlightUids.add(tv.uid);

    // Authentic Darkest Dungeon 3-Phase Comic-Book Close-Up:
    // Phase 1 (HARD SNAP-IN): Instantly cut into foreground duel frame + blackout curtain!
    this.spotlightTargetAlpha = 0.87;
    if (!wasActive) {
      this.spotlightAlpha = 0.72; // Hard comic cut-in (no sluggish 300ms fade!)
      this.camZoom = 1.15;
      this.camX = STAGE_W * 0.5;
      this.camY = STAGE_H * 0.525;
      this.camTilt = actorVis ? (actorVis.isEnemy ? 0.017 : -0.017) : 0;
    }
    this.camTargetZoom = 1.24; // Phase 2: continuous slow comic zoom-in drift!
    this.camTargetX = STAGE_W * 0.5;
    this.camTargetY = STAGE_H * 0.53;
    this.camTargetTilt = actorVis ? (actorVis.isEnemy ? 0.022 : -0.022) : 0;

    const snapUnit = (vis, finalX, finalY, finalScale, snapKickX = 0) => {
      vis.targetX = finalX;
      vis.targetY = finalY;
      vis.targetScaleMul = finalScale;
      if (!wasActive) {
        // Snap immediately to foreground duel position with a crisp 38px kinetic entry kick!
        vis.x = finalX + snapKickX;
        vis.y = finalY;
        vis.scaleMul = finalScale * 0.94;
      }
    };

    const isSelfOnly = targets.length === 1 && actorVis && targets[0].uid === actorVis.uid;
    if (isSelfOnly && actorVis) {
      snapUnit(actorVis, STAGE_W * 0.5, GROUND_Y + 18, 1.32, 0);
      return;
    }

    if (actorVis) {
      const attackerSide = actorVis.isEnemy ? 1 : -1;
      // Attacker snaps slightly behind center-duel and lunges forward into the blow
      snapUnit(actorVis, STAGE_W * 0.5 + attackerSide * 218, GROUND_Y + 18, 1.30, attackerSide * 42);
    }

    const otherTargets = targets.filter((tv) => !actorVis || tv.uid !== actorVis.uid);
    if (otherTargets.length === 1) {
      const tv = otherTargets[0];
      const targetSide = actorVis ? (actorVis.isEnemy ? -1 : 1) : (tv.isEnemy ? 1 : -1);
      // Target snaps into duel frame and slides back on impact recoil
      snapUnit(tv, STAGE_W * 0.5 + targetSide * (isAttack ? 215 : 230), GROUND_Y + 18, 1.26, -targetSide * 32);
    } else if (otherTargets.length > 1) {
      const targetSide = actorVis ? (actorVis.isEnemy ? -1 : 1) : 1;
      const spacing = 148;
      otherTargets.forEach((tv, idx) => {
        snapUnit(tv, STAGE_W * 0.5 + targetSide * (140 + idx * spacing), GROUND_Y + 16, 1.18, -targetSide * 26);
      });
    }
  }

  resetPlayback() {
    this.playbackId = (this.playbackId || 0) + 1;
    this._resetCamera();
    this.unitVisuals.clear();
    this.activeFx.length = this.poptexts.length = 0;
    this.coinflipSpine = this.banner = this.moveBanner = this.latestObs = null;
    this._previewCast = null;
  }

  /**
   * Instant 0ms visual & audio feedback the moment the user clicks a target or self-skill
   * (before the WebSocket roundtrip returns from the Java backend).
   */
  previewSkillCast(actorUid, skillDef, targetUids = []) {
    if (!skillDef) return;
    const actorVis = this.unitVisuals.get(actorUid);
    const targets = (targetUids || []).map((id) => this.unitVisuals.get(id)).filter(Boolean);
    const isAttack = skillDef.target_team === "enemy";
    this._beginSkillCloseup(actorVis, targets, isAttack);
    const sName = this.lang === "zh" ? skillDef.name_zh : skillDef.name_en;
    this.moveBanner = { text: sName, age: 0, life: 1.45 };
    if (actorVis) {
      actorVis.overrideTimer = 1.05 / this.playbackSpeed;
      actorVis.lungeOffsetX = actorVis.isEnemy ? -52 : 52;
      this._loadUnitAnim(actorVis, skillDef.anim || "combat", false);
      if (this.closeupEnabled) this.camTargetZoom = 1.25;
      if (skillDef.fx) {
        this._spawnAttachedHeroFx(actorVis.unit.hero_id, skillDef.fx, actorVis.uid, "fxskill", 0);
      }
      this.audio?.playHeroSkill(actorVis.unit.hero_id, skillDef.sfx_hint, skillDef.id);
    }
    this._previewCast = { actorUid, skillId: skillDef.id, ts: performance.now() };
  }

  async playEvents(events) {
    if (!events || !events.length) return { hadResolveCheck: false, hadHeartAttack: false };
    const playbackId = this.playbackId;
    const wait = (ms) => new Promise((r) => setTimeout(r, ms / this.playbackSpeed));

    const BOUNDARY_TYPES = new Set([
      "MATCH_START",
      "COINFLIP",
      "ROUND_START",
      "TURN_START",
      "SKILL_USE",
      "RIPOSTE",
      "PASS",
      "STUN_SKIP",
      "TRANSFORM",
      "MATCH_END",
      "SURRENDER",
    ]);
    const KILL_TYPES = new Set([
      "DEATHBLOW",
      "CORPSE_SPAWNED",
      "CORPSE_CLEARED",
      "CORPSE_DESTROYED",
      "CORPSE_DECAYED",
    ]);
    const DEFERRED_TYPES = new Set([
      "RESOLVE_CHECK",
      "HEART_ATTACK",
    ]);

    let hadResolveCheck = false;
    let hadHeartAttack = false;
    let i = 0;

    while (i < events.length) {
      if (playbackId !== this.playbackId) return { hadResolveCheck, hadHeartAttack };
      const ev = events[i];

      if (BOUNDARY_TYPES.has(ev.type)) {
        await this._playSingleEvent(ev);
        i += 1;
        // Collect ALL resolution events belonging to this action/turn wave up to the next boundary
        // so AoE attacks display ALL 4 damages, ALL 4 stresses, debuffs & halos simultaneously,
        // while RESOLVE_CHECK / HEART_ATTACK are deferred strictly until after the action completes!
        const impactBatch = [];
        const killEvents = [];
        const deferredEvents = [];
        while (i < events.length && !BOUNDARY_TYPES.has(events[i].type)) {
          const cur = events[i];
          if (DEFERRED_TYPES.has(cur.type)) deferredEvents.push(cur);
          else if (KILL_TYPES.has(cur.type)) killEvents.push(cur);
          else impactBatch.push(cur);
          i += 1;
        }
        let waveStress = null;
        if (impactBatch.length) {
          waveStress = await this._playResolutionBatch(impactBatch, playbackId);
        }
        for (const kev of killEvents) {
          if (playbackId !== this.playbackId) return { hadResolveCheck, hadHeartAttack };
          await this._playSingleEvent(kev);
        }
        const hasStressHalos = waveStress && (waveStress.gainUids.size > 0 || waveStress.healUids.size > 0);
        if (hasStressHalos || deferredEvents.length) {
          // Finish the action animation & return from close-up BEFORE playing post-action stress halos & Affliction!
          if (this.closeupActive) {
            await wait(240);
            if (playbackId === this.playbackId) this._resetCamera();
            await wait(140);
          }
          if (hasStressHalos && playbackId === this.playbackId) {
            this._playPostActionStressHalos(waveStress.gainUids, waveStress.healUids);
            await wait(340);
          }
          for (const dev of deferredEvents) {
            if (playbackId !== this.playbackId) return { hadResolveCheck, hadHeartAttack };
            if (dev.type === "RESOLVE_CHECK") hadResolveCheck = true;
            if (dev.type === "HEART_ATTACK") hadHeartAttack = true;
            await this._playSingleEvent(dev);
          }
        }
      } else {
        // Standalone events not preceded by a boundary
        const impactBatch = [];
        const killEvents = [];
        const deferredEvents = [];
        while (i < events.length && !BOUNDARY_TYPES.has(events[i].type)) {
          const cur = events[i];
          if (DEFERRED_TYPES.has(cur.type)) deferredEvents.push(cur);
          else if (KILL_TYPES.has(cur.type)) killEvents.push(cur);
          else impactBatch.push(cur);
          i += 1;
        }
        const waveStress = impactBatch.length ? await this._playResolutionBatch(impactBatch, playbackId) : null;
        for (const kev of killEvents) {
          if (playbackId !== this.playbackId) return { hadResolveCheck, hadHeartAttack };
          await this._playSingleEvent(kev);
        }
        if (waveStress && (waveStress.gainUids.size > 0 || waveStress.healUids.size > 0) && playbackId === this.playbackId) {
          this._playPostActionStressHalos(waveStress.gainUids, waveStress.healUids);
          await wait(340);
        }
        for (const dev of deferredEvents) {
          if (playbackId !== this.playbackId) return { hadResolveCheck, hadHeartAttack };
          if (dev.type === "RESOLVE_CHECK") hadResolveCheck = true;
          if (dev.type === "HEART_ATTACK") hadHeartAttack = true;
          await this._playSingleEvent(dev);
        }
      }
    }

    if (this.closeupActive) {
      await wait(260);
    }
    if (playbackId === this.playbackId) this._resetCamera();
    return { hadResolveCheck, hadHeartAttack };
  }

  /**
   * Plays all resolution events from a single skill (including multi-target AoE hits/misses/heals/statuses)
   * simultaneously so AoE attacks feel unified and punchy just like the original Darkest Dungeon!
   */
  async _playResolutionBatch(batch, playbackId) {
    if (!batch.length || playbackId !== this.playbackId) return;
    const wait = (ms) => new Promise((r) => setTimeout(r, ms / this.playbackSpeed));

    let anyCrit = false;
    let anyHitOrMiss = false;
    let anyDeathsDoor = false;
    let hitAudioCount = 0;
    let critAudioPlayed = false;
    let missAudioPlayed = false;
    let healAudioPlayed = false;
    let stressAudioPlayed = false;
    let statusAudioPlayed = false;
    let moveAudioPlayed = false;
    const stressHaloUnits = new Set();
    const stressHealHaloUnits = new Set();

    // Track per-unit poptext vertical stacking so simultaneous dmg + bleed + stress never overlap!
    const unitPopStack = new Map();
    const nextStackOffset = (uid) => {
      const c = unitPopStack.get(uid) || 0;
      unitPopStack.set(uid, c + 1);
      return c * 34;
    };

    for (const ev of batch) {
      const t = ev.type;
      const d = ev.data || {};

      if (t === "HIT") {
        anyHitOrMiss = true;
        const tv = this.unitVisuals.get(d.target_uid);
        const av = this.unitVisuals.get(d.actor_uid);
        const heroId = d.hero_id || av?.unit?.hero_id || this.lastSkillCtx?.hero_id || "";
        const sfxHint = d.sfx_hint ?? this.lastSkillCtx?.sfx_hint ?? "";
        const skillId = d.skill_id || this.lastSkillCtx?.skill_id || "";
        const isAllyAttacker = av ? !av.isEnemy : (this.latestObs ? d.actor_team === this.latestObs.viewer_team : true);

        if (tv) {
          const chest = this._resolveUnitAnchor(tv, "fxchest");
          const dy = nextStackOffset(tv.uid);
          tv.hitFlash = 0.34;
          tv.lungeOffsetX = tv.isEnemy ? 50 : -50;
          if (!tv.unit.is_corpse) {
            tv.overrideTimer = 0.68 / this.playbackSpeed;
            this._loadUnitAnim(tv, "defend", false);
          }

          // Play punchy impact audio (cap to 2 weapon hits + 1 crit sting per AoE wave!)
          const playCritNow = !!d.crit && !critAudioPlayed;
          if (hitAudioCount < 2 || playCritNow) {
            this.audio?.playSkillHit(heroId, sfxHint, skillId, playCritNow, isAllyAttacker, !!d.marked);
            hitAudioCount += 1;
            if (playCritNow) critAudioPlayed = true;
          }

          if (d.hp !== undefined && d.hp !== null) {
            if (d.hp !== tv.liveHp) tv.hpBufferHold = 0.24;
            tv.liveHp = d.hp;
          }

          if (d.dmg > 0) {
            this._spawnAttachedGlobalFx("blood_splatter", tv.uid, "fxchest", 0, 0.88);
            if (d.crit) {
              anyCrit = true;
              this.closeupCrit = true;
              this.hitStopTimer = Math.max(this.hitStopTimer, 0.068);
              this._triggerShake(0.42, 22);
              this._triggerFlash("rgba(235, 70, 20, 0.36)");
              if (this.closeupEnabled) {
                // Iconic DD CRIT zoom punch!
                this.camZoom = Math.max(this.camZoom, 1.26);
                this.camTargetZoom = 1.35;
                this.camTilt *= 1.5;
                this.camTargetTilt *= 1.65;
              }
              this._spawnPoptext(
                this.lang === "zh" ? `暴击！ ${d.dmg}` : `CRIT! ${d.dmg}`,
                chest.x,
                chest.y - 88 - dy,
                "#ffcf48",
                "#5c0000",
                46,
                1.4,
                null
              );
            } else {
              this.hitStopTimer = Math.max(this.hitStopTimer, 0.025);
              this._triggerShake(0.18, 9);
              this._spawnPoptext(`${d.dmg}`, chest.x, chest.y - 72 - dy, "#ff4d3d", "#2a0000", 38, 1.18, null);
            }
          }
        }
      } else if (t === "MISS") {
        anyHitOrMiss = true;
        const tv = this.unitVisuals.get(d.target_uid);
        const av = this.unitVisuals.get(d.actor_uid);
        if (!missAudioPlayed) {
          const heroId = d.hero_id || av?.unit?.hero_id || this.lastSkillCtx?.hero_id || "";
          this.audio?.playSkillMiss(heroId, d.sfx_hint ?? this.lastSkillCtx?.sfx_hint ?? "", d.skill_id || this.lastSkillCtx?.skill_id || "");
          missAudioPlayed = true;
        }
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          tv.lungeOffsetX = tv.isEnemy ? 46 : -46;
          // Authentic DD: Dodge uses the hurt/defend pose (same silhouette animation, different SFX/VFX, no blood/red flash)
          if (!tv.unit.is_corpse) {
            tv.overrideTimer = 0.68 / this.playbackSpeed;
            this._loadUnitAnim(tv, "defend", false);
          }
          this._spawnPoptext(this.lang === "zh" ? "闪避！" : "DODGE!", head.x, head.y - 25 - dy, "#d4dce6", "#111", 34, 1.15);
        }
      } else if (t === "BLOCKED") {
        anyHitOrMiss = true;
        const tv = this.unitVisuals.get(d.target_uid);
        if (!statusAudioPlayed) {
          this.audio?.playStatus("block");
          statusAudioPlayed = true;
        }
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._spawnPoptext(this.lang === "zh" ? "格挡！" : "BLOCKED!", head.x, head.y - 25 - dy, "#8fd6ff", "#092538", 34, 1.2, "pop_block");
        }
      } else if (t === "HEAL") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (tv) {
          if (d.hp !== undefined && d.hp !== null) {
            if (d.hp !== tv.liveHp) tv.hpBufferHold = 0.22;
            tv.liveHp = d.hp;
          }
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          if (!healAudioPlayed) {
            this.audio?.playHeal(!tv.isEnemy, !!d.crit);
            healAudioPlayed = true;
          }
          this._spawnPoptext(`+${d.amount}`, head.x, head.y - 20 - dy, "#5cf282", "#063612", 36, 1.2);
        }
      } else if (t === "STRESS") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (tv) {
          if (d.stress !== undefined && d.stress !== null) {
            tv.liveStress = d.stress;
          }
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          if (!stressAudioPlayed) {
            this.audio?.playStress();
            stressAudioPlayed = true;
          }
          // Queue stress gain halo to display cleanly AFTER the action finishes!
          if ((d.amount || 0) > 0) {
            stressHaloUnits.add(tv.uid);
          }
          this._spawnPoptext(`+${d.amount}`, head.x, head.y - 50 - dy, "#e8e8e8", "#000000", 30, 1.22, "tray_horror");
        }
      } else if (t === "STRESS_HEAL") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (tv && d.amount > 0) {
          if (d.stress !== undefined && d.stress !== null) {
            tv.liveStress = d.stress;
          }
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this.audio?.playStressHeal();
          // Queue stress relief halo to display cleanly AFTER the action finishes!
          stressHealHaloUnits.add(tv.uid);
          this._spawnPoptext(`-${d.amount}`, head.x, head.y - 50 - dy, "#9cf0ff", "#082936", 30, 1.18, "tray_virtued");
        }
      } else if (t === "DOT_TICK") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (tv) {
          if (d.hp !== undefined && d.hp !== null) {
            if (d.hp !== tv.liveHp) tv.hpBufferHold = 0.24;
            tv.liveHp = d.hp;
          }
          const chest = this._resolveUnitAnchor(tv, "fxchest");
          const dy = nextStackOffset(tv.uid);
          const isBleed = d.kind === "bleed";
          const col = isBleed ? "#d91e2b" : "#80e038";
          const lbl = d.no_deathblow
            ? (this.lang === "zh" ? "持续伤害(无致死)" : "DoT (No Deathblow)")
            : `-${d.amount}`;
          this._spawnPoptext(lbl, chest.x, chest.y - 55 - dy, col, "#140404", 28, 1.1, isBleed ? "pop_bleed" : "pop_poison");
        }
      } else if (t === "MOVE") {
        if (!moveAudioPlayed) {
          this.audio?.playMove();
          moveAudioPlayed = true;
        }
      } else if (t === "DEBUFF_APPLIED") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._spawnPoptext(this.lang === "zh" ? "减益！" : "DEBUFF!", head.x, head.y - 56 - dy, "#f09048", "#240c04", 24, 1.1, "pop_debuff");
        }
      } else if (t === "BUFF_APPLIED") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._spawnPoptext(this.lang === "zh" ? "增益！" : "BUFF!", head.x, head.y - 56 - dy, "#7ed8ff", "#071c28", 24, 1.1, "pop_buff");
        }
      } else if (t === "STATUS_APPLIED") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (!statusAudioPlayed) {
          this.audio?.playStatus(d.status);
          statusAudioPlayed = true;
        }
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          const map = {
            stun: [this.lang === "zh" ? "眩晕！" : "STUNNED!", "#ffe066", "tray_stun"],
            daze: [this.lang === "zh" ? "晕眩！" : "DAZED!", "#f0cf5a", "pop_daze"],
            bleed: [this.lang === "zh" ? `流血 ${d.amount}/回` : `BLEED ${d.amount}`, "#e63946", "pop_bleed"],
            blight: [this.lang === "zh" ? `腐蚀 ${d.amount}/回` : `BLIGHT ${d.amount}`, "#8ce038", "pop_poison"],
            mark: [this.lang === "zh" ? "标记！" : "MARKED!", "#ff5959", "pop_tagged"],
            stealth: [this.lang === "zh" ? "潜行" : "STEALTH", "#c299ff", "tray_stealth"],
            riposte: [this.lang === "zh" ? "反击姿态" : "RIPOSTE", "#ffd480", "pop_riposte"],
            aegis: [this.lang === "zh" ? `格挡 +${d.amount}` : `BLOCK +${d.amount}`, "#70d6ff", "pop_block"],
            regen: [this.lang === "zh" ? `再生 +${d.amount}` : `REGEN +${d.amount}`, "#70f59b", "pop_regen"],
          };
          const spec = map[d.status];
          if (spec) this._spawnPoptext(spec[0], head.x, head.y - 60 - dy, spec[1], "#120a04", 26, 1.2, spec[2]);
          if (d.status === "stun") this._spawnAttachedGlobalFx("halo", tv.uid, "fxhead", -55, 0.9, "stunned");
          if (d.status === "daze") this._spawnAttachedGlobalFx("halo", tv.uid, "fxhead", -55, 0.9, "dazed");
        }
      } else if (t === "GUARD_SET") {
        const tv = this.unitVisuals.get(d.protected_uid);
        this.audio?.playStatus("guard");
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._spawnPoptext(this.lang === "zh" ? "守护！" : "GUARDED!", head.x, head.y - 52 - dy, "#80d4ff", "#092030", 26, 1.15, "pop_guard");
        }
      } else if (t === "GUARD_BROKEN") {
        const tv = this.unitVisuals.get(d.guardian_uid);
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._spawnPoptext(this.lang === "zh" ? "破除守护！" : "GUARD BROKEN!", head.x, head.y - 52 - dy, "#ff9966", "#2a0c04", 26, 1.15, "pop_guard_break");
        }
      } else if (t === "RESIST") {
        const tv = this.unitVisuals.get(d.unit_uid);
        if (!statusAudioPlayed) {
          this.audio?.playStatus("resist");
          statusAudioPlayed = true;
        }
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          const iconMap = {
            bleed: "pop_bleed_resist",
            blight: "pop_poison_resist",
            stun: "pop_stun_resist",
            daze: "pop_daze_resist",
            move: "pop_move_resist",
            debuff: "pop_debuff",
          };
          const lbl = d.kind === "daze"
            ? (this.lang === "zh" ? "抵抗晕眩" : "DAZE RESIST")
            : (this.lang === "zh" ? "抵抗" : "RESIST");
          this._spawnPoptext(lbl, head.x, head.y - 55 - dy, "#d4b483", "#1b1109", 24, 1.05, iconMap[d.kind] || null);
        }
      } else if (t === "DEATHS_DOOR") {
        anyDeathsDoor = true;
        const tv = this.unitVisuals.get(d.unit_uid);
        this.audio?.playDeathsDoor();
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._triggerShake(0.30, 14);
          this._triggerFlash("rgba(150, 0, 0, 0.36)");
          this._spawnPoptext(this.lang === "zh" ? "濒临死门！" : "DEATH'S DOOR!", head.x, head.y - 62 - dy, "#ff2a2a", "#000", 36, 1.45, "tray_deathsdoor");
        }
      } else if (t === "DEATHS_DOOR_RESIST") {
        const tv = this.unitVisuals.get(d.unit_uid);
        this.audio?.playStatus("resist");
        if (tv) {
          const head = this._resolveUnitAnchor(tv, "fxhead");
          const dy = nextStackOffset(tv.uid);
          this._spawnPoptext(this.lang === "zh" ? "抵抗死门！" : "DEATH'S DOOR!", head.x, head.y - 55 - dy, "#f5d77a", "#3d0000", 34, 1.3, "pop_death_avoided");
        }
      }
    }

    // One unified impact beat for the whole AoE / single-target wave!
    const beatMs = anyCrit ? 480 : anyDeathsDoor ? 420 : anyHitOrMiss ? 330 : 220;
    await wait(beatMs);
    return { gainUids: stressHaloUnits, healUids: stressHealHaloUnits };
  }

  _playPostActionStressHalos(gainUids, healUids) {
    for (const uid of gainUids) {
      const tv = this.unitVisuals.get(uid);
      if (!tv || !tv.unit.alive || tv.unit.is_corpse) continue;
      this._spawnAttachedGlobalFx("halo", uid, "fxhead", -55, 0.94, "afflicted");
      this._spawnAttachedGlobalFx("stress_cloud", uid, "fxhead", -35, 0.74, "stress_cloud");
    }
    for (const uid of healUids) {
      const tv = this.unitVisuals.get(uid);
      if (!tv || !tv.unit.alive || tv.unit.is_corpse) continue;
      this._spawnAttachedGlobalFx("halo", uid, "fxhead", -55, 0.94, "heroic");
    }
  }

  async _playSingleEvent(ev) {
    const playbackId = this.playbackId;
    const t = ev.type;
    const d = ev.data || {};
    const wait = (ms) => new Promise((r) => setTimeout(r, ms / this.playbackSpeed));

    if (t === "COINFLIP") {
      const cf = await this.spineCache.createGlobalFx("coinflip");
      if (playbackId !== this.playbackId) return;
      const isMe = this.latestObs && d.winner === this.latestObs.viewer_team;
      if (cf) {
        cf.setAnimation(isMe ? "coinflip_first" : "coinflip_second", false);
        this.coinflipSpine = cf;
        this.coinflipTimer = Math.max(2.2, cf.duration);
      }
      this.coinflipWinnerText =
        this.lang === "zh"
          ? isMe ? "掷币胜利 — 你方先攻！" : "掷币落败 — 敌方先攻！"
          : isMe ? "COIN FLIP WON — YOUR FIRST TURN!" : "COIN FLIP LOST — OPPONENT ACTS FIRST!";
      this.audio?.playCoinflip();
      await wait(1650);
      return;
    }

    if (t === "ROUND_START") {
      const firstMe = this.latestObs && d.first_team === this.latestObs.viewer_team;
      this.banner = {
        title: this.lang === "zh" ? `第 ${d.round} 回合` : `ROUND ${d.round}`,
        subtitle:
          this.lang === "zh"
            ? firstMe ? "你方先手" : "敌方先手"
            : firstMe ? "YOUR INITIATIVE" : "ENEMY INITIATIVE",
        color: "#e8c47a",
        age: 0,
        life: 1.15,
      };
      this.audio?.playRoundStart();
      await wait(360);
      return;
    }

    if (t === "SKILL_USE") {
      const actorVis = this.unitVisuals.get(d.actor_uid);
      const targets = (d.target_uids || []).map((id) => this.unitVisuals.get(id)).filter(Boolean);
      this.lastSkillCtx = { hero_id: d.hero_id, sfx_hint: d.sfx_hint, skill_id: d.skill_id, actor_uid: d.actor_uid };

      // Check if previewSkillCast() already started this exact cast 0-250ms ago so we don't double-play audio/FX!
      const alreadyPreviewed =
        this._previewCast &&
        this._previewCast.actorUid === d.actor_uid &&
        this._previewCast.skillId === d.skill_id &&
        performance.now() - this._previewCast.ts < 800;
      this._previewCast = null;

      this._beginSkillCloseup(actorVis, targets, d.is_attack !== false);

      // Authentic Darkest Dungeon Move Name Banner (overlays/movename_frame.png)
      const sName = this.lang === "zh" ? d.name_zh : d.name_en;
      this.moveBanner = { text: sName, age: 0, life: 1.4 };

      // ZERO pre-cast dead delay! Animation, FX, and Sound trigger instantaneously!
      if (actorVis && !alreadyPreviewed) {
        this.audio?.playHeroSkill(d.hero_id, d.sfx_hint, d.skill_id);
        actorVis.overrideTimer = 1.0 / this.playbackSpeed;
        actorVis.lungeOffsetX = actorVis.isEnemy ? -52 : 52;
        this._loadUnitAnim(actorVis, d.anim || "combat", false);
        if (this.closeupEnabled) this.camTargetZoom = 1.25;
        if (d.fx) {
          this._spawnAttachedHeroFx(d.hero_id, d.fx, actorVis.uid, "fxskill", 0);
        }
      }

      // Target Skill FX: attached to target's exact Spine bone (fxhead / fxchest / feet)!
      if (d.targfx) {
        const anchor = d.target_anchor === "head" ? "fxhead" : d.target_anchor === "chest" ? "fxchest" : "feet";
        for (const tv of targets) {
          if (d.targfx === "mark_target") {
            this._spawnAttachedGlobalFx(d.targfx, tv.uid, "fxhead", -30, 0.9);
          } else {
            this._spawnAttachedHeroFx(d.hero_id, d.targfx, tv.uid, anchor, 0);
          }
        }
      }
      // Instant impact on player click (0ms wait when already previewed, minimal 45ms for AI turns)!
      if (!alreadyPreviewed) await wait(45);
      return;
    }

    if (t === "RIPOSTE") {
      const vis = this.unitVisuals.get(d.actor_uid);
      const tv = this.unitVisuals.get(d.target_uid);
      this.lastSkillCtx = { hero_id: d.hero_id || vis?.unit?.hero_id || "", sfx_hint: "", skill_id: "riposte", actor_uid: d.actor_uid };
      if (vis) {
        this._beginSkillCloseup(vis, tv ? [tv] : [], true);
        this.moveBanner = { text: this.lang === "zh" ? "反击！" : "RIPOSTE!", age: 0, life: 1.0 };
        this.audio?.playStatus("riposte");
        vis.lungeOffsetX = vis.isEnemy ? -55 : 55;
        vis.overrideTimer = 0.75 / this.playbackSpeed;
        const counter = { man_at_arms: "attack_shield", abomination: "attack_barf" };
        this._loadUnitAnim(vis, counter[vis.unit.hero_id] || "attack_slice", false);
      }
      await wait(320);
      return;
    }

    if (t === "MOVE") {
      this.audio?.playMove();
      return;
    }

    if (t === "PASS") {
      this.audio?.playPass();
      return;
    }

    if (t === "STUN_SKIP") {
      const tv = this.unitVisuals.get(d.actor_uid);
      this.audio?.playPass();
      if (tv) {
        const head = this._resolveUnitAnchor(tv, "fxhead");
        this._spawnPoptext(this.lang === "zh" ? "眩晕跳过！" : "STUNNED!", head.x, head.y - 50, "#ffe066", "#2b1c00", 32, 1.2, "tray_stun");
      }
      await wait(420);
      return;
    }

    if (t === "RESOLVE_CHECK") {
      const tv = this.unitVisuals.get(d.unit_uid);
      if (tv && this.closeupEnabled) {
        this._beginSkillCloseup(tv, [tv], false);
      }
      const title = this.lang === "zh" ? d.name_zh : d.name_en;
      const bark = this.lang === "zh" ? d.bark_zh : d.bark_en;
      this.banner = {
        title: (d.is_virtue ? "✦ " : "☠ ") + title.toUpperCase() + (d.is_virtue ? " ✦" : " ☠"),
        subtitle: bark,
        color: d.is_virtue ? "#ffe478" : "#ff4040",
        age: 0,
        life: 2.0,
      };
      this._triggerShake(0.4, 15);
      this._triggerFlash(d.is_virtue ? "rgba(255, 220, 120, 0.35)" : "rgba(180, 0, 0, 0.38)");
      this.audio?.playResolveCheck(!!d.is_virtue);
      if (tv) {
        tv.overrideTimer = 1.5 / this.playbackSpeed;
        this._loadUnitAnim(tv, d.is_virtue ? "heroic" : "afflicted", false);
        this._spawnAttachedGlobalFx("halo", tv.uid, "fxhead", -55, 1.0, d.is_virtue ? "heroic" : "afflicted");
      }
      await wait(1100);
      return;
    }

    if (t === "HEART_ATTACK") {
      const tv = this.unitVisuals.get(d.unit_uid);
      if (tv && this.closeupEnabled) {
        this._beginSkillCloseup(tv, [tv], false);
      }
      this._triggerShake(0.48, 20);
      this._triggerFlash("rgba(200, 0, 0, 0.45)");
      this.audio?.playHeartAttack();
      if (tv) {
        if (tv.liveHp > 0) {
          tv.hpBufferHold = 0.28;
          tv.liveHp = 0;
        }
        tv.liveStress = d.at_deaths_door ? 200 : 170;
        const head = this._resolveUnitAnchor(tv, "fxhead");
        this._spawnPoptext(
          this.lang === "zh" ? "心脏骤停！" : "HEART ATTACK!",
          head.x,
          head.y - 72,
          "#ff2424",
          "#000000",
          44,
          1.6,
          "tray_deathsdoor"
        );
        // 200-stress Heart Attack MUST play hurt/defend recoil pose, NEVER the affliction animation!
        tv.overrideTimer = 1.1 / this.playbackSpeed;
        this._loadUnitAnim(tv, "defend", false);
      }
      await wait(900);
      return;
    }

    if (t === "DEATHBLOW") {
      // Authentic Darkest Dungeon Deathblow:
      // 1) Spotlight focus on victim, crimson impact flash + heavy shake
      // 2) Real fx/death_medium crimson skull + ring + rays over fxchest!
      // 3) Hero defend pose darkens into pure black silhouette & dissolves (fx/anim_snippets/death.json)
      // 4) Iconic crimson DEATHBLOW! poptext (NO generic modal banner blocking the skull!)
      const tv = this.unitVisuals.get(d.unit_uid);
      if (tv && this.closeupEnabled && !this.closeupActive) {
        this._beginSkillCloseup(tv, [tv], true);
      }
      this._triggerShake(0.55, 24);
      this._triggerFlash("rgba(195, 8, 8, 0.48)");
      this.audio?.playDeathblow(tv ? !tv.isEnemy : false);
      if (tv) {
        tv.overrideTimer = 1.15 / this.playbackSpeed;
        this._loadUnitAnim(tv, "defend", false);
        tv.deathDissolve = 0.001; // Triggers authentic ffffffff -> 000000ff -> 00000000 dissolve in _update/_draw!
        this._spawnAttachedGlobalFx("death_medium", tv.uid, "fxchest", 0, 1.28, "death");
        const head = this._resolveUnitAnchor(tv, "fxhead");
        this._spawnPoptext(
          this.lang === "zh" ? "致命一击！" : "DEATHBLOW!",
          head.x,
          head.y - 76,
          "#ff2626",
          "#000000",
          50,
          1.75,
          null
        );
      }
      await wait(920);
      return;
    }

    if (t === "CORPSE_SPAWNED") {
      // Real Darkest Dungeon corpse arrival: dark smoke & dust cloud (fx/death_corpse_medium)
      // + hero's unique dead skeleton (crusader.sprite.dead.skel -> helmet/sword in dirt)!
      const tv = this.unitVisuals.get(d.unit_uid);
      if (tv) {
        tv.deathDissolve = -1;
        tv.alpha = 1.0;
        tv.overrideTimer = 0;
        this._loadUnitAnim(tv, "dead", true, "dead");
        this._spawnAttachedGlobalFx("death_corpse_medium", tv.uid, "fxchest", 20, 1.05, "death");
      }
      await wait(240);
      return;
    }

    if (t === "CORPSE_DESTROYED" || t === "CORPSE_DECAYED") {
      const tv = this.unitVisuals.get(d.unit_uid);
      if (tv) {
        this._spawnAttachedGlobalFx("death_corpse_medium", tv.uid, "fxchest", 20, 1.0, "death");
        tv.deathDissolve = 0.45;
      }
      await wait(240);
      return;
    }

    if (t === "MATCH_END") {
      this.audio?.playMatchEnd();
      await wait(550);
    }
  }

  _resetCamera() {
    this.closeupActive = false;
    this.camTargetZoom = 1.0;
    this.camTargetTilt = 0.0;
    this.camTargetX = STAGE_W * 0.5;
    this.camTargetY = STAGE_H * 0.5;
    this.spotlightTargetAlpha = 0;
    this.spotlightUids.clear();
    for (const vis of this.unitVisuals.values()) {
      vis.targetX = vis.formationX;
      vis.targetY = GROUND_Y;
      vis.targetScaleMul = 1.0;
    }
  }

  _spawnAttachedHeroFx(heroId, fxName, attachUid, anchor = "fxskill", extraOffsetY = 0) {
    const pid = this.playbackId;
    const pushFx = (inst) => {
      if (!inst || pid !== this.playbackId) return;
      inst.setAnimation(Object.keys(inst.animations)[0] || fxName, false);
      this.activeFx.push({
        spine: inst,
        attachUid,
        anchor,
        extraOffsetY,
        scaleFactor: 1.0,
        ttl: Math.max(0.6, inst.duration + 0.1),
      });
    };
    const sync = this.spineCache.createHeroFxSync(heroId, fxName);
    if (sync) {
      pushFx(sync);
      return;
    }
    this.spineCache.createHeroFx(heroId, fxName).then(pushFx);
  }

  _spawnAttachedGlobalFx(fxName, attachUid, anchor = "fxchest", extraOffsetY = 0, scaleFactor = 0.9, trackName = null) {
    const pid = this.playbackId;
    const pushFx = (inst) => {
      if (!inst || pid !== this.playbackId) return;
      const chosen = trackName && inst.animations[trackName] ? trackName : (Object.keys(inst.animations)[0] || "animation");
      inst.setAnimation(chosen, false);
      this.activeFx.push({
        spine: inst,
        attachUid,
        anchor,
        extraOffsetY,
        scaleFactor,
        ttl: Math.max(0.6, inst.duration + 0.1),
      });
    };
    const sync = this.spineCache.createGlobalFxSync(fxName);
    if (sync) {
      pushFx(sync);
      return;
    }
    this.spineCache.createGlobalFx(fxName).then(pushFx);
  }

  _spawnPoptext(text, x, y, color, stroke, size = 32, life = 1.2, iconKey = null) {
    this.poptexts.push({ text, x, y, color, stroke, size, age: 0, life, iconKey });
  }

  _triggerShake(duration, mag) {
    this.shakeTime = Math.max(this.shakeTime, duration);
    this.shakeMag = Math.max(this.shakeMag, mag);
  }

  _triggerFlash(color) {
    this.flashColor = color;
    this.flashAlpha = 1.0;
  }

  _getStageViewport(cw, ch) {
    // Uniform 1:1 aspect-preserving scaling anchored to the bottom transition bar
    // so the perspective arena floor ALWAYS meets the HUD seamlessly with ZERO stretching!
    const scale = Math.min(cw / STAGE_W, ch / STAGE_H);
    const ox = (cw - STAGE_W * scale) * 0.5;
    const oy = ch - STAGE_H * scale;
    return { scale, ox, oy };
  }

  screenToStage(clientX, clientY) {
    const rect = this.canvas.getBoundingClientRect();
    const { scale, ox, oy } = this._getStageViewport(Math.max(1, rect.width), Math.max(1, rect.height));
    const sx = (clientX - rect.left - ox) / Math.max(0.001, scale);
    const sy = (clientY - rect.top - oy) / Math.max(0.001, scale);
    return { x: sx, y: sy };
  }

  pickTrayIconAt(clientX, clientY) {
    if (this.closeupActive || !this.iconHitboxes.length) return null;
    const { x, y } = this.screenToStage(clientX, clientY);
    for (let i = this.iconHitboxes.length - 1; i >= 0; i--) {
      const hb = this.iconHitboxes[i];
      if (x >= hb.x - 2 && x <= hb.x + hb.w + 2 && y >= hb.y - 2 && y <= hb.y + hb.h + 2) {
        return hb;
      }
    }
    return null;
  }

  pickUnitAt(clientX, clientY) {
    const { x, y } = this.screenToStage(clientX, clientY);
    let best = null;
    let bestDist = 82;
    for (const vis of this.unitVisuals.values()) {
      if (!vis.unit.alive) continue;
      if (y < GROUND_Y - 380 || y > GROUND_Y + 85) continue;
      const dx = Math.abs(x - vis.x);
      if (dx < bestDist) {
        bestDist = dx;
        best = vis.unit;
      }
    }
    return best;
  }

  _loop(ts) {
    const rawDt = Math.min(0.05, (ts - this._lastTs) / 1000);
    this._lastTs = ts;
    this._update(rawDt);
    this._draw();
    this._raf = requestAnimationFrame((t) => this._loop(t));
  }

  _update(dt) {
    // Hit-stop impact freeze frame (iconic Darkest Dungeon blow weight!)
    if (this.hitStopTimer > 0) {
      this.hitStopTimer = Math.max(0, this.hitStopTimer - dt);
      if (this.shakeTime > 0) this.shakeTime = Math.max(0, this.shakeTime - dt);
      if (this.flashAlpha > 0) this.flashAlpha = Math.max(0, this.flashAlpha - dt * 1.4);
      return;
    }

    const spdDt = dt * this.playbackSpeed;
    // Authentic Darkest Dungeon 3-Phase Close-Up Camera Curve:
    // - During closeup: crisp initial snap then slow continuous comic drift-in
    // - Returning to formation: fast easeOutQuart snap-back (dt * 16)
    let camLerp, unitLerp, spotLerp;
    if (this.closeupActive) {
      this.closeupAge += dt;
      // Continuous slow zoom drift during impact hold
      const maxZoom = this.closeupCrit ? 1.37 : 1.26;
      this.camTargetZoom = Math.min(maxZoom, this.camTargetZoom + dt * (this.closeupCrit ? 0.08 : 0.055));
      camLerp = Math.min(1, dt * (this.closeupAge < 0.08 ? 28.0 : 3.2));
      unitLerp = Math.min(1, dt * (this.closeupAge < 0.10 ? 24.0 : 5.5));
      spotLerp = Math.min(1, dt * 26.0);
    } else {
      camLerp = Math.min(1, dt * 16.0);
      unitLerp = Math.min(1, dt * 17.0);
      spotLerp = Math.min(1, dt * 16.0);
    }

    this.camZoom += (this.camTargetZoom - this.camZoom) * camLerp;
    this.camTilt += (this.camTargetTilt - this.camTilt) * camLerp;
    this.camX += (this.camTargetX - this.camX) * camLerp;
    this.camY += (this.camTargetY - this.camY) * camLerp;
    this.spotlightAlpha += (this.spotlightTargetAlpha - this.spotlightAlpha) * spotLerp;

    if (this.shakeTime > 0) this.shakeTime = Math.max(0, this.shakeTime - dt);
    if (this.flashAlpha > 0) this.flashAlpha = Math.max(0, this.flashAlpha - dt * 2.8);

    if (this.torchSpine) this.torchSpine.update(dt);
    if (this.coinflipSpine && this.coinflipTimer > 0) {
      this.coinflipSpine.update(dt);
      this.coinflipTimer -= dt;
      if (this.coinflipTimer <= 0) this.coinflipSpine = null;
    }

    for (const vis of this.unitVisuals.values()) {
      vis.x += (vis.targetX - vis.x) * unitLerp;
      vis.y += (vis.targetY - vis.y) * unitLerp;
      vis.scaleMul += (vis.targetScaleMul - vis.scaleMul) * unitLerp;
      vis.lungeOffsetX *= Math.pow(0.015, dt);
      // Smooth HP drain / heal buffer trail with authentic Darkest Dungeon hold delay
      if (vis.hpBufferHold > 0) {
        vis.hpBufferHold = Math.max(0, vis.hpBufferHold - dt);
      } else {
        vis.displayHp += (vis.liveHp - vis.displayHp) * Math.min(1, dt * 5.2);
        if (Math.abs(vis.liveHp - vis.displayHp) < 0.05) vis.displayHp = vis.liveHp;
      }
      if (vis.hitFlash > 0) vis.hitFlash = Math.max(0, vis.hitFlash - dt);

      if (vis.deathDissolve >= 0) {
        // Authentic fx/anim_snippets/death.json progression (0 -> 1 over ~0.95s)
        vis.deathDissolve = Math.min(1.0, vis.deathDissolve + spdDt * 1.08);
        if (vis.deathDissolve > 0.62) {
          vis.alpha = Math.max(0, 1.0 - (vis.deathDissolve - 0.62) / 0.38);
        } else {
          vis.alpha = 1.0;
        }
      } else {
        const targetAlpha = vis.unit.alive ? (vis.unit.stealth_rounds > 0 ? 0.52 : 1.0) : 0.0;
        vis.alpha += (targetAlpha - vis.alpha) * Math.min(1, dt * 8);
      }

      if (vis.overrideTimer > 0) {
        vis.overrideTimer -= dt;
        if (vis.overrideTimer <= 0) {
          const baseAnim = vis.unit.is_corpse ? "dead" : "combat";
          if (vis.animName !== baseAnim) this._loadUnitAnim(vis, baseAnim, true);
        }
      }
      if (vis.spine) vis.spine.update(spdDt);
      if (vis.haloSpine && vis.haloAnim) vis.haloSpine.update(dt);
    }

    for (let i = this.activeFx.length - 1; i >= 0; i--) {
      const fx = this.activeFx[i];
      fx.spine.update(spdDt);
      fx.ttl -= spdDt;
      if (fx.ttl <= 0 || fx.spine.finished) this.activeFx.splice(i, 1);
    }

    for (let i = this.poptexts.length - 1; i >= 0; i--) {
      const p = this.poptexts[i];
      p.age += dt;
      p.y -= dt * 46;
      if (p.age >= p.life) this.poptexts.splice(i, 1);
    }

    if (this.moveBanner) {
      this.moveBanner.age += dt;
      if (this.moveBanner.age >= this.moveBanner.life) this.moveBanner = null;
    }

    if (this.banner) {
      this.banner.age += dt;
      if (this.banner.age >= this.banner.life) this.banner = null;
    }
  }

  _draw() {
    this.iconHitboxes.length = 0;
    const ctx = this.ctx;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const cw = this.canvas.clientWidth || STAGE_W;
    const ch = this.canvas.clientHeight || STAGE_H;
    if (this.canvas.width !== Math.round(cw * dpr) || this.canvas.height !== Math.round(ch * dpr)) {
      this.canvas.width = Math.round(cw * dpr);
      this.canvas.height = Math.round(ch * dpr);
    }

    ctx.save();
    ctx.fillStyle = "#060304";
    ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

    const { scale, ox, oy } = this._getStageViewport(cw, ch);
    ctx.translate(ox * dpr, oy * dpr);
    ctx.scale(scale * dpr, scale * dpr);
    ctx.beginPath();
    ctx.rect(0, 0, STAGE_W, STAGE_H);
    ctx.clip();

    // Camera transform + Dutch tilt + screen shake
    ctx.save();
    let sx = 0, sy = 0;
    if (this.shakeTime > 0) {
      sx = (Math.random() - 0.5) * 2 * this.shakeMag * (this.shakeTime / 0.4);
      sy = (Math.random() - 0.5) * 2 * this.shakeMag * (this.shakeTime / 0.4);
    }
    ctx.translate(STAGE_W * 0.5 + sx, STAGE_H * 0.5 + sy);
    if (Math.abs(this.camTilt) > 0.0005) ctx.rotate(this.camTilt);
    ctx.scale(this.camZoom, this.camZoom);
    ctx.translate(-this.camX, -this.camY);

    // 1. Arena background wall
    this._drawBackground(ctx);

    // 2. Guard links & AoE target '+' connectors (hidden during blackout close-up)
    if (this.spotlightAlpha < 0.3) {
      this._drawGuardLinks(ctx);
      this._drawAoeConnectors(ctx);
    }

    // 3. Draw non-spotlight units -> Deep Blackout -> Spotlight close-up duelists!
    const allUnits = Array.from(this.unitVisuals.values()).filter((v) => v.alpha > 0.02);
    const hasSpotlight = this.spotlightAlpha > 0.02 && this.spotlightUids.size > 0;
    const backUnits = hasSpotlight ? allUnits.filter((v) => !this.spotlightUids.has(v.uid)) : allUnits;
    const frontUnits = hasSpotlight ? allUnits.filter((v) => this.spotlightUids.has(v.uid)) : [];

    for (const v of backUnits) this._drawUnit(ctx, v, false);

    if (this.spotlightAlpha > 0.02) {
      ctx.fillStyle = `rgba(3, 1, 2, ${this.spotlightAlpha.toFixed(3)})`;
      ctx.fillRect(-400, -300, STAGE_W + 800, STAGE_H + 600);

      const rim = ctx.createRadialGradient(STAGE_W * 0.5, STAGE_H * 0.58, 60, STAGE_W * 0.5, STAGE_H * 0.58, 620);
      rim.addColorStop(0, `rgba(180, 48, 22, ${(this.spotlightAlpha * 0.25).toFixed(3)})`);
      rim.addColorStop(1, "rgba(0, 0, 0, 0)");
      ctx.fillStyle = rim;
      ctx.fillRect(0, 0, STAGE_W, STAGE_H);

      for (const v of frontUnits) this._drawUnit(ctx, v, true);
    }

    // 4. Bone-attached One-shot Spine FX (tracks live actor/target positions & close-up scaling!)
    for (const fx of this.activeFx) {
      const parentVis = this.unitVisuals.get(fx.attachUid);
      if (!parentVis) continue;
      const pt = this._resolveUnitAnchor(parentVis, fx.anchor, fx.extraOffsetY || 0);
      fx.spine.draw(ctx, pt.x, pt.y, pt.scale * (fx.scaleFactor || 1.0), pt.flipX, 1.0);
    }

    // 5. Floating poptexts with REAL Darkest Dungeon poptext_*.png icons!
    for (const p of this.poptexts) {
      const a = Math.max(0, 1 - Math.max(0, (p.age - p.life * 0.65) / (p.life * 0.35)));
      ctx.save();
      ctx.globalAlpha = a;
      ctx.font = `900 ${p.size}px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
      const iconImg = p.iconKey ? this._imgReady(p.iconKey) : null;
      const textW = ctx.measureText(p.text).width;
      const iconW = iconImg ? Math.round(p.size * 0.92) : 0;
      const gap = iconImg ? 6 : 0;
      const totalW = iconW + gap + textW;
      const startX = p.x - totalW * 0.5;

      if (iconImg) {
        ctx.drawImage(iconImg, startX, p.y - iconW * 0.82, iconW, iconW);
      }
      ctx.textAlign = "left";
      ctx.lineWidth = 6;
      ctx.strokeStyle = p.stroke;
      ctx.strokeText(p.text, startX + iconW + gap, p.y);
      ctx.fillStyle = p.color;
      ctx.fillText(p.text, startX + iconW + gap, p.y);
      ctx.restore();
    }

    ctx.restore(); // end camera transform

    // 6. Top Arena Torch & Round Indicator
    ctx.save();
    ctx.globalAlpha = Math.max(0.3, 1.0 - this.spotlightAlpha * 0.65);
    this._drawArenaTorch(ctx);
    ctx.restore();

    // 7. Real Move Name Banner (overlays/movename_frame.png)
    if (this.moveBanner) {
      this._drawMoveBanner(ctx, this.moveBanner);
    }

    // 8. Vignette & impact flash
    const vig = ctx.createRadialGradient(STAGE_W * 0.5, STAGE_H * 0.45, 480, STAGE_W * 0.5, STAGE_H * 0.5, 1120);
    vig.addColorStop(0, "rgba(0,0,0,0)");
    vig.addColorStop(1, "rgba(0,0,0,0.68)");
    ctx.fillStyle = vig;
    ctx.fillRect(0, 0, STAGE_W, STAGE_H);

    if (this.flashAlpha > 0.01 && this.flashColor) {
      ctx.save();
      ctx.globalAlpha = this.flashAlpha;
      ctx.fillStyle = this.flashColor;
      ctx.fillRect(0, 0, STAGE_W, STAGE_H);
      ctx.restore();
    }

    // 9. Dramatic Banner (Round / Affliction / Virtue / Deathblow / Heart Attack)
    if (this.banner) this._drawBanner(ctx, this.banner);

    // 10. Coinflip overlay
    if (this.coinflipSpine && this.coinflipTimer > 0) {
      ctx.fillStyle = "rgba(0,0,0,0.62)";
      ctx.fillRect(0, 0, STAGE_W, STAGE_H);
      this.coinflipSpine.draw(ctx, STAGE_W * 0.5, STAGE_H * 0.52, 0.95, false, 1.0);
      if (this.coinflipWinnerText) {
        ctx.save();
        ctx.font = `900 42px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
        ctx.textAlign = "center";
        ctx.lineWidth = 7;
        ctx.strokeStyle = "#000";
        ctx.strokeText(this.coinflipWinnerText, STAGE_W * 0.5, STAGE_H * 0.84);
        ctx.fillStyle = "#f2cf7c";
        ctx.fillText(this.coinflipWinnerText, STAGE_W * 0.5, STAGE_H * 0.84);
        ctx.restore();
      }
    }

    ctx.restore();
  }

  _drawBackground(ctx) {
    if (this.bgImage && this.bgImage.complete && this.bgImage.naturalWidth > 0) {
      // Exact 1:1 native 1920x720 alignment so the perspective dirt pit floor matches GROUND_Y!
      ctx.drawImage(this.bgImage, 0, 0, STAGE_W, STAGE_H);
    } else {
      const grad = ctx.createLinearGradient(0, 0, 0, STAGE_H);
      grad.addColorStop(0, "#190c0e");
      grad.addColorStop(0.7, "#261214");
      grad.addColorStop(1, "#0d0708");
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, STAGE_W, STAGE_H);
    }

    // Subtle bottom seam fade (last 22px only) — never covers the lit perspective dirt floor!
    const seamGrad = ctx.createLinearGradient(0, STAGE_H - 24, 0, STAGE_H);
    seamGrad.addColorStop(0, "rgba(8, 5, 6, 0.0)");
    seamGrad.addColorStop(1, "rgba(8, 5, 6, 0.72)");
    ctx.fillStyle = seamGrad;
    ctx.fillRect(0, STAGE_H - 24, STAGE_W, 24);

    // Warm animated arena overhead torchlight ambience
    const pulse = 0.04 + 0.025 * Math.sin(performance.now() * 0.004);
    const glow = ctx.createRadialGradient(STAGE_W * 0.5, 110, 20, STAGE_W * 0.5, 240, 660);
    glow.addColorStop(0, `rgba(255, 155, 60, ${pulse + 0.05})`);
    glow.addColorStop(1, "rgba(255, 80, 20, 0)");
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, STAGE_W, STAGE_H);
  }

  _drawArenaTorch(ctx) {
    if (this.torchSpine) {
      this.torchSpine.draw(ctx, STAGE_W * 0.5, 110, 0.44, false, 0.95);
    }
    if (!this.latestObs) return;
    const roundTxt = this.lang === "zh" ? `第 ${this.latestObs.round_num} 回合` : `ROUND ${this.latestObs.round_num}`;
    const turnTxt = this.latestObs.finished
      ? (this.lang === "zh" ? "战斗结束" : "MATCH OVER")
      : this.latestObs.is_my_turn
        ? (this.lang === "zh" ? "轮到你方行动 — 选择一名英雄" : "YOUR TURN — SELECT A HERO")
        : (this.lang === "zh" ? "敌方行动中..." : "OPPONENT'S TURN...");

    ctx.save();
    ctx.textAlign = "center";
    ctx.font = `900 22px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
    ctx.lineWidth = 4;
    ctx.strokeStyle = "#000";
    ctx.strokeText(roundTxt, STAGE_W * 0.5, 38);
    ctx.fillStyle = "#f2cf7c";
    ctx.fillText(roundTxt, STAGE_W * 0.5, 38);

    ctx.font = `700 17px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
    ctx.strokeText(turnTxt, STAGE_W * 0.5, 62);
    ctx.fillStyle = this.latestObs.is_my_turn ? "#7ef59a" : "#ff857a";
    ctx.fillText(turnTxt, STAGE_W * 0.5, 62);
    ctx.restore();
  }

  _drawMoveBanner(ctx, mb) {
    const fadeIn = Math.min(1, mb.age / 0.14);
    const fadeOut = Math.min(1, (mb.life - mb.age) / 0.25);
    const alpha = Math.max(0, Math.min(fadeIn, fadeOut));
    ctx.save();
    ctx.globalAlpha = alpha;
    const frame = this._imgReady("movename_frame");
    const cx = STAGE_W * 0.5;
    const cy = 152;
    if (frame) {
      const fw = 420, fh = 66;
      ctx.drawImage(frame, cx - fw * 0.5, cy - fh * 0.5, fw, fh);
    }
    ctx.textAlign = "center";
    ctx.font = `900 26px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
    ctx.lineWidth = 5;
    ctx.strokeStyle = "#000";
    ctx.strokeText(mb.text, cx, cy + 8);
    ctx.fillStyle = "#f4d78b";
    ctx.fillText(mb.text, cx, cy + 8);
    ctx.restore();
  }

  _drawGuardLinks(ctx) {
    for (const vis of this.unitVisuals.values()) {
      const u = vis.unit;
      if (!u.alive || !u.guarding_uid) continue;
      const prot = this.unitVisuals.get(u.guarding_uid);
      if (!prot || !prot.unit.alive) continue;
      ctx.save();
      ctx.strokeStyle = vis.isEnemy ? "rgba(255, 95, 85, 0.55)" : "rgba(110, 200, 255, 0.65)";
      ctx.lineWidth = 3.5;
      ctx.setLineDash([8, 6]);
      ctx.beginPath();
      ctx.moveTo(vis.x, GROUND_Y + 20);
      ctx.quadraticCurveTo((vis.x + prot.x) * 0.5, GROUND_Y + 75, prot.x, GROUND_Y + 20);
      ctx.stroke();
      ctx.restore();
    }
  }

  _drawAoeConnectors(ctx) {
    if (!this.armedSkill || !this.armedSkill.skillDef?.is_aoe || !this.armedSkill.validTargetGroups?.length) return;
    const grp = this.armedSkill.validTargetGroups[0];
    if (!grp || grp.length < 2) return;
    const xs = grp
      .map((uid) => this.unitVisuals.get(uid)?.x)
      .filter((x) => x !== undefined)
      .sort((a, b) => a - b);
    const plusImg = this._imgReady(this.armedSkill.skillDef.target_team === "enemy" ? "plus" : "plus_h");
    if (!plusImg) return;
    for (let i = 0; i < xs.length - 1; i++) {
      const mx = (xs[i] + xs[i + 1]) * 0.5;
      ctx.drawImage(plusImg, mx - 20, GROUND_Y + 34, 40, 35);
    }
  }

  _drawUnit(ctx, vis, inCloseup) {
    const u = vis.unit;
    const drawX = vis.x + vis.lungeOffsetX;
    const drawY = vis.y;
    const isSelected = u.uid === this.selectedActorUid;
    const isHovered = u.uid === this.hoveredUid;

    // Authentic Darkest Dungeon Ground Selection / Target Overlay PNGs!
    if (!inCloseup) {
      if (!vis.isEnemy && u.alive && !u.is_corpse) {
        if (isSelected) {
          const selImg = this._imgReady(u.selectable ? "selected" : "selected_noaction");
          if (selImg) ctx.drawImage(selImg, drawX - 88, drawY - 122, 176, 206);
        } else if (u.selectable && this.latestObs?.is_my_turn) {
          const selImg = this._imgReady("selected");
          if (selImg) {
            ctx.save();
            ctx.globalAlpha = 0.35 + 0.25 * Math.sin(performance.now() * 0.005);
            ctx.drawImage(selImg, drawX - 84, drawY - 116, 168, 198);
            ctx.restore();
          }
        }
      }

      const targetHighlight = this._getTargetHighlight(u.uid);
      if (targetHighlight) {
        const key = targetHighlight === "enemy"
          ? (isHovered ? "target_h" : "target")
          : "target_h";
        const tgtImg = this._imgReady(key);
        if (tgtImg) {
          ctx.save();
          if (targetHighlight !== "enemy") ctx.filter = "hue-rotate(115deg) saturate(1.5)";
          ctx.globalAlpha = isHovered ? 1.0 : (0.65 + 0.25 * Math.sin(performance.now() * 0.008));
          ctx.drawImage(tgtImg, drawX - 88, drawY - 122, 176, 206);
          ctx.restore();
        }
      }
    }

    // Draw Spine 2.1 character skeleton!
    ctx.save();
    if (vis.deathDissolve >= 0) {
      // Authentic fx/anim_snippets/death.json silhouette dissolve (0..0.15 bright -> 0.15..0.55 black silhouette)
      const darkT = Math.max(0, Math.min(1, (vis.deathDissolve - 0.14) / 0.42));
      const bright = (1 - darkT).toFixed(2);
      ctx.filter = `brightness(${bright}) contrast(${(1 + darkT * 0.8).toFixed(2)})`;
    } else if (vis.hitFlash > 0) {
      ctx.filter = "brightness(1.85) sepia(0.55) hue-rotate(-30deg)";
    } else if (!inCloseup && u.acted_this_round && !u.is_corpse && !isSelected) {
      ctx.filter = "brightness(0.76)";
    }
    if (vis.spine) {
      vis.spine.draw(ctx, drawX, drawY, HERO_SCALE * vis.scaleMul, vis.isEnemy, vis.alpha);
    }
    ctx.restore();

    // Draw real persistent Spine fx/halo overhead (Stunned / Dazed / Death's Door)!
    if (vis.haloSpine && vis.haloAnim && u.alive && !u.is_corpse) {
      const head = this._resolveUnitAnchor(vis, "fxhead", -58);
      vis.haloSpine.draw(ctx, head.x, head.y, head.scale * 0.92, vis.isEnemy, 0.95);
    }

    // Always draw HP & stress bar tray (both normal formation and Close-Up so HP/stress changes & buffer are visible!)
    this._drawUnitTray(ctx, vis, drawX, drawY + (inCloseup ? 16 : 14), inCloseup);
  }

  _getTargetHighlight(uid) {
    if (!this.armedSkill || !this.armedSkill.validTargetGroups) return null;
    for (const grp of this.armedSkill.validTargetGroups) {
      if (grp.includes(uid)) {
        return this.armedSkill.skillDef.target_team === "enemy" ? "enemy" : "ally";
      }
    }
    return null;
  }

  /**
   * Authentic Darkest Dungeon status bar tray matching screen.raid.status_bars.darkest:
   *  - Real turnticker.png activation marker
   *  - Crimson HP bar with animated damage trail
   *  - 10 real stress_pip_empty / stress_pip_full / stress_pip_full_overstressed PNGs
   *  - Real 24x24 tray_*.png status icons row
   */
  _drawUnitTray(ctx, vis, x, y, inCloseup = false) {
    const u = vis.unit;
    if (!u.alive) return;
    const barW = inCloseup ? 120 : 104;
    const barH = inCloseup ? 12 : 10;
    const left = x - barW * 0.5;

    ctx.save();

    // 1. Real Turn Ticker (overlays/turnticker.png) to left of HP bar
    if (!u.is_corpse && !inCloseup) {
      const tickImg = this._imgReady("turnticker");
      if (tickImg) {
        ctx.save();
        if (u.acted_this_round) ctx.globalAlpha = 0.28;
        else if (u.dazed) ctx.filter = "sepia(1) hue-rotate(-10deg) saturate(3)";
        ctx.drawImage(tickImg, left - 25, y - 5, 21, 24);
        ctx.restore();
      }
    }

    // 2. Authentic HP Bar with dual-direction Damage Drain & Heal Fill Buffer (original DD style!)
    ctx.fillStyle = "#120606";
    ctx.fillRect(left - 2, y - 2, barW + 4, barH + 4);

    const maxHp = Math.max(1, u.max_hp);
    const liveHp = vis.liveHp !== undefined ? vis.liveHp : u.hp;
    const curRatio = Math.max(0, Math.min(1, liveHp / maxHp));
    const bufRatio = Math.max(0, Math.min(1, vis.displayHp / maxHp));
    const isDd = u.at_deaths_door || (liveHp <= 0 && !u.is_corpse);

    if (bufRatio > curRatio + 0.004) {
      // Taking damage: crimson drops immediately, bright orange-gold damage buffer holds then drains!
      ctx.fillStyle = vis.hpBufferHold > 0 ? "#ffe08a" : "#f2a93b";
      ctx.fillRect(left, y, barW * bufRatio, barH);
      ctx.fillStyle = u.is_corpse ? "#6b6157" : isDd ? "#ff1c1c" : "#b81814";
      ctx.fillRect(left, y, barW * curRatio, barH);
    } else if (curRatio > bufRatio + 0.004) {
      // Healing: emerald heal buffer previews target HP while crimson fills smoothly up!
      ctx.fillStyle = "#5cf282";
      ctx.fillRect(left, y, barW * curRatio, barH);
      ctx.fillStyle = u.is_corpse ? "#6b6157" : "#b81814";
      ctx.fillRect(left, y, barW * bufRatio, barH);
    } else {
      ctx.fillStyle = u.is_corpse ? "#6b6157" : isDd ? "#ff1c1c" : "#b81814";
      ctx.fillRect(left, y, barW * curRatio, barH);
    }

    // Subtle dark-iron frame
    ctx.strokeStyle = isDd ? "#ff4a4a" : "#5e4632";
    ctx.lineWidth = 1.5;
    ctx.strokeRect(left - 1.5, y - 1.5, barW + 3, barH + 3);

    // 3. Authentic 10-Pip Stress Bar using REAL stress_pip_*.png textures & liveStress!
    if (!u.is_corpse) {
      const sy = y + barH + 3;
      const emptyPip = this._imgReady("stress_empty");
      const fullPip = this._imgReady("stress_full");
      const overPip = this._imgReady("stress_over");
      const liveStress = vis.liveStress !== undefined ? vis.liveStress : u.stress;
      const filled1 = Math.floor(Math.min(100, liveStress) / 10);
      const filled2 = Math.floor(Math.max(0, liveStress - 100) / 10);
      const pipStep = barW / 10;
      for (let i = 0; i < 10; i++) {
        const px = left + i * pipStep;
        const img = i < filled2 ? overPip : (i < filled1 ? fullPip : emptyPip);
        if (img) {
          ctx.drawImage(img, px, sy, pipStep - 0.8, inCloseup ? 12 : 11);
        }
      }
    }

    if (inCloseup) {
      ctx.restore();
      return;
    }

    // 4. Authentic 24x24 Status Tray Icons (overlays/tray_*.png) — including Buff & Debuff icons with hover hitboxes!
    const icons = [];
    if (u.at_deaths_door) icons.push(["tray_deathsdoor", "", "deaths_door"]);
    if (u.stunned) icons.push(["tray_stun", "", "stun"]);
    if (u.dazed) icons.push(["tray_daze", "", "daze"]);
    if (u.marked_rounds > 0) icons.push(["tray_tag", String(u.marked_rounds), "mark"]);
    if (u.stealth_rounds > 0) icons.push(["tray_stealth", String(u.stealth_rounds), "stealth"]);
    if (u.riposte_rounds > 0) icons.push(["tray_riposte", String(u.riposte_rounds), "riposte"]);
    if (u.guarded_by_uid || u.guarding_uid) icons.push(["tray_guard", u.guard_rounds > 0 ? String(u.guard_rounds) : "", "guard"]);
    if (u.aegis_blocks > 0) icons.push(["tray_block", String(u.aegis_blocks), "block"]);

    const bleedDots = (u.dots || []).filter((d) => d.kind === "bleed");
    const blightDots = (u.dots || []).filter((d) => d.kind === "blight");
    const horrorDots = (u.dots || []).filter((d) => d.kind === "horror");
    const regenDots = (u.dots || []).filter((d) => d.kind === "regen");
    const bleedAmt = bleedDots.reduce((s, d) => s + d.amount, 0);
    const blightAmt = blightDots.reduce((s, d) => s + d.amount, 0);
    const horrorAmt = horrorDots.reduce((s, d) => s + d.amount, 0);
    const regenAmt = regenDots.reduce((s, d) => s + d.amount, 0);
    if (bleedAmt > 0) icons.push(["tray_bleed", String(bleedAmt), "bleed"]);
    if (blightAmt > 0) icons.push(["tray_poison", String(blightAmt), "blight"]);
    if (horrorAmt > 0) icons.push(["tray_horror", String(horrorAmt), "horror"]);
    if (regenAmt > 0) icons.push(["tray_regen", String(regenAmt), "regen"]);

    const posBuffs = (u.buffs || []).filter((b) => !b.is_debuff && b.stat !== "daze");
    const negBuffs = (u.buffs || []).filter((b) => b.is_debuff);
    if (posBuffs.length > 0) icons.push(["tray_buff", posBuffs.length > 1 ? String(posBuffs.length) : "", "buff"]);
    if (negBuffs.length > 0) icons.push(["tray_debuff", negBuffs.length > 1 ? String(negBuffs.length) : "", "debuff"]);

    if (u.is_virtuous) icons.push(["tray_virtued", "", "virtued"]);
    if (u.is_afflicted) icons.push(["tray_afflicted", "", "afflicted"]);

    if (icons.length) {
      const iy = y + (u.is_corpse ? 16 : 27);
      const step = 23;
      let ix = x - (icons.length * step) * 0.5;
      ctx.font = `800 11px "Segoe UI", sans-serif`;
      ctx.textAlign = "right";
      for (const [key, badge, kind] of icons) {
        const im = this._imgReady(key);
        const isHov = this.hoveredIcon && this.hoveredIcon.uid === u.uid && this.hoveredIcon.kind === kind;
        if (isHov) {
          ctx.fillStyle = "rgba(232, 195, 116, 0.28)";
          ctx.fillRect(ix - 1, iy - 1, 24, 24);
          ctx.strokeStyle = "#f4d78b";
          ctx.lineWidth = 1.5;
          ctx.strokeRect(ix - 1, iy - 1, 24, 24);
        }
        if (im) ctx.drawImage(im, ix, iy, 22, 22);
        if (badge) {
          ctx.lineWidth = 3;
          ctx.strokeStyle = "#000";
          ctx.strokeText(badge, ix + 22, iy + 22);
          ctx.fillStyle = "#fff";
          ctx.fillText(badge, ix + 22, iy + 22);
        }
        this.iconHitboxes.push({
          uid: u.uid,
          unit: u,
          kind,
          key,
          x: ix,
          y: iy,
          w: 22,
          h: 22,
          posBuffs,
          negBuffs,
          bleedDots,
          blightDots,
          horrorDots,
          regenDots,
        });
        ix += step;
      }
    }

    ctx.restore();
  }

  _drawBanner(ctx, b) {
    const fadeIn = Math.min(1, b.age / 0.2);
    const fadeOut = Math.min(1, (b.life - b.age) / 0.35);
    const alpha = Math.max(0, Math.min(fadeIn, fadeOut));
    ctx.save();
    ctx.globalAlpha = alpha;
    const by = 235;
    const grad = ctx.createLinearGradient(0, by - 55, 0, by + 55);
    grad.addColorStop(0, "rgba(0,0,0,0)");
    grad.addColorStop(0.25, "rgba(10, 4, 4, 0.90)");
    grad.addColorStop(0.75, "rgba(10, 4, 4, 0.90)");
    grad.addColorStop(1, "rgba(0,0,0,0)");
    ctx.fillStyle = grad;
    ctx.fillRect(0, by - 55, STAGE_W, 110);

    ctx.textAlign = "center";
    ctx.font = `900 44px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
    ctx.lineWidth = 6;
    ctx.strokeStyle = "#000";
    ctx.strokeText(b.title, STAGE_W * 0.5, by + 6);
    ctx.fillStyle = b.color;
    ctx.fillText(b.title, STAGE_W * 0.5, by + 6);

    if (b.subtitle) {
      ctx.font = `600 22px "Georgia", "Microsoft YaHei", serif`;
      ctx.fillStyle = "#e8dcc4";
      ctx.fillText(b.subtitle, STAGE_W * 0.5, by + 38);
    }
    ctx.restore();
  }
}
