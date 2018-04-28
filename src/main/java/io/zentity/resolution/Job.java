package io.zentity.resolution;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zentity.common.Json;
import io.zentity.model.Matcher;
import io.zentity.model.Model;
import io.zentity.model.ValidationException;
import io.zentity.resolution.input.Attribute;
import io.zentity.resolution.input.Input;
import io.zentity.resolution.input.value.Value;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class Job {

    // Constants
    public static final boolean DEFAULT_INCLUDE_ATTRIBUTES = true;
    public static final boolean DEFAULT_INCLUDE_HITS = true;
    public static final boolean DEFAULT_INCLUDE_QUERIES = false;
    public static final boolean DEFAULT_INCLUDE_SOURCE = true;
    public static final int DEFAULT_MAX_DOCS_PER_QUERY = 1000;
    public static final int DEFAULT_MAX_HOPS = 100;
    public static final boolean DEFAULT_PRETTY = false;
    public static final boolean DEFAULT_PROFILE = false;
    private final JsonStringEncoder encoder = new JsonStringEncoder();

    // Job configuration
    private Input input;
    private boolean includeAttributes = DEFAULT_INCLUDE_ATTRIBUTES;
    private boolean includeHits = DEFAULT_INCLUDE_QUERIES;
    private boolean includeQueries = DEFAULT_INCLUDE_QUERIES;
    private boolean includeSource = DEFAULT_INCLUDE_SOURCE;
    private int maxDocsPerQuery = DEFAULT_MAX_DOCS_PER_QUERY;
    private int maxHops = DEFAULT_MAX_HOPS;
    private boolean pretty = DEFAULT_PRETTY;
    private boolean profile = DEFAULT_PROFILE;

    // Job state
    private Map<String, Attribute> attributes = new TreeMap<>();
    private NodeClient client;
    private Map<String, Set<String>> docIds = new TreeMap<>();
    private List<String> hits = new ArrayList<>();
    private int hop = 0;
    private List<String> queries = new ArrayList<>();
    private boolean ran = false;

    public Job(NodeClient client) {
        this.client = client;
    }

    /**
     * Determine if a field of an index has a matcher associated with that field.
     *
     * @param model          The entity model.
     * @param indexName      The name of the index to reference in the entity model.
     * @param indexFieldName The name of the index field to reference in the index.
     * @return Boolean decision.
     */
    public static boolean indexFieldHasMatcher(Model model, String indexName, String indexFieldName) {
        String matcherName = model.indices().get(indexName).fields().get(indexFieldName).matcher();
        if (matcherName == null)
            return false;
        if (model.matchers().get(matcherName) == null)
            return false;
        return true;
    }

    /**
     * Determine if we can construct a query for a given resolver on a given index with a given input.
     * Each attribute of the resolver must be mapped to a field of the index and have a matcher defined for it.
     *
     * @param model        The entity model.
     * @param indexName    The name of the index to reference in the entity model.
     * @param resolverName The name of the resolver to reference in the entity model.
     * @param attributes   The values for the input attributes.
     * @return Boolean decision.
     */
    public static boolean canQuery(Model model, String indexName, String resolverName, Map<String, Attribute> attributes) {

        // Each attribute of the resolver must pass these conditions:
        for (String attributeName : model.resolvers().get(resolverName).attributes()) {

            // The input must have the attribute.
            if (!attributes.containsKey(attributeName))
                return false;

            // The input must have at least one value for the attribute.
            if (attributes.get(attributeName).values().isEmpty())
                return false;

            // The index must have at least one index field mapped to the attribute.
            if (!model.indices().get(indexName).attributeIndexFieldsMap().containsKey(attributeName))
                return false;
            if (model.indices().get(indexName).attributeIndexFieldsMap().get(attributeName).isEmpty())
                return false;

            // The index field must have a matcher defined for it.
            boolean hasMatcher = false;
            for (String indexFieldName : model.indices().get(indexName).attributeIndexFieldsMap().get(attributeName).keySet()) {
                if (indexFieldHasMatcher(model, indexName, indexFieldName)) {
                    hasMatcher = true;
                    break;
                }
            }
            if (!hasMatcher)
                return false;
        }
        return true;
    }

    /**
     * Given a clause from the "matchers" field of an entity model, replace the {{ field }} and {{ value }} variables
     * and arbitrary parameters. If a parameter exists, the value
     *
     * @param matcher        The matcher object.
     * @param indexFieldName The name of the index field to populate in the clause.
     * @param value          The value to populate in the clause.
     * @param attribute      The attribute object.
     * @return A "bool" clause that references the desired field and value.
     */
    public static String populateMatcherClause(Matcher matcher, String indexFieldName, String value, Attribute attribute) throws ValidationException {
        String matcherClause = matcher.clause();
        for (String variable : matcher.variables().keySet()) {
            Pattern pattern = matcher.variables().get(variable);
            switch (variable) {
                case "field":
                    matcherClause = pattern.matcher(matcherClause).replaceAll(indexFieldName);
                    break;
                case "value":
                    matcherClause = pattern.matcher(matcherClause).replaceAll(value);
                    break;
                default:
                    String paramValue;
                    if (attribute.params().containsKey(variable))
                        paramValue = attribute.params().get(variable);
                    else if (matcher.params().containsKey(variable))
                        paramValue = matcher.params().get(variable);
                    else
                        throw new ValidationException("'matchers." + matcher.name() + "' was given no value for '{{ " + variable + " }}'");
                    matcherClause = pattern.matcher(matcherClause).replaceAll(paramValue);
                    break;
            }
        }
        return matcherClause;
    }

    /**
     * Given an entity model, an index name, a set of attribute values, and an attribute name,
     * find all index field names that are mapped to the attribute name and populate their matcher clauses.
     *
     * @param model         The entity model.
     * @param indexName     The name of the index to reference in the entity model.
     * @param attributes    The names and values of the input attributes.
     * @param attributeName The name of the attribute to reference in the attributeSet.
     * @param combiner      Combine clauses with "should" or "filter".
     * @return
     */
    public static List<String> makeIndexFieldClauses(Model model, String indexName, Map<String, Attribute> attributes, String attributeName, String combiner) throws ValidationException {
        if (!combiner.equals("should") && !combiner.equals("filter"))
            throw new ValidationException("'" + combiner + "' is not a supported clause combiner.");
        List<String> indexFieldClauses = new ArrayList<>();
        for (String indexFieldName : model.indices().get(indexName).attributeIndexFieldsMap().get(attributeName).keySet()) {

            // Can we use this index field?
            if (!indexFieldHasMatcher(model, indexName, indexFieldName))
                continue;

            // Construct a clause for each input value for this attribute.
            List<String> valueClauses = new ArrayList<>();
            Attribute attribute = attributes.get(attributeName);
            for (Value value : attribute.values()) {

                // Skip value if it's blank.
                if (value.serialized() == null || value.serialized().equals(""))
                    continue;

                // Populate the {{ field }} and {{ value }} variables of the matcher template.
                String matcherName = model.indices().get(indexName).fields().get(indexFieldName).matcher();
                Matcher matcher = model.matchers().get(matcherName);
                valueClauses.add(populateMatcherClause(matcher, indexFieldName, value.serialized(), attribute));
            }
            if (valueClauses.size() == 0)
                continue;

            // Combine each value clause into a single "should" or "filter" clause.
            String valuesClause = String.join(",", valueClauses);
            if (valueClauses.size() > 1)
                valuesClause = "{\"bool\":{\"" + combiner + "\":[" + valuesClause + "]}}";
            indexFieldClauses.add(valuesClause);
        }
        return indexFieldClauses;
    }

    /**
     * Given an entity model, an index name, and a set of attribute values,
     * for each attribute name in the set of attributes, find all index field names that are mapped to the attribute
     * name and populate their matcher clauses.
     *
     * @param model      The entity model.
     * @param indexName  The name of the index to reference in the entity model.
     * @param attributes The names and values of the input attributes.
     * @param combiner   Combine clauses with "should" or "filter".
     * @return
     */
    public static List<String> makeAttributeClauses(Model model, String indexName, Map<String, Attribute> attributes, String combiner) throws ValidationException {
        if (!combiner.equals("should") && !combiner.equals("filter"))
            throw new ValidationException("'" + combiner + "' is not a supported clause combiner.");
        List<String> attributeClauses = new ArrayList<>();
        for (String attributeName : attributes.keySet()) {

            // Construct a "should" or "filter" clause for each index field mapped to this attribute.
            List<String> indexFieldClauses = makeIndexFieldClauses(model, indexName, attributes, attributeName, combiner);
            if (indexFieldClauses.size() == 0)
                continue;

            // Combine each matcher clause into a single "should" or "filter" clause.
            String indexFieldsClause = String.join(",", indexFieldClauses);
            if (indexFieldClauses.size() > 1)
                indexFieldsClause = "{\"bool\":{\"" + combiner + "\":[" + indexFieldsClause + "]}}";
            attributeClauses.add(indexFieldsClause);
        }
        return attributeClauses;
    }

    /**
     * Populate the field names and values of the resolver clause of a query.
     *
     * @param model               The entity model.
     * @param indexName           The name of the index to reference in the entity model.
     * @param resolversFilterTree The filter tree for the resolvers to be queried.
     * @param attributes          The names and values for the input attributes.
     * @return A "bool" clause for all applicable resolvers.
     */
    public static String populateResolversFilterTree(Model model, String indexName, TreeMap<String, TreeMap> resolversFilterTree, Map<String, Attribute> attributes) throws ValidationException {

        // Construct a "filter" clause for each attribute at this level of the filter tree.
        List<String> attributeClauses = new ArrayList<>();
        for (String attributeName : resolversFilterTree.keySet()) {

            // Construct a "should" clause for each index field mapped to this attribute.
            List<String> indexFieldClauses = makeIndexFieldClauses(model, indexName, attributes, attributeName, "should");
            if (indexFieldClauses.size() == 0)
                continue;

            // Combine each matcher clause into a single "should" clause.
            String indexFieldsClause = String.join(",", indexFieldClauses);
            if (indexFieldClauses.size() > 1)
                indexFieldsClause = "{\"bool\":{\"should\":[" + indexFieldsClause + "]}}";

            // Populate any child filters.
            String filter = populateResolversFilterTree(model, indexName, resolversFilterTree.get(attributeName), attributes);
            if (!filter.equals("{}"))
                attributeClauses.add("{\"bool\":{\"filter\":[" + indexFieldsClause + "," + filter + "]}}");
            else
                attributeClauses.add(indexFieldsClause);

        }

        // Combine each attribute clause into a single "should" clause.
        int size = attributeClauses.size();
        if (size > 1)
            return "{\"bool\":{\"should\":[" + String.join(",", attributeClauses) + "]}}";
        else if (size == 1)
            return "{\"bool\":{\"filter\":" + attributeClauses.get(0) + "}}";
        else
            return "{}";
    }

    /**
     * Reorganize the attributes of all resolvers into a tree of Maps.
     *
     * @param resolversSorted The attributes for each resolver. Attributes are sorted first by priority and then lexicographically.
     * @return The attributes of all applicable resolvers nested in a tree.
     */
    public static TreeMap<String, TreeMap> makeResolversFilterTree(List<List<String>> resolversSorted) {
        TreeMap<String, TreeMap> filterTree = new TreeMap<>();
        filterTree.put("root", new TreeMap<>());
        for (List<String> resolverSorted : resolversSorted) {
            TreeMap<String, TreeMap> current = filterTree.get("root");
            for (String attributeName : resolverSorted) {
                if (!current.containsKey(attributeName))
                    current.put(attributeName, new TreeMap<String, TreeMap>());
                current = current.get(attributeName);
            }
        }
        return filterTree.get("root");
    }

    /**
     * Sort the attributes of each resolver in descending order by how many resolvers each attribute appears in,
     * and secondarily in ascending order by the name of the attribute.
     *
     * @param model     The entity model.
     * @param resolvers The names of the resolvers.
     * @param counts    For each attribute, the number of resolvers it appears in.
     * @return For each resolver, a list of attributes sorted first by priority and then lexicographically.
     */
    public static List<List<String>> sortResolverAttributes(Model model, List<String> resolvers, Map<String, Integer> counts) {
        List<List<String>> resolversSorted = new ArrayList<>();
        for (String resolverName : resolvers) {
            List<String> resolverSorted = new ArrayList<>();
            Map<Integer, TreeSet<String>> attributeGroups = new TreeMap<>();
            for (String attributeName : model.resolvers().get(resolverName).attributes()) {
                int count = counts.get(attributeName);
                if (!attributeGroups.containsKey(count))
                    attributeGroups.put(count, new TreeSet<>());
                attributeGroups.get(count).add(attributeName);
            }
            TreeSet<Integer> countsKeys = new TreeSet<>(Collections.reverseOrder());
            countsKeys.addAll(attributeGroups.keySet());
            for (int count : countsKeys)
                for (String attributeName : attributeGroups.get(count))
                    resolverSorted.add(attributeName);
            resolversSorted.add(resolverSorted);
        }
        return resolversSorted;
    }

    /**
     * Count how many resolvers each attribute appears in.
     * Attributes that appear in more resolvers should be higher in the query tree.
     *
     * @param model     The entity model.
     * @param resolvers The names of the resolvers to reference in the entity model.
     * @return For each attribute, the number of resolvers it appears in.
     */
    public static Map<String, Integer> countAttributesAcrossResolvers(Model model, List<String> resolvers) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String resolverName : resolvers)
            for (String attributeName : model.resolvers().get(resolverName).attributes())
                counts.put(attributeName, counts.getOrDefault(attributeName, 0) + 1);
        return counts;
    }

    /**
     * Resets the variables that hold the state of the job, in case the same Job object is reused.
     */
    private void resetState() {
        this.attributes = new TreeMap<>(this.input().attributes());
        this.docIds = new TreeMap<>();
        this.hits = new ArrayList<>();
        this.hop = 0;
        this.queries = new ArrayList<>();
        this.ran = false;
    }

    public boolean includeAttributes() {
        return this.includeAttributes;
    }

    public void includeAttributes(boolean includeAttributes) {
        this.includeAttributes = includeAttributes;
    }

    public boolean includeHits() {
        return this.includeHits;
    }

    public void includeHits(boolean includeHits) {
        this.includeHits = includeHits;
    }

    public boolean includeQueries() {
        return this.includeQueries;
    }

    public void includeQueries(boolean includeQueries) {
        this.includeQueries = includeQueries;
    }

    public boolean includeSource() {
        return this.includeSource;
    }

    public void includeSource(boolean includeSource) {
        this.includeSource = includeSource;
    }

    public int maxHops() {
        return this.maxHops;
    }

    public void maxHops(int maxHops) {
        this.maxHops = maxHops;
    }

    public int maxDocsPerQuery() {
        return this.maxDocsPerQuery;
    }

    public void maxDocsPerQuery(int maxDocsPerQuery) {
        this.maxDocsPerQuery = maxDocsPerQuery;
    }

    public boolean pretty() {
        return this.pretty;
    }

    public void pretty(boolean pretty) {
        this.pretty = pretty;
    }

    public Boolean profile() {
        return this.profile;
    }

    public void profile(Boolean profile) {
        this.profile = profile;
    }

    public Input input() {
        return this.input;
    }

    public void input(Input input) {
        this.input = input;
    }

    private String jsonStringEscape(String value) {
        return new String(encoder.quoteAsString(value));
    }

    private String jsonStringQuote(String value) {
        return "\"" + value + "\"";
    }

    private String jsonStringFormat(String value) {
        return jsonStringQuote(jsonStringEscape(value));
    }

    /**
     * Submit a search query to Elasticsearch.
     *
     * @param indexName The name of the index to search.
     * @param query     The query to search.
     * @return The search response returned by Elasticsearch.
     * @throws IOException
     */
    private SearchResponse search(String indexName, String query) throws IOException {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        SearchModule searchModule = new SearchModule(Settings.EMPTY, false, Collections.emptyList());
        try (XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(new NamedXContentRegistry(searchModule
                .getNamedXContents()), query)) {
            searchSourceBuilder.parseXContent(parser);
        }
        SearchRequestBuilder searchRequestBuilder = new SearchRequestBuilder(client, SearchAction.INSTANCE);
        return searchRequestBuilder.setIndices(indexName).setSource(searchSourceBuilder).execute().actionGet();
    }

    /**
     * Given a set of attribute values, determine which queries to submit to which indices then submit them and recurse.
     *
     * @throws IOException
     * @throws ValidationException
     */
    private void traverse() throws IOException, ValidationException {

        // Prepare to collect attributes from the results of these queries as the inputs to subsequent queries.
        Map<String, Attribute> nextInputAttributes = new TreeMap<>();
        Boolean newHits = false;

        // Construct a query for each index that maps to a resolver.
        for (String indexName : this.input.model().indices().keySet()) {

            // Track _ids for this index.
            if (!this.docIds.containsKey(indexName))
                this.docIds.put(indexName, new TreeSet<>());

            // Determine which resolvers can be queried for this index.
            List<String> resolvers = new ArrayList<>();
            for (String resolverName : this.input.model().resolvers().keySet())
                if (canQuery(this.input.model(), indexName, resolverName, this.attributes))
                    resolvers.add(resolverName);
            if (resolvers.size() == 0)
                continue;

            // Construct query for this index.
            String query;
            String queryClause = "{}";
            List<String> queryClauses = new ArrayList<>();
            List<String> queryMustNotClauses = new ArrayList<>();
            List<String> queryFilterClauses = new ArrayList<>();

            // Exclude docs by _id
            Set<String> ids = this.docIds.get(indexName);
            if (!ids.isEmpty())
                queryMustNotClauses.add("{\"ids\":{\"values\":[" + String.join(",", ids) + "]}}");

            // Create "scope.exclude.attributes" clauses. Combine them into a single "should" clause.
            if (!this.input.scope().exclude().attributes().isEmpty()) {
                List<String> attributeClauses = makeAttributeClauses(this.input.model(), indexName, this.input.scope().exclude().attributes(), "should");
                int size = attributeClauses.size();
                if (size > 1)
                    queryMustNotClauses.add("{\"bool\":{\"should\":[" + String.join(",", attributeClauses) + "]}}");
                else if (size == 1)
                    queryMustNotClauses.add(attributeClauses.get(0));
            }

            // Construct the top-level "must_not" clause.
            if (!queryMustNotClauses.isEmpty())
                queryClauses.add("\"must_not\":[" + String.join(",", queryMustNotClauses) + "]");

            // Create "scope.include.attributes" clauses. Combine them into a single "filter" clause.
            if (!this.input.scope().include().attributes().isEmpty()) {
                List<String> attributeClauses = makeAttributeClauses(this.input.model(), indexName, this.input.scope().include().attributes(), "filter");
                int size = attributeClauses.size();
                if (size > 1)
                    queryFilterClauses.add("{\"bool\":{\"filter\":[" + String.join(",", attributeClauses) + "]}}");
                else if (size == 1)
                    queryFilterClauses.add(attributeClauses.get(0));
            }

            // Construct the resolvers clause.
            Map<String, Integer> counts = countAttributesAcrossResolvers(this.input.model(), resolvers);
            List<List<String>> resolversSorted = sortResolverAttributes(this.input.model(), resolvers, counts);
            TreeMap<String, TreeMap> resolversFilterTree = makeResolversFilterTree(resolversSorted);
            String resolversClause = populateResolversFilterTree(this.input.model(), indexName, resolversFilterTree, this.attributes);
            queryFilterClauses.add(resolversClause);

            // Construct the top-level "filter" clause.
            if (!queryFilterClauses.isEmpty()) {
                if (queryFilterClauses.size() > 1)
                    queryClauses.add("\"filter\":[" + String.join(",", queryFilterClauses) + "]");
                else
                    queryClauses.add("\"filter\":" + queryFilterClauses.get(0));
            }

            // Construct the "query" clause.
            if (!queryClauses.isEmpty())
                queryClause = "{\"bool\":{" + String.join(",", queryClauses) + "}}";

            // Construct the final query.
            if (this.profile)
                query = "{\"query\":" + queryClause + ",\"size\": " + this.maxDocsPerQuery + ",\"profile\":true}";
            else
                query = "{\"query\":" + queryClause + ",\"size\": " + this.maxDocsPerQuery + "}";

            // Submit query to Elasticsearch.
            SearchResponse response = this.search(indexName, query);

            // Read response from Elasticsearch.
            String responseBody = response.toString();
            JsonNode responseData = Json.ORDERED_MAPPER.readTree(responseBody);

            // Log queries.
            if (this.includeQueries || this.profile) {
                JsonNode responseDataCopy = responseData.deepCopy();
                ObjectNode responseDataCopyObj = (ObjectNode) responseDataCopy;
                if (responseDataCopyObj.has("hits")) {
                    ObjectNode responseDataCopyObjHits = (ObjectNode) responseDataCopyObj.get("hits");
                    if (responseDataCopyObjHits.has("hits"))
                        responseDataCopyObjHits.remove("hits");
                }
                String resolversListLogged = Json.ORDERED_MAPPER.writeValueAsString(resolvers);
                String resolversFilterTreeLogged = Json.ORDERED_MAPPER.writeValueAsString(resolversFilterTree);
                String resolversLogged = "{\"list\":" + resolversListLogged + ",\"tree\":" + resolversFilterTreeLogged + "}";
                String searchLogged = "{\"request\":" + query + ",\"response\":" + responseDataCopyObj + "}";
                String logged = "{\"_hop\":" + this.hop + ",\"_index\":\"" + indexName + "\",\"resolvers\":" + resolversLogged + ",\"search\":" + searchLogged + "}";
                this.queries.add(logged);
            }

            // Read the hits
            if (!responseData.has("hits"))
                continue;
            if (!responseData.get("hits").has("hits"))
                continue;
            for (JsonNode doc : responseData.get("hits").get("hits")) {

                // Skip doc if already fetched. Otherwise mark doc as fetched and then proceed.
                String _id = jsonStringFormat(doc.get("_id").textValue());
                if (this.docIds.get(indexName).contains(_id))
                    continue;
                this.docIds.get(indexName).add(_id);

                // Gather attributes from the doc. Store them in the "_attributes" field of the doc,
                // and include them in the attributes for subsequent queries.
                TreeMap<String, JsonNode> docAttributes = new TreeMap<>();
                for (String indexFieldName : this.input.model().indices().get(indexName).fields().keySet()) {
                    String attributeName = this.input.model().indices().get(indexName).fields().get(indexFieldName).attribute();
                    if (this.input.model().attributes().get(attributeName) == null)
                        continue;
                    String attributeType = this.input.model().attributes().get(attributeName).type();
                    if (!nextInputAttributes.containsKey(attributeName))
                        nextInputAttributes.put(attributeName, new Attribute(attributeName, attributeType));
                    // The index field name might not refer to the _source property.
                    // If it's not in the _source, remove the last part of the index field name from the dot notation.
                    // Index field names can reference multi-fields, which are not returned in the _source.
                    String path = this.input.model().indices().get(indexName).fields().get(indexFieldName).path();
                    String pathParent = this.input.model().indices().get(indexName).fields().get(indexFieldName).pathParent();
                    JsonNode valueNode = doc.get("_source").at(path);
                    if (valueNode.isMissingNode())
                        valueNode = doc.get("_source").at(pathParent);
                    if (valueNode.isMissingNode())
                        continue;
                    docAttributes.put(attributeName, valueNode);
                    Value value = Value.create(attributeType, valueNode);
                    nextInputAttributes.get(attributeName).values().add(value);
                }

                // Modify doc metadata.
                if (this.includeHits) {
                    ObjectNode docObjNode = (ObjectNode) doc;
                    docObjNode.remove("_score");
                    docObjNode.put("_hop", this.hop);
                    if (this.includeAttributes) {
                        ObjectNode docAttributesObjNode = docObjNode.putObject("_attributes");
                        for (String attributeName : docAttributes.keySet()) {
                            JsonNode values = docAttributes.get(attributeName);
                            docAttributesObjNode.set(attributeName, values);
                        }
                    }
                    if (!this.includeSource) {
                        docObjNode.remove("_source");
                    } else {
                        // Move _source under _attributes
                        JsonNode _sourceNode = docObjNode.get("_source");
                        docObjNode.remove("_source");
                        docObjNode.set("_source", _sourceNode);
                    }

                    // Store doc in response.
                    this.hits.add(doc.toString());
                }
            }
        }

        // Stop traversing if we've reached max depth.
        boolean maxDepthReached = this.maxHops > -1 && this.hop >= this.maxHops;
        if (maxDepthReached)
            return;

        // Update input attributes for the next queries.
        for (String attributeName : nextInputAttributes.keySet()) {
            if (!this.attributes.containsKey(attributeName)) {
                String attributeType = this.input.model().attributes().get(attributeName).type();
                this.attributes.put(attributeName, new Attribute(attributeName, attributeType));
            }
            for (Value value : nextInputAttributes.get(attributeName).values()) {
                if (!this.attributes.get(attributeName).values().contains(value)) {
                    this.attributes.get(attributeName).values().add(value);
                    newHits = true;
                }
            }
        }

        // Stop traversing if there are no more attributes to query.
        if (!newHits)
            return;

        // Update hop count and traverse.
        this.hop++;
        this.traverse();
    }

    /**
     * Run the entity resolution job.
     *
     * @return A JSON string to be returned as the body of the response to a client.
     * @throws IOException
     */
    public String run() throws IOException, ValidationException {
        try {

            // Reset the state of the job if reusing this Job object.
            if (this.ran)
                this.resetState();
            else
                this.attributes = new TreeMap<>(this.input.attributes());

            // Start timer and begin job
            long startTime = System.nanoTime();
            this.traverse();
            long took = TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

            // Format response
            List<String> responseParts = new ArrayList<>();
            responseParts.add("\"took\":" + Long.toString(took));
            if (this.includeHits)
                responseParts.add("\"hits\":{\"total\":" + this.hits.size() + ",\"hits\":[" + String.join(",", this.hits) + "]}");
            if (this.includeQueries || this.profile)
                responseParts.add("\"queries\":[" + queries + "]");
            String response = "{" + String.join(",", responseParts) + "}";
            if (this.pretty)
                response = Json.ORDERED_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Json.ORDERED_MAPPER.readTree(response));
            return response;

        } finally {
            this.ran = true;
        }
    }

}
