/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity.mdns;

import java.util.Collections;
import java.util.List;

/**
 * A class holding data that can be included in a mDNS packet.
 */
public class MdnsPacket {
    public final int flags;
    public final List<MdnsRecord> questions;
    public final List<MdnsRecord> answers;
    public final List<MdnsRecord> authorityRecords;
    public final List<MdnsRecord> additionalRecords;

    MdnsPacket(int flags,
            List<MdnsRecord> questions,
            List<MdnsRecord> answers,
            List<MdnsRecord> authorityRecords,
            List<MdnsRecord> additionalRecords) {
        this.flags = flags;
        this.questions = Collections.unmodifiableList(questions);
        this.answers = Collections.unmodifiableList(answers);
        this.authorityRecords = Collections.unmodifiableList(authorityRecords);
        this.additionalRecords = Collections.unmodifiableList(additionalRecords);
    }
}
