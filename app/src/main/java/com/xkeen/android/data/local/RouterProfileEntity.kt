package com.xkeen.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xkeen.android.domain.model.RouterProfile

@Entity(tableName = "router_profiles")
data class RouterProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val host: String,
    val username: String,
    val password: String,
    val port: Int = 22
) {
    fun toDomain() = RouterProfile(id, alias, host, username, password, port)

    companion object {
        fun fromDomain(p: RouterProfile) = RouterProfileEntity(p.id, p.alias, p.host, p.username, p.password, p.port)
    }
}
