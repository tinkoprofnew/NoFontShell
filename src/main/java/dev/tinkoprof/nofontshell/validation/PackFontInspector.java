package dev.tinkoprof.nofontshell.validation;

import dev.tinkoprof.nofontshell.NoFontShellMod;
import dev.tinkoprof.nofontshell.config.NoFontShellConfig;
import dev.tinkoprof.nofontshell.telemetry.FontThreatClassifier;
import dev.tinkoprof.nofontshell.telemetry.NoFontShellNotifier;
import dev.tinkoprof.nofontshell.telemetry.NoFontShellStats;
import dev.tinkoprof.nofontshell.telemetry.ThreatReport;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PackFontInspector {
    private static final Map<String, ThreatReport> FLAGGED_PACK_REPORTS = new ConcurrentHashMap<>();

    private PackFontInspector() {}

    public static void beginReloadCycle() {
        FLAGGED_PACK_REPORTS.clear();
        NoFontShellNotifier.resetReloadSuppression();
    }

    public static List<PackResources> inspectReloadPacks(List<PackResources> packs) {
        beginReloadCycle();
        NoFontShellConfig config = NoFontShellConfig.get();
        NoFontShellConfig.Action action = config.resolvedAction();

        List<PackResources> allowed = new ArrayList<>(packs.size());
        for (PackResources pack : packs) {
            boolean packSafe = inspectPack(pack, config, action, "resource-reload");
            if (!packSafe && action == NoFontShellConfig.Action.BLOCK_PACK) {
                String sourceLabel = describeSource(pack);
                ThreatReport report = FLAGGED_PACK_REPORTS.getOrDefault(
                        pack.packId(),
                        new ThreatReport(
                                dev.tinkoprof.nofontshell.telemetry.ThreatSeverity.HIGH,
                                "UNSAFE_TTF_PACK",
                                "Pack failed NoFontShell TrueType validation"
                        )
                );
                NoFontShellStats.recordPackBlocked("resource-reload", sourceLabel, pack.packId(), report);
                NoFontShellNotifier.notifyOncePerPack(sourceLabel, pack.packId(), action, report);
                NoFontShellMod.LOGGER.warn(
                        "[NoFontShell] BLOCK_PACK [{}]: Excluding resource pack [source={}, id={}] from client reload.",
                        report.getSeverity().getLabel(), sourceLabel, pack.packId()
                );
                try {
                    pack.close();
                } catch (Exception e) {
                    NoFontShellMod.LOGGER.debug("[NoFontShell] Error closing blocked pack {}: {}", pack.packId(), e.getMessage());
                }
                continue;
            }
            allowed.add(pack);
        }
        return allowed;
    }

    public static boolean inspectPack(
            PackResources pack,
            NoFontShellConfig config,
            NoFontShellConfig.Action action,
            String stage
    ) {
        if (pack == null) {
            return true;
        }
        String sourceLabel = describeSource(pack);
        if (!config.validateBuiltInPacks && isBuiltIn(pack)) {
            return true;
        }

        NoFontShellStats.recordPackScanned();

        List<Map.Entry<Identifier, IoSupplier<InputStream>>> ttfEntries = new ArrayList<>();
        try {
            Set<String> namespaces = pack.getNamespaces(PackType.CLIENT_RESOURCES);
            for (String namespace : namespaces) {
                pack.listResources(PackType.CLIENT_RESOURCES, namespace, "font", (id, supplier) -> {
                    if (id.getPath().toLowerCase(Locale.ROOT).endsWith(".ttf")) {
                        ttfEntries.add(Map.entry(id, supplier));
                    }
                });
            }
        } catch (Exception e) {
            NoFontShellMod.LOGGER.warn(
                    "[NoFontShell] Failed to enumerate font resources in pack [stage={}, source={}, id={}]: {}",
                    stage, sourceLabel, pack.packId(), e.getMessage()
            );
            return false;
        }

        if (ttfEntries.isEmpty()) {
            return true;
        }

        boolean safe = true;

        if (ttfEntries.size() > config.maxTtfFacesPerPack) {
            ThreatReport batchReport = FontThreatClassifier.classifyBatchSpray(ttfEntries.size(), config.maxTtfFacesPerPack);
            FLAGGED_PACK_REPORTS.put(pack.packId(), batchReport);
            safe = false;
            NoFontShellStats.recordFontFlagged(stage, sourceLabel, pack.packId(), "<ttf-batch:" + ttfEntries.size() + ">", action, batchReport);
            NoFontShellNotifier.notifyOncePerPack(sourceLabel, pack.packId(), action, batchReport);
            NoFontShellMod.LOGGER.warn(
                    "[NoFontShell] [{}] Suspicious TTF batch [stage={}, action={}, source={}, pack={}]: {}",
                    batchReport.getSeverity().getLabel(), stage, action, sourceLabel, pack.packId(), batchReport.getSummary()
            );
            if (action == NoFontShellConfig.Action.BLOCK_PACK) {
                return false;
            }
        }

        int loggedViolations = 0;
        for (Map.Entry<Identifier, IoSupplier<InputStream>> entry : ttfEntries) {
            Identifier resourceId = entry.getKey();
            NoFontShellStats.recordFontScanned();
            ValidationResult result = validateStream(entry.getValue(), config);
            if (!result.isSafe()) {
                ThreatReport report = FontThreatClassifier.classify(result);
                if (!FontPolicy.isBlocking(result, report, config)) {
                    logAdvisory(stage, sourceLabel, pack.packId(), resourceId.toString(), result);
                    continue;
                }
                safe = false;
                FLAGGED_PACK_REPORTS.putIfAbsent(pack.packId(), report);
                NoFontShellStats.recordFontFlagged(stage, sourceLabel, pack.packId(), resourceId.toString(), action, report);
                NoFontShellNotifier.notifyOncePerPack(sourceLabel, pack.packId(), action, report);

                if (loggedViolations < 10) {
                    NoFontShellMod.LOGGER.warn(
                            "[NoFontShell] [{}] Unsafe TrueType font [stage={}, action={}, source={}, pack={}, resource={}, sig={}]: {}",
                            report.getSeverity().getLabel(), stage, action, sourceLabel, pack.packId(), resourceId,
                            report.getSignature(), result.joinedReasons()
                    );
                } else if (loggedViolations == 10) {
                    NoFontShellMod.LOGGER.warn(
                            "[NoFontShell] Additional unsafe .ttf warnings suppressed for pack [source={}, pack={}] (total .ttf files={})",
                            sourceLabel, pack.packId(), ttfEntries.size()
                    );
                }
                loggedViolations++;
                if (action == NoFontShellConfig.Action.BLOCK_PACK) {
                    return false;
                }
            }
        }

        return safe;
    }

    public static boolean shouldAllowFontLoad(ResourceManager resourceManager, Identifier fontResourceId) {
        NoFontShellConfig config = NoFontShellConfig.get();
        NoFontShellConfig.Action action = config.resolvedAction();

        Optional<Resource> resourceOpt = resourceManager.getResource(fontResourceId);
        if (resourceOpt.isEmpty()) {
            return true;
        }

        Resource resource = resourceOpt.get();
        PackResources sourcePack = resource.source();
        String packId = resource.sourcePackId();
        String sourceLabel = describeSource(sourcePack);

        if (!config.validateBuiltInPacks && isBuiltIn(sourcePack)) {
            return true;
        }

        ThreatReport packFlagReport = FLAGGED_PACK_REPORTS.get(packId);
        if (packFlagReport != null) {
            NoFontShellStats.recordFontFlagged("font-load", sourceLabel, packId, fontResourceId.toString(), action, packFlagReport);
            NoFontShellNotifier.notifyOncePerPack(sourceLabel, packId, action, packFlagReport);
            NoFontShellMod.LOGGER.warn(
                    "[NoFontShell] [{}] Font load flagged by pack policy [action={}, source={}, pack={}, resource={}]: {}",
                    packFlagReport.getSeverity().getLabel(), action, sourceLabel, packId, fontResourceId, packFlagReport.getSummary()
            );
            return action == NoFontShellConfig.Action.LOG_ONLY;
        }

        NoFontShellStats.recordFontScanned();
        ValidationResult result;
        try (InputStream in = resource.open()) {
            byte[] bytes = readBounded(in, config.maxFileSizeBytes);
            if (bytes == null) {
                result = ValidationResult.malformed("Font stream exceeds maxFileSizeBytes (" + config.maxFileSizeBytes + ")");
            } else {
                result = TrueTypeValidator.validate(bytes, config);
            }
        } catch (Exception e) {
            result = ValidationResult.malformed("Exception reading .ttf stream: " + e.getMessage());
        }

        if (!result.isSafe()) {
            ThreatReport report = FontThreatClassifier.classify(result);
            if (!FontPolicy.isBlocking(result, report, config)) {
                logAdvisory("font-load", sourceLabel, packId, fontResourceId.toString(), result);
                return true;
            }
            NoFontShellStats.recordFontFlagged("font-load", sourceLabel, packId, fontResourceId.toString(), action, report);
            NoFontShellNotifier.notifyOncePerPack(sourceLabel, packId, action, report);
            NoFontShellMod.LOGGER.warn(
                    "[NoFontShell] [{}] Rejected TrueType font before FreeType [action={}, source={}, pack={}, resource={}, sig={}]: {}",
                    report.getSeverity().getLabel(), action, sourceLabel, packId, fontResourceId,
                    report.getSignature(), result.joinedReasons()
            );
            return action == NoFontShellConfig.Action.LOG_ONLY;
        }

        return true;
    }

    private static void logAdvisory(String stage, String source, String packId, String resourceId, ValidationResult result) {
        NoFontShellMod.LOGGER.info(
                "[NoFontShell] Advisory only (hinting disabled, not blocking) [stage={}, source={}, pack={}, resource={}]: {}",
                stage, source, packId, resourceId, result.joinedReasons()
        );
    }

    private static ValidationResult validateStream(IoSupplier<InputStream> supplier, NoFontShellConfig config) {
        try (InputStream in = supplier.get()) {
            byte[] bytes = readBounded(in, config.maxFileSizeBytes);
            if (bytes == null) {
                return ValidationResult.malformed("Font size exceeds maxFileSizeBytes (" + config.maxFileSizeBytes + ")");
            }
            return TrueTypeValidator.validate(bytes, config);
        } catch (Exception e) {
            return ValidationResult.malformed("Failed to read .ttf binary: " + e.getMessage());
        }
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 16384));
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    public static boolean isBuiltIn(PackResources pack) {
        if (pack == null) {
            return false;
        }
        PackLocationInfo info = pack.location();
        if (info == null) {
            return false;
        }
        PackSource source = info.source();
        return source == PackSource.BUILT_IN || source == PackSource.FEATURE;
    }

    public static String describeSource(PackResources pack) {
        if (pack == null) {
            return "UNKNOWN";
        }
        PackLocationInfo info = pack.location();
        if (info != null) {
            PackSource source = info.source();
            if (source == PackSource.SERVER) {
                return "SERVER";
            }
            if (source == PackSource.WORLD) {
                return "WORLD";
            }
            if (source == PackSource.BUILT_IN || source == PackSource.FEATURE) {
                return "BUILT_IN";
            }
            if (source == PackSource.DEFAULT) {
                return "LOCAL";
            }
        }
        String id = pack.packId();
        if (id != null && id.startsWith("server/")) {
            return "SERVER";
        }
        return "LOCAL";
    }
}
