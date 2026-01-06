package com.terminox.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.dao.ConnectionEventDao
import com.terminox.data.local.database.dao.Ec2InstanceDao
import com.terminox.data.local.database.dao.PairedAgentDao
import com.terminox.data.local.database.dao.SnippetCategoryDao
import com.terminox.data.local.database.dao.SnippetDao
import com.terminox.data.local.database.dao.SshKeyDao
import com.terminox.data.local.database.dao.TrustedHostDao
import com.terminox.data.local.database.entity.ConnectionEntity
import com.terminox.data.local.database.entity.ConnectionEventEntity
import com.terminox.data.local.database.entity.Ec2InstanceEntity
import com.terminox.data.local.database.entity.PairedAgentEntity
import com.terminox.data.local.database.entity.SnippetCategoryEntity
import com.terminox.data.local.database.entity.SnippetEntity
import com.terminox.data.local.database.entity.SshKeyEntity
import com.terminox.data.local.database.entity.TrustedHostEntity

@Database(
    entities = [
        ConnectionEntity::class,
        SshKeyEntity::class,
        TrustedHostEntity::class,
        ConnectionEventEntity::class,
        PairedAgentEntity::class,
        Ec2InstanceEntity::class,
        SnippetEntity::class,
        SnippetCategoryEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun trustedHostDao(): TrustedHostDao
    abstract fun connectionEventDao(): ConnectionEventDao
    abstract fun pairedAgentDao(): PairedAgentDao
    abstract fun ec2InstanceDao(): Ec2InstanceDao
    abstract fun snippetDao(): SnippetDao
    abstract fun snippetCategoryDao(): SnippetCategoryDao
}
