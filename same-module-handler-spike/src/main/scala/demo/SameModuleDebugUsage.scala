package demo

@sameModuleDebug
class SameModuleUser

val sameModuleResult: String = new SameModuleUser().sameModuleDebugName

object SameModuleDebugCheck:
  def main(args: Array[String]): Unit =
    assert(sameModuleResult == "SameModuleUser")
    assert(secondSameModuleResult == "SecondSameModuleUser")
    println(s"sameModuleResult=$sameModuleResult")
    println(s"secondSameModuleResult=$secondSameModuleResult")
