/*
 * Copyright 2011 Daniel Drown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * config.c - configuration settings
 */

#include <arpa/inet.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <cutils/config_utils.h>
#include <netutils/checksum.h>
#include <netutils/ifc.h>

#include "clatd.h"
#include "config.h"
#include "getaddr.h"
#include "logging.h"

struct clat_config Global_Clatd_Config;

/* function: ipv6_prefix_equal
 * compares the prefixes two ipv6 addresses. assumes the prefix lengths are both /64.
 *   a1 - first address
 *   a2 - second address
 *   returns: 0 if the subnets are different, 1 if they are the same.
 */
int ipv6_prefix_equal(struct in6_addr *a1, struct in6_addr *a2) { return !memcmp(a1, a2, 8); }
