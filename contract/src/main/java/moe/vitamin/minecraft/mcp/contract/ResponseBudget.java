package moe.vitamin.minecraft.mcp.contract;

/** The hard ceiling every query tool answers within. */
public record ResponseBudget(int maxItems, int maxBytes) {

    /** Default ceiling: 200 records or 25 KB, whichever comes first. */
    public static final ResponseBudget DEFAULT = new ResponseBudget(200, 25 * 1024);

    public ResponseBudget {
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be at least 1 but was: " + maxItems);
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be at least 1 but was: " + maxBytes);
        }
    }

    /** Clamps a caller-supplied limit into this budget. */
    public int clampLimit(int requested) {
        return requested < 1 ? maxItems : Math.min(requested, maxItems);
    }
}
