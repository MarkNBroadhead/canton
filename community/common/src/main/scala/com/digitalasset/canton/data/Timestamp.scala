// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.data

import com.digitalasset.canton.LfTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.serialization.ProtoConverter
import com.google.protobuf.timestamp.{Timestamp => ProtoTimestamp}

import java.time.Instant

trait Timestamp extends PrettyPrinting {
  def underlying: LfTimestamp

  def toLf: LfTimestamp = underlying

  def isAfter(t: CantonTimestamp): Boolean = underlying.compareTo(t.underlying) > 0

  def isBefore(t: CantonTimestamp): Boolean = underlying.compareTo(t.underlying) < 0

  def toProtoPrimitive: ProtoTimestamp =
    ProtoConverter.InstantConverter.toProtoPrimitive(underlying.toInstant)

  def getEpochSecond: Long = underlying.toInstant.getEpochSecond

  def toEpochMilli: Long = underlying.toInstant.toEpochMilli

  def toInstant: Instant = underlying.toInstant

  def toMicros: Long = underlying.micros

  def microsOverSecond(): Long = {
    val nanos = underlying.toInstant.getNano
    assert(nanos % 1000 == 0)
    nanos / 1000L
  }

  override def pretty: Pretty[this.type] = prettyOfParam(_.underlying)
}
