package net.corda.bn.flows

import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNORole
import net.corda.bn.states.BNRole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.services.Vault
import net.corda.core.serialization.CordaSerializable
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.expect
import net.corda.testing.core.expectEvents
import net.corda.testing.core.sequence
import net.corda.testing.driver.InProcess
import net.corda.testing.driver.NodeHandle
import rx.Observable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class AbstractBusinessNetworksTest {

    companion object {
        private const val NUMBER_OF_MEMBERS = 4
    }

    protected val bnoIdentity = TestIdentity(CordaX500Name.parse("O=BNO,L=New York,C=US")).party
    protected val membersIdentities = (0..NUMBER_OF_MEMBERS).mapIndexed { idx, _ -> TestIdentity(CordaX500Name.parse("O=Member$idx,L=New York,C=US")).party }

    @CordaSerializable
    data class MyIdentity(val name: String) : BNIdentity

    data class VaultUpdates(val membershipUpdates: Observable<Vault.Update<MembershipState>>, val groupUpdates: Observable<Vault.Update<GroupState>>)

    protected fun createBusinessNetworkAndCheck(
            bnoNode: NodeHandle,
            networkId: UniqueIdentifier,
            businessIdentity: BNIdentity,
            groupId: UniqueIdentifier,
            groupName: String,
            notary: Party
    ): UniqueIdentifier {
        val bnoVaultUpdates = bnoNode.rpc.run {
            VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
        }

        val membershipId = bnoNode.createBusinessNetwork(
                networkId,
                businessIdentity,
                groupId,
                groupName,
                notary
        ).linearId

        bnoVaultUpdates.apply {
            membershipUpdates.expectEvents {
                sequence(
                        expect { update ->
                            val membership = update.produced.single().state.data
                            assertEquals(networkId.toString(), membership.networkId)
                            assertEquals(bnoNode.identity(), membership.identity.cordaIdentity)
                            assertEquals(businessIdentity, membership.identity.businessIdentity)
                            assertEquals(MembershipStatus.PENDING, membership.status)
                            assertEquals(emptySet(), membership.roles)
                        },
                        expect { update ->
                            val membership = update.produced.single().state.data
                            assertEquals(networkId.toString(), membership.networkId)
                            assertEquals(bnoNode.identity(), membership.identity.cordaIdentity)
                            assertEquals(businessIdentity, membership.identity.businessIdentity)
                            assertEquals(MembershipStatus.ACTIVE, membership.status)
                            assertEquals(emptySet(), membership.roles)
                        },
                        expect { update ->
                            val membership = update.produced.single().state.data
                            assertEquals(networkId.toString(), membership.networkId)
                            assertEquals(bnoNode.identity(), membership.identity.cordaIdentity)
                            assertEquals(businessIdentity, membership.identity.businessIdentity)
                            assertEquals(MembershipStatus.ACTIVE, membership.status)
                            assertEquals(setOf(BNORole()), membership.roles)
                        }
                )
            }
            groupUpdates.expectEvents {
                expect { update ->
                    val group = update.produced.single().state.data
                    assertEquals(networkId.toString(), group.networkId)
                    assertEquals(groupName, group.name)
                    assertEquals(groupId, group.linearId)
                    assertEquals(setOf(bnoNode.identity()), group.participants.toSet())
                }
            }
        }

        return membershipId
    }

    protected fun requestMembershipAndCheck(
            memberNode: NodeHandle,
            bnoNode: NodeHandle,
            networkId: String,
            businessIdentity: BNIdentity,
            notary: Party
    ): UniqueIdentifier {
        val allVaultUpdates = listOf(bnoNode, memberNode).map { node ->
            node.rpc.run {
                VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
            }
        }

        val membershipId = memberNode.requestMembership(bnoNode.identity(), networkId, businessIdentity, notary).linearId

        allVaultUpdates.forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(networkId, membership.networkId)
                    assertEquals(memberNode.identity(), membership.identity.cordaIdentity)
                    assertEquals(businessIdentity, membership.identity.businessIdentity)
                    assertEquals(MembershipStatus.PENDING, membership.status)
                    assertTrue(membership.roles.isEmpty())
                }
            }
        }

        return membershipId
    }

    protected fun activateMembershipAndCheck(
            bnoNode: NodeHandle,
            memberNodes: List<NodeHandle>,
            membershipId: UniqueIdentifier,
            notary: Party
    ) {
        val allVaultUpdates = (memberNodes + bnoNode).map { node ->
            node.rpc.run {
                VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
            }
        }

        bnoNode.activateMembership(membershipId, notary)

        allVaultUpdates.forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(MembershipStatus.ACTIVE, membership.status)
                    assertEquals(membershipId, membership.linearId)
                }
            }
        }
    }

    protected fun suspendMembershipAndCheck(
            bnoNode: NodeHandle,
            memberNodes: List<NodeHandle>,
            membershipId: UniqueIdentifier,
            notary: Party
    ) {
        val allVaultUpdates = (memberNodes + bnoNode).map { node ->
            node.rpc.run {
                VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
            }
        }

        bnoNode.suspendMembership(membershipId, notary)

        allVaultUpdates.forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(MembershipStatus.SUSPENDED, membership.status)
                    assertEquals(membershipId, membership.linearId)
                }
            }
        }
    }

    protected fun revokeMembershipAndCheck(
            bnoNode: InProcess,
            memberNodes: List<InProcess>,
            membershipId: UniqueIdentifier,
            notary: Party,
            networkId: String,
            revokedParty: Party
    ) {
        bnoNode.revokeMembership(membershipId, notary)

        (memberNodes + bnoNode).forEach { node ->
            val service = node.services.cordaService(DatabaseService::class.java)

            assertNull(service.getMembership(membershipId))
            assertTrue(service.getAllBusinessNetworkGroups(networkId).all { revokedParty !in it.state.data.participants })
        }
    }

    protected fun modifyRolesAndCheck(
            bnoNode: NodeHandle,
            memberNodes: List<NodeHandle>,
            membershipId: UniqueIdentifier,
            roles: Set<BNRole>,
            notary: Party
    ) {
        val allVaultUpdates = (memberNodes + bnoNode).map { node ->
            node.rpc.run {
                VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
            }
        }

        bnoNode.modifyRoles(membershipId, roles, notary)

        allVaultUpdates.forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(roles, membership.roles)
                    assertEquals(membershipId, membership.linearId)
                }
            }
        }
    }

    protected fun modifyBusinessIdentityAndCheck(
            bnoNode: NodeHandle,
            memberNodes: List<NodeHandle>,
            membershipId: UniqueIdentifier,
            businessIdentity: BNIdentity,
            notary: Party
    ) {
        val allVaultUpdates = (memberNodes + bnoNode).map { node ->
            node.rpc.run {
                VaultUpdates(vaultTrackBy<MembershipState>().updates, vaultTrackBy<GroupState>().updates)
            }
        }

        bnoNode.modifyBusinessIdentity(membershipId, businessIdentity, notary)

        allVaultUpdates.forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(businessIdentity, membership.identity.businessIdentity)
                    assertEquals(membershipId, membership.linearId)
                }
            }
        }
    }

    protected fun createGroupAndCheck(
            bnoNode: InProcess,
            memberNodes: List<InProcess>,
            networkId: String,
            groupId: UniqueIdentifier,
            groupName: String,
            additionalParticipants: Set<UniqueIdentifier>,
            notary: Party,
            expectedParticipants: Set<Party>
    ) {
        bnoNode.createGroup(networkId, groupId, groupName, additionalParticipants, notary)

        (memberNodes + bnoNode).forEach { node ->
            val service = node.services.cordaService(DatabaseService::class.java)

            val group = service.getBusinessNetworkGroup(groupId)
            assertNotNull(group)
            group?.state?.data?.let {
                assertEquals(networkId, it.networkId)
                assertEquals(groupName, it.name)
                assertEquals(groupId, it.linearId)
                assertEquals(expectedParticipants, it.participants.toSet())
            }
        }
    }

    protected fun modifyGroupAndCheck(
            bnoNode: InProcess,
            memberNodes: List<InProcess>,
            groupId: UniqueIdentifier,
            name: String,
            participants: Set<UniqueIdentifier>,
            notary: Party,
            expectedParticipants: Set<Party>
    ) {
        bnoNode.modifyGroup(groupId, name, participants, notary)

        (memberNodes + bnoNode).forEach { node ->
            val service = node.services.cordaService(DatabaseService::class.java)

            val group = service.getBusinessNetworkGroup(groupId)
            assertNotNull(group)
            group?.state?.data?.let {
                assertEquals(name, it.name)
                assertEquals(groupId, it.linearId)
                assertEquals(expectedParticipants, it.participants.toSet())
            }
        }
    }

    protected fun deleteGroupAndCheck(
            bnoNode: InProcess,
            memberNodes: List<InProcess>,
            groupId: UniqueIdentifier,
            notary: Party
    ) {
        bnoNode.deleteGroup(groupId, notary)

        (memberNodes + bnoNode).forEach { node ->
            val service = node.services.cordaService(DatabaseService::class.java)

            val group = service.getBusinessNetworkGroup(groupId)
            assertNotNull(group)
            group?.state?.data?.let {
                assertEquals(groupId, it.linearId)
            }
        }
    }
}