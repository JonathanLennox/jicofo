/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.bridge

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import org.jitsi.config.setNewConfig
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.test.time.FakeClock
import org.jxmpp.jid.impl.JidCreate

class BridgeSelectorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        MetaconfigSettings.cacheEnabled = false
        val clock = FakeClock()
        // Test different types of jid (domain, entity bare, entity full).
        val jid1 = JidCreate.from("jvb1.example.com")
        val jid2 = JidCreate.from("jvb2@example.com")
        val jid3 = JidCreate.from("jvb3@example.com/goldengate")

        context("Selection based on operational status") {
            val bridgeSelector = BridgeSelector(clock)
            val jvb1 = bridgeSelector.addJvbAddress(jid1)
            val jvb2 = bridgeSelector.addJvbAddress(jid2)
            val jvb3 = bridgeSelector.addJvbAddress(jid3)

            bridgeSelector.selectBridge() shouldBeIn setOf(jvb1, jvb2, jvb3)

            // Bridge 1 is down
            jvb1.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBeIn setOf(jvb2, jvb3)

            // Bridge 2 is down
            jvb2.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBe jvb3

            // Bridge 1 is up again, but 3 is down instead
            jvb1.setIsOperational(true)
            jvb3.setIsOperational(false)
            // We need to elapse time after setting isOperational=true because isOperational=false is sticky
            clock.elapse(BridgeConfig.config.failureResetThreshold)
            bridgeSelector.selectBridge() shouldBe jvb1
        }
        context("Selection based on stress level") {
            val bridgeSelector = BridgeSelector(clock)
            val jvb1 = bridgeSelector.addJvbAddress(jid1).apply { setStats(stress = 0.1) }
            val jvb2 = bridgeSelector.addJvbAddress(jid2).apply { setStats(stress = 0.23) }
            val jvb3 = bridgeSelector.addJvbAddress(jid3).apply { setStats(stress = 0.0) }

            bridgeSelector.selectBridge() shouldBe jvb3

            // Now Jvb 3 gets occupied the most
            jvb3.setStats(stress = 0.3)
            bridgeSelector.selectBridge() shouldBe jvb1

            // Jvb 1 is gone
            jvb1.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBe jvb2

            // All bridges down
            jvb2.setIsOperational(false)
            jvb3.setIsOperational(false)
            bridgeSelector.selectBridge() shouldBe null

            jvb1.setIsOperational(true)
            jvb2.setIsOperational(true)
            jvb3.setIsOperational(true)
            // We need to elapse time after setting isOperational=true because isOperational=false is sticky
            clock.elapse(BridgeConfig.config.failureResetThreshold)

            jvb1.setStats(stress = .01)
            jvb2.setStats(stress = 0.0)
            jvb3.setStats(stress = 0.0)
            bridgeSelector.selectBridge() shouldBeIn setOf(jvb2, jvb3)

            // JVB 2 least occupied
            jvb1.setStats(stress = .01)
            jvb2.setStats(stress = 0.0)
            jvb3.setStats(stress = .01)
            bridgeSelector.selectBridge() shouldBe jvb2
        }
        context("Selection with a conference bridge removed from the selector") {
            setNewConfig(
                """
                    jicofo.octo.enabled=true
                    jicofo.bridge.selection-strategy=RegionBasedBridgeSelectionStrategy
                """.trimIndent(),
                true
            )

            val regionBasedSelector = BridgeSelector(clock)
            val jvb1 = regionBasedSelector.addJvbAddress(jid1).apply { setStats(stress = 0.2, region = "r1") }
            val jvb2 = regionBasedSelector.addJvbAddress(jid2).apply { setStats(stress = 0.5, region = "r2") }
            val jvb3 = regionBasedSelector.addJvbAddress(jid3).apply { setStats(stress = 0.1, region = "r3") }

            regionBasedSelector.removeJvbAddress(jid3)

            regionBasedSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 1, jvb3 to 1), null) shouldBe jvb1
            regionBasedSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 1, jvb3 to 1), "r2") shouldBe jvb2
        }
        context("SplitBridgeSelectionStrategy") {
            setNewConfig(
                """
                    jicofo.octo.enabled=true
                    jicofo.bridge.selection-strategy=SplitBridgeSelectionStrategy
                """.trimIndent(),
                true
            )

            val splitSelector = BridgeSelector(clock)
            val jvb1 = splitSelector.addJvbAddress(jid1).apply { setStats(stress = 0.2, region = "r1") }
            val jvb2 = splitSelector.addJvbAddress(jid2).apply { setStats(stress = 0.5, region = "r2") }
            val jvb3 = splitSelector.addJvbAddress(jid3).apply { setStats(stress = 0.1, region = "r3") }

            splitSelector.selectBridge() shouldBeIn setOf(jvb1, jvb2, jvb3)
            splitSelector.selectBridge(mapOf(jvb1 to 1), null) shouldBeIn setOf(jvb2, jvb3)
            splitSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 1), null) shouldBe jvb3
            splitSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 2, jvb3 to 3), null) shouldBe jvb1

            splitSelector.removeJvbAddress(jid1)

            splitSelector.selectBridge(mapOf(jvb1 to 1, jvb2 to 2, jvb3 to 3), null) shouldBe jvb2
        }
    }
}
