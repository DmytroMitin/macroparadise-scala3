@qualifiedone.audit
class QualifiedOneAuditUser

@qualifiedtwo.audit
class QualifiedTwoAuditUser

@qualifiedone.audit
@qualifiedunknown.audit
class QualifiedHandledWithUnknownUser

object QualifiedAnnotationIdentityExample:
  val one: String = new QualifiedOneAuditUser().qualifiedOneAuditName
  val two: String = new QualifiedTwoAuditUser().qualifiedTwoAuditName
  val handledWithUnknown: String =
    new QualifiedHandledWithUnknownUser().qualifiedOneAuditName
