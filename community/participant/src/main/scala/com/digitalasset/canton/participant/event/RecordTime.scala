// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.event

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.util.TimeOfChange

/** Canton-internal record time
  * @param timestamp ACS change timestamp
  * @param tieBreaker ordering tie-breaker for changes that have the same timestamp (currently, happens only with repair requests)
  */
case class RecordTime(timestamp: CantonTimestamp, tieBreaker: Long) extends PrettyPrinting {
  override lazy val pretty: Pretty[RecordTime] = prettyOfClass(
    param("timestamp", _.timestamp),
    param("tieBreaker", _.tieBreaker),
  )

  /** Note that there is no guarantee that this will result in a time of change with an existing request counter. */
  def toTimeOfChange: TimeOfChange = TimeOfChange(tieBreaker, timestamp)
}
object RecordTime {
  val lowestTiebreaker: Long = Long.MinValue

  val MinValue: RecordTime = RecordTime(CantonTimestamp.MinValue, lowestTiebreaker)

  implicit val recordTimeOrdering: Ordering[RecordTime] =
    Ordering.by(rt => (rt.timestamp -> rt.tieBreaker))

  def fromTimeOfChange(toc: TimeOfChange): RecordTime = RecordTime(toc.timestamp, toc.rc)

}
