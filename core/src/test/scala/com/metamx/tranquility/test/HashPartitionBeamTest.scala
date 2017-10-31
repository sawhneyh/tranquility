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

import com.metamx.tranquility.beam.Beam
import com.metamx.tranquility.beam.HashPartitionBeam
import com.metamx.tranquility.beam.SendResult
import com.twitter.util.Await
import com.twitter.util.Future
import java.util.concurrent.CopyOnWriteArrayList
import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.collection.JavaConverters._
import scala.collection.immutable.BitSet
import scala.collection.mutable

class HashPartitionBeamTest extends FunSuite with Matchers
{

  class TestObject(val s: String)
  {
    override def hashCode(): Int = s(0).toInt
  }

  class DroppingBeam() extends Beam[TestObject]
  {
    override def sendAll(messages: Seq[TestObject]): Seq[Future[SendResult]] = {
      messages.map(_ => Future(SendResult.Dropped))
    }

    override def close(): Future[Unit] = Future.Done
  }

  class TestBeam(callback: TestObject => Unit, dropAll: Boolean = false) extends Beam[TestObject]
  {
    override def sendAll(messages: Seq[TestObject]): Seq[Future[SendResult]] = {
      messages foreach callback
      messages.map(_ => Future(SendResult.Sent))
    }

    override def close() = Future.Done
  }

  test("Partitioning") {
    val bufferOne: mutable.Buffer[TestObject] = new CopyOnWriteArrayList[TestObject]().asScala
    val bufferTwo: mutable.Buffer[TestObject] = new CopyOnWriteArrayList[TestObject]().asScala

    val one = new TestBeam(bufferOne += _)
    val two = new TestBeam(bufferTwo += _)
    val beam = new HashPartitionBeam(Vector(one, two))
    Await.result(
      beam.sendBatch(
        Seq(
          new TestObject("foo"),
          new TestObject("bar"),
          new TestObject("foo")
        )
      )
    ) should be(BitSet(0, 1, 2))
    bufferOne.map(_.s) should be(Seq("bar"))
    bufferTwo.map(_.s) should be(Seq("foo", "foo"))
  }

  test("Partitioning, with drops") {
    val bufferTwo: mutable.Buffer[TestObject] = new CopyOnWriteArrayList[TestObject]().asScala

    val one = new DroppingBeam
    val two = new TestBeam(bufferTwo += _)
    val beam = new HashPartitionBeam(Vector(one, two))
    Await.result(
      beam.sendBatch(
        Seq(
          new TestObject("foo"),
          new TestObject("bar"),
          new TestObject("foo")
        )
      )
    ) should be(BitSet(0, 2))
    bufferTwo.map(_.s) should be(Seq("foo", "foo"))
  }
}
