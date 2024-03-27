package actor

import join_patterns.Matcher

import java.util.concurrent.Executors
import java.util.concurrent.LinkedTransferQueue as Mailbox
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.*

implicit val ec: ExecutionContext =
  ExecutionContext.fromExecutorService(
    Executors.newVirtualThreadPerTaskExecutor()
  )

sealed trait Result[T]
case class Stop[T](value: T) extends Result[T]
case class Next[T]()         extends Result[T]

extension [T](result: Result[T])
  def map[U](f: T => U): Result[U] =
    result match
      case Stop(value) => Stop(f(value))
      case Next()      => Next()

  def flatMap[U](f: T => Result[U]): Result[U] =
    result match
      case Stop(value) => f(value)
      case Next()      => Next()

  def getValue: Option[Result[T]] =
    Some(result)

  def isEmpty: Boolean =
    result match
      case Stop(_) => false
      case Next()  => true

  def nonEmpty: Boolean =
    result match
      case Stop(_) => true
      case Next()  => false

class Actor[M, T](private val matcher: Matcher[M, Result[T]]):
  private val mailbox: Mailbox[M] = Mailbox[M]
  val self                        = ActorRef(mailbox)

  def start(): (Future[T], ActorRef[M]) =
    val promise = Promise[T]

    ec.execute(() => run(promise))

    (promise.future, self)

  @tailrec
  private def run(promise: Promise[T]): Unit =
    matcher(mailbox)(self) match
      case Next()      => run(promise)
      case Stop(value) => promise.success(value)