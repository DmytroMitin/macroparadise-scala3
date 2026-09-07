package roleawareconsumer

import roleawareprobe.{IndependentRoleAwareMarker, LegacyClassMarker, LegacyTraitMarker, OmitMarker, RetainMarker}

@IndependentRoleAwareMarker object ObjectEdit

class ExistingClass
@IndependentRoleAwareMarker object ExistingClass

trait ExistingTrait
@IndependentRoleAwareMarker object ExistingTrait

@IndependentRoleAwareMarker object CreateClass

@IndependentRoleAwareMarker object CreateTrait

@LegacyClassMarker class LegacyClass
object LegacyClass { val old = 1 }
@LegacyTraitMarker trait LegacyTrait
object LegacyTrait { val old = 2 }

@OmitMarker class DroppedCompanion
object DroppedCompanion { val old = 1 }
@OmitMarker @RetainMarker class RetainedCompanion
object RetainedCompanion { val old = 2 }

object IndependentRoleAwareConsumer:
  def main(args: Array[String]): Unit =
    assert(new DroppedCompanion().omissionProof == "ok")
    assert(new RetainedCompanion().omissionProof == "ok" && RetainedCompanion.old == 2)
    assert(List(ObjectEdit.answer, ExistingClass.answer, ExistingTrait.answer, CreateClass.answer, CreateTrait.answer).forall(_ == 42))
    assert(List(ExistingClass.foo, ExistingTrait.foo, CreateClass.foo, CreateTrait.foo).forall(_ == "ok"))
    assert(List(new ExistingClass().answer, new ExistingTrait {}.answer, new CreateClass().answer, new CreateTrait {}.answer).forall(_ == 42))
    assert(new LegacyClass().a == "A" && new LegacyTrait {}.a == "A")
    assert(new LegacyClass().foo == "primary" && new LegacyTrait {}.foo == "primary")
    assert(new LegacyClass().answer == 42 && new LegacyTrait {}.answer == 42)
    assert(LegacyClass.foo == "companion" && LegacyTrait.foo == "companion")
    assert(LegacyClass.answer == 42 && LegacyTrait.answer == 42)
    assert(LegacyClass.old == 1 && LegacyTrait.old == 2)
    println(ObjectEdit.foo)
    println(new ExistingClass().foo)
    println(new ExistingTrait {}.foo)
    println(new CreateClass().foo)
    println(new CreateTrait {}.foo)
