package com.waqtak.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.at
import com.waqtak.app.data.backup.BackupCodec
import com.waqtak.app.data.backup.BackupException
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.installGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupTest {
    private fun expectReason(text: String, reason: BackupException.Reason) {
        try {
            BackupCodec.decode(text); fail("expected $reason")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun exportImportRoundTrip_neutralisesLiveAlarmState() = runBlocking {
        val (g, clock) = installGraph()
        g.repo.logDrink(DrinkType.TEA, 200, hasCalories = true, caffeineMg = 40)
        clock.nowMs = at(8)
        g.repo.claimDueMeals(clock.now()) // breakfast RINGING at export time
        val json = g.backup.export()

        g.repo.deleteAll()
        g.backup.import(json)
        assertEquals(3, g.db.schedule().plans().size)
        assertEquals(1, g.db.drinks().all().size)
        assertTrue(g.db.occurrences().all().none { it.status == OccurrenceStatus.RINGING.name })
        assertTrue(g.settings.get().onboardingDone)
    }

    @Test
    fun invalidFilesAreRejectedWithAReason() {
        expectReason("hello", BackupException.Reason.NOT_JSON)
        expectReason("{\"format\":\"other\"}", BackupException.Reason.WRONG_FORMAT)
        val (g, _) = installGraph()
        val good = runBlocking { g.backup.export() }
        expectReason(good.replace("\"version\": 2", "\"version\": 99"), BackupException.Reason.NEWER_VERSION)
        expectReason(good.replace("\"wakeMinute\": 420", "\"wakeMinute\": 5000"), BackupException.Reason.INVALID_DATA)
        BackupCodec.decode(good) // the untouched file is valid
    }
}
