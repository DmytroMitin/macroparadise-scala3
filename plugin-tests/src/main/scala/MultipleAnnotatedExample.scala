package multi

import paradise3.gen

@gen
class User(val name: String)

object User:
  def existing: Int = 42

@gen
class Order(val name: String)

object MultipleAnnotatedExample:
  val existingValue = User.existing
  def useUser(u: User): String = u.generatedHello
  def createUser(): User = User.generatedFactory("A")
  def createUserMeta(): UserMeta = new UserMeta

  def useOrder(o: Order): String = o.generatedHello
  def createOrder(): Order = Order.generatedFactory("B")
  def createOrderMeta(): OrderMeta = new OrderMeta

  val userHello = createUser().generatedHello
  val orderHello = createOrder().generatedHello
