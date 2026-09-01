package index4s.client

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*

/** At most `n` effectful tasks in flight at any time; results in input order
  * (cats-effect std Semaphore, no extra dependencies).
  */
object Bounded {

  def parTraverseBounded[A, B](
      n: Int
  )(as: List[A])(f: A => IO[B]): IO[List[B]] =
    Semaphore[IO](math.max(1, n)).flatMap { sem =>
      as.parTraverse(a => sem.permit.use(_ => f(a)))
    }
}
