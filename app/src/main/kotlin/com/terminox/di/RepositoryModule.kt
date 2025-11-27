package com.terminox.di

import com.terminox.data.repository.ConnectionRepositoryImpl
import com.terminox.data.repository.SshKeyRepositoryImpl
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.domain.repository.SshKeyRepository
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
}
