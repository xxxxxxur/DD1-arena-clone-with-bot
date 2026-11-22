/**
 * Authentic Butcher's Circus Town Hub Renderer (1920x1080 virtual canvas).
 * Uses real game assets from dlc/1117860_arena_mp/campaign/town/ and fx/town_*
 * positioned according to arena.town.layout.darkest.
 */
import { SpineAssetCache } from "./spine21.js";

const TOWN_W = 1920;
const TOWN_H = 1080;

// Interactive Circus buildings mapped from arena.town.layout.darkest
const BUILDINGS = [
  {
    id: "dueling_grounds",
    fx: "town_dueling_grounds_level01",
    x: 345,
    y: 775,
    scale: 0.92,
    hit: { x: 115, y: 310, w: 460, h: 485 },
    label_zh: "决斗场 · 本地双开 PvP",
    label_en: "DUELING GROUNDS · LOCAL 2P PVP",
    sub_zh: "创建房间或一键双开窗口进行双人对抗",
    sub_en: "Create room & open Player 2 window for local PvP",
  },
  {
    id: "fight",
    fx: "town_fight_level01",
    x: 960,
    y: 660,
    scale: 0.96,
    hit: { x: 640, y: 150, w: 640, h: 520 },
    label_zh: "血腥擂台 · 单人对战 Bot",
    label_en: "THE RING · PLAY VS BOT",
    sub_zh: "进入配队界面，组建 4 人战队挑战竞技场 AI",
    sub_en: "Enter Party Setup & battle Arena AI bots",
  },
  {
    id: "rankings",
    fx: "town_rankings_level01",
    x: 1415,
    y: 775,
    scale: 0.94,
    hit: { x: 1275, y: 365, w: 280, h: 425 },
    label_zh: "排行榜 · 无头 Bot 锦标赛 & 录像",
    label_en: "RANKINGS · BOT TOURNAMENT & REPLAYS",
    sub_zh: "纯 Java 26 引擎高速跑分，查看 ELO 与录像回放",
    sub_en: "Run pure Java 26 headless tournaments & watch replays",
  },
  {
    id: "prize_booth",
    fx: "town_prize_booth_level01",
    x: 1755,
    y: 790,
    scale: 0.92,
    hit: { x: 1575, y: 360, w: 330, h: 440 },
    label_zh: "奖品亭 · 实时 Bot vs Bot 观战",
    label_en: "PRIZE BOOTH · LIVE BOT VS BOT",
    sub_zh: "实时观战两支 AI 在完整动画角斗场中厮杀",
    sub_en: "Spectate two AI squads fighting live in the Ring",
  },
];

export class CircusTownScene {
  constructor(canvas, onSelectBuilding) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.onSelectBuilding = onSelectBuilding;
    this.spineCache = new SpineAssetCache();
    this.lang = "zh";
    this.active = true;
    this.hoveredId = null;

    this.layers = {};
    this.buildingSpines = new Map();
    this.ambientSpines = [];

    this._loadAssets();
    this._bindPointer();
    this._lastTs = performance.now();
    this._raf = requestAnimationFrame((ts) => this._loop(ts));
  }

  setLanguage(lang) {
    this.lang = lang;
  }

  setActive(isActive) {
    this.active = isActive;
  }

  _loadImage(key, relPath) {
    const img = new Image();
    img.src = `/api/game_asset/${relPath}`;
    img.onload = () => {
      this.layers[key] = img;
    };
  }

  async _loadAssets() {
    this._loadImage("bg", "campaign/town/circus_bg.png");
    this._loadImage("tent", "campaign/town/circus_tent.png");
    this._loadImage("stage", "campaign/town/circus_stage.png");
    this._loadImage("crowd", "campaign/town/circus_crowd.png");

    // Load interactive building Spine skeletons
    for (const b of BUILDINGS) {
      const sp = await this.spineCache.createGlobalFx(b.fx);
      if (sp) {
        sp.setAnimation("idle", true);
        this.buildingSpines.set(b.id, { spine: sp, mode: "idle", def: b });
      }
    }

    // Load animated ambient characters & circus particles
    const ambSpecs = [
      { fx: "town_circus_ambience", x: 960, y: 500, scale: 1.0, layer: "back" },
      { fx: "town_ushers", x: 960, y: 800, scale: 0.92, layer: "front" },
      { fx: "town_punch_and_judy", x: 350, y: 855, scale: 0.88, layer: "front" },
      { fx: "town_ringmaster", x: 1625, y: 865, scale: 0.92, layer: "front" },
    ];
    for (const spec of ambSpecs) {
      const sp = await this.spineCache.createGlobalFx(spec.fx);
      if (sp) {
        sp.setAnimation(Object.keys(sp.animations)[0] || spec.fx, true);
        this.ambientSpines.push({ ...spec, spine: sp });
      }
    }
  }

  _screenToTown(clientX, clientY) {
    const rect = this.canvas.getBoundingClientRect();
    return {
      x: ((clientX - rect.left) / Math.max(1, rect.width)) * TOWN_W,
      y: ((clientY - rect.top) / Math.max(1, rect.height)) * TOWN_H,
    };
  }

  _hitTest(tx, ty) {
    for (const b of BUILDINGS) {
      const h = b.hit;
      if (tx >= h.x && tx <= h.x + h.w && ty >= h.y && ty <= h.y + h.h) {
        return b;
      }
    }
    return null;
  }

  _bindPointer() {
    this.canvas.addEventListener("mousemove", (e) => {
      if (!this.active) return;
      const { x, y } = this._screenToTown(e.clientX, e.clientY);
      const hit = this._hitTest(x, y);
      const newId = hit ? hit.id : null;
      if (newId !== this.hoveredId) {
        this.hoveredId = newId;
        this.canvas.style.cursor = newId ? "pointer" : "default";
        for (const [id, entry] of this.buildingSpines.entries()) {
          const want = id === newId ? "active" : "idle";
          if (entry.mode !== want) {
            entry.mode = want;
            entry.spine.setAnimation(want, true);
          }
        }
      }
    });

    this.canvas.addEventListener("click", (e) => {
      if (!this.active) return;
      const { x, y } = this._screenToTown(e.clientX, e.clientY);
      const hit = this._hitTest(x, y);
      if (hit && this.onSelectBuilding) {
        this.onSelectBuilding(hit.id);
      }
    });
  }

  _loop(ts) {
    const dt = Math.min(0.05, (ts - this._lastTs) / 1000);
    this._lastTs = ts;
    if (this.active) {
      for (const entry of this.buildingSpines.values()) entry.spine.update(dt);
      for (const amb of this.ambientSpines) amb.spine.update(dt);
      this._draw();
    }
    this._raf = requestAnimationFrame((t) => this._loop(t));
  }

  _draw() {
    const ctx = this.ctx;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const cw = this.canvas.clientWidth || TOWN_W;
    const ch = this.canvas.clientHeight || TOWN_H;
    if (this.canvas.width !== Math.round(cw * dpr) || this.canvas.height !== Math.round(ch * dpr)) {
      this.canvas.width = Math.round(cw * dpr);
      this.canvas.height = Math.round(ch * dpr);
    }

    ctx.save();
    ctx.scale((cw * dpr) / TOWN_W, (ch * dpr) / TOWN_H);
    ctx.clearRect(0, 0, TOWN_W, TOWN_H);

    // 1. Sky / Circus fairground background
    if (this.layers.bg) {
      ctx.drawImage(this.layers.bg, 0, 0, TOWN_W, TOWN_H);
    } else {
      ctx.fillStyle = "#12080a";
      ctx.fillRect(0, 0, TOWN_W, TOWN_H);
    }

    // 2. Back ambience particles + Big Top Tent
    if (this.layers.tent) {
      ctx.drawImage(this.layers.tent, 0, 0, TOWN_W, 956);
    }
    for (const amb of this.ambientSpines) {
      if (amb.layer === "back") amb.spine.draw(ctx, amb.x, amb.y, amb.scale, false, 0.9);
    }

    // 3. Center Big Top Ring (Fight)
    const fightEntry = this.buildingSpines.get("fight");
    if (fightEntry) {
      fightEntry.spine.draw(ctx, fightEntry.def.x, fightEntry.def.y, fightEntry.def.scale, false, 1.0);
    }

    // 4. Circus Stage floor
    if (this.layers.stage) {
      ctx.drawImage(this.layers.stage, 0, TOWN_H - 411, TOWN_W, 411);
    }

    // 5. Side Circus buildings (Dueling Grounds, Rankings, Prize Booth)
    for (const bid of ["dueling_grounds", "rankings", "prize_booth"]) {
      const entry = this.buildingSpines.get(bid);
      if (entry) {
        entry.spine.draw(ctx, entry.def.x, entry.def.y, entry.def.scale, false, 1.0);
      }
    }

    // 6. Animated NPCs (Ushers, Punch & Judy, Ringmaster)
    for (const amb of this.ambientSpines) {
      if (amb.layer === "front") amb.spine.draw(ctx, amb.x, amb.y, amb.scale, false, 1.0);
    }

    // 7. Foreground silhouetted Circus Crowd
    if (this.layers.crowd) {
      ctx.save();
      ctx.globalAlpha = 0.86;
      ctx.drawImage(this.layers.crowd, 0, 30, TOWN_W, TOWN_H);
      ctx.restore();
    }

    // 8. Building labels (subtle always-visible signs; bright gold + subtitle on hover!)
    for (const b of BUILDINGS) {
      const isHov = this.hoveredId === b.id;
      const cx = b.hit.x + b.hit.w * 0.5;
      const cy = b.id === "fight" ? 615 : 835;
      const title = this.lang === "zh" ? b.label_zh : b.label_en;
      const sub = this.lang === "zh" ? b.sub_zh : b.sub_en;

      ctx.save();
      const boxW = isHov ? 410 : 310;
      const boxH = isHov ? 66 : 38;
      ctx.fillStyle = isHov ? "rgba(38, 14, 14, 0.92)" : "rgba(14, 8, 9, 0.78)";
      ctx.strokeStyle = isHov ? "#f2cf7c" : "#6b5239";
      ctx.lineWidth = isHov ? 2.5 : 1.5;
      ctx.fillRect(cx - boxW * 0.5, cy - boxH * 0.5, boxW, boxH);
      ctx.strokeRect(cx - boxW * 0.5, cy - boxH * 0.5, boxW, boxH);

      ctx.textAlign = "center";
      ctx.font = `800 ${isHov ? 21 : 17}px "Cinzel", "Georgia", "Microsoft YaHei", serif`;
      ctx.fillStyle = isHov ? "#ffe191" : "#e6d2ad";
      ctx.fillText(title, cx, cy + (isHov ? -4 : 6));
      if (isHov) {
        ctx.font = `500 14px "Georgia", "Microsoft YaHei", sans-serif`;
        ctx.fillStyle = "#d8c7ab";
        ctx.fillText(sub, cx, cy + 20);
      }
      ctx.restore();
    }

    // Subtle gothic edge vignette
    const vig = ctx.createRadialGradient(TOWN_W * 0.5, TOWN_H * 0.45, 520, TOWN_W * 0.5, TOWN_H * 0.5, 1180);
    vig.addColorStop(0, "rgba(0,0,0,0)");
    vig.addColorStop(1, "rgba(0,0,0,0.62)");
    ctx.fillStyle = vig;
    ctx.fillRect(0, 0, TOWN_W, TOWN_H);

    ctx.restore();
  }
}
