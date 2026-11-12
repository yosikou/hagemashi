package com.yosikou.hagemashi

import java.time.Instant
import java.time.ZoneId

internal val comparisonNow:Instant=Instant.parse("2026-11-10T12:00:00Z")
internal val comparisonZone:ZoneId=ZoneId.of("Asia/Tokyo")
internal suspend fun personaComparisonSnapshot(kind:GenerationKind):HealthSnapshot {
    val source=object:HealthDataSource {
        override suspend fun permissions()=HealthKind.entries.toSet()
        override suspend fun samples(kind:HealthKind,window:HealthWindow):HealthPage {
            val values=if(kind==HealthKind.WEIGHT)listOf("2026-11-01T00:00:00Z" to 70.0,"2026-11-09T00:00:00Z" to 69.8,"2026-11-10T00:00:00Z" to 69.7)
                else listOf("2026-11-01T00:00:00Z" to 23.0,"2026-11-09T00:00:00Z" to 22.8,"2026-11-10T00:00:00Z" to 22.7)
            return HealthPage(values.map{HealthSample(it.second,Instant.parse(it.first),"synthetic.example")}.filter{it.measuredAt>=window.start && it.measuredAt<window.end})
        }
        override suspend fun steps(window:HealthWindow):HealthSteps? {
            val count=when(window.start.atZone(comparisonZone).dayOfMonth){8->5000L;9->4000L;10->3200L;else->null}
            return count?.let{HealthSteps(it,setOf("synthetic.example"))}
        }
    }
    return HealthReader(source).read(comparisonNow,comparisonZone,HealthReadPlan.GENERATION,kind)
}
