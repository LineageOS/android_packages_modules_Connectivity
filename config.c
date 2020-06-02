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

/* function: config_item_int16_t
 * locates the config item, parses the integer, and returns the pointer ret_val_ptr, or NULL on
 * failure
 *   root        - parsed configuration
 *   item_name   - name of config item to locate
 *   defaultvar  - value to use if config item isn't present
 *   ret_val_ptr - pointer for return value storage
 */
int16_t *config_item_int16_t(cnode *root, const char *item_name, const char *defaultvar,
                             int16_t *ret_val_ptr) {
  const char *tmp;
  char *endptr;
  long int conf_int;

  if (!(tmp = config_str(root, item_name, defaultvar))) {
    logmsg(ANDROID_LOG_FATAL, "%s config item needed", item_name);
    return NULL;
  }

  errno    = 0;
  conf_int = strtol(tmp, &endptr, 10);
  if (errno > 0) {
    logmsg(ANDROID_LOG_FATAL, "%s config item is not numeric: %s (error=%s)", item_name, tmp,
           strerror(errno));
    return NULL;
  }
  if (endptr == tmp || *tmp == '\0') {
    logmsg(ANDROID_LOG_FATAL, "%s config item is not numeric: %s", item_name, tmp);
    return NULL;
  }
  if (*endptr != '\0') {
    logmsg(ANDROID_LOG_FATAL, "%s config item contains non-numeric characters: %s", item_name,
           endptr);
    return NULL;
  }
  if (conf_int > INT16_MAX || conf_int < INT16_MIN) {
    logmsg(ANDROID_LOG_FATAL, "%s config item is too big/small: %d", item_name, conf_int);
    return NULL;
  }
  *ret_val_ptr = conf_int;
  return ret_val_ptr;
}

/* function: config_item_ip
 * locates the config item, parses the ipv4 address, and returns the pointer ret_val_ptr, or NULL on
 * failure
 *   root        - parsed configuration
 *   item_name   - name of config item to locate
 *   defaultvar  - value to use if config item isn't present
 *   ret_val_ptr - pointer for return value storage
 */
struct in_addr *config_item_ip(cnode *root, const char *item_name, const char *defaultvar,
                               struct in_addr *ret_val_ptr) {
  const char *tmp;
  int status;

  if (!(tmp = config_str(root, item_name, defaultvar))) {
    logmsg(ANDROID_LOG_FATAL, "%s config item needed", item_name);
    return NULL;
  }

  status = inet_pton(AF_INET, tmp, ret_val_ptr);
  if (status <= 0) {
    logmsg(ANDROID_LOG_FATAL, "invalid IPv4 address specified for %s: %s", item_name, tmp);
    return NULL;
  }

  return ret_val_ptr;
}

/* function: config_item_ip6
 * locates the config item, parses the ipv6 address, and returns the pointer ret_val_ptr, or NULL on
 * failure
 *   root        - parsed configuration
 *   item_name   - name of config item to locate
 *   defaultvar  - value to use if config item isn't present
 *   ret_val_ptr - pointer for return value storage
 */
struct in6_addr *config_item_ip6(cnode *root, const char *item_name, const char *defaultvar,
                                 struct in6_addr *ret_val_ptr) {
  const char *tmp;
  int status;

  if (!(tmp = config_str(root, item_name, defaultvar))) {
    logmsg(ANDROID_LOG_FATAL, "%s config item needed", item_name);
    return NULL;
  }

  status = inet_pton(AF_INET6, tmp, ret_val_ptr);
  if (status <= 0) {
    logmsg(ANDROID_LOG_FATAL, "invalid IPv6 address specified for %s: %s", item_name, tmp);
    return NULL;
  }

  return ret_val_ptr;
}

/* function: ipv6_prefix_equal
 * compares the prefixes two ipv6 addresses. assumes the prefix lengths are both /64.
 *   a1 - first address
 *   a2 - second address
 *   returns: 0 if the subnets are different, 1 if they are the same.
 */
int ipv6_prefix_equal(struct in6_addr *a1, struct in6_addr *a2) { return !memcmp(a1, a2, 8); }

/* function: read_config
 * reads the config file and parses it into the global variable Global_Clatd_Config. returns 0 on
 * failure, 1 on success
 *   file             - filename to parse
 *   uplink_interface - interface to use to reach the internet and supplier of address space
 */
int read_config(const char *file, const char *uplink_interface) {
  cnode *root   = config_node("", "");

  if (!root) {
    logmsg(ANDROID_LOG_FATAL, "out of memory");
    return 0;
  }

  memset(&Global_Clatd_Config, '\0', sizeof(Global_Clatd_Config));

  config_load_file(root, file);
  if (root->first_child == NULL) {
    logmsg(ANDROID_LOG_FATAL, "Could not read config file %s", file);
    goto failed;
  }

  Global_Clatd_Config.default_pdp_interface = strdup(uplink_interface);
  if (!Global_Clatd_Config.default_pdp_interface) goto failed;

  if (!config_item_ip(root, "ipv4_local_subnet", DEFAULT_IPV4_LOCAL_SUBNET,
                      &Global_Clatd_Config.ipv4_local_subnet))
    goto failed;

  if (!config_item_int16_t(root, "ipv4_local_prefixlen", DEFAULT_IPV4_LOCAL_PREFIXLEN,
                           &Global_Clatd_Config.ipv4_local_prefixlen))
    goto failed;

  return 1;

failed:
  free(root);
  return 0;
}
