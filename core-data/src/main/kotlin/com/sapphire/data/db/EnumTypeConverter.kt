package com.sapphire.data.db

import androidx.room.TypeConverter

import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.ReadMechanism
import com.sapphire.domain.model.ReadState
import com.sapphire.domain.model.SourceKind

/** Room type converters for domain enums. Stored as name() for readability in DB browser. */
class EnumTypeConverter {
    @TypeConverter fun fromSourceKind(kind: SourceKind): String = kind.name
    @TypeConverter fun toSourceKind(value: String): SourceKind =
        runCatching { SourceKind.valueOf(value) }.getOrDefault(SourceKind.RSS)

    @TypeConverter fun fromHealthState(state: HealthState): String = state.name
    @TypeConverter fun toHealthState(value: String): HealthState =
        runCatching { HealthState.valueOf(value) }.getOrDefault(HealthState.OK)

    @TypeConverter fun fromReadState(state: ReadState): String = state.name
    @TypeConverter fun toReadState(value: String): ReadState =
        runCatching { ReadState.valueOf(value) }.getOrDefault(ReadState.UNREAD)

    @TypeConverter fun fromReadMechanism(m: ReadMechanism): String = m.name
    @TypeConverter fun toReadMechanism(value: String): ReadMechanism =
        runCatching { ReadMechanism.valueOf(value) }.getOrDefault(ReadMechanism.MANUAL)

    @TypeConverter fun fromAgentFrequency(f: AgentFrequency): String = f.name
    @TypeConverter fun toAgentFrequency(value: String): AgentFrequency =
        runCatching { AgentFrequency.valueOf(value) }.getOrDefault(AgentFrequency.DAILY)

    @TypeConverter fun fromAgentRecency(r: AgentRecency): String = r.name
    @TypeConverter fun toAgentRecency(value: String): AgentRecency =
        runCatching { AgentRecency.valueOf(value) }.getOrDefault(AgentRecency.WEEK)

    @TypeConverter fun fromAgentStyle(s: AgentStyle): String = s.name
    @TypeConverter fun toAgentStyle(value: String): AgentStyle =
        runCatching { AgentStyle.valueOf(value) }.getOrDefault(AgentStyle.BRIEF)

    @TypeConverter fun fromOutputLanguage(l: OutputLanguage): String = l.name
    @TypeConverter fun toOutputLanguage(value: String): OutputLanguage =
        runCatching { OutputLanguage.valueOf(value) }.getOrDefault(OutputLanguage.EN)


    @TypeConverter fun fromAgentRunStatus(s: AgentRunStatus): String = s.name
    @TypeConverter fun toAgentRunStatus(value: String): AgentRunStatus =
        runCatching { AgentRunStatus.valueOf(value) }.getOrDefault(AgentRunStatus.EMPTY)
}
