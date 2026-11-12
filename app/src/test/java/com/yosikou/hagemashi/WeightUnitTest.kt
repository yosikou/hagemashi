package com.yosikou.hagemashi

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WeightUnitTest {
    private val personas = listOf(Persona("friend","friend","prompt"),Persona("numbers","numbers","prompt"),Persona("companion","companion","prompt"))
    @Test fun kgDefaultsAndLegacySettingsSurviveRoundTripAndUnitChanges() {
        val original=AppSettings(goal="68.123456",personas=personas,supplement="keep",modelId="model")
        val legacy=encodeSettings(original).apply{remove("weightUnit")}
        assertEquals(original,decodeSettings(legacy,personas))
        var value=original
        repeat(100){value=decodeSettings(encodeSettings(value.copy(weightUnit=if(it%2==0)WeightUnit.LB else WeightUnit.KG)),personas)}
        assertEquals(original,value)
        assertEquals("68.123456",value.goal)
        val lb=original.copy(weightUnit=WeightUnit.LB)
        assertEquals(lb,decodeSettings(encodeSettings(lb),personas))
        assertEquals(original.morningVersion,nextSettings(original,lb).morningVersion)
    }
    @Test fun positiveDecimalInputConvertsExactlyAndRejectsInvalidPrecision() {
        assertEquals("68.123456",weightInputKg("６８.１２３４５６",WeightUnit.KG))
        assertEquals("68.0388555",weightInputKg("150",WeightUnit.LB))
        assertEquals("0.45359237",weightInputKg("1",WeightUnit.LB))
        for(v in listOf("0","-1","NaN","1e2","2 kg","1,2","1.1234567","Infinity",""))assertNull(v,weightInputKg(v,WeightUnit.KG))
        assertEquals("150",WeightUnit.LB.input("68.0388555"))
    }
    @Test fun openingAndSavingAnUneditedRoundedDraftNeverChangesKg() {
        val kg="68.123456789012345"
        for(unit in WeightUnit.entries) {
            val draft=unit.input(kg)
            assertEquals(kg,goalDraftKg(draft,draft,kg,unit))
            assertEquals(weightInputKg("100.125",unit),goalDraftKg("100.125",draft,kg,unit))
        }
        assertEquals("150.13",WeightUnit.LB.display("68.1"))
    }
    @Test fun displayConversionDoesNotRegroupDistinctValuesOrRewriteHistory() {
        val at="2026-11-01T00:00:00Z"
        val rows=listOf(MeasurementRow(MeasureItem.WEIGHT,"68.000001","kg",at),MeasurementRow(MeasureItem.WEIGHT,"68.000002","kg",at),
            MeasurementRow(MeasureItem.BODY_FAT,"23","%",at),MeasurementRow(MeasureItem.STEPS,null,"歩",at,MeasureState.UNPERMITTED))
        val projected=measurementDisplay(rows,ZoneId.of("UTC")).rows.map{weightDisplayRow(it,WeightUnit.LB)}
        assertEquals(4,projected.size)
        assertEquals(projected[0].value,projected[1].value)
        assertEquals(rows[2],projected[2]);assertEquals(rows[3],projected[3])
        val saved=SavedEncouragement("00000000-0000-0000-0000-000000000001",Instant.EPOCH,"original short","original 68 kg full","original input",true,rows=rows)
        val encoded=encodeEncouragement(saved).toString()
        val loaded=decodeEncouragement(saved.id,org.json.JSONObject(encoded))
        WeightUnit.entries.forEach{unit->loaded.rows.map{weightDisplayRow(it,unit)}}
        assertEquals(encoded,encodeEncouragement(loaded).toString())
        assertEquals(saved.full,loaded.full);assertEquals(saved.short,loaded.short)
        val missing=weightDisplayRow(MeasurementRow(MeasureItem.WEIGHT,null,"kg",at,MeasureState.NO_RECORDS),WeightUnit.LB)
        assertNull(missing.value);assertEquals(MeasureState.NO_RECORDS,missing.state)
    }
    @Test fun eachGenerationKindUsesOneUnitAndCanonicalRowsStayInKg()=runBlocking {
        val labels=HealthPromptLabels("goal=%s kg","read=%s","%s %s %s","measurements","steps","%s %s %s %s %s","%s %s %s %s","%s steps","%s %s","weight","fat","kg","%","unknown","partial",HealthReadStatus.entries.associateWith{it.name})
        for(kind in GenerationKind.entries) {
            val snapshot=personaComparisonSnapshot(kind)
            val kg=healthGenerationInput("68",snapshot,comparisonZone,labels,WeightUnit.KG)
            val lb=healthGenerationInput("68",snapshot,comparisonZone,labels,WeightUnit.LB)
            assertTrue(kg.contains("goal=68 kg"));assertFalse(lb.contains(" kg"))
            assertTrue(lb.contains("goal="+WeightUnit.LB.prompt("68")+" lb"))
            val rows=measurementRows(snapshot,"68",null,comparisonZone)
            rows.filter{it.item in setOf(MeasureItem.WEIGHT,MeasureItem.GOAL)}.forEach{row->
                assertEquals("kg",row.unit)
                assertTrue(lb.contains(weightDisplayRow(row,WeightUnit.LB,true).value+" lb"))
            }
            assertEquals(kg,healthGenerationInput("68",snapshot,comparisonZone,labels))
        }
    }
}
