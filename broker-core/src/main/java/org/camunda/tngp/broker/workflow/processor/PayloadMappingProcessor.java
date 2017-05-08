package org.camunda.tngp.broker.workflow.processor;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.camunda.tngp.broker.workflow.graph.model.metadata.Mapping;
import org.camunda.tngp.msgpack.jsonpath.JsonPathQuery;
import org.camunda.tngp.msgpack.query.MsgPackQueryExecutor;
import org.camunda.tngp.msgpack.query.MsgPackTokenVisitor;
import org.camunda.tngp.msgpack.query.MsgPackTraverser;
import org.camunda.tngp.msgpack.spec.MsgPackToken;
import org.camunda.tngp.msgpack.spec.MsgPackType;
import org.camunda.tngp.msgpack.spec.MsgPackWriter;
import org.camunda.tngp.util.buffer.BufferUtil;

/**
 * Represents an processor, which executes/process the given mapping on the given payload.
 *
 * Uses a internal data structure to rewrite/merge the payload.
 *
 * There exist two methods to process the mappings {@link #mergePayloads(Mapping[], DirectBuffer, DirectBuffer)} and
 * {@link #extractPayload(Mapping[], DirectBuffer)}.
 * The first one merges two buffers with the help of the given mapping into one. The second method extract
 * content of the given buffer with help of the given mapping and writes the result into a buffer.
 * The result is stored into a buffer, which is available via {@link #getResultBuffer()}.
 */
public class PayloadMappingProcessor
{
    /**
     * The maximum JSON key length.
     */
    public static final int MAX_JSON_KEY_LEN = 128;

    /**
     * Nodes of type {@link #TYPE_MAP_NODE} represents usal nodes, which have
     * child nodes.
     */
    private static final short TYPE_MAP_NODE = 1;
    private static final short TYPE_ARRAY_NODE = 2;

    /**
     * Nodes of type {@link #TYPE_EXTRACT_LEAF} represents leafs
     * from the extracting payload. So these leafs can
     * be found in the payload from which the content should be extracted.
     */
    private static final short TYPE_EXTRACT_LEAF = 10;

    /**
     * Nodes of type {@link #TYPE_MERGE_LEAF} represents leafs from the merging payload.
     * So these leafs can be found in the payload in which the payloads
     * should be merged.
     */
    private static final short TYPE_MERGE_LEAF = 11;

    /**
     * The json path separator to split the json path in single tokens.
     */
    private static final String JSON_PATH_SEPARATOR = "\\.";

    // internal data structure
    protected final Map<String, Short> nodeTypeMap; //Bytes2LongHashIndex nodeTypeMap;
    protected final Map<String, Set<String>> nodeChildsMap;
    protected final Map<String, Long>  leafMap; //Bytes2LongHashIndex leafMap;

    // processing related
    protected final MsgPackTraverser traverser = new MsgPackTraverser();
    protected final MsgPackQueryExecutor queryExecutor = new MsgPackQueryExecutor();
    protected final PayloadMergePreprocessor payloadVisitor;

    protected MsgPackWriter msgPackWriter = new MsgPackWriter();
    protected MutableDirectBuffer resultingBuffer;
    protected DirectBuffer nodeName = new UnsafeBuffer(0, 0);

    public DirectBuffer extractingPayload = new UnsafeBuffer(0, 0);
    public DirectBuffer mergingPayload = new UnsafeBuffer(0, 0);

    public PayloadMappingProcessor(int payloadMaxSize)
    {
        nodeTypeMap = new HashMap<>();
        nodeChildsMap = new HashMap<>();
        leafMap = new HashMap<>();
        resultingBuffer = new UnsafeBuffer(new byte[payloadMaxSize]);
        payloadVisitor = new PayloadMergePreprocessor(nodeTypeMap, nodeChildsMap, leafMap);
    }

    /**
     * This method will merge, with help of the given mappings, a source into a given target.
     *
     * The target will be the given targetPayload, which does not mean the result is written in this buffer.
     * The result is after processing available in the resultBuffer, which can be accessed with {@link #getResultBuffer()}.
     * The target is used to determine which content should be available in the result and with help of the mapping
     * the target can be modified. Objects can be added or replaced. Simply this means this method
     * merges the given payloads into the resultBuffer with help of the given mappings.
     *
     *
     * @param mappings the mapping which should be processed
     * @param sourcePayload the payload which is used as source of the mapping
     * @param targetPayload the targetPayload which should be merged with the source payload with help of the mappings
     * @return the resulting length of the message pack
     */
    public int mergePayloads(Mapping[] mappings, DirectBuffer sourcePayload,
                             DirectBuffer targetPayload)
    {
        mergingPayload.wrap(targetPayload);

        traverser.wrap(mergingPayload, 0, mergingPayload.capacity());
        traverser.traverse(payloadVisitor);

        return extractPayload(mappings, sourcePayload);
    }

    /**
     *
     * This method will extract, with help of the given mappings, a payload from the given payload.
     * After processing the mappings the result is available in the resultBuffer,
     * which can be accessed with {@link #getResultBuffer()}.
     *
     * @param mappings the mapping which should be processed
     * @param sourcePayload the payload which is used as source of the mapping
     * @return the resulting length of the message pack
     */
    public int extractPayload(Mapping[] mappings, DirectBuffer sourcePayload)
    {
        extractingPayload.wrap(sourcePayload);

        preprocessMappings(mappings);
        processMapping();

        clear();
        return msgPackWriter.getOffset();
    }

    /**
     * The result buffer which contains the message pack after processing the mappings.
     *
     * @return the result buffer
     */
    public MutableDirectBuffer getResultBuffer()
    {
        return resultingBuffer;
    }

    /**
     * Pre-process the given mappings. Traverse for each mapping the target path
     * and constructs a tree-like structure, which is located in
     * {@link #nodeChildsMap}, {@link #nodeTypeMap} and {@link #leafMap}.
     * The constructed structure is used by the {@link #extractPayload(Mapping[], DirectBuffer)} to
     * write the message pack in the right way.
     *
     * @param mappings the given mappings, which contains the source and target json paths
     */
    private void preprocessMappings(Mapping[] mappings)
    {
        for (Mapping mapping : mappings)
        {
            final String targetPath[] = mapping.getTargetQueryString().split(JSON_PATH_SEPARATOR);

            String parent = null;
            for (int i = 0; i < targetPath.length; i++)
            {
                final short type;
                String currentPath = targetPath[i];
                final String nodeId;
                int indexOfOpeningBracket = currentPath.indexOf('[');
                if (indexOfOpeningBracket != -1)
                {
                    parent = currentPath.substring(0, indexOfOpeningBracket);
                    final String parentId = i + parent;
                    currentPath = currentPath.replace("[", "").replace("]", "");
                    nodeId = (i+1) + currentPath;

                    if (i != 0)
                    {
                        if (nodeChildsMap.get(parentId) == null)
                        {
                            nodeChildsMap.put(parentId, new LinkedHashSet<>());
                        }
                        nodeChildsMap.get(parentId).add(currentPath);
                    }
                    nodeTypeMap.put(nodeId, TYPE_EXTRACT_LEAF);
                    final JsonPathQuery sourceQuery = mapping.getSource();
                    final long leafMapping = executeJsonPathQuery(sourceQuery);
                    leafMap.put(nodeId, leafMapping);
                    nodeTypeMap.put(parentId, TYPE_ARRAY_NODE);
                }
                else
                {
                    nodeId = (i + currentPath);
                    if ((i + 1 == targetPath.length))
                    {
                        type = TYPE_EXTRACT_LEAF;
                        final JsonPathQuery sourceQuery = mapping.getSource();
                        final long leafMapping = executeJsonPathQuery(sourceQuery);
                        leafMap.put(nodeId, leafMapping);
                    }
                    else
                    {
                        type = TYPE_MAP_NODE;
                        if (nodeChildsMap.get(nodeId) == null)
                        {
                            nodeChildsMap.put(nodeId, new HashSet<>());
                        }
                    }

                    if (i != 0)
                    {
                        nodeChildsMap.get(((i - 1) + parent)).add(currentPath);
                    }
                    nodeTypeMap.put(nodeId, type);

                    parent = currentPath;
                }
            }
        }
    }

    /**
     * Executes the given json path query and returns the matching result as long.
     * The first 4 bytes of the result represents the position in the given payload,
     * the last 4 bytes represents the length of the matching value.
     *
     * @param jsonPathQuery the query which should be executed
     * @return the position and length concatenated as long
     */
    private long executeJsonPathQuery(JsonPathQuery jsonPathQuery)
    {
        queryExecutor.init(jsonPathQuery.getFilters(), jsonPathQuery.getFilterInstances());
        traverser.wrap(extractingPayload, 0, extractingPayload.capacity());
        traverser.traverse(queryExecutor);

        final long queryResult;
        if (queryExecutor.numResults() == 1)
        {
            queryExecutor.moveToResult(0);
            queryResult = (((long) queryExecutor.currentResultPosition()) << 32)
                | (queryExecutor.currentResultLength());
        }
        else if (queryExecutor.numResults() == 0)
        {
            final String errorMessage = String.format("No data found for query '%s'.", BufferUtil.bufferAsString(jsonPathQuery.getExpression()));
            throw new PayloadMappingException(errorMessage);
        }
        else
        {
            throw new IllegalStateException("JSON path mapping has more than one matching source.");
        }
        return queryResult;
    }

    /**
     * Processes the mapping after the mappings a preprocessed and
     * the internal data structure is constructed.
     *
     * If the root path is a LEAF it will end in the {@link PayloadMappingProcessor#processLeafMapping(String, Short)}}
     * and writes directly the mapping for the root path.
     *
     * If the root is a NODE, which means it has at least one child it will process the corresponding child nodes
     * and resolved the mapping.
     */
    private void processMapping()
    {
        final int depth = 0;
        final String startNode = Mapping.JSON_ROOT_PATH;
        msgPackWriter.wrap(resultingBuffer, 0);

        processMapping(depth, startNode, false);
    }

    /**
     * Recursive method to process the mapping.
     * The mapping will start with depth = 0 and `$` as nodeName, which represents the root.
     * The depth and the nodeName is equal to the node identifier.
     * With help of the interal data structure it can be determined if the current node
     * is of type NODE or LEAF. If the node is of type NODE the map header will be writen
     * with the size of existing childs. After that the childs are recursivly processed.
     *
     * If the node is of type LEAF the leaf mapping will be resolved and written to the result buffer.
     *
     * @param depth the current depth of the node
     * @param nodeName the name of the node
     */
    private void processMapping(int depth, String nodeName, boolean isArray)
    {
        if (depth != 0 && !isArray)
        {
            this.nodeName.wrap(nodeName.getBytes());
            msgPackWriter.writeString(this.nodeName);
        }

        final String nodeId = depth + nodeName;
        final Short nodeType = nodeTypeMap.get(nodeId);
        if (nodeType == TYPE_MAP_NODE)
        {
            final Set<String> childs = nodeChildsMap.get(nodeId);
            msgPackWriter.writeMapHeader(childs.size());
            for (String child : childs)
            {
                processMapping(depth + 1, child, false);
            }
        }
        else if (nodeType == TYPE_ARRAY_NODE)
        {
            final Set<String> childs = nodeChildsMap.get(nodeId);
            msgPackWriter.writeArrayHeader(childs.size());
            for (String child : childs)
            {
                processMapping(depth + 1, child, true);
            }
        }
        else
        {
            processLeafMapping(nodeId, nodeType);
        }
    }

    /**
     * Process the leaf mapping.
     * Writes from the payload to the result buffer.
     *
     * @param leafId the identifier of the leaf
     * @param nodeType the type of the leaf
     */
    private void processLeafMapping(String leafId, Short nodeType)
    {
        final long mapping = leafMap.get(leafId);
        final int position = (int) (mapping >> 32);
        final int length = (int) mapping;
        DirectBuffer relatedBuffer = extractingPayload;
        if (nodeType == TYPE_MERGE_LEAF)
        {
            relatedBuffer = mergingPayload;
        }
        msgPackWriter.writeRaw(relatedBuffer, position, length);
    }

    /**
     * Tidy up the internal data structure.
     */
    private void clear()
    {
        nodeChildsMap.clear();
        nodeTypeMap.clear();
        leafMap.clear();
        payloadVisitor.clear();
    }

    /**
     * An internal helper to preprocess the given target payload on merge.
     */
    private static final class PayloadMergePreprocessor implements MsgPackTokenVisitor
    {
        /**
         * Holds a reference of the {@link PayloadMappingProcessor#nodeTypeMap}.
         */
        protected final Map<String, Short> nodeTypeMap;

        /**
         * Holds a reference of the {@link PayloadMappingProcessor#nodeChildsMap}.
         */
        protected final Map<String, Set<String>> nodeChildsMap;

        /**
         * Holds a reference of the {@link PayloadMappingProcessor#leafMap}.
         */
        protected final Map<String, Long>  leafMap;

        /**
         * The last key for the node, since
         * the node is divided in separate MsgPackTokens.
         */
        protected final byte lastKey[] = new byte[MAX_JSON_KEY_LEN];

        /**
         * The length of the last key.
         */
        protected int lastKeyLen;

        /**
         * Indicates if the next MsgPackToken is a value.
         */
        protected boolean nextIsValue;

        /**
         *  Contains the current parents of the current node.
         *  A node become a parent if the node is of type MAP.
         *  This node will be added several times, coresponding to the size of the MsgPackToken#size of this node.
         */
        private Deque<String> parentsStack = new ArrayDeque<>();

        /**
         * Contains the current depth of the current node.
         */
        private Deque<Integer> childDepthCount = new ArrayDeque<>();

        protected boolean isArrayValue = false;
        protected final AtomicLong arraySize = new AtomicLong();
        protected final AtomicLong currentIndex = new AtomicLong();

        protected PayloadMergePreprocessor(Map<String, Short> nodeTypeMap, Map<String, Set<String>> nodeChildsMap, Map<String, Long> leafMap)
        {
            this.nodeTypeMap = nodeTypeMap;
            this.nodeChildsMap = nodeChildsMap;
            this.leafMap = leafMap;

            lastKey[0] = '$';
            lastKeyLen = 1;
            nextIsValue = true;
            childDepthCount.push(0);
        }

        @Override
        public void visitElement(int position, MsgPackToken currentValue)
        {
            final MsgPackType currentValueType = currentValue.getType();
            if (currentValueType == MsgPackType.MAP)
            {
                processObjectNode(currentValue);
            }
            else if (currentValueType == MsgPackType.ARRAY)
            {
                processArrayNode(currentValue);
            }
            else if (currentValueType == MsgPackType.STRING && !nextIsValue)
            {
                final DirectBuffer valueBuffer = currentValue.getValueBuffer();
                lastKeyLen = valueBuffer.capacity();
                valueBuffer.getBytes(0, lastKey, 0, lastKeyLen);
                nextIsValue = true;
            }
            else if (nextIsValue)
            {
                processValueNode(position, currentValue);
            }
        }

        private void processArrayNode(MsgPackToken currentValue)
        {
            // set size of array as atomic long
            final String nodeName = getNodeName(lastKey, lastKeyLen);
            final int depth = childDepthCount.pop();
            final String nodeId = depth + nodeName;
            nodeTypeMap.put(nodeId, TYPE_ARRAY_NODE);
            nodeChildsMap.put(nodeId, new LinkedHashSet<>());

            if (!parentsStack.isEmpty())
            {
                final String parentId = parentsStack.pop();
                nodeChildsMap.get(parentId).add(nodeName);
            }

            arraySize.set(currentValue.getSize());
            currentIndex.set(0);
            for (int i = 0; i < currentValue.getSize(); i++)
            {
                parentsStack.push(nodeId);
                childDepthCount.push(depth + 1);
            }
            // no keys in arrays
            nextIsValue = true;
            isArrayValue = true;
        }

        /**
         * The current node is a simple value and the currentValue reference to a token
         * of another type except MAP.
         *
         * This method process the value node and inserts the node properties into the
         * internal data structure. It will add the nodeType ( type = leaf), pops a
         * parent of the stack and add this node to his child list. Also the leaf mapping will be
         * created and inserted.
         *
         *
         * @param position the position of the token in the payload
         * @param currentValue the message pack token which represents a simple value
         */
        private void processValueNode(long position, MsgPackToken currentValue)
        {
            final String nodeName;
            if (isArrayValue)
            {
                final long index = currentIndex.getAndIncrement();
                nodeName = getNodeName(lastKey, lastKeyLen) + index;
                if (index >= arraySize.get() - 1)
                {
                    isArrayValue = false;
                    nextIsValue = false;
                }
            }
            else
            {
                nodeName = getNodeName(lastKey, lastKeyLen);
                nextIsValue = false;
            }

            final int depth = childDepthCount.pop();
            final String nodeId = depth + nodeName;
            nodeTypeMap.put(nodeId, TYPE_MERGE_LEAF);

            final String parentId = parentsStack.pop();
            nodeChildsMap.get(parentId).add(nodeName);

            final long mapping = (position << 32)
                | (currentValue.getTotalLength());
            leafMap.put(nodeId, mapping);
        }

        /**
         * The current node is a map and the currentValue references the MAP token.
         * The string value which identifies the node was read before.
         *
         * This method process the object node and inserts the node properties
         * into the internal data structure. It will add the nodeType (type = node), create
         * a new entry in the node childs map with a empty list, since this node represents a new parent node.
         *
         * If necessary it will add this node to his parent child list and pops a parent from the stack.
         * It will also push the current node identifier several times to the parent stack.
         * The count is related to the child count which is equal to the MsgPackToken#size if the current token is of
         * type MAP.
         *
         * Also the depth will be incremented since we go on the next tokens a layer deeper.
         * The
         *
         * @param currentValue the message pack token which represents a map
         */
        private void processObjectNode(MsgPackToken currentValue)
        {
            final String nodeName = getNodeName(lastKey, lastKeyLen);
            final int depth = childDepthCount.pop();
            final String nodeId = depth + nodeName;
            nodeTypeMap.put(nodeId, TYPE_MAP_NODE);
            nodeChildsMap.put(nodeId, new HashSet<>());

            if (!parentsStack.isEmpty())
            {
                final String parentId = parentsStack.pop();
                nodeChildsMap.get(parentId).add(nodeName);
            }

            for (int i = 0; i < currentValue.getSize(); i++)
            {
                parentsStack.push(nodeId);
                childDepthCount.push(depth + 1);
            }
            nextIsValue = false;
        }

        /**
         * Clears the preprocessor and resets to the inital state.
         */
        public void clear()
        {
            lastKey[0] = '$';
            lastKeyLen = 1;
            nextIsValue = true;
            childDepthCount.push(0);

            parentsStack.clear();
        }

        /**
         * Returns the node name.
         * @param bytes the bytes which contains the name
         * @param length the length of the name
         * @return the name as string
         */
        private String getNodeName(byte[] bytes, int length)
        {
            final byte nameBytes[] = Arrays.copyOf(bytes, length);
            return new String(nameBytes);
        }
    }
}
