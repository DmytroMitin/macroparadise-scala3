package contractprobeconsumernegative

import contractprobebody.IndependentBodyViewMarker

@IndependentBodyViewMarker
trait UnsupportedBodyView[A]:
  def empty: A
  def combine(a: List[A], a1: A): A
