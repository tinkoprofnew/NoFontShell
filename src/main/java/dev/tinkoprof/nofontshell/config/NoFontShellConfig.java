package dev.tinkoprof.nofontshell.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class NoFontShellConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("nofontshell");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "nofontshell.json";

    private static volatile NoFontShellConfig instance = new NoFontShellConfig();

    public enum Action {
        LOG_ONLY,
        SKIP_FONT,
        BLOCK_PACK;

        public static Action parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return SKIP_FONT;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "log_only", "log" -> LOG_ONLY;
                case "skip_font", "skip" -> SKIP_FONT;
                case "block_pack", "block" -> BLOCK_PACK;
                default -> SKIP_FONT;
            };
        }
    }

    public String action = "skip_font";

    public boolean disableTrueTypeHinting = true;

    public boolean validateBuiltInPacks = false;

    public int maxTtfFacesPerPack = 32;

    public int maxFileSizeBytes = 16 * 1024 * 1024;

    public int maxStackElements = 1024;

    public int maxStorage = 2048;

    public int maxInstructionDefs = 0;

    public int maxSizeOfInstructions = 4096;

    public int maxFpgmBytes = 4096;

    public int maxPrepBytes = 4096;

    public int maxCvtBytes = 4096;

    public int maxCompositeDepth = 8;

    public int maxCompositeComponents = 32;

    public boolean rejectCompositeGlyphInstructions = true;

    public boolean blockDangerousHintingOpcodes = true;

    public boolean strictBytecodeChecks = false;

    public Action resolvedAction() {
        return Action.parse(this.action);
    }

    public static NoFontShellConfig get() {
        return instance;
    }

    public static void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configPath)) {
            NoFontShellConfig defaults = new NoFontShellConfig();
            instance = defaults;
            save(configPath, defaults);
            return;
        }

        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            NoFontShellConfig loaded = GSON.fromJson(json, NoFontShellConfig.class);
            if (loaded == null) {
                loaded = new NoFontShellConfig();
            }
            loaded.sanitize();
            instance = loaded;
            LOGGER.info("[NoFontShell] Loaded config from {} (action={}, disableTrueTypeHinting={}, maxTtfFacesPerPack={})",
                    configPath, loaded.resolvedAction(), loaded.disableTrueTypeHinting, loaded.maxTtfFacesPerPack);
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("[NoFontShell] Failed to read config {}, falling back to safe defaults: {}", configPath, e.getMessage());
            instance = new NoFontShellConfig();
        }
    }

    private void sanitize() {
        this.action = resolvedAction().name().toLowerCase(Locale.ROOT);
        if (this.maxTtfFacesPerPack < 1) this.maxTtfFacesPerPack = 32;
        if (this.maxFileSizeBytes < 1024) this.maxFileSizeBytes = 16 * 1024 * 1024;
        if (this.maxStackElements < 16) this.maxStackElements = 1024;
        if (this.maxStorage < 0) this.maxStorage = 2048;
        if (this.maxInstructionDefs < 0) this.maxInstructionDefs = 0;
        if (this.maxSizeOfInstructions < 0) this.maxSizeOfInstructions = 4096;
        if (this.maxFpgmBytes < 0) this.maxFpgmBytes = 4096;
        if (this.maxPrepBytes < 0) this.maxPrepBytes = 4096;
        if (this.maxCvtBytes < 0) this.maxCvtBytes = 4096;
        if (this.maxCompositeDepth < 1) this.maxCompositeDepth = 8;
        if (this.maxCompositeComponents < 1) this.maxCompositeComponents = 32;
    }

    public static void saveCurrent() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        NoFontShellConfig current = instance;
        current.sanitize();
        save(configPath, current);
    }

    private static void save(Path path, NoFontShellConfig config) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8);
            LOGGER.info("[NoFontShell] Wrote default config to {}", path);
        } catch (IOException e) {
            LOGGER.warn("[NoFontShell] Could not write default config to {}: {}", path, e.getMessage());
        }
    }
}
