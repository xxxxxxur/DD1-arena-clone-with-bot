/**
 * Lightweight, faithful Spine 2.1.27 Canvas2D Runtime for Darkest Dungeon: The Butcher's Circus.
 * Supports:
 *  - .atlas parsing (rotated & non-rotated regions, offsets, orig sizes)
 *  - Hierarchical bone transforms (translate, rotate, scale, flipX, flipY, inheritRotation/Scale)
 *  - Animation timelines (rotate, translate, scale, flipX/Y, slot color, slot attachment, drawOrder, FFD)
 *  - Linear, Stepped, and Cubic Bezier curve interpolation
 *  - Region attachments, Mesh attachments, and SkinnedMesh (weighted bone) attachments with affine triangle UV mapping
 *  - Additive blending ('lighter') and RGBA slot tinting
 */

import { getSpineBatch } from "./spine_batch.js";

export function parseAtlas(text) {
  const lines = text.replace(/\r/g, "").split("\n");
  const regions = {};
  let pageName = "";
  let pageSize = [1, 1];
  let current = null;
  let expectingPage = true;

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const line = raw.trim();
    if (!line) {
      expectingPage = true;
      current = null;
      continue;
    }
    if (expectingPage) {
      pageName = line;
      expectingPage = false;
      continue;
    }
    if (!raw.startsWith(" ") && !raw.startsWith("\t")) {
      const colonIdx = line.indexOf(":");
      if (colonIdx !== -1 && !current) {
        const key = line.slice(0, colonIdx).trim();
        const val = line.slice(colonIdx + 1).trim();
        if (key === "size") {
          const [w, h] = val.split(",").map((n) => parseInt(n.trim(), 10));
          pageSize = [w || 1, h || 1];
        }
        continue;
      }
      // New region name
      current = {
        name: line,
        page: pageName,
        rotate: false,
        x: 0,
        y: 0,
        w: 0,
        h: 0,
        origW: 0,
        origH: 0,
        offsetX: 0,
        offsetY: 0,
      };
      regions[line] = current;
      continue;
    }
    if (current) {
      const colonIdx = line.indexOf(":");
      if (colonIdx === -1) continue;
      const key = line.slice(0, colonIdx).trim();
      const val = line.slice(colonIdx + 1).trim();
      if (key === "rotate") {
        current.rotate = val === "true";
      } else if (key === "xy") {
        const [x, y] = val.split(",").map((n) => parseInt(n.trim(), 10));
        current.x = x;
        current.y = y;
      } else if (key === "size") {
        const [w, h] = val.split(",").map((n) => parseInt(n.trim(), 10));
        current.w = w;
        current.h = h;
      } else if (key === "orig") {
        const [w, h] = val.split(",").map((n) => parseInt(n.trim(), 10));
        current.origW = w;
        current.origH = h;
      } else if (key === "offset") {
        const [ox, oy] = val.split(",").map((n) => parseInt(n.trim(), 10));
        current.offsetX = ox;
        current.offsetY = oy;
      }
    }
  }
  return { pageName, pageSize, regions };
}

function parseHexColor(hex) {
  if (!hex || hex.length < 6) return [1, 1, 1, 1];
  const r = parseInt(hex.slice(0, 2), 16) / 255;
  const g = parseInt(hex.slice(2, 4), 16) / 255;
  const b = parseInt(hex.slice(4, 6), 16) / 255;
  const a = hex.length >= 8 ? parseInt(hex.slice(6, 8), 16) / 255 : 1;
  return [r, g, b, a];
}

function evalCurve(curve, percent) {
  if (!curve) return percent;
  if (curve === "stepped") return 0;
  if (Array.isArray(curve) && curve.length === 4) {
    const [cx1, cy1, cx2, cy2] = curve;
    // Newton-Raphson / binary search solve cubic bezier x(t) = percent
    let lo = 0, hi = 1, t = percent;
    for (let i = 0; i < 8; i++) {
      const u = 1 - t;
      const x = 3 * u * u * t * cx1 + 3 * u * t * t * cx2 + t * t * t;
      if (Math.abs(x - percent) < 1e-4) break;
      if (x < percent) lo = t;
      else hi = t;
      t = (lo + hi) * 0.5;
    }
    const u = 1 - t;
    return 3 * u * u * t * cy1 + 3 * u * t * t * cy2 + t * t * t;
  }
  return percent;
}

function findFrameIndex(frames, time) {
  let lo = 0;
  let hi = frames.length - 1;
  if (time < frames[0].time) return -1;
  if (time >= frames[hi].time) return hi;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (frames[mid].time <= time) lo = mid + 1;
    else hi = mid - 1;
  }
  return Math.max(0, lo - 1);
}

export class SpineInstance {
  constructor(json, atlas, image) {
    this.meshBuffers = new WeakMap();
    this.json = json;
    this.atlas = atlas;
    this.image = image;

    this.bonesData = json.bones || [];
    this.slotsData = json.slots || [];
    this.skins = json.skins || {};
    this.animations = json.animations || {};

    this.boneIndexByName = {};
    this.bones = this.bonesData.map((b, i) => {
      this.boneIndexByName[b.name] = i;
      return {
        data: b,
        parent: -1,
        x: b.x || 0,
        y: b.y || 0,
        rotation: b.rotation || 0,
        scaleX: b.scaleX !== undefined ? b.scaleX : 1,
        scaleY: b.scaleY !== undefined ? b.scaleY : 1,
        flipX: !!b.flipX,
        flipY: !!b.flipY,
        // World transform matrix [m00, m01, m10, m11, worldX, worldY]
        m00: 1, m01: 0, m10: 0, m11: 1,
        worldX: 0, worldY: 0,
        worldRotation: 0,
        worldScaleX: 1, worldScaleY: 1,
      };
    });
    for (let i = 0; i < this.bones.length; i++) {
      const pName = this.bones[i].data.parent;
      if (pName !== undefined && this.boneIndexByName[pName] !== undefined) {
        this.bones[i].parent = this.boneIndexByName[pName];
      }
    }

    this.slotIndexByName = {};
    this.slots = this.slotsData.map((s, i) => {
      this.slotIndexByName[s.name] = i;
      return {
        data: s,
        boneIndex: this.boneIndexByName[s.bone] ?? 0,
        setupColor: parseHexColor(s.color || "ffffffff"),
        color: null,
        attachment: s.attachment || null,
        additive: !!s.additive,
        ffdOffsets: null,
      };
    });
    this.drawOrder = this.slots.map((_, i) => i);

    const animNames = Object.keys(this.animations);
    this.currentAnimName = animNames[0] || null;
    this.time = 0;
    this.loop = true;
    this.duration = this._calcDuration(this.currentAnimName);
    this.finished = false;
    this.update(0);
  }

  _calcDuration(animName) {
    const anim = animName ? this.animations[animName] : null;
    if (!anim) return 1.0;
    let maxT = 0;
    const scan = (frames) => {
      if (Array.isArray(frames) && frames.length > 0) {
        const t = frames[frames.length - 1].time || 0;
        if (t > maxT) maxT = t;
      }
    };
    if (anim.bones) {
      for (const btl of Object.values(anim.bones)) {
        for (const frames of Object.values(btl)) scan(frames);
      }
    }
    if (anim.slots) {
      for (const stl of Object.values(anim.slots)) {
        for (const frames of Object.values(stl)) scan(frames);
      }
    }
    if (anim.ffd) {
      for (const skinObj of Object.values(anim.ffd)) {
        for (const slotObj of Object.values(skinObj)) {
          for (const frames of Object.values(slotObj)) scan(frames);
        }
      }
    }
    if (anim.drawOrder) scan(anim.drawOrder);
    if (anim.events) scan(anim.events);
    return maxT > 0.01 ? maxT : 0.6;
  }

  setAnimation(name, loop = true) {
    const chosen = this.animations[name] ? name : Object.keys(this.animations)[0] || null;
    this.currentAnimName = chosen;
    this.loop = loop;
    this.time = 0;
    this.finished = false;
    this.duration = this._calcDuration(chosen);
    this.update(0);
  }

  setToSetupPose() {
    for (const b of this.bones) {
      const d = b.data;
      b.x = d.x || 0;
      b.y = d.y || 0;
      b.rotation = d.rotation || 0;
      b.scaleX = d.scaleX !== undefined ? d.scaleX : 1;
      b.scaleY = d.scaleY !== undefined ? d.scaleY : 1;
      b.flipX = !!d.flipX;
      b.flipY = !!d.flipY;
    }
    for (let i = 0; i < this.slots.length; i++) {
      const s = this.slots[i];
      s.color = s.setupColor;
      s.attachment = s.data.attachment || null;
      s.ffdOffsets = null;
      this.drawOrder[i] = i;
    }
  }

  update(dt) {
    if (this.duration > 0) {
      this.time += dt;
      if (this.loop) {
        this.time = this.time % this.duration;
      } else if (this.time >= this.duration) {
        this.time = this.duration;
        this.finished = true;
      }
    }
    this.setToSetupPose();
    this._applyAnimation(this.currentAnimName, this.time);
    this._updateWorldTransforms();
  }

  _applyAnimation(animName, time) {
    const anim = animName ? this.animations[animName] : null;
    if (!anim) return;

    // Bone timelines
    if (anim.bones) {
      for (const [bname, btl] of Object.entries(anim.bones)) {
        const bi = this.boneIndexByName[bname];
        if (bi === undefined) continue;
        const bone = this.bones[bi];
        const d = bone.data;

        if (btl.rotate && btl.rotate.length) {
          const frames = btl.rotate;
          const idx = findFrameIndex(frames, time);
          if (idx >= 0) {
            let angle;
            if (idx === frames.length - 1) {
              angle = frames[idx].angle || 0;
            } else {
              const f0 = frames[idx], f1 = frames[idx + 1];
              const pct = evalCurve(f0.curve, (time - f0.time) / Math.max(1e-6, f1.time - f0.time));
              let diff = (f1.angle || 0) - (f0.angle || 0);
              while (diff > 180) diff -= 360;
              while (diff < -180) diff += 360;
              angle = (f0.angle || 0) + diff * pct;
            }
            bone.rotation = (d.rotation || 0) + angle;
          }
        }

        if (btl.translate && btl.translate.length) {
          const frames = btl.translate;
          const idx = findFrameIndex(frames, time);
          if (idx >= 0) {
            let tx, ty;
            if (idx === frames.length - 1) {
              tx = frames[idx].x || 0;
              ty = frames[idx].y || 0;
            } else {
              const f0 = frames[idx], f1 = frames[idx + 1];
              const pct = evalCurve(f0.curve, (time - f0.time) / Math.max(1e-6, f1.time - f0.time));
              tx = (f0.x || 0) + ((f1.x || 0) - (f0.x || 0)) * pct;
              ty = (f0.y || 0) + ((f1.y || 0) - (f0.y || 0)) * pct;
            }
            bone.x = (d.x || 0) + tx;
            bone.y = (d.y || 0) + ty;
          }
        }

        if (btl.scale && btl.scale.length) {
          const frames = btl.scale;
          const idx = findFrameIndex(frames, time);
          if (idx >= 0) {
            let sx, sy;
            if (idx === frames.length - 1) {
              sx = frames[idx].x !== undefined ? frames[idx].x : 1;
              sy = frames[idx].y !== undefined ? frames[idx].y : 1;
            } else {
              const f0 = frames[idx], f1 = frames[idx + 1];
              const pct = evalCurve(f0.curve, (time - f0.time) / Math.max(1e-6, f1.time - f0.time));
              const x0 = f0.x !== undefined ? f0.x : 1;
              const y0 = f0.y !== undefined ? f0.y : 1;
              const x1 = f1.x !== undefined ? f1.x : 1;
              const y1 = f1.y !== undefined ? f1.y : 1;
              sx = x0 + (x1 - x0) * pct;
              sy = y0 + (y1 - y0) * pct;
            }
            bone.scaleX = (d.scaleX !== undefined ? d.scaleX : 1) * sx;
            bone.scaleY = (d.scaleY !== undefined ? d.scaleY : 1) * sy;
          }
        }

        if (btl.flipX && btl.flipX.length) {
          const idx = findFrameIndex(btl.flipX, time);
          if (idx >= 0) bone.flipX = !!btl.flipX[idx].flipX;
        }
        if (btl.flipY && btl.flipY.length) {
          const idx = findFrameIndex(btl.flipY, time);
          if (idx >= 0) bone.flipY = !!btl.flipY[idx].flipY;
        }
      }
    }

    // Slot timelines
    if (anim.slots) {
      for (const [sname, stl] of Object.entries(anim.slots)) {
        const si = this.slotIndexByName[sname];
        if (si === undefined) continue;
        const slot = this.slots[si];

        if (stl.attachment && stl.attachment.length) {
          const idx = findFrameIndex(stl.attachment, time);
          if (idx >= 0) slot.attachment = stl.attachment[idx].name ?? null;
        }

        if (stl.color && stl.color.length) {
          const frames = stl.color;
          const idx = findFrameIndex(frames, time);
          if (idx >= 0) {
            if (idx === frames.length - 1) {
              slot.color = parseHexColor(frames[idx].color);
            } else {
              const f0 = frames[idx], f1 = frames[idx + 1];
              const c0 = parseHexColor(f0.color);
              const c1 = parseHexColor(f1.color);
              const pct = evalCurve(f0.curve, (time - f0.time) / Math.max(1e-6, f1.time - f0.time));
              slot.color = [
                c0[0] + (c1[0] - c0[0]) * pct,
                c0[1] + (c1[1] - c0[1]) * pct,
                c0[2] + (c1[2] - c0[2]) * pct,
                c0[3] + (c1[3] - c0[3]) * pct,
              ];
            }
          }
        }
      }
    }

    // FFD timelines
    if (anim.ffd && anim.ffd.default) {
      for (const [sname, attMap] of Object.entries(anim.ffd.default)) {
        const si = this.slotIndexByName[sname];
        if (si === undefined) continue;
        const slot = this.slots[si];
        const frames = attMap[slot.attachment];
        if (!frames || !frames.length) continue;
        const idx = findFrameIndex(frames, time);
        if (idx < 0) continue;
        const f0 = frames[idx];
        const off0 = f0.offset || 0;
        const v0 = f0.vertices || [];
        if (idx === frames.length - 1) {
          slot.ffdOffsets = { offset: off0, vertices: v0 };
        } else {
          const f1 = frames[idx + 1];
          const off1 = f1.offset || 0;
          const v1 = f1.vertices || [];
          const pct = evalCurve(f0.curve, (time - f0.time) / Math.max(1e-6, f1.time - f0.time));
          const maxLen = Math.max(off0 + v0.length, off1 + v1.length);
          const blended = new Float32Array(maxLen);
          for (let k = 0; k < maxLen; k++) {
            const a = k >= off0 && k < off0 + v0.length ? v0[k - off0] : 0;
            const b = k >= off1 && k < off1 + v1.length ? v1[k - off1] : 0;
            blended[k] = a + (b - a) * pct;
          }
          slot.ffdOffsets = { offset: 0, vertices: blended };
        }
      }
    }

    // Draw order
    if (anim.drawOrder && anim.drawOrder.length) {
      const idx = findFrameIndex(anim.drawOrder, time);
      if (idx >= 0 && anim.drawOrder[idx].offsets && anim.drawOrder[idx].offsets.length) {
        const n = this.slots.length;
        const order = new Array(n).fill(-1);
        const unchanged = [];
        const moved = new Set();
        for (const o of anim.drawOrder[idx].offsets) {
          const si = this.slotIndexByName[o.slot];
          if (si === undefined) continue;
          const targetIdx = Math.max(0, Math.min(n - 1, si + o.offset));
          order[targetIdx] = si;
          moved.add(si);
        }
        for (let i = 0; i < n; i++) {
          if (!moved.has(i)) unchanged.push(i);
        }
        let ui = 0;
        for (let i = 0; i < n; i++) {
          if (order[i] === -1) order[i] = unchanged[ui++];
        }
        this.drawOrder = order;
      }
    }
  }

  _updateWorldTransforms() {
    for (let i = 0; i < this.bones.length; i++) {
      const b = this.bones[i];
      const d = b.data;
      const sx = b.scaleX * (b.flipX ? -1 : 1);
      const sy = b.scaleY * (b.flipY ? -1 : 1);
      const rad = (b.rotation * Math.PI) / 180;
      const cos = Math.cos(rad);
      const sin = Math.sin(rad);

      if (b.parent < 0) {
        b.worldX = b.x;
        b.worldY = b.y;
        b.m00 = cos * sx;
        b.m01 = -sin * sy;
        b.m10 = sin * sx;
        b.m11 = cos * sy;
      } else {
        const p = this.bones[b.parent];
        b.worldX = b.x * p.m00 + b.y * p.m01 + p.worldX;
        b.worldY = b.x * p.m10 + b.y * p.m11 + p.worldY;
        const inheritScale = d.inheritScale !== false;
        const inheritRot = d.inheritRotation !== false;
        if (inheritScale && inheritRot) {
          const lm00 = cos * sx, lm01 = -sin * sy;
          const lm10 = sin * sx, lm11 = cos * sy;
          b.m00 = p.m00 * lm00 + p.m01 * lm10;
          b.m01 = p.m00 * lm01 + p.m01 * lm11;
          b.m10 = p.m10 * lm00 + p.m11 * lm10;
          b.m11 = p.m10 * lm01 + p.m11 * lm11;
        } else {
          b.m00 = cos * sx;
          b.m01 = -sin * sy;
          b.m10 = sin * sx;
          b.m11 = cos * sy;
        }
      }
    }
  }

  _getAttachment(slotName, attName) {
    if (!attName) return null;
    const defSkin = this.skins.default;
    if (defSkin && defSkin[slotName] && defSkin[slotName][attName]) {
      return defSkin[slotName][attName];
    }
    return null;
  }

  /** Return a bone's world position in skeleton-local Canvas Y-down pixels, or null if absent. */
  getBonePos(boneName) {
    const idx = this.boneIndexByName[boneName];
    if (idx === undefined) return null;
    const b = this.bones[idx];
    return b ? { x: b.worldX, y: -b.worldY } : null;
  }

  _findRegion(att, attName) {
    const key = att.path || att.name || attName;
    if (this.atlas.regions[key]) return this.atlas.regions[key];
    // Strip path prefix if needed
    const shortKey = key.split("/").pop();
    if (this.atlas.regions[shortKey]) return this.atlas.regions[shortKey];
    return null;
  }

  draw(ctx, x, y, scale = 1.0, flipX = false, alpha = 1.0) {
    if (!this.image || !this.image.complete || this.image.naturalWidth === 0) return;
    if (this.batch?.draw(ctx, this, x, y, scale, flipX, alpha)) return;
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(flipX ? -scale : scale, scale);

    for (let di = 0; di < this.drawOrder.length; di++) {
      const slot = this.slots[this.drawOrder[di]];
      if (!slot || !slot.attachment) continue;
      const slotAlpha = slot.color[3] * alpha;
      if (slotAlpha <= 0.005) continue;

      const att = this._getAttachment(slot.data.name, slot.attachment);
      if (!att || att.type === "boundingbox") continue;
      const region = this._findRegion(att, slot.attachment);
      if (!region) continue;

      ctx.save();
      ctx.globalAlpha = Math.max(0, Math.min(1, slotAlpha));
      if (slot.additive) {
        ctx.globalCompositeOperation = "lighter";
      }

      if (att.type === "region") {
        this._drawRegion(ctx, slot, att, region);
      } else if (att.type === "mesh" || att.type === "skinnedmesh") {
        this._drawMesh(ctx, slot, att, region);
      }
      ctx.restore();
    }

    ctx.restore();
  }

  _drawRegion(ctx, slot, att, region) {
    const b = this.bones[slot.boneIndex];
    // Convert Spine Y-up matrix to Canvas Y-down:
    // canvasX = worldX, canvasY = -worldY
    ctx.transform(b.m00, -b.m10, -b.m01, b.m11, b.worldX, -b.worldY);

    ctx.translate(att.x || 0, -(att.y || 0));
    if (att.rotation) ctx.rotate((-att.rotation * Math.PI) / 180);
    ctx.scale(att.scaleX !== undefined ? att.scaleX : 1, att.scaleY !== undefined ? att.scaleY : 1);

    const origW = region.origW || region.w;
    const origH = region.origH || region.h;
    const attW = att.width || origW;
    const attH = att.height || origH;
    const scaleX = attW / Math.max(1, origW);
    const scaleY = attH / Math.max(1, origH);

    // Offset from bottom-left of original uncropped image in Spine coords
    const offsetX = region.offsetX || 0;
    const offsetY = region.offsetY || 0;
    const drawX = (-origW * 0.5 + offsetX) * scaleX;
    const drawY = (-origH * 0.5 + (origH - offsetY - region.h)) * scaleY;
    const drawW = region.w * scaleX;
    const drawH = region.h * scaleY;

    if (region.rotate) {
      // Spine atlas rotated 90 deg CCW -> stored as (h x w) in texture, needs +90 deg CW to restore
      ctx.translate(drawX + drawW * 0.5, drawY + drawH * 0.5);
      ctx.rotate(Math.PI * 0.5);
      ctx.drawImage(
        this.image,
        region.x, region.y, region.h, region.w,
        -drawH * 0.5, -drawW * 0.5, drawH, drawW
      );
    } else {
      ctx.drawImage(
        this.image,
        region.x, region.y, region.w, region.h,
        drawX, drawY, drawW, drawH
      );
    }
  }

  _regionGeometry(slot, att, region) {
    let geom = this.meshBuffers.get(att);
    if (!geom || geom.region !== region) {
      const ow = region.origW || region.w, oh = region.origH || region.h;
      const sx = (att.width || ow) / ow, sy = (att.height || oh) / oh;
      const x = (-ow / 2 + region.offsetX) * sx;
      const y = (oh / 2 - region.offsetY - region.h) * sy;
      const w = region.w * sx, h = region.h * sy;
      const local = [x, y, x + w, y, x + w, y + h, x, y + h];
      const angle = (att.rotation || 0) * Math.PI / 180;
      const c = Math.cos(angle), s = Math.sin(angle);
      for (let i = 0; i < 8; i += 2) {
        const lx = local[i] * (att.scaleX ?? 1), ly = -local[i + 1] * (att.scaleY ?? 1);
        local[i] = (att.x || 0) + c * lx - s * ly;
        local[i + 1] = (att.y || 0) + s * lx + c * ly;
      }
      const u = region.x, v = region.y;
      const texXY = region.rotate
        ? [u, v + region.w, u, v, u + region.h, v, u + region.h, v + region.w]
        : [u, v, u + region.w, v, u + region.w, v + region.h, u, v + region.h];
      geom = { region, local, texXY, worldXY: new Float32Array(8) };
      this.meshBuffers.set(att, geom);
    }
    const b = this.bones[slot.boneIndex];
    for (let i = 0; i < 8; i += 2) {
      const x = geom.local[i], y = geom.local[i + 1];
      geom.worldXY[i] = b.m00 * x + b.m01 * y + b.worldX;
      geom.worldXY[i + 1] = -(b.m10 * x + b.m11 * y + b.worldY);
    }
    return geom;
  }

  _meshGeometry(slot, att, region) {
    const uvs = att.uvs;
    const triangles = att.triangles;
    const verts = att.vertices;
    if (!uvs || !triangles || !verts) return;

    const numVerts = uvs.length >> 1;
    let buffers = this.meshBuffers.get(att);
    if (!buffers || buffers.region !== region) {
      const texXY = new Float32Array(uvs.length);
      for (let i = 0; i < uvs.length; i += 2) {
        const u = uvs[i], v = uvs[i + 1];
        texXY[i] = region.x + (region.rotate ? v * region.h : u * region.w);
        texXY[i + 1] = region.y + (region.rotate ? (1 - u) * region.w : v * region.h);
      }
      buffers = { region, texXY, worldXY: new Float32Array(uvs.length) };
      this.meshBuffers.set(att, buffers);
    }
    const { worldXY, texXY } = buffers;
    const ffd = slot.ffdOffsets;

    if (att.type === "mesh") {
      const b = this.bones[slot.boneIndex];
      for (let i = 0; i < numVerts; i++) {
        let vx = verts[i * 2];
        let vy = verts[i * 2 + 1];
        if (ffd) {
          const k0 = i * 2 - ffd.offset;
          if (k0 >= 0 && k0 + 1 < ffd.vertices.length) {
            vx += ffd.vertices[k0];
            vy += ffd.vertices[k0 + 1];
          }
        }
        worldXY[i * 2] = vx * b.m00 + vy * b.m01 + b.worldX;
        worldXY[i * 2 + 1] = -(vx * b.m10 + vy * b.m11 + b.worldY);
      }
    } else {
      // SkinnedMesh: vertices = [boneCount, boneIdx, vx, vy, weight, ...]
      let idx = 0;
      let ffdCursor = 0;
      for (let i = 0; i < numVerts; i++) {
        const bc = verts[idx++] | 0;
        let wx = 0, wy = 0;
        for (let k = 0; k < bc; k++) {
          const bi = verts[idx++] | 0;
          let vx = verts[idx++];
          let vy = verts[idx++];
          const weight = verts[idx++];
          if (ffd) {
            const fk = ffdCursor - ffd.offset;
            if (fk >= 0 && fk + 1 < ffd.vertices.length) {
              vx += ffd.vertices[fk];
              vy += ffd.vertices[fk + 1];
            }
            ffdCursor += 2;
          }
          const b = this.bones[bi];
          if (b) {
            wx += (vx * b.m00 + vy * b.m01 + b.worldX) * weight;
            wy += (vx * b.m10 + vy * b.m11 + b.worldY) * weight;
          }
        }
        worldXY[i * 2] = wx;
        worldXY[i * 2 + 1] = -wy;
      }
    }

    return buffers;
  }

  _drawMesh(ctx, slot, att, region) {
    const geom = this._meshGeometry(slot, att, region);
    if (!geom) return;
    const { worldXY, texXY } = geom;
    const triangles = att.triangles;
    // Draw each triangle via affine UV -> XY mapping
    for (let t = 0; t < triangles.length; t += 3) {
      const i0 = triangles[t], i1 = triangles[t + 1], i2 = triangles[t + 2];
      const x0 = worldXY[i0 * 2], y0 = worldXY[i0 * 2 + 1];
      const x1 = worldXY[i1 * 2], y1 = worldXY[i1 * 2 + 1];
      const x2 = worldXY[i2 * 2], y2 = worldXY[i2 * 2 + 1];

      const u0 = texXY[i0 * 2], v0 = texXY[i0 * 2 + 1];
      const u1 = texXY[i1 * 2], v1 = texXY[i1 * 2 + 1];
      const u2 = texXY[i2 * 2], v2 = texXY[i2 * 2 + 1];

      const denom = (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0);
      if (Math.abs(denom) < 1e-5) continue;

      const m11 = ((x1 - x0) * (v2 - v0) - (x2 - x0) * (v1 - v0)) / denom;
      const m12 = ((x2 - x0) * (u1 - u0) - (x1 - x0) * (u2 - u0)) / denom;
      const m21 = ((y1 - y0) * (v2 - v0) - (y2 - y0) * (v1 - v0)) / denom;
      const m22 = ((y2 - y0) * (u1 - u0) - (y1 - y0) * (u2 - u0)) / denom;
      const dx = x0 - m11 * u0 - m12 * v0;
      const dy = y0 - m21 * u0 - m22 * v0;

      // Expand clip triangle slightly by 0.4px from centroid to hide Canvas2D anti-aliasing seams
      const cx = (x0 + x1 + x2) / 3;
      const cy = (y0 + y1 + y2) / 3;
      const pad0 = 0.45 / (Math.hypot(x0 - cx, y0 - cy) || 1);
      const pad1 = 0.45 / (Math.hypot(x1 - cx, y1 - cy) || 1);
      const pad2 = 0.45 / (Math.hypot(x2 - cx, y2 - cy) || 1);

      ctx.save();
      ctx.beginPath();
      ctx.moveTo(x0 + (x0 - cx) * pad0, y0 + (y0 - cy) * pad0);
      ctx.lineTo(x1 + (x1 - cx) * pad1, y1 + (y1 - cy) * pad1);
      ctx.lineTo(x2 + (x2 - cx) * pad2, y2 + (y2 - cy) * pad2);
      ctx.closePath();
      ctx.clip();
      ctx.transform(m11, m21, m12, m22, dx, dy);
      const w = region.rotate ? region.h : region.w;
      const h = region.rotate ? region.w : region.h;
      ctx.drawImage(this.image, region.x, region.y, w, h, region.x, region.y, w, h);
      ctx.restore();
    }
  }
}

export class SpineAssetCache {
  constructor() {
    this._cache = new Map();
    this._pending = new Map();
  }

  async load(key, jsonUrl, atlasUrl, texUrl) {
    if (this._cache.has(key)) return this._cache.get(key);
    if (this._pending.has(key)) return this._pending.get(key);

    const prom = (async () => {
      try {
        const [jsonRes, atlasRes] = await Promise.all([fetch(jsonUrl), fetch(atlasUrl)]);
        if (!jsonRes.ok || !atlasRes.ok) return null;
        const [json, atlasText] = await Promise.all([jsonRes.json(), atlasRes.text()]);
        const atlas = parseAtlas(atlasText);
        const img = await new Promise((resolve, reject) => {
          const im = new Image();
          im.crossOrigin = "anonymous";
          im.onload = () => resolve(im);
          im.onerror = () => reject(new Error("Image load failed"));
          im.src = texUrl;
        });
        const bundle = { json, atlas, image: img };
        this._cache.set(key, bundle);
        return bundle;
      } catch {
        this._cache.set(key, null);
        return null;
      } finally {
        this._pending.delete(key);
      }
    })();

    this._pending.set(key, prom);
    return prom;
  }

  createHeroAnimSync(heroId, anim, variant = "A") {
    const bundle = this._cache.get(`hero:${heroId}:${variant}:${anim}`);
    if (!bundle) return null;
    const rig = new SpineInstance(bundle.json, bundle.atlas, bundle.image);
    rig.batch = getSpineBatch();
    return rig;
  }

  async createHeroAnim(heroId, anim, variant = "A") {
    const sync = this.createHeroAnimSync(heroId, anim, variant);
    if (sync) return sync;
    const key = `hero:${heroId}:${variant}:${anim}`;
    const bundle = await this.load(
      key,
      `/api/spine/hero/${heroId}/${anim}`,
      `/api/atlas/hero/${heroId}/${anim}`,
      `/api/texture/hero/${heroId}/${variant}/${anim}`
    );
    if (!bundle) return null;
    const rig = new SpineInstance(bundle.json, bundle.atlas, bundle.image);
    rig.batch = getSpineBatch();
    return rig;
  }

  createHeroFxSync(heroId, fxName) {
    if (!fxName) return null;
    const bundle = this._cache.get(`herofx:${heroId}:${fxName}`);
    return bundle ? new SpineInstance(bundle.json, bundle.atlas, bundle.image) : null;
  }

  async createHeroFx(heroId, fxName) {
    const sync = this.createHeroFxSync(heroId, fxName);
    if (sync) return sync;
    if (!fxName) return null;
    const key = `herofx:${heroId}:${fxName}`;
    const bundle = await this.load(
      key,
      `/api/spine/herofx/${heroId}/${fxName}`,
      `/api/atlas/herofx/${heroId}/${fxName}`,
      `/api/texture/herofx/${heroId}/${fxName}`
    );
    return bundle ? new SpineInstance(bundle.json, bundle.atlas, bundle.image) : null;
  }

  createGlobalFxSync(fxName) {
    if (!fxName) return null;
    const bundle = this._cache.get(`fx:${fxName}`);
    return bundle ? new SpineInstance(bundle.json, bundle.atlas, bundle.image) : null;
  }

  async createGlobalFx(fxName) {
    const sync = this.createGlobalFxSync(fxName);
    if (sync) return sync;
    if (!fxName) return null;
    const key = `fx:${fxName}`;
    const bundle = await this.load(
      key,
      `/api/spine/fx/${fxName}`,
      `/api/atlas/fx/${fxName}`,
      `/api/texture/fx/${fxName}`
    );
    return bundle ? new SpineInstance(bundle.json, bundle.atlas, bundle.image) : null;
  }
}
