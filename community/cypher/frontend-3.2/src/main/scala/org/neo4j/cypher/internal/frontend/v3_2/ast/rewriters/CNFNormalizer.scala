/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.frontend.v3_2.ast.rewriters

import org.neo4j.cypher.internal.frontend.v3_2.Foldable._
import org.neo4j.cypher.internal.frontend.v3_2.ast._
import org.neo4j.cypher.internal.frontend.v3_2.ast.functions.Exists
import org.neo4j.cypher.internal.frontend.v3_2.helpers.fixedPoint
import org.neo4j.cypher.internal.frontend.v3_2.phases.{BaseContext, Condition}
import org.neo4j.cypher.internal.frontend.v3_2.{AstRewritingMonitor, Rewriter, bottomUp, inSequence, topDown}

case object CNFNormalizer extends StatementRewriter {

  override def description: String = "normalize boolean predicates into conjunctive normal form"

  override def instance(context: BaseContext): Rewriter = {
    implicit val monitor = context.monitors.newMonitor[AstRewritingMonitor]()
    inSequence(
      deMorganRewriter(),
      distributeLawsRewriter(),
      flattenBooleanOperators,
      simplifyPredicates,
      // Redone here since CNF normalization might introduce negated inequalities (which this removes)
      normalizeSargablePredicates
    )
  }

  override def postConditions: Set[Condition] = Set.empty
}

case class deMorganRewriter()(implicit monitor: AstRewritingMonitor) extends Rewriter {

  def apply(that: AnyRef): AnyRef = instance(that)

  private val step = Rewriter.lift {
    case p@Xor(expr1, expr2) =>
      And(Or(expr1, expr2)(p.position), Not(And(expr1.endoRewrite(copyVariables), expr2.endoRewrite(copyVariables))(p.position))(p.position))(p.position)
    case p@Not(And(exp1, exp2)) =>
      Or(Not(exp1)(p.position), Not(exp2)(p.position))(p.position)
    case p@Not(Or(exp1, exp2)) =>
      And(Not(exp1)(p.position), Not(exp2)(p.position))(p.position)
  }

  private val instance: Rewriter = repeatWithSizeLimit(bottomUp(step))(monitor)
}

object distributeLawsRewriter {
  // converting from DNF to CNF is exponentially expensive, so we only do it for a small amount of clauses
  // see https://en.wikipedia.org/wiki/Conjunctive_normal_form#Conversion_into_CNF
  val DNF_CONVERSION_LIMIT = 8
}

case class distributeLawsRewriter()(implicit monitor: AstRewritingMonitor) extends Rewriter {
  def apply(that: AnyRef): AnyRef = {
    if (dnfCounts(that) < distributeLawsRewriter.DNF_CONVERSION_LIMIT)
      instance(that)
    else {
      monitor.abortedRewritingDueToLargeDNF(that)
      that
    }
  }

  private def dnfCounts(value: Any) = value.treeFold(1) {
    case Or(lhs, a: And) => acc => (acc + 1, Some(identity))
    case Or(a: And, rhs) => acc => (acc + 1, Some(identity))
  }

  private val step = Rewriter.lift {
    case p@Or(exp1, And(exp2, exp3)) => And(Or(exp1, exp2)(p.position), Or(exp1.endoRewrite(copyVariables), exp3)(p.position))(p.position)
    case p@Or(And(exp1, exp2), exp3) => And(Or(exp1, exp3)(p.position), Or(exp2, exp3.endoRewrite(copyVariables))(p.position))(p.position)
  }

  private val instance: Rewriter = repeatWithSizeLimit(bottomUp(step))(monitor)
}

object flattenBooleanOperators extends Rewriter {
  def apply(that: AnyRef): AnyRef = instance.apply(that)

  private val firstStep: Rewriter = Rewriter.lift {
    case p@And(lhs, rhs) => Ands(Set(lhs, rhs))(p.position)
    case p@Or(lhs, rhs)  => Ors(Set(lhs, rhs))(p.position)
  }

  private val secondStep: Rewriter = Rewriter.lift {
    case p@Ands(exprs) => Ands(exprs.flatMap {
      case Ands(inner) => inner
      case x => Set(x)
    })(p.position)
    case p@Ors(exprs) => Ors(exprs.flatMap {
      case Ors(inner) => inner
      case x => Set(x)
    })(p.position)
  }

  private val instance = inSequence(bottomUp(firstStep), fixedPoint(bottomUp(secondStep)))
}

object simplifyPredicates extends Rewriter {
  def apply(that: AnyRef): AnyRef = instance.apply(that)

  private val T = True()(null)
  private val F = False()(null)

  private val step: Rewriter = Rewriter.lift {
    case Not(Not(exp))                    => exp
    case p@Ands(exps) if exps.size == 1   => exps.head
    case p@Ors(exps) if exps.size == 1    => exps.head
    case p@Ands(exps) if exps.contains(T) => Ands(exps.filterNot(T == _))(p.position)
    case p@Ors(exps) if exps.contains(F)  => Ors(exps.filterNot(F == _))(p.position)
    case p@Ors(exps) if exps.contains(T)  => True()(p.position)
    case p@Ands(exps) if exps.contains(F) => False()(p.position)
  }

  private val instance = fixedPoint(bottomUp(step))
}

case object normalizeSargablePredicates extends Rewriter {

  override def apply(that: AnyRef): AnyRef = instance(that)

  private val instance: Rewriter = topDown(Rewriter.lift {

    // turn n.prop IS NOT NULL into exists(n.prop)
    case predicate@IsNotNull(property@Property(_, _)) =>
      Exists.asInvocation(property)(predicate.position)

    // remove not from inequality expressions by negating them
    case Not(inequality: InequalityExpression) =>
      inequality.negated
  })
}
