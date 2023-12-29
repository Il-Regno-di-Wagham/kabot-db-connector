package org.wagham.db.scopes

import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.flow.Flow
import org.bson.BsonDocument
import org.bson.Document
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.wagham.db.KabotMultiDBClient
import org.wagham.db.enums.CharacterStatus
import org.wagham.db.enums.CollectionNames
import org.wagham.db.exceptions.ResourceNotFoundException
import org.wagham.db.models.*
import org.wagham.db.models.client.TransactionResult
import org.wagham.db.models.creation.CharacterCreationData
import org.wagham.db.models.embed.ProficiencyStub
import org.wagham.db.models.responses.ActiveCharacterOrAllActive
import org.wagham.db.pipelines.characters.CharacterWithPlayer
import org.wagham.db.utils.isSuccessful
import java.util.*


class KabotDBCharacterScope(
    override val client: KabotMultiDBClient
) : KabotDBScope<Character> {

    override val collectionName = CollectionNames.CHARACTERS.stringValue

    override fun getMainCollection(guildId: String): CoroutineCollection<Character> =
        client.getGuildDb(guildId).getCollection(collectionName)

    suspend fun getActiveCharacterOrAllActive(guildId: String, playerId: String): ActiveCharacterOrAllActive =
        client.getGuildDb(guildId).getCollection<Player>(CollectionNames.PLAYERS.stringValue)
            .findOne(Player::playerId eq playerId).let { player ->
                if(player?.activeCharacter != null) {
                    getMainCollection(guildId).findOne(
                        Character::id eq player.activeCharacter
                    )?.let {
                        ActiveCharacterOrAllActive(currentActive = it)
                    }
                } else null
            } ?: getMainCollection(guildId).find(
                Character::status eq CharacterStatus.active,
                Character::player eq playerId
            ).toList().let {
                if(it.size == 1) ActiveCharacterOrAllActive(currentActive = it.first())
                else ActiveCharacterOrAllActive(allActive = it)
            }

    fun getActiveCharacters(guildId: String, playerId: String): Flow<Character> =
        getMainCollection(guildId).find(
            Character::status eq CharacterStatus.active,
            Character::player eq playerId
        ).toFlow()

    suspend fun getCharacter(guildId: String, characterId: String): Character =
        getMainCollection(guildId)
            .findOne(Character::id eq characterId)
            ?: throw ResourceNotFoundException(characterId, "characters")

    suspend fun getCharacter(session: ClientSession, guildId: String, characterId: String): Character =
        getMainCollection(guildId)
            .findOne(session, Character::id eq characterId)
            ?: throw ResourceNotFoundException(characterId, "characters")

    /**
     * Gets all the characters in the guild which id is in the provided list.
     * If an id does not correspond to any character, then it is ignored.
     *
     * @param guildId the id of the guild where to search the characters.
     * @param characterIds a [List] of character ids to search.
     * @return a [Flow] containing the retrieved [Character]s.
     */
    fun getCharacters(guildId: String, characterIds: List<String>): Flow<Character> =
        getMainCollection(guildId)
            .find(
                Character::id `in` characterIds
            ).toFlow()

    suspend fun updateCharacter(guildId: String, updatedCharacter: Character): Boolean =
        getMainCollection(guildId)
            .updateOne(
                Character::id eq updatedCharacter.id,
                updatedCharacter
            ).modifiedCount == 1L

    fun getAllCharacters(guildId: String, status: CharacterStatus? = null) =
        getMainCollection(guildId)
            .find(Document(
                status?.let {
                    mapOf("status" to status)
                } ?: emptyMap<String, String>()
            )).toFlow()

    suspend fun addProficiencyToCharacter(guildId: String, characterId: String, proficiency: ProficiencyStub) =
        getMainCollection(guildId)
            .updateOne(
                Character::id eq characterId,
                addToSet(Character::proficiencies, proficiency)
            ).modifiedCount == 1L

    suspend fun addProficiencyToCharacter(session: ClientSession, guildId: String, characterId: String, proficiency: ProficiencyStub) =
        getMainCollection(guildId)
            .updateOne(
                session,
                Character::id eq characterId,
                addToSet(Character::proficiencies, proficiency)
            ).modifiedCount == 1L

    suspend fun addLanguageToCharacter(session: ClientSession, guildId: String, characterId: String, language: ProficiencyStub) =
        getMainCollection(guildId)
            .updateOne(
                session,
                Character::id eq characterId,
                addToSet(Character::languages, language)
            ).modifiedCount == 1L

    suspend fun removeProficiencyFromCharacter(guildId: String, characterId: String, proficiency: ProficiencyStub) =
        getMainCollection(guildId)
            .updateOne(
                Character::id eq characterId,
                pull(Character::proficiencies, proficiency),
            ).modifiedCount == 1L

    suspend fun removeLanguageFromCharacter(session: ClientSession, guildId: String, characterId: String, language: ProficiencyStub) =
        getMainCollection(guildId)
            .updateOne(
                session,
                Character::id eq characterId,
                pull(Character::languages, language),
            ).modifiedCount == 1L

    suspend fun subtractMoney(session: ClientSession, guildId: String, characterId: String, qty: Float) =
        client.getGuildDb(guildId).let {
            val character = getCharacter(session, guildId, characterId)
            it.getCollection<Character>(collectionName)
                .updateOne(
                    session,
                    Character::id eq characterId,
                    setValue(Character::money, character.money - qty),
                ).modifiedCount == 1L
        }

    suspend fun addMoney(session: ClientSession, guildId: String, characterId: String, qty: Float) =
        client.getGuildDb(guildId).let {
            val character = getCharacter(session, guildId, characterId)
            it.getCollection<Character>(collectionName)
                .updateOne(
                    session,
                    Character::id eq characterId,
                    setValue(Character::money, character.money + qty),
                ).modifiedCount == 1L
        }

    suspend fun removeItemFromInventory(session: ClientSession, guildId: String, characterId: String, item: String, qty: Int) =
        client.getGuildDb(guildId).let {
            val c = getCharacter(session, guildId, characterId)
            val updatedCharacter = when {
                c.inventory[item] == null -> c
                c.inventory[item]!! <= qty -> c.copy(
                    inventory = c.inventory - item
                )
                c.inventory[item]!! > qty -> c.copy(
                    inventory = c.inventory + (item to c.inventory[item]!! - qty)
                )
                else -> c
            }
            it.getCollection<Character>(collectionName)
                .updateOne(
                    session,
                    Character::id eq characterId,
                    updatedCharacter
                ).modifiedCount == 1L
        }

    suspend fun removeItemFromAllInventories(session: ClientSession, guildId: String, item: String) =
        getMainCollection(guildId).updateMany(
            session,
            BsonDocument(),
            Updates.unset("inventory.$item")
        ).let { true }

    suspend fun addItemToInventory(session: ClientSession, guildId: String, characterId: String, item: String, qty: Int) =
        client.getGuildDb(guildId).let {
            val c = getCharacter(session, guildId, characterId)
            it.getCollection<Character>(collectionName)
                .updateOne(
                    session,
                    Character::id eq characterId,
                    c.copy(
                        inventory = c.inventory + (item to (c.inventory[item] ?: 0) + qty)
                    )
                ).modifiedCount == 1L
        }

    suspend fun addBuilding(session: ClientSession, guildId: String, characterId: String, building: Building, type: BaseBuilding) =
        client.getGuildDb(guildId).let {
            val c = getCharacter(session, guildId, characterId)
            val bId = "${type.name}:${type.type}:${type.tier}"
            it.getCollection<Character>(collectionName)
                .updateOne(
                    session,
                    Character::id eq characterId,
                    c.copy(
                        buildings = c.buildings +
                            (bId to (c.buildings[bId] ?: emptyList()) + building)
                    )
                ).modifiedCount == 1L
        }

    suspend fun removeBuilding(session: ClientSession, guildId: String, characterId: String, buildingId: String, type: BaseBuilding) =
        client.getGuildDb(guildId).let {
            val c = getCharacter(session, guildId, characterId)
            val bId = "${type.name}:${type.type}:${type.tier}"
            it.getCollection<Character>(collectionName)
                .updateOne(
                    session,
                    Character::id eq characterId,
                    c.copy(
                        buildings = c.buildings +
                            (bId to (c.buildings[bId] ?: emptyList()).filter { b -> b.name != buildingId })
                    )
                ).modifiedCount == 1L
        }

    fun getCharactersWithPlayer(guildId: String, status: CharacterStatus? = null) =
        getMainCollection(guildId)
            .aggregate<CharacterWithPlayer>(CharacterWithPlayer.getPipeline(status))
            .toFlow()

    suspend fun createCharacter(guildId: String, playerId: String, playerName: String, data: CharacterCreationData): TransactionResult =
        client.transaction(guildId) { session ->
            val playerExistsOrIsCreated = client.playersScope.getPlayer(session, guildId, playerId) ?:
                client.playersScope.createPlayer(session, guildId, playerId, playerName)
            val startingExp = client.utilityScope.getExpTable(guildId).levelToExp(data.startingLevel)
            val characterCreated = getMainCollection(guildId).updateOne(
                session,
                Character::id eq "$playerId:${data.name}",
                Character(
                    id = "$playerId:${data.name}",
                    name = data.name,
                    player = playerId,
                    race = data.race,
                    territory = data.territory,
                    characterClass = data.characterClass?.let { listOf(it) } ?: emptyList(),
                    age = data.age,
                    created = Date(),
                    errataMS = startingExp,
                    errata = listOf(
                        Errata(
                            ms = startingExp,
                            description = "Starts from level ${data.startingLevel}",
                            date = Date()
                        )
                    )
                ),
                UpdateOptions().upsert(true)
            ).isSuccessful()
            characterCreated && playerExistsOrIsCreated != null
        }

    /**
     * Adds a new [Errata] to a [Character], updating its status and exp if needed.
     *
     * @param guildId the id of the guild where to update the character.
     * @param characterId the id of the [Character] to update.
     * @param errata the [Errata] to add.
     * @return a [TransactionResult].
     */
    suspend fun addErrata(guildId: String, characterId: String, errata: Errata): TransactionResult =
        client.transaction(guildId) { clientSession ->
            val character = getCharacter(clientSession, guildId, characterId)
            getMainCollection(guildId).updateOne(
                clientSession,
                Character::id eq characterId,
                character.copy(
                    errataMS = character.errataMS + errata.ms,
                    status = errata.statusChange ?: character.status,
                    errata = listOf(errata) + character.errata
                )
            ).isSuccessful()
        }
}
