package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PersonaDefaultsTest {
    private val old=builtInPersonaIds.map{Persona(it,it,"old-$it")}
    private val fresh=old.map{it.copy(prompt="new-${it.id}")}
    @Test fun onlyUntouchedInitialPersonasUpgradeAndSelectionSupplementAndCustomSurvive() {
        val custom=Persona("custom","custom name","custom prompt")
        val editedName=old[1].copy(name="edited")
        val editedPrompt=old[2].copy(prompt=old[2].prompt+" ")
        val cfg=AppSettings(personas=listOf(old[0],editedName,editedPrompt,custom),personaId="custom",supplement="private supplement",goal="68")
        val upgraded=upgradeDefaultPersonas(cfg,old,fresh)
        assertEquals(fresh[0],upgraded.personas[0]);assertEquals(editedName,upgraded.personas[1]);assertEquals(editedPrompt,upgraded.personas[2]);assertEquals(custom,upgraded.personas[3])
        assertEquals(cfg.personaId,upgraded.personaId);assertEquals(cfg.supplement,upgraded.supplement);assertEquals(cfg.goal,upgraded.goal)
        assertEquals(upgraded,upgradeDefaultPersonas(upgraded,old,fresh))
    }
    @Test fun comparisonUsesDeterministicFictionalRecordsAndMorningExcludesToday()=runBlocking {
        val morning=personaComparisonSnapshot(GenerationKind.MORNING)
        val manual=personaComparisonSnapshot(GenerationKind.MANUAL)
        val evening=personaComparisonSnapshot(GenerationKind.EVENING)
        val midnight=comparisonNow.atZone(comparisonZone).toLocalDate().atStartOfDay(comparisonZone).toInstant()
        assertTrue(morning.results.flatMap{it.samples}.all{it.measuredAt<midnight})
        assertTrue(manual.results.flatMap{it.samples}.any{it.measuredAt>=midnight})
        assertEquals(manual,evening)
        assertEquals(manual,personaComparisonSnapshot(GenerationKind.MANUAL))
        assertTrue(manual.results.flatMap{it.days}.any{it.value==null})
        assertTrue(manual.results.flatMap{it.samples}.all{it.origin=="synthetic.example"})
    }
}
