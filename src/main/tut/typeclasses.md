# Type classes
Polymorphism is the idea that a single entity (function, object, interface) can change behavior depending on various
implementations of an (potentially different) entity. In object-oriented languages the most common type of polymorphism
is *subtype* polymorphism, where an abstract class or interface takes on many forms through inheritance.  Contrast this
with *ad hoc* polymorphism, where a function varies with its inputs depending on the type.

## Example: collapsing a list
The following code snippets show code that sums a list of integers, concatenates a list of strings, and unions a list
of sets.

```tut:book:silent
def sumInts(list: List[Int]): Int = list.foldRight(0)(_ + _)

def concatStrings(list: List[String]): String = list.foldRight("")(_ ++ _)

def unionSets[A](list: List[Set[A]]): Set[A] = list.foldRight(Set.empty[A])(_ union _)
```

All of these follow the same pattern: an initial value (0, empty string, empty set) and a combining function
(`+`, `++`, `union`). We'd like to abstract over this so we can write the function once instead of once for every type
so we pull out the necessary pieces into an interface.

```tut:book:silent
trait Monoid[A] {
  def empty: A
  def combine(x: A, y: A): A
}
```

The name `Monoid` is taken from abstract algebra which specifies precisely this kind of structure.

We can now write the functions above against this interface.

```tut:book:silent
def combineAll[A](list: List[A], A: Monoid[A]): A = list.foldRight(A.empty)(A.combine)
```

## Type classes vs. subtyping
The definition above takes an actual monoid argument instead of doing the usual object-oriented practice of using
subtype constraints.

```tut:book:silent
// Subtyping
def combineAll[A <: Monoid[A]](list: List[A]): A = ???
```

This has a subtle difference with the earlier explicit example. In order to seed the `foldRight` with the empty value,
we need to get a hold of it given only the type `A`. Taking `Monoid[A]` as an argument gives us this by calling the
appropriate `empty` method on it. With the subtype example, the `empty` method would be on a **value** of type
`Monoid[A]` itself, which we are only getting from the `list` argument. If `list` is empty, we have no values to work
with and therefore can't get the empty value. Not to mention the oddity of getting a constant value from a non-static
object.

---

For another motivating difference, consider the simple pair type.

```tut:book:silent
final case class Pair[A, B](first: A, second: B)
```

Defining a `Monoid[Pair[A, B]]` depends on the ability to define a `Monoid[A]` and `Monoid[B]`, where the definition
is point-wise. With subtyping such a constraint would be encoded as something like

```tut:book:silent
final case class Pair[A <: Monoid[A], B <: Monoid[B]](first: A, second: B) extends Monoid[Pair[A, B]] {
  def empty: Pair[A, B] = ???

  def combine(x: Pair[A, B], y: Pair[A, B]): Pair[A, B] = ???
}
```

Not only is the type signature of `Pair` now messy but it also forces all instances of `Pair` to have a `Monoid`
instance, whereas `Pair` should be able to carry any types it wants and if the types happens to have a
`Monoid` instance then so would it. We could try bubbling down the constraint into the methods themselves.

```tut:book:fail
final case class Pair[A, B](first: A, second: B) extends Monoid[Pair[A, B]] {
  def empty(implicit eva: A <:< Monoid[A], evb: B <:< Monoid[B]): Pair[A, B] = ???

  def combine(x: Pair[A, B], y: Pair[A, B])(implicit eva: A <:< Monoid[A], evb: B <:< Monoid[B]): Pair[A, B] = ???
}
```

But now these don't conform to the interface of `Monoid` due to the implicit constraints.

### Implicit derivation

Note that a `Monoid[Pair[A, B]]` is derivable given `Monoid[A]` and `Monoid[B]`:

```tut:book:silent
final case class Pair[A, B](first: A, second: B)

def deriveMonoidPair[A, B](A: Monoid[A], B: Monoid[B]): Monoid[Pair[A, B]] =
  new Monoid[Pair[A, B]] {
    def empty: Pair[A, B] = Pair(A.empty, B.empty)

    def combine(x: Pair[A, B], y: Pair[A, B]): Pair[A, B] =
      Pair(A.combine(x.first, y.first), B.combine(x.second, y.second))
  }
```

One of the most powerful features of type classes is the ability to do this kind of derivation automatically.
We can do this through Scala's implicit mechanism.

```tut:book:silent
object Demo { // needed for tut, irrelevant to demonstration
  final case class Pair[A, B](first: A, second: B)

  object Pair {
    implicit def tuple2Instance[A, B](implicit A: Monoid[A], B: Monoid[B]): Monoid[Pair[A, B]] = ??? /* as before */
  }
}
```

We also change any functions that have a `Monoid` constraint on the type parameter to take the argument implicitly.

```tut:book:silent
def combineAll[A](list: List[A])(implicit A: Monoid[A]): A = list.foldRight(A.empty)(A.combine)
```

Now we can also `combineAll` a list of `Pair`s so long as `Pair`'s type parameters themselves have `Monoid`
instances.

## A note on syntax
In many cases, including the `combineAll` function above, the implicit arguments are written with syntactic sugar.

```tut:book:silent
def combineAll[A : Monoid](list: List[A]): A = ???
```

While nicer to read as a user, it comes at a cost for the implementer.

```tut:book:silent
// Defined in the standard library, shown for illustration purposes
// Implicitly looks in implicit scope for a value of type `A` and just hands it back
def implicitly[A](implicit ev: A): A = ev

def combineAll[A : Monoid](list: List[A]): A =
  list.foldRight(implicitly[Monoid[A]].empty)(implicitly[Monoid[A]].combine)
```

For this reason, many libraries that provide type classes provide a utility method on the companion object of the type
class, usually under the name `apply`, that skirts the need to call `implicitly` everywhere.

```tut:book:silent
object Monoid {
  def apply[A : Monoid]: Monoid[A] = implicitly[Monoid[A]]
}

def combineAll[A : Monoid](list: List[A]): A =
  list.foldRight(Monoid[A].empty)(Monoid[A].combine)
```

In fact, the [simulacrum][simulacrum] project will auto-generate such an `apply` method.

## Further reading
* [Returning the "Current" Type in Scala][fbounds]

[fbounds]: http://tpolecat.github.io/2015/04/29/f-bounds.html "Returning the "Current" Type in Scala"
[simulacrum]: https://github.com/mpilquist/simulacrum "First class syntax support for type classes in Scala"
