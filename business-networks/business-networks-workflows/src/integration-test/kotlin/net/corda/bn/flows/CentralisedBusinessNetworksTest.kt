package net.corda.bn.flows

import net.corda.bn.states.BNIdentity
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.serialization.CordaSerializable
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import org.junit.Test

@CordaSerializable
data class MyIdentity(val name: String) : BNIdentity

class CentralisedBusinessNetworksTest : AbstractBusinessNetworksTest() {

    companion object {
        private const val NUMBER_OF_MEMBERS = 4
    }

    private val bnoIdentity = TestIdentity(CordaX500Name.parse("O=BNO,L=New York,C=US")).party
    private val membersIdentities = (0..NUMBER_OF_MEMBERS).mapIndexed { idx, _ -> TestIdentity(CordaX500Name.parse("O=Member$idx,L=New York,C=US")).party }

    @Test(timeout = 300_000)
    fun `business networks test`() {
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
}