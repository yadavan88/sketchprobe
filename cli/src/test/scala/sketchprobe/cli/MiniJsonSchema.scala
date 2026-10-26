package sketchprobe.cli

import scala.util.matching.Regex

/** A small JSON Schema checker for the keywords `docs/report.schema.json` uses: type, const, enum,
  * required, properties, additionalProperties, items, minimum, maximum, pattern, allOf and
  * if/then/else. It returns every violation with its path, so a failing test says where. It does
  * not pretend to be a full validator: a keyword it does not know is reported, not ignored, so the
  * schema cannot quietly gain checks that nothing enforces.
  */
object MiniJsonSchema {
  private val Known = Set(
    "$schema",
    "$id",
    "title",
    "description",
    "type",
    "const",
    "enum",
    "required",
    "properties",
    "additionalProperties",
    "items",
    "minimum",
    "maximum",
    "pattern",
    "allOf",
    "if",
    "then",
    "else"
  )

  private def typeName(v: ujson.Value): List[String] = v match {
    case ujson.Null    => List("null")
    case ujson.Bool(_) => List("boolean")
    case ujson.Str(_)  => List("string")
    case ujson.Arr(_)  => List("array")
    case ujson.Obj(_)  => List("object")
    case ujson.Num(d)  => if (d.isWhole) List("integer", "number") else List("number")
  }

  def validate(schema: ujson.Value, instance: ujson.Value, path: String = "$"): List[String] =
    schema match {
      case ujson.Bool(true)  => Nil
      case ujson.Bool(false) => List(s"$path: not allowed")
      case ujson.Obj(rules)  =>
        val unknown =
          rules.keys.filterNot(Known.contains).map(k => s"$path: unsupported keyword '$k'").toList
        unknown ++ rules.toList.flatMap { case (k, v) => check(k, v, rules, instance, path) }
      case other => List(s"$path: schema is not an object: $other")
    }

  private def check(
      key: String,
      rule: ujson.Value,
      all: scala.collection.mutable.Map[String, ujson.Value],
      v: ujson.Value,
      path: String
  ): List[String] = key match {
    case "type" =>
      val wanted = rule match {
        case ujson.Str(s) => List(s)
        case ujson.Arr(a) => a.toList.map(_.str)
        case _            => Nil
      }
      if (wanted.exists(typeName(v).contains)) Nil
      else List(s"$path: expected ${wanted.mkString(" or ")}, found ${typeName(v).head}")
    case "const" =>
      if (v == rule) Nil
      else List(s"$path: expected the constant ${ujson.write(rule)}, found ${ujson.write(v)}")
    case "enum" =>
      if (rule.arr.exists(_ == v)) Nil
      else List(s"$path: ${ujson.write(v)} is not one of ${ujson.write(rule)}")
    case "required" =>
      v match {
        case o: ujson.Obj =>
          rule.arr.toList.map(_.str).filterNot(o.value.contains).map(f => s"$path: missing '$f'")
        case _ => Nil
      }
    case "properties" =>
      v match {
        case o: ujson.Obj =>
          rule.obj.toList.flatMap { case (name, sub) =>
            o.value.get(name).toList.flatMap(validate(sub, _, s"$path.$name"))
          }
        case _ => Nil
      }
    case "additionalProperties" =>
      rule match {
        case ujson.Bool(true)  => Nil
        case ujson.Bool(false) =>
          val declared = all.get("properties").map(_.obj.keySet.toSet).getOrElse(Set.empty)
          v match {
            case o: ujson.Obj =>
              o.value.keys.filterNot(declared.contains).map(k => s"$path: unexpected '$k'").toList
            case _ => Nil
          }
        case _ => List(s"$path: additionalProperties as a schema is not supported here")
      }
    case "items" =>
      v match {
        case ujson.Arr(items) =>
          items.toList.zipWithIndex.flatMap { case (x, i) => validate(rule, x, s"$path[$i]") }
        case _ => Nil
      }
    case "minimum" =>
      v match {
        case ujson.Num(d) =>
          if (d >= rule.num) Nil else List(s"$path: $d is below the minimum ${rule.num}")
        case _ => Nil
      }
    case "maximum" =>
      v match {
        case ujson.Num(d) =>
          if (d <= rule.num) Nil else List(s"$path: $d is above the maximum ${rule.num}")
        case _ => Nil
      }
    case "pattern" =>
      v match {
        case ujson.Str(s) =>
          val re = new Regex(rule.str)
          if (re.findFirstIn(s).isDefined) Nil else List(s"$path: '$s' does not match ${rule.str}")
        case _ => Nil
      }
    case "allOf" => rule.arr.toList.flatMap(validate(_, v, path))
    case "if"    =>
      val matches = validate(rule, v, path).isEmpty
      val branch = if (matches) all.get("then") else all.get("else")
      branch.toList.flatMap(validate(_, v, path))
    case _ => Nil // then, else (handled by if), and the annotation keywords
  }
}
