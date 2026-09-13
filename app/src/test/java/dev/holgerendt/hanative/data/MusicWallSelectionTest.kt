package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicWallSelectionTest {
    private val dining = MusicAssistantPlayer(
        entityId = "media_player.dining_room",
        name = "Dining Room",
        massPlayerId = "dining_room",
        groupMemberIds = listOf("dining_room", "living_room_arc"),
    )
    private val arc = MusicAssistantPlayer(
        entityId = "media_player.living_room_living_room_arc",
        name = "Living Room Arc",
        massPlayerId = "living_room_arc",
        syncedToId = "dining_room",
        groupMemberIds = listOf("dining_room", "living_room_arc"),
    )
    private val kitchen = MusicAssistantPlayer(
        entityId = "media_player.kitchen_2",
        name = "Kitchen",
        massPlayerId = "kitchen",
    )

    @Test
    fun keepsExactMassSelection() {
        val id = resolveMusicWallSelection(
            players = listOf(dining, arc, kitchen),
            preferredEntityId = "media_player.kitchen_2",
            playerState = { "idle" },
        )
        assertEquals("media_player.kitchen_2", id)
    }

    @Test
    fun remapsStaleSonosDiningRoomToMassEntity() {
        val id = resolveMusicWallSelection(
            players = listOf(dining, arc, kitchen),
            preferredEntityId = "media_player.office",
            playerState = { "idle" },
            preferredDisplayName = "Dining Room",
        )
        assertEquals("media_player.dining_room", id)
    }

    @Test
    fun prefersGroupLeaderWhenSeveralPlaying() {
        val states = mapOf(
            dining.entityId to "playing",
            arc.entityId to "playing",
            kitchen.entityId to "idle",
        )
        val id = resolveMusicWallSelection(
            players = listOf(arc, dining, kitchen),
            preferredEntityId = null,
            playerState = { states[it] },
        )
        assertEquals("media_player.dining_room", id)
    }

    @Test
    fun emptyPlayersYieldNull() {
        assertNull(
            resolveMusicWallSelection(
                players = emptyList(),
                preferredEntityId = "media_player.office",
                playerState = { "playing" },
                preferredDisplayName = "Dining Room",
            ),
        )
    }
}
