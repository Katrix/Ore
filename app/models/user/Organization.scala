package models.user

import db.impl.OrePostgresDriver.api._
import db.impl.access.UserBase
import db.impl.{OrganizationMembersTable, OrganizationRoleTable, OrganizationTable}
import db.{Model, ModelService, Named, ObjectId, ObjectReference, ObjectTimestamp}
import models.user.role.OrganizationRole
import ore.organization.OrganizationMember
import ore.permission.role.{Default, RoleType, Trust}
import ore.permission.scope.OrganizationScope
import ore.user.{MembershipDossier, UserOwned}
import ore.{Joinable, Visitable}
import slick.lifted.{Compiled, Rep, TableQuery}
import scala.concurrent.{ExecutionContext, Future}

import security.spauth.SpongeAuthApi
import cats.data.OptionT

/**
  * Represents an Ore Organization. An organization is like a [[User]] in the
  * sense that it shares many qualities with Users and also has a companion
  * User on the forums. An organization is made up by a group of Users who each
  * have a corresponding rank within the organization.
  *
  * @param id             Unique ID
  * @param createdAt      Date of creation
  * @param ownerId        The ID of the [[User]] that owns this organization
  */
case class Organization(id: ObjectId = ObjectId.Uninitialized,
                        createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
                        username: String,
                        ownerId: ObjectReference)
                        extends Model
                          with UserOwned
                          with OrganizationScope
                          with Named
                          with Visitable
                          with Joinable[OrganizationMember, Organization] {

  override type M = Organization
  override type T = OrganizationTable

  /**
    * Contains all information for [[User]] memberships.
    */
  override def memberships(implicit service: ModelService): MembershipDossier {
  type MembersTable = OrganizationMembersTable

  type MemberType = OrganizationMember

  type RoleTable = OrganizationRoleTable

  type ModelType = Organization

  type RoleType = OrganizationRole
} = new MembershipDossier {

    type ModelType = Organization
    type RoleType = OrganizationRole
    type RoleTable = OrganizationRoleTable
    type MembersTable = OrganizationMembersTable
    type MemberType = OrganizationMember

    val membersTableClass: Class[MembersTable] = classOf[OrganizationMembersTable]
    val roleClass: Class[RoleType] = classOf[OrganizationRole]
    val model: ModelType = Organization.this

    def newMember(userId: ObjectReference)(implicit ec: ExecutionContext): OrganizationMember = new OrganizationMember(this.model, userId)

    def clearRoles(user: User): Future[Int] = this.roleAccess.removeAll({ s => (s.userId === user.id.value) && (s.organizationId === id.value) })

    /**
      * Returns the highest level of [[ore.permission.role.Trust]] this user has.
      *
      * @param user User to get trust of
      * @return Trust of user
      */
    override def getTrust(user: User)(implicit ex: ExecutionContext): Future[Trust] =
      Organization.getTrust(user.id.value, id.value)
  }

  /**
    * Returns the [[User]] that owns this Organization.
    *
    * @return User that owns organization
    */
  override def owner(implicit service: ModelService): OrganizationMember =
    new OrganizationMember(this, this.ownerId)

  override def transferOwner(member: OrganizationMember)(implicit ec: ExecutionContext, service: ModelService): Future[Organization] = {
    // Down-grade current owner to "Admin"
    for {
      (owner, memberUser) <- this.owner.user.zip(member.user)
      (roles, memberRoles) <- this.memberships.getRoles(owner).zip(this.memberships.getRoles(memberUser))
      setOwner <- service.update(copy(ownerId = memberUser.id.value))
      _ <- Future.sequence(
        roles
          .filter(_.roleType == RoleType.OrganizationOwner)
          .map(role => service.update(role.copy(roleType = RoleType.OrganizationAdmin)))
      )
      _ <- Future.sequence(memberRoles.map(role => service.update(role.copy(roleType = RoleType.OrganizationOwner))))
    } yield setOwner
  }

  /**
    * Returns this Organization as a [[User]].
    *
    * @return This Organization as a User
    */
  def toUser(implicit ec: ExecutionContext, users: UserBase, auth: SpongeAuthApi): OptionT[Future, User] = users.withName(this.username)

  override val name: String = this.username
  override def url: String = this.username
  override val userId: ObjectReference = this.ownerId
  override def organizationId: ObjectReference = this.id.value
  override def copyWith(id: ObjectId, theTime: ObjectTimestamp): Model = this.copy(createdAt = theTime)

}

object Organization {
  lazy val roleForTrustQuery = Compiled(queryRoleForTrust _)

  private def queryRoleForTrust(orgId: Rep[ObjectReference], userId: Rep[ObjectReference]) = {
    val memberTable = TableQuery[OrganizationMembersTable]
    val roleTable = TableQuery[OrganizationRoleTable]

    for {
      m <- memberTable if m.organizationId === orgId && m.userId === userId
      r <- roleTable if m.userId === r.userId && r.organizationId === orgId
    } yield r.roleType
  }

  /**
    * Returns the highest level of [[ore.permission.role.Trust]] this user has.
    *
    * @param user User to get trust of
    * @return Trust of user
    */
  def getTrust(userId: ObjectReference, orgId: ObjectReference)(implicit ex: ExecutionContext, service: ModelService): Future[Trust] = {
    service.DB.db.run(Organization.roleForTrustQuery(orgId, userId).result)
      .map(_.sortBy(_.trust).headOption.map(_.trust).getOrElse(Default))
  }
}
