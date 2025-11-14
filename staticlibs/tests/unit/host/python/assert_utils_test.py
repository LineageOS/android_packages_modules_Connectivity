#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from mobly import asserts
from mobly import base_test
from net_tests_utils.host.python.assert_utils import (
    UnexpectedBehaviorError,
    UnexpectedExceptionError,
    expect_throws,
    expect_with_retry,
)


class TestAssertUtils(base_test.BaseTestClass):

  def test_predicate_succeed(self):
    """Test when the predicate becomes True within retries."""
    call_count = 0

    def predicate():
      nonlocal call_count
      call_count += 1
      return call_count > 2  # True on the third call

    expect_with_retry(predicate, max_retries=5, retry_interval_sec=0)
    asserts.assert_equal(call_count, 3)  # Ensure it was called exactly 3 times

  def test_predicate_failed(self):
    """Test when the predicate never becomes True."""

    with asserts.assert_raises(UnexpectedBehaviorError):
      expect_with_retry(
          predicate=lambda: False, max_retries=3, retry_interval_sec=0
      )

  def test_retry_action_not_called_succeed(self):
    """Test that the retry_action is not called if the predicate returns true in the first try."""
    retry_action_called = False

    def retry_action():
      nonlocal retry_action_called
      retry_action_called = True

    expect_with_retry(
        predicate=lambda: True,
        retry_action=retry_action,
        max_retries=5,
        retry_interval_sec=0,
    )
    asserts.assert_false(
        retry_action_called, 'retry_action called.'
    )  # Assert retry_action was NOT called

  def test_retry_action_not_called_failed(self):
    """Test that the retry_action is not called if the max_retries is reached."""
    retry_action_called = False

    def retry_action():
      nonlocal retry_action_called
      retry_action_called = True

    with asserts.assert_raises(UnexpectedBehaviorError):
      expect_with_retry(
          predicate=lambda: False,
          retry_action=retry_action,
          max_retries=1,
          retry_interval_sec=0,
      )
    asserts.assert_false(
        retry_action_called, 'retry_action called.'
    )  # Assert retry_action was NOT called

  def test_retry_action_called(self):
    """Test that the retry_action is executed when provided."""
    retry_action_called = False

    def retry_action():
      nonlocal retry_action_called
      retry_action_called = True

    with asserts.assert_raises(UnexpectedBehaviorError):
      expect_with_retry(
          predicate=lambda: False,
          retry_action=retry_action,
          max_retries=2,
          retry_interval_sec=0,
      )
    asserts.assert_true(retry_action_called, 'retry_action not called.')

  def test_expect_exception_throws(self):
    def raise_unexpected_behavior_error():
      raise UnexpectedBehaviorError()

    expect_throws(raise_unexpected_behavior_error, UnexpectedBehaviorError)

  def test_unexpect_exception_throws(self):
    def raise_value_error():
      raise ValueError()

    with asserts.assert_raises(UnexpectedExceptionError):
      expect_throws(raise_value_error, UnexpectedBehaviorError)

  def test_no_exception_throws(self):
    def raise_no_error():
      return

    expect_throws(raise_no_error, UnexpectedBehaviorError)
