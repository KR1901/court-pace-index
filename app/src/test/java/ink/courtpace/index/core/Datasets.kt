package ink.courtpace.index.core

/** Loads the bundled dataset from the unit-test classpath. */
object Datasets {
    fun raw(): String =
        Datasets::class.java.classLoader?.getResourceAsStream("pace_dataset.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("pace_dataset.json is not on the test classpath")
}
