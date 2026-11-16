package com.yosikou.hagemashi

// Bump this version when destination, purpose or transmitted fields materially change.
// Presentation and acceptance are independent and local; neither is portable.
internal const val DATA_CONSENT_VERSION = 1
internal fun hasDataConsent(cfg:AppSettings):Boolean =
    cfg.consentControlsVersion==DATA_CONSENT_VERSION && cfg.consentAcceptedVersion==DATA_CONSENT_VERSION
internal fun migrateDataConsent(cfg:AppSettings):AppSettings =
    if(cfg.consentControlsVersion!=DATA_CONSENT_VERSION) cfg.copy(consentControlsVersion=DATA_CONSENT_VERSION,
        consentAcceptedVersion=0, automaticGeneration=false, resumeAutomatic=false) else cfg
internal fun consentDecision(cfg:AppSettings,accept:Boolean):AppSettings = migrateDataConsent(cfg).copy(
    consentShownVersion=DATA_CONSENT_VERSION,consentAcceptedVersion=if(accept)DATA_CONSENT_VERSION else 0,
    automaticGeneration=accept && hasDataConsent(cfg) && cfg.automaticGeneration,resumeAutomatic=false)
internal fun canSendData(cfg:AppSettings,kind:GenerationKind,expectedVersion:Long?=null):Boolean = hasDataConsent(cfg) &&
    (kind==GenerationKind.MANUAL || (!cfg.resumeRequired && cfg.automaticGeneration &&
        cfg.slot(if(kind==GenerationKind.MORNING)NoticeSlot.MORNING else NoticeSlot.EVENING).enabled &&
        (expectedVersion==null || cfg.version(if(kind==GenerationKind.MORNING)NoticeSlot.MORNING else NoticeSlot.EVENING)==expectedVersion)))
// An injectable gate lets tests prove that queued work rereads persisted policy at body write.
internal class GenerationSendGate(private val lock:Any,private val read:()->AppSettings) {
    fun check(kind:GenerationKind,expectedVersion:Long?=null) {
        if(!canSendData(read(),kind,expectedVersion))throw Problem(Failure.CONSENT)
    }
    fun write(kind:GenerationKind,expectedVersion:Long?=null,write:()->Unit) = synchronized(lock) {
        check(kind,expectedVersion);write()
    }
}
