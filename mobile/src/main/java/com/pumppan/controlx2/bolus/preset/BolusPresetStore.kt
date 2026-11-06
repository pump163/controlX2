package com.pumppan.controlx2.bolus.preset

/** 保存/修改预设的结果；失败原因直接映射到一句提示。 */
sealed interface BolusPresetSaveResult {
    data class Saved(val preset: BolusPreset, val isNew: Boolean) : BolusPresetSaveResult
    data object EmptyName : BolusPresetSaveResult
    data object DuplicateName : BolusPresetSaveResult
    data object LimitReached : BolusPresetSaveResult
    data object NotFound : BolusPresetSaveResult
}

enum class BolusPresetMove { UP, DOWN }

/**
 * 预设读写与规则：上限 12、不许空名、不许重名、固定顺序 + 用户改序号。
 * 规则全在这一层，Dao 只做存取；单测用假 Dao 覆盖（不需要 Room）。
 */
class BolusPresetStore(private val dao: BolusPresetDao) {

    suspend fun list(): List<BolusPreset> = dao.getAll().map { it.toPreset() }

    /** 新建；超过 [MAX_PRESETS] 拒绝并提示，不自动裁剪旧条目。 */
    suspend fun saveNew(name: String, spec: BolusPresetSpec): BolusPresetSaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return BolusPresetSaveResult.EmptyName

        val existing = list()
        if (isDuplicateName(trimmed, existing)) return BolusPresetSaveResult.DuplicateName
        if (existing.size >= MAX_PRESETS) return BolusPresetSaveResult.LimitReached

        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val id = dao.insert(
            BolusPresetEntity(
                name = trimmed,
                sortOrder = nextOrder,
                immediateMilliunits = spec.immediateMilliunits,
                laterMilliunits = spec.laterMilliunits,
                durationMinutes = spec.durationMinutes,
            )
        )
        return BolusPresetSaveResult.Saved(
            preset = BolusPreset(
                id = id,
                name = trimmed,
                sortOrder = nextOrder,
                immediateMilliunits = spec.immediateMilliunits,
                laterMilliunits = spec.laterMilliunits,
                durationMinutes = spec.durationMinutes,
            ),
            isNew = true,
        )
    }

    /**
     * 「+」新建：按序号自动命名，取 `1..MAX_PRESETS` 中最小未被占用的整数。
     * 不弹命名框、不覆盖任何条目；12 条已满时返回 [BolusPresetSaveResult.LimitReached]。
     */
    suspend fun saveNewAuto(spec: BolusPresetSpec): BolusPresetSaveResult {
        val existing = list()
        if (existing.size >= MAX_PRESETS) return BolusPresetSaveResult.LimitReached
        val slot = nextFreeSlot(existing) ?: return BolusPresetSaveResult.LimitReached
        return saveNew(slot.toString(), spec)
    }

    /**
     * 「新建」：自动命名（同 [saveNewAuto]），插入到 [afterId] 条目**正后方**；
     * [afterId] 为 null（未选中）或已不存在时追加到末尾。插入后序号重排成 0..N-1。
     */
    suspend fun saveNewAutoAfter(afterId: Long?, spec: BolusPresetSpec): BolusPresetSaveResult {
        val existing = list()
        if (existing.size >= MAX_PRESETS) return BolusPresetSaveResult.LimitReached
        val slot = nextFreeSlot(existing) ?: return BolusPresetSaveResult.LimitReached

        val index = insertionIndexAfter(existing, afterId)
        // 先落到末尾再按目标顺序整体重排：避免与既有条目临时同号（排序会按 id 抖动）。
        val id = dao.insert(
            BolusPresetEntity(
                name = slot.toString(),
                sortOrder = existing.size,
                immediateMilliunits = spec.immediateMilliunits,
                laterMilliunits = spec.laterMilliunits,
                durationMinutes = spec.durationMinutes,
            )
        )
        val ordered = existing.take(index).map { it.id } + id + existing.drop(index).map { it.id }
        val byId = dao.getAll().associateBy { it.id }
        ordered.forEachIndexed { order, presetId ->
            byId[presetId]?.let { entity -> if (entity.sortOrder != order) dao.update(entity.copy(sortOrder = order)) }
        }
        return BolusPresetSaveResult.Saved(
            preset = BolusPreset(
                id = id,
                name = slot.toString(),
                sortOrder = index,
                immediateMilliunits = spec.immediateMilliunits,
                laterMilliunits = spec.laterMilliunits,
                durationMinutes = spec.durationMinutes,
            ),
            isNew = true,
        )
    }

    /** 覆盖已有条目（也是编辑对话框的保存路径）；重名判断排除自己。 */
    suspend fun overwrite(id: Long, name: String, spec: BolusPresetSpec): BolusPresetSaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return BolusPresetSaveResult.EmptyName

        val existing = list()
        if (isDuplicateName(trimmed, existing, excludeId = id)) {
            return BolusPresetSaveResult.DuplicateName
        }
        val current = existing.firstOrNull { it.id == id } ?: return BolusPresetSaveResult.NotFound

        val updated = current.copy(
            name = trimmed,
            immediateMilliunits = spec.immediateMilliunits,
            laterMilliunits = spec.laterMilliunits,
            durationMinutes = spec.durationMinutes,
        )
        dao.update(updated.toEntity())
        return BolusPresetSaveResult.Saved(updated, isNew = false)
    }

    /** 删除后把序号重排成 0..N-1，只保留相对顺序（不是按时间/频率重排）。 */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
        renumberInPlace()
    }

    /** 上移/下移一位：按用户操作交换相邻两条，再把序号重排成 0..N-1。 */
    suspend fun move(id: Long, direction: BolusPresetMove): List<BolusPreset> {
        val items = list()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return items
        val target = if (direction == BolusPresetMove.UP) index - 1 else index + 1
        if (target !in items.indices) return items

        val reordered = items.toMutableList().apply { add(target, removeAt(index)) }
        reordered.forEachIndexed { order, preset ->
            if (preset.sortOrder != order) dao.update(preset.copy(sortOrder = order).toEntity())
        }
        return dao.getAll().map { it.toPreset() }
    }

    private suspend fun renumberInPlace() {
        dao.getAll().forEachIndexed { order, entity ->
            if (entity.sortOrder != order) dao.update(entity.copy(sortOrder = order))
        }
    }

    companion object {
        const val MAX_PRESETS = 12

        /** 插入位置：选中条目正后方；未选中 / 找不到则追加到末尾。 */
        fun insertionIndexAfter(items: List<BolusPreset>, afterId: Long?): Int {
            if (afterId == null) return items.size
            val index = items.indexOfFirst { it.id == afterId }
            return if (index < 0) items.size else index + 1
        }

        /** 自动命名槽位：`1..MAX_PRESETS` 中最小未被占用的整数。 */
        fun nextFreeSlot(existing: List<BolusPreset>): Int? {
            val used = existing.map { it.name.trim().lowercase() }.toSet()
            return (1..MAX_PRESETS).firstOrNull { it.toString() !in used }
        }

        /** 去首尾空格后比较，大小写不敏感；中文不受影响。 */
        fun isDuplicateName(
            name: String,
            existing: List<BolusPreset>,
            excludeId: Long? = null,
        ): Boolean = existing.any { it.id != excludeId && it.name.equals(name, ignoreCase = true) }
    }
}
