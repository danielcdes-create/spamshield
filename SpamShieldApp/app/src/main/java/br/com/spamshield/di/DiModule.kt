package br.com.spamshield.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.spamshield.data.local.AppDatabase
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.domain.ContactAndCallLogLookup
import br.com.spamshield.service.AndroidContactAndCallLogLookup
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    @Singleton
    abstract fun bindContactAndCallLogLookup(
        impl: AndroidContactAndCallLogLookup
    ): ContactAndCallLogLookup
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        daoProvider: Provider<SpamShieldDao>
    ): AppDatabase {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "spamshield_edge.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    scope.launch {
                        val dao = daoProvider.get()
                        if (dao.countRules() == 0) {
                            val now = System.currentTimeMillis()
                            dao.insertRules(AppDatabase.initialRulesSeed(now))
                            dao.insertQuarantines(AppDatabase.initialQuarantineSeed(now))
                            dao.insertAnatelPrefixes(AppDatabase.initialAnatelSeed(now))
                        }
                    }
                }
            })
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideSpamShieldDao(db: AppDatabase): SpamShieldDao = db.spamShieldDao()
}
