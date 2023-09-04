package com.cosmos.unreddit.data.model.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.cosmos.stealth.sdk.data.model.api.ServiceName

@Entity(
    tableName = "subscription",
    primaryKeys = ["name", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Subscription (
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "time")
    val time: Long,

    @ColumnInfo(name = "icon")
    val icon: String?,

    @ColumnInfo(name = "service")
    var service: ServiceName = ServiceName.reddit,

    @ColumnInfo(name = "instance")
    val instance: String? = null,

    @ColumnInfo(name = "profile_id", index = true)
    var profileId: Int = 1
)
