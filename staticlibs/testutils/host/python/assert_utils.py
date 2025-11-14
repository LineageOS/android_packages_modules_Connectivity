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

import time
from typing import Callable


class UnexpectedBehaviorError(Exception):
  """Raised when there is an unexpected behavior during applying a procedure."""


class UnexpectedExceptionError(Exception):
  """Raised when there is an unexpected exception throws during applying a procedure"""


def expect_with_retry(
    predicate: Callable[[], bool],
    retry_action: Callable[[], None] = None,
    max_retries: int = 10,
    retry_interval_sec: int = 1,
) -> None:
  """Executes a predicate and retries if it doesn't return True."""

  for retry in range(max_retries):
    if predicate():
      return None
    else:
      if retry == max_retries - 1:
        break
      if retry_action:
        retry_action()
      time.sleep(retry_interval_sec)

  raise UnexpectedBehaviorError(
      "Predicate didn't become true after " + str(max_retries) + ' retries.'
  )


def expect_throws(runnable: callable, exception_class) -> None:
  try:
    runnable()
    raise UnexpectedBehaviorError('Expected an exception, but none was thrown')
  except exception_class:
    pass
  except UnexpectedBehaviorError as e:
    raise e
  except Exception as e:
    raise UnexpectedExceptionError(
        f'Expected exception of type {exception_class.__name__}, '
        f'but got {type(e).__name__}: {e}'
    )
