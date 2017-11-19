package org.bykn.edgemar

import com.stripe.dagon.Memoize
import cats.data.{NonEmptyList, Validated, ValidatedNel, ReaderT}
import cats.Foldable
import cats.implicits._
import fastparse.all._
import org.typelevel.paiges.{Doc, Document}
import Parser.{lowerIdent, upperIdent, spaces, maybeSpace, Combinators}

case class PackageName(parts: NonEmptyList[String]) {
  def asString: String = parts.toList.mkString("/")
}

object PackageName {
  implicit val document: Document[PackageName] =
    Document.instance[PackageName] { pn => Doc.text(pn.asString) }

  implicit val parser: P[PackageName] =
    P(upperIdent ~ ("/" ~ upperIdent).rep()).map { case (head, tail) =>
      PackageName(NonEmptyList(head, tail.toList))
    }

  def parse(s: String): Option[PackageName] =
    parser.parse(s) match {
      case Parsed.Success(pn, _) => Some(pn)
      case _ => None
    }
}

sealed abstract class ImportedName[T] {
  def originalName: String
  def localName: String
  def tag: T
  def setTag(t: T): ImportedName[T]
}
object ImportedName {
  case class OriginalName[T](originalName: String, tag: T) extends ImportedName[T] {
    def localName = originalName
    def setTag(t: T): ImportedName[T] = OriginalName(originalName, t)
  }
  case class Renamed[T](originalName: String, localName: String, tag: T) extends ImportedName[T] {
    def setTag(t: T): ImportedName[T] = Renamed(originalName, localName, t)
  }

  implicit val document: Document[ImportedName[Unit]] =
    Document.instance[ImportedName[Unit]] {
      case ImportedName.OriginalName(nm, _) => Doc.text(nm)
      case ImportedName.Renamed(from, to, _) => Doc.text(from) + Doc.text(" as ") + Doc.text(to)
    }

  val parser: P[ImportedName[Unit]] = {
    def basedOn(of: P[String]): P[ImportedName[Unit]] =
      P(of ~ (spaces ~ "as" ~ spaces ~ of).?).map {
        case (from, Some(to)) => ImportedName.Renamed(from, to, ())
        case (orig, None) => ImportedName.OriginalName(orig, ())
      }

    basedOn(lowerIdent) | basedOn(upperIdent)
  }
}

case class Import[A, B](pack: A, items: NonEmptyList[ImportedName[B]])

object Import {
  implicit val document: Document[Import[PackageName, Unit]] =
    Document.instance[Import[PackageName, Unit]] { case Import(pname, items) =>
      Doc.text("import ") + Document[PackageName].document(pname) + Doc.space +
        // TODO: use paiges to pack this in nicely using .group or something
        Doc.char('[') + Doc.intercalate(Doc.text(", "), items.toList.map(Document[ImportedName[Unit]].document _)) + Doc.char(']')
    }

  val parser: P[Import[PackageName, Unit]] = {
    P("import" ~ spaces ~/ PackageName.parser ~ maybeSpace ~
      ImportedName.parser.nonEmptyListSyntax).map { case (pname, imported) =>
        Import(pname, imported)
      }
  }
}

sealed abstract class ExportedName[T] {
  def name: String
  def tag: T
}
object ExportedName {
  case class Binding[T](name: String, tag: T) extends ExportedName[T]
  case class TypeName[T](name: String, tag: T) extends ExportedName[T]
  case class Constructor[T](name: String, tag: T) extends ExportedName[T]

  private[this] val consDoc = Doc.text("()")

  implicit val document: Document[ExportedName[Unit]] =
    Document.instance[ExportedName[Unit]] {
      case Binding(n, _) => Doc.text(n)
      case TypeName(n, _) => Doc.text(n)
      case Constructor(n, _) => Doc.text(n) + consDoc
    }

  val parser: P[ExportedName[Unit]] =
    lowerIdent.map(Binding(_, ())) |
      P(upperIdent ~ "()".!.?).map {
        case (n, None) => TypeName(n, ())
        case (n, Some(_)) => Constructor(n, ())
      }

  /**
   * The only subtlety here is that for Constructor, we export everything in the type
   */
  private[edgemar] def buildExportMap(exs: List[ExportedName[Referant]]): Map[String, ExportedName[Referant]] =
    exs.flatMap {
      case b@Binding(n, _) => (n, b) :: Nil
      case t@TypeName(n, _) => (n, t) :: Nil
      case Constructor(_, Referant.Constructor(_, dt)) =>
        // we actually export all constructors in this case
        val tname = dt.name.asString
        (tname, TypeName(tname, Referant.DefinedT(dt): Referant)) :: dt.constructors.map { case (c@ConstructorName(n), _) =>
          (n, Constructor(n, Referant.Constructor(c, dt): Referant))
        }
      case invalid@Constructor(_, _) => sys.error(s"this should be unreachable: $invalid")
    }.toMap[String, ExportedName[Referant]]
}

case class Package[A, B, C, D](name: PackageName, imports: List[Import[A, B]], exports: List[ExportedName[C]], program: D)

object Package {
  type FixPackage[B, C, D] = Fix[Lambda[a => Package[a, B, C, D]]]
  type PackageF[A, B] = Package[FixPackage[A, A, B], A, A, B]
  type Parsed = Package[PackageName, Unit, Unit, Program[Declaration, Statement]]

  implicit val document: Document[Package[PackageName, Unit, Unit, Program[Declaration, Statement]]] =
    Document.instance[Package.Parsed] { case Package(name, imports, exports, program) =>
      val p = Doc.text("package ") + Document[PackageName].document(name) + Doc.line
      val i = imports match {
        case Nil => Doc.empty
        case nonEmptyImports =>
          Doc.intercalate(Doc.line, nonEmptyImports.map(Document[Import[PackageName, Unit]].document _)) + Doc.line
      }
      val e = exports match {
        case Nil => Doc.empty
        case nonEmptyExports =>
          Doc.text("export ") + Doc.text("[ ") +
          Doc.intercalate(Doc.text(", "), nonEmptyExports.map(Document[ExportedName[Unit]].document _)) + Doc.text(" ]") + Doc.line
      }
      val b = Document[Statement].document(program.from)
      // add an extra line between each group
      Doc.intercalate(Doc.line, List(p, i, e, b))
    }

  val parser: P[Package[PackageName, Unit, Unit, Program[Declaration, Statement]]] = {
    // TODO: support comments before the Statement
    val pname = Padding.parser(P("package" ~ spaces ~ PackageName.parser)).map(_.padded)
    val im = Padding.parser(Import.parser).map(_.padded).rep().map(_.toList)
    val ex = Padding.parser(P("export" ~ maybeSpace ~ ExportedName.parser.nonEmptyListSyntax)).map(_.padded)
    val body = Padding.parser(Statement.parser).map(_.padded)
    (pname ~ im ~ Parser.nonEmptyListToList(ex) ~ body).map { case (p, i, e, b) =>
      Package(p, i, e, b.toProgram)
    }
  }
}

sealed abstract class Referant
object Referant {
  case class Value(scheme: Scheme) extends Referant
  case class DefinedT(dtype: DefinedType) extends Referant
  case class Constructor(name: ConstructorName, dtype: DefinedType) extends Referant
}

case class PackageMap[A, B, C, D](toMap: Map[PackageName, Package[A, B, C, D]])

object PackageMap {
  def build(ps: Iterable[Package.Parsed]): ValidatedNel[PackageError.DuplicatePackages, PackageMap[PackageName, Unit, Unit, Program[Declaration, Statement]]] = {

    def toPackage(it: Iterable[Package.Parsed]): ValidatedNel[PackageError.DuplicatePackages, Package.Parsed] =
      it.toList match {
        case Nil => sys.error("unreachable, groupBy never produces an empty list")
        case h :: Nil => Validated.valid(h)
        case h :: h1 :: tail => Validated.invalidNel(PackageError.DuplicatePackages(h, NonEmptyList(h1, tail)))
      }

    ps.groupBy(_.name).traverse(toPackage _).map(PackageMap(_))
  }

  import Package.{ FixPackage, PackageF }

  type MapF3[A, B, C] = PackageMap[FixPackage[A, B, C], A, B, C]
  type MapF2[A, B] = MapF3[A, A, B]
  type Parsed = MapF2[Unit, Program[Declaration, Statement]]
  type Inferred = MapF2[Referant, Program[(Declaration, Scheme), Statement]]

  def resolvePackages[A, B, C](map: PackageMap[PackageName, A, B, C]): ValidatedNel[PackageError, MapF3[A, B, C]] = {
    def getPackage(i: Import[PackageName, A], from: Package[PackageName, A, B, C]): ValidatedNel[PackageError, Import[Package[PackageName, A, B, C], A]] =
      map.toMap.get(i.pack) match {
        case None => Validated.invalidNel(PackageError.UnknownImportPackage(i.pack, from))
        case Some(pack) => Validated.valid(Import(pack, i.items))
      }

    def step(p: Package[PackageName, A, B, C]): ReaderT[Either[NonEmptyList[PackageError], ?], List[PackageName], Package[FixPackage[A, B, C], A, B, C]] = {
      val edeps = ReaderT.ask[Either[NonEmptyList[PackageError], ?], List[PackageName]]
        .flatMapF {
          case nonE@(h :: tail) if nonE.contains(p.name) =>
            Left(NonEmptyList.of(PackageError.CircularDependency(p, NonEmptyList(h, tail))))
          case _ =>
            val deps = p.imports.traverse(getPackage(_, p)) // the packages p depends on
            deps.toEither
        }

      edeps
        .flatMap { deps =>
          deps.traverse { i =>
            step(i.pack)
              .local[List[PackageName]](p.name :: _) // add this package into the path of all the deps
              .map { p => Import(Fix[Lambda[a => Package[a, A, B, C]]](p), i.items) }
          }
          .map { imports =>
            Package(p.name, imports, p.exports, p.program)
          }
        }
    }

    type M = Map[PackageName, Package[FixPackage[A, B, C], A, B, C]]
    val r: ReaderT[Either[NonEmptyList[PackageError], ?], List[PackageName], M] =
      map.toMap.traverse(step)
    val m: Either[NonEmptyList[PackageError], M] = r.run(Nil)
    m.map(PackageMap(_)).toValidated
  }

  def resolveAll(ps: Iterable[Package.Parsed]): ValidatedNel[PackageError, Parsed] =
    build(ps).andThen(resolvePackages(_))


  def inferAll(ps: Parsed): ValidatedNel[PackageError, Inferred] = {

      type PackIn = PackageF[Unit, Program[Declaration, Statement]]
      type PackOut = PackageF[Referant, Program[(Declaration, Scheme), Statement]]

      val infer: PackIn => ValidatedNel[PackageError, PackOut] =
        Memoize.function[PackIn, ValidatedNel[PackageError, PackOut]] {
          case (p@Package(nm, imports, exports, prog), recurse) =>

            def getImport(packF: PackOut,
              exMap: Map[String, ExportedName[Referant]],
              i: ImportedName[Unit]): ValidatedNel[PackageError, ImportedName[Referant]] = {

              def err =
                Validated.invalidNel(PackageError.UnknownImportName(p, packF, i, exMap))

              i match {
                case ImportedName.OriginalName(nm, _) =>
                  exMap.get(nm) match {
                    case Some(ref) => Validated.valid(ImportedName.OriginalName(nm, ref.tag))
                    case None => err
                  }
                case ImportedName.Renamed(orig, nm, _) =>
                  exMap.get(orig) match {
                    case Some(ref) => Validated.valid(ImportedName.Renamed(orig, nm, ref.tag))
                    case None => err
                  }
              }
            }

            def stepImport(i: Import[FixPackage[Unit, Unit, Program[Declaration, Statement]], Unit]):
              ValidatedNel[PackageError, Import[FixPackage[Referant, Referant, Program[(Declaration, Scheme), Statement]], Referant]] = {
              val Import(fixpack, items) = i
              recurse(fixpack.unfix).andThen { packF =>
                val exMap = ExportedName.buildExportMap(packF.exports)
                items.traverse(getImport(packF, exMap, _))
                  .map(Import(Fix[Lambda[a => Package[a, Referant, Referant, Program[(Declaration, Scheme), Statement]]]](packF), _))
              }
            }

            def inferExports(imps: List[Import[FixPackage[Referant, Referant, Program[(Declaration, Scheme), Statement]], Referant]]):
              ValidatedNel[PackageError, (List[ExportedName[Referant]], TypeEnv, List[(String, Expr[(Declaration, Scheme)])])] = {
              implicit val subD: Substitutable[Declaration] = Substitutable.opaqueSubstitutable[Declaration]
              implicit val subS: Substitutable[String] = Substitutable.opaqueSubstitutable[String]
              implicit val subExpr: Substitutable[Expr[(Declaration, Scheme)]] =
                Substitutable.fromMapFold[Expr, (Declaration, Scheme)]

              val foldNest = Foldable[List].compose(Foldable[NonEmptyList])

              def addRef(te: TypeEnv, nm: String, ref: Referant): TypeEnv =
                ref match {
                  case Referant.Value(scheme) => te.updated(nm, scheme)
                  case Referant.DefinedT(dt) => te.addDefinedType(dt.rename(nm))
                  case Referant.Constructor(orig, dt) => te.addDefinedType(dt.renameConstructor(orig, nm))
                }

              val Program(te, lets, _) = prog
              val updatedTE = foldNest.foldLeft(imps.map(_.items), te) {
                case (te, ImportedName.OriginalName(nm, ref)) => addRef(te, nm, ref)
                case (te, ImportedName.Renamed(_, nm, ref)) => addRef(te, nm, ref)
              }

              val ilets = Inference.inferLets(lets)

              Inference.runInfer(updatedTE, ilets) match {
                case Left(typeerr) => Validated.invalidNel(PackageError.TypeErrorIn(typeerr, p)) // TODO give better errors
                case Right(lets) =>
                  val letMap = lets.toMap
                  def expName(n: ExportedName[Unit]): ValidatedNel[PackageError, ExportedName[Referant]] = {
                    def err = Validated.invalidNel(PackageError.UnknownExport(n, prog))
                    n match {
                      case ExportedName.Binding(n, _) =>
                        letMap.get(n) match {
                          case Some(exprDeclScheme) =>
                            Validated.valid(ExportedName.Binding(n, Referant.Value(exprDeclScheme.tag._2.normalized)))
                          case None => err
                        }
                      case ExportedName.TypeName(n, _) =>
                        // export the opaque type
                        te.definedTypes.get(TypeName(n)) match {
                          case Some(dt) =>
                            Validated.valid(ExportedName.Constructor(n, Referant.DefinedT(dt.toOpaque)))
                          case None => err
                        }
                      case ExportedName.Constructor(n, _) =>
                        // export the type and all constructors
                        te.definedTypes.get(TypeName(n)) match {
                          case Some(dt) =>
                            Validated.valid(ExportedName.Constructor(n, Referant.Constructor(ConstructorName(n), dt)))
                          case None => err
                        }
                    }
                  }
                  exports.traverse(expName).map((_, updatedTE, lets))
              }
            }

            imports
              .traverse(stepImport(_))
              .andThen { imps =>
                inferExports(imps).map((imps, _))
              }
              .map { case (imps, (exps, types, lets)) =>
                val Program(_, _, statement) = prog
                Package(nm, imps, exps, Program(types, lets, statement))
              }
        }

      ps.toMap.traverse(infer).map(PackageMap(_))
    }

    def resolveThenInfer(ps: Iterable[Package.Parsed]): ValidatedNel[PackageError, Inferred] =
        resolveAll(ps).andThen(inferAll(_))
}

sealed abstract class PackageError {
  def message: String = toString
}
object PackageError {
  case class UnknownExport(ex: ExportedName[Unit], in: Program[Declaration, Statement]) extends PackageError
  case class UnknownImportPackage[A, B, C](pack: PackageName, from: Package[PackageName, A, B, C]) extends PackageError
  // We could check if we forgot to export the name in the package and give that error
  case class UnknownImportName(in: Package.PackageF[Unit, Program[Declaration, Statement]],
    importing: Package.PackageF[Referant, Program[(Declaration, Scheme), Statement]],
    iname: ImportedName[Unit],
    exports: Map[String, ExportedName[Referant]]) extends PackageError {
      override def message = {
        val ls = importing
          .program
          .lets

        ls
          .toMap
          .get(iname.originalName) match {
            case Some(_) =>
              s"package: ${importing.name} has ${iname.originalName} but it is not exported. Add to exports"
            case None =>
              val dist = Memoize.function[String, Int] { (s, _) => EditDistance(iname.originalName.toIterable, s.toIterable) }
              val nearest = ls.map { case (n, _) => (dist(n), n) }.sorted.take(3).map { case (_, n) => n }.mkString(", ")
              s"package: ${importing.name} does not have name ${iname.originalName}. Nearest: $nearest"
          }
      }
    }
  case class DuplicatePackages(head: Package.Parsed, collisions: NonEmptyList[Package.Parsed]) extends PackageError
  case class CircularDependency[A, B, C](from: Package[PackageName, A, B, C], path: NonEmptyList[PackageName]) extends PackageError
  case class TypeErrorIn(tpeErr: TypeError, pack: Package.PackageF[Unit, Program[Declaration, Statement]]) extends PackageError
}