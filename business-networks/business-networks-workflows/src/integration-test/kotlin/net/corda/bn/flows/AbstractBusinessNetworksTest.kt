package net.corda.bn.flows

import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNORole
import net.corda.bn.states.BNRole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.testing.core.expect
import net.corda.testing.core.expectEvents
import net.corda.testing.driver.NodeHandle
import rx.Observable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class AbstractBusinessNetworksTest {

    data class VaultUpdates(val membershipUpdates: Observable<Vault.Update<MembershipState>>, val groupUpdates: Observable<Vault.Update<GroupState>>)

    protected fun createBusinessNetworkAndCheck(
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            networkId: UniqueIdentifier,
            businessIdentity: BNIdentity,
            groupId: UniqueIdentifier,
            groupName: String,
            notary: Party
    ): UniqueIdentifier {
        val bnoMembershipId = bnoNode.createBusinessNetwork(
                networkId,
                businessIdentity,
                groupId,
                groupName,
                notary
        ).linearId

        bnoVaultUpdates.apply {
            membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(networkId.toString(), membership.networkId)
                    assertEquals(bnoNode.identity(), membership.identity.cordaIdentity)
                    assertEquals(businessIdentity, membership.identity.businessIdentity)
                    assertEquals(MembershipStatus.ACTIVE, membership.status)
                    assertEquals(setOf(BNORole()), membership.roles)
                }
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

        return bnoMembershipId
    }

    protected fun requestMembershipAndCheck(
            memberNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            authorisedParty: Party,
            networkId: String,
            businessIdentity: BNIdentity,
            notary: Party
    ) {
        memberNode.requestMembership(authorisedParty, networkId, businessIdentity, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
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
    }

    protected fun activateMembershipAndCheck(
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            membershipId: UniqueIdentifier,
            notary: Party
    ) {
        bnoNode.activateMembership(membershipId, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
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
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            membershipId: UniqueIdentifier,
            notary: Party
    ) {
        bnoNode.suspendMembership(membershipId, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
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
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            membershipId: UniqueIdentifier,
            notary: Party
    ) {
        bnoNode.activateMembership(membershipId, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.consumed.single().state.data
                    assertEquals(membershipId, membership.linearId)
                }
            }
        }
    }

    protected fun modifyRolesAndCheck(
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            membershipId: UniqueIdentifier,
            roles: Set<BNRole>,
            notary: Party
    ) {
        bnoNode.modifyRoles(membershipId, roles, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
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
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            membershipId: UniqueIdentifier,
            businessIdentity: BNIdentity,
            notary: Party
    ) {
        bnoNode.modifyBusinessIdentity(membershipId, businessIdentity, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
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
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            networkId: String,
            groupId: UniqueIdentifier,
            groupName: String,
            additionalParticipants: Set<UniqueIdentifier>,
            notary: Party,
            expectedParticipants: Set<Party>
    ) {
        bnoNode.createGroup(networkId, groupId, groupName, additionalParticipants, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(businessIdentity, membership.identity.businessIdentity)
                    assertEquals(membershipId, membership.linearId)
                }
            }
            vaultUpdates.groupUpdates.expectEvents {
                expect { update ->
                    val group = update.produced.single().state.data
                    assertEquals(networkId, group.networkId)
                    assertEquals(groupName, group.name)
                    assertEquals(groupId, group.linearId)
                    assertEquals(expectedParticipants, group.participants.toSet())
                }
            }
        }
    }

    protected fun modifyGroupAndCheck(
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            groupId: UniqueIdentifier,
            name: String,
            participants: Set<UniqueIdentifier>,
            notary: Party,
            expectedParticipants: Set<Party>
    ) {
        bnoNode.modifyGroup(groupId, name, participants, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(businessIdentity, membership.identity.businessIdentity)
                    assertEquals(membershipId, membership.linearId)
                }
            }
            vaultUpdates.groupUpdates.expectEvents {
                expect { update ->
                    val group = update.produced.single().state.data
                    assertEquals(name, group.name)
                    assertEquals(groupId, group.linearId)
                    assertEquals(expectedParticipants, group.participants.toSet())
                }
            }
        }
    }

    protected fun deleteGroupAndCheck(
            bnoNode: NodeHandle,
            bnoVaultUpdates: VaultUpdates,
            membersVaultUpdates: List<VaultUpdates>,
            groupId: UniqueIdentifier,
            notary: Party
    ) {
        bnoNode.deleteGroup(groupId, notary)

        (membersVaultUpdates + bnoVaultUpdates).forEach { vaultUpdates ->
            vaultUpdates.membershipUpdates.expectEvents {
                expect { update ->
                    val membership = update.produced.single().state.data
                    assertEquals(businessIdentity, membership.identity.businessIdentity)
                    assertEquals(membershipId, membership.linearId)
                }
            }
            vaultUpdates.groupUpdates.expectEvents {
                expect { update ->
                    val group = update.consumed.single().state.data
                    assertEquals(groupId, group.linearId)
                }
            }
        }
    }
}