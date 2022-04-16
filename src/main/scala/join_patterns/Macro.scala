package join_patterns

import java.util.concurrent.{LinkedTransferQueue => Queue}

import scala.quoted.{Expr, Type, Quotes}

case class JoinPattern[M, T](
  test: List[M] => Boolean,
  extract: List[M] => Any,
  guard: Any => Boolean,
  rhs: Any => T,
)

def extractClassNames(using quotes: Quotes)(ua: quotes.reflect.Unapply): (String, String) =
  import quotes.reflect.*

  ua.fun match
    case sel @ Select(Ident(_), "unapply") => sel.signature match
      case Some(sig) =>
        val extractor = ua.fun.etaExpand(ua.symbol)

        extractor.tpe match
          case AppliedType(TypeRef(ThisType(TypeRef(NoPrefix(), "scala")), "Function1"),
            trepr :: _) => trepr.dealias.simplified match
              case tp: TypeRef => return (sig.resultSig, tp.classSymbol.get.fullName)
              case default => errorTypeRepr("Unsupported TypeRepr", default)
          case default => errorTypeRepr("Unsupported extractor type", extractor.tpe)
      case None => error("Unsupported Select", sel)
    case default => error("Unsupported unapply function", ua.fun)

  ("", "")

def generateGuard[T](using quotes: Quotes, tt: Type[T])
                 (guard: Option[quotes.reflect.Term],
                  variable: Option[(String, quotes.reflect.TypeIdent)] = None):
                    quotes.reflect.Block =
  import quotes.reflect.*

  val _transform = new TreeMap {
    override def transformTerm(term: Term)(owner: Symbol): Term =
      super.transformTerm(term)(owner)
  }

  var _rhsFn = (sym: Symbol, params: List[Tree]) =>
    _transform.transformTerm(('{true}).asExprOf[Boolean].asTerm.changeOwner(sym))(sym)

  guard match
    case Some(apply: Apply) =>
      variable match
        case Some(name, _type) =>
          _rhsFn = (sym: Symbol, params: List[Tree]) =>
            val p0 = params.head.asInstanceOf[Ident]

            val transform = new TreeMap {
              override def transformTerm(term: Term)(owner: Symbol): Term =
                val nt: Term = term match
                  case Ident(n) if (n == name) => p0
                  case x => super.transformTerm(x)(owner)

                nt
              }

            transform.transformTerm(apply.changeOwner(sym))(sym)
        case None => _rhsFn = (sym: Symbol, params: List[Tree]) =>
          _transform.transformTerm(apply.changeOwner(sym))(sym)
    case None => ()
    case Some(default) => error("Unsupported guard", default)

  Lambda(
    owner = Symbol.spliceOwner,
    tpe = variable match
      case Some(name, _type) =>
        MethodType(List(name))(_ => List(TypeRepr.of[T]), _ => TypeRepr.of[Boolean])
      case None => MethodType(List())(_ => List(), _ => TypeRepr.of[Boolean]),
    rhsFn = _rhsFn
  )

def makeNewRhs[T](using quotes: Quotes, tt: Type[T])
                 (name: String, _type: quotes.reflect.TypeRepr,
                  rhs: quotes.reflect.Term): quotes.reflect.Block =
  import quotes.reflect.*

  Lambda(
    owner = Symbol.spliceOwner,
    tpe = MethodType(List(name))(_ => List(_type), _ => TypeRepr.of[T]),
    rhsFn = (sym: Symbol, params: List[Tree]) =>
      val p0 = params.head.asInstanceOf[Ident]

      val transform = new TreeMap {
        override def transformTerm(term: Term)(owner: Symbol): Term =
          val nt: Term = term match
            case Ident(n) if (n == name) => p0
            case x => super.transformTerm(x)(owner)

          nt
      }

      transform.transformTerm(rhs.changeOwner(sym))(sym)
  )

def generate[M, T](using quotes: Quotes, tm: Type[M], tt: Type[T])(_case: quotes.reflect.CaseDef):
  Expr[JoinPattern[M, T]] =
  import quotes.reflect.*

  _case match
    case CaseDef(pattern, guard, rhs) =>

      pattern match
        case TypedOrTest(tree, tpd) =>
          report.info(_case.show(using Printer.TreeStructure), _case.pos)
          tree match
            case ua @ Unapply(sel @ Select(Ident(_), "unapply"), Nil, patterns) =>
              val classNames = extractClassNames(ua)

              patterns match
                case Nil =>
                  if (classNames._1 != "scala.Boolean") then
                    errorSig("Unsupported Signature", sel.signature.get)

                  val _guard = generateGuard[T](guard)

                  return '{JoinPattern(
                    (m: List[M]) => m.find(_.getClass.getName == ${Expr(classNames._1)}).isDefined,
                    (m: List[M]) => None,
                    ${ _guard.asExprOf[Any => Boolean] },
                    (p: Any) => ${rhs.asExprOf[T]}
                  )}
                case List(bind @ Bind(varName, typed @ Typed(_, varType @ TypeIdent(_type)))) =>
                  //report.info(bind.show(using Printer.TreeStructure), bind.pos)

                  val _guard = generateGuard[T](guard, Some(varName, varType))
                  val newRhs = makeNewRhs[T](varName, varType.tpe, rhs)

                  varType.tpe.asType match
                    case '[t] =>
                      return '{JoinPattern(
                        (m: List[M]) =>
                          m.find(_.getClass.getName == ${Expr(classNames._2)}).isDefined,
                        // use ua.fun; m(0).unapply => Some(Int)
                        (m: List[M]) =>
                          8
                          //.unapply(m(0).asInstanceOf[t])
                          /*
                          ua.fun match
                            case sel: Select =>

                              Block(Nil, Apply(sel, List(TypeApply(Select(
                                Apply(
                                  Select(Ident("m"), "apply"), List(Literal(IntConstant(0)))),
                                  "asInstanceOf"
                              ), List(varType)))))
                          */
                          ,
                        (m: Any) => ${ _guard.asExprOf[t => Boolean] }(m.asInstanceOf[t]),
                        (m: Any) => ${ newRhs.asExprOf[t => T] }(m.asInstanceOf[t])
                      )}
                    case default => error("Unsupported type", default)
                /*
                case List(_) =>
                  println("List(_)")
                  val classes = patterns.map {
                    case TypedOrTest(ua1 @ Unapply(sel @ Select(Ident(x), "unapply"), Nil, Nil), _) =>
                      extractClassName(ua1)
                    case w: Wildcard => w.name
                    case default =>
                      errorTree("Unsupported pattern", default)
                      ""
                  }
                  val length = Expr(classes.length)

                  return '{(
                    (m: List[M]) =>
                      m.length >= ${length} && ${Expr(classes)}.forall(
                        c_c => m.find(_.getClass.getName == c_c.getClass.getName).isDefined || c_c == "_"
                      ),
                    $_guard,
                    () => ${rhs.asExprOf[T]}
                  )}
                */
                case default => error("Unsupported patterns", default)
            // (A, B, ...)
            /*
            case Unapply(TypeApply(Select(Ident(_Tuple), "unapply"), args), Nil, classes) =>
              // args : List(Inferred(), Inferred())
              classes.map {
                case TypedOrTest(Unapply(Select(Ident(typename), "unapply"), Nil, Nil), Inferred()) => ()
                case default => ()
              }

              val typenames = args.map {
                t => t.tpe.dealias.simplified.classSymbol.get.fullName
              }

              return '{(
                (m: List[M]) => true,
                $_guard,
                () => ${rhs.asExprOf[T]}
              )}
            */
            case default => errorTree("Unsupported test", default)
        case Wildcard() =>
          val _guard = generateGuard[T](guard)

          return '{JoinPattern(
            (m: List[M]) => true,
            (m: List[M]) => Nil,
            ${ _guard.asExprOf[Any => Boolean] },
            (p: Any) => ${rhs.asExprOf[T]}
          )}
        case default => errorTree("Unsupported case pattern", default)

  null

// Translate a series of match clause into a list of pairs, each one
// containing a test function to check whether a message has a certain type,
// and a closure (returning T) to execute if the test returns true
def getCases[M, T](expr: Expr[M => T])(using quotes: Quotes, tm: Type[M], tt: Type[T]):
  List[Expr[JoinPattern[M, T]]] =
  import quotes.reflect.*

  expr.asTerm match
    case Inlined(_, _, Block(_, Block(stmts, _))) =>
      stmts(0) match
        case DefDef(_, _, _, Some(Block(_, Match(_, cases)))) =>
          cases.map { generate[M, T](_) }
        case default =>
          errorTree("Unsupported code", default)
          List()
    case default =>
      errorTree("Unsupported expression", default)
      List()

// Generate the code returned by the receive macro
def receiveCodegen[M, T](expr: Expr[M => T])
                        (using tm: Type[M], tt: Type[T], quotes: Quotes): Expr[Queue[M] => T] =
  import quotes.reflect.*

  val genCode = '{
    (q: Queue[M]) =>
      val matchTable = ${ Expr.ofList(getCases(expr)) }
      var matched: Option[T] = None

      //strategy function
      while (matched.isEmpty)
        val msg = q.take()
        val messages = msg :: Nil

        matchTable.find(_._1(messages)) match
          case Some(m) =>
            val inners = m._2(messages)
            if m._3(inners) then matched = Some(m._4(inners))
          case _ => ()

      matched.get
  }

  report.info(f"Generated code: ${genCode.asTerm.show(using Printer.TreeAnsiCode)}")
  genCode

/** Entry point of the `receive` macro.
 *
 *  @param f the block to use as source of the pattern-matching code.
 *  @return a comptime function performing pattern-matching on a message queue at runtime.
 */
inline def receive[M, T](inline f: M => T): Queue[M] => T = ${ receiveCodegen('f) }

/*
 primitive / composite event

val p = (C c -> D d)
val p1[Ty] = Ty c

receive(e) {
	d: D => println(""),
	D(d0, d1, d2) => println(""),
	(A a, B b, C c) => println(""), // local copies, used data is marked for `this` but not consumed (so others can use it)
	(A a & B b & C c) => println(""), // all present at the same time in LinkedTransferQueue[M]
	(B b -> D d) => println(""), // sequential
	(A a -> (D d, A a)) => println(""),
	A a | B b => println(""), // disjunction
	p => println(""), // pattern variable
	p1 => println(""), // parameterized pattern variable
	~Debug dbg => println(""), // not
	_ => println(""), // wildcard type
	E _ => println(""), // wildcard var
}
*/