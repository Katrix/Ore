package ore.permission.role

import ore.permission.scope.ScopeSubject
import ore.user.UserOwned

/**
  * Represents a "role" that is posessed by a [[models.user.User]].
  */
trait Role extends ScopeSubject with UserOwned {
  /** Type of role */
  def roleType: RoleType
}
