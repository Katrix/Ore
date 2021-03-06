package ore.permission.role

import db.ObjectReference
import ore.permission.scope.{GlobalScope, Scope}

/**
  * Represents a [[Role]] within the [[GlobalScope]].
  *
  * @param userId   ID of [[models.user.User]] this role belongs to
  * @param roleType Type of role
  */
case class GlobalRole(override val userId: ObjectReference, override val roleType: RoleType) extends Role {
  override val scope: Scope = GlobalScope
}
