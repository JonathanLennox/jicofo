package org.jitsi.jicofo.cascade

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class TestCascade : Cascade {
    override val bridges = HashMap<String, CascadeNode>()
}

class TestCascadeNode(override val relayId: String) : CascadeNode {
    override val links = HashMap<String, CascadeLink>()
    override fun addLink(node: CascadeNode, meshId: String) {
        links[node.relayId] = TestCascadeLink(node.relayId, meshId)
    }

    override fun toString(): String = "$relayId: [${links.values.joinToString()}]"
}

class TestCascadeLink(override val relayId: String, override val meshId: String) : CascadeLink {
    override fun toString(): String = "$meshId->$relayId"
}

class CascadeTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val numNodes = 5
    init {
        context("creating a cascade with a single mesh") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }
            nodes.iterator().forEach { cascade.addNodeToMesh(it, "A") }

            should("cause all the nodes to be connected") {
                nodes.forEach { node ->
                    cascade.containsNode(node) shouldBe true
                    node.links.size shouldBe numNodes - 1

                    nodes.forEach { other ->
                        if (other === node) {
                            node.links.contains(other.relayId) shouldBe false
                        } else {
                            node.links[other.relayId]?.relayId shouldBe other.relayId
                        }
                    }
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("not call a callback when removing any node") {
                nodes.forEach {
                    var called = false
                    cascade.removeNode(it) { _, _ ->
                        called = true
                        setOf()
                    }
                    called shouldBe false
                }
                cascade.validate()
            }
        }
        context("creating a mesh with two nodes") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }
            cascade.addNodeToMesh(nodes[0], "A")
            cascade.addNodeToMesh(nodes[1], "A")
            cascade.addNodeToMesh(nodes[2], "A")

            cascade.addMesh(nodes[0], nodes[3], "B")
            cascade.addNodeToMesh(nodes[4], "B")

            should("create the correct links for each node") {
                val aNodes = cascade.getMeshNodes("A")
                val bNodes = cascade.getMeshNodes("B")

                aNodes.map { it.relayId }.shouldContainExactly("0", "1", "2")
                bNodes.map { it.relayId }.shouldContainExactly("0", "3", "4")

                nodes[0].links.size shouldBe 4
                for (i in 1 until numNodes) {
                    nodes[i].links.size shouldBe 2
                }

                arrayOf(aNodes, bNodes).forEach { set ->
                    set.forEach { node ->
                        set.forEach { other ->
                            if (other === node) {
                                node.links.contains(other.relayId) shouldBe false
                            } else {
                                node.links[other.relayId]?.relayId shouldBe other.relayId
                            }
                        }
                    }
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("not call a callback when removing a leaf node") {
                for (i in 1 until numNodes) {
                    var called = false
                    cascade.removeNode(nodes[i]) { _, _ ->
                        called = true
                        setOf()
                    }
                    called shouldBe false
                }
                cascade.validate()
            }
            should("call the callback when removing the core node") {
                var called = true
                cascade.removeNode(nodes[0]) { _, _ ->
                    called = true
                    setOf()
                }
                called shouldBe true
            }
            should("fail validation if not repaired after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ -> setOf() }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
        }

        context("creating a star topology") {
            val cascade = TestCascade()
            val nodes = Array(numNodes) { i -> TestCascadeNode(i.toString()) }

            cascade.addNodeToMesh(nodes[0], "A")
            for (i in 1 until numNodes) {
                cascade.addMesh(nodes[0], nodes[i], ('A'.code + i).toChar().toString())
            }

            should("create the correct links for each node") {
                cascade.getMeshNodes("A").size shouldBe 0
                for (m in 'B'..'E') {
                    cascade.getMeshNodes(m.toString()).size shouldBe 2
                }
                nodes[0].links.size shouldBe 4
                for (i in 1 until numNodes) {
                    nodes[i].links.size shouldBe 1
                }
            }
            should("validate") {
                cascade.validate()
            }
            should("not call a callback when removing a leaf node") {
                for (i in 1 until numNodes) {
                    var called = false
                    cascade.removeNode(nodes[i]) { _, _ ->
                        called = true
                        setOf()
                    }
                    called shouldBe false
                }
            }
            should("call the callback when removing the core node") {
                var called = true
                cascade.removeNode(nodes[0]) { _, _ ->
                    called = true
                    setOf()
                }
                called shouldBe true
            }
            should("fail validation if not repaired after removing the core node") {
                cascade.removeNode(nodes[0]) { _, _ -> setOf() }
                shouldThrow<IllegalStateException> {
                    cascade.validate()
                }
            }
        }
    }
}
