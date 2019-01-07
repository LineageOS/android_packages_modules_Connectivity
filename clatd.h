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
 * clatd.h - main routines used by clatd
 */
#ifndef __CLATD_H__
#define __CLATD_H__

#include <sys/uio.h>

struct tun_data;

#define MAXMTU 1500
#define PACKETLEN (MAXMTU + sizeof(struct tun_pi))
#define CLATD_VERSION "1.4"

#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))

// how frequently (in seconds) to poll for an address change while traffic is passing
#define INTERFACE_POLL_FREQUENCY 30

// how frequently (in seconds) to poll for an address change while there is no traffic
#define NO_TRAFFIC_INTERFACE_POLL_FREQUENCY 90

void stop_loop();
void set_capability(uint64_t target_cap);
void drop_root_but_keep_caps();
void open_sockets(struct tun_data *tunnel, uint32_t mark);
int ipv6_address_changed(const char *interface);
int update_clat_ipv6_address(const struct tun_data *tunnel, const char *interface);
void configure_interface(const char *uplink_interface, const char *plat_prefix,
                         struct tun_data *tunnel, unsigned net_id);
void event_loop(struct tun_data *tunnel);
int parse_unsigned(const char *str, unsigned *out);

#endif /* __CLATD_H__ */
