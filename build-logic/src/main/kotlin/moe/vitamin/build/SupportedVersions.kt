package moe.vitamin.build

/**
 * The supported Minecraft floor, and everything that follows from it.
 *
 * Changing which versions this project supports should be one edit, to [FLOOR]. Everything
 * else here is derived: the Paper API coordinate to compile against, the `api-version` in
 * plugin.yml, and the bytecode level the server must be able to load.
 *
 * The reason this exists is a bug that shipped. The floor was 1.13 and the toolchain was Java
 * 21, and nothing objected — until Paper 1.13.2 refused the plugin with
 * UnsupportedClassVersionError, because 1.13 runs on a JVM that reads class file 55 and we
 * were emitting 65. Those two settings lived in different files and neither knew about the
 * other. With the mapping in [requiredJavaRelease] encoded here, that same combination now
 * fails at compile time instead: a 1.13 floor sets `release = 8`, and code using records stops
 * compiling immediately.
 */
object SupportedVersions {

    /** The oldest Minecraft version this project supports. See docs/design.md §5. */
    const val FLOOR = "1.21"

    /** Paper API to compile the agent against — always the floor, never newer. */
    val paperApiCoordinate: String = "io.papermc.paper:paper-api:$FLOOR-R0.1-SNAPSHOT"

    /** `api-version` for plugin.yml: major.minor of the floor. */
    val pluginApiVersion: String = FLOOR.split(".").take(2).joinToString(".")

    /** Bytecode level a server at [FLOOR] can load. */
    val javaRelease: Int = requiredJavaRelease(FLOOR)

    /**
     * The JVM a given Minecraft version requires.
     *
     * Mojang raised this five times, and each step is a hard boundary: a server on the older
     * JVM cannot load bytecode built for the newer one. 26.1 is the calendar version after
     * 1.21.11, and the numeric comparison below sorts it there.
     */
    fun requiredJavaRelease(minecraftVersion: String): Int = when {
        isAtLeast(minecraftVersion, "26.1") -> 25
        isAtLeast(minecraftVersion, "1.20.5") -> 21
        isAtLeast(minecraftVersion, "1.18") -> 17
        isAtLeast(minecraftVersion, "1.17") -> 16
        else -> 8
    }

    /** Numeric version comparison, so "1.21.8" sorts after "1.21.10" is *not* assumed. */
    private fun isAtLeast(version: String, minimum: String): Boolean {
        val actual = parse(version)
        val required = parse(minimum)
        for (i in 0 until maxOf(actual.size, required.size)) {
            val a = actual.getOrElse(i) { 0 }
            val r = required.getOrElse(i) { 0 }
            if (a != r) return a > r
        }
        return true
    }

    private fun parse(version: String): List<Int> =
        version.split("-", limit = 2).first()
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
}
