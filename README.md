# NoFontShell

Client-side Fabric mod for Minecraft 26.3 that disables FreeType TrueType hinting bytecode execution (`FT_LOAD_NO_HINTING`) and validates TrueType (`.ttf`) fonts from local and server resource packs before they reach FreeType. Written to mitigate the FreeType TrueType bytecode interpreter vulnerability (`Ins_SHZ`, see [`minecraft2shell`](https://github.com/bikini/minecraft2shell)).

## What it does

- Adds `FT_LOAD_NO_HINTING` to `FreeType.FT_Load_Glyph` in `TrueTypeGlyphProvider#loadGlyph` (configurable via `disableTrueTypeHinting`), preventing the FreeType TrueType bytecode interpreter from executing on resource-pack fonts.
- Hooks `ReloadableResourceManager#createReload` (`CLIENT_RESOURCES`) and `DownloadedPackSource#loadRequestedPacks` to scan both local and server-delivered resource packs.
- Intercepts `TrueTypeGlyphProviderDefinition#load` before `FreeType.FT_New_Memory_Face` is called.
- Parses SFNT headers and TrueType tables (`head`, `maxp`, `loca`, `glyf`, `fpgm`, `prep`, `cvt `) in Java with bounds checks, separating **structural** violations from **hinting** heuristics (`FontPolicy`):
  - **Structural checks (always blocking):** malformed/out-of-bounds SFNT tables, duplicate tables, invalid `head`/`maxp`/`loca`/`glyf` headers, non-monotonic `loca` offsets, out-of-range component references, composite glyph nesting depth above `maxCompositeDepth`, and composite glyph reference cycles.
  - **Exploit profile detection (always blocking):** custom instruction definitions (`IDEF` / `maxInstructionDefs > 0`) combined with `SHZ` (`0x36`/`0x37`), jump-reachable hidden opcodes (`JMPR`/`JROT`/`JROF` with `0x36`/`0x37`/`0x89` inside `PUSHB`/`PUSHW`/`NPUSHB`/`NPUSHW` payloads), composite glyph instructions, or oversized VM stack/`fpgm` (`FREETYPE_VM_EXPLOIT_PROFILE`).
  - **Hinting heuristics (advisory when `FT_LOAD_NO_HINTING` is active, blocking when `strictBytecodeChecks=true` or hinting is enabled):** `maxp` VM limits (`maxStackElements`, `maxStorage`, `maxSizeOfInstructions`, `maxComponentElements`, `maxComponentDepth`), `fpgm`/`prep`/`cvt ` table sizes, standalone `SHZ`, and composite glyph `WE_HAVE_INSTRUCTIONS`.
  - Flags packs bundling unusually large numbers of `.ttf` faces (`maxTtfFacesPerPack`).
- Replaces rejected fonts with an empty fallback `GlyphProvider` (`skip_font`), or excludes the entire pack (`block_pack`), without crashing the client.

## Configuration

Generated at `config/nofontshell.json`:

```json
{
  "action": "skip_font",
  "disableTrueTypeHinting": true,
  "validateBuiltInPacks": false,
  "maxTtfFacesPerPack": 32,
  "maxFileSizeBytes": 16777216,
  "maxStackElements": 1024,
  "maxStorage": 2048,
  "maxInstructionDefs": 0,
  "maxSizeOfInstructions": 4096,
  "maxFpgmBytes": 4096,
  "maxPrepBytes": 4096,
  "maxCvtBytes": 4096,
  "maxCompositeDepth": 8,
  "maxCompositeComponents": 32,
  "rejectCompositeGlyphInstructions": true,
  "blockDangerousHintingOpcodes": true,
  "strictBytecodeChecks": false
}
```

Actions:
- `log_only`: logs warnings without blocking.
- `skip_font`: skips flagged `.ttf` fonts and falls back to remaining/default providers.
- `block_pack`: drops the offending resource pack from reload/activation.

## Client Commands

- `/nofontshell status`
- `/nofontshell history`
- `/nofontshell mode <log_only|skip_font|block_pack>`
- `/nofontshell hinting off`
- `/nofontshell hinting on confirm` (requires explicit `confirm`)
- `/nofontshell reload`
- `/nofontshell export` (writes `config/nofontshell-report.txt`)

## Scope

This mod disables TrueType hinting bytecode execution (`FT_LOAD_NO_HINTING`) and validates `.ttf` inputs before FreeType sees them. It does not patch native memory-safety bugs inside `libfreetype` itself (e.g. non-hinting parser or rasterizer bugs). Update Minecraft / LWJGL / FreeType once an upstream fix is available.

## Build

Requires Java 25.

```bash
./gradlew clean build
```

Output JAR: `build/libs/nofontshell-tested.jar`

## Test Results

Run with `./gradlew test` (JUnit 5). Optionally set `NOFONTSHELL_SAMPLE_DIR` to an extracted sample pack's font directory to run the real-sample test:

| Test | Result | Notes |
| --- | --- | --- |
| `acceptsValidMinimalTrueTypeFont` | PASS | Allows valid `.ttf` (including `0x36` inside `PUSHB` immediate data without jump opcodes) |
| `rejectsHighMaxStackElements` | PASS | Flags `maxp.maxStackElements=8192` |
| `rejectsShzOpcodeInFpgm` | PASS | Flags `SHZ` (`0x36`) opcode in `fpgm` |
| `rejectsCompositeGlyphCycle` | PASS | Detects self-referencing composite glyph cycle |
| `rejectsCompositeGlyphWithInstructions` | PASS | Flags composite glyph with `WE_HAVE_INSTRUCTIONS` |
| `handlesMalformedBinariesGracefully` | PASS | Rejects null/empty/truncated/non-SFNT binaries without throwing |
| `hintingOnlyHeuristicsAreAdvisoryWhenHintingDisabled` | PASS | Keeps hinting-only findings advisory when `FT_LOAD_NO_HINTING` is enabled and `strictBytecodeChecks=false` |
| `shzAloneIsNotExploitProfile` | PASS | Avoids `CRITICAL` classification on standalone `SHZ` without `IDEF` |
| `shzWithIdefIsExploitProfile` | PASS | Classifies `IDEF` + `SHZ` as `CRITICAL` (`FREETYPE_VM_EXPLOIT_PROFILE`) and blocks under default policy |
| `flagsOpcodeHiddenInPushDataWhenJumpPresent` | PASS | Detects `0x36`/`0x37`/`0x89` hidden inside push immediate data when `JMPR`/`JROT`/`JROF` is present |
| `structuralProblemsAlwaysBlock` | PASS | Ensures structural violations always block regardless of hinting state |
| `detectsRealMinecraft2ShellSamplePack` | PASS | Flags all 144 `.ttf` files as `CRITICAL` (`FREETYPE_VM_EXPLOIT_PROFILE`) and blocking (skipped if `NOFONTSHELL_SAMPLE_DIR` is unset) |
