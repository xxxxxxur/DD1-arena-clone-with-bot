/**
 * Butcher's Circus Web Client Controller.
 * ZERO game logic in JS — all rules, legality, hit/crit/deathblow/stress
 * calculations execute authoritatively in the pure Java 26 backend engine.
 */
import { CircusAudio } from "./audio.js?v=5";
import { ArenaRenderer } from "./renderer.js?v=13";
import { CircusTownScene } from "./town_scene.js?v=5";

const appState = {
  lang: "zh",
  bootstrap: null,
  audio: new CircusAudio(),
  renderer: null,
  townScene: null,

  // Active room / connection state
  ws: null,
  roomId: null,
  seat: 0,
  roomInfo: null,
  obs: null,
  isReplay: false,
  replayData: null,
  replayRunning: false,

  // Team builder state (index 0 = Rank 1 Front .. index 3 = Rank 4 Back)
  draftTeam: null,
  editingSlot: 0,

  // Combat UI selection state
  selectedActorUid: null,
  armedSkillId: null,
  inspectedEnemyUid: null,
  busyAnimating: false,
};

const $ = (id) => document.getElementById(id);

function heroName(hid) {
  const h = appState.bootstrap?.heroes?.[hid];
  if (!h) return hid;
  return appState.lang === "zh" ? h.name_zh : h.name_en;
}
function skillDef(hid, sid) {
  return appState.bootstrap?.heroes?.[hid]?.skills?.find((s) => s.id === sid) || null;
}
function skillName(sdef) {
  return !sdef ? "" : appState.lang === "zh" ? sdef.name_zh : sdef.name_en;
}
function skillDesc(sdef) {
  return !sdef ? "" : appState.lang === "zh" ? sdef.description_zh : sdef.description_en;
}
function trinketDef(tid) {
  return appState.bootstrap?.trinkets?.find((t) => t.id === tid) || null;
}
function trinketName(tdef) {
  return !tdef ? "" : appState.lang === "zh" ? tdef.name_zh : tdef.name_en;
}
function trinketDesc(tdef) {
  return !tdef ? "" : appState.lang === "zh" ? tdef.description_zh : tdef.description_en;
}

function formatPips(ranks, cls) {
  let out = "";
  for (let r = 4; r >= 1; r--) {
    out += ranks.includes(r) ? `<span class="${cls}">●</span>` : `<span style="opacity:0.25">○</span>`;
  }
  return out;
}

function showView(viewId) {
  for (const el of document.querySelectorAll(".view")) el.classList.remove("active");
  $(viewId).classList.add("active");
  $("btn-lobby").classList.toggle("hidden", viewId === "view-town");
  if (appState.townScene) appState.townScene.setActive(viewId === "view-town");
  if (viewId === "view-combat") fitCombatHud();
  if (viewId === "view-town") appState.audio.stopArenaMusic();
}

function openBuildingModal(buildingId) {
  appState.audio.playUiClick();
  for (const m of document.querySelectorAll(".building-modal")) m.classList.add("hidden");
  const target = $(`modal-${buildingId}`);
  if (target) target.classList.remove("hidden");
  if (buildingId === "rankings") refreshReplays();
}

function closeAllBuildingModals() {
  for (const m of document.querySelectorAll(".building-modal")) m.classList.add("hidden");
}

function fitCombatHud() {
  const outer = $("combat-hud-outer");
  const hud = $("combat-hud-1920");
  if (!outer || !hud) return;
  const availW = Math.max(320, window.innerWidth);
  const availH = Math.max(190, Math.min(360, window.innerHeight * 0.34));
  const scale = Math.min(availW / 1920, availH / 360);
  outer.style.height = `${Math.round(360 * scale)}px`;
  hud.style.transform = `scale(${scale.toFixed(4)})`;
}

// ------------------------------------------------------------------ Bootstrap
async function init() {
  const res = await fetch("/api/bootstrap");
  appState.bootstrap = await res.json();
  appState.audio.setManifest(appState.bootstrap.audio_manifest);

  appState.renderer = new ArenaRenderer($("arena-canvas"), appState.audio);
  appState.renderer.setLanguage(appState.lang);
  updateCloseupButtonLabel();

  appState.townScene = new CircusTownScene($("town-canvas"), (bid) => openBuildingModal(bid));
  appState.townScene.setLanguage(appState.lang);

  const unlockOnce = () => {
    appState.audio.unlock();
    window.removeEventListener("pointerdown", unlockOnce);
  };
  window.addEventListener("pointerdown", unlockOnce);
  window.addEventListener("resize", () => fitCombatHud());

  populatePresetSelects();
  populateTeamBotSelects();
  populateTournamentTeamPicker();
  bindEvents();
  refreshReplays();
  fitCombatHud();

  // Direct join link for local 2-player dual-window PvP (/?room=CIRCUS-1234&seat=1)
  const params = new URLSearchParams(window.location.search);
  const joinRoom = params.get("room");
  const seatParam = params.get("seat") || "auto";
  if (joinRoom) {
    connectRoom(joinRoom.toUpperCase(), seatParam);
  }
}

function updateCloseupButtonLabel() {
  const on = appState.renderer?.closeupEnabled ?? true;
  const btn = $("btn-closeup");
  btn.classList.toggle("active", on);
  btn.textContent =
    appState.lang === "zh"
      ? (on ? "🎬 特写镜头: 开" : "🎬 特写镜头: 关")
      : (on ? "🎬 Close-Up: ON" : "🎬 Close-Up: OFF");
  const hudBtn = $("btn-hud-closeup");
  const badge = $("hud-closeup-badge");
  if (hudBtn) hudBtn.classList.toggle("active", on);
  if (badge) badge.textContent = on ? "ON" : "OFF";
}

const CLASSIC_TOURNAMENT_TEAMS = [
  "direct_kill",
  "stun_kill",
  "iron_wall",
  "iron_hound",
  "shuffle_kill",
];

function getTournamentBotOptions() {
  const fromServer = appState.bootstrap?.bot_classes;
  if (Array.isArray(fromServer) && fromServer.length) return fromServer;
  return [
    { id: "unit_neural_search", label: "UnitNeuralSearchBot (单位级神经搜索)" },
    { id: "neural_search", label: "NeuralSearchBot (轻量神经搜索)" },
    { id: "search", label: "CircusSearchBot (时间展开搜索)" },
    { id: "fortress", label: "FortressBot (铁壁防守搜索)" },
    { id: "tactical", label: "TacticalBot (战术启发)" },
    { id: "stress", label: "StressSpecialistBot (压力专精)" },
    { id: "random", label: "RandomBot (随机)" },
  ];
}

function getSelectedTournamentTeams() {
  const container = $("lab-team-list");
  if (!container) return [];
  return Array.from(container.querySelectorAll('input[type="checkbox"]:checked')).map((cb) => cb.value);
}

function getSelectedTournamentSpecs() {
  const container = $("lab-team-list");
  if (!container) return [];
  const specs = [];
  for (const row of container.querySelectorAll(".lab-team-chip")) {
    const cb = row.querySelector('input[type="checkbox"]');
    if (!cb?.checked) continue;
    const sel = row.querySelector(".lab-team-bot");
    const spec = { type: cb.value };
    if (sel?.value) spec.bot = sel.value;
    specs.push(spec);
  }
  return specs;
}

function updateTournamentSelectionSummary() {
  const summaryEl = $("lab-team-summary");
  const container = $("lab-team-list");
  if (!summaryEl || !container) return;
  const all = container.querySelectorAll('input[type="checkbox"]').length;
  const selected = getSelectedTournamentTeams().length;
  const mRaw = parseInt($("lab-matches")?.value, 10);
  const m = Number.isFinite(mRaw) ? Math.max(1, Math.min(1000, mRaw)) : 30;
  const pairs = selected === 1 ? 1 : (selected >= 2 ? (selected * (selected - 1)) / 2 : 0);
  const totalMatches = pairs * m;
  if (selected === 1) {
    summaryEl.textContent =
      appState.lang === "zh"
        ? `已选 1/${all} 队 · 镜像对阵 · 共 ${totalMatches} 局`
        : `1/${all} selected · Mirror Match · ${totalMatches} matches`;
  } else {
    summaryEl.textContent =
      appState.lang === "zh"
        ? `已选 ${selected}/${all} 队 · ${pairs} 组对阵 · 共 ${totalMatches} 局`
        : `${selected}/${all} selected · ${pairs} pairs · ${totalMatches} matches`;
  }
}

function populateTournamentTeamPicker(preserveChecked = false) {
  const container = $("lab-team-list");
  if (!container) return;

  const workersInput = $("lab-workers");
  if (workersInput && !workersInput.dataset.initialized) {
    const cores = appState.bootstrap?.cpu_cores || navigator.hardwareConcurrency || 4;
    workersInput.value = String(Math.max(1, cores));
    workersInput.dataset.initialized = "1";
  }

  const prevChecked = preserveChecked ? new Set(getSelectedTournamentTeams()) : null;
  const prevBots = new Map();
  if (preserveChecked) {
    for (const row of container.querySelectorAll(".lab-team-chip")) {
      const cb = row.querySelector('input[type="checkbox"]');
      const sel = row.querySelector(".lab-team-bot");
      if (cb?.value && sel) prevBots.set(cb.value, sel.value);
    }
  }

  const botOpts = getTournamentBotOptions();
  const bulkSel = $("lab-bulk-bot");
  if (bulkSel) {
    const prevBulk = bulkSel.value;
    bulkSel.innerHTML = "";
    const defOpt = document.createElement("option");
    defOpt.value = "";
    defOpt.textContent = appState.lang === "zh" ? "恢复队伍默认 Bot" : "Team Default Bot";
    bulkSel.appendChild(defOpt);
    for (const b of botOpts) {
      const opt = document.createElement("option");
      opt.value = b.id;
      opt.textContent = b.label || b.id;
      bulkSel.appendChild(opt);
    }
    if (prevBulk) bulkSel.value = prevBulk;
  }

  const teams = appState.bootstrap?.teams || [];
  container.innerHTML = "";
  for (const t of teams) {
    const isChecked = prevChecked ? prevChecked.has(t.id) : true;
    const row = document.createElement("div");
    row.className = "lab-team-chip" + (isChecked ? " checked" : "");
    row.title = `${t.id} (${t.bot_class || ""})`;

    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.value = t.id;
    cb.checked = isChecked;
    cb.addEventListener("change", () => {
      row.classList.toggle("checked", cb.checked);
      updateTournamentSelectionSummary();
    });

    const nameSpan = document.createElement("span");
    nameSpan.className = "lab-team-name";
    nameSpan.style.cursor = "pointer";
    nameSpan.textContent = appState.lang === "zh" ? (t.name_zh || t.name || t.id) : (t.name_en || t.name || t.id);
    nameSpan.addEventListener("click", () => {
      cb.checked = !cb.checked;
      row.classList.toggle("checked", cb.checked);
      updateTournamentSelectionSummary();
    });

    const sel = document.createElement("select");
    sel.className = "lab-team-bot";
    const shortDefault = (t.bot_class || "CircusSearchBot").split(".").pop() || "Bot";
    const defOpt = document.createElement("option");
    defOpt.value = "";
    defOpt.textContent = appState.lang === "zh" ? `队伍默认 (${shortDefault})` : `Default (${shortDefault})`;
    sel.appendChild(defOpt);
    for (const b of botOpts) {
      const opt = document.createElement("option");
      opt.value = b.id;
      opt.textContent = b.label || b.id;
      sel.appendChild(opt);
    }
    if (prevBots.has(t.id)) sel.value = prevBots.get(t.id);

    row.appendChild(cb);
    row.appendChild(nameSpan);
    row.appendChild(sel);
    container.appendChild(row);
  }
  updateTournamentSelectionSummary();
}

function populateTeamBotSelects() {
  const teams = appState.bootstrap.teams || [];
  if (!teams.length) return;
  for (const selId of ["vsbot-ai", "bvb-bot0", "bvb-bot1"]) {
    const sel = $(selId);
    if (!sel) continue;
    const prev = sel.value;
    const existing = new Set(Array.from(sel.options).map((o) => o.value));
    for (const t of teams) {
      if (!existing.has(t.id)) {
        const opt = document.createElement("option");
        opt.value = t.id;
        const shortCls = (t.bot_class || "").split(".").pop();
        opt.textContent = `${t.name} [${shortCls}]`;
        sel.appendChild(opt);
      }
    }
    if (prev) sel.value = prev;
  }
}

function populatePresetSelects() {
  const presets = appState.bootstrap.presets || [];
  for (const selId of ["vsbot-preset", "bvb-preset0", "bvb-preset1"]) {
    const sel = $(selId);
    if (!sel) continue;
    sel.innerHTML = "";
    if (selId === "vsbot-preset") {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = appState.lang === "zh" ? "随机马戏团阵容" : "Random Circus Comp";
      sel.appendChild(opt);
    }
    for (const p of presets) {
      const opt = document.createElement("option");
      opt.value = p.id;
      opt.textContent = appState.lang === "zh" ? p.name_zh : p.name_en;
      sel.appendChild(opt);
    }
  }
  if (presets.length > 0 && $("vsbot-preset")) $("vsbot-preset").value = presets[0].id;
  if (presets.length > 0 && $("bvb-preset0")) $("bvb-preset0").value = presets[0].id;
  if (presets.length > 1 && $("bvb-preset1")) $("bvb-preset1").value = presets[1].id;
}

const META_BOT_PRESET_MAP = {
  direct_kill: "meta_direct_kill",
  stun_kill: "meta_stun_kill",
  iron_wall: "meta_iron_wall",
  iron_hound: "meta_iron_hound",
  shuffle_kill: "meta_shuffle_kill",
};

function bindEvents() {
  for (const [botSelId, presetSelId] of [
    ["vsbot-ai", "vsbot-preset"],
    ["bvb-bot0", "bvb-preset0"],
    ["bvb-bot1", "bvb-preset1"],
  ]) {
    const bSel = $(botSelId);
    const pSel = $(presetSelId);
    if (bSel && pSel) {
      bSel.addEventListener("change", () => {
        const mapped = META_BOT_PRESET_MAP[bSel.value];
        if (mapped && [...pSel.options].some((o) => o.value === mapped)) {
          pSel.value = mapped;
        }
      });
    }
  }
  // Top bar controls
  $("btn-closeup").addEventListener("click", () => {
    appState.renderer.setCloseupEnabled(!appState.renderer.closeupEnabled);
    updateCloseupButtonLabel();
    appState.audio.playUiClick();
  });

  $("arena-tier-select").addEventListener("change", (e) => {
    appState.renderer.loadArenaBackground(e.target.value);
    if (appState.ws && appState.ws.readyState === 1 && appState.draftTeam) {
      appState.ws.send(JSON.stringify({ type: "SET_TEAM", team: appState.draftTeam, arena_tier: e.target.value }));
    }
  });

  $("btn-lang").addEventListener("click", () => {
    appState.lang = appState.lang === "zh" ? "en" : "zh";
    appState.renderer.setLanguage(appState.lang);
    appState.townScene.setLanguage(appState.lang);
    updateCloseupButtonLabel();
    populatePresetSelects();
    populateTournamentTeamPicker(true);
    if (appState.draftTeam) renderTeamBuilder();
    if (appState.obs) renderCombatHud();
    appState.audio.playUiClick();
  });

  $("btn-mute").addEventListener("click", () => {
    appState.audio.setMuted(!appState.audio.muted);
    $("btn-mute").textContent = appState.audio.muted ? "🔇 Muted" : "🔊 Audio";
  });

  $("btn-lobby").addEventListener("click", () => {
    leaveRoom();
    closeAllBuildingModals();
    showView("view-town");
  });

  // Town bottom dock buttons
  for (const btn of document.querySelectorAll(".dock-btn")) {
    btn.addEventListener("click", () => openBuildingModal(btn.dataset.building));
  }
  for (const btn of document.querySelectorAll(".dialog-close")) {
    btn.addEventListener("click", () => {
      $(btn.dataset.closeModal).classList.add("hidden");
      appState.audio.playUiClick();
    });
  }
  for (const modal of document.querySelectorAll(".building-modal")) {
    modal.addEventListener("click", (e) => {
      if (e.target === modal) modal.classList.add("hidden");
    });
  }

  // Modal actions: Play vs Bot
  $("btn-start-vsbot").addEventListener("click", async () => {
    appState.audio.playLockIn();
    closeAllBuildingModals();
    const res = await fetch("/api/rooms", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mode: "vs_bot",
        arena_tier: $("arena-tier-select").value,
        bot1_type: $("vsbot-ai").value,
        bot1_preset: $("vsbot-preset").value || null,
      }),
    });
    const data = await res.json();
    connectRoom(data.room_id, "0");
  });

  // Modal actions: Local 2P PvP
  $("btn-create-pvp").addEventListener("click", async () => {
    appState.audio.playLockIn();
    closeAllBuildingModals();
    const res = await fetch("/api/rooms", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mode: "pvp",
        arena_tier: $("arena-tier-select").value,
      }),
    });
    const data = await res.json();
    connectRoom(data.room_id, "0");
  });

  $("btn-join-pvp").addEventListener("click", () => {
    const code = $("join-room-input").value.trim().toUpperCase();
    if (!code) return;
    appState.audio.playUiClick();
    closeAllBuildingModals();
    connectRoom(code, "auto");
  });

  // Modal actions: Live Bot vs Bot Spectate
  $("btn-start-bvb").addEventListener("click", async () => {
    appState.audio.playLockIn();
    closeAllBuildingModals();
    const res = await fetch("/api/rooms", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mode: "bot_vs_bot",
        arena_tier: $("arena-tier-select").value,
        bot0_type: $("bvb-bot0").value,
        bot1_type: $("bvb-bot1").value,
        bot0_preset: $("bvb-preset0").value || null,
        bot1_preset: $("bvb-preset1").value || null,
      }),
    });
    const data = await res.json();
    connectRoom(data.room_id, "spectator");
  });

  // Modal actions: Headless Tournament Lab
  let lastReportUrl = "";
  $("btn-open-report")?.addEventListener("click", () => {
    if (lastReportUrl) window.open(lastReportUrl, "_blank");
  });

  const setLabSelection = (predicate) => {
    const container = $("lab-team-list");
    if (!container) return;
    for (const cb of container.querySelectorAll('input[type="checkbox"]')) {
      cb.checked = predicate(cb.value);
      cb.closest(".lab-team-chip")?.classList.toggle("checked", cb.checked);
    }
    updateTournamentSelectionSummary();
  };
  $("btn-lab-select-all")?.addEventListener("click", () => setLabSelection(() => true));
  $("btn-lab-select-classic")?.addEventListener("click", () =>
    setLabSelection((id) => CLASSIC_TOURNAMENT_TEAMS.includes(id))
  );
  $("btn-lab-clear")?.addEventListener("click", () => setLabSelection(() => false));
  $("btn-lab-apply-bulk")?.addEventListener("click", () => {
    const bulkVal = $("lab-bulk-bot")?.value ?? "";
    const container = $("lab-team-list");
    if (!container) return;
    for (const row of container.querySelectorAll(".lab-team-chip")) {
      const cb = row.querySelector('input[type="checkbox"]');
      const sel = row.querySelector(".lab-team-bot");
      if (cb?.checked && sel) sel.value = bulkVal;
    }
  });
  $("lab-matches")?.addEventListener("input", () => updateTournamentSelectionSummary());

  $("btn-run-lab").addEventListener("click", async () => {
    const btn = $("btn-run-lab");
    const openBtn = $("btn-open-report");
    const out = $("lab-output");
    const progWrap = $("lab-progress-wrap");
    const progFill = $("lab-progress-fill");
    const progLabel = $("lab-progress-label");
    const progStats = $("lab-progress-stats");
    const selectedSpecs = getSelectedTournamentSpecs();
    if (selectedSpecs.length < 1) {
      out.textContent = appState.lang === "zh" ? "请至少选择 1 支参赛队伍（选 1 支将自动进行镜像对打）！" : "Please select at least 1 team (1 team runs a mirror tournament)!";
      return;
    }
    const isMirror = selectedSpecs.length === 1;
    btn.disabled = true;
    openBtn?.classList.add("hidden");
    progWrap?.classList.remove("hidden");
    if (progFill) progFill.style.width = "0%";
    if (progLabel) {
      progLabel.textContent = appState.lang === "zh"
        ? (isMirror ? "⚔ 正在进行镜像锦标赛..." : `⚔ 正在进行锦标赛（${selectedSpecs.length} 支队伍）...`)
        : (isMirror ? "⚔ Running Mirror Tournament..." : `⚔ Running Tournament (${selectedSpecs.length} teams)...`);
    }
    if (progStats) progStats.textContent = "0 / ? (0.0%)";
    out.textContent =
      appState.lang === "zh"
        ? (isMirror
            ? "正在 Java 26 多线程无头引擎中运行单队镜像锦标赛..."
            : `正在 Java 26 多线程无头引擎中运行高速锦标赛（${selectedSpecs.length} 支队伍）...`)
        : (isMirror
            ? "Running Java 26 multi-threaded mirror tournament..."
            : `Running Java 26 multi-threaded headless tournament (${selectedSpecs.length} teams)...`);
    try {
      const mRaw = parseInt($("lab-matches")?.value, 10);
      const matches = Number.isFinite(mRaw) ? Math.max(1, Math.min(1000, mRaw)) : 30;
      const wRaw = parseInt($("lab-workers")?.value, 10);
      const defaultWorkers = appState.bootstrap?.cpu_cores || navigator.hardwareConcurrency || 4;
      const workers = Number.isFinite(wRaw) ? Math.max(1, wRaw) : defaultWorkers;
      const sRaw = parseInt($("lab-seed")?.value, 10);
      const seed = Number.isFinite(sRaw) ? sRaw : 42;
      const mrRaw = parseInt($("lab-max-rounds")?.value, 10);
      const maxRounds = Number.isFinite(mrRaw) ? Math.max(1, Math.min(200, mrRaw)) : 40;
      const rpRaw = parseInt($("lab-replays")?.value, 10);
      const replays = Number.isFinite(rpRaw) ? Math.max(0, Math.min(100, rpRaw)) : 2;
      const budgetStr = ($("lab-budget")?.value ?? "").trim();
      const budgetRaw = budgetStr === "" ? null : parseFloat(budgetStr);
      const timeBudget = (budgetRaw !== null && Number.isFinite(budgetRaw) && budgetRaw > 0)
        ? Math.min(10, Math.max(0.005, budgetRaw))
        : null;
      const autoOpen = Boolean($("lab-open-report")?.checked);

      const payload = {
        bots: selectedSpecs,
        matches,
        workers,
        seed,
        max_rounds: maxRounds,
        replays,
      };
      if (timeBudget !== null) payload.time_budget = timeBudget;

      const startRes = await fetch("/api/tournament/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const startData = await startRes.json();
      if (!startRes.ok || startData.error || !startData.job_id) {
        throw new Error(startData.error || `HTTP ${startRes.status}`);
      }

      const jobId = startData.job_id;
      let statusData = startData;
      while (statusData.status === "running") {
        const pct = Number(statusData.pct || 0);
        if (progFill) progFill.style.width = `${Math.min(100, Math.max(0, pct))}%`;
        if (progStats) {
          const etaText = statusData.eta_sec !== null && statusData.eta_sec !== undefined
            ? ` | ETA ${Number(statusData.eta_sec).toFixed(1)}s`
            : "";
          progStats.textContent = `${statusData.completed}/${statusData.total} (${pct.toFixed(1)}%) · ${Number(statusData.elapsed_sec || 0).toFixed(1)}s${etaText}`;
        }
        await new Promise((resolve) => setTimeout(resolve, 250));
        const stRes = await fetch(`/api/tournament/status/${encodeURIComponent(jobId)}`);
        statusData = await stRes.json();
        if (!stRes.ok) {
          throw new Error(statusData.error || `HTTP ${stRes.status}`);
        }
      }

      if (statusData.status === "error") {
        throw new Error(statusData.error || "Tournament failed");
      }

      const r = statusData.result;
      if (progFill) progFill.style.width = "100%";
      if (progLabel) {
        progLabel.textContent = appState.lang === "zh" ? "✅ 锦标赛已完成" : "✅ Tournament Complete";
      }
      if (progStats) {
        progStats.textContent = `${r.total_matches}/${r.total_matches} (100.0%) · ${r.elapsed_sec}s`;
      }

      let txt = `Matches: ${r.total_matches} in ${r.elapsed_sec}s (${r.matches_per_sec} matches/sec)\n`;
      txt += `Avg Rounds: ${r.avg_rounds} | Deathblows: ${r.deathblow_kills} | Heart Attacks: ${r.heart_attack_kills}\n`;
      if (r.report_file) {
        txt += `Report File: tournaments/${r.report_file}\n`;
      }
      txt += `-------------------------------------------------------------------------\n`;
      for (const [name, st] of Object.entries(r.winrates)) {
        const ps = st.power_score !== undefined ? Number(st.power_score).toFixed(1) : "—";
        const tier = st.power_tier || "-";
        txt += `${name.padEnd(26)} 实力分:${String(ps).padStart(5)} [${tier.padEnd(2)}]  ELO:${String(st.elo).padStart(6)}  W:${String(st.wins).padStart(2)} L:${String(st.losses).padStart(2)} (${st.winrate_pct}%)\n`;
      }
      out.textContent = txt;
      if (r.report_url) {
        lastReportUrl = r.report_url;
        openBtn?.classList.remove("hidden");
        if (autoOpen) window.open(lastReportUrl, "_blank");
      }
      refreshReplays();
    } catch (e) {
      if (progLabel) progLabel.textContent = appState.lang === "zh" ? "❌ 锦标赛失败" : "❌ Tournament Error";
      out.textContent = "Error: " + e.message;
    } finally {
      btn.disabled = false;
    }
  });

  $("btn-refresh-replays").addEventListener("click", refreshReplays);

  // Team builder controls
  $("btn-open-p2").addEventListener("click", () => {
    if (!appState.roomId) return;
    const url = `${window.location.origin}/?room=${encodeURIComponent(appState.roomId)}&seat=1`;
    window.open(url, "_blank");
  });

  $("btn-copy-p2").addEventListener("click", async () => {
    if (!appState.roomId) return;
    const url = `${window.location.origin}/?room=${encodeURIComponent(appState.roomId)}&seat=1`;
    try {
      await navigator.clipboard.writeText(url);
      $("btn-copy-p2").textContent = "✅ 已复制 / Copied!";
      setTimeout(() => { $("btn-copy-p2").textContent = "📋 复制玩家 2 链接"; }, 1800);
    } catch {
      prompt("Player 2 Link:", url);
    }
  });

  for (const btn of document.querySelectorAll(".skin-btn")) {
    btn.addEventListener("click", () => {
      if (!appState.draftTeam) return;
      appState.draftTeam.heroes[appState.editingSlot].variant = btn.dataset.variant;
      syncDraftToServer();
      renderTeamBuilder();
      appState.audio.playUiClick();
    });
  }

  $("trinket-search").addEventListener("input", () => renderTrinketPicker());

  $("btn-lock-in").addEventListener("click", () => {
    if (!appState.ws || appState.ws.readyState !== 1) return;
    appState.audio.playLockIn();
    appState.audio.startArenaMusic();
    appState.ws.send(JSON.stringify({ type: "SET_READY", ready: true, team: appState.draftTeam }));
  });

  // Combat HUD controls
  $("btn-hud-closeup").addEventListener("click", () => {
    appState.renderer.setCloseupEnabled(!appState.renderer.closeupEnabled);
    updateCloseupButtonLabel();
    appState.audio.playUiClick();
  });
  $("btn-move").addEventListener("click", () => toggleMoveDrawer());
  $("btn-pass").addEventListener("click", () => {
    if (!appState.selectedActorUid) return;
    sendAction({ kind: "PASS", actor_uid: appState.selectedActorUid });
  });
  $("btn-surrender").addEventListener("click", () => {
    const ok = confirm(appState.lang === "zh" ? "确定要向角斗场投降吗？" : "Surrender this Circus match?");
    if (ok) sendAction({ kind: "SURRENDER" });
  });
  $("btn-toggle-log").addEventListener("click", () => {
    $("combat-log-drawer").classList.toggle("collapsed");
    appState.audio.playUiClick();
  });
  $("btn-close-log").addEventListener("click", () => {
    $("combat-log-drawer").classList.add("collapsed");
    appState.audio.playUiClick();
  });

  // Spectate controls
  $("btn-pause").addEventListener("click", () => {
    if (appState.isReplay) {
      appState.replayRunning = !appState.replayRunning;
      $("btn-pause").textContent = appState.replayRunning ? "⏸ Pause" : "▶ Resume";
    } else if (appState.ws && appState.ws.readyState === 1) {
      appState.ws.send(JSON.stringify({ type: "TOGGLE_PAUSE" }));
    }
  });
  for (const btn of document.querySelectorAll(".speed-btn")) {
    btn.addEventListener("click", () => {
      for (const b of document.querySelectorAll(".speed-btn")) b.classList.remove("active");
      btn.classList.add("active");
      const spd = parseFloat(btn.dataset.speed);
      appState.renderer.setPlaybackSpeed(spd);
      if (appState.ws && appState.ws.readyState === 1) {
        appState.ws.send(JSON.stringify({ type: "SET_SPEED", speed: spd }));
      }
    });
  }

  // Results modal
  $("btn-rematch").addEventListener("click", () => {
    $("results-modal").classList.add("hidden");
    if (appState.isReplay && appState.replayData) {
      playReplayObject(appState.replayData);
      return;
    }
    if (appState.ws && appState.ws.readyState === 1) {
      appState.ws.send(JSON.stringify({ type: "REMATCH" }));
    }
  });
  $("btn-results-lobby").addEventListener("click", () => {
    $("results-modal").classList.add("hidden");
    leaveRoom();
    showView("view-town");
  });

  // Stage canvas interactions
  const canvas = $("arena-canvas");
  canvas.addEventListener("mousemove", (e) => {
    const iconHb = appState.renderer.pickTrayIconAt(e.clientX, e.clientY);
    appState.renderer.hoveredIcon = iconHb;
    renderStageIconTooltip(iconHb, e.clientX, e.clientY);

    const unit = iconHb ? iconHb.unit : appState.renderer.pickUnitAt(e.clientX, e.clientY);
    appState.renderer.hoveredUid = unit ? unit.uid : null;
    if (unit && appState.obs) {
      const isEnemy = appState.obs.enemy_units.some((u) => u.uid === unit.uid);
      if (isEnemy) {
        appState.inspectedEnemyUid = unit.uid;
        renderEnemyPanel();
      }
    }
  });
  canvas.addEventListener("mouseleave", () => {
    appState.renderer.hoveredIcon = null;
    renderStageIconTooltip(null, 0, 0);
  });

  canvas.addEventListener("click", (e) => {
    if (appState.busyAnimating || !appState.obs || appState.obs.finished) return;
    const unit = appState.renderer.pickUnitAt(e.clientX, e.clientY);
    if (!unit) return;

    if (appState.armedSkillId && appState.selectedActorUid && appState.obs.is_my_turn && appState.seat >= 0) {
      const matchingAct = (appState.obs.legal_actions || []).find(
        (a) =>
          a.kind === "SKILL" &&
          a.actor_uid === appState.selectedActorUid &&
          a.skill_id === appState.armedSkillId &&
          a.target_uids.includes(unit.uid)
      );
      if (matchingAct) {
        const actorUnit = appState.obs.my_units.find((x) => x.uid === matchingAct.actor_uid);
        const sdef = actorUnit ? skillDef(actorUnit.hero_id, matchingAct.skill_id) : null;
        appState.armedSkillId = null;
        appState.renderer.armedSkill = null;
        appState.audio.playSkillTrigger();
        if (sdef) appState.renderer.previewSkillCast(matchingAct.actor_uid, sdef, matchingAct.target_uids);
        sendAction(matchingAct);
        return;
      }
    }

    const myHero = appState.obs.my_units.find((u) => u.uid === unit.uid && u.alive && !u.is_corpse);
    if (myHero) {
      appState.selectedActorUid = myHero.uid;
      appState.armedSkillId = null;
      appState.renderer.selectedActorUid = myHero.uid;
      appState.renderer.armedSkill = null;
      $("move-drawer").classList.add("hidden");
      appState.audio.playUiClick();
      renderCombatHud();
      return;
    }

    const enHero = appState.obs.enemy_units.find((u) => u.uid === unit.uid);
    if (enHero) {
      appState.inspectedEnemyUid = enHero.uid;
      renderEnemyPanel();
    }
  });
}

// ------------------------------------------------------------------ WebSocket Room Lifecycle
function leaveRoom() {
  appState.playbackId = (appState.playbackId || 0) + 1;
  appState.renderer?.resetPlayback();
  appState.busyAnimating = false;
  appState.audio.stopArenaMusic();
  appState.isReplay = false;
  appState.replayRunning = false;
  if (appState.ws) {
    try { appState.ws.close(); } catch {}
    appState.ws = null;
  }
  appState.roomId = null;
  appState.obs = null;
  appState.draftTeam = null;
  $("room-badge").classList.add("hidden");
  $("results-modal").classList.add("hidden");
  $("spectate-bar").classList.add("hidden");
}

function connectRoom(roomId, seatQuery = "auto") {
  leaveRoom();
  appState.roomId = roomId;
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  const wsUrl = `${proto}://${window.location.host}/ws/room/${encodeURIComponent(roomId)}?seat=${encodeURIComponent(seatQuery)}`;
  const ws = new WebSocket(wsUrl);
  appState.ws = ws;

  ws.onerror = () => {
    alert(appState.lang === "zh" ? "WebSocket 连接失败，请确认后端服务器正在运行。" : "WebSocket connection failed.");
  };

  let playback = Promise.resolve();
  ws.onmessage = (evt) => {
    if (appState.ws !== ws) return;
    const msg = JSON.parse(evt.data);
    if (msg.type === "ERROR") {
      alert(msg.message);
      return;
    }
    if (msg.type === "ROOM_STATE") {
      appState.seat = msg.seat;
      appState.roomInfo = msg.room;
      onRoomStateUpdate();
    } else if (msg.type === "COMBAT_UPDATE") {
      appState.seat = msg.seat;
      appState.roomInfo = msg.room;
      playback = playback.then(() => {
        if (appState.ws === ws) return onCombatUpdate(msg.observation, msg.events || []);
      }).catch((error) => console.error("Combat playback failed", error));
    }
  };
}

function onRoomStateUpdate() {
  const room = appState.roomInfo;
  if (!room) return;
  $("room-badge").textContent = `${room.room_id} · ${appState.seat === 0 ? "P1" : appState.seat === 1 ? "P2" : "SPECTATOR"}`;
  $("room-badge").classList.remove("hidden");
  appState.renderer.loadArenaBackground(room.arena_tier);
  $("arena-tier-select").value = room.arena_tier;

  if (room.phase === "SETUP" && appState.seat >= 0) {
    const serverTeam = appState.seat === 0 ? room.p0_team : room.p1_team;
    if (!appState.draftTeam) {
      appState.draftTeam = structuredClone(serverTeam || appState.bootstrap.presets[0].team);
    }
    showView("view-setup");
    renderTeamBuilder();
    updateConnectionCard();
  } else if (room.mode === "bot_vs_bot") {
    showView("view-combat");
    $("spectate-bar").classList.remove("hidden");
    $("btn-pause").textContent = room.paused ? "▶ Resume" : "⏸ Pause";
  }
}

function updateConnectionCard() {
  const room = appState.roomInfo;
  if (!room) return;
  const isPvp = room.mode === "pvp";
  $("conn-mode-title").textContent = isPvp
    ? (appState.lang === "zh" ? "🎭 本地双开 PvP 对战" : "🎭 LOCAL 2-PLAYER PVP")
    : (appState.lang === "zh" ? `🤖 对手: ${room.p1_name}` : `🤖 OPPONENT: ${room.p1_name}`);
  $("pvp-invite-controls").classList.toggle("hidden", !isPvp);
  if (isPvp) {
    $("invite-room-code").textContent = room.room_id;
    const p1 = room.p0_connected ? (room.p0_ready ? "P1 ✅已锁定" : "P1 ⏳配队中") : "P1 ○未连接";
    const p2 = room.p1_connected ? (room.p1_ready ? "P2 ✅已锁定" : "P2 ⏳配队中") : "P2 ○等待加入";
    $("invite-seat-status").textContent = `${p1}   vs   ${p2}`;
  } else {
    $("invite-seat-status").textContent = appState.lang === "zh" ? "AI 已就绪，锁定后即刻开战！" : "AI Ready — Lock In to Fight!";
  }

  const myReady = appState.seat === 0 ? room.p0_ready : room.p1_ready;
  const oppReady = appState.seat === 0 ? room.p1_ready : room.p0_ready;
  $("ready-status").textContent = myReady
    ? (appState.lang === "zh" ? "你已锁定！等待对手锁定..." : "Locked In! Waiting for opponent...")
    : oppReady
      ? (appState.lang === "zh" ? "对手已准备就绪！" : "Opponent is READY!")
      : (appState.lang === "zh" ? "点击顶部 18 位英雄换人，配置技能与饰品后锁定" : "Select heroes, 4 skills & 2 trinkets, then Lock In");
  $("btn-lock-in").disabled = myReady;
}

function syncDraftToServer() {
  if (appState.ws && appState.ws.readyState === 1 && appState.draftTeam) {
    appState.ws.send(JSON.stringify({ type: "SET_TEAM", team: appState.draftTeam }));
  }
}

// ------------------------------------------------------------------ Authentic Party Setup Rendering
function renderTeamBuilder() {
  const team = appState.draftTeam;
  if (!team) return;
  const cur = team.heroes[appState.editingSlot];

  // 1. Top 18-Hero Horizontal Strip (forbid selecting duplicate hero classes!)
  const takenByOther = new Set(
    team.heroes.filter((_, idx) => idx !== appState.editingSlot).map((h) => h.hero_id)
  );
  const stripEl = $("hero-roster-strip");
  stripEl.innerHTML = "";
  for (const hid of appState.bootstrap.hero_order) {
    const isTaken = takenByOther.has(hid);
    const tile = document.createElement("div");
    tile.className = "roster-tile" + (hid === cur.hero_id ? " active" : "") + (isTaken ? " taken" : "");
    tile.title = isTaken
      ? (appState.lang === "zh" ? "该职业已在阵容中，禁止重复职业" : "Class already in party (no duplicate classes)")
      : heroName(hid);
    tile.innerHTML = `
      <img src="/api/portrait/${hid}/A" />
      ${isTaken ? `<div class="taken-tag">${appState.lang === "zh" ? "已选" : "USED"}</div>` : ""}
      <span>${heroName(hid)}</span>
    `;
    tile.addEventListener("click", () => {
      if (isTaken) {
        $("ready-status").textContent =
          appState.lang === "zh" ? "⚠ 竞技场规则：禁止选择重复职业！" : "⚠ Duplicate hero classes are not allowed!";
        return;
      }
      const hdef = appState.bootstrap.heroes[hid];
      cur.hero_id = hid;
      cur.trinkets = cur.trinkets.filter((tid) => {
        const item = trinketDef(tid);
        return item && (!item.hero_class || item.hero_class === hid);
      });
      cur.skills = [...hdef.default_skills];
      cur.name = hdef.name_en;
      syncDraftToServer();
      renderTeamBuilder();
      appState.audio.playUiClick();
    });
    stripEl.appendChild(tile);
  }

  // 2. 4 Party Formation Slots (Rank 4 Back -> Rank 1 Front)
  const slotsEl = $("party-slots");
  slotsEl.innerHTML = "";
  for (const idx of [3, 2, 1, 0]) {
    const h = team.heroes[idx];
    const div = document.createElement("div");
    div.className = "party-slot" + (idx === appState.editingSlot ? " selected" : "");
    const iconsHtml = h.skills
      .slice(0, 4)
      .map((sid) => {
        const sd = skillDef(h.hero_id, sid);
        return sd ? `<img src="/api/skill_icon/${h.hero_id}/${sd.icon}" />` : "";
      })
      .join("");
    div.innerHTML = `
      <img class="slot-port" src="/api/portrait/${h.hero_id}/${h.variant}" />
      <div class="slot-info">
        <div class="slot-rank">${appState.lang === "zh" ? `站位 ${idx + 1}` : `RANK ${idx + 1}`} ${idx === 0 ? "(前排)" : idx === 3 ? "(后排)" : ""}</div>
        <div class="slot-name">${heroName(h.hero_id)}</div>
        <div class="slot-mini-icons">${iconsHtml}</div>
      </div>
    `;
    div.addEventListener("click", () => {
      appState.editingSlot = idx;
      renderTeamBuilder();
      appState.audio.playUiClick();
    });
    slotsEl.appendChild(div);
  }

  // 3. Character Sheet Details
  const hdef = appState.bootstrap.heroes[cur.hero_id];
  $("sheet-hero-title").textContent = `${heroName(cur.hero_id)} · ${appState.lang === "zh" ? `站位 ${appState.editingSlot + 1}` : `Rank ${appState.editingSlot + 1}`}`;
  $("sheet-hero-stats").innerHTML = `
    <span>HP <b>${hdef.max_hp}</b></span>
    <span>DMG <b>${hdef.dmg_min}-${hdef.dmg_max}</b></span>
    <span>DODGE <b>${hdef.dodge}</b></span>
    <span>PROT <b>${Math.round(hdef.prot * 100)}%</b></span>
    <span>CRIT <b>${hdef.crit}%</b></span>
    <span>DBR <b>${hdef.deathblow_res}%</b></span>
    <span>STUN/BLD/BLT <b>${hdef.stun_res}/${hdef.bleed_res}/${hdef.blight_res}</b></span>
  `;

  for (const btn of document.querySelectorAll(".skin-btn")) {
    btn.classList.toggle("active", btn.dataset.variant === cur.variant);
  }

  const skillEl = $("skill-picker");
  skillEl.innerHTML = "";
  const isAbom = cur.hero_id === "abomination";
  for (const s of hdef.skills) {
    const equipped = isAbom || cur.skills.includes(s.id);
    const row = document.createElement("div");
    row.className = "skill-pick-row" + (equipped ? " equipped" : "");
    const tCls = s.target_team === "enemy" ? "pip-t" : "pip-a";
    row.innerHTML = `
      <img src="/api/skill_icon/${cur.hero_id}/${s.icon}" />
      <div class="skill-pick-meta">
        <div class="skill-pick-title">
          <span>${skillName(s)}</span>
          <span class="pips">${formatPips(s.launch_ranks, "pip-l")} ▸ ${formatPips(s.target_ranks, tCls)}</span>
        </div>
        <div class="skill-pick-desc">${skillDesc(s)}</div>
      </div>
    `;
    row.addEventListener("click", () => {
      if (isAbom) return;
      if (cur.skills.includes(s.id)) {
        if (cur.skills.length > 1) cur.skills = cur.skills.filter((x) => x !== s.id);
      } else {
        if (cur.skills.length >= 4) cur.skills.shift();
        cur.skills.push(s.id);
      }
      syncDraftToServer();
      renderTeamBuilder();
      appState.audio.playUiClick();
    });
    skillEl.appendChild(row);
  }

  renderTrinketPicker();
  renderPartyBuilderPresets();
}

function renderPartyBuilderPresets() {
  const el = $("party-builder-list");
  el.innerHTML = "";
  for (const p of appState.bootstrap.presets || []) {
    const item = document.createElement("div");
    item.className = "preset-item";
    const icons = [...p.team.heroes]
      .reverse()
      .map((h) => `<img src="/api/portrait/${h.hero_id}/${h.variant}" />`)
      .join("");
    item.innerHTML = `
      <div class="preset-name">${appState.lang === "zh" ? p.name_zh : p.name_en}</div>
      <div class="preset-icons">${icons}</div>
    `;
    item.addEventListener("click", () => {
      appState.draftTeam = structuredClone(p.team);
      syncDraftToServer();
      renderTeamBuilder();
      appState.audio.playUiClick();
    });
    el.appendChild(item);
  }
}

function renderTrinketPicker() {
  const cur = appState.draftTeam?.heroes?.[appState.editingSlot];
  if (!cur) return;

  const eqEl = $("equipped-trinkets");
  eqEl.innerHTML = "";
  for (let i = 0; i < 2; i++) {
    const tid = cur.trinkets[i];
    const chip = document.createElement("div");
    chip.className = "eq-trinket-chip";
    if (tid) {
      const td = trinketDef(tid);
      chip.innerHTML = `<img src="/api/trinket_icon/${tid}" /><span>${trinketName(td)} ✕</span>`;
      chip.addEventListener("click", () => {
        cur.trinkets = cur.trinkets.filter((x) => x !== tid);
        syncDraftToServer();
        renderTrinketPicker();
      });
    } else {
      chip.innerHTML = `<span style="color:#776858">${appState.lang === "zh" ? `空饰品槽 ${i + 1}` : `Empty Slot ${i + 1}`}</span>`;
    }
    eqEl.appendChild(chip);
  }

  const q = ($("trinket-search").value || "").trim().toLowerCase();
  const listEl = $("trinket-picker");
  listEl.innerHTML = "";
  const all = (appState.bootstrap.trinkets || [])
    .filter((t) => !t.hero_class || t.hero_class === cur.hero_id)
    .filter((t) => !q || t.name_en.toLowerCase().includes(q) || t.name_zh.toLowerCase().includes(q) || t.description_en.toLowerCase().includes(q) || t.description_zh.toLowerCase().includes(q))
    .sort((a, b) => (b.hero_class === cur.hero_id ? 1 : 0) - (a.hero_class === cur.hero_id ? 1 : 0));

  for (const t of all) {
    const equipped = cur.trinkets.includes(t.id);
    const row = document.createElement("div");
    row.className = "trinket-row" + (equipped ? " equipped" : "");
    row.innerHTML = `
      <img src="/api/trinket_icon/${t.id}" loading="lazy" />
      <div class="trinket-meta">
        <div class="trinket-name">${trinketName(t)} ${t.hero_class ? "★" : ""}</div>
        <div class="trinket-desc">${trinketDesc(t)}</div>
      </div>
    `;
    row.addEventListener("click", () => {
      if (equipped) {
        cur.trinkets = cur.trinkets.filter((x) => x !== t.id);
      } else {
        if (cur.trinkets.length >= 2) cur.trinkets.shift();
        cur.trinkets.push(t.id);
      }
      syncDraftToServer();
      renderTrinketPicker();
      appState.audio.playUiClick();
    });
    listEl.appendChild(row);
  }
}

// ------------------------------------------------------------------ Authentic Combat HUD
async function onCombatUpdate(obs, events) {
  const playbackId = appState.playbackId;
  showView("view-combat");
  $("results-modal").classList.add("hidden");
  $("move-drawer").classList.add("hidden");
  if (!appState.audio.musicStarted && !appState.audio.muted) {
    appState.audio.startArenaMusic();
  }

  appendEventsToLog(events, obs);

  if (!appState.obs) {
    appState.obs = obs;
    appState.renderer.syncObservation(obs);
    appState.renderer.preloadCombatAssets(obs, appState.bootstrap?.heroes);
  }
  appState.busyAnimating = true;
  let playResult = null;
  if (events && events.length) {
    playResult = await appState.renderer.playEvents(events);
  }
  if (appState.playbackId !== playbackId) return;
  appState.obs = obs;
  appState.renderer.syncObservation(obs);
  // Frontend-only delay after Affliction/Virtue check before unlocking next action (zero backend impact!)
  if (playResult && (playResult.hadResolveCheck || playResult.hadHeartAttack)) {
    const cooldownMs = Math.round(500 / (appState.renderer.playbackSpeed || 1.0));
    await new Promise((r) => setTimeout(r, cooldownMs));
    if (appState.playbackId !== playbackId) return;
  }
  appState.busyAnimating = false;

  const selectable = (obs.my_units || []).filter((u) => u.selectable && u.alive && !u.is_corpse);
  if (selectable.length) {
    if (!selectable.some((u) => u.uid === appState.selectedActorUid)) {
      appState.selectedActorUid = selectable[0].uid;
    }
  } else {
    const firstLiving = (obs.my_units || []).find((u) => u.alive && !u.is_corpse);
    if (firstLiving && (!appState.selectedActorUid || !obs.my_units.some((u) => u.uid === appState.selectedActorUid && u.alive))) {
      appState.selectedActorUid = firstLiving.uid;
    }
  }

  if (!appState.inspectedEnemyUid || !obs.enemy_units.some((u) => u.uid === appState.inspectedEnemyUid && u.alive)) {
    const firstEn = (obs.enemy_units || []).find((u) => u.alive);
    appState.inspectedEnemyUid = firstEn ? firstEn.uid : null;
  }

  appState.armedSkillId = null;
  appState.renderer.selectedActorUid = appState.selectedActorUid;
  appState.renderer.armedSkill = null;

  renderCombatHud();
  if (obs.finished) showResultsModal(obs);
}

function sendAction(action) {
  if (!appState.ws || appState.ws.readyState !== 1 || appState.busyAnimating) return;
  appState.ws.send(JSON.stringify({ type: "ACTION", action }));
}

function renderCombatHud() {
  renderHeroPanel();
  renderEnemyPanel();
}

function setHudTooltip(html) {
  const el = $("hud-tooltip");
  if (!el) return;
  if (!html) {
    el.innerHTML = "";
    el.classList.add("hidden");
  } else {
    el.innerHTML = html;
    el.classList.remove("hidden");
  }
}

function getDefaultHeroTooltipHtml(u, obs, canAct) {
  if (!u || !obs) return "";
  if (appState.armedSkillId) {
    const sdef = skillDef(u.hero_id, appState.armedSkillId);
    if (sdef) {
      return `<strong style="color:#ffe270">${skillName(sdef)}</strong> — ${
        appState.lang === "zh" ? "点击舞台上高亮的目标发动技能！" : "Click a highlighted target on stage to execute!"
      }`;
    }
  }
  if (u.stunned && canAct) {
    return `<strong style="color:#ffe066">⚡ ${
      appState.lang === "zh" ? "该英雄已被眩晕！点击跳过以解除眩晕并获得 +50% 眩晕抗性。" : "Hero is STUNNED! Click PASS to recover (+50% Stun Resist)."
    }</strong>`;
  }
  if (u.dazed && !u.selectable && obs.is_my_turn) {
    return `<strong style="color:#f0a524">◐ ${
      appState.lang === "zh" ? "已被晕眩 (Dazed)：必须等其他未晕眩队友行动后才能选择！" : "DAZED: Must wait until all non-dazed allies act first!"
    }</strong>`;
  }
  return "";
}

function renderBaseStats(u, asColumn = false) {
  const s = u.stats || {};
  const accStr = `${Math.round(s.acc || 0) >= 0 ? "+" : ""}${Math.round(s.acc || 0)}`;
  const rows = [
    ["ACC", accStr],
    ["CRIT", `${Math.round(s.crit || 0)}%`],
    ["DMG", `${u.dmg_min}-${u.dmg_max}`],
    ["DODGE", `${Math.round(s.dodge || 0)}`],
    ["PROT", `${Math.round((s.prot || 0) * 100)}%`],
    ["DBR", `${Math.round(s.deathblow_res || 0)}%`],
  ];
  if (asColumn) {
    return rows.map(([k, v]) => `<div class="stat-line"><span>${k}</span><b>${v}</b></div>`).join("");
  }
  return rows.map(([k, v]) => `<span>${k} <b>${v}</b></span>`).join("");
}

function renderResistances(u) {
  const s = u.stats || {};
  const rows = [
    [appState.lang === "zh" ? "眩晕 STUN" : "STUN", `${Math.round(s.stun_res || 0)}%`, "#f0c850"],
    [appState.lang === "zh" ? "瘟疫 BLIGHT" : "BLIGHT", `${Math.round(s.blight_res || 0)}%`, "#8cd958"],
    [appState.lang === "zh" ? "流血 BLEED" : "BLEED", `${Math.round(s.bleed_res || 0)}%`, "#e84a42"],
    [appState.lang === "zh" ? "减益 DEBUFF" : "DEBUFF", `${Math.round(s.debuff_res || 0)}%`, "#d98a4a"],
    [appState.lang === "zh" ? "位移 MOVE" : "MOVE", `${Math.round(s.move_res || 0)}%`, "#6ab6e8"],
    [appState.lang === "zh" ? "压力 STRESS" : "STRESS", `${Math.round(s.stress_res || 0)}%`, "#c8b8f0"],
  ];
  return rows.map(([k, v, col]) => `<div class="res-line"><span style="color:${col}">${k}</span><b>${v}</b></div>`).join("");
}

function renderTrinketSlots(el, trinkets, restoreTooltipFn = null) {
  el.innerHTML = "";
  for (let i = 0; i < 2; i++) {
    const slot = document.createElement("div");
    slot.className = "hud-trinket-slot";
    const tid = (trinkets || [])[i];
    if (tid) {
      const td = trinketDef(tid);
      const img = document.createElement("img");
      img.className = "hud-trinket-icon";
      img.src = `/api/trinket_icon/${tid}`;
      img.alt = td ? trinketName(td) : tid;
      img.addEventListener("mouseenter", () => {
        if (td) {
          setHudTooltip(`<strong style="color:#f0cf7c">${trinketName(td)}</strong><div>${trinketDesc(td)}</div>`);
        }
      });
      img.addEventListener("mouseleave", () => {
        if (restoreTooltipFn) restoreTooltipFn();
        else setHudTooltip("");
      });
      slot.appendChild(img);
    }
    el.appendChild(slot);
  }
}

function renderPortraitSeal(imgEl, traitId) {
  if (!imgEl) return;
  const td = traitId ? appState.bootstrap?.traits?.[traitId] : null;
  if (td) {
    imgEl.src = td.is_virtue
      ? "/api/game_asset/panels/seal.heroic.png"
      : "/api/game_asset/panels/seal.affliction.png";
    imgEl.classList.remove("hidden");
  } else {
    imgEl.classList.add("hidden");
  }
}

function renderEnemyStatuses(u) {
  const el = $("hud-enemy-statuses");
  if (!el) return;
  const chips = [];
  if (u.is_corpse) chips.push(appState.lang === "zh" ? "☠ 尸体" : "☠ Corpse");
  if (u.at_deaths_door) chips.push(appState.lang === "zh" ? "☠ 濒死之门" : "☠ Death's Door");
  if (u.stunned) chips.push(appState.lang === "zh" ? "⚡ 眩晕" : "⚡ Stunned");
  if (u.dazed) chips.push(appState.lang === "zh" ? "◐ 晕眩" : "◐ Dazed");
  if (u.marked_rounds > 0) chips.push(`${appState.lang === "zh" ? "🎯 标记" : "🎯 Marked"} (${u.marked_rounds})`);
  if (u.stealth_rounds > 0) chips.push(`${appState.lang === "zh" ? "👁 潜行" : "👁 Stealth"} (${u.stealth_rounds})`);
  if (u.riposte_rounds > 0) chips.push(`${appState.lang === "zh" ? "⚔ 反击" : "⚔ Riposte"} (${u.riposte_rounds})`);
  if (u.aegis_blocks > 0) chips.push(`${appState.lang === "zh" ? "◈ 盾牌格挡" : "◈ Aegis"} ×${u.aegis_blocks}`);
  if (u.guarded_by_uid) chips.push(appState.lang === "zh" ? "🛡 被守护" : "🛡 Guarded");
  if (u.guarding_uids && u.guarding_uids.length) chips.push(appState.lang === "zh" ? "🛡 守护中" : "🛡 Guarding");
  const bleedSum = (u.dots || []).filter((d) => d.kind === "bleed").reduce((a, b) => a + b.amount, 0);
  const blightSum = (u.dots || []).filter((d) => d.kind === "blight").reduce((a, b) => a + b.amount, 0);
  if (bleedSum > 0) chips.push(`${appState.lang === "zh" ? "🩸 流血" : "🩸 Bleed"} ${bleedSum}/r`);
  if (blightSum > 0) chips.push(`${appState.lang === "zh" ? "☠ 瘟疫" : "☠ Blight"} ${blightSum}/r`);
  if (u.buffs && u.buffs.length) {
    const pos = u.buffs.filter((b) => b.value > 0).length;
    const neg = u.buffs.filter((b) => b.value < 0).length;
    if (pos) chips.push(`${appState.lang === "zh" ? "▲ 增益" : "▲ Buff"} ×${pos}`);
    if (neg) chips.push(`${appState.lang === "zh" ? "▼ 减益" : "▼ Debuff"} ×${neg}`);
  }
  if (!chips.length) {
    el.innerHTML = `<span style="color:#6d6052;font-size:12px">${appState.lang === "zh" ? "无异常状态" : "No active effects"}</span>`;
  } else {
    el.innerHTML = chips.map((c) => `<span class="enemy-status-chip">${c}</span>`).join("");
  }
}

function renderHeroPanel() {
  const obs = appState.obs;
  if (!obs) return;
  const u = obs.my_units.find((x) => x.uid === appState.selectedActorUid) || obs.my_units.find((x) => x.alive);
  if (!u) return;

  const canAct = obs.is_my_turn && u.selectable && appState.seat >= 0 && !appState.isReplay;
  const restoreDefaultTooltip = () => setHudTooltip(getDefaultHeroTooltipHtml(u, obs, canAct));

  $("hud-hero-portrait").src = `/api/portrait/${u.hero_id}/${u.variant}`;
  renderPortraitSeal($("hud-hero-seal"), u.trait_id);
  $("hud-hero-name").textContent = heroName(u.hero_id) + (u.is_beast ? " [BEAST]" : "");
  $("hud-hero-rank").textContent = `R${u.rank}`;
  $("hud-hero-hp").textContent = `${u.hp} / ${u.max_hp}` + (u.at_deaths_door ? " ☠" : "");
  $("hud-hero-stress").textContent = `${u.stress} / 200`;
  $("hud-hero-stats").innerHTML = renderBaseStats(u, true);
  $("hud-hero-res").innerHTML = renderResistances(u);
  renderTrinketSlots($("hud-hero-trinkets"), u.trinkets, restoreDefaultTooltip);

  const traitEl = $("hud-hero-trait");
  if (u.trait_id && appState.bootstrap.traits[u.trait_id]) {
    const td = appState.bootstrap.traits[u.trait_id];
    traitEl.textContent = appState.lang === "zh" ? td.name_zh : td.name_en;
    traitEl.className = "trait-pill " + (td.is_virtue ? "virtue" : "affliction");
  } else {
    traitEl.className = "trait-pill hidden";
  }

  // Real pass icon switches to ability_pass_stunned.png when stunned!
  $("btn-pass-img").src = u.stunned
    ? "/api/game_asset/panels/icons_ability/ability_pass_stunned.png"
    : "/api/game_asset/panels/icons_ability/ability_pass.png";

  const skillsEl = $("hud-skills");
  skillsEl.innerHTML = "";
  const actorLegal = (obs.legal_actions || []).filter((a) => a.actor_uid === u.uid);

  let displaySkills = u.equipped_skills;
  if (u.hero_id === "abomination") {
    displaySkills = u.equipped_skills.filter((sid) => {
      const sd = skillDef(u.hero_id, sid);
      return sd && (sd.abom_form === "any" || (u.is_beast ? sd.abom_form === "beast" : sd.abom_form === "human"));
    });
  }

  for (const sid of displaySkills) {
    const sdef = skillDef(u.hero_id, sid);
    if (!sdef) continue;
    const skillActions = actorLegal.filter((a) => a.kind === "SKILL" && a.skill_id === sid);
    const usable = canAct && skillActions.length > 0;

    const btn = document.createElement("button");
    btn.className = "skill-btn" + (appState.armedSkillId === sid ? " armed" : "");
    btn.disabled = !usable;
    const remUses = sdef.max_uses > 0 ? Math.max(0, sdef.max_uses - (u.skill_uses[sid] || 0)) : 0;
    const statusOverlay = u.stunned
      ? `<img class="skill-overlay" src="/api/game_asset/panels/icons_ability/ability_stun_disabled.png" />`
      : (u.dazed && !u.selectable)
        ? `<img class="skill-overlay" src="/api/game_asset/panels/icons_ability/ability_dazed_disabled.png" />`
        : "";
    btn.innerHTML = `
      <img class="skill-img" src="/api/skill_icon/${u.hero_id}/${sdef.icon}" />
      ${statusOverlay}
      ${sdef.max_uses > 0 ? `<span class="skill-uses-badge">${remUses}/${sdef.max_uses}</span>` : ""}
    `;
    btn.addEventListener("mouseenter", () => {
      const tCls = sdef.target_team === "enemy" ? "pip-t" : "pip-a";
      setHudTooltip(`
        <strong style="color:#f0cf7c">${skillName(sdef)}</strong>
        <span class="pips" style="margin-left:8px">${formatPips(sdef.launch_ranks, "pip-l")} ▸ ${formatPips(sdef.target_ranks, tCls)}</span>
        ${sdef.is_aoe ? `<span style="color:#ff8c66;margin-left:6px">[AoE]</span>` : ""}
        <div>${skillDesc(sdef)}</div>
      `);
    });
    btn.addEventListener("mouseleave", restoreDefaultTooltip);
    btn.addEventListener("click", () => {
      if (!usable) return;
      $("move-drawer").classList.add("hidden");

      if (sdef.target_team === "self" && skillActions.length === 1) {
        appState.armedSkillId = null;
        appState.renderer.armedSkill = null;
        appState.audio.playSkillTrigger();
        appState.renderer.previewSkillCast(u.uid, sdef, skillActions[0].target_uids);
        sendAction(skillActions[0]);
        return;
      }
      if (appState.armedSkillId === sid && skillActions.length === 1) {
        appState.armedSkillId = null;
        appState.renderer.armedSkill = null;
        appState.audio.playSkillTrigger();
        appState.renderer.previewSkillCast(u.uid, sdef, skillActions[0].target_uids);
        sendAction(skillActions[0]);
        return;
      }

      appState.audio.playUiClick();
      appState.armedSkillId = sid;
      appState.renderer.armedSkill = {
        skillDef: sdef,
        validTargetGroups: skillActions.map((a) => a.target_uids),
      };
      renderHeroPanel();
    });
    skillsEl.appendChild(btn);
  }

  $("btn-move").disabled = !(canAct && actorLegal.some((a) => a.kind === "MOVE"));
  $("btn-pass").disabled = !(canAct && actorLegal.some((a) => a.kind === "PASS"));
  $("btn-surrender").disabled = appState.seat < 0 || appState.isReplay || obs.finished;

  restoreDefaultTooltip();
}

function toggleMoveDrawer() {
  const drawer = $("move-drawer");
  if (!drawer.classList.contains("hidden")) {
    drawer.classList.add("hidden");
    return;
  }
  const obs = appState.obs;
  if (!obs || !appState.selectedActorUid) return;
  const moves = (obs.legal_actions || []).filter((a) => a.kind === "MOVE" && a.actor_uid === appState.selectedActorUid);
  if (!moves.length) return;
  drawer.innerHTML = `<span>${appState.lang === "zh" ? "移动：" : "Move:"}</span>`;
  for (const m of moves) {
    const b = document.createElement("button");
    b.className = "btn-iron";
    b.textContent = m.move_delta > 0
      ? (appState.lang === "zh" ? `前进 ${m.move_delta} 格 →` : `Forward ${m.move_delta} →`)
      : (appState.lang === "zh" ? `← 后退 ${-m.move_delta} 格` : `← Back ${-m.move_delta}`);
    b.addEventListener("click", () => {
      drawer.classList.add("hidden");
      sendAction(m);
    });
    drawer.appendChild(b);
  }
  drawer.classList.remove("hidden");
}

function renderEnemyPanel() {
  const obs = appState.obs;
  if (!obs) return;
  const u = obs.enemy_units.find((x) => x.uid === appState.inspectedEnemyUid) || obs.enemy_units.find((x) => x.alive);
  if (!u) return;

  const restoreHeroTooltip = () => {
    const hero = obs.my_units.find((x) => x.uid === appState.selectedActorUid) || obs.my_units.find((x) => x.alive);
    const canAct = obs.is_my_turn && hero?.selectable && appState.seat >= 0 && !appState.isReplay;
    setHudTooltip(getDefaultHeroTooltipHtml(hero, obs, canAct));
  };

  $("hud-enemy-portrait").src = `/api/portrait/${u.hero_id}/${u.variant}`;
  renderPortraitSeal($("hud-enemy-seal"), u.trait_id);
  $("hud-enemy-name").textContent = (u.is_corpse ? "☠ " : "") + heroName(u.hero_id);
  $("hud-enemy-rank").textContent = `R${u.rank}`;
  $("hud-enemy-hp").textContent = `${u.hp} / ${u.max_hp}` + (u.at_deaths_door ? " ☠" : "");
  $("hud-enemy-stress").textContent = u.is_corpse ? "CORPSE" : `${u.stress} / 200`;
  $("hud-enemy-stats").innerHTML = renderBaseStats(u, false);
  $("hud-enemy-res").innerHTML = renderResistances(u);
  renderTrinketSlots($("hud-enemy-trinkets"), u.trinkets, restoreHeroTooltip);
  renderEnemyStatuses(u);

  // Enemy 4 equipped skill icons (matching arena_panel_monster.png skills_pos!)
  const esEl = $("hud-enemy-skills");
  esEl.innerHTML = "";
  for (const sid of (u.equipped_skills || []).slice(0, 4)) {
    const sd = skillDef(u.hero_id, sid);
    if (!sd) continue;
    const img = document.createElement("img");
    img.src = `/api/skill_icon/${u.hero_id}/${sd.icon}`;
    img.alt = skillName(sd);
    img.addEventListener("mouseenter", () => {
      const tCls = sd.target_team === "enemy" ? "pip-t" : "pip-a";
      setHudTooltip(`
        <strong style="color:#ff867c">[${heroName(u.hero_id)}] ${skillName(sd)}</strong>
        <span class="pips" style="margin-left:8px">${formatPips(sd.launch_ranks, "pip-l")} ▸ ${formatPips(sd.target_ranks, tCls)}</span>
        <div>${skillDesc(sd)}</div>
      `);
    });
    img.addEventListener("mouseleave", restoreHeroTooltip);
    esEl.appendChild(img);
  }

  const traitEl = $("hud-enemy-trait");
  if (u.trait_id && appState.bootstrap.traits[u.trait_id]) {
    const td = appState.bootstrap.traits[u.trait_id];
    traitEl.textContent = appState.lang === "zh" ? td.name_zh : td.name_en;
    traitEl.className = "trait-pill " + (td.is_virtue ? "virtue" : "affliction");
  } else {
    traitEl.className = "trait-pill hidden";
  }
}

function unitLabel(uid, obs) {
  const all = [...(obs?.my_units || []), ...(obs?.enemy_units || [])];
  const u = all.find((x) => x.uid === uid);
  if (!u) return uid;
  const side = obs.my_units.includes(u) ? (appState.lang === "zh" ? "我方" : "Ally") : (appState.lang === "zh" ? "敌方" : "Enemy");
  return `[${side}] ${heroName(u.hero_id)}`;
}

function appendEventsToLog(events, obs) {
  const logEl = $("combat-log");
  for (const ev of events || []) {
    const d = ev.data || {};
    let cls = "log-entry";
    let line = "";
    if (ev.type === "ROUND_START") {
      line = `━━ ${appState.lang === "zh" ? `第 ${d.round} 回合` : `Round ${d.round}`} ━━`;
    } else if (ev.type === "SKILL_USE") {
      line = `${unitLabel(d.actor_uid, obs)} ▸ ${appState.lang === "zh" ? d.name_zh : d.name_en}`;
    } else if (ev.type === "HIT" && d.dmg > 0) {
      cls += d.crit ? " log-crit" : "";
      line = `  ↳ ${d.crit ? "CRIT! " : ""}${unitLabel(d.target_uid, obs)} -${d.dmg} HP (${d.hp})`;
    } else if (ev.type === "MISS") {
      line = `  ↳ ${unitLabel(d.target_uid, obs)} DODGED!`;
    } else if (ev.type === "HEAL") {
      cls += " log-heal";
      line = `  ↳ ${unitLabel(d.unit_uid, obs)} +${d.amount} HP`;
    } else if (ev.type === "STRESS") {
      cls += " log-stress";
      line = `  ↳ ${unitLabel(d.unit_uid, obs)} +${d.amount} Stress (${d.stress}/200)`;
    } else if (ev.type === "RESOLVE_CHECK") {
      cls += d.is_virtue ? " log-crit" : " log-death";
      line = `★ ${unitLabel(d.unit_uid, obs)} → ${appState.lang === "zh" ? d.name_zh : d.name_en}!`;
    } else if (ev.type === "HEART_ATTACK") {
      cls += " log-death";
      line = `☠ HEART ATTACK: ${unitLabel(d.unit_uid, obs)}!`;
    } else if (ev.type === "DEATHBLOW") {
      cls += " log-death";
      line = `☠ DEATHBLOW! ${unitLabel(d.unit_uid, obs)} (${d.cause})`;
    }
    if (line) {
      const div = document.createElement("div");
      div.className = cls;
      div.textContent = line;
      logEl.appendChild(div);
    }
  }
  logEl.scrollTop = logEl.scrollHeight;
}

function showResultsModal(obs) {
  const iWon = obs.winner === obs.viewer_team;
  const titleEl = $("results-title");
  const card = $("results-card");
  if (appState.seat < 0 || appState.isReplay) {
    titleEl.textContent = `TEAM ${obs.winner + 1} VICTORY!`;
    titleEl.className = "win";
    card.classList.remove("defeat");
  } else {
    titleEl.textContent = iWon
      ? (appState.lang === "zh" ? "🏆 竞技场胜利！" : "🏆 VICTORY!")
      : (appState.lang === "zh" ? "☠ 败北 — 血洒擂台" : "☠ DEFEAT");
    titleEl.className = iWon ? "win" : "lose";
    card.classList.toggle("defeat", !iWon);
  }
  $("results-subtitle").textContent = `${appState.lang === "zh" ? "终结方式" : "Result"}: ${obs.win_reason} · ${
    appState.lang === "zh" ? `共 ${obs.round_num} 回合` : `${obs.round_num} Rounds`
  }`;
  const myAlive = obs.my_units.filter((u) => u.alive && !u.is_corpse).length;
  const enAlive = obs.enemy_units.filter((u) => u.alive && !u.is_corpse).length;
  $("results-stats").innerHTML = `
    <div>${appState.lang === "zh" ? "我方存活英雄" : "Your Survivors"}: <strong>${myAlive} / 4</strong></div>
    <div>${appState.lang === "zh" ? "敌方存活英雄" : "Enemy Survivors"}: <strong>${enAlive} / 4</strong></div>
  `;
  $("results-modal").classList.remove("hidden");
}

// ------------------------------------------------------------------ Replay Player
async function refreshReplays() {
  const res = await fetch("/api/replays");
  const data = await res.json();
  const el = $("replay-list");
  el.innerHTML = "";
  if (!data.replays || !data.replays.length) {
    el.innerHTML = `<div style="font-size:12px;color:#887a68">${
      appState.lang === "zh" ? "暂无录像，点击上方运行锦标赛生成！" : "No replays yet — run a tournament above!"
    }</div>`;
    return;
  }
  for (const r of data.replays) {
    const row = document.createElement("div");
    row.className = "replay-item";
    row.innerHTML = `<span>${r.bot0} vs ${r.bot1} (${r.rounds}r)</span>`;
    const btn = document.createElement("button");
    btn.className = "btn-mini";
    btn.textContent = "▶ Watch";
    btn.addEventListener("click", async () => {
      closeAllBuildingModals();
      const rr = await fetch(`/api/replays/${encodeURIComponent(r.filename)}`);
      const replay = await rr.json();
      playReplayObject(replay);
    });
    row.appendChild(btn);
    el.appendChild(row);
  }
}

async function playReplayObject(replay) {
  leaveRoom();
  const playbackId = appState.playbackId;
  appState.isReplay = true;
  appState.replayData = replay;
  appState.replayRunning = true;
  appState.seat = -1;
  $("combat-log").innerHTML = "";
  $("spectate-bar").classList.remove("hidden");
  $("spectate-label").textContent = `🎬 REPLAY: ${replay.bot0} vs ${replay.bot1}`;
  $("btn-pause").textContent = "⏸ Pause";
  showView("view-combat");
  appState.audio.startArenaMusic();

  for (const frame of replay.frames || []) {
    while (!appState.replayRunning && appState.isReplay) {
      await new Promise((r) => setTimeout(r, 150));
    }
    if (!appState.isReplay || playbackId !== appState.playbackId) return;
    await onCombatUpdate(frame.obs0, frame.events || []);
    await new Promise((r) => setTimeout(r, 450 / appState.renderer.playbackSpeed));
  }
}

init();

// ------------------------------------------------------------------ Stage Buff/Debuff/Status Icon Hover Tooltip
const BUFF_STAT_LABELS = {
  dmg_pct: ["伤害", "DMG", true],
  acc: ["精准", "ACC", false],
  crit: ["暴击", "CRIT", false, "%"],
  dodge: ["闪避", "DODGE", false],
  prot: ["防御", "PROT", true],
  deathblow_res: ["抗死门", "Deathblow Res", false, "%"],
  deathblow_chance: ["致死率", "Deathblow Chance", false, "%"],
  stun_res: ["眩晕抗性", "Stun Res", false, "%"],
  bleed_res: ["流血抗性", "Bleed Res", false, "%"],
  blight_res: ["腐蚀抗性", "Blight Res", false, "%"],
  debuff_res: ["减益抗性", "Debuff Res", false, "%"],
  move_res: ["位移抗性", "Move Res", false, "%"],
  stress_res: ["压力抗性", "Stress Res", false, "%"],
  stress_dmg_pct: ["施加压力", "Stress Dealt", true],
  stress_heal_pct: ["压力治疗", "Stress Heal", true],
  heal_done_pct: ["治疗量", "Healing Done", true],
  heal_recv_pct: ["受治疗量", "Healing Recv", true],
  bleed_chance: ["流血概率", "Bleed Chance", false, "%"],
  blight_chance: ["腐蚀概率", "Blight Chance", false, "%"],
  stun_chance: ["晕眩概率", "Stun Chance", false, "%"],
  debuff_chance: ["减益概率", "Debuff Chance", false, "%"],
  move_chance: ["位移概率", "Move Chance", false, "%"],
  virtue_chance: ["美德概率", "Virtue Chance", false, "%"],
  crits_received: ["被暴击率", "Crits Received", false, "%"],
  damage_taken_pct: ["承受伤害", "Damage Taken", true],
  armor_pierce: ["护甲穿透", "Armor Pierce", true],
  rake_dmg_pct: ["利爪横扫伤害", "Rake DMG", true],
  finale_damage: ["谢幕充能伤害", "Finale DMG", true],
  finale_crit: ["谢幕充能暴击", "Finale CRIT", false, "%"],
  dmg_vs_marked_pct: ["对标记伤害", "DMG vs Marked", true],
  dmg_vs_bleed_pct: ["对流血伤害", "DMG vs Bleed", true],
  dmg_vs_blight_pct: ["对腐蚀伤害", "DMG vs Blight", true],
  dmg_vs_stunned_pct: ["对眩晕伤害", "DMG vs Stunned", true],
};

const BUFF_COND_LABELS = {
  melee: ["近战", "Melee"],
  ranged: ["远程", "Ranged"],
  riposte: ["反击", "Riposte"],
  stealthed: ["潜行时", "Stealthed"],
  guarding: ["守护时", "Guarding"],
  guarded: ["受守护时", "Guarded"],
  deaths_door: ["死门时", "Death's Door"],
  rank1: ["1号位", "Rank 1"],
  afflicted: ["折磨时", "Afflicted"],
  bleeding: ["流血时", "Bleeding"],
  low20: ["生命<20%", "HP<20%"],
};

function formatBuffEntryLine(b, zh) {
  const [baseStat, cond] = String(b.stat || "").split("@");
  const spec = BUFF_STAT_LABELS[baseStat] || [baseStat, baseStat, false, ""];
  const label = zh ? spec[0] : spec[1];
  const isRatio = !!spec[2];
  const suffix = spec[3] || (isRatio ? "%" : "");
  const rawNum = isRatio ? Math.round((b.value || 0) * 100) : Math.round(b.value || 0);
  const signed = `${rawNum >= 0 ? "+" : ""}${rawNum}${suffix}`;
  const condStr = cond && BUFF_COND_LABELS[cond]
    ? (zh ? ` (${BUFF_COND_LABELS[cond][0]})` : ` (${BUFF_COND_LABELS[cond][1]})`)
    : "";
  const durStr = b.rounds_left < 0
    ? (zh ? "整场战斗" : "Battle")
    : (zh ? `${b.rounds_left} 回合` : `${b.rounds_left} rds`);
  const cls = b.is_debuff ? "sbt-neg" : "sbt-pos";
  return `<div class="sbt-line"><span class="${cls}">${signed} ${label}${condStr}</span><span class="sbt-dur">${durStr}</span></div>`;
}

function renderStageIconTooltip(hb, clientX, clientY) {
  const el = $("stage-buff-tooltip");
  if (!el) return;
  if (!hb) {
    el.classList.add("hidden");
    return;
  }

  const zh = appState.lang === "zh";
  const u = hb.unit;
  let title = "";
  let color = "#f4d78b";
  let body = "";

  if (hb.kind === "buff") {
    title = zh ? `▲ 增益效果 (${hb.posBuffs.length})` : `▲ Buffs (${hb.posBuffs.length})`;
    color = "#7ed8ff";
    body = hb.posBuffs.map((b) => formatBuffEntryLine(b, zh)).join("");
  } else if (hb.kind === "debuff") {
    title = zh ? `▼ 减益效果 (${hb.negBuffs.length})` : `▼ Debuffs (${hb.negBuffs.length})`;
    color = "#ff8e6b";
    body = hb.negBuffs.map((b) => formatBuffEntryLine(b, zh)).join("");
  } else if (hb.kind === "bleed") {
    const tot = hb.bleedDots.reduce((s, d) => s + d.amount, 0);
    title = zh ? `🩸 流血 (${tot} 点/回合)` : `🩸 Bleed (${tot} pts/rd)`;
    color = "#ff5c5c";
    body = hb.bleedDots.map((d) =>
      `<div class="sbt-line"><span>${d.amount} ${zh ? "点/回合" : "pts/rd"}</span><span class="sbt-dur">${d.rounds_left} ${zh ? "回合" : "rds"}</span></div>`
    ).join("") + `<div class="sbt-note">${zh ? "竞技场规则：流血不会触发致死判定" : "Circus rule: DoT cannot trigger Deathblow"}</div>`;
  } else if (hb.kind === "blight") {
    const tot = hb.blightDots.reduce((s, d) => s + d.amount, 0);
    title = zh ? `☣ 腐蚀 (${tot} 点/回合)` : `☣ Blight (${tot} pts/rd)`;
    color = "#8ce038";
    body = hb.blightDots.map((d) =>
      `<div class="sbt-line"><span>${d.amount} ${zh ? "点/回合" : "pts/rd"}</span><span class="sbt-dur">${d.rounds_left} ${zh ? "回合" : "rds"}</span></div>`
    ).join("") + `<div class="sbt-note">${zh ? "竞技场规则：腐蚀不会触发致死判定" : "Circus rule: DoT cannot trigger Deathblow"}</div>`;
  } else if (hb.kind === "horror") {
    const tot = hb.horrorDots.reduce((s, d) => s + d.amount, 0);
    title = zh ? `👁 恐惧 (+${tot} 压力/回合)` : `👁 Horror (+${tot} Stress/rd)`;
    color = "#c8a4ff";
    body = hb.horrorDots.map((d) =>
      `<div class="sbt-line"><span>+${d.amount} ${zh ? "压力/回合" : "stress/rd"}</span><span class="sbt-dur">${d.rounds_left} ${zh ? "回合" : "rds"}</span></div>`
    ).join("");
  } else if (hb.kind === "regen") {
    const tot = hb.regenDots.reduce((s, d) => s + d.amount, 0);
    title = zh ? `✦ 再生 (+${tot} 生命/回合)` : `✦ Restoration (+${tot} HP/rd)`;
    color = "#5cf282";
    body = hb.regenDots.map((d) =>
      `<div class="sbt-line"><span>+${d.amount} ${zh ? "生命/回合" : "HP/rd"}</span><span class="sbt-dur">${d.rounds_left} ${zh ? "回合" : "rds"}</span></div>`
    ).join("");
  } else if (hb.kind === "stun") {
    title = zh ? "⚡ 眩晕 (Stunned)" : "⚡ Stunned";
    color = "#ffe066";
    body = `<div>${zh ? "跳过本回合行动，解除后获得 +50% 眩晕抗性（持续 2 回合）" : "Skips next turn; gains +50% Stun Resist for 2 rounds on recovery."}</div>`;
  } else if (hb.kind === "daze") {
    title = zh ? "◐ 晕眩 (Dazed)" : "◐ Dazed";
    color = "#f0cf5a";
    body = `<div>${zh ? "必须等队内其他未晕眩英雄全部行动完毕后才可行动" : "Must act after all non-dazed allies have acted."}</div>`;
  } else if (hb.kind === "mark") {
    title = zh ? `🎯 标记 (${u.marked_rounds} 回合)` : `🎯 Marked (${u.marked_rounds} rds)`;
    color = "#ff5959";
    body = `<div>${zh ? "受到针对标记的技能额外伤害/暴击/致死率加成" : "Takes bonus damage/crit/deathblow from mark-synergy skills."}</div>`;
  } else if (hb.kind === "stealth") {
    title = zh ? `🌫 潜行 (${u.stealth_rounds} 回合)` : `🌫 Stealthed (${u.stealth_rounds} rds)`;
    color = "#c299ff";
    body = `<div>${zh ? "无法被普通单体攻击直接选中（AoE 与破潜行技能除外）" : "Cannot be directly targeted by standard single-target attacks."}</div>`;
  } else if (hb.kind === "riposte") {
    title = zh ? `⚔ 反击姿态 (${u.riposte_rounds} 回合)` : `⚔ Riposte (${u.riposte_rounds} rds)`;
    color = "#ffd480";
    body = `<div>${zh ? "受到直接伤害攻击时自动反击（0 伤害纯减益/压力技不触发反击）" : "Counters direct-damage attacks (non-damaging skills do not trigger Riposte)."}</div>`;
  } else if (hb.kind === "guard") {
    title = zh ? "🛡 守护 (Guard)" : "🛡 Guard";
    color = "#80d4ff";
    body = u.guarding_uid
      ? `<div>${zh ? `正在替队友承受单体攻击（剩余 ${u.guard_rounds} 回合）` : `Intercepting single-target attacks (${u.guard_rounds} rds left).`}</div>`
      : `<div>${zh ? "正受到队友守护，单体攻击将由守护者承受" : "Protected by an ally guardian."}</div>`;
  } else if (hb.kind === "block") {
    title = zh ? `🛡 神盾格挡 (${u.aegis_blocks} 层)` : `🛡 Aegis Block (${u.aegis_blocks})`;
    color = "#70d6ff";
    body = `<div>${zh ? "完全抵挡下一次直接伤害（消耗 1 层）" : "Negates incoming direct HP damage (consumes 1 stack)."}</div>`;
  } else if (hb.kind === "deaths_door") {
    const dbr = Math.round(u.stats?.deathblow_res ?? 75);
    title = zh ? "☠ 濒临死门 (Death's Door)" : "☠ Death's Door";
    color = "#ff3b3b";
    body = `<div>${zh ? `当前抗死门：${dbr}%；再次受直接伤害将触发致死判定（200 压力心梗直接即死）` : `Deathblow Resist: ${dbr}%. Any direct hit risks Deathblow (200 Stress Heart Attack is instant death).`}</div>`;
  } else if (hb.kind === "virtued" || hb.kind === "afflicted") {
    const tr = appState.bootstrap?.traits?.[u.trait_id];
    const tName = tr ? (zh ? tr.name_zh : tr.name_en) : (u.trait_id || "");
    title = hb.kind === "virtued" ? `✦ ${zh ? "美德" : "Virtue"}: ${tName}` : `☠ ${zh ? "折磨" : "Affliction"}: ${tName}`;
    color = hb.kind === "virtued" ? "#ffe478" : "#ff5252";
    body = `<div>${zh ? "100 压力决心检定永久状态（本场持续生效）" : "Resolve check trait active for the rest of the match."}</div>`;
  }

  el.innerHTML = `<div class="sbt-title" style="color:${color}">${title}</div>${body}`;
  el.classList.remove("hidden");

  const wrapRect = $("stage-wrap").getBoundingClientRect();
  let left = clientX - wrapRect.left + 16;
  let top = clientY - wrapRect.top - el.offsetHeight - 12;
  if (left + el.offsetWidth > wrapRect.width - 10) left = wrapRect.width - el.offsetWidth - 10;
  if (left < 10) left = 10;
  if (top < 10) top = clientY - wrapRect.top + 18;
  el.style.left = `${Math.round(left)}px`;
  el.style.top = `${Math.round(top)}px`;
}
