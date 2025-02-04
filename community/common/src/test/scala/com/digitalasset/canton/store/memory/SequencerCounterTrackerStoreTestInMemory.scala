// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.store.memory

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.store.SequencerCounterTrackerStoreTest
import org.scalatest.wordspec.AsyncWordSpec

class SequencerCounterTrackerStoreTestInMemory
    extends AsyncWordSpec
    with BaseTest
    with SequencerCounterTrackerStoreTest {

  "InMemorySequencerCounterTrackerStore" should {
    behave like sequencerCounterTrackerStore(() =>
      new InMemorySequencerCounterTrackerStore(loggerFactory)
    )
  }
}
