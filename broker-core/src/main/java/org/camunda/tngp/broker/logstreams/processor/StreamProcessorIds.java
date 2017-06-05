/* Licensed under the Apache License, Version 2.0 (the "License");
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
package org.camunda.tngp.broker.logstreams.processor;

public class StreamProcessorIds
{
    // a stream processor partitionId should be unique to distinguish event producers

    public static final int TASK_QUEUE_STREAM_PROCESSOR_ID = 10;

    public static final int TASK_LOCK_STREAM_PROCESSOR_ID = 20;

    public static final int TASK_EXPIRE_LOCK_STREAM_PROCESSOR_ID = 30;

    public static final int TOPIC_SUBSCRIPTION_PUSH_PROCESSOR_ID = 40;

    public static final int TOPIC_SUBSCRIPTION_MANAGEMENT_PROCESSOR_ID = 50;

    public static final int DEPLOYMENT_PROCESSOR_ID = 60;

    public static final int WORKFLOW_INSTANCE_PROCESSOR_ID = 70;

    public static final int INCIDENT_PROCESSOR_ID = 80;

    public static final int TOPIC_MANAGEMENT_PROCESSOR_ID = 90;
}
