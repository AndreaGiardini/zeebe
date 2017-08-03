/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.task.data;

import io.zeebe.msgpack.UnpackedObject;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import io.zeebe.msgpack.property.BinaryProperty;
import io.zeebe.msgpack.property.EnumProperty;
import io.zeebe.msgpack.property.IntegerProperty;
import io.zeebe.msgpack.property.LongProperty;
import io.zeebe.msgpack.property.ObjectProperty;
import io.zeebe.msgpack.property.StringProperty;
import io.zeebe.msgpack.spec.MsgPackHelper;
import io.zeebe.protocol.Protocol;

public class TaskEvent extends UnpackedObject
{
    protected static final DirectBuffer NO_PAYLOAD = new UnsafeBuffer(MsgPackHelper.NIL);

    private final EnumProperty<TaskState> stateProp = new EnumProperty<>("state", TaskState.class);
    private final LongProperty lockTimeProp = new LongProperty("lockTime", Protocol.INSTANT_NULL_VALUE);
    private final StringProperty lockOwnerProp = new StringProperty("lockOwner", "");
    private final IntegerProperty retriesProp = new IntegerProperty("retries", -1);
    private final StringProperty typeProp = new StringProperty("type");
    private final ObjectProperty<TaskHeaders> headersProp = new ObjectProperty<>("headers", new TaskHeaders());
    private final BinaryProperty payloadProp = new BinaryProperty("payload", NO_PAYLOAD);

    public TaskEvent()
    {
        this.declareProperty(stateProp)
            .declareProperty(lockTimeProp)
            .declareProperty(lockOwnerProp)
            .declareProperty(retriesProp)
            .declareProperty(typeProp)
            .declareProperty(headersProp)
            .declareProperty(payloadProp);
    }

    public TaskState getState()
    {
        return stateProp.getValue();
    }

    public TaskEvent setState(TaskState type)
    {
        stateProp.setValue(type);
        return this;
    }

    public long getLockTime()
    {
        return lockTimeProp.getValue();
    }

    public TaskEvent setLockTime(long val)
    {
        lockTimeProp.setValue(val);
        return this;
    }

    public DirectBuffer getLockOwner()
    {
        return lockOwnerProp.getValue();
    }

    public TaskEvent setLockOwner(DirectBuffer lockOwer)
    {
        return setLockOwner(lockOwer, 0, lockOwer.capacity());
    }

    public TaskEvent setLockOwner(DirectBuffer lockOwer, int offset, int length)
    {
        lockOwnerProp.setValue(lockOwer, offset, length);
        return this;
    }

    public int getRetries()
    {
        return retriesProp.getValue();
    }

    public TaskEvent setRetries(int retries)
    {
        retriesProp.setValue(retries);
        return this;
    }

    public DirectBuffer getType()
    {
        return typeProp.getValue();
    }

    public TaskEvent setType(DirectBuffer buf)
    {
        return setType(buf, 0, buf.capacity());
    }

    public TaskEvent setType(DirectBuffer buf, int offset, int length)
    {
        typeProp.setValue(buf, offset, length);
        return this;
    }

    public DirectBuffer getPayload()
    {
        return payloadProp.getValue();
    }

    public TaskEvent setPayload(DirectBuffer payload)
    {
        payloadProp.setValue(payload);
        return this;
    }

    public TaskEvent setPayload(DirectBuffer payload, int offset, int length)
    {
        payloadProp.setValue(payload, offset, length);
        return this;
    }

    public TaskHeaders headers()
    {
        return headersProp.getValue();
    }

}

