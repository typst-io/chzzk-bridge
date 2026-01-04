package io.typst.chzzk.bridge.repository

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.typst.chzzk.bridge.api.ApiFetchChzzkMessage
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.sqlite.Tables.EVENT
import io.typst.chzzk.bridge.sqlite.Tables.TOKEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.io.File
import java.time.Instant
import java.util.*

// https://www.jooq.org/doc/latest/manual/sql-building/kotlin-sql-building/kotlin-coroutines/
class SQLiteBridgeRepository(
    val dbDir: File,
    val dbFile: File = dbDir.resolve("chzzk_bridge.db"),
) : BridgeRepository {
    private val lazyDataSource = lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
            maximumPoolSize = 1
            connectionTestQuery = "SELECT 1"
        }).also { runMigrations(it) }
    }

    private val dataSource: HikariDataSource by lazyDataSource

    private val dsl: DSLContext by lazy {
        DSL.using(dataSource, SQLDialect.SQLITE)
    }

    private fun runMigrations(dataSource: HikariDataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    override suspend fun getToken(id: UUID): UserToken? {
        return dsl.selectFrom(TOKEN)
            .where(TOKEN.TOKEN_MC_UUID.eq(id.toString()))
            .awaitFirstOrNull()
            ?.let { record ->
                UserToken(
                    channelId = record.tokenChannelId,
                    mcUuid = id,
                    accessToken = record.tokenAccessToken,
                    refreshToken = record.tokenRefreshToken,
                    expireTime = Instant.ofEpochMilli(record.tokenExpireTime)
                )
            }
    }

    override suspend fun setToken(token: UserToken) {
        dsl.transactionCoroutine {
            val ctx = DSL.using(it)
            assert(
                ctx.insertInto(TOKEN)
                    .set(TOKEN.TOKEN_CHANNEL_ID, token.channelId)
                    .set(TOKEN.TOKEN_MC_UUID, token.mcUuid.toString())
                    .set(TOKEN.TOKEN_ACCESS_TOKEN, token.accessToken)
                    .set(TOKEN.TOKEN_REFRESH_TOKEN, token.refreshToken)
                    .set(TOKEN.TOKEN_EXPIRE_TIME, token.expireTime.toEpochMilli())
                    .onConflict(TOKEN.TOKEN_CHANNEL_ID)
                    .doUpdate()
                    .set(TOKEN.TOKEN_MC_UUID, token.mcUuid.toString())
                    .set(TOKEN.TOKEN_ACCESS_TOKEN, token.accessToken)
                    .set(TOKEN.TOKEN_REFRESH_TOKEN, token.refreshToken)
                    .set(TOKEN.TOKEN_EXPIRE_TIME, token.expireTime.toEpochMilli())
                    .awaitSingle() == 1
            )
        }
    }

    override suspend fun addMessage(x: ApiFetchChzzkMessage) {
        dsl.transactionCoroutine {
            val ctx = DSL.using(it)
            ctx.insertInto(EVENT)
                .set(EVENT.EVENT_CHANNEL_ID, x.channelId)
                .set(EVENT.EVENT_SENDER_ID, x.senderId)
                .set(EVENT.EVENT_SENDER_NAME, x.senderName)
                .set(EVENT.EVENT_MESSAGE, x.message)
                .set(EVENT.EVENT_TIME, x.messageTime.toEpochMilli())
                .set(EVENT.EVENT_PAY_AMOUNT, x.payAmount)
                .awaitFirst()
        }
    }

    override suspend fun getMessages(mcUuid: UUID, fromId: Int): List<ApiFetchChzzkMessage> {
        return dsl.transactionCoroutine { config ->
            val ctx = DSL.using(config)

            val messages = ctx.select(EVENT)
                .from(EVENT)
                .join(TOKEN).on(TOKEN.TOKEN_CHANNEL_ID.eq(EVENT.EVENT_CHANNEL_ID))
                .where(
                    TOKEN.TOKEN_MC_UUID.eq(mcUuid.toString())
                        .and(EVENT.EVENT_ID.greaterOrEqual(fromId))
                )
                .orderBy(EVENT.EVENT_ID.asc())
                .asFlow()
                .map { tup ->
                    val record = tup.value1()
                    ApiFetchChzzkMessage(
                        record.eventId,
                        record.eventChannelId,
                        record.eventSenderId,
                        record.eventSenderName,
                        record.eventMessage,
                        Instant.ofEpochMilli(record.eventTime),
                        record.eventPayAmount
                    )
                }
            messages.toList()
        }
    }

    override suspend fun close() {
        if (lazyDataSource.isInitialized()) {
            withContext(Dispatchers.IO) {
                dataSource.close()
            }
        }
    }
}
