/*
 * Copyright @ 2018 - present 8x8, Inc.
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

class VisitorSelectionStrategy : BridgeSelectionStrategy() {
    private val participantSelectionStrategy = checkNotNull(BridgeConfig.config.visitorSelectionStrategy) {
        "participant-selection-strategy must be set when VisitorSelectionStrategy is used"
    }

    private val visitorSelectionStrategy = checkNotNull(BridgeConfig.config.visitorSelectionStrategy) {
        "visitor-selection-strategy must be set when VisitorSelectionStrategy is used"
    }

    override fun doSelect(
        bridges: List<Bridge>,
        conferenceBridges: Map<Bridge, ConferenceBridgeProperties>,
        participantProperties: ParticipantProperties
    ): Bridge? {
        val eligibleBridges = bridges.filterNot {
            /* Note this is not the same thing as != -- bridges not in conferenceBridges should always be
             * included.
             */
            conferenceBridges[it]?.visitor == !participantProperties.visitor
        }
        val conferenceBridgesOfType = conferenceBridges.filter { it.value.visitor == participantProperties.visitor }

        return if (participantProperties.visitor) {
            visitorSelectionStrategy
        } else {
            participantSelectionStrategy
        }.doSelect(eligibleBridges, conferenceBridgesOfType, participantProperties)
    }
}
