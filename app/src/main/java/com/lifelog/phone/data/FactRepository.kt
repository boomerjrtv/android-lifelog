package com.lifelog.phone.data

import com.lifelog.phone.data.local.FactDao
import com.lifelog.phone.data.local.FactEntity
import com.lifelog.phone.data.remote.LifeLogApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FactRepository @Inject constructor(
    private val factDao: FactDao,
    private val api: LifeLogApi
) {
    suspend fun getAll(): List<Fact> =
        factDao.getAll().map { Fact(it.id, it.text, it.timestamp) }

    suspend fun search(query: String): List<Fact> =
        factDao.search(query).map { Fact(it.id, it.text, it.timestamp) }

    suspend fun insert(fact: Fact) =
        factDao.insert(FactEntity(0, fact.text, fact.timestamp))

    suspend fun clear() = factDao.clearAll()

    // Note: syncFromServer disabled - facts are only stored on server database
    // suspend fun syncFromServer(baseUrl: String): Result<Unit> = try {
    //     val result = api.getFacts(baseUrl)
    //     if (result.isSuccess) {
    //         val facts = result.getOrNull() ?: emptyList()
    //         factDao.clearAll()
    //         facts.forEach { fact ->
    //             factDao.insert(FactEntity(0, fact, System.currentTimeMillis()))
    //         }
    //         Result.success(Unit)
    //     } else {
    //         Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
    //     }
    // } catch (e: Exception) {
    //     Result.failure(e)
    // }
}
