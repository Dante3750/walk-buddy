package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodTest {
    private val cat = Catalogue.bundled()
    private val dairy = listOf("curd", "paneer", "milk", "ghee", "butter", "lassi", "dahi", "yogurt", "yoghurt", "cheese", "cream")
    private val egg = listOf("egg")
    private val flesh = listOf("chicken", "fish", "mutton", "meat", "prawn", "beef", "pork")
    private val pattern = MonthlyPattern(totalSteps = 240_000, daysCovered = 30, avgCadenceSpm = 110.0, activeEquivMinPerWeek = 200, briskShare = 0.5)

    private fun text(i: FoodItem) = (i.name + " " + i.ingredients.joinToString(" ")).lowercase()

    @Test fun catalogueLoadsWithUniqueIds() {
        assertTrue(cat.size >= 15)
        assertEquals(cat.size, cat.map { it.id }.toSet().size)
        assertTrue(cat.all { it.name.isNotBlank() && it.ingredients.isNotEmpty() })
    }

    @Test fun indiaFirstStaples() {
        val names = cat.joinToString(" ") { it.name.lowercase() }
        for (k in listOf("banana", "curd", "chana", "peanut", "poha", "upma", "idli", "dal", "paneer", "egg", "coconut water", "buttermilk")) assertTrue("missing $k", k in names)
    }

    @Test fun dietLabelsMatchIngredients() {
        for (i in cat) {
            val t = text(i)
            if (egg.any { it in t }) assertEquals("${i.id} contains egg", Diet.Eggetarian, i.diet)
            else if (dairy.any { it in t }) assertTrue("${i.id} contains dairy but is vegan", i.diet != Diet.Vegan)
            assertTrue("${i.id} contains flesh", flesh.none { it in t })
        }
    }

    @Test fun veganFilterHasNoDairyOrEgg() {
        val ideas = RefuelPlanner.suggest(pattern, Diet.Vegan, cat, hotDay = true, afterLongOrBriskWalk = true)
        assertTrue(ideas.all.isNotEmpty())
        for (i in ideas.all) { val t = text(i); assertTrue(i.id, (dairy + egg).none { it in t }) }
        for (i in cat.filter { Diet.Vegan.allows(it.diet) }) { val t = text(i); assertTrue(i.id, (dairy + egg).none { it in t }) }
    }

    @Test fun vegetarianAllowsDairyButNeverEgg() {
        val allowed = cat.filter { Diet.Vegetarian.allows(it.diet) }
        assertTrue(allowed.any { it.id == "curd_lassi" })
        assertTrue(allowed.none { egg.any { e -> e in text(it) } })
        val ideas = RefuelPlanner.suggest(pattern, Diet.Vegetarian, cat, true, true)
        assertTrue(ideas.all.none { egg.any { e -> e in text(it) } })
    }

    @Test fun eggetarianAllowsEverythingInTheCatalogue() {
        assertEquals(cat.size, cat.count { Diet.Eggetarian.allows(it.diet) })
    }

    @Test fun everyDietEveryScenarioStartsWithHydration() {
        for (d in Diet.values()) for (hot in listOf(true, false)) for (after in listOf(true, false)) {
            val r = RefuelPlanner.suggest(pattern, d, cat, hot, after)
            assertTrue("$d hydration non-empty", r.hydration.isNotEmpty())
            assertTrue(r.hydration.all { it.kind == FoodKind.Hydration || it.kind == FoodKind.Electrolyte })
            assertTrue(r.snacks.all { it.kind == FoodKind.Fruit || it.kind == FoodKind.Snack })
            assertEquals(r.hydration + r.afterWalk + r.snacks, r.all)
        }
    }

    @Test fun electrolytesOnlyOnHotDays() {
        assertTrue(RefuelPlanner.suggest(pattern, Diet.Vegan, cat, false, true).all.none { it.kind == FoodKind.Electrolyte })
        assertTrue(RefuelPlanner.suggest(pattern, Diet.Vegan, cat, true, true).hydration.any { it.kind == FoodKind.Electrolyte })
    }

    @Test fun proteinAndCarbsAfterLongerOrBriskerWalks() {
        val light = pattern.copy(totalSteps = 60_000, activeEquivMinPerWeek = 20, briskShare = 0.0)
        assertTrue(RefuelPlanner.suggest(light, Diet.Vegetarian, cat, false, false).afterWalk.isEmpty())
        assertTrue(RefuelPlanner.suggest(light, Diet.Vegetarian, cat, false, true).afterWalk.isNotEmpty())
        assertTrue(RefuelPlanner.suggest(pattern.copy(totalSteps = 400_000, activeEquivMinPerWeek = 400), Diet.Vegetarian, cat, false, false).afterWalk.isNotEmpty())
    }

    @Test fun classification() {
        assertEquals(ActivityLevel.Light, RefuelPlanner.classify(MonthlyPattern(90_000, 30, 80.0, 30, 0.0)))
        assertEquals(ActivityLevel.Moderate, RefuelPlanner.classify(MonthlyPattern(200_000, 30, 100.0, 160, 0.2)))
        assertEquals(ActivityLevel.High, RefuelPlanner.classify(MonthlyPattern(330_000, 30, 115.0, 320, 0.6)))
        assertNull("needs a week of data", RefuelPlanner.classify(MonthlyPattern(50_000, 5, 100.0, 100, 0.5)))
    }

    @Test fun implausibleProfileHidesNumbersButKeepsIdeas() {
        val r = RefuelPlanner.suggest(pattern, Diet.Vegan, cat, false, true, profileOk = false)
        assertNull(r.summary)
        assertTrue(r.all.isNotEmpty())
        assertNotNull(RefuelPlanner.suggest(pattern, Diet.Vegan, cat, false, true).summary)
        assertNull(RefuelPlanner.suggest(pattern.copy(daysCovered = 3), Diet.Vegan, cat, false, true).summary)
    }

    @Test fun disclaimerIsAlwaysPresentAndExact() {
        val exact = "General wellness ideas, not medical or nutrition advice. Talk to a doctor or dietitian for personal needs, especially with a health condition."
        assertEquals(exact, Copy.FOOD_DISCLAIMER)
        assertEquals(exact, RefuelPlanner.suggest(pattern, Diet.Vegan, cat, false, false).disclaimer)
    }

    @Test fun contentNeverFramesFoodAsPunishmentOrDiet() {
        val banned = listOf("burn off", "burn it", "earn", "cheat", "guilt", "detox", "diet plan", "lose weight", "weight loss", "slim", "fat loss", "deficit", "calorie", "kcal", "skip meal", "starve", "binge", "clean eating", "reward")
        val corpus = cat.joinToString(" ") { it.name + " " + it.note }.lowercase() +
            Diet.values().flatMap { d -> listOf(true, false).map { RefuelPlanner.suggest(pattern, d, cat, it, true).headline } }.joinToString(" ").lowercase()
        for (b in banned) assertFalse("banned phrase '$b'", b in corpus)
    }

    @Test fun parserSkipsBrokenEntriesAndToleratesGarbage() {
        val items = Catalogue.parse("""{"items":[{"id":"a","name":"A","diet":"vegan","kind":"fruit"},{"id":"b","name":"B","diet":"mystery","kind":"fruit"},{"name":"no id","diet":"vegan","kind":"fruit"},5]}""")
        assertEquals(listOf("a"), items.map { it.id })
        assertTrue(Catalogue.parse("""{"nothing":1}""").isEmpty())
    }
}
