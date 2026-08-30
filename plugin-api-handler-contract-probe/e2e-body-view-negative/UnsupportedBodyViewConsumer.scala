package contractprobeconsumernegative

import contractprobebody.IndependentBodyViewMarker

@IndependentBodyViewMarker
trait UnsupportedBodyView[A]:
  def show(a: A): List[String]
