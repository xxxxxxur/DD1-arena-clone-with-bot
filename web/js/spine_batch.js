// Draw a posed skeleton in slot order, batching its textured triangles by blend mode.
// The caller composites the result with its Canvas2D camera and lighting.
let shared;

export function getSpineBatch() {
  if (shared === undefined) {
    const canvas = document.createElement("canvas");
    const gl = canvas.getContext("webgl", { alpha: true, premultipliedAlpha: true, antialias: true, depth: false });
    shared = gl ? new SpineBatch(canvas, gl) : null;
    canvas.addEventListener("webglcontextlost", () => {
      if (shared) shared.ready = false;
      shared = null;
    });
  }
  return shared;
}

class SpineBatch {
  constructor(canvas, gl) {
    this.canvas = canvas;
    this.gl = gl;
    this.textures = new WeakMap();
    this.vertices = new Float32Array(8192);
    const program = gl.createProgram();
    for (const [type, source] of [
      [gl.VERTEX_SHADER, `attribute vec2 pos, uv;
        attribute vec4 color;
        uniform vec4 bounds;
        uniform vec2 imageSize;
        varying vec2 tex;
        varying vec4 tint;
        void main() {
          vec2 p = (pos - bounds.xy) / bounds.zw;
          gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
          tex = uv / imageSize;
          tint = color;
        }`],
      [gl.FRAGMENT_SHADER, `precision mediump float;
        uniform sampler2D atlas;
        varying vec2 tex;
        varying vec4 tint;
        void main() {
          vec4 pixel = texture2D(atlas, tex);
          gl_FragColor = vec4(pixel.rgb * tint.rgb, pixel.a) * tint.a;
        }`]
    ]) {
      const shader = gl.createShader(type);
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      gl.attachShader(program, shader);
      gl.deleteShader(shader);
    }
    gl.linkProgram(program);
    this.ready = gl.getProgramParameter(program, gl.LINK_STATUS);
    if (!this.ready) return;
    gl.useProgram(program);
    this.bounds = gl.getUniformLocation(program, "bounds");
    this.imageSize = gl.getUniformLocation(program, "imageSize");
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    for (const [name, size, offset] of [["pos", 2, 0], ["uv", 2, 8], ["color", 4, 16]]) {
      const loc = gl.getAttribLocation(program, name);
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, size, gl.FLOAT, false, 32, offset);
    }
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
    gl.enable(gl.BLEND);
    gl.enable(gl.SCISSOR_TEST);
  }

  draw(ctx, rig, x, y, scale, flip, alpha) {
    if (!this.ready) return false;
    const gl = this.gl;
    let used = 0, left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
    const groups = [];
    for (const index of rig.drawOrder) {
      const slot = rig.slots[index];
      if (!slot.attachment || slot.color[3] < 0.005) continue;
      const att = rig._getAttachment(slot.data.name, slot.attachment);
      if (!att || att.type === "boundingbox") continue;
      const region = rig._findRegion(att, slot.attachment);
      if (!region) continue;
      const quad = att.type === "region";
      const geom = quad ? rig._regionGeometry(slot, att, region) : rig._meshGeometry(slot, att, region);
      if (!geom) continue;
      const indices = quad ? QUAD : att.triangles;
      if (used + indices.length * 8 > this.vertices.length) {
        const larger = new Float32Array(Math.max(this.vertices.length * 2, used + indices.length * 8));
        larger.set(this.vertices);
        this.vertices = larger;
      }
      if (!groups.length || groups.at(-1).additive !== slot.additive) {
        groups.push({ first: used / 8, count: 0, additive: slot.additive });
      }
      groups.at(-1).count += indices.length;
      for (const vertex of indices) {
        const i = vertex * 2, vx = geom.worldXY[i], vy = geom.worldXY[i + 1];
        left = Math.min(left, vx); right = Math.max(right, vx);
        top = Math.min(top, vy); bottom = Math.max(bottom, vy);
        this.vertices[used++] = vx;
        this.vertices[used++] = vy;
        this.vertices[used++] = geom.texXY[i];
        this.vertices[used++] = geom.texXY[i + 1];
        for (let c = 0; c < 4; c++) this.vertices[used++] = slot.color[c];
      }
    }
    if (!used) return true;
    left = Math.floor(left) - 1; top = Math.floor(top) - 1;
    const w = Math.ceil(right) - left + 1, h = Math.ceil(bottom) - top + 1;
    const matrix = ctx.getTransform();
    const density = Math.min(2, Math.max(0.5, scale * Math.hypot(matrix.a, matrix.b)));
    const pw = Math.ceil(w * density), ph = Math.ceil(h * density);
    if (pw > 2048 || ph > 2048) return false;
    if (this.canvas.width < pw) this.canvas.width = Math.ceil(pw / 128) * 128;
    if (this.canvas.height < ph) this.canvas.height = Math.ceil(ph / 128) * 128;
    gl.viewport(0, this.canvas.height - ph, pw, ph);
    gl.scissor(0, this.canvas.height - ph, pw, ph);
    gl.clear(gl.COLOR_BUFFER_BIT);
    let tex = this.textures.get(rig.image);
    if (!tex) {
      tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, rig.image);
      for (const param of [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T]) gl.texParameteri(gl.TEXTURE_2D, param, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      this.textures.set(rig.image, tex);
    } else gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.uniform2f(this.imageSize, rig.image.width, rig.image.height);
    gl.uniform4f(this.bounds, left, top, w, h);
    gl.bufferData(gl.ARRAY_BUFFER, this.vertices.subarray(0, used), gl.STREAM_DRAW);
    for (const group of groups) {
      gl.blendFunc(gl.ONE, group.additive ? gl.ONE : gl.ONE_MINUS_SRC_ALPHA);
      gl.drawArrays(gl.TRIANGLES, group.first, group.count);
    }
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(flip ? -scale : scale, scale);
    ctx.globalAlpha *= alpha;
    ctx.drawImage(this.canvas, 0, 0, pw, ph, left, top, w, h);
    ctx.restore();
    return true;
  }
}

const QUAD = [0, 1, 2, 2, 3, 0];
