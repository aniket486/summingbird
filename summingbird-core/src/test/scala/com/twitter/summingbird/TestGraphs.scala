/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird

import com.twitter.algebird.MapAlgebra
import com.twitter.algebird.Monoid
import org.scalacheck.Arbitrary
import org.scalacheck.Prop._

/**
  * Helpful functions and test graphs designed to flex Summingbird
  * planners.
  */

object TestGraphs {
  def diamondJobInScala[T, K, V: Monoid]
    (source: TraversableOnce[T])
    (fnA: T => TraversableOnce[(K, V)])
    (fnB: T => TraversableOnce[(K, V)]): Map[K, V] = {
    val stream = source.toStream
    val left = stream.flatMap(fnA)
    val right = stream.flatMap(fnB)
    MapAlgebra.sumByKey(left ++ right)
  }

  def diamondJob[P <: Platform[P], T, K, V: Monoid]
    (source: Producer[P, T], sink: P#Sink[T], store: P#Store[K, V])
    (fnA: T => TraversableOnce[(K, V)])
    (fnB: T => TraversableOnce[(K, V)]): Summer[P, K, V] = {
    val written = source.write(sink)
    val left = written.flatMap(fnA)
    val right = written.flatMap(fnB)
    left.merge(right).sumByKey(store)
  }

  def singleStepInScala[T, K, V: Monoid]
    (source: TraversableOnce[T])
    (fn: T => TraversableOnce[(K, V)]): Map[K, V] =
    MapAlgebra.sumByKey(
      source.flatMap(fn)
    )

  def singleStepJob[P <: Platform[P], T, K, V: Monoid]
    (source: Producer[P, T], store: P#Store[K, V])
    (fn: T => TraversableOnce[(K, V)]): Summer[P, K, V] =
    source
      .flatMap(fn)
      .sumByKey(store)

  def leftJoinInScala[T, U, JoinedU, K, V: Monoid]
    (source: TraversableOnce[T])
    (service: K => Option[JoinedU])
    (preJoinFn: T => TraversableOnce[(K, U)])
    (postJoinFn: ((K, (U, Option[JoinedU]))) => TraversableOnce[(K, V)]): Map[K, V] =
    MapAlgebra.sumByKey(
      source
        .flatMap(preJoinFn)
        .map { case (k, v) => (k, (v, service(k))) }
        .flatMap(postJoinFn)
    )

  def leftJoinJob[P <: Platform[P], T, U, JoinedU, K, V: Monoid](
    source: Producer[P, T],
    service: P#Service[K, JoinedU],
    store: P#Store[K, V])
    (preJoinFn: T => TraversableOnce[(K, U)])
    (postJoinFn: ((K, (U, Option[JoinedU]))) => TraversableOnce[(K, V)]): Summer[P, K, V] =
    source
      .name("My named source")
      .flatMap(preJoinFn)
      .leftJoin(service)
      .name("My named flatmap")
      .flatMap(postJoinFn)
      .sumByKey(store)

  def mapOnlyJob[P <: Platform[P], T, U](
    source: Producer[P, T],
    sink: P#Sink[U]
    )(mapOp: T => TraversableOnce[U]): Producer[P, U] =
    source
      .flatMap(mapOp)
      .write(sink)
}

class TestGraphs[P <: Platform[P], T: Manifest: Arbitrary, K: Arbitrary, V: Arbitrary: Equiv: Monoid](platform: P)(
  store: () => P#Store[K, V])(sink: () => P#Sink[T])(
  sourceMaker: TraversableOnce[T] => Producer[P, T])(
  toLookupFn: P#Store[K, V] => (K => Option[V]))(
  toSinkChecker: (P#Sink[T], List[T]) => Boolean)(
  run: (P, P#Plan[_]) => Unit
  ){

  def diamondChecker = forAll { (items: List[T], fnA: T => List[(K, V)], fnB: T => List[(K, V)]) =>
    val currentStore = store()
    val currentSink = sink()
    // Use the supplied platform to execute the source into the
    // supplied store.
    val plan = platform.plan {
      TestGraphs.diamondJob(sourceMaker(items), currentSink, currentStore)(fnA)(fnB)
    }
    run(platform, plan)
    val lookupFn = toLookupFn(currentStore)
    TestGraphs.diamondJobInScala(items)(fnA)(fnB).forall { case (k, v) =>
      val lv = lookupFn(k).getOrElse(Monoid.zero)
      Equiv[V].equiv(v, lv)
    } && toSinkChecker(currentSink, items)
  }

    /**
    * Accepts a platform, and supplier of an EMPTY store from K -> V
    * and returns a ScalaCheck property. The property generates a
    * random function from T => TraversableOnce[(K, V)], wires up
    * singleStepJob summingbird job using this function and runs the
    * job using the supplied platform.
    *
    * Results are retrieved using the supplied toMap function. The
    * initial data source is generated using the supplied sourceMaker
    * function.
      */
  def singleStepChecker = forAll { (items: List[T], fn: T => List[(K, V)]) =>
    val currentStore = store()
    // Use the supplied platform to execute the source into the
    // supplied store.
    val plan = platform.plan {
      TestGraphs.singleStepJob(sourceMaker(items), currentStore)(fn)
    }
    run(platform, plan)
    val lookupFn = toLookupFn(currentStore)
    TestGraphs.singleStepInScala(items)(fn).forall { case (k, v) =>
      val lv = lookupFn(k).getOrElse(Monoid.zero)
      Equiv[V].equiv(v, lv)
    }
  }

  /**
    * Accepts a platform, a service of K -> JoinedU and a supplier of
    * an EMPTY store from K -> V and returns a ScalaCheck
    * property. The property generates random functions between the
    * types required by leftJoinJob, wires up a summingbird job using
    * those functions and runs the job using the supplied platform.
    *
    * Results are retrieved using the supplied toMap function. The
    * service is tested in scala's in-memory mode using serviceToFn,
    * and the initial data source is generated using the supplied
    * sourceMaker function.
    */
  def leftJoinChecker[U: Arbitrary, JoinedU: Arbitrary](service: P#Service[K, JoinedU])
    (serviceToFn: P#Service[K, JoinedU] => (K => Option[JoinedU])) =
    forAll { (items: List[T], preJoinFn: T => List[(K, U)], postJoinFn: ((K, (U, Option[JoinedU]))) => List[(K, V)]) =>
      val currentStore = store()

      val plan = platform.plan {
        TestGraphs.leftJoinJob(sourceMaker(items), service, currentStore)(preJoinFn)(postJoinFn)
      }
      run(platform, plan)
      val serviceFn = serviceToFn(service)
      val lookupFn = toLookupFn(currentStore)

      MapAlgebra.sumByKey(
        items
          .flatMap(preJoinFn)
          .map { case (k, u) => (k, (u, serviceFn(k))) }
          .flatMap(postJoinFn)
      ).forall { case (k, v) =>
          val lv = lookupFn(k).getOrElse(Monoid.zero)
          Equiv[V].equiv(v, lv)
      }
    }
}
