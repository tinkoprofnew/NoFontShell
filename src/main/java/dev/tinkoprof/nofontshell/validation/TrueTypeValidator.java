package dev.tinkoprof.nofontshell.validation;

import dev.tinkoprof.nofontshell.config.NoFontShellConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TrueTypeValidator {
    private static final int SFNT_TRUETYPE_1 = 0x00010000;
    private static final int SFNT_TRUETYPE_MAC = 0x74727565;
    private static final int SFNT_TYP1 = 0x74797031;
    private static final int SFNT_OTTO = 0x4F54544F;
    private static final int HEAD_MAGIC = 0x5F0F3CF5;
    private static final int MAXP_VERSION_1_0 = 0x00010000;
    private static final int MAXP_VERSION_0_5 = 0x00005000;

    private static final int ARG_1_AND_2_ARE_WORDS = 0x0001;
    private static final int WE_HAVE_A_SCALE = 0x0008;
    private static final int MORE_COMPONENTS = 0x0020;
    private static final int WE_HAVE_AN_X_AND_Y_SCALE = 0x0040;
    private static final int WE_HAVE_A_TWO_BY_TWO = 0x0080;
    private static final int WE_HAVE_INSTRUCTIONS = 0x0100;

    private static final int OP_SHZ_0 = 0x36;
    private static final int OP_SHZ_1 = 0x37;
    private static final int OP_NPUSHB = 0x40;
    private static final int OP_NPUSHW = 0x41;
    private static final int OP_IDEF = 0x89;
    private static final int OP_JMPR = 0x1C;
    private static final int OP_JROT = 0x78;
    private static final int OP_JROF = 0x79;
    private static final int OP_PUSHB_MIN = 0xB0;
    private static final int OP_PUSHB_MAX = 0xB7;
    private static final int OP_PUSHW_MIN = 0xB8;
    private static final int OP_PUSHW_MAX = 0xBF;

    private static final int MAX_REASONABLE_TABLES = 128;

    private record TableRecord(String tag, int offset, int length) {}

    private TrueTypeValidator() {}

    public static ValidationResult validate(byte[] data, NoFontShellConfig config) {
        if (data == null || data.length < 12) {
            return ValidationResult.malformed("Empty or truncated SFNT header (< 12 bytes)");
        }
        if (data.length > config.maxFileSizeBytes) {
            return ValidationResult.malformed("Font size " + data.length + " exceeds maxFileSizeBytes (" + config.maxFileSizeBytes + ")");
        }

        List<String> structural = new ArrayList<>();
        List<String> hinting = new ArrayList<>();

        int sfntVersion = readS32(data, 0);
        if (sfntVersion != SFNT_TRUETYPE_1
                && sfntVersion != SFNT_TRUETYPE_MAC
                && sfntVersion != SFNT_TYP1
                && sfntVersion != SFNT_OTTO) {
            return ValidationResult.malformed(String.format("Unsupported or invalid SFNT version: 0x%08X", sfntVersion));
        }

        int numTables = readU16(data, 4);
        if (numTables == 0 || numTables > MAX_REASONABLE_TABLES) {
            return ValidationResult.malformed("Invalid SFNT table count: " + numTables);
        }

        long tableDirEnd = 12L + (long) numTables * 16L;
        if (tableDirEnd > data.length) {
            return ValidationResult.malformed("Truncated SFNT table directory");
        }

        Map<String, TableRecord> tables = new HashMap<>(numTables * 2);
        for (int i = 0; i < numTables; i++) {
            int entryOff = 12 + i * 16;
            String tag = new String(data, entryOff, 4, StandardCharsets.ISO_8859_1);
            long tOffset = readU32(data, entryOff + 8);
            long tLength = readU32(data, entryOff + 12);

            if (tOffset > data.length || tLength > data.length || tOffset + tLength > data.length) {
                return ValidationResult.malformed("Table '" + sanitizeTag(tag) + "' exceeds file bounds (offset=" + tOffset + ", length=" + tLength + ")");
            }
            if (tables.put(tag, new TableRecord(tag, (int) tOffset, (int) tLength)) != null) {
                structural.add("Duplicate SFNT table '" + sanitizeTag(tag) + "'");
            }
        }

        TableRecord head = tables.get("head");
        if (head == null || head.length < 54) {
            return ValidationResult.malformed("Missing or truncated 'head' table");
        }
        int headMagic = readS32(data, head.offset + 12);
        if (headMagic != HEAD_MAGIC) {
            return ValidationResult.malformed(String.format("Invalid 'head' magic number: 0x%08X", headMagic));
        }
        int indexToLocFormat = readS16(data, head.offset + 50);
        if (indexToLocFormat != 0 && indexToLocFormat != 1) {
            return ValidationResult.malformed("Invalid head.indexToLocFormat: " + indexToLocFormat);
        }

        TableRecord maxp = tables.get("maxp");
        if (maxp == null || maxp.length < 6) {
            return ValidationResult.malformed("Missing or truncated 'maxp' table");
        }
        int maxpVersion = readS32(data, maxp.offset);
        int numGlyphs = readU16(data, maxp.offset + 4);
        int maxStackElements = 0;
        int maxStorage = 0;

        if (maxpVersion == MAXP_VERSION_1_0) {
            if (maxp.length < 32) {
                return ValidationResult.malformed("Truncated 'maxp' v1.0 table (" + maxp.length + " bytes)");
            }
            maxStorage = readU16(data, maxp.offset + 18);
            int maxInstructionDefs = readU16(data, maxp.offset + 22);
            maxStackElements = readU16(data, maxp.offset + 24);
            int maxSizeOfInstructions = readU16(data, maxp.offset + 26);
            int maxComponentElements = readU16(data, maxp.offset + 28);
            int maxComponentDepth = readU16(data, maxp.offset + 30);

            if (maxStackElements > config.maxStackElements) {
                hinting.add("maxp.maxStackElements=" + maxStackElements + " exceeds limit " + config.maxStackElements);
            }
            if (maxStorage > config.maxStorage) {
                hinting.add("maxp.maxStorage=" + maxStorage + " exceeds limit " + config.maxStorage);
            }
            if (maxInstructionDefs > config.maxInstructionDefs) {
                hinting.add("maxp.maxInstructionDefs=" + maxInstructionDefs + " exceeds limit " + config.maxInstructionDefs);
            }
            if (maxSizeOfInstructions > config.maxSizeOfInstructions) {
                hinting.add("maxp.maxSizeOfInstructions=" + maxSizeOfInstructions + " exceeds limit " + config.maxSizeOfInstructions);
            }
            if (maxComponentElements > config.maxCompositeComponents) {
                hinting.add("maxp.maxComponentElements=" + maxComponentElements + " exceeds limit " + config.maxCompositeComponents);
            }
            if (maxComponentDepth > config.maxCompositeDepth) {
                hinting.add("maxp.maxComponentDepth=" + maxComponentDepth + " exceeds limit " + config.maxCompositeDepth);
            }
        } else if (maxpVersion != MAXP_VERSION_0_5) {
            return ValidationResult.malformed(String.format("Unsupported 'maxp' version: 0x%08X", maxpVersion));
        }

        int fpgmLen = 0;
        TableRecord fpgm = tables.get("fpgm");
        if (fpgm != null) {
            fpgmLen = fpgm.length;
            if (fpgm.length > config.maxFpgmBytes) {
                hinting.add("fpgm table size " + fpgm.length + " exceeds limit " + config.maxFpgmBytes);
            }
            if (config.blockDangerousHintingOpcodes) {
                inspectBytecode(data, fpgm.offset, fpgm.length, "fpgm", hinting);
            }
        }

        TableRecord prep = tables.get("prep");
        if (prep != null) {
            if (prep.length > config.maxPrepBytes) {
                hinting.add("prep table size " + prep.length + " exceeds limit " + config.maxPrepBytes);
            }
            if (config.blockDangerousHintingOpcodes) {
                inspectBytecode(data, prep.offset, prep.length, "prep", hinting);
            }
        }

        TableRecord cvt = tables.get("cvt ");
        if (cvt != null) {
            if ((cvt.length & 1) != 0) {
                structural.add("cvt table length " + cvt.length + " is not 2-byte aligned");
            }
            if (cvt.length > config.maxCvtBytes) {
                hinting.add("cvt table size " + cvt.length + " exceeds limit " + config.maxCvtBytes);
            }
        }

        int maxObservedCompositeDepth = 0;
        TableRecord loca = tables.get("loca");
        TableRecord glyf = tables.get("glyf");
        if (loca != null || glyf != null) {
            if (loca == null || glyf == null) {
                return ValidationResult.malformed("Inconsistent TrueType outline tables (only one of 'loca'/'glyf' present)");
            }
            maxObservedCompositeDepth = inspectGlyfAndLoca(data, loca, glyf, numGlyphs, indexToLocFormat, config, structural, hinting);
            if (maxObservedCompositeDepth < 0) {
                return ValidationResult.of(structural, hinting, numGlyphs, maxStackElements, maxStorage, fpgmLen, -1);
            }
        }

        return ValidationResult.of(structural, hinting, numGlyphs, maxStackElements, maxStorage, fpgmLen, maxObservedCompositeDepth);
    }

    private static int inspectGlyfAndLoca(
            byte[] data,
            TableRecord loca,
            TableRecord glyf,
            int numGlyphs,
            int indexToLocFormat,
            NoFontShellConfig config,
            List<String> reasons,
            List<String> hinting
    ) {
        int entrySize = (indexToLocFormat == 0) ? 2 : 4;
        long requiredLocaBytes = (long) (numGlyphs + 1) * entrySize;
        if (loca.length < requiredLocaBytes) {
            reasons.add("Truncated 'loca' table: expected at least " + requiredLocaBytes + " bytes, got " + loca.length);
            return -1;
        }

        int[] offsets = new int[numGlyphs + 1];
        for (int i = 0; i <= numGlyphs; i++) {
            long relOff;
            if (indexToLocFormat == 0) {
                relOff = (long) readU16(data, loca.offset + i * 2) * 2L;
            } else {
                relOff = readU32(data, loca.offset + i * 4);
            }
            if (relOff > glyf.length) {
                reasons.add("loca[" + i + "] offset " + relOff + " exceeds glyf length " + glyf.length);
                return -1;
            }
            if (i > 0 && relOff < offsets[i - 1]) {
                reasons.add("Non-monotonic loca offsets at glyph " + (i - 1));
                return -1;
            }
            offsets[i] = (int) relOff;
        }

        Map<Integer, List<Integer>> compositeChildren = new HashMap<>();
        boolean reportedCompositeInstr = false;
        boolean reportedGlyphInstrSize = false;
        boolean reportedGlyphOpcode = false;

        for (int gid = 0; gid < numGlyphs; gid++) {
            int start = offsets[gid];
            int end = offsets[gid + 1];
            if (start == end) {
                continue;
            }
            if (end - start < 10) {
                reasons.add("Truncated glyph header at gid=" + gid);
                return -1;
            }

            int absStart = glyf.offset + start;
            int absEnd = glyf.offset + end;
            int numberOfContours = readS16(data, absStart);

            if (numberOfContours >= 0) {

                long instrLenPos = (long) absStart + 10L + (long) numberOfContours * 2L;
                if (instrLenPos + 2 > absEnd) {
                    reasons.add("Truncated simple glyph contour/instruction header at gid=" + gid);
                    return -1;
                }
                int instrLen = readU16(data, (int) instrLenPos);
                long instrStart = instrLenPos + 2;
                if (instrStart + instrLen > absEnd) {
                    reasons.add("Simple glyph instructions exceed glyph bounds at gid=" + gid);
                    return -1;
                }
                if (instrLen > config.maxSizeOfInstructions && !reportedGlyphInstrSize) {
                    hinting.add("Glyph " + gid + " instruction length " + instrLen + " exceeds limit " + config.maxSizeOfInstructions);
                    reportedGlyphInstrSize = true;
                }
                if (instrLen > 0 && config.blockDangerousHintingOpcodes && !reportedGlyphOpcode) {
                    int before = hinting.size();
                    inspectBytecode(data, (int) instrStart, instrLen, "glyf[" + gid + "]", hinting);
                    if (hinting.size() > before) {
                        reportedGlyphOpcode = true;
                    }
                }
            } else {

                int pos = absStart + 10;
                int componentCount = 0;
                boolean hasInstructions = false;
                List<Integer> children = new ArrayList<>(4);

                while (true) {
                    if (pos + 4 > absEnd) {
                        reasons.add("Truncated composite glyph component record at gid=" + gid);
                        return -1;
                    }
                    int flags = readU16(data, pos);
                    int childGid = readU16(data, pos + 2);
                    pos += 4;
                    componentCount++;

                    if (childGid >= numGlyphs) {
                        reasons.add("Composite glyph " + gid + " references out-of-range glyphIndex " + childGid);
                        return -1;
                    }
                    children.add(childGid);

                    if (componentCount > config.maxCompositeComponents) {
                        reasons.add("Composite glyph " + gid + " has >" + config.maxCompositeComponents + " components");
                        break;
                    }

                    pos += ((flags & ARG_1_AND_2_ARE_WORDS) != 0) ? 4 : 2;
                    if ((flags & WE_HAVE_A_SCALE) != 0) {
                        pos += 2;
                    } else if ((flags & WE_HAVE_AN_X_AND_Y_SCALE) != 0) {
                        pos += 4;
                    } else if ((flags & WE_HAVE_A_TWO_BY_TWO) != 0) {
                        pos += 8;
                    }
                    if (pos > absEnd) {
                        reasons.add("Composite glyph component arguments exceed glyph bounds at gid=" + gid);
                        return -1;
                    }

                    if ((flags & WE_HAVE_INSTRUCTIONS) != 0) {
                        hasInstructions = true;
                    }
                    if ((flags & MORE_COMPONENTS) == 0) {
                        break;
                    }
                }

                compositeChildren.put(gid, children);

                if (hasInstructions) {
                    if (pos + 2 > absEnd) {
                        reasons.add("Truncated composite glyph instruction length at gid=" + gid);
                        return -1;
                    }
                    int instrLen = readU16(data, pos);
                    pos += 2;
                    if (pos + instrLen > absEnd) {
                        reasons.add("Composite glyph instructions exceed glyph bounds at gid=" + gid);
                        return -1;
                    }
                    if (config.rejectCompositeGlyphInstructions && instrLen > 0 && !reportedCompositeInstr) {
                        hinting.add("Composite glyph " + gid + " contains hinting instructions (" + instrLen + " bytes)");
                        reportedCompositeInstr = true;
                    }
                    if (instrLen > config.maxSizeOfInstructions && !reportedGlyphInstrSize) {
                        hinting.add("Composite glyph " + gid + " instruction length " + instrLen + " exceeds limit " + config.maxSizeOfInstructions);
                        reportedGlyphInstrSize = true;
                    }
                    if (instrLen > 0 && config.blockDangerousHintingOpcodes && !reportedGlyphOpcode) {
                        int before = hinting.size();
                        inspectBytecode(data, pos, instrLen, "glyf-composite[" + gid + "]", hinting);
                        if (hinting.size() > before) {
                            reportedGlyphOpcode = true;
                        }
                    }
                }
            }
        }

        if (compositeChildren.isEmpty()) {
            return 0;
        }
        Map<Integer, Integer> depthMemo = new HashMap<>(compositeChildren.size() * 2);
        Map<Integer, Integer> visitState = new HashMap<>(compositeChildren.size() * 2);
        int maxDepth = 0;

        for (Integer gid : compositeChildren.keySet()) {
            int depth = computeCompositeDepth(gid, compositeChildren, depthMemo, visitState, config.maxCompositeDepth + 2);
            if (depth < 0) {
                reasons.add("Cyclic composite glyph reference detected involving gid=" + gid);
                return -1;
            }
            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }

        if (maxDepth > config.maxCompositeDepth) {
            reasons.add("Observed composite glyph nesting depth " + maxDepth + " exceeds limit " + config.maxCompositeDepth);
        }
        return maxDepth;
    }

    private static int computeCompositeDepth(
            int gid,
            Map<Integer, List<Integer>> compositeChildren,
            Map<Integer, Integer> depthMemo,
            Map<Integer, Integer> visitState,
            int hardCap
    ) {
        Integer cached = depthMemo.get(gid);
        if (cached != null) {
            return cached;
        }
        Integer state = visitState.get(gid);
        if (state != null && state == 1) {
            return -1;
        }
        List<Integer> children = compositeChildren.get(gid);
        if (children == null || children.isEmpty()) {
            return 0;
        }

        visitState.put(gid, 1);
        int maxChildDepth = 0;
        for (int child : children) {
            int childDepth = computeCompositeDepth(child, compositeChildren, depthMemo, visitState, hardCap);
            if (childDepth < 0) {
                return -1;
            }
            if (childDepth > maxChildDepth) {
                maxChildDepth = childDepth;
            }
            if (maxChildDepth >= hardCap) {
                break;
            }
        }
        visitState.put(gid, 2);
        int depth = 1 + maxChildDepth;
        depthMemo.put(gid, depth);
        return depth;
    }

    private static void inspectBytecode(byte[] data, int offset, int length, String sourceName, List<String> reasons) {
        int end = offset + length;
        int i = offset;
        boolean foundShz = false;
        boolean foundIdef = false;
        boolean hasJump = false;
        boolean hiddenShz = false;
        boolean hiddenIdef = false;

        while (i < end) {
            int op = data[i++] & 0xFF;
            int payloadStart;
            int payloadLen;
            if (op == OP_NPUSHB) {
                if (i >= end) {
                    reasons.add("Truncated NPUSHB instruction in " + sourceName);
                    return;
                }
                int count = data[i++] & 0xFF;
                payloadStart = i;
                payloadLen = count;
            } else if (op == OP_NPUSHW) {
                if (i >= end) {
                    reasons.add("Truncated NPUSHW instruction in " + sourceName);
                    return;
                }
                int count = data[i++] & 0xFF;
                payloadStart = i;
                payloadLen = count * 2;
            } else if (op >= OP_PUSHB_MIN && op <= OP_PUSHB_MAX) {
                payloadStart = i;
                payloadLen = (op - OP_PUSHB_MIN) + 1;
            } else if (op >= OP_PUSHW_MIN && op <= OP_PUSHW_MAX) {
                payloadStart = i;
                payloadLen = ((op - OP_PUSHW_MIN) + 1) * 2;
            } else {
                if (op == OP_JMPR || op == OP_JROT || op == OP_JROF) {
                    hasJump = true;
                } else if ((op == OP_SHZ_0 || op == OP_SHZ_1) && !foundShz) {
                    reasons.add("Dangerous TrueType bytecode opcode SHZ (0x" + Integer.toHexString(op) + ") detected in " + sourceName);
                    foundShz = true;
                } else if (op == OP_IDEF && !foundIdef) {
                    reasons.add("TrueType IDEF opcode (0x89) detected in " + sourceName);
                    foundIdef = true;
                }
                continue;
            }

            if (payloadStart + payloadLen > end) {
                reasons.add("Truncated push payload (opcode 0x" + Integer.toHexString(op) + ") in " + sourceName);
                return;
            }
            for (int k = payloadStart; k < payloadStart + payloadLen; k++) {
                int b = data[k] & 0xFF;
                if (b == OP_SHZ_0 || b == OP_SHZ_1) {
                    hiddenShz = true;
                } else if (b == OP_IDEF) {
                    hiddenIdef = true;
                }
            }
            i = payloadStart + payloadLen;
        }

        if (hasJump && !foundShz && hiddenShz) {
            reasons.add("Possible jump-reachable hidden opcode 0x36/0x37 inside push data in " + sourceName);
        }
        if (hasJump && !foundIdef && hiddenIdef) {
            reasons.add("Possible jump-reachable hidden opcode 0x89 inside push data in " + sourceName);
        }
    }

    private static int readU16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static int readS16(byte[] data, int off) {
        return (short) readU16(data, off);
    }

    private static int readS32(byte[] data, int off) {
        return ((data[off] & 0xFF) << 24)
                | ((data[off + 1] & 0xFF) << 16)
                | ((data[off + 2] & 0xFF) << 8)
                | (data[off + 3] & 0xFF);
    }

    private static long readU32(byte[] data, int off) {
        return Integer.toUnsignedLong(readS32(data, off));
    }

    private static String sanitizeTag(String tag) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            sb.append((c >= 0x20 && c <= 0x7E) ? c : '?');
        }
        return sb.toString();
    }
}
