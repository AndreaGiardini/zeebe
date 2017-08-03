/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client.event.impl;

import io.zeebe.client.event.TopicEvent;
import io.zeebe.client.event.TopicEventType;
import io.zeebe.client.task.impl.subscription.MsgPackField;

public class TopicEventImpl extends EventImpl implements TopicEvent
{

    protected final MsgPackField content = new MsgPackField();

    public TopicEventImpl(
            final String topicName,
            final int partitionId,
            final long key,
            final long position,
            final TopicEventType eventType,
            final byte[] rawContent)
    {
        super(eventType, null);
        this.setKey(key);
        this.setEventPosition(position);
        this.setTopicName(topicName);
        this.setPartitionId(partitionId);
        this.content.setMsgPack(rawContent);
    }


    @Override
    public String getJson()
    {
        return content.getAsJson();
    }

    public byte[] getAsMsgPack()
    {
        return content.getMsgPack();
    }

    @Override
    public String toString()
    {
        return "TopicEventImpl [metadata=" + metadata + ", content=" + content.getAsJson() + "]";
    }


    @Override
    public String getState()
    {
        // see https://github.com/camunda-zeebe/zeebe/issues/367 to avoid extracting this from msgpack payload
        throw new RuntimeException("not implemented");
    }

}
