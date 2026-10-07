package com.walkbuddy.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ActivityLevel(val label: String) { Light("Light"), Moderate("Moderate"), High("High") }

/** Minimal diet an item fits: VEGAN fits everyone, VEGETARIAN includes dairy, EGGETARIAN includes eggs. */
enum class Diet(val rank: Int, val label: String) {
    Vegan(0, "Vegan"), Vegetarian(1, "Vegetarian"), Eggetarian(2, "Vegetarian + egg");

    fun allows(itemDiet: Diet) = itemDiet.rank <= rank
}

enum class FoodKind { Hydration, Electrolyte, ProteinCarb, Snack, Fruit }

data class FoodItem(
    val id: String,
    val name: String,
    val diet: Diet,
    val kind: FoodKind,
    val ingredients: List<String>,
    val note: String,
    val region: String,
)

data class MonthlyPattern(
    val totalSteps: Long,
    val daysCovered: Int,
    val avgCadenceSpm: Double?,
    val activeEquivMinPerWeek: Int,
    /** Share of walking time at brisk or vigorous cadence, 0..1. */
    val briskShare: Double,
)

object Catalogue {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<FoodItem> {
        val arr = (json.parseToJsonElement(text) as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val diet = when (o.str("diet")?.lowercase()) {
                "vegan" -> Diet.Vegan
                "vegetarian" -> Diet.Vegetarian
                "egg", "eggetarian" -> Diet.Eggetarian
                else -> return@mapNotNull null
            }
            val kind = when (o.str("kind")?.lowercase()) {
                "hydration" -> FoodKind.Hydration
                "electrolyte" -> FoodKind.Electrolyte
                "protein_carb" -> FoodKind.ProteinCarb
                "snack" -> FoodKind.Snack
                "fruit" -> FoodKind.Fruit
                else -> return@mapNotNull null
            }
            FoodItem(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name") ?: return@mapNotNull null,
                diet = diet, kind = kind,
                ingredients = (o["ingredients"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                note = o.str("note") ?: "",
                region = o.str("region") ?: "",
            )
        }
    }

    /** The bundled India-first catalogue (domain/src/main/resources/refuel_catalogue.json). */
    fun bundled(): List<FoodItem> {
        val stream = Catalogue::class.java.classLoader.getResourceAsStream("refuel_catalogue.json") ?: return emptyList()
        return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}

data class RefuelIdeas(
    val level: ActivityLevel?,
    val headline: String,
    /** Hydration always comes first. */
    val hydration: List<FoodItem>,
    val afterWalk: List<FoodItem>,
    val snacks: List<FoodItem>,
    /** Plain-language pattern summary with numbers; null if the profile looked implausible. */
    val summary: String?,
    val disclaimer: String = Copy.FOOD_DISCLAIMER,
) {
    val all: List<FoodItem> get() = hydration + afterWalk + snacks
}

object RefuelPlanner {
    fun classify(p: MonthlyPattern): ActivityLevel? {
        if (p.daysCovered < 7) return null
        val perDay = p.totalSteps.toDouble() / p.daysCovered
        var pts = 0
        pts += if (perDay >= 9000) 2 else if (perDay >= 5500) 1 else 0
        pts += if (p.activeEquivMinPerWeek >= 300) 2 else if (p.activeEquivMinPerWeek >= 150) 1 else 0
        pts += if (p.briskShare >= 0.4) 1 else 0
        return when {
            pts >= 4 -> ActivityLevel.High
            pts >= 2 -> ActivityLevel.Moderate
            else -> ActivityLevel.Light
        }
    }

    /**
     * General ideas only. No calorie targets, no plans, no weight advice.
     * Hydration is always first; electrolytes only on hot days; protein + carbs only after longer or brisker walks.
     */
    fun suggest(
        pattern: MonthlyPattern,
        diet: Diet,
        catalogue: List<FoodItem>,
        hotDay: Boolean,
        afterLongOrBriskWalk: Boolean,
        profileOk: Boolean = true,
    ): RefuelIdeas {
        val allowed = catalogue.filter { diet.allows(it.diet) }
        val level = classify(pattern)
        // Rotate deterministically by pattern so the list is stable within a month but not identical for everyone.
        val seed = (pattern.totalSteps / 1000).toInt()
        fun pick(kind: FoodKind, n: Int): List<FoodItem> {
            val l = allowed.filter { it.kind == kind }
            if (l.isEmpty()) return emptyList()
            return (0 until minOf(n, l.size)).map { l[(seed + it) % l.size] }.distinct()
        }
        val hydration = pick(FoodKind.Hydration, 2) + if (hotDay) pick(FoodKind.Electrolyte, 1) else emptyList()
        val wantsMeal = afterLongOrBriskWalk || level == ActivityLevel.High
        val after = if (wantsMeal) pick(FoodKind.ProteinCarb, 2) else emptyList()
        val snacks = pick(FoodKind.Fruit, 1) + pick(FoodKind.Snack, 1)
        val headline = when (level) {
            null -> "A few steady habits to start with"
            ActivityLevel.Light -> "Water first, simple snacks as you like"
            ActivityLevel.Moderate -> "Water first, a balanced bite after longer walks"
            ActivityLevel.High -> "Water first, then protein with complex carbs after bigger walks"
        }
        val summary = if (!profileOk || level == null) null else
            "This month: about ${pattern.totalSteps / pattern.daysCovered} steps a day over ${pattern.daysCovered} days. Activity level: ${level.label.lowercase()}."
        return RefuelIdeas(level, headline, hydration.distinct(), after, snacks, summary)
    }
}
