package dev.tinkoprof.nofontshell.validation;

import dev.tinkoprof.nofontshell.config.NoFontShellConfig;
import dev.tinkoprof.nofontshell.telemetry.ThreatReport;
import dev.tinkoprof.nofontshell.telemetry.ThreatSeverity;

public final class FontPolicy {
    private FontPolicy() {}

    public static boolean isBlocking(ValidationResult result, ThreatReport report, NoFontShellConfig config) {
        if (result.isSafe()) {
            return false;
        }
        if (!result.isStructurallySafe()) {
            return true;
        }
        if (!config.disableTrueTypeHinting || config.strictBytecodeChecks) {
            return true;
        }
        return report.getSeverity() == ThreatSeverity.CRITICAL;
    }
}
