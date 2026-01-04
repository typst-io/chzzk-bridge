package io.typst.chzzk.bridge.repository

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.typst.chzzk.bridge.api.ApiSseChatMessage
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.sqlite.Tables.EVENT
import io.typst.chzzk.bridge.sqlite.Tables.TOKEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
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

    override suspend fun getToken(id: UUID): UserToken? = runInterruptible(Dispatchers.IO) {
        dsl.selectFrom(TOKEN)
            .where(TOKEN.TOKEN_MC_UUID.eq(id.toString()))
            .fetchOne { record ->
                UserToken(
                    channelId = record.tokenChannelId,
                    mcUuid = id,
                    accessToken = record.tokenAccessToken,
                    refreshToken = record.tokenRefreshToken,
                    expireTime = Instant.ofEpochMilli(record.tokenExpireTime),
                    lastSentEventId = record.tokenLastSentEventId,
                )
            }
    }

    override suspend fun setToken(token: UserToken) = runInterruptible(Dispatchers.IO) {
        dsl.transaction { trx ->
            val ctx = trx.dsl()
            assert(
                ctx.insertInto(TOKEN)
                    .set(TOKEN.TOKEN_CHANNEL_ID, token.channelId)
                    .set(TOKEN.TOKEN_MC_UUID, token.mcUuid.toString())
                    .set(TOKEN.TOKEN_ACCESS_TOKEN, token.accessToken)
                    .set(TOKEN.TOKEN_REFRESH_TOKEN, token.refreshToken)
                    .set(TOKEN.TOKEN_EXPIRE_TIME, token.expireTime.toEpochMilli())
                    .set(TOKEN.TOKEN_LAST_SENT_EVENT_ID, token.lastSentEventId)
                    .onConflict(TOKEN.TOKEN_CHANNEL_ID)
                    .doUpdate()
                    .set(TOKEN.TOKEN_MC_UUID, token.mcUuid.toString())
                    .set(TOKEN.TOKEN_ACCESS_TOKEN, token.accessToken)
                    .set(TOKEN.TOKEN_REFRESH_TOKEN, token.refreshToken)
                    .set(TOKEN.TOKEN_EXPIRE_TIME, token.expireTime.toEpochMilli())
                    .execute() == 1
            )
        }
    }

    override suspend fun deleteToken(id: UUID): Unit = runInterruptible(Dispatchers.IO) {
        dsl.deleteFrom(TOKEN)
            .where(TOKEN.TOKEN_MC_UUID.eq(id.toString()))
            .execute()
    }

    override suspend fun addMessage(x: ApiSseChatMessage): Unit = runInterruptible(Dispatchers.IO) {
        dsl.transaction { it ->
            val ctx = DSL.using(it)
            ctx.insertInto(EVENT)
                .set(EVENT.EVENT_CHANNEL_ID, x.channelId)
                .set(EVENT.EVENT_SENDER_ID, x.senderId)
                .set(EVENT.EVENT_SENDER_NAME, x.senderName)
                .set(EVENT.EVENT_MESSAGE, x.message)
                .set(EVENT.EVENT_TIME, x.messageTime.toEpochMilli())
                .set(EVENT.EVENT_PAY_AMOUNT, x.payAmount)
                .execute()
        }
    }

    override suspend fun getMessages(mcUuid: UUID, fromId: Int): List<ApiSseChatMessage> =
        runInterruptible(Dispatchers.IO) {
            dsl.transactionResult { config ->
                val ctx = DSL.using(config)
                val messages = ctx.select(EVENT)
                    .from(EVENT)
                    .join(TOKEN).on(TOKEN.TOKEN_CHANNEL_ID.eq(EVENT.EVENT_CHANNEL_ID))
                    .where(
                        TOKEN.TOKEN_MC_UUID.eq(mcUuid.toString())
                            .and(EVENT.EVENT_ID.greaterOrEqual(fromId))
                    )
                    .orderBy(EVENT.EVENT_ID.asc())
                    .map { tup ->
                        val record = tup.value1()
                        ApiSseChatMessage(
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

    override suspend fun updateLastSentEventId(mcUuid: UUID, eventId: Int): Unit = runInterruptible(Dispatchers.IO) {
        assert(dsl.update(TOKEN)
            .set(TOKEN.TOKEN_LAST_SENT_EVENT_ID, eventId)
            .where(TOKEN.TOKEN_MC_UUID.eq(mcUuid.toString()))
            .execute() == 1)
    }

    override fun close() {
        if (lazyDataSource.isInitialized()) {
            dataSource.close()
        }
    }
}
