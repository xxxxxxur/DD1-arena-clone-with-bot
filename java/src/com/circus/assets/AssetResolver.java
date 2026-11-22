package com.circus.assets;

import com.circus.Config;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Resolves hero animations, skins, FX, icons, UI panels, arena walls, and static assets
 * directly from Darkest Dungeon & The Butcher's Circus DLC directories with thread-safe caching.
 */
public final class AssetResolver {
    private static volatile AssetResolver INSTANCE;

    private static final Map<String, Path> DLC_HERO_ROOTS = Map.of(
            "flagellant", Path.of("dlc", "580100_crimson_court", "features", "flagellant", "heroes", "flagellant"),
            "shieldbreaker", Path.of("dlc", "702540_shieldbreaker", "heroes", "shieldbreaker"),
            "musketeer", Path.of("dlc", "445700_musketeer", "heroes", "musketeer")
    );

    public record SpineBundle(Path skel, Path atlas, Path png) {}

    private final Path gameDir;
    private final Path dlcDir;
    private final Map<String, Optional<SpineBundle>> heroAnimCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<SpineBundle>> heroFxCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<SpineBundle>> globalFxCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Path>> pathCache = new ConcurrentHashMap<>();

    public AssetResolver(Path gameDir, Path dlcDir) {
        this.gameDir = gameDir;
        this.dlcDir = dlcDir;
    }

    public static AssetResolver get() {
        AssetResolver inst = INSTANCE;
        if (inst == null) {
            synchronized (AssetResolver.class) {
                inst = INSTANCE;
                if (inst == null) {
                    inst = new AssetResolver(Config.DD_GAME_DIR, Config.DD_DLC_DIR);
                    INSTANCE = inst;
                }
            }
        }
        return inst;
    }

    public Path heroBaseDir(String heroId) {
        Path rel = DLC_HERO_ROOTS.get(heroId);
        if (rel != null) return gameDir.resolve(rel);
        return gameDir.resolve("heroes").resolve(heroId);
    }

    public Path heroArenaDir(String heroId) {
        return dlcDir.resolve("heroes").resolve(heroId);
    }

    private Path[] heroSearchDirs(String heroId) {
        return new Path[]{heroArenaDir(heroId), heroBaseDir(heroId)};
    }

    public SpineBundle resolveHeroAnim(String heroId, String animName, String variant) {
        String v = (variant != null && !variant.isEmpty()) ? variant.toUpperCase(Locale.ROOT) : "A";
        if (!v.equals("A") && !v.equals("B") && !v.equals("C") && !v.equals("D")) v = "A";
        String key = heroId + "|" + animName + "|" + v;
        String finalV = v;
        return heroAnimCache.computeIfAbsent(key, k -> Optional.ofNullable(resolveHeroAnimUncached(heroId, animName, finalV)))
                .orElse(null);
    }

    private SpineBundle resolveHeroAnimUncached(String heroId, String animName, String variant) {
        String stem = heroId + ".sprite." + animName;
        Path skelPath = null;
        Path atlasPath = null;
        Path pngPath = null;

        for (Path hdir : heroSearchDirs(heroId)) {
            if (!Files.exists(hdir)) continue;
            Path candSkel = hdir.resolve("anim").resolve(stem + ".skel");
            Path candAtlas = hdir.resolve("anim").resolve(stem + ".atlas");
            if (skelPath == null && Files.exists(candSkel)) skelPath = candSkel;
            if (atlasPath == null && Files.exists(candAtlas)) atlasPath = candAtlas;

            for (String v : new String[]{variant, "A"}) {
                Path candPng = hdir.resolve(heroId + "_" + v).resolve("anim").resolve(stem + ".png");
                if (pngPath == null && Files.exists(candPng)) {
                    pngPath = candPng;
                    break;
                }
            }
            if (pngPath == null) {
                Path candPng2 = hdir.resolve("anim").resolve(stem + ".png");
                if (Files.exists(candPng2)) pngPath = candPng2;
            }
        }

        if (skelPath == null && !"combat".equals(animName)) {
            return resolveHeroAnim(heroId, "combat", variant);
        }
        if (skelPath == null || atlasPath == null || pngPath == null) return null;
        return new SpineBundle(skelPath, atlasPath, pngPath);
    }

    public SpineBundle resolveHeroFx(String heroId, String fxName) {
        String key = heroId + "|" + fxName;
        return heroFxCache.computeIfAbsent(key, k -> Optional.ofNullable(resolveHeroFxUncached(heroId, fxName)))
                .orElse(null);
    }

    private SpineBundle resolveHeroFxUncached(String heroId, String fxName) {
        String stem = heroId + ".sprite." + fxName;
        for (Path hdir : heroSearchDirs(heroId)) {
            Path fxDir = hdir.resolve("fx");
            if (!Files.exists(fxDir)) continue;
            Path skel = fxDir.resolve(stem + ".skel");
            Path atlas = fxDir.resolve(stem + ".atlas");
            Path png = fxDir.resolve(stem + ".png");
            if (Files.exists(skel) && Files.exists(atlas) && Files.exists(png)) {
                return new SpineBundle(skel, atlas, png);
            }
            Path skel2 = fxDir.resolve(fxName + ".sprite.skel");
            Path atlas2 = fxDir.resolve(fxName + ".sprite.atlas");
            Path png2 = fxDir.resolve(fxName + ".sprite.png");
            if (Files.exists(skel2) && Files.exists(atlas2) && Files.exists(png2)) {
                return new SpineBundle(skel2, atlas2, png2);
            }
        }
        return null;
    }

    public SpineBundle resolveGlobalFx(String fxName) {
        return globalFxCache.computeIfAbsent(fxName, k -> Optional.ofNullable(resolveGlobalFxUncached(fxName)))
                .orElse(null);
    }

    private SpineBundle resolveGlobalFxUncached(String fxName) {
        for (Path root : new Path[]{dlcDir.resolve("fx"), gameDir.resolve("fx")}) {
            Path d = root.resolve(fxName);
            if (!Files.isDirectory(d)) continue;
            Path skel = findFirstByExt(d, ".skel");
            Path atlas = findFirstByExt(d, ".atlas");
            Path png = findFirstByExt(d, ".png");
            if (skel != null && atlas != null && png != null) {
                return new SpineBundle(skel, atlas, png);
            }
        }
        return null;
    }

    private static Path findFirstByExt(Path dir, String ext) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public Path resolveHeroPortrait(String heroId, String variant) {
        String v = (variant != null && (variant.equalsIgnoreCase("A") || variant.equalsIgnoreCase("B")
                || variant.equalsIgnoreCase("C") || variant.equalsIgnoreCase("D")))
                ? variant.toUpperCase(Locale.ROOT) : "A";
        return pathCache.computeIfAbsent("portrait|" + heroId + "|" + v, k -> {
            for (Path hdir : heroSearchDirs(heroId)) {
                for (String candV : new String[]{v, "A"}) {
                    Path p = hdir.resolve(heroId + "_" + candV).resolve(heroId + "_portrait_roster.png");
                    if (Files.exists(p)) return Optional.of(p);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    public Path resolveSkillIcon(String heroId, String iconName) {
        return pathCache.computeIfAbsent("skill|" + heroId + "|" + iconName, k -> {
            String fname = heroId + ".ability." + iconName + ".png";
            for (Path hdir : heroSearchDirs(heroId)) {
                Path p = hdir.resolve(fname);
                if (Files.exists(p)) return Optional.of(p);
            }
            return Optional.empty();
        }).orElse(null);
    }

    public Path resolveTrinketIcon(String trinketId) {
        return pathCache.computeIfAbsent("trinket|" + trinketId, k -> {
            Path p = dlcDir.resolve("panels").resolve("icons_equip").resolve("trinket")
                    .resolve("inv_trinket+" + trinketId + ".png");
            return Files.exists(p) ? Optional.of(p) : Optional.empty();
        }).orElse(null);
    }

    public Path resolveArenaBackground(String tier) {
        String t = tier == null ? "darkest" : tier.toLowerCase(Locale.ROOT);
        if (!t.equals("novice") && !t.equals("apprentice") && !t.equals("veteran") && !t.equals("champion") && !t.equals("darkest")) {
            t = "darkest";
        }
        String finalT = t;
        return pathCache.computeIfAbsent("bg|" + finalT, k -> {
            Path p = dlcDir.resolve("dungeons").resolve("arena").resolve("arena.entrance_room_wall_" + finalT + ".png");
            return Files.exists(p) ? Optional.of(p) : Optional.empty();
        }).orElse(null);
    }

    public Path resolveStaticAsset(String relPath) {
        if (relPath == null) return null;
        String clean = relPath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        for (String seg : clean.split("/")) {
            if ("..".equals(seg)) return null;
        }
        String finalClean = clean;
        return pathCache.computeIfAbsent("static|" + finalClean, k -> {
            for (Path root : new Path[]{dlcDir, gameDir}) {
                Path rootNorm = root.toAbsolutePath().normalize();
                Path cand = rootNorm.resolve(finalClean).normalize();
                if (!cand.startsWith(rootNorm)) continue;
                if (Files.isRegularFile(cand)) return Optional.of(cand);
            }
            return Optional.empty();
        }).orElse(null);
    }
}
