/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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

package org.jitsi.jicofo.cascade

import kotlin.collections.HashSet
import kotlin.streams.toList

/**
 * A representation of a cascade of bridges.
 */
interface Cascade<N : CascadeNode<N, L>, L : CascadeLink> {
    val bridges: MutableMap<String?, N>
    fun addLinkBetween(node: N, otherNode: N, meshId: String)
}

/**
 * A representation of a single bridge in a cascade
 */
interface CascadeNode<N : CascadeNode<N, L>, L : CascadeLink> {
    val relayId: String?
    val relays: MutableMap<String, L>
}

/**
 * A representation of a link between bridges in a cascade
 */
interface CascadeLink {
    val relayId: String?
    val meshId: String?
}

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.containsNode(node: N) =
    bridges[node.relayId] === node

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.hasMesh(meshId: String): Boolean =
    bridges.values.stream().anyMatch { node -> node.relays.values.any { it.meshId == meshId } }

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getMeshNodes(meshId: String?): List<N> =
    bridges.values.stream().filter { node -> node.relays.values.any { it.meshId == meshId } }.toList()

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.addNodeToMesh(newNode: N, meshId: String) {
    require(!containsNode(newNode)) {
        "Cascade $this already contains node $newNode"
    }

    if (bridges.isEmpty()) {
        bridges[newNode.relayId] = newNode
        return
    } else if (bridges.size == 1) {
        val onlyNode = bridges.values.first()
        addLinkBetween(onlyNode, newNode, meshId)
        bridges[newNode.relayId] = newNode
        return
    }

    val meshNodes = getMeshNodes(meshId)
    require(meshNodes.isNotEmpty()) {
        "meshId $meshId must correspond to an existing mesh ID when size ${bridges.size} > 1"
    }

    meshNodes.forEach { node -> addLinkBetween(node, newNode, meshId) }
    bridges[newNode.relayId] = newNode
}

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.addMesh(existingNode: N, newNode: N, meshId: String) {
    require(containsNode(existingNode)) {
        "Cascade $this does not contain node $existingNode"
    }

    require(!containsNode(newNode)) {
        "Cascade $this already contains node $newNode"
    }

    require(!hasMesh(meshId)) {
        "Cascade $this already contains mesh $meshId"
    }

    addLinkBetween(existingNode, newNode, meshId)
    bridges[newNode.relayId] = newNode
}

fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.removeNode(
    node: N,
    repairFn: (Cascade<N, L>, Set<String?>) -> Set<Triple<N, N, String>>
) {
    if (!containsNode(node)) {
        return; /* Or should this be an exception. i.e. `require`? */
    }
    check(bridges[node.relayId] === node) {
        "Bridge entry for ${node.relayId} is not $node"
    }
    bridges.remove(node.relayId)

    node.relays.keys.forEach { key ->
        val other = bridges[key]
        checkNotNull(other) {
            "Cascade does not contain node for $key"
        }
        val backLink = other.relays[node.relayId]
        checkNotNull(backLink) {
            "Node $other does not have backlink to $node"
        }
        check(backLink.relayId == node.relayId) {
            "Backlink from $other to $node points to ${backLink.relayId}"
        }
        other.relays.remove(node.relayId)
    }

    val meshes = node.relays.values.map { it.meshId }.toSet()

    if (meshes.size > 1) {
        /* The removed node was a bridge between two or more meshes - we need to repair the cascade. */
        val newLinks = repairFn(this, meshes)
        newLinks.forEach { (node, other, mesh) ->
            addLinkBetween(node, other, mesh)
        }
        /* TODO: validate that the newly-added links have left us with a valid cascade? */
    }
}

/** Return a set of all nodes "behind" a given node link. */
/* TODO: would this be better as an iterator? */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getNodesBehind(from: N, toward: N): Set<N> {
    val set = HashSet<N>()
    getNodesBehind(from, toward, set)
    return set
}

private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.getNodesBehind(
    from: N,
    toward: N,
    nodes: MutableSet<N>
) {
    val link = requireNotNull(from.relays[toward.relayId]) {
        "$from does not have a link to $toward"
    }
    nodes.add(toward)
    toward.relays.values.forEach {
        if (it.relayId == from.relayId || it.meshId == link.meshId) {
            return@forEach
        }
        val next = checkNotNull(bridges[it.relayId])
        getNodesBehind(toward, next, nodes)
    }
}

/** Validate a node, or throw IllegalStateException. */
private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.validateNode(node: N) {
    node.relays.entries.forEach { (key, link) ->
        check(key != node.relayId) {
            "$node has a link to itself"
        }
        check(key == link.relayId) {
            "$node link indexed by $key links to $link"
        }
        val other = bridges[key]
        checkNotNull(other) {
            "$node has link to $key not found in cascade"
        }
        val backLink = other.relays[node.relayId]
        checkNotNull(backLink) {
            "$node has link to $other, but $other has no link to $node"
        }
        check(link.meshId == backLink.meshId) {
            "$node links to $other in mesh ${link.meshId}, but backlink has mesh ${backLink.meshId}"
        }
    }
}

/** Validate a mesh, or throw IllegalStateException. */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.validateMesh(meshId: String?) {
    val meshNodes = getMeshNodes(meshId)

    meshNodes.forEach { node ->
        meshNodes.forEach { other ->
            if (other != node) {
                check(node.relays.contains(other.relayId)) {
                    "$node has no link to $other in mesh $meshId"
                }
                val link = node.relays[other.relayId]
                check(link!!.meshId == meshId) {
                    "link from $node to $other has meshId ${link.meshId}, not expected $meshId"
                }
            }
        }
    }
}

private fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.visitNodeForValidation(
    node: N,
    parent: N?,
    visitedNodes: MutableSet<String?>,
    validatedMeshes: MutableSet<String?>
) {
    validateNode(node)
    visitedNodes.add(node.relayId)

    node.relays.values.forEach {
        if (!visitedNodes.contains(it.relayId)) {
            if (!validatedMeshes.contains(it.meshId)) {
                validateMesh(it.meshId)
                validatedMeshes.add(it.meshId)
            }
            val linkedNode = bridges[it.relayId]
            checkNotNull(linkedNode) {
                "$node has link to node ${it.relayId} not found in cascade"
            }
            visitNodeForValidation(linkedNode, node, visitedNodes, validatedMeshes)
        } else {
            check(it.relayId == parent?.relayId || validatedMeshes.contains(it.meshId)) {
                "Multiple paths found to ${bridges[it.relayId]}"
            }
        }
    }
}

/** Validate a cascade, or throw IllegalStateException */
fun <N : CascadeNode<N, L>, L : CascadeLink> Cascade<N, L>.validate() {
    if (bridges.isEmpty()) {
        /* Empty cascade is trivially valid */
        return
    }
    val firstNode = bridges.values.first()

    val visitedNodes = HashSet<String?>()
    val validatedMeshes = HashSet<String?>()

    visitNodeForValidation(firstNode, null, visitedNodes, validatedMeshes)

    check(visitedNodes.size == bridges.size) {
        val unvisitedNodes = bridges.keys.subtract(visitedNodes)
        "Nodes ${unvisitedNodes.joinToString()} not reachable from initial node $firstNode"
    }
}
