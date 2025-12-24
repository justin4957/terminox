package com.terminox.di

import com.terminox.data.repository.AwsCredentialRepositoryImpl
import com.terminox.data.repository.Ec2RepositoryImpl
import com.terminox.domain.repository.AwsCredentialRepository
import com.terminox.domain.repository.Ec2Repository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for EC2 and AWS credential dependency injection.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class Ec2Module {

    /**
     * Bind EC2 repository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindEc2Repository(
        impl: Ec2RepositoryImpl
    ): Ec2Repository

    /**
     * Bind AWS credential repository implementation.
     */
    @Binds
    @Singleton
    abstract fun bindAwsCredentialRepository(
        impl: AwsCredentialRepositoryImpl
    ): AwsCredentialRepository
}
