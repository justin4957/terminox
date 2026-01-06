package com.terminox.di

import com.terminox.data.repository.AuditLogRepositoryImpl
import com.terminox.data.repository.ConnectionRepositoryImpl
import com.terminox.data.repository.KeySyncRepositoryImpl
import com.terminox.data.repository.RemoteSessionRepositoryImpl
import com.terminox.data.repository.SnippetRepositoryImpl
import com.terminox.data.repository.SshKeyRepositoryImpl
import com.terminox.data.repository.SyncRepositoryImpl
import com.terminox.data.repository.TrustedHostRepositoryImpl
import com.terminox.domain.repository.AuditLogRepository
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.domain.repository.KeySyncRepository
import com.terminox.domain.repository.RemoteSessionRepository
import com.terminox.domain.repository.SnippetRepository
import com.terminox.domain.repository.SshKeyRepository
import com.terminox.domain.repository.SyncRepository
import com.terminox.domain.repository.TrustedHostRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(
        impl: ConnectionRepositoryImpl
    ): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindSshKeyRepository(
        impl: SshKeyRepositoryImpl
    ): SshKeyRepository

    @Binds
    @Singleton
    abstract fun bindTrustedHostRepository(
        impl: TrustedHostRepositoryImpl
    ): TrustedHostRepository

    @Binds
    @Singleton
    abstract fun bindKeySyncRepository(
        impl: KeySyncRepositoryImpl
    ): KeySyncRepository

    @Binds
    @Singleton
    abstract fun bindAuditLogRepository(
        impl: AuditLogRepositoryImpl
    ): AuditLogRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(
        impl: SyncRepositoryImpl
    ): SyncRepository

    @Binds
    @Singleton
    abstract fun bindCertificateRepository(
        impl: com.terminox.data.repository.CertificateRepositoryImpl
    ): com.terminox.domain.repository.CertificateRepository

    @Binds
    @Singleton
    abstract fun bindSnippetRepository(
        impl: SnippetRepositoryImpl
    ): SnippetRepository

    @Binds
    @Singleton
    abstract fun bindRemoteSessionRepository(
        impl: RemoteSessionRepositoryImpl
    ): RemoteSessionRepository

}
