package dev.tinkoprof.nofontshell.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    private final List<String> structuralReasons;
    private final List<String> hintingReasons;
    private final List<String> reasons;
    private final int numGlyphs;
    private final int maxStackElements;
    private final int maxStorage;
    private final int fpgmLength;
    private final int maxObservedCompositeDepth;

    private ValidationResult(
            List<String> structuralReasons,
            List<String> hintingReasons,
            int numGlyphs,
            int maxStackElements,
            int maxStorage,
            int fpgmLength,
            int maxObservedCompositeDepth
    ) {
        this.structuralReasons = List.copyOf(structuralReasons);
        this.hintingReasons = List.copyOf(hintingReasons);
        List<String> all = new ArrayList<>(this.structuralReasons.size() + this.hintingReasons.size());
        all.addAll(this.structuralReasons);
        all.addAll(this.hintingReasons);
        this.reasons = Collections.unmodifiableList(all);
        this.numGlyphs = numGlyphs;
        this.maxStackElements = maxStackElements;
        this.maxStorage = maxStorage;
        this.fpgmLength = fpgmLength;
        this.maxObservedCompositeDepth = maxObservedCompositeDepth;
    }

    public static ValidationResult of(
            List<String> structuralReasons,
            List<String> hintingReasons,
            int numGlyphs,
            int maxStackElements,
            int maxStorage,
            int fpgmLength,
            int maxObservedCompositeDepth
    ) {
        return new ValidationResult(structuralReasons, hintingReasons, numGlyphs, maxStackElements,
                maxStorage, fpgmLength, maxObservedCompositeDepth);
    }

    public static ValidationResult malformed(String reason) {
        return new ValidationResult(List.of(reason), List.of(), -1, -1, -1, -1, -1);
    }

    public boolean isSafe() {
        return reasons.isEmpty();
    }

    public boolean isStructurallySafe() {
        return structuralReasons.isEmpty();
    }

    public List<String> reasons() {
        return reasons;
    }

    public List<String> structuralReasons() {
        return structuralReasons;
    }

    public List<String> hintingReasons() {
        return hintingReasons;
    }

    public String joinedReasons() {
        return String.join("; ", reasons);
    }

    public int numGlyphs() {
        return numGlyphs;
    }

    public int maxStackElements() {
        return maxStackElements;
    }

    public int maxStorage() {
        return maxStorage;
    }

    public int fpgmLength() {
        return fpgmLength;
    }

    public int maxObservedCompositeDepth() {
        return maxObservedCompositeDepth;
    }
}
