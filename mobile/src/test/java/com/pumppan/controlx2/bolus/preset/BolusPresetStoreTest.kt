package com.pumppan.controlx2.bolus.preset

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假 Dao：只做内存存取，验证 Store 的规则（上限/重名/空名/序号重排）。 */
private class FakeBolusPresetDao : BolusPresetDao {
    private val items = mutableListOf<BolusPresetEntity>()
    private var nextId = 1L

    override suspend fun getAll(): List<BolusPresetEntity> =
        items.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getById(id: Long): BolusPresetEntity? = items.firstOrNull { it.id == id }

    override suspend fun count(): Int = items.size

    override suspend fun insert(entity: BolusPresetEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        items += entity.copy(id = id)
        return id
    }

    override suspend fun update(entity: BolusPresetEntity) {
        val index = items.indexOfFirst { it.id == entity.id }
        if (index >= 0) items[index] = entity else items += entity
    }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
    }
}

class BolusPresetStoreTest {

    private val spec = BolusPresetSpec(immediateMilliunits = 1000, laterMilliunits = 0, durationMinutes = 0)

    @Test
    fun saveNew_thenOverwrite_thenDelete() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())

        val created = store.saveNew("早餐", spec) as BolusPresetSaveResult.Saved
        assertEquals(1L, created.preset.id)
        assertEquals(0, created.preset.sortOrder)
        assertEquals(1, store.list().size)

        val updated = store.overwrite(
            created.preset.id,
            "早餐加量",
            spec.copy(immediateMilliunits = 840, laterMilliunits = 360, durationMinutes = 60),
        ) as BolusPresetSaveResult.Saved
        assertEquals("早餐加量", updated.preset.name)
        assertEquals(1200, updated.preset.totalMilliunits)
        assertTrue(updated.preset.extendedEnabled)
        assertEquals(70, updated.preset.percent)
        assertEquals(3600, updated.preset.extendedSeconds)

        store.delete(created.preset.id)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun saveNew_rejectsEmptyName() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        assertEquals(BolusPresetSaveResult.EmptyName, store.saveNew("   ", spec))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun saveNew_rejectsDuplicateNameIgnoringCase() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        store.saveNew("Breakfast", spec)
        assertEquals(BolusPresetSaveResult.DuplicateName, store.saveNew(" breakfast ", spec))
        assertEquals(1, store.list().size)
    }

    @Test
    fun overwrite_allowsKeepingOwnName_andRejectsOthers() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        val first = (store.saveNew("A", spec) as BolusPresetSaveResult.Saved).preset
        val second = (store.saveNew("B", spec) as BolusPresetSaveResult.Saved).preset

        assertTrue(store.overwrite(first.id, "A", spec) is BolusPresetSaveResult.Saved)
        assertEquals(BolusPresetSaveResult.DuplicateName, store.overwrite(second.id, "A", spec))
        assertEquals(BolusPresetSaveResult.NotFound, store.overwrite(99L, "C", spec))
    }

    @Test
    fun saveNew_rejectsThirteenthPreset() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        repeat(BolusPresetStore.MAX_PRESETS) { index ->
            store.saveNew("P$index", spec)
        }
        assertEquals(BolusPresetStore.MAX_PRESETS, store.list().size)
        assertEquals(BolusPresetSaveResult.LimitReached, store.saveNew("P12", spec))
        assertEquals(BolusPresetStore.MAX_PRESETS, store.list().size)
    }

    @Test
    fun move_upAndDown_reordersAndRenumbers() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        val a = (store.saveNew("A", spec) as BolusPresetSaveResult.Saved).preset
        val b = (store.saveNew("B", spec) as BolusPresetSaveResult.Saved).preset
        val c = (store.saveNew("C", spec) as BolusPresetSaveResult.Saved).preset
        assertEquals(listOf("A", "B", "C"), store.list().map { it.name })

        store.move(c.id, BolusPresetMove.UP)
        assertEquals(listOf("A", "C", "B"), store.list().map { it.name })
        assertEquals(listOf(0, 1, 2), store.list().map { it.sortOrder })

        store.move(a.id, BolusPresetMove.DOWN)
        assertEquals(listOf("C", "A", "B"), store.list().map { it.name })

        // 已在边界时不变。
        store.move(c.id, BolusPresetMove.UP)
        assertEquals(listOf("C", "A", "B"), store.list().map { it.name })
        assertEquals(b.id, store.list()[2].id)
    }

    @Test
    fun delete_renumbersRemainingPresets() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        val a = (store.saveNew("A", spec) as BolusPresetSaveResult.Saved).preset
        (store.saveNew("B", spec) as BolusPresetSaveResult.Saved)
        val c = (store.saveNew("C", spec) as BolusPresetSaveResult.Saved).preset

        store.delete(store.list()[1].id)
        assertEquals(listOf(a.id, c.id), store.list().map { it.id })
        assertEquals(listOf(0, 1), store.list().map { it.sortOrder })
    }

    @Test
    fun saveNewAutoAfter_insertsRightBehindSelectedAndRenumbers() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        val a = (store.saveNew("A", spec) as BolusPresetSaveResult.Saved).preset
        store.saveNew("B", spec)
        store.saveNew("C", spec)

        val inserted = store.saveNewAutoAfter(a.id, spec) as BolusPresetSaveResult.Saved
        assertEquals("1", inserted.preset.name)
        assertEquals(1, inserted.preset.sortOrder)
        assertEquals(listOf("A", "1", "B", "C"), store.list().map { it.name })
        assertEquals(listOf(0, 1, 2, 3), store.list().map { it.sortOrder })
    }

    @Test
    fun saveNewAutoAfter_withoutSelectionAppendsToEnd() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        store.saveNew("A", spec)
        store.saveNew("B", spec)

        val inserted = store.saveNewAutoAfter(null, spec) as BolusPresetSaveResult.Saved
        assertEquals(listOf("A", "B", "1"), store.list().map { it.name })
        assertEquals(2, inserted.preset.sortOrder)
    }

    @Test
    fun saveNewAutoAfter_unknownIdAppendsToEnd() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        store.saveNew("A", spec)

        store.saveNewAutoAfter(99L, spec) as BolusPresetSaveResult.Saved
        assertEquals(listOf("A", "1"), store.list().map { it.name })
    }

    @Test
    fun saveNewAutoAfter_rejectsWhenFull() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        repeat(BolusPresetStore.MAX_PRESETS) { index -> store.saveNew("P$index", spec) }
        val anchor = store.list().first()

        assertEquals(BolusPresetSaveResult.LimitReached, store.saveNewAutoAfter(anchor.id, spec))
        assertEquals(BolusPresetStore.MAX_PRESETS, store.list().size)
    }

    @Test
    fun saveNewAutoAfter_reusesSmallestFreeSlot() = runBlocking {
        val store = BolusPresetStore(FakeBolusPresetDao())
        store.saveNew("1", spec)
        val three = (store.saveNew("3", spec) as BolusPresetSaveResult.Saved).preset
        store.delete(store.list().first().id)

        val inserted = store.saveNewAutoAfter(three.id, spec) as BolusPresetSaveResult.Saved
        assertEquals("1", inserted.preset.name)
    }

    @Test
    fun insertionIndexAfter_fallsBackToEnd() {
        val items = listOf(
            BolusPreset(1L, "A", 0, 1000, 0, 0),
            BolusPreset(2L, "B", 1, 1000, 0, 0),
        )
        assertEquals(2, BolusPresetStore.insertionIndexAfter(items, null))
        assertEquals(1, BolusPresetStore.insertionIndexAfter(items, 1L))
        assertEquals(2, BolusPresetStore.insertionIndexAfter(items, 2L))
        assertEquals(2, BolusPresetStore.insertionIndexAfter(items, 99L))
    }
}
