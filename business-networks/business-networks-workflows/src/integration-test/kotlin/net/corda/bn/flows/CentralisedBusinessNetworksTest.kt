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
        private const val NUMBER_OF_MEMBERS = 2
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

            val bnoVaultUpdates = bnoNode.rpc.run {
                VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
            }
            val memberVaultUpdates = memberNodes.map { node ->
                node.rpc.run {
                    VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
                }
            }

            val networkId = UniqueIdentifier()
            val businessIdentity = MyIdentity("BNO")
            val groupId = UniqueIdentifier()
            val groupName = "default-group"
            val bnoMembershipId = createBusinessNetworkAndCheck(bnoNode, bnoVaultUpdates, networkId, businessIdentity, groupId, groupName, defaultNotaryIdentity)

            val membershipIds = memberNodes.map {
                it.requestMembership(bnoNode.identity(), networkId, null, defaultNotaryIdentity).linearId
            }.toSet()
            membershipIds.forEach { bnoNode.activateMembership(it, defaultNotaryIdentity) }

            val group = bnoNode.services.cordaService(DatabaseService::class.java).getAllBusinessNetworkGroups(networkId).single()
            bnoNode.modifyGroup(group.state.data.linearId, null, membershipIds + bnoMembershipId, defaultNotaryIdentity)

            membershipIds.forEach { bnoNode.suspendMembership(it, defaultNotaryIdentity) }
            membershipIds.forEach { bnoNode.revokeMembership(it, defaultNotaryIdentity) }
        }
    }
}