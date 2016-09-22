# Type classes + subtyping = :(

## Problem

The common encoding of type classes in Scala relies on subtyping. This singular
fact gives us a certain cleanliness in the code, but at what cost?

Consider the following hierarchy of type classes. A similar hierarchy can be
found in both [Cats][cats] and [Scalaz 7][scalaz7].

```scala
trait Functor[F[_]]

trait Applicative[F[_]] extends Functor[F]

trait Monad[F[_]] extends Applicative[F]

trait Traverse[F[_]] extends Functor[F]
```

For purposes of demonstration I will be using Cats for the rest of this post,
but the same arguments apply to Scalaz 7.

```scala
import cats._
import cats.implicits._
```

We will also assume that there is syntax accompanying this hierarchy, allowing
us to call methods like `map`, `flatMap`, and `traverse` directly on some
`F[A]`, provided `F` has the appropriate type class instances (`Functor`,
`Monad`, and `Traverse`, respectively). One important consequence is we can use
for comprehensions in methods parameterized over some `Monad`.

```scala
def foo[F[_]: Monad]: F[Int] = for {
  a <- Monad[F].pure(10)
  b <- Monad[F].pure(20)
} yield a + b
```

Notice that due to how for comprehensions [desugar][forcomp], there is also
a call to `map` in there. Since our type class hierarchy is encoded via
subtyping Scala knows a `Monad[F]` implies a `Functor[F]`, so all is well.
Or is it?

Consider a case where we want to abstract over a data type that has
both `Monad` and `Traverse`.

```scala
// Ignore the fact we're not even using `Traverse` - we can't even call `map`!
def foo[F[_]: Monad: Traverse]: F[Int] = Monad[F].pure(10).map(identity)
// <console>:19: error: value map is not a member of type parameter F[Int]
//        def foo[F[_]: Monad: Traverse]: F[Int] = Monad[F].pure(10).map(identity)
//                                                                   ^
// <console>:19: error: missing argument list for method identity in object Predef
// Unapplied methods are only converted to functions when a function type is expected.
// You can make this conversion explicit by writing `identity _` or `identity(_)` instead of `identity`.
//        def foo[F[_]: Monad: Traverse]: F[Int] = Monad[F].pure(10).map(identity)
//                                                                       ^
```

We're already in trouble. In order to call `map` we need `F` to have a
`Functor` instance, which it does via `Monad` as before.. but now also via
`Traverse`. It is for precisely this reason that this does not work. Because
our encoding of type classes uses subtyping, a `Monad[F]` **is a** `Functor[F]`.
Similarly, a `Traverse[F]` **is a** `Functor[F]`. When implicit resolution
attempts to find a `Functor[F]`, it can't decide between `Monad[F]`'s or
`Traverse[F]`'s and bails out. Even though the instances may be, and arguably
should be, the same, the compiler has no way of knowing that.

This problem generalizes to anytime the compiler decides an implicit is ambiguous,
such as method calls.

```scala
// The fact we don't actually use `Functor` here is irrelevant.
def bar[F[_]: Applicative: Functor]: F[Int] = Applicative[F].pure(10)
```

```scala
def callBar[F[_]: Monad: Traverse]: F[Int] = bar[F]
// <console>:19: error: ambiguous implicit values:
//  both value evidence$2 of type cats.Traverse[F]
//  and value evidence$1 of type cats.Monad[F]
//  match expected type cats.Functor[F]
//        def callBar[F[_]: Monad: Traverse]: F[Int] = bar[F]
//                                                        ^
```

What do we do? For `map` it is easy enough to arbitrarily pick one
of the instances and call `map` on that. For function calls you
can thread the implicit through explicitly.

```scala
def foo[F[_]: Monad: Traverse]: F[Int] = Monad[F].map(Monad[F].pure(10))(identity)

def callBar[F[_]: Monad: Traverse]: F[Int] = bar(Monad[F], Monad[F])
                                       // or bar(Monad[F], Traverse[F])
```

For `foo` it's not *too* terrible. For `bar` though we are
already starting to see it get unwieldy. While we could have passed in
`Monad[F]` or `Traverse[F]` for the second parameter which corresponds
to `bar`'s `Functor[F]` constraint, we can only pass in `Monad[F]` for
the first parameter to satisfy `Applicative[F]`. Because implicit resolution
can't disambiguate the `Functor[F]` by itself we've had to pass it in
explicitly, but by doing so we also have to pass in everything else explicitly!
We become the implicit resolver. And this is with just two constraints, what
if we had three, four, five?

And the trouble doesn't end there. We asked for a `Monad` so let's try using
a for comprehension.

```scala
def foo[F[_]: Monad: Traverse]: F[Int] = for {
  a <- Monad[F].pure(10)
  b <- Monad[F].pure(20)
} yield a + b
// <console>:21: error: value map is not a member of type parameter F[Int]
//          b <- Monad[F].pure(20)
//                            ^
```

This is also broken! Because of how [for comprehensions][forcomp] desugar, a
`map` call is inevitable which leads to the for comprehension breaking down.
This drastically reduces the ergonomics of doing anything monadic.

As with `map` we could call `flatMap` on `Monad` directly, but this quickly
becomes cumbersome.

```scala
def foo[F[_]: Monad: Traverse]: F[Int] = {
  val M = Monad[F]
  M.flatMap(M.pure(10)) { a =>
    M.map(M.pure(20)) { b =>
      a + b
    }
  }
}
```

The same problems arise if you ask for two or more type classes that share a
common subclass. Some examples of this:

* Two or more of Monad{Error, Plus, Reader, State, Writer} (ambiguous Monad)
  * This prevents ergonomic use of ["MTL-style"][mtl]
* MonadPlus + Monad (ambiguous Monad)
* Alternative + Traverse (ambiguous Functor)
* MonadRec + MonadPlus (ambiguous Monad)

This suggests for each type class there should only be **one** subclass of it.
That is quite limiting as is readily demonstrated by the extremely useful
`Applicative` and `Traverse` type classes. What do we do?

## Solution (?)

This more or less remains an open problem in Scala. There has been
an interesting alternative prototyped in [scato][scato], now making its way to
[Scalaz 8][scalaz8], that has received some positive feedback. The gist of the
encoding completely throws out the notion of subtyping, encoding the hierarchy
via members instead.

```scala
trait Functor[F[_]] {
  def map[A, B](fa: F[A])(f: A => B): F[B]
}

trait Applicative[F[_]] {
  def functor: Functor[F]

  def pure[A](a: A): F[A]
  def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C]
}

// Definitions elided for space
trait Monad[F[_]] { def applicative: Applicative[F] }

trait Traverse[F[_]] { def functor: Functor[F] }
```

Because there is no relation between the type classes, there is no
danger of implicit ambiguity. However, for that very reason, having a
`Monad[F]` no longer implies having a `Functor[F]`. Not currently
anyway. What we can do is use implicit conversions to re-encode the
hierarchy.

```scala
implicit def applicativeIsFunctor[F[_]: Applicative]: Functor[F] =
  implicitly[Applicative[F]].functor

implicit def traverseIsFunctor[F[_]: Traverse]: Functor[F] =
  implicitly[Traverse[F]].functor
```

But now we're back to square one.

```scala
// Syntax for Functor
implicit class FunctorOps[F[_], A](fa: F[A])(implicit F: Functor[F]) {
  def map[B](f: A => B): F[B] = F.map(fa)(f)
}
```

```scala
def foo[F[_]: Applicative: Traverse]: F[Int] =
  implicitly[Applicative[F]].pure(10).map(identity)
// <console>:19: error: value map is not a member of type parameter F[Int]
//          implicitly[Applicative[F]].pure(10).map(identity)
//                                              ^
// <console>:19: error: missing argument list for method identity in object Predef
// Unapplied methods are only converted to functions when a function type is expected.
// You can make this conversion explicit by writing `identity _` or `identity(_)` instead of `identity`.
//          implicitly[Applicative[F]].pure(10).map(identity)
//                                                  ^
```

Since both implicit conversions have same [priority][implicits], the compiler
doesn't know which one to pick. **However**, Scala has mechanisms for
[prioritizing implicits][implicits] which solves the problem.

```scala
object Prioritized { // needed for tut, irrelevant to demonstration
  trait Functor[F[_]] {
    def map[A, B](fa: F[A])(f: A => B): F[B]
  }

  // Prioritize implicit conversions
  trait FunctorLowPriority {
    implicit def applicativeIsFunctor[F[_]: Applicative]: Functor[F] =
      implicitly[Applicative[F]].functor
  }

  object Functor extends FunctorLowPriority {
    implicit def traverseIsFunctor[F[_]: Traverse]: Functor[F] =
      implicitly[Traverse[F]].functor
  }

  trait Applicative[F[_]] {
    def functor: Functor[F]

    def pure[A](a: A): F[A]
    def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C]
  }

  // Definition elided for space
  trait Traverse[F[_]] { def functor: Functor[F] }

  // Syntax for Functor
  implicit class FunctorOps[F[_], A](fa: F[A])(implicit F: Functor[F]) {
    def map[B](f: A => B): F[B] = F.map(fa)(f)
  }

  def foo[F[_]: Applicative: Traverse]: F[Int] = {
    implicitly[Applicative[F]] // we have Applicative
    implicitly[Traverse[F]]    // we have Traverse
    implicitly[Functor[F]]     // we also have Functor!

    // and we have syntax!
    implicitly[Applicative[F]].pure(10).map(identity)
  }
}
// defined object Prioritized
```

The trick is to use trait linearization which gives the effect of implicits
in subtypes having lower priority than those in supertypes. Here this means
that `applicativeIsFunctor` has lower priority than `traverseIsFunctor`, so
when both `Applicative` and `Traverse` instances are in scope and the compiler
is looking for a `Functor`, `traverseIsFunctor` wins.

Do we win? I'm not sure. This encoding is certainly more cumbersome than what
we started with, but solves the problem we ran into. For a more developed
version of this I direct you to work being done on [scato][scato] and
[Scalaz 8][scalaz8].

One downside of this encoding is that superclasses (e.g. `Functor`) are aware
of subclasses (e.g. `Applicative`, `Traverse`). From a modularity perspective,
this seems quite strange, but in Scala the only other tried alternative is
subtyping which has worse problems.

## Compromise?

One thing we can try is to make some compromise of the two. We can
continue to use subtyping for a blessed subset of the hierarchy, and use
members for any branching type class.

```scala
trait Functor[F[_]] {
  def map[A, B](fa: F[A])(f: A => B): F[B]
}

implicit class FunctorOps[F[_], A](fa: F[A])(implicit F: Functor[F]) {
  def map[B](f: A => B): F[B] = F.map(fa)(f)
}

trait Applicative[F[_]] extends Functor[F] {
  def pure[A](a: A): F[A]
  def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C]
}

trait Monad[F[_]] extends Applicative[F]

trait Traverse[F[_]] { def applicative: Applicative[F] }

def foo[F[_]: Applicative: Traverse]: F[Int] =
  implicitly[Applicative[F]].pure(10).map(identity)
```

This works, but is even messier than the alternatives. We have to
decide which type classes get to live in the subtype hierachy and which are
doomed (blessed?) to express the relationship with members. But maybe the
pros outweigh the cons. Pull requests with this change have been filed for
[Cats][fixCats] and [Scalaz 7.3][fixScalaz].

I'm not convinced that the story is over though. Maybe there's another encoding
encoding yet to be discovered. If you find it, please let me know :-)

For further reading, there are open tickets for both [Cats][issueCats] and
[Scalaz 7][issueScalaz] documenting the subtyping problem. A discussion around
the Scato encoding for Scalaz 8 can be found [here][scatoScalaz].

[cats]: https://github.com/typelevel/cats "Typelevel Cats"
[fixCats]: https://github.com/typelevel/cats/pull/1379 "MTL fix for Cats"
[fixScalaz]: https://github.com/scalaz/scalaz/pull/1262 "MTL fix for Scalaz"
[forcomp]: http://docs.scala-lang.org/tutorials/FAQ/yield.html "How does yield work?"
[implicits]: http://eed3si9n.com/revisiting-implicits-without-import-tax "revisiting implicits without import tax"
[issueCats]: https://github.com/typelevel/cats/issues/1210 "Better accommodate MTL style"
[issueScalaz]: https://github.com/scalaz/scalaz/issues/1110 "MTL-style doesn't seem to work in Scala"
[mtl]: https://hackage.haskell.org/package/mtl "mtl: Monad classes, using functional dependencies"
[scalaz7]: https://github.com/scalaz/scalaz/tree/series/7.3.x "Scalaz 7"
[scalaz8]: https://github.com/scalaz/scalaz/tree/series/8.0.x "Scalaz 8"
[scato]: https://github.com/aloiscochard/scato "Scato"
[scatoScalaz]: https://github.com/scalaz/scalaz/issues/1084 "[scalaz8] Subtyping-free encoding for typeclasses"
