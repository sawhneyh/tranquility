/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.metamx.tranquility.test

import com.fasterxml.jackson.core.JsonGenerator
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.metamx.common.scala.Jackson
import com.metamx.common.scala.Logging
import com.metamx.common.scala.Predef._
import com.metamx.common.scala.concurrent.abortingRunnable
import com.metamx.common.scala.untyped.Dict
import com.metamx.tranquility.beam.Beam
import com.metamx.tranquility.beam.MemoryBeam
import com.metamx.tranquility.beam.SendResult
import com.metamx.tranquility.test.TranquilizerTest._
import com.metamx.tranquility.test.common.FailableBeam
import com.metamx.tranquility.tranquilizer.BufferFullException
import com.metamx.tranquility.tranquilizer.MessageDroppedException
import com.metamx.tranquility.tranquilizer.Tranquilizer
import com.metamx.tranquility.typeclass.JsonWriter
import com.twitter.util.Await
import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.util.Random

class TranquilizerTest extends FunSuite with Matchers with Logging
{
  def doSend(
    tranquilizer: Tranquilizer[String],
    strings: Seq[String],
    doFlush: Boolean = false
  ): (Long, Long, Long) =
  {
    val futures = for (string <- strings) yield {
      tranquilizer.send(string) map { ok =>
        (1L, 0L, 0L)
      } handle {
        case e: MessageDroppedException =>
          (0L, 1L, 0L)
        case e: Exception =>
          (0L, 0L, 1L)
      }
    }
    if (doFlush) {
      tranquilizer.flush()
    }
    Await.result(Future.collect(futures)).foldLeft((0L, 0L, 0L)) { (a, b) =>
      (a._1 + b._1, a._2 + b._2, a._3 + b._3)
    }
  }

  test("Send by maxBatchSize") {
    for (beam <- newBeams(); maxBatchSize <- Seq(1, 2); maxPendingBatches <- Seq(1); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "what"))
          assert(acked === 2, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 0, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey"), Dict("bar" -> "what")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send by lingerMillis") {
    for (beam <- newBeams(); maxBatchSize <- Seq(100); maxPendingBatches <- Seq(1, 2, 10); lingerMillis <- Seq(100)) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "what"))
          assert(acked === 2, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 0, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey"), Dict("bar" -> "what")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send by flush") {
    for (beam <- newBeams(); maxBatchSize <- Seq(100); maxPendingBatches <- Seq(1, 5); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "what"), true)
          assert(acked === 2, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 0, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey"), Dict("bar" -> "what")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send with failures (single event batches)") {
    for (beam <- newBeams(); maxBatchSize <- Seq(1); maxPendingBatches <- Seq(1); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "__fail__"))
          assert(acked === 1, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 1, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send with failures (multi event batches)") {
    for (beam <- newBeams(); maxBatchSize <- Seq(2); maxPendingBatches <- Seq(1); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "__fail__"))
          assert(acked === 1, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 1, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send with superfailures (multi event batches)") {
    for (beam <- newBeams(); maxBatchSize <- Seq(2); maxPendingBatches <- Seq(1); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "__superfail__"))
          assert(acked === 0, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 2, "failed (%s)" format desc)
          assert(
            MemoryBeam.get() === Map.empty,
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send with drops (single event batches)") {
    for (beam <- newBeams(); maxBatchSize <- Seq(1); maxPendingBatches <- Seq(1); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "__drop__"))
          assert(acked === 1, "acked (%s)" format desc)
          assert(dropped === 1, "dropped (%s)" format desc)
          assert(failed === 0, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send with drops (multi event batches)") {
    for (beam <- newBeams(); maxBatchSize <- Seq(2); maxPendingBatches <- Seq(1); lingerMillis <- MomentsSoDear) {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val (acked, dropped, failed) = doSend(tranquilizer, Seq("hey", "__drop__"))
          assert(acked === 1, "acked (%s)" format desc)
          assert(dropped === 1, "dropped (%s)" format desc)
          assert(failed === 0, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === Seq(Dict("bar" -> "hey")),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Send 200k messages") {
    for {
      beam <- Seq(newDelayedMemoryBeam(50, 0.2))
      maxBatchSize <- Seq(2000)
      maxPendingBatches <- Seq(1, 5)
      lingerMillis <- Seq(0, 100)
    } {
      MemoryBeam.clear()
      newTranquilizer(beam, maxBatchSize, maxPendingBatches, lingerMillis).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val count = 200002
          val messages: Seq[String] = (0 until count) map (_ => "x")
          val (acked, dropped, failed) = doSend(tranquilizer, messages)
          assert(acked === count, "acked (%s)" format desc)
          assert(dropped === 0, "dropped (%s)" format desc)
          assert(failed === 0, "failed (%s)" format desc)
          assert(
            MemoryBeam.get("foo") === messages.map(s => Dict("bar" -> s)),
            "output (%s)" format desc
          )
      }
    }
  }

  test("Multithreaded batch sends") {
    for {
      beam <- Seq(newDelayedMemoryBeam(50, 0.2))
      maxBatchSize <- Seq(2000)
      maxPendingBatches <- Seq(1, 5)
      lingerMillis <- Seq(0, 100)
    } {
      MemoryBeam.clear()
      val exec = Executors.newFixedThreadPool(8, new ThreadFactoryBuilder().setDaemon(true).build())
      val allAcked = new AtomicLong
      val sender = Tranquilizer.create(beam, maxBatchSize, maxPendingBatches, lingerMillis)
      sender.start()
      val futures = for (i <- 0 until 8) yield {
        exec.submit(
          new Runnable
          {
            override def run(): Unit = {
              val acked = new AtomicLong
              val failed = new AtomicLong

              for (j <- 0 until 40003) {
                sender.send("x") respond {
                  case Return(_) => acked.incrementAndGet()
                  case Throw(e) => failed.incrementAndGet()
                }
              }

              sender.flush()
              if (failed.get() == 0) {
                allAcked.addAndGet(acked.get())
              }
            }
          }
        )
      }

      futures foreach (_.get())
      exec.shutdown()
      exec.awaitTermination(10, TimeUnit.MINUTES)
      sender.stop()
      assert(allAcked.get() === 40003 * 8)
    }

  }

  test("Send with BufferFullExceptions") {
    for {
      beam <- Seq(newDelayedMemoryBeam(500, 0.1))
      maxBatchSize <- Seq(10)
      maxPendingBatches <- Seq(1, 5)
      lingerMillis <- Seq(0, 100)
    } {
      MemoryBeam.clear()
      val builder = Tranquilizer.builder()
        .maxBatchSize(maxBatchSize)
        .maxPendingBatches(maxPendingBatches)
        .lingerMillis(lingerMillis)
        .blockOnFull(false)
      newTranquilizer(beam, builder).withFinally(_._1.stop()) {
        case (tranquilizer, desc) =>
          val count = 200002
          val messages: Seq[String] = (0 until count) map (_ => "x")
          val e = the[BufferFullException] thrownBy {
            doSend(tranquilizer, messages)
          }
          e.getMessage should be("Buffer full")
      }
    }
  }
}

object TranquilizerTest
{
  val MomentsSoDear = Seq(525600 * 60000, -1)

  def newBeams(): Seq[Beam[String]] = {
    Seq(
      newImmediateMemoryBeam(),
      newDelayedMemoryBeam(10, 0),
      newDelayedMemoryBeam(100, 0.1),
      newDelayedMemoryBeam(250, 0.2)
    )
  }

  def newImmediateMemoryBeam(): Beam[String] = {
    new MemoryBeam[String](
      "foo",
      new JsonWriter[String]
      {
        override protected def viaJsonGenerator(a: String, jg: JsonGenerator): Unit = {
          Jackson.generate(Dict("bar" -> a), jg)
        }
      }
    )
  }

  def newDelayedMemoryBeam(baseDelay: Long, fuzz: Double): Beam[String] = {
    val memoryBeam = newImmediateMemoryBeam()
    val exec = Executors.newSingleThreadScheduledExecutor()
    val random = new Random()
    new Beam[String] with Logging
    {
      override def sendAll(messages: Seq[String]): Seq[Future[SendResult]] = {
        val delay = math.max(1, baseDelay + fuzz * baseDelay * random.nextGaussian()).toLong
        log.debug(s"Delaying propagate by ${delay}ms.")

        val promises = messages.map(_ => Promise[SendResult]())
        exec.schedule(
          abortingRunnable {
            for ((promise, future) <- promises zip memoryBeam.sendAll(messages)) {
              promise.become(future)
            }
          },
          delay,
          TimeUnit.MILLISECONDS
        )
        promises
      }

      override def close(): Future[Unit] = memoryBeam.close()

      override def toString: String = s"Delayed($memoryBeam)"
    }
  }

  def newTranquilizer(beam: Beam[String], builder: Tranquilizer.Builder): (Tranquilizer[String], String) = {
    val wrappedBeam = FailableBeam.forStrings(beam)
    val tranquilizer = builder.build(wrappedBeam)
    val desc = s"(builder = $builder, beam = $wrappedBeam)"

    tranquilizer.start()
    (tranquilizer, desc)
  }

  def newTranquilizer(
    beam: Beam[String],
    maxBatchSize: Int,
    maxPendingBatches: Int,
    lingerMillis: Long
  ): (Tranquilizer[String], String) =
  {
    newTranquilizer(
      beam,
      Tranquilizer.builder()
        .maxBatchSize(maxBatchSize)
        .maxPendingBatches(maxPendingBatches)
        .lingerMillis(lingerMillis)
    )
  }
}
