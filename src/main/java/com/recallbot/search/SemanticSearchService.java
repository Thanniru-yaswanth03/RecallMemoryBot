package com.recallbot.search;

import com.recallbot.search.dto.SearchHit;
import java.util.List;

/**
 * Service for semantic vector similarity retrieval of messages within a group boundary.
 */
public interface SemanticSearchService {

    /**
     * Performs semantic similarity search for a query string within a group, returning up to {@code topK} hits.
     *
     * @param groupId   Mandatory group ID for tenant isolation
     * @param queryText Query string to embed and search
     * @param topK      Maximum number of results to return
     * @return Ordered list of matching hits by cosine distance ascending (closest first)
     */
    List<SearchHit> search(Long groupId, String queryText, int topK);

    /**
     * Performs semantic similarity search for a query string within a group, returning default top-K hits.
     *
     * @param groupId   Mandatory group ID for tenant isolation
     * @param queryText Query string to embed and search
     * @return Ordered list of matching hits by cosine distance ascending (closest first)
     */
    List<SearchHit> search(Long groupId, String queryText);

    /**
     * Performs vector similarity search for an existing dense query vector within a group.
     *
     * @param groupId     Mandatory group ID for tenant isolation
     * @param queryVector Dense vector float array
     * @param topK        Maximum number of results to return
     * @return Ordered list of matching hits by cosine distance ascending (closest first)
     */
    List<SearchHit> searchByVector(Long groupId, float[] queryVector, int topK);
}
