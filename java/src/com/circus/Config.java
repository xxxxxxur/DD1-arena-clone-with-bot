package com.circus;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Global configuration for Darkest Dungeon: The Butcher's Circus Java backend.
 *
 * Game directory resolution priority:
 * 1. Environment variables: DD_GAME_DIR (and optional DD_DLC_DIR)
 * 2. Local config file: local.properties (gitignored, auto-created on first launch)
 * 3. Automatic Steam library detection (Windows Registry + libraryfolders.vdf + common Steam paths)
 * 4. Interactive directory picker dialog on first launch (defaulting to Steam location)
 */
public final class Config {
    public static final Path PROJECT_ROOT = detectProjectRoot();
    public static final Path LOCAL_CONFIG_PATH = PROJECT_ROOT.resolve("local.properties");
    public static final String ARENA_DLC_REL = "dlc/1117860_arena_mp";

    public static volatile Path DD_GAME_DIR;
    public static volatile Path DD_DLC_DIR;
    public static volatile Path FMOD_DLL_PATH;

    public static final Path CACHE_DIR = PROJECT_ROOT.resolve(".cache");
    public static final Path SPINE_CACHE_DIR = CACHE_DIR.resolve("spine");
    public static final Path AUDIO_CACHE_DIR = CACHE_DIR.resolve("audio");
    public static final Path REPLAY_DIR = PROJECT_ROOT.resolve("replays");
    public static final Path TEAM_DIR = PROJECT_ROOT.resolve("team");
    public static final Path BOT_DIR = PROJECT_ROOT.resolve("bot");
    public static final Path TOURNAMENT_DIR = PROJECT_ROOT.resolve("tournaments");
    public static final Path WEB_DIR = PROJECT_ROOT.resolve("web");
    public static final Path GAME_DATA_PATH = PROJECT_ROOT.resolve("java").resolve("resources").resolve("game_data.json");

    public static final String DEFAULT_HOST = envOrDefault("ARENA_HOST", "127.0.0.1");
    public static final int DEFAULT_PORT = Integer.parseInt(envOrDefault("ARENA_PORT", "8765"));

    private static final Pattern VDF_PATH_PATTERN = Pattern.compile("\"path\"\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static volatile boolean resolvedFromEnv = false;

    static {
        initializeGameDirs();
    }

    private Config() {}

    private static void initializeGameDirs() {
        String envGame = envOrNull("DD_GAME_DIR");
        String envDlc = envOrNull("DD_DLC_DIR");
        if (envGame != null) {
            Path g = normalizeGameDir(Path.of(envGame));
            Path d = envDlc != null ? Path.of(envDlc).toAbsolutePath().normalize() : defaultDlcDir(g);
            applyGameDirs(g, d);
            resolvedFromEnv = true;
            return;
        }

        Properties local = loadLocalProperties();
        String savedGame = local.getProperty("dd.game.dir", "").trim();
        String savedDlc = local.getProperty("dd.dlc.dir", "").trim();
        if (!savedGame.isEmpty()) {
            Path g = normalizeGameDir(Path.of(savedGame));
            Path d = !savedDlc.isEmpty() ? Path.of(savedDlc).toAbsolutePath().normalize() : defaultDlcDir(g);
            if (isValidGameDir(g, d)) {
                applyGameDirs(g, d);
                return;
            }
        }

        Path detected = detectSteamGameDir();
        if (detected != null) {
            applyGameDirs(detected, defaultDlcDir(detected));
            return;
        }

        Path fallback = defaultSteamSuggestionDir();
        applyGameDirs(fallback, defaultDlcDir(fallback));
    }

    public static void applyGameDirs(Path gameDir, Path dlcDir) {
        Path normalizedGame = normalizeGameDir(gameDir);
        Path normalizedDlc = (dlcDir != null) ? dlcDir.toAbsolutePath().normalize() : defaultDlcDir(normalizedGame);
        DD_GAME_DIR = normalizedGame;
        DD_DLC_DIR = normalizedDlc;
        FMOD_DLL_PATH = normalizedGame.resolve("_windows").resolve("win64").resolve("fmod.dll");
    }

    public static Path defaultDlcDir(Path gameDir) {
        return gameDir.resolve("dlc").resolve("1117860_arena_mp");
    }

    /**
     * If the user selects the DLC folder (dlc/1117860_arena_mp) instead of the game root,
     * automatically normalize it back to the DarkestDungeon root directory.
     */
    public static Path normalizeGameDir(Path candidate) {
        if (candidate == null) return defaultSteamSuggestionDir();
        Path norm = candidate.toAbsolutePath().normalize();
        Path fileName = norm.getFileName();
        if (fileName != null && fileName.toString().equalsIgnoreCase("1117860_arena_mp")) {
            Path parent = norm.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().equalsIgnoreCase("dlc")
                    && parent.getParent() != null) {
                return parent.getParent();
            }
        }
        return norm;
    }

    public static boolean isValidBaseGameDir(Path gameDir) {
        if (gameDir == null || !Files.isDirectory(gameDir)) return false;
        return Files.isDirectory(gameDir.resolve("heroes"))
                || Files.isDirectory(gameDir.resolve("localization"));
    }

    public static boolean isValidGameDir(Path gameDir, Path dlcDir) {
        if (!isValidBaseGameDir(gameDir)) return false;
        Path effectiveDlc = (dlcDir != null) ? dlcDir : defaultDlcDir(gameDir);
        return Files.isDirectory(effectiveDlc);
    }

    /**
     * Ensures that Darkest Dungeon & Butcher's Circus DLC assets are configured before launching the server.
     * If not found automatically (or forcePrompt is true), prompts the user with a directory picker
     * defaulting to the Steam installation directory, and saves the choice to local.properties.
     */
    public static boolean ensureGameAssetsConfigured(boolean allowInteractivePrompt, boolean forcePrompt) {
        if (!forcePrompt && isValidGameDir(DD_GAME_DIR, DD_DLC_DIR)) {
            if (!resolvedFromEnv && !Files.exists(LOCAL_CONFIG_PATH)) {
                saveLocalProperties(DD_GAME_DIR, null);
            }
            return true;
        }

        if (!allowInteractivePrompt) {
            return isValidGameDir(DD_GAME_DIR, DD_DLC_DIR);
        }

        Path initialSuggestion = isValidGameDir(DD_GAME_DIR, DD_DLC_DIR)
                ? DD_GAME_DIR
                : defaultSteamSuggestionDir();

        Path chosen = null;
        if (!GraphicsEnvironment.isHeadless()) {
            chosen = promptGameDirGui(initialSuggestion);
        } else {
            chosen = promptGameDirConsole(initialSuggestion);
        }

        if (chosen != null) {
            Path normalized = normalizeGameDir(chosen);
            Path dlc = defaultDlcDir(normalized);
            applyGameDirs(normalized, dlc);
            saveLocalProperties(normalized, null);
            return isValidGameDir(DD_GAME_DIR, DD_DLC_DIR);
        }
        return isValidGameDir(DD_GAME_DIR, DD_DLC_DIR);
    }

    /**
     * Scans Steam installations (Windows Registry, Steam libraryfolders.vdf, and standard Steam paths)
     * for Darkest Dungeon with The Butcher's Circus DLC.
     */
    public static Path detectSteamGameDir() {
        List<Path> candidates = findSteamGameCandidates();
        // First pass: prefer complete installation (base game + Butcher's Circus DLC)
        for (Path candidate : candidates) {
            if (isValidGameDir(candidate, defaultDlcDir(candidate))) {
                return candidate;
            }
        }
        // Second pass: base game directory even if DLC is missing
        for (Path candidate : candidates) {
            if (isValidBaseGameDir(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the best Steam directory to pre-fill or open in the directory picker dialog.
     */
    public static Path defaultSteamSuggestionDir() {
        List<Path> steamRoots = discoverSteamRoots();
        for (Path root : steamRoots) {
            Path common = root.resolve("steamapps").resolve("common");
            Path dd = common.resolve("DarkestDungeon");
            if (Files.isDirectory(dd)) return dd;
        }
        for (Path root : steamRoots) {
            Path common = root.resolve("steamapps").resolve("common");
            if (Files.isDirectory(common)) return common.resolve("DarkestDungeon");
        }
        if (isWindows()) {
            return Path.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\DarkestDungeon");
        }
        return Path.of(System.getProperty("user.home"), ".steam", "steam", "steamapps", "common", "DarkestDungeon");
    }

    private static Path preferredBrowserStartDir(Path currentTextPath) {
        if (currentTextPath != null) {
            if (Files.isDirectory(currentTextPath)) return currentTextPath;
            Path parent = currentTextPath.getParent();
            if (parent != null && Files.isDirectory(parent)) return parent;
        }
        for (Path root : discoverSteamRoots()) {
            Path common = root.resolve("steamapps").resolve("common");
            if (Files.isDirectory(common)) return common;
            if (Files.isDirectory(root)) return root;
        }
        return Path.of(System.getProperty("user.home"));
    }

    private static List<Path> findSteamGameCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        for (Path libRoot : discoverSteamLibraryRoots()) {
            candidates.add(libRoot.resolve("steamapps").resolve("common").resolve("DarkestDungeon"));
        }
        return new ArrayList<>(candidates);
    }

    private static List<Path> discoverSteamLibraryRoots() {
        Set<Path> libs = new LinkedHashSet<>();
        List<Path> steamRoots = discoverSteamRoots();
        for (Path root : steamRoots) {
            libs.add(root);
            parseLibraryFoldersVdf(root.resolve("steamapps").resolve("libraryfolders.vdf"), libs);
            parseLibraryFoldersVdf(root.resolve("config").resolve("libraryfolders.vdf"), libs);
        }
        if (isWindows()) {
            for (char drive : new char[]{'C', 'D', 'E', 'F', 'G'}) {
                String d = drive + ":\\";
                libs.add(Path.of(d, "SteamLibrary"));
                libs.add(Path.of(d, "Program Files (x86)", "Steam"));
                libs.add(Path.of(d, "Program Files", "Steam"));
                libs.add(Path.of(d, "Steam"));
                libs.add(Path.of(d, "Games", "Steam"));
            }
        }
        return new ArrayList<>(libs);
    }

    private static List<Path> discoverSteamRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        if (isWindows()) {
            addIfNonBlank(roots, queryWindowsRegistry("HKCU\\Software\\Valve\\Steam", "SteamPath"));
            addIfNonBlank(roots, queryWindowsRegistry("HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath"));
            addIfNonBlank(roots, queryWindowsRegistry("HKLM\\SOFTWARE\\Valve\\Steam", "InstallPath"));
            for (char drive : new char[]{'C', 'D', 'E', 'F', 'G'}) {
                String d = drive + ":\\";
                for (Path p : List.of(
                        Path.of(d, "Program Files (x86)", "Steam"),
                        Path.of(d, "Program Files", "Steam"),
                        Path.of(d, "Steam")
                )) {
                    if (Files.isDirectory(p)) roots.add(p);
                }
            }
        } else {
            String home = System.getProperty("user.home", "");
            for (Path p : List.of(
                    Path.of(home, ".steam", "steam"),
                    Path.of(home, ".local", "share", "Steam"),
                    Path.of(home, "Library", "Application Support", "Steam")
            )) {
                if (Files.isDirectory(p)) roots.add(p);
            }
        }
        return new ArrayList<>(roots);
    }

    private static void addIfNonBlank(Set<Path> set, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return;
        try {
            Path p = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) set.add(p);
        } catch (Exception ignored) {}
    }

    private static String queryWindowsRegistry(String key, String valueName) {
        try {
            Process proc = new ProcessBuilder("reg", "query", key, "/v", valueName)
                    .redirectErrorStream(true)
                    .start();
            boolean done = proc.waitFor(2, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.toLowerCase(Locale.ROOT).startsWith(valueName.toLowerCase(Locale.ROOT))) {
                        String[] parts = trimmed.split("\\s+REG_\\w+\\s+", 2);
                        if (parts.length == 2 && !parts[1].isBlank()) {
                            return parts[1].trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void parseLibraryFoldersVdf(Path vdfPath, Set<Path> outLibs) {
        if (!Files.isRegularFile(vdfPath)) return;
        try {
            String content = Files.readString(vdfPath, StandardCharsets.UTF_8);
            Matcher m = VDF_PATH_PATTERN.matcher(content);
            while (m.find()) {
                String raw = m.group(1).replace("\\\\", "\\").trim();
                if (!raw.isEmpty()) {
                    try {
                        Path p = Path.of(raw).toAbsolutePath().normalize();
                        if (Files.isDirectory(p)) outLibs.add(p);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private static Properties loadLocalProperties() {
        Properties props = new Properties();
        if (Files.isRegularFile(LOCAL_CONFIG_PATH)) {
            try (Reader r = Files.newBufferedReader(LOCAL_CONFIG_PATH, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException ignored) {}
        }
        return props;
    }

    public static void saveLocalProperties(Path gameDir, Path dlcDir) {
        Properties props = loadLocalProperties();
        props.setProperty("dd.game.dir", gameDir.toAbsolutePath().normalize().toString());
        if (dlcDir != null && !dlcDir.toAbsolutePath().normalize().equals(defaultDlcDir(gameDir).toAbsolutePath().normalize())) {
            props.setProperty("dd.dlc.dir", dlcDir.toAbsolutePath().normalize().toString());
        } else {
            props.remove("dd.dlc.dir");
        }
        try (Writer w = Files.newBufferedWriter(LOCAL_CONFIG_PATH, StandardCharsets.UTF_8)) {
            props.store(w, "Local Darkest Dungeon installation path (auto-generated, gitignored)");
        } catch (IOException e) {
            System.err.println("[Config] Warning: could not save " + LOCAL_CONFIG_PATH + ": " + e.getMessage());
        }
    }

    private static Path promptGameDirConsole(Path initialSuggestion) {
        System.err.println();
        System.err.println("======================================================================");
        System.err.println("未检测到《暗黑地牢》(Darkest Dungeon) 游戏素材目录！");
        System.err.println("Darkest Dungeon game assets not found.");
        System.err.println("请设置环境变量 DD_GAME_DIR，或在项目根目录创建 local.properties：");
        System.err.println("  dd.game.dir=" + initialSuggestion);
        System.err.println("======================================================================");
        Console console = System.console();
        if (console != null) {
            String input = console.readLine("请输入 DarkestDungeon 安装目录（直接回车跳过）: ");
            if (input != null && !input.isBlank()) {
                return Path.of(input.trim());
            }
        }
        return null;
    }

    private static Path promptGameDirGui(Path initialSuggestion) {
        AtomicReference<Path> result = new AtomicReference<>(null);
        Runnable showDialog = () -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            JFrame owner = new JFrame();
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);

            JDialog dialog = new JDialog(owner, "选择《暗黑地牢》游戏目录 — Darkest Dungeon Path", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            JPanel root = new JPanel(new BorderLayout(10, 10));
            root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("未自动读取到《暗黑地牢》(Darkest Dungeon) 游戏素材目录");
            title.setFont(title.getFont().deriveFont(title.getFont().getStyle() | java.awt.Font.BOLD, 14f));
            JLabel desc = new JLabel(
                    "<html>请选择或填入本地 Steam 安装的 <b>DarkestDungeon</b> 根目录（需包含 <code>dlc\\1117860_arena_mp</code> 屠夫马戏团 DLC）：</html>"
            );
            top.add(title);
            top.add(Box.createVerticalStrut(6));
            top.add(desc);
            root.add(top, BorderLayout.NORTH);

            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

            JPanel inputRow = new JPanel(new BorderLayout(8, 0));
            JTextField pathField = new JTextField(initialSuggestion.toString(), 46);
            JButton browseBtn = new JButton("浏览目录...");
            inputRow.add(pathField, BorderLayout.CENTER);
            inputRow.add(browseBtn, BorderLayout.EAST);

            JLabel statusLabel = new JLabel(" ");
            statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));

            Runnable updateStatus = () -> {
                String text = pathField.getText().trim();
                if (text.isEmpty()) {
                    statusLabel.setForeground(new Color(180, 50, 50));
                    statusLabel.setText("请输入或选择 DarkestDungeon 安装目录");
                    return;
                }
                try {
                    Path p = normalizeGameDir(Path.of(text));
                    boolean baseOk = isValidBaseGameDir(p);
                    boolean dlcOk = Files.isDirectory(defaultDlcDir(p));
                    if (baseOk && dlcOk) {
                        statusLabel.setForeground(new Color(24, 128, 56));
                        statusLabel.setText("✓ 已检测到本体与屠夫马戏团 DLC (1117860_arena_mp)");
                    } else if (baseOk) {
                        statusLabel.setForeground(new Color(180, 100, 0));
                        statusLabel.setText("! 已检测到游戏本体，但未找到 dlc\\1117860_arena_mp（请确认已安装屠夫马戏团免费 DLC）");
                    } else {
                        statusLabel.setForeground(new Color(180, 50, 50));
                        statusLabel.setText("× 该目录下未找到 DarkestDungeon 素材文件（heroes / localization）");
                    }
                } catch (Exception ex) {
                    statusLabel.setForeground(new Color(180, 50, 50));
                    statusLabel.setText("× 路径格式无效");
                }
            };

            pathField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { updateStatus.run(); }
                @Override public void removeUpdate(DocumentEvent e) { updateStatus.run(); }
                @Override public void changedUpdate(DocumentEvent e) { updateStatus.run(); }
            });

            browseBtn.addActionListener(ev -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("选择 DarkestDungeon 游戏根目录（优先 Steam 目录）");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                Path startDir;
                try {
                    startDir = preferredBrowserStartDir(Path.of(pathField.getText().trim()));
                } catch (Exception ex) {
                    startDir = preferredBrowserStartDir(null);
                }
                chooser.setCurrentDirectory(startDir.toFile());
                if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    Path selected = normalizeGameDir(chooser.getSelectedFile().toPath());
                    pathField.setText(selected.toString());
                }
            });

            center.add(inputRow);
            center.add(statusLabel);
            root.add(center, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton cancelBtn = new JButton("取消");
            JButton okBtn = new JButton("确定并保存");

            cancelBtn.addActionListener(ev -> dialog.dispose());
            okBtn.addActionListener(ev -> {
                String text = pathField.getText().trim();
                if (text.isEmpty()) {
                    JOptionPane.showMessageDialog(dialog, "请先选择或填入 DarkestDungeon 目录。", "提示", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                Path chosenPath;
                try {
                    chosenPath = normalizeGameDir(Path.of(text));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "路径格式无效，请重新输入。", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (!isValidGameDir(chosenPath, defaultDlcDir(chosenPath))) {
                    int confirm = JOptionPane.showConfirmDialog(
                            dialog,
                            "当前目录未检测到完整的本体及屠夫马戏团 DLC (dlc\\1117860_arena_mp)，可能导致立绘或音效无法加载。\n是否仍要使用该目录？",
                            "目录校验提示",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE
                    );
                    if (confirm != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                result.set(chosenPath);
                dialog.dispose();
            });

            buttons.add(cancelBtn);
            buttons.add(okBtn);
            root.add(buttons, BorderLayout.SOUTH);

            dialog.getRootPane().setDefaultButton(okBtn);
            dialog.setContentPane(root);
            dialog.setMinimumSize(new Dimension(560, 195));
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            updateStatus.run();
            dialog.setVisible(true);
            owner.dispose();
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (Exception e) {
            System.err.println("[Config] GUI directory picker failed: " + e.getMessage());
            return promptGameDirConsole(initialSuggestion);
        }
        return result.get();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String envOrNull(String key) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val.trim() : null;
    }

    private static String envOrDefault(String key, String fallback) {
        String val = envOrNull(key);
        return val != null ? val : fallback;
    }

    private static Path detectProjectRoot() {
        String prop = System.getProperty("circus.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("java").resolve("resources").resolve("game_data.json"))) {
            return cwd;
        }
        if (cwd.getParent() != null && Files.exists(cwd.getParent().resolve("java").resolve("resources").resolve("game_data.json"))) {
            return cwd.getParent();
        }
        return cwd;
    }
}
