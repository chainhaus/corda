package net.corda.bn.flows

import net.corda.bn.states.BNORole
import net.corda.core.contracts.UniqueIdentifier
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import org.junit.Test

class DecentralisedBusinessNetworksTest : AbstractBusinessNetworksTest() {

    @Test(timeout = 300_000)
    fun `public decentralised business network test`() {
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

            membershipIds.forEach { (membershipId, _) ->
                modifyRolesAndCheck(bnoNode, memberNodes, membershipId, setOf(BNORole()), defaultNotaryIdentity)
            }

            // more to come...
        }
    }
}