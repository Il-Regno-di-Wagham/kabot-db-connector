import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.equality.shouldBeEqualToIgnoringFields
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import org.wagham.db.KabotMultiDBClient
import org.wagham.db.KabotMultiDBClientTest
import org.wagham.db.exceptions.InvalidGuildException
import org.wagham.db.models.AnnouncementType
import org.wagham.db.models.Prize
import org.wagham.db.uuid
import java.util.*
import kotlin.random.Random

fun KabotMultiDBClientTest.testBounties(
    client: KabotMultiDBClient,
    guildId: String
) {
    "getAllBounties should be able to get all the bounties" {
        client.bountiesScope.getAllBounties(guildId).count() shouldBeGreaterThan 0
    }

    "getAllBounties should not be able of getting the backgrounds for a non-existent guild" {
        shouldThrow<InvalidGuildException> {
            client.bountiesScope.getAllBounties("I_DO_NOT_EXIST")
        }
    }

    "Should be able of rewriting the whole bounties collection" {
        val bounties = client.bountiesScope.getAllBounties(guildId).toList()
        val bountyToEdit = bounties.random().copy(
            prizes = emptyList()
        )
        client.bountiesScope.rewriteAllBounties(
            guildId,
            bounties.filter { it.id != bountyToEdit.id } + bountyToEdit
        ) shouldBe true
        val newBounties = client.bountiesScope.getAllBounties(guildId).toList()
        newBounties.size shouldBe bounties.size
        newBounties.first { it.id == bountyToEdit.id }.prizes.size shouldBe 0
    }

    "Should not be able of updating the bounties for a non-existent guild" {
        val bounties = client.bountiesScope.getAllBounties(guildId).toList()
        shouldThrow<InvalidGuildException> {
            client.bountiesScope.rewriteAllBounties(
                UUID.randomUUID().toString(),
                bounties
            )
        }
    }

    "Should be able of serializing and deserializing prizes" {
        val objectMapper = ObjectMapper().registerKotlinModule()
        val prizeWithAnnouncement = Prize(
            Random.nextFloat(),
            Random.nextInt(),
            uuid(),
            Random.nextInt(),
            listOf(AnnouncementType.CriticalFail, AnnouncementType.Success, AnnouncementType.Fail, AnnouncementType.Jackpot).random()
        )
        val prizeWithAnnouncementString = objectMapper.writeValueAsString(prizeWithAnnouncement)
        objectMapper.readValue<Prize>(prizeWithAnnouncementString).let {
            it.probability shouldBe prizeWithAnnouncement.probability
            it.moDelta shouldBe prizeWithAnnouncement.moDelta
            it.guaranteedObjectId shouldBe prizeWithAnnouncement.guaranteedObjectId
            it.guaranteedObjectDelta shouldBe prizeWithAnnouncement.guaranteedObjectDelta
            it.announceId shouldBe prizeWithAnnouncement.announceId
        }

        val prizeWithNullAnnouncement = Prize(
            Random.nextFloat(),
            Random.nextInt(),
            uuid(),
            Random.nextInt(),
            null
        )
        val prizeWithNullAnnouncementString = objectMapper.writeValueAsString(prizeWithNullAnnouncement)
        objectMapper.readValue<Prize>(prizeWithNullAnnouncementString).let {
            it.probability shouldBe prizeWithNullAnnouncement.probability
            it.moDelta shouldBe prizeWithNullAnnouncement.moDelta
            it.guaranteedObjectId shouldBe prizeWithNullAnnouncement.guaranteedObjectId
            it.guaranteedObjectDelta shouldBe prizeWithNullAnnouncement.guaranteedObjectDelta
            it.announceId shouldBe null
        }

        val prizeWithEmptyAnnouncement = Prize(
            Random.nextFloat(),
            Random.nextInt(),
            uuid(),
            Random.nextInt(),
            null
        )
        val prizeWithEmptyAnnouncementString = objectMapper.writeValueAsString(prizeWithEmptyAnnouncement)
            .replace("null", "\"\"")
        objectMapper.readValue<Prize>(prizeWithEmptyAnnouncementString).let {
            it.probability shouldBe prizeWithEmptyAnnouncement.probability
            it.moDelta shouldBe prizeWithEmptyAnnouncement.moDelta
            it.guaranteedObjectId shouldBe prizeWithEmptyAnnouncement.guaranteedObjectId
            it.guaranteedObjectDelta shouldBe prizeWithEmptyAnnouncement.guaranteedObjectDelta
            it.announceId shouldBe null
        }
    }
}