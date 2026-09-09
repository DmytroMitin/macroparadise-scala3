package macroparadise

private[macroparadise] object ExpansionBudget:
  val Default: Int = 256
  private val Prefix = "expansionBudget="

  def parse(options: List[String]): Either[String, Int] =
    val values = options.collect:
      case option if option.startsWith(Prefix) => option.stripPrefix(Prefix)
    values match
      case Nil => Right(Default)
      case _ :: _ :: _ => Left("plugin option `expansionBudget=` may be supplied at most once")
      case raw :: Nil if raw.isEmpty => Left("plugin option `expansionBudget=` requires a positive decimal integer")
      case raw :: Nil =>
        raw.toIntOption.filter(_ > 0).toRight(
          s"plugin option `expansionBudget=$raw` requires a positive decimal integer within Int range"
        )
