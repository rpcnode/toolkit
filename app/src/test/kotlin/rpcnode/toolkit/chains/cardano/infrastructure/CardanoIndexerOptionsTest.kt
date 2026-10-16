package rpcnode.toolkit.chains.cardano.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardanoIndexerOptionsTest
{
    @Test
    fun default_is_ogmios_and_none_is_an_explicit_choice()
    {
        assertEquals(CardanoIndexerMode.OGMIOS, CardanoIndexerMode.fromJson(null))
        assertEquals(CardanoIndexerMode.OGMIOS, CardanoIndexerMode.fromJson(""))
        assertEquals(CardanoIndexerMode.OGMIOS, CardanoIndexerMode.fromJson("""{"snapshot":"mithril"}"""))
        assertEquals(CardanoIndexerMode.OGMIOS, CardanoIndexerMode.fromJson("not json"))
        assertEquals(CardanoIndexerMode.OGMIOS, CardanoIndexerMode.parse("garbage"))
        assertEquals(CardanoIndexerMode.NONE, CardanoIndexerMode.fromJson("""{"indexer":"none"}"""))
    }

    @Test
    fun modes_map_to_the_companions()
    {
        val ogmios = CardanoIndexerMode.fromJson("""{"snapshot":"mithril","indexer":"ogmios"}""")
        assertTrue(ogmios.ogmios); assertFalse(ogmios.dbSync)
        val db = CardanoIndexerMode.parse(" DbSync ")
        assertFalse(db.ogmios); assertTrue(db.dbSync)
        val both = CardanoIndexerMode.parse("both")
        assertTrue(both.ogmios); assertTrue(both.dbSync)
        val none = CardanoIndexerMode.parse("none")
        assertFalse(none.ogmios); assertFalse(none.dbSync)
        assertEquals(listOf("none", "ogmios", "dbsync", "both"), CardanoIndexerMode.entries.map { it.id })
    }
}
