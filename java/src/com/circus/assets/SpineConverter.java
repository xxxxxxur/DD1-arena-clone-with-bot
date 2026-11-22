package com.circus.assets;

import com.circus.util.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full Spine 2.1.27 binary (.skel) to Spine JSON converter with disk & memory caching.
 * 100% faithful to assets/spine_converter.py.
 */
public final class SpineConverter {
    private static final int TIMELINE_SCALE = 0;
    private static final int TIMELINE_ROTATE = 1;
    private static final int TIMELINE_TRANSLATE = 2;
    private static final int TIMELINE_ATTACHMENT = 3;
    private static final int TIMELINE_COLOR = 4;
    private static final int TIMELINE_FLIPX = 5;
    private static final int TIMELINE_FLIPY = 6;

    private static final int CURVE_LINEAR = 0;
    private static final int CURVE_STEPPED = 1;
    private static final int CURVE_BEZIER = 2;

    private static final int ATTACHMENT_REGION = 0;
    private static final int ATTACHMENT_BOUNDING_BOX = 1;
    private static final int ATTACHMENT_MESH = 2;
    private static final int ATTACHMENT_SKINNED_MESH = 3;

    private static final Map<String, byte[]> MEM_CACHE = new ConcurrentHashMap<>();

    private SpineConverter() {}

    public static byte[] convertSkelFileToJsonBytes(Path skelPath, Path cacheDir) throws IOException {
        var attrs = Files.readAttributes(skelPath, java.nio.file.attribute.BasicFileAttributes.class);
        long mtimeNs = attrs.lastModifiedTime().toInstant().getEpochSecond() * 1_000_000_000L
                + attrs.lastModifiedTime().toInstant().getNano();
        long size = attrs.size();
        String cacheKey = skelPath.toAbsolutePath() + "|" + mtimeNs + "|" + size;

        byte[] cached = MEM_CACHE.get(cacheKey);
        if (cached != null) return cached;

        if (cacheDir != null) {
            Files.createDirectories(cacheDir);
            String sha = sha1Hex(cacheKey).substring(0, 16);
            String stem = skelPath.getFileName().toString();
            int dot = stem.lastIndexOf('.');
            if (dot > 0) stem = stem.substring(0, dot);
            Path cacheFile = cacheDir.resolve(stem + "_" + sha + ".json");
            if (Files.isRegularFile(cacheFile)) {
                byte[] bytes = Files.readAllBytes(cacheFile);
                MEM_CACHE.put(cacheKey, bytes);
                return bytes;
            }
            Map<String, Object> parsed = parseSpine21Skel(Files.readAllBytes(skelPath));
            byte[] jsonBytes = Json.stringify(parsed).getBytes(StandardCharsets.UTF_8);
            try {
                Path tmp = Files.createTempFile(cacheDir, "skel_", ".tmp");
                Files.write(tmp, jsonBytes);
                try {
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {}
            MEM_CACHE.put(cacheKey, jsonBytes);
            return jsonBytes;
        }

        Map<String, Object> parsed = parseSpine21Skel(Files.readAllBytes(skelPath));
        byte[] jsonBytes = Json.stringify(parsed).getBytes(StandardCharsets.UTF_8);
        MEM_CACHE.put(cacheKey, jsonBytes);
        return jsonBytes;
    }

    public static Map<String, Object> parseSpine21Skel(byte[] data) {
        BinaryReader r = new BinaryReader(data);

        String skelHash = nonNull(r.readString(), "");
        String version = nonNull(r.readString(), "2.1.27");
        double width = round5(r.readFloat());
        double height = round5(r.readFloat());
        boolean nonessential = r.readBool();
        String imagesPath = nonessential ? r.readString() : null;

        Map<String, Object> skeletonMeta = new LinkedHashMap<>();
        skeletonMeta.put("hash", skelHash);
        skeletonMeta.put("spine", version);
        skeletonMeta.put("width", width);
        skeletonMeta.put("height", height);
        if (imagesPath != null && !imagesPath.isEmpty()) skeletonMeta.put("images", imagesPath);

        // --- Bones ---
        int boneCount = r.readVarint(true);
        List<Map<String, Object>> bones = new ArrayList<>(boneCount);
        List<String> boneNames = new ArrayList<>(boneCount);
        for (int i = 0; i < boneCount; i++) {
            String name = nonNull(r.readString(), "");
            int parentIdx = r.readVarint(true) - 1;
            double x = round5(r.readFloat());
            double y = round5(r.readFloat());
            double scaleX = round5(r.readFloat());
            double scaleY = round5(r.readFloat());
            double rotation = round5(r.readFloat());
            double length = round5(r.readFloat());
            boolean flipX = r.readBool();
            boolean flipY = r.readBool();
            boolean inheritScale = r.readBool();
            boolean inheritRotation = r.readBool();
            String color = nonessential ? r.readColorHex() : null;

            Map<String, Object> bone = new LinkedHashMap<>();
            bone.put("name", name);
            if (parentIdx >= 0) bone.put("parent", boneNames.get(parentIdx));
            if (length != 0) bone.put("length", length);
            if (x != 0) bone.put("x", x);
            if (y != 0) bone.put("y", y);
            if (rotation != 0) bone.put("rotation", rotation);
            if (scaleX != 1) bone.put("scaleX", scaleX);
            if (scaleY != 1) bone.put("scaleY", scaleY);
            if (flipX) bone.put("flipX", true);
            if (flipY) bone.put("flipY", true);
            if (!inheritScale) bone.put("inheritScale", false);
            if (!inheritRotation) bone.put("inheritRotation", false);
            if (color != null && !color.isEmpty()) bone.put("color", color);
            bones.add(bone);
            boneNames.add(name);
        }

        // --- IK Constraints ---
        int ikCount = r.readVarint(true);
        List<Map<String, Object>> ikConstraints = new ArrayList<>(ikCount);
        List<String> ikNames = new ArrayList<>(ikCount);
        for (int i = 0; i < ikCount; i++) {
            String name = nonNull(r.readString(), "");
            int ikBoneCount = r.readVarint(true);
            List<String> ikBones = new ArrayList<>(ikBoneCount);
            for (int j = 0; j < ikBoneCount; j++) ikBones.add(boneNames.get(r.readVarint(true)));
            String target = boneNames.get(r.readVarint(true));
            double mix = round5(r.readFloat());
            int bendDir = r.readSByte();
            Map<String, Object> ikObj = new LinkedHashMap<>();
            ikObj.put("name", name);
            ikObj.put("bones", ikBones);
            ikObj.put("target", target);
            ikObj.put("mix", mix);
            ikObj.put("bendPositive", bendDir >= 0);
            ikConstraints.add(ikObj);
            ikNames.add(name);
        }

        // --- Slots ---
        int slotCount = r.readVarint(true);
        List<Map<String, Object>> slots = new ArrayList<>(slotCount);
        List<String> slotNames = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            String name = nonNull(r.readString(), "");
            int boneIdx = r.readVarint(true);
            String color = r.readColorHex();
            String attachment = r.readString();
            boolean additive = r.readBool();
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("name", name);
            slot.put("bone", boneNames.get(boneIdx));
            if (!"ffffffff".equals(color)) slot.put("color", color);
            if (attachment != null) slot.put("attachment", attachment);
            if (additive) slot.put("additive", true);
            slots.add(slot);
            slotNames.add(name);
        }

        // --- Skins ---
        Map<String, Map<String, Map<String, Object>>> skins = new LinkedHashMap<>();
        List<String> skinNames = new ArrayList<>();
        skins.put("default", readSkin(r, slotNames, nonessential));
        skinNames.add("default");

        int userSkinCount = r.readVarint(true);
        for (int i = 0; i < userSkinCount; i++) {
            String sname = nonNull(r.readString(), "");
            skins.put(sname, readSkin(r, slotNames, nonessential));
            skinNames.add(sname);
        }

        // --- Events ---
        int eventCount = r.readVarint(true);
        Map<String, Map<String, Object>> events = new LinkedHashMap<>();
        List<String> eventNames = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            String ename = nonNull(r.readString(), "");
            int eint = r.readVarint(false);
            double efloat = round5(r.readFloat());
            String estr = r.readString();
            Map<String, Object> ev = new LinkedHashMap<>();
            if (eint != 0) ev.put("int", eint);
            if (efloat != 0) ev.put("float", efloat);
            if (estr != null && !estr.isEmpty()) ev.put("string", estr);
            events.put(ename, ev);
            eventNames.add(ename);
        }

        // --- Animations ---
        int animCount = r.readVarint(true);
        Map<String, Map<String, Object>> animations = new LinkedHashMap<>();
        for (int ai = 0; ai < animCount; ai++) {
            String aname = nonNull(r.readString(), "animation");
            Map<String, Object> anim = new LinkedHashMap<>();

            // Slot timelines
            int slotTlCount = r.readVarint(true);
            if (slotTlCount > 0) {
                Map<String, Map<String, Object>> slotTls = new LinkedHashMap<>();
                for (int i = 0; i < slotTlCount; i++) {
                    String sname = slotNames.get(r.readVarint(true));
                    int tlCount = r.readVarint(true);
                    Map<String, Object> stl = new LinkedHashMap<>();
                    for (int j = 0; j < tlCount; j++) {
                        int tlType = r.readByte();
                        int frameCount = r.readVarint(true);
                        if (tlType == TIMELINE_COLOR) {
                            List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                            for (int fi = 0; fi < frameCount; fi++) {
                                double t = round5(r.readFloat());
                                String c = r.readColorHex();
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("time", t);
                                f.put("color", c);
                                if (fi < frameCount - 1) {
                                    Object curve = readCurve(r);
                                    if (curve != null) f.put("curve", curve);
                                }
                                frames.add(f);
                            }
                            stl.put("color", frames);
                        } else if (tlType == TIMELINE_ATTACHMENT) {
                            List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                            for (int fi = 0; fi < frameCount; fi++) {
                                double t = round5(r.readFloat());
                                String att = r.readString();
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("time", t);
                                f.put("name", att);
                                frames.add(f);
                            }
                            stl.put("attachment", frames);
                        }
                    }
                    slotTls.put(sname, stl);
                }
                anim.put("slots", slotTls);
            }

            // Bone timelines
            int boneTlCount = r.readVarint(true);
            if (boneTlCount > 0) {
                Map<String, Map<String, Object>> boneTls = new LinkedHashMap<>();
                for (int i = 0; i < boneTlCount; i++) {
                    String bname = boneNames.get(r.readVarint(true));
                    int tlCount = r.readVarint(true);
                    Map<String, Object> btl = new LinkedHashMap<>();
                    for (int j = 0; j < tlCount; j++) {
                        int tlType = r.readByte();
                        int frameCount = r.readVarint(true);
                        if (tlType == TIMELINE_ROTATE) {
                            List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                            for (int fi = 0; fi < frameCount; fi++) {
                                double t = round5(r.readFloat());
                                double angle = round5(r.readFloat());
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("time", t);
                                f.put("angle", angle);
                                if (fi < frameCount - 1) {
                                    Object curve = readCurve(r);
                                    if (curve != null) f.put("curve", curve);
                                }
                                frames.add(f);
                            }
                            btl.put("rotate", frames);
                        } else if (tlType == TIMELINE_TRANSLATE || tlType == TIMELINE_SCALE) {
                            String key = (tlType == TIMELINE_TRANSLATE) ? "translate" : "scale";
                            List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                            for (int fi = 0; fi < frameCount; fi++) {
                                double t = round5(r.readFloat());
                                double x = round5(r.readFloat());
                                double y = round5(r.readFloat());
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("time", t);
                                f.put("x", x);
                                f.put("y", y);
                                if (fi < frameCount - 1) {
                                    Object curve = readCurve(r);
                                    if (curve != null) f.put("curve", curve);
                                }
                                frames.add(f);
                            }
                            btl.put(key, frames);
                        } else if (tlType == TIMELINE_FLIPX || tlType == TIMELINE_FLIPY) {
                            String key = (tlType == TIMELINE_FLIPX) ? "flipX" : "flipY";
                            List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                            for (int fi = 0; fi < frameCount; fi++) {
                                double t = round5(r.readFloat());
                                boolean val = r.readBool();
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("time", t);
                                f.put(key, val);
                                frames.add(f);
                            }
                            btl.put(key, frames);
                        }
                    }
                    boneTls.put(bname, btl);
                }
                anim.put("bones", boneTls);
            }

            // IK timelines
            int ikTlCount = r.readVarint(true);
            if (ikTlCount > 0) {
                Map<String, List<Map<String, Object>>> ikTls = new LinkedHashMap<>();
                for (int i = 0; i < ikTlCount; i++) {
                    String ikName = ikNames.get(r.readVarint(true));
                    int frameCount = r.readVarint(true);
                    List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                    for (int fi = 0; fi < frameCount; fi++) {
                        double t = round5(r.readFloat());
                        double mix = round5(r.readFloat());
                        int bendDir = r.readSByte();
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("time", t);
                        f.put("mix", mix);
                        f.put("bendPositive", bendDir >= 0);
                        if (fi < frameCount - 1) {
                            Object curve = readCurve(r);
                            if (curve != null) f.put("curve", curve);
                        }
                        frames.add(f);
                    }
                    ikTls.put(ikName, frames);
                }
                anim.put("ik", ikTls);
            }

            // FFD timelines
            int ffdSkinCount = r.readVarint(true);
            if (ffdSkinCount > 0) {
                Map<String, Map<String, Map<String, List<Map<String, Object>>>>> ffd = new LinkedHashMap<>();
                for (int i = 0; i < ffdSkinCount; i++) {
                    String sname = skinNames.get(r.readVarint(true));
                    Map<String, Map<String, List<Map<String, Object>>>> skinMap = new LinkedHashMap<>();
                    int ffdSlotCount = r.readVarint(true);
                    for (int j = 0; j < ffdSlotCount; j++) {
                        String slotName = slotNames.get(r.readVarint(true));
                        Map<String, List<Map<String, Object>>> slotMap = new LinkedHashMap<>();
                        int attCount = r.readVarint(true);
                        for (int k = 0; k < attCount; k++) {
                            String attName = nonNull(r.readString(), "");
                            int frameCount = r.readVarint(true);
                            List<Map<String, Object>> frames = new ArrayList<>(frameCount);
                            for (int fi = 0; fi < frameCount; fi++) {
                                double t = round5(r.readFloat());
                                int end = r.readVarint(true);
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("time", t);
                                if (end != 0) {
                                    int start = r.readVarint(true);
                                    double[] verts = new double[end];
                                    for (int vi = 0; vi < end; vi++) verts[vi] = round5(r.readFloat());
                                    if (start != 0) f.put("offset", start);
                                    f.put("vertices", verts);
                                }
                                if (fi < frameCount - 1) {
                                    Object curve = readCurve(r);
                                    if (curve != null) f.put("curve", curve);
                                }
                                frames.add(f);
                            }
                            slotMap.put(attName, frames);
                        }
                        skinMap.put(slotName, slotMap);
                    }
                    ffd.put(sname, skinMap);
                }
                anim.put("ffd", ffd);
            }

            // Draw order timeline
            int drawOrderCount = r.readVarint(true);
            if (drawOrderCount > 0) {
                List<Map<String, Object>> drawOrder = new ArrayList<>(drawOrderCount);
                for (int i = 0; i < drawOrderCount; i++) {
                    int offsetCount = r.readVarint(true);
                    List<Map<String, Object>> offsets = new ArrayList<>(offsetCount);
                    for (int j = 0; j < offsetCount; j++) {
                        int slotIdx = r.readVarint(true);
                        int offset = r.readVarint(false);
                        Map<String, Object> om = new LinkedHashMap<>();
                        om.put("slot", slotNames.get(slotIdx));
                        om.put("offset", offset);
                        offsets.add(om);
                    }
                    double t = round5(r.readFloat());
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("time", t);
                    dm.put("offsets", offsets);
                    drawOrder.add(dm);
                }
                anim.put("drawOrder", drawOrder);
            }

            // Event timeline
            int evCount = r.readVarint(true);
            if (evCount > 0) {
                List<Map<String, Object>> evFrames = new ArrayList<>(evCount);
                for (int i = 0; i < evCount; i++) {
                    double t = round5(r.readFloat());
                    int evIdx = r.readVarint(true);
                    String ename = eventNames.get(evIdx);
                    int eint = r.readVarint(false);
                    double efloat = round5(r.readFloat());
                    boolean hasStr = r.readBool();
                    String estr = hasStr ? r.readString() : null;
                    Map<String, Object> ef = new LinkedHashMap<>();
                    ef.put("time", t);
                    ef.put("name", ename);
                    if (eint != 0) ef.put("int", eint);
                    if (efloat != 0) ef.put("float", efloat);
                    if (estr != null) ef.put("string", estr);
                    evFrames.add(ef);
                }
                anim.put("events", evFrames);
            }

            animations.put(aname, anim);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skeleton", skeletonMeta);
        result.put("bones", bones);
        result.put("slots", slots);
        result.put("skins", skins);
        result.put("animations", animations);
        if (!ikConstraints.isEmpty()) result.put("ik", ikConstraints);
        if (!events.isEmpty()) result.put("events", events);
        return result;
    }

    private static Map<String, Map<String, Object>> readSkin(BinaryReader r, List<String> slotNames, boolean nonessential) {
        int skinSlotCount = r.readVarint(true);
        Map<String, Map<String, Object>> skinMap = new LinkedHashMap<>();
        for (int i = 0; i < skinSlotCount; i++) {
            String slotName = slotNames.get(r.readVarint(true));
            int attCount = r.readVarint(true);
            Map<String, Object> atts = new LinkedHashMap<>();
            for (int j = 0; j < attCount; j++) {
                String attName = nonNull(r.readString(), "");
                atts.put(attName, readAttachment(r, attName, nonessential));
            }
            skinMap.put(slotName, atts);
        }
        return skinMap;
    }

    private static Map<String, Object> readAttachment(BinaryReader r, String attName, boolean nonessential) {
        String name = nonNull(r.readString(), attName);
        int attType = r.readByte();
        if (attType == ATTACHMENT_REGION) {
            String path = r.readString();
            double x = round5(r.readFloat());
            double y = round5(r.readFloat());
            double scaleX = round5(r.readFloat());
            double scaleY = round5(r.readFloat());
            double rotation = round5(r.readFloat());
            double w = round5(r.readFloat());
            double h = round5(r.readFloat());
            String color = r.readColorHex();
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("type", "region");
            obj.put("width", w);
            obj.put("height", h);
            if (path != null && !path.isEmpty() && !path.equals(attName)) obj.put("path", path);
            else if (!name.equals(attName)) obj.put("name", name);
            if (x != 0) obj.put("x", x);
            if (y != 0) obj.put("y", y);
            if (scaleX != 1) obj.put("scaleX", scaleX);
            if (scaleY != 1) obj.put("scaleY", scaleY);
            if (rotation != 0) obj.put("rotation", rotation);
            if (!"ffffffff".equals(color)) obj.put("color", color);
            return obj;
        }
        if (attType == ATTACHMENT_BOUNDING_BOX) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("type", "boundingbox");
            obj.put("vertices", r.readFloatArray());
            return obj;
        }
        if (attType == ATTACHMENT_MESH) {
            String path = r.readString();
            double[] uvs = r.readFloatArray();
            int[] triangles = r.readShortArray();
            double[] vertices = r.readFloatArray();
            String color = r.readColorHex();
            int hullLength = r.readVarint(true);
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("type", "mesh");
            obj.put("uvs", uvs);
            obj.put("triangles", triangles);
            obj.put("vertices", vertices);
            obj.put("hull", hullLength);
            if (path != null && !path.isEmpty()) obj.put("path", path);
            if (!"ffffffff".equals(color)) obj.put("color", color);
            if (nonessential) {
                obj.put("edges", r.readIntArray());
                obj.put("width", round5(r.readFloat()));
                obj.put("height", round5(r.readFloat()));
            }
            return obj;
        }
        if (attType == ATTACHMENT_SKINNED_MESH) {
            String path = r.readString();
            double[] uvs = r.readFloatArray();
            int[] triangles = r.readShortArray();
            int vertexCount = r.readVarint(true);
            float[] rawFloats = new float[vertexCount];
            for (int i = 0; i < vertexCount; i++) rawFloats[i] = r.readFloat();
            List<Object> weights = new ArrayList<>(vertexCount);
            int idx = 0;
            while (idx < vertexCount) {
                int bc = (int) rawFloats[idx++];
                weights.add(bc);
                for (int b = 0; b < bc; b++) {
                    if (idx + 3 < vertexCount) {
                        weights.add((int) rawFloats[idx]);
                        weights.add(round5(rawFloats[idx + 1]));
                        weights.add(round5(rawFloats[idx + 2]));
                        weights.add(round5(rawFloats[idx + 3]));
                    }
                    idx += 4;
                }
            }
            String color = r.readColorHex();
            int hullLength = r.readVarint(true);
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("type", "skinnedmesh");
            obj.put("uvs", uvs);
            obj.put("triangles", triangles);
            obj.put("vertices", weights);
            obj.put("hull", hullLength);
            if (path != null && !path.isEmpty()) obj.put("path", path);
            if (!"ffffffff".equals(color)) obj.put("color", color);
            if (nonessential) {
                obj.put("edges", r.readIntArray());
                obj.put("width", round5(r.readFloat()));
                obj.put("height", round5(r.readFloat()));
            }
            return obj;
        }
        throw new IllegalArgumentException("Unknown Spine 2.1 attachment type: " + attType);
    }

    private static Object readCurve(BinaryReader r) {
        int ctype = r.readByte();
        if (ctype == CURVE_LINEAR) return null;
        if (ctype == CURVE_STEPPED) return "stepped";
        if (ctype == CURVE_BEZIER) {
            return new double[]{round5(r.readFloat()), round5(r.readFloat()), round5(r.readFloat()), round5(r.readFloat())};
        }
        return null;
    }

    private static double round5(float v) {
        return Math.rint((double) v * 100000.0) / 100000.0;
    }

    private static String nonNull(String s, String def) {
        return (s != null && !s.isEmpty()) ? s : def;
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static final class BinaryReader {
        private final ByteBuffer buf;

        BinaryReader(byte[] data) {
            this.buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        }

        int readByte() {
            return buf.get() & 0xFF;
        }

        int readSByte() {
            return buf.get();
        }

        boolean readBool() {
            return buf.get() != 0;
        }

        float readFloat() {
            return buf.getFloat();
        }

        int readVarint(boolean optimizePositive) {
            int b = readByte();
            int result = b & 0x7F;
            if ((b & 0x80) != 0) {
                b = readByte();
                result |= (b & 0x7F) << 7;
                if ((b & 0x80) != 0) {
                    b = readByte();
                    result |= (b & 0x7F) << 14;
                    if ((b & 0x80) != 0) {
                        b = readByte();
                        result |= (b & 0x7F) << 21;
                        if ((b & 0x80) != 0) {
                            b = readByte();
                            result |= (b & 0x7F) << 28;
                        }
                    }
                }
            }
            return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
        }

        String readString() {
            int charCount = readVarint(true);
            if (charCount == 0) return null;
            if (charCount == 1) return "";
            int n = charCount - 1;
            byte[] raw = new byte[n];
            buf.get(raw);
            return new String(raw, StandardCharsets.UTF_8);
        }

        double[] readFloatArray() {
            int n = readVarint(true);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) out[i] = round5(buf.getFloat());
            return out;
        }

        int[] readShortArray() {
            int n = readVarint(true);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = buf.getShort() & 0xFFFF;
            return out;
        }

        int[] readIntArray() {
            int n = readVarint(true);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = readVarint(true);
            return out;
        }

        String readColorHex() {
            int v = buf.getInt();
            return String.format("%08x", v);
        }
    }
}
