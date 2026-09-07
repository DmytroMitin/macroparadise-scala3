package roleawareconsumer

import roleawareprobe.IndependentRoleAwareMarker

@IndependentRoleAwareMarker object ObjectEdit

class ExistingClass
@IndependentRoleAwareMarker object ExistingClass

trait ExistingTrait
@IndependentRoleAwareMarker object ExistingTrait

@IndependentRoleAwareMarker object CreateClass

@IndependentRoleAwareMarker object CreateTrait

object IndependentRoleAwareConsumer:
  def main(args: Array[String]): Unit =
    println(ObjectEdit.foo)
    println(new ExistingClass().foo)
    println(new ExistingTrait {}.foo)
    println(new CreateClass().foo)
    println(new CreateTrait {}.foo)
