package com.jwoglom.controlx2.shared.capability

import com.jwoglom.controlx2.shared.util.canControlPump
import com.jwoglom.pumpx2.pump.messages.models.KnownApiVersion
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力表的契约测试：行为必须与迁移前 18 处 `canControlPump` 完全一致。
 * 放开/收紧某一项时，先改这里再改表。
 */
class CapabilityTableTest {

    private val api = KnownApiVersion.API_V2_5.get()

    @Test
    fun unknownModel_deniesEverything() {
        Capability.values().forEach { cap ->
            assertFalse(cap.name, CapabilityTable.allows(null, api, cap))
        }
    }

    @Test
    fun tslimX2_deniesAllCapabilities() {
        // X2 控制页全只读是产品决定（2026-11-03 真机结论），不是缺陷；
        // 大剂量走独立签名流程，不登记在本表。
        Capability.values().forEach { cap ->
            assertFalse(cap.name, CapabilityTable.allows(KnownDeviceModel.TSLIM_X2, api, cap))
            assertFalse(cap.name, canControlPump(KnownDeviceModel.TSLIM_X2, cap))
        }
    }

    @Test
    fun mobi_allowsEveryRegisteredCapability() {
        Capability.values().forEach { cap ->
            assertTrue(cap.name, CapabilityTable.allows(KnownDeviceModel.MOBI, api, cap))
            assertTrue(cap.name, canControlPump(KnownDeviceModel.MOBI, cap))
        }
    }

    @Test
    fun apiVersionIsIgnoredInFirstRelease() {
        // 首版不按版本分档：null 与已知版本必须给出相同结果。
        assertTrue(CapabilityTable.allows(KnownDeviceModel.MOBI, null, Capability.SET_MODES))
        assertTrue(CapabilityTable.allows(KnownDeviceModel.MOBI, api, Capability.SET_MODES))
        assertFalse(CapabilityTable.allows(KnownDeviceModel.TSLIM_X2, null, Capability.SET_MODES))
    }

    @Test
    fun totalGateStillWinsOverTable() {
        // 即使表里登记了该能力，总闸（model == MOBI）仍先拒绝 X2。
        assertFalse(canControlPump(KnownDeviceModel.TSLIM_X2, Capability.CARTRIDGE_MODE, null, api))
        assertTrue(canControlPump(KnownDeviceModel.MOBI, Capability.CARTRIDGE_MODE, null, api))
    }
}
