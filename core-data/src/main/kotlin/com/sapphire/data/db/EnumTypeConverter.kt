package com.sapphire.data.db

import androidx.room.TypeConverter

import com.sapphire.domain.model.HealthState
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
}
