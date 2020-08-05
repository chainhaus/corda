package net.corda.bn.flows

import net.corda.core.contracts.UniqueIdentifier
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import org.junit.Test

class CentralisedBusinessNetworksTest : AbstractBusinessNetworksTest() {

    @Test(timeout = 300_000)
    fun `public centralised business network test`() {
        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = listOf(TestCordapp.findCordapp("net.corda.bn.contracts"), TestCordapp.findCordapp("net.corda.bn.flows"))
        )) {
            val bnoNode = startNodes(listOf(bnoIdentity)).single()
            val memberNodes = startNodes(membersIdentities)

            val networkId = UniqueIdentifier()
            val bnoBusinessIdentity = MyIdentity("BNO")
            val groupId = UniqueIdentifier()
            val groupName = "default-group"
            val bnoMembershipId = createBusinessNetworkAndCheck(bnoNode, networkId, bnoBusinessIdentity, groupId, groupName, defaultNotaryIdentity)

            val membershipIds = memberNodes.mapIndexed { idx, node ->
                val memberBusinessIdentity = MyIdentity("Member$idx")
                val linearId = requestMembershipAndCheck(node, bnoNode, networkId.toString(), memberBusinessIdentity, defaultNotaryIdentity)

                linearId to node
            }.toMap()

            membershipIds.forEach { (membershipId, node) ->
                activateMembershipAndCheck(bnoNode, listOf(node), membershipId, defaultNotaryIdentity)
            }

            modifyGroupAndCheck(
                    bnoNode,
                    memberNodes,
                    groupId,
                    groupName,
                    membershipIds.keys + bnoMembershipId,
                    defaultNotaryIdentity,
                    (memberNodes + bnoNode).map { it.identity() }.toSet()
            )

            val iterator = membershipIds.entries.iterator()
            iterator.next().also { (membershipId, node) ->
                suspendMembershipAndCheck(bnoNode, memberNodes, membershipId, defaultNotaryIdentity)
                revokeMembershipAndCheck(bnoNode, memberNodes, membershipId, defaultNotaryIdentity, networkId.toString(), node.identity())
            }
            iterator.next().also { (membershipId, node) ->
                revokeMembershipAndCheck(bnoNode, memberNodes.run { takeLast(size - 1) }, membershipId, defaultNotaryIdentity, networkId.toString(), node.identity())
            }
            iterator.next().also { (membershipId, _) ->
                modifyBusinessIdentityAndCheck(bnoNode, memberNodes.run { takeLast(size - 2) }, membershipId, MyIdentity("SpecialMember"), defaultNotaryIdentity)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `private centralised business network test`() {
        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = listOf(TestCordapp.findCordapp("net.corda.bn.contracts"), TestCordapp.findCordapp("net.corda.bn.flows"))
        )) {
            val bnoNode = startNodes(listOf(bnoIdentity)).single()
            val memberNodes = startNodes(membersIdentities)

            val networkId = UniqueIdentifier()
            val bnoBusinessIdentity = MyIdentity("BNO")
            val groupId = UniqueIdentifier()
            val groupName = "default-group"
            createBusinessNetworkAndCheck(bnoNode, networkId, bnoBusinessIdentity, groupId, groupName, defaultNotaryIdentity)

            val membershipIds = memberNodes.mapIndexed { idx, node ->
                val memberBusinessIdentity = MyIdentity("Member$idx")
                val linearId = requestMembershipAndCheck(node, bnoNode, networkId.toString(), memberBusinessIdentity, defaultNotaryIdentity)

                linearId to node
            }.toMap()

            membershipIds.forEach { (membershipId, node) ->
                activateMembershipAndCheck(bnoNode, listOf(node), membershipId, defaultNotaryIdentity)
                createGroupAndCheck(
                        bnoNode,
                        listOf(node),
                        networkId.toString(),
                        UniqueIdentifier(),
                        "custom-group-$membershipId",
                        setOf(membershipId),
                        defaultNotaryIdentity,
                        setOf(bnoNode.identity(), node.identity())
                )
            }

            membershipIds.forEach { (membershipId, node) ->
                modifyBusinessIdentityAndCheck(bnoNode, listOf(node), membershipId, MyIdentity("SpecialMember-$membershipId"), defaultNotaryIdentity)
            }

            membershipIds.forEach { (membershipId, node) ->
                suspendMembershipAndCheck(bnoNode, listOf(node), membershipId, defaultNotaryIdentity)
            }

            membershipIds.forEach { (membershipId, node) ->
                revokeMembershipAndCheck(bnoNode, listOf(node), membershipId, defaultNotaryIdentity, networkId.toString(), node.identity())
            }
        }
    }

    @Test(timeout = 300_000)
    fun `rbac oriented business network test`() {
        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = listOf(TestCordapp.findCordapp("net.corda.bn.contracts"), TestCordapp.findCordapp("net.corda.bn.flows"))
        )) {
            val bnoNode = startNodes(listOf(bnoIdentity)).single()
            val memberNodes = startNodes(membersIdentities)

            val networkId = UniqueIdentifier()
            val bnoBusinessIdentity = MyIdentity("BNO")
            val groupId = UniqueIdentifier()
            val groupName = "default-group"
            val bnoMembershipId = createBusinessNetworkAndCheck(bnoNode, networkId, bnoBusinessIdentity, groupId, groupName, defaultNotaryIdentity)

            val membershipIds = memberNodes.mapIndexed { idx, node ->
                val memberBusinessIdentity = MyIdentity("Member$idx")
                val linearId = requestMembershipAndCheck(node, bnoNode, networkId.toString(), memberBusinessIdentity, defaultNotaryIdentity)

                linearId to node
            }.toMap()

            membershipIds.forEach { (membershipId, node) ->
                activateMembershipAndCheck(bnoNode, listOf(node), membershipId, defaultNotaryIdentity)
            }

            modifyGroupAndCheck(
                    bnoNode,
                    memberNodes,
                    groupId,
                    groupName,
                    membershipIds.keys + bnoMembershipId,
                    defaultNotaryIdentity,
                    (memberNodes + bnoNode).map { it.identity() }.toSet()
            )

            // assign roles and more to come...
        }
    }
}