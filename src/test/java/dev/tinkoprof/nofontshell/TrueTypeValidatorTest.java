package dev.tinkoprof.nofontshell;

import dev.tinkoprof.nofontshell.config.NoFontShellConfig;
import dev.tinkoprof.nofontshell.telemetry.FontThreatClassifier;
import dev.tinkoprof.nofontshell.telemetry.ThreatReport;
import dev.tinkoprof.nofontshell.telemetry.ThreatSeverity;
import dev.tinkoprof.nofontshell.validation.FontPolicy;
import dev.tinkoprof.nofontshell.validation.TrueTypeValidator;
import dev.tinkoprof.nofontshell.validation.ValidationResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrueTypeValidatorTest {

    @Test
    void acceptsValidMinimalTrueTypeFont() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 0, 128, 1, new byte[]{(byte) 0xB0, 0x36}, null, false, false);

        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        assertTrue(res.isSafe(), "Expected valid font to pass, got: " + res.joinedReasons());
    }

    @Test
    void rejectsHighMaxStackElements() throws IOException {
        byte[] ttf = buildSyntheticTtf(8192, 64, 0, 128, 1, null, null, false, false);
        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        assertFalse(res.isSafe());
        assertTrue(res.joinedReasons().contains("maxp.maxStackElements=8192"), res.joinedReasons());
    }

    @Test
    void rejectsShzOpcodeInFpgm() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 0, 128, 1, new byte[]{0x36}, null, false, false);
        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        assertFalse(res.isSafe());
        assertTrue(res.joinedReasons().contains("SHZ"), res.joinedReasons());
    }

    @Test
    void rejectsCompositeGlyphCycle() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 0, 128, 1, null, null, true, false);
        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        assertFalse(res.isSafe());
        assertTrue(res.joinedReasons().contains("Cyclic composite glyph"), res.joinedReasons());
    }

    @Test
    void rejectsCompositeGlyphWithInstructions() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 0, 128, 1, null, null, false, true);
        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        assertFalse(res.isSafe());
        assertTrue(res.joinedReasons().contains("contains hinting instructions"), res.joinedReasons());
    }

    @Test
    void handlesMalformedBinariesGracefully() {
        NoFontShellConfig cfg = new NoFontShellConfig();
        assertFalse(TrueTypeValidator.validate(null, cfg).isSafe());
        assertFalse(TrueTypeValidator.validate(new byte[0], cfg).isSafe());
        assertFalse(TrueTypeValidator.validate(new byte[]{0, 1, 0, 0, 0, 5}, cfg).isSafe());
        assertFalse(TrueTypeValidator.validate("not-a-ttf-binary-at-all".getBytes(StandardCharsets.UTF_8), cfg).isSafe());
    }

    @Test
    void detectsRealMinecraft2ShellSamplePack() throws IOException {
        String dir = System.getProperty("nofontshell.sampleDir", "");
        Assumptions.assumeTrue(!dir.isBlank() && Files.isDirectory(Path.of(dir)),
                "NOFONTSHELL_SAMPLE_DIR not set or missing; skipping real-sample test");
        Path sampleDir = Path.of(dir);
        NoFontShellConfig cfg = new NoFontShellConfig();
        List<Path> ttfFiles;
        try (var stream = Files.list(sampleDir)) {
            ttfFiles = stream.filter(p -> p.toString().endsWith(".ttf")).sorted().toList();
        }

        assertTrue(ttfFiles.size() > cfg.maxTtfFacesPerPack,
                "Expected sample pack to exceed maxTtfFacesPerPack (" + cfg.maxTtfFacesPerPack + "), found " + ttfFiles.size());

        for (Path ttf : ttfFiles) {
            byte[] bytes = Files.readAllBytes(ttf);
            ValidationResult res = TrueTypeValidator.validate(bytes, cfg);
            assertFalse(res.isSafe(), "Sample exploit TTF should be flagged unsafe: " + ttf.getFileName());
            String reasons = res.joinedReasons();
            assertTrue(reasons.contains("maxp.maxStackElements=8192"), reasons);
            assertTrue(reasons.contains("fpgm table size"), reasons);

            ThreatReport report = FontThreatClassifier.classify(res);
            assertEquals(ThreatSeverity.CRITICAL, report.getSeverity());
            assertEquals("FREETYPE_VM_EXPLOIT_PROFILE", report.getSignature());
            assertTrue(FontPolicy.isBlocking(res, report, cfg), "PoC font must be blocking under default policy");
        }
    }

    @Test
    void hintingOnlyHeuristicsAreAdvisoryWhenHintingDisabled() throws IOException {
        byte[] ttf = buildSyntheticTtf(2048, 64, 0, 128, 1, null, null, false, true);
        NoFontShellConfig cfg = new NoFontShellConfig();
        ValidationResult res = TrueTypeValidator.validate(ttf, cfg);
        assertFalse(res.isSafe());
        assertTrue(res.isStructurallySafe(), res.joinedReasons());
        ThreatReport report = FontThreatClassifier.classify(res);
        assertNotEquals(ThreatSeverity.CRITICAL, report.getSeverity());
        assertFalse(FontPolicy.isBlocking(res, report, cfg));

        cfg.strictBytecodeChecks = true;
        assertTrue(FontPolicy.isBlocking(res, report, cfg));
        cfg.strictBytecodeChecks = false;
        cfg.disableTrueTypeHinting = false;
        assertTrue(FontPolicy.isBlocking(res, report, cfg));
    }

    @Test
    void shzAloneIsNotExploitProfile() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 0, 128, 1, new byte[]{0x36}, null, false, false);
        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        assertNotEquals(ThreatSeverity.CRITICAL, FontThreatClassifier.classify(res).getSeverity());
    }

    @Test
    void shzWithIdefIsExploitProfile() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 1, 128, 1, new byte[]{(byte) 0x89, 0x36}, null, false, false);
        ValidationResult res = TrueTypeValidator.validate(ttf, new NoFontShellConfig());
        ThreatReport report = FontThreatClassifier.classify(res);
        assertEquals(ThreatSeverity.CRITICAL, report.getSeverity(), res.joinedReasons());
        assertTrue(FontPolicy.isBlocking(res, report, new NoFontShellConfig()));
    }

    @Test
    void flagsOpcodeHiddenInPushDataWhenJumpPresent() throws IOException {
        byte[] withJump = buildSyntheticTtf(64, 64, 0, 128, 1, new byte[]{(byte) 0xB0, 0x36, 0x1C}, null, false, false);
        ValidationResult res = TrueTypeValidator.validate(withJump, new NoFontShellConfig());
        assertTrue(res.joinedReasons().contains("hidden opcode 0x36"), res.joinedReasons());

        byte[] noJump = buildSyntheticTtf(64, 64, 0, 128, 1, new byte[]{(byte) 0xB0, 0x36}, null, false, false);
        assertTrue(TrueTypeValidator.validate(noJump, new NoFontShellConfig()).isSafe());
    }

    @Test
    void structuralProblemsAlwaysBlock() throws IOException {
        byte[] ttf = buildSyntheticTtf(64, 64, 0, 128, 1, null, null, true, false);
        NoFontShellConfig cfg = new NoFontShellConfig();
        ValidationResult res = TrueTypeValidator.validate(ttf, cfg);
        assertFalse(res.isStructurallySafe());
        assertTrue(FontPolicy.isBlocking(res, FontThreatClassifier.classify(res), cfg));
    }

    private static byte[] buildSyntheticTtf(
            int maxStackElements,
            int maxStorage,
            int maxInstructionDefs,
            int maxSizeOfInstructions,
            int maxComponentDepth,
            byte[] fpgmData,
            byte[] prepData,
            boolean cyclicComposite,
            boolean compositeWithInstructions
    ) throws IOException {

        byte[] head = new byte[54];
        putInt(head, 12, 0x5F0F3CF5);
        putShort(head, 50, 0);

        byte[] maxp = new byte[32];
        putInt(maxp, 0, 0x00010000);
        putShort(maxp, 4, 3);
        putShort(maxp, 18, maxStorage);
        putShort(maxp, 22, maxInstructionDefs);
        putShort(maxp, 24, maxStackElements);
        putShort(maxp, 26, maxSizeOfInstructions);
        putShort(maxp, 28, 2);
        putShort(maxp, 30, maxComponentDepth);

        ByteArrayOutputStream glyfOut = new ByteArrayOutputStream();
        int[] glyphOffsets = new int[4];
        glyphOffsets[0] = 0;
        glyphOffsets[1] = 0;

        DataOutputStream gd = new DataOutputStream(glyfOut);
        gd.writeShort(1);
        gd.writeShort(0); gd.writeShort(0); gd.writeShort(10); gd.writeShort(10);
        gd.writeShort(0);
        gd.writeShort(0);
        glyphOffsets[2] = glyfOut.size();

        if (cyclicComposite) {

            gd.writeShort(-1);
            gd.writeShort(0); gd.writeShort(0); gd.writeShort(10); gd.writeShort(10);
            gd.writeShort(0x0002);
            gd.writeShort(2);
            gd.writeByte(0); gd.writeByte(0);
        } else if (compositeWithInstructions) {

            gd.writeShort(-1);
            gd.writeShort(0); gd.writeShort(0); gd.writeShort(10); gd.writeShort(10);
            gd.writeShort(0x0102);
            gd.writeShort(1);
            gd.writeByte(0); gd.writeByte(0);
            gd.writeShort(2);
            gd.writeByte(0x00); gd.writeByte(0x00);
        } else {

        }
        if ((glyfOut.size() & 1) != 0) {
            gd.writeByte(0);
        }
        glyphOffsets[3] = glyfOut.size();

        byte[] loca = new byte[8];
        for (int i = 0; i < 4; i++) {
            putShort(loca, i * 2, glyphOffsets[i] / 2);
        }

        Map<String, byte[]> tables = new LinkedHashMap<>();
        tables.put("head", head);
        tables.put("maxp", maxp);
        tables.put("loca", loca);
        tables.put("glyf", glyfOut.toByteArray());
        if (fpgmData != null) tables.put("fpgm", fpgmData);
        if (prepData != null) tables.put("prep", prepData);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(0x00010000);
        dos.writeShort(tables.size());
        dos.writeShort(0); dos.writeShort(0); dos.writeShort(0);

        int offset = 12 + tables.size() * 16;
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            dos.write(e.getKey().getBytes(StandardCharsets.ISO_8859_1));
            dos.writeInt(0);
            dos.writeInt(offset);
            dos.writeInt(e.getValue().length);
            offset += (e.getValue().length + 3) & ~3;
        }
        for (byte[] tableBytes : tables.values()) {
            dos.write(tableBytes);
            int pad = ((tableBytes.length + 3) & ~3) - tableBytes.length;
            for (int p = 0; p < pad; p++) dos.writeByte(0);
        }
        return out.toByteArray();
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }
}
